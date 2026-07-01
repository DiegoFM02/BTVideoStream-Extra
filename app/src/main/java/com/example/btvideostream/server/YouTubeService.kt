package com.example.btvideostream.server

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object YouTubeService {
    private const val TAG = "YouTubeService"
    private const val UA_BROWSER =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    // ─── Clientes Innertube — orden de fiabilidad para 2025-2026 ──────────────
    private data class InnertubeClient(
        val name: String,
        val version: String,
        val id: String,
        val ua: String,
        val embedContext: Boolean = false
    )

    private val INNERTUBE_CLIENTS = listOf(
        // ANDROID_VR (Daydream): cliente de nicho, menos escrutinio bot-detection
        InnertubeClient(
            "ANDROID_VR", "1.57.29", "28",
            "com.google.android.apps.youtube.vr.oculus/1.57.29 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
        ),
        // ANDROID estándar — app oficial
        InnertubeClient(
            "ANDROID", "19.44.38", "3",
            "com.google.android.youtube/19.44.38 (Linux; U; Android 11; sdk_gphone_x86 Build/RSR1.210722.013) gzip"
        ),
        // iOS — app oficial Apple
        InnertubeClient(
            "IOS", "19.45.4", "5",
            "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_1 like Mac OS X)"
        ),
        // TV embedded — contexto de reproductor de terceros
        InnertubeClient(
            "TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", "85",
            "Mozilla/5.0 (SMART-TV; LINUX; Tizen 5.0) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/2.1 Chrome/56.0.2924.0 TV Safari/537.36",
            embedContext = true
        ),
        // ANDROID_TESTSUITE — cliente de pruebas interno
        InnertubeClient(
            "ANDROID_TESTSUITE", "1.9", "30",
            "com.google.android.youtube/1.9 (Linux; U; Android 11) gzip"
        ),
    )

    // ─── Instancias Invidious ─────────────────────────────────────────────────
    val INVIDIOUS_INSTANCES = listOf(
        "https://iv.datura.network",
        "https://invidious.privacyredirect.com",
        "https://invidious.nerdvpn.de",
        "https://inv.nadeko.net",
        "https://yewtu.be",
        "https://invidious.fdn.fr",
    )

    // ══════════════════════════════════════════════════════════════════════════
    //  VISITOR DATA — reduce la detección como bot en Innertube
    // ══════════════════════════════════════════════════════════════════════════

    private fun fetchVisitorData(): String? = try {
        val conn = (URL("https://www.youtube.com/").openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA_BROWSER)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            connectTimeout = 8000
            readTimeout = 10000
        }
        if (conn.responseCode == 200) {
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        } else null
    } catch (e: Exception) {
        Log.w(TAG, "fetchVisitorData: ${e.message}")
        null
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  BÚSQUEDA
    // ══════════════════════════════════════════════════════════════════════════

    fun search(query: String, maxResults: Int = 15): List<VideoResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return try {
            val conn = (URL("https://www.youtube.com/results?search_query=$encoded&hl=es&gl=MX")
                .openConnection() as HttpURLConnection).apply {
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
            val list = mutableListOf<VideoResult>()
            extractVideos(JSONObject(html.substring(jsonStart, findJsonEnd(html, jsonStart))), list, maxResults)
            list
        } catch (e: Exception) {
            Log.e(TAG, "search: ${e.message}")
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
                    val thumb = vr.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                        ?.let { if (it.length() > 0) it.getJSONObject(0).optString("url") else "" } ?: ""
                    if (id.isNotEmpty() && title.isNotEmpty()) out.add(VideoResult(id, title, channel, "", thumb))
                } else {
                    val keys = obj.keys()
                    while (keys.hasNext()) extractVideos(obj.get(keys.next()), out, max)
                }
            }
            is JSONArray -> { for (i in 0 until obj.length()) extractVideos(obj.get(i), out, max) }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  OBTENER STREAM
    // ══════════════════════════════════════════════════════════════════════════

    fun getStreamUrl(videoId: String, preferLow: Boolean = true): StreamInfo? {
        val targetRes = if (preferLow) "360p" else "720p"

        // 1. Innertube API con visitor_data + múltiples clientes (prioridad)
        val visitorData = fetchVisitorData()
        Log.d(TAG, "visitorData=${visitorData?.take(20) ?: "null"}")
        extractViaInnertube(videoId, targetRes, visitorData)?.let { return it }

        // 2. Parseo del HTML de /watch (funciona cuando los formatos no están ciphered)
        extractFromWatchPage(videoId, targetRes)?.let { return it }

        // 3. Invidious con proxy local — elude IP binding del CDN
        getViaInvidious(videoId, targetRes)?.let { return it }

        return null
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  INNERTUBE API
    // ══════════════════════════════════════════════════════════════════════════

    private fun extractViaInnertube(videoId: String, targetRes: String, visitorData: String?): StreamInfo? {
        for (client in INNERTUBE_CLIENTS) {
            try {
                val conn = (URL("https://www.youtube.com/youtubei/v1/player")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("User-Agent", client.ua)
                    setRequestProperty("X-YouTube-Client-Name", client.id)
                    setRequestProperty("X-YouTube-Client-Version", client.version)
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                    setRequestProperty("Origin", "https://www.youtube.com")
                    if (!visitorData.isNullOrEmpty()) {
                        setRequestProperty("X-Goog-Visitor-Id", visitorData)
                    }
                    connectTimeout = 12000
                    readTimeout = 20000
                    doOutput = true
                }

                val clientCtx = JSONObject().apply {
                    put("clientName", client.name)
                    put("clientVersion", client.version)
                    put("hl", "en")
                    put("gl", "US")
                    if (client.name.contains("ANDROID") || client.name == "IOS") {
                        put("androidSdkVersion", 30)
                    }
                    if (!visitorData.isNullOrEmpty()) {
                        put("visitorData", visitorData)
                    }
                }

                val ctxObj = JSONObject().apply {
                    put("client", clientCtx)
                    if (client.embedContext) {
                        put("thirdParty", JSONObject().apply {
                            put("embedUrl", "https://www.youtube.com/")
                        })
                    }
                }

                val body = JSONObject().apply {
                    put("videoId", videoId)
                    put("context", ctxObj)
                    put("racyCheckOk", true)
                    put("contentCheckOk", true)
                }

                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code != 200) { Log.w(TAG, "Innertube ${client.name} HTTP $code"); continue }

                val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                val status = json.optJSONObject("playabilityStatus")?.optString("status") ?: ""
                if (status != "OK") { Log.w(TAG, "Innertube ${client.name}: status=$status"); continue }

                val title = json.optJSONObject("videoDetails")?.optString("title", "Video") ?: "Video"
                val formats = json.optJSONObject("streamingData")?.optJSONArray("formats")
                    ?: run { Log.w(TAG, "Innertube ${client.name}: sin streamingData.formats"); continue }

                var bestUrl: String? = null
                var bestLabel = ""
                var ciphered = 0
                for (i in 0 until formats.length()) {
                    val f = formats.getJSONObject(i)
                    val url = f.optString("url")
                    if (url.isEmpty()) { ciphered++; continue }
                    val quality = f.optString("qualityLabel")
                    if (quality == targetRes) {
                        Log.d(TAG, "Innertube ${client.name} OK @ $targetRes")
                        return StreamInfo(url, quality, "video/mp4", title, 0L)
                    }
                    if (bestUrl == null) { bestUrl = url; bestLabel = quality }
                }
                if (bestUrl != null) {
                    Log.d(TAG, "Innertube ${client.name} OK fallback @ $bestLabel")
                    return StreamInfo(bestUrl, bestLabel, "video/mp4", title, 0L)
                }
                Log.w(TAG, "Innertube ${client.name}: ${formats.length()} formatos, $ciphered ciphered — sin URL directa")
            } catch (e: Exception) {
                Log.w(TAG, "Innertube ${client.name}: ${e.message}")
            }
        }
        return null
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  INVIDIOUS — proxy que elude el IP binding del CDN
    // ══════════════════════════════════════════════════════════════════════════

    fun getViaInvidious(videoId: String, targetRes: String = "360p"): StreamInfo? {
        for (api in INVIDIOUS_INSTANCES) {
            try {
                // Sin fields= para máxima compatibilidad con todas las instancias
                val conn = (URL("$api/api/v1/videos/$videoId?local=true")
                    .openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", UA_BROWSER)
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 10000
                    readTimeout = 15000
                }
                if (conn.responseCode != 200) { Log.w(TAG, "Invidious $api HTTP ${conn.responseCode}"); continue }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                if (!text.startsWith("{")) { Log.w(TAG, "Invidious $api respuesta no-JSON"); continue }
                val json = JSONObject(text)
                val title = json.optString("title", "Video")
                val streams = json.optJSONArray("formatStreams") ?: continue
                var bestUrl: String? = null
                var bestLabel = ""
                for (i in 0 until streams.length()) {
                    val s = streams.getJSONObject(i)
                    val url = s.optString("url")
                    if (url.isEmpty()) continue
                    val res = s.optString("resolution")
                    if (res == targetRes) {
                        Log.d(TAG, "Invidious OK: $api @ $targetRes")
                        return StreamInfo(url, targetRes, "video/mp4", title, 0L)
                    }
                    if (bestUrl == null) { bestUrl = url; bestLabel = res }
                }
                if (bestUrl != null) {
                    Log.d(TAG, "Invidious OK fallback: $api @ $bestLabel")
                    return StreamInfo(bestUrl, bestLabel, "video/mp4", title, 0L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Invidious $api: ${e.message}")
            }
        }
        return null
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PARSEO DEL HTML DE /watch
    // ══════════════════════════════════════════════════════════════════════════

    private fun extractFromWatchPage(videoId: String, targetRes: String): StreamInfo? {
        return try {
            val conn = (URL("https://www.youtube.com/watch?v=$videoId").openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", UA_BROWSER)
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Cookie", "CONSENT=YES+cb.20210328-17-0-0+FX+en; SOCS=CAESEwgDEgk4NTA4NzE5MzAaBXpoLUNO")
                connectTimeout = 12000
                readTimeout = 15000
                instanceFollowRedirects = true
            }
            if (conn.responseCode != 200) { Log.w(TAG, "WatchPage HTTP ${conn.responseCode}"); return null }
            val sessionCookies = conn.headerFields["Set-Cookie"]
                ?.joinToString("; ") { it.substringBefore(";") }?.ifEmpty { null }
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val marker = "var ytInitialPlayerResponse = "
            val start = html.indexOf(marker).takeIf { it >= 0 } ?: run {
                Log.w(TAG, "WatchPage: ytInitialPlayerResponse no encontrado"); return null
            }
            val jsonStart = start + marker.length
            val playerResponse = JSONObject(html.substring(jsonStart, findJsonEnd(html, jsonStart)))
            val playStatus = playerResponse.optJSONObject("playabilityStatus")?.optString("status") ?: "?"
            val title = playerResponse.optJSONObject("videoDetails")?.optString("title", "Video") ?: "Video"
            val streamingData = playerResponse.optJSONObject("streamingData") ?: run {
                Log.w(TAG, "WatchPage: sin streamingData, status=$playStatus"); return null
            }
            val formats = streamingData.optJSONArray("formats") ?: JSONArray()
            var bestUrl: String? = null; var bestLabel = ""; var ciphered = 0
            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                val url = f.optString("url")
                if (url.isEmpty()) { ciphered++; continue }
                val quality = f.optString("qualityLabel")
                if (quality == targetRes) {
                    Log.d(TAG, "WatchPage OK: $targetRes")
                    return StreamInfo(url, quality, "video/mp4", title, 0L, sessionCookies)
                }
                if (bestUrl == null) { bestUrl = url; bestLabel = quality }
            }
            if (bestUrl != null) {
                Log.d(TAG, "WatchPage OK fallback: $bestLabel")
                StreamInfo(bestUrl, bestLabel, "video/mp4", title, 0L, sessionCookies)
            } else {
                Log.w(TAG, "WatchPage: ${formats.length()} formatos, $ciphered ciphered, status=$playStatus")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "WatchPage: ${e.message}")
            null
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  UTILIDADES
    // ══════════════════════════════════════════════════════════════════════════

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
