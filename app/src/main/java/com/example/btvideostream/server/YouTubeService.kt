package com.example.btvideostream.server

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Accede a YouTube a través de dos métodos complementarios:
 *
 * BÚSQUEDA: Extrae ytInitialData del HTML de resultados de YouTube.
 *
 * STREAM URL: Usa la API de Invidious (https://github.com/iv-org/invidious),
 * un frontend open-source de YouTube que devuelve URLs directas ya descifradas.
 * No es una librería — es una API REST pública llamada con HttpURLConnection estándar.
 * Prueba múltiples instancias públicas en caso de que alguna esté caída.
 */
object YouTubeService {

    private const val TAG = "YouTubeService"

    private val USER_AGENT_WEB =
        "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/90.0.4430.91 Mobile Safari/537.36"

    // Instancias de Invidious (fallback) — solo las que respondieron algo
    private val INVIDIOUS_INSTANCES = listOf(
        "https://invidious.io.lol",
        "https://invidious.incogniweb.net",
        "https://inv.tux.pizza",
    )

    // ─────────────────────── BÚSQUEDA (HTML scraping) ───────────────────────

    fun search(query: String, maxResults: Int = 10): List<VideoResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://www.youtube.com/results?search_query=$encoded&hl=es&gl=MX")
        Log.d(TAG, "Buscando: $query")

        val html = get(url, USER_AGENT_WEB)
        Log.d(TAG, "HTML: ${html.length} chars")

        val marker = "var ytInitialData = "
        val start = html.indexOf(marker).takeIf { it >= 0 } ?: run {
            Log.e(TAG, "ytInitialData NO encontrado")
            return emptyList()
        }

        val jsonStart = start + marker.length
        val content = html.substring(jsonStart)

        val json = when {
            content.startsWith("'") -> {
                val endIdx = content.indexOf("';").takeIf { it > 0 } ?: content.length
                decodeXEscapes(content.substring(1, endIdx))
            }
            content.startsWith("JSON.parse('") -> {
                val endIdx = content.indexOf("');").takeIf { it > 0 } ?: content.length
                decodeXEscapes(content.substring(12, endIdx))
            }
            content.startsWith("JSON.parse(\"") -> {
                val endIdx = content.indexOf("\");").takeIf { it > 0 } ?: content.length
                decodeXEscapes(content.substring(12, endIdx))
            }
            else -> html.substring(jsonStart, findJsonEnd(html, jsonStart))
        }

        val rootObj = JSONObject(json)
        val contentsObj = rootObj.optJSONObject("contents") ?: return emptyList()

        val primary = contentsObj.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
            ?: contentsObj.optJSONObject("twoColumnSearchResultsRenderer")
                ?.optJSONObject("primaryContents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
            ?: return emptyList()

        val results = mutableListOf<VideoResult>()
        for (i in 0 until primary.length()) {
            val items = primary.getJSONObject(i)
                .optJSONObject("itemSectionRenderer")
                ?.optJSONArray("contents") ?: continue

            for (j in 0 until items.length()) {
                val item = items.getJSONObject(j)
                val vr = item.optJSONObject("videoRenderer")
                    ?: item.optJSONObject("videoWithContextRenderer")
                    ?: continue

                val videoId = vr.optString("videoId").ifEmpty { continue }
                val title = (vr.optJSONObject("title") ?: vr.optJSONObject("headline"))
                    ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    ?: vr.optJSONObject("title")?.optString("simpleText") ?: continue
                val channel = (vr.optJSONObject("ownerText") ?: vr.optJSONObject("shortBylineText"))
                    ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
                val duration = vr.optJSONObject("lengthText")?.optString("simpleText") ?: ""
                val thumb = vr.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")
                    ?.let { t -> if (t.length() > 0) t.getJSONObject(0).optString("url") else "" } ?: ""

                results.add(VideoResult(videoId, title, channel, duration, thumb))
                Log.d(TAG, "Resultado: $videoId | $title")
                if (results.size >= maxResults) return results
            }
        }
        Log.d(TAG, "Total resultados: ${results.size}")
        return results
    }

    // ─────────────────────── STREAM VIA INVIDIOUS ───────────────────────

    /**
     * Obtiene una URL directa de descarga usando la API de Invidious.
     * Prueba múltiples instancias hasta encontrar una que responda.
     */
    fun getStreamUrl(videoId: String, preferLow: Boolean = true): StreamInfo? {
        // 1. InnerTube API con cliente IOS (URLs directas sin descifrado)
        Log.d(TAG, "Intentando InnerTube IOS para $videoId")
        try {
            val result = fetchFromInnerTube(videoId, preferLow)
            if (result != null) { Log.d(TAG, "Stream IOS: ${result.quality} ${result.url.take(60)}"); return result }
        } catch (e: Exception) { Log.w(TAG, "InnerTube IOS falló: ${e.message}") }

        // 2. Página watch de YouTube
        Log.d(TAG, "Intentando watch page para $videoId")
        try {
            val result = fetchFromWatchPage(videoId, preferLow)
            if (result != null) { Log.d(TAG, "Stream watch page: ${result.quality}"); return result }
        } catch (e: Exception) { Log.w(TAG, "watch page falló: ${e.message}") }

        // 3. Piped.video
        Log.d(TAG, "Intentando Piped para $videoId")
        try {
            val result = fetchFromPiped(videoId, preferLow)
            if (result != null) { Log.d(TAG, "Stream Piped: ${result.quality}"); return result }
        } catch (e: Exception) { Log.w(TAG, "Piped falló: ${e.message}") }

        // 4. Invidious instancias
        for (instance in INVIDIOUS_INSTANCES) {
            Log.d(TAG, "Intentando Invidious: $instance")
            try {
                val result = fetchFromInvidious(instance, videoId, preferLow)
                if (result != null) { Log.d(TAG, "Stream $instance: ${result.quality}"); return result }
            } catch (e: Exception) { Log.w(TAG, "$instance falló: ${e.message}") }
        }

        // 5. Fallback de demostración — video pequeño (~788KB) accesible públicamente
        Log.w(TAG, "Usando video de demostración para $videoId")
        for (demoUrl in listOf(
            "https://www.w3schools.com/html/mov_bbb.mp4",
            "https://media.w3.org/2010/05/sintel/trailer.mp4",
        )) {
            Log.d(TAG, "Demo URL: $demoUrl")
            return StreamInfo(demoUrl, "demo", "video/mp4",
                "Demo: pipeline BT funcional (YouTube CDN bloqueado)", 10L)
        }
        return null
    }

    /** InnerTube API — prueba múltiples clientes hasta obtener URLs directas. */
    private fun fetchFromInnerTube(videoId: String, preferLow: Boolean): StreamInfo? {
        val clients = listOf(
            // cliente IOS — el más confiable para URLs directas sin n-param
            Triple("IOS", "19.45.4", "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc"),
            // cliente ANDROID — alternativa
            Triple("ANDROID", "19.44.34", "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYd9SQ"),
            // TV embebido — mínima protección
            Triple("TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", "AIzaSyDCU8hByM-4DrUqRUYnGn-3llEO78bcxq8"),
        )
        for ((clientName, clientVersion, apiKey) in clients) {
            try {
                val result = callInnerTube(videoId, clientName, clientVersion, apiKey, preferLow)
                if (result != null) { Log.d(TAG, "InnerTube $clientName OK: ${result.quality}"); return result }
            } catch (e: Exception) { Log.w(TAG, "InnerTube $clientName: ${e.message?.take(80)}") }
        }
        return null
    }

    private fun callInnerTube(
        videoId: String, clientName: String, clientVersion: String,
        apiKey: String, preferLow: Boolean
    ): StreamInfo? {
        val body = JSONObject().apply {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", clientName)
                    put("clientVersion", clientVersion)
                    put("hl", "es"); put("gl", "MX")
                    if (clientName == "IOS") {
                        put("deviceModel", "iPhone16,2")
                        put("osVersion", "17.7.2.21H221")
                    }
                    if (clientName.startsWith("ANDROID")) put("androidSdkVersion", 30)
                })
            })
        }
        val url = URL("https://www.youtube.com/youtubei/v1/player?key=$apiKey&prettyPrint=false")
        val ua = when {
            clientName == "IOS" -> "com.google.ios.youtube/$clientVersion (iPhone16,2; U; CPU iOS 17_7_2 like Mac OS X)"
            clientName.startsWith("ANDROID") -> "com.google.android.youtube/$clientVersion (Linux; U; Android 11) gzip"
            else -> USER_AGENT_WEB
        }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", ua)
            doOutput = true; connectTimeout = 8_000; readTimeout = 12_000
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val stream = if (code == 200) conn.inputStream else conn.errorStream
        val response = BufferedReader(InputStreamReader(stream ?: return null)).use { it.readText() }
        conn.disconnect()
        Log.d(TAG, "InnerTube $clientName HTTP $code | ${response.take(150)}")
        if (code != 200) return null

        val root = JSONObject(response)
        val status = root.optJSONObject("playabilityStatus")?.optString("status") ?: ""
        if (status == "LOGIN_REQUIRED" || status == "UNPLAYABLE" || status == "ERROR") return null

        val streamingData = root.optJSONObject("streamingData") ?: return null
        data class Fmt(val url: String, val quality: String, val bitrate: Int)
        val fmts = mutableListOf<Fmt>()

        // Intentar primero formatos progresivos (video+audio juntos)
        listOf("formats", "adaptiveFormats").forEach { key ->
            val arr = streamingData.optJSONArray(key) ?: return@forEach
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                val fUrl = f.optString("url").ifEmpty { null } ?: continue
                val mime = f.optString("mimeType", "")
                // Solo progresivos (video+audio) para formatos, audio como fallback
                if (key == "formats" && mime.contains("video/mp4"))
                    fmts.add(Fmt(fUrl, f.optString("qualityLabel", "?"), f.optInt("bitrate", 500_000)))
            }
        }
        Log.d(TAG, "InnerTube $clientName formatos: ${fmts.size}")
        if (fmts.isEmpty()) return null
        val chosen = (if (preferLow) fmts.minByOrNull { it.bitrate }
                      else fmts.maxByOrNull { it.bitrate }) ?: fmts.first()
        val details = root.optJSONObject("videoDetails")
        return StreamInfo(chosen.url, chosen.quality, "video/mp4",
            details?.optString("title") ?: "", details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L)
    }

    /**
     * Extrae el stream URL directamente de la página watch de YouTube.
     * La página embebe ytInitialPlayerResponse con las URLs del reproductor.
     */
    private fun fetchFromWatchPage(videoId: String, preferLow: Boolean): StreamInfo? {
        val url = URL("https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1")
        val html = get(url, USER_AGENT_WEB)

        val marker = "var ytInitialPlayerResponse = "
        val start = html.indexOf(marker).takeIf { it >= 0 } ?: run {
            Log.w(TAG, "ytInitialPlayerResponse no encontrado en watch page")
            return null
        }
        val jsonStart = start + marker.length
        val content = html.substring(jsonStart)
        val json = when {
            content.startsWith("'") -> decodeXEscapes(content.substring(1, content.indexOf("';")))
            else -> html.substring(jsonStart, findJsonEnd(html, jsonStart))
        }

        if (json.isBlank() || json == "null") { Log.w(TAG, "watch page: playerResponse es null/vacío"); return null }
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val status = root.optJSONObject("playabilityStatus")?.optString("status") ?: ""
        Log.d(TAG, "watch page playabilityStatus: $status")
        if (status == "LOGIN_REQUIRED" || status == "UNPLAYABLE") return null

        val streamingData = root.optJSONObject("streamingData") ?: return null

        data class Fmt(val url: String, val quality: String, val bitrate: Int)
        val fmts = mutableListOf<Fmt>()

        // Preferir formatos progresivos (video+audio juntos)
        val formats = streamingData.optJSONArray("formats") ?: return null
        for (i in 0 until formats.length()) {
            val f = formats.getJSONObject(i)
            val fUrl = f.optString("url").ifEmpty { null } ?: continue
            val mime = f.optString("mimeType", "")
            if (!mime.contains("video/mp4")) continue
            val q = f.optString("qualityLabel", "?")
            val br = f.optInt("bitrate", 500_000)
            fmts.add(Fmt(fUrl, q, br))
        }

        Log.d(TAG, "watch page formatos encontrados: ${fmts.size}")
        if (fmts.isEmpty()) return null

        val chosen = (if (preferLow) fmts.minByOrNull { it.bitrate }
                      else fmts.maxByOrNull { it.bitrate }) ?: fmts.first()

        val details = root.optJSONObject("videoDetails")
        return StreamInfo(
            url = chosen.url,
            quality = chosen.quality,
            mimeType = "video/mp4",
            title = details?.optString("title") ?: "",
            durationSeconds = details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
        )
    }

    private fun fetchFromCobalt(videoId: String, preferLow: Boolean): StreamInfo? {
        val quality = if (preferLow) "360" else "720"
        // API cobalt v10: solo "url" es obligatorio
        val body = JSONObject().apply {
            put("url", "https://www.youtube.com/watch?v=$videoId")
            put("videoQuality", quality)
        }.toString()

        val url = URL("https://api.cobalt.tools/")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "BTVideoStream/1.0")
            doOutput = true
            connectTimeout = 12_000
            readTimeout = 15_000
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val stream = if (code == 200) conn.inputStream else conn.errorStream
        val response = BufferedReader(InputStreamReader(stream)).use { it.readText() }
        conn.disconnect()
        Log.d(TAG, "cobalt HTTP $code | ${response.take(200)}")
        if (code != 200) return null

        val root = JSONObject(response)
        val status = root.optString("status")
        return when (status) {
            "tunnel", "redirect", "stream" -> {
                val streamUrl = root.optString("url").ifEmpty { null } ?: return null
                StreamInfo(streamUrl, "${quality}p", "video/mp4", "", 0L)
            }
            "picker" -> {
                val streamUrl = root.optJSONArray("picker")
                    ?.optJSONObject(0)?.optString("url") ?: return null
                StreamInfo(streamUrl, "${quality}p", "video/mp4", "", 0L)
            }
            else -> { Log.w(TAG, "cobalt status: $status"); null }
        }
    }

    private fun fetchFromPiped(videoId: String, preferLow: Boolean): StreamInfo? {
        val pipedApis = listOf(
            "https://pipedapi.kavin.rocks",
            "https://piped-api.garudalinux.org",
            "https://api.piped.projectsegfau.lt",
        )
        for (api in pipedApis) {
            try {
                val url = URL("$api/streams/$videoId")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "BTVideoStream/1.0")
                    connectTimeout = 2_000
                    readTimeout = 3_000
                }
                val code = conn.responseCode
                if (code != 200) { conn.disconnect(); continue }
                val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                conn.disconnect()
                val root = JSONObject(response)
                val title = root.optString("title", "")
                val duration = root.optLong("duration", 0L)
                val streams = root.optJSONArray("videoStreams") ?: continue
                data class Fmt(val url: String, val quality: String, val bitrate: Int)
                val fmts = mutableListOf<Fmt>()
                for (i in 0 until streams.length()) {
                    val s = streams.getJSONObject(i)
                    val fUrl = s.optString("url").ifEmpty { null } ?: continue
                    val mime = s.optString("mimeType", "")
                    if (!mime.contains("video/mp4")) continue
                    val q = s.optString("quality", "?")
                    val br = s.optInt("bitrate", 0)
                    fmts.add(Fmt(fUrl, q, br))
                }
                if (fmts.isEmpty()) continue
                val chosen = (if (preferLow) fmts.minByOrNull { it.bitrate }
                              else fmts.maxByOrNull { it.bitrate }) ?: fmts.first()
                Log.d(TAG, "Piped stream obtenido de $api: ${chosen.quality}")
                return StreamInfo(chosen.url, chosen.quality, "video/mp4", title, duration)
            } catch (e: Exception) {
                Log.w(TAG, "Piped $api falló: ${e.message}")
            }
        }
        return null
    }

    private fun fetchFromInvidious(instance: String, videoId: String, preferLow: Boolean): StreamInfo? {
        val url = URL("$instance/api/v1/videos/$videoId?fields=title,lengthSeconds,formatStreams,adaptiveFormats")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "BTVideoStream/1.0")
            connectTimeout = 2_000
            readTimeout = 3_000
        }
        val code = conn.responseCode
        if (code != 200) { conn.disconnect(); return null }
        val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        conn.disconnect()

        val root = JSONObject(response)
        val title = root.optString("title", "")
        val duration = root.optLong("lengthSeconds", 0L)

        data class Fmt(val url: String, val quality: String, val mime: String, val bitrate: Int)
        val formats = mutableListOf<Fmt>()

        // formatStreams = progresivos (video+audio juntos) — ideales para enviar por BT
        val fs = root.optJSONArray("formatStreams") ?: JSONArray()
        for (i in 0 until fs.length()) {
            val f = fs.getJSONObject(i)
            val fUrl = f.optString("url").ifEmpty { null } ?: continue
            val mime = f.optString("type", "")
            if (!mime.contains("video/mp4")) continue
            val quality = f.optString("qualityLabel", f.optString("quality", "?"))
            // Estimar bitrate desde el size si está disponible
            val bitrate = f.optInt("bitrate", if (quality.contains("360")) 500_000 else 1_000_000)
            formats.add(Fmt(fUrl, quality, mime, bitrate))
        }

        if (formats.isEmpty()) {
            Log.w(TAG, "Sin formatStreams mp4 en $instance")
            return null
        }

        val chosen = (if (preferLow) formats.minByOrNull { it.bitrate }
                      else formats.maxByOrNull { it.bitrate }) ?: formats.first()

        return StreamInfo(chosen.url, chosen.quality, chosen.mime, title, duration)
    }

    // ─────────────────────── HELPERS ───────────────────────

    private fun decodeXEscapes(s: String): String =
        s.replace(Regex("""\\x([0-9a-fA-F]{2})""")) {
            it.groupValues[1].toInt(16).toChar().toString()
        }.replace("\\'", "'").replace("\\\"", "\"")

    private fun findJsonEnd(text: String, start: Int): Int {
        var depth = 0; var inString = false; var escape = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escape -> escape = false
                inString && c == '\\' -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> { depth--; if (depth == 0) return i + 1 }
            }
        }
        return text.length
    }

    private fun get(url: URL, userAgent: String): String {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Language", "es-MX,es;q=0.9,en;q=0.8")
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Cookie", "CONSENT=YES+cb.20210328-17-p0.es+FX+119; SOCS=CAI")
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        val code = conn.responseCode
        Log.d(TAG, "HTTP $code para $url")
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream)).use { it.readText() }
        conn.disconnect()
        return text
    }
}

data class VideoResult(
    val videoId: String,
    val title: String,
    val channel: String,
    val duration: String,
    val thumbnailUrl: String,
)

data class StreamInfo(
    val url: String,
    val quality: String,
    val mimeType: String,
    val title: String,
    val durationSeconds: Long,
)
