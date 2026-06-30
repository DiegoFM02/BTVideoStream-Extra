package com.example.btvideostream.server

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object YouTubeService {
    private const val TAG = "YouTubeService"
    private const val UA_BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private val trustAll = SSLContext.getInstance("TLS").also { ctx ->
        ctx.init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }), SecureRandom())
    }

    private fun openConn(urlStr: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection()
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = trustAll.socketFactory
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        return conn as HttpURLConnection
    }

    fun search(query: String, maxResults: Int = 15): List<VideoResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://www.youtube.com/results?search_query=$encoded&hl=es&gl=MX")
        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", UA_BROWSER)
                setRequestProperty("Accept-Language", "es-MX,es;q=0.9,en;q=0.8")
                connectTimeout = 10000
                readTimeout = 10000
            }
            if (conn.responseCode != 200) return emptyList()
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            val marker = "var ytInitialData = "
            val start = html.indexOf(marker).takeIf { it >= 0 } ?: return emptyList()
            val jsonStart = start + marker.length
            val jsonText = html.substring(jsonStart, findJsonEnd(html, jsonStart))
            val list = mutableListOf<VideoResult>()
            extractVideos(JSONObject(jsonText), list, maxResults)
            list
        } catch (e: Exception) {
            Log.e(TAG, "Search failed: ${e.message}")
            emptyList()
        }
    }

    private fun extractVideos(obj: Any, out: MutableList<VideoResult>, max: Int) {
        if (out.size >= max) return
        when (obj) {
            is JSONObject -> {
                if (obj.has("videoRenderer")) {
                    val vr = obj.getJSONObject("videoRenderer")
                    val id = vr.optString("videoId")
                    val title = vr.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                        ?: vr.optJSONObject("title")?.optString("simpleText") ?: ""
                    val channel = vr.optJSONObject("ownerText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                        ?: vr.optJSONObject("shortBylineText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
                    val thumb = vr.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.let {
                        if (it.length() > 0) it.getJSONObject(0).optString("url") else ""
                    } ?: ""
                    if (id.isNotEmpty() && title.isNotEmpty()) out.add(VideoResult(id, title, channel, "", thumb))
                } else {
                    val keys = obj.keys()
                    while (keys.hasNext()) extractVideos(obj.get(keys.next()), out, max)
                }
            }
            is JSONArray -> { for (i in 0 until obj.length()) extractVideos(obj.get(i), out, max) }
        }
    }

    fun getStreamUrl(videoId: String, preferLow: Boolean = true): StreamInfo? {
        val targetRes = if (preferLow) "360p" else "720p"

        // 1. Parseo directo de ytInitialPlayerResponse del HTML — sin llamadas secundarias a Innertube
        extractFromWatchPage(videoId, targetRes)?.let { return it }

        // 2. Cobalt API como respaldo (si en algún momento vuelve a estar disponible sin auth)
        try {
            val conn = openConn("https://api.cobalt.tools/").apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", UA_BROWSER)
                doOutput = true
                connectTimeout = 10000
                readTimeout = 15000
            }
            val body = JSONObject().apply {
                put("url", "https://www.youtube.com/watch?v=$videoId")
                put("videoQuality", if (preferLow) "360" else "720")
                put("filenameStyle", "basic")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode == 200) {
                val res = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val status = res.optString("status")
                val url = res.optString("url")
                Log.d(TAG, "Cobalt → status=$status")
                if (url.isNotEmpty() && status != "error") {
                    return StreamInfo(url, targetRes, "video/mp4", "Video", 0L)
                }
            } else {
                Log.w(TAG, "Cobalt HTTP ${conn.responseCode}: ${conn.errorStream?.bufferedReader()?.readText()?.take(150)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cobalt falló: ${e.message}")
        }

        // 3. Invidious como último recurso
        for (api in listOf("https://iv.datura.network", "https://invidious.privacyredirect.com", "https://yewtu.be")) {
            try {
                // local=true hace que Invidious proxee el video a través de su servidor,
                // evitando el IP binding del CDN de YouTube
                val conn = (URL("$api/api/v1/videos/$videoId?fields=formatStreams,title&local=true").openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", UA_BROWSER)
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 8000
                    readTimeout = 12000
                }
                if (conn.responseCode != 200) { Log.w(TAG, "Invidious $api HTTP ${conn.responseCode}"); continue }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                if (!text.startsWith("{")) { Log.w(TAG, "Invidious $api no-JSON"); continue }
                val json = JSONObject(text)
                val title = json.optString("title", "Video")
                val streams = json.optJSONArray("formatStreams") ?: continue
                for (i in 0 until streams.length()) {
                    val s = streams.getJSONObject(i)
                    if (s.optString("resolution") == targetRes) {
                        Log.d(TAG, "Invidious OK: $api @ $targetRes")
                        return StreamInfo(s.getString("url"), targetRes, "video/mp4", title, 0L)
                    }
                }
                if (streams.length() > 0) {
                    val s = streams.getJSONObject(0)
                    Log.d(TAG, "Invidious OK fallback: $api")
                    return StreamInfo(s.getString("url"), s.optString("quality"), "video/mp4", title, 0L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Invidious $api falló: ${e.message}")
            }
        }

        return null
    }

    private fun extractFromWatchPage(videoId: String, targetRes: String): StreamInfo? {
        return try {
            val conn = (URL("https://www.youtube.com/watch?v=$videoId").openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", UA_BROWSER)
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                // Cookie de consentimiento para evitar redirect de consent page
                setRequestProperty("Cookie", "CONSENT=YES+cb; VISITOR_INFO1_LIVE=fPQ4jCL6EiE; YSC=DwKYllHNwuw")
                connectTimeout = 12000
                readTimeout = 15000
                instanceFollowRedirects = true
            }
            if (conn.responseCode != 200) {
                Log.w(TAG, "WatchPage HTTP ${conn.responseCode}")
                return null
            }
            // Capturar cookies de sesión que YouTube asigna (necesarias para el CDN)
            val sessionCookies = conn.headerFields["Set-Cookie"]
                ?.joinToString("; ") { it.substringBefore(";") }
                ?.ifEmpty { null }
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val marker = "var ytInitialPlayerResponse = "
            val start = html.indexOf(marker).takeIf { it >= 0 } ?: run {
                Log.w(TAG, "WatchPage: ytInitialPlayerResponse no encontrado en HTML")
                return null
            }
            val jsonStart = start + marker.length
            val playerResponse = JSONObject(html.substring(jsonStart, findJsonEnd(html, jsonStart)))
            val playStatus = playerResponse.optJSONObject("playabilityStatus")?.optString("status") ?: "?"
            val title = playerResponse.optJSONObject("videoDetails")?.optString("title", "Video") ?: "Video"
            val streamingData = playerResponse.optJSONObject("streamingData") ?: run {
                Log.w(TAG, "WatchPage: sin streamingData, playabilityStatus=$playStatus")
                return null
            }
            val formats = streamingData.optJSONArray("formats") ?: JSONArray()
            var bestUrl: String? = null
            var bestLabel = ""
            var cipherCount = 0
            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                val url = f.optString("url")
                if (url.isEmpty()) { cipherCount++; continue }
                val quality = f.optString("qualityLabel")
                if (quality == targetRes) {
                    Log.d(TAG, "WatchPage OK: $targetRes cookies=${sessionCookies?.take(40)}")
                    return StreamInfo(url, quality, "video/mp4", title, 0L, sessionCookies)
                }
                if (bestUrl == null) { bestUrl = url; bestLabel = quality }
            }
            if (bestUrl != null) {
                // Extraer y loguear la IP embebida en la URL firmada para diagnóstico
                val embeddedIp = Regex("[?&]ip=([^&]+)").find(bestUrl)?.groupValues?.get(1)
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                Log.d(TAG, "WatchPage OK: $bestLabel ip_embedded=${embeddedIp ?: "none"}")
                StreamInfo(bestUrl, bestLabel, "video/mp4", title, 0L, sessionCookies)
            } else {
                Log.w(TAG, "WatchPage: ${formats.length()} formatos, $cipherCount con signatureCipher. status=$playStatus")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "WatchPage falló: ${e.message}")
            null
        }
    }

    private fun findJsonEnd(text: String, start: Int): Int {
        var depth = 0; var inString = false; var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (!inString) {
                if (c == '{') depth++
                else if (c == '}') { depth--; if (depth == 0) return i + 1 }
            }
        }
        return text.length
    }
}
