package com.example.btvideostream.server

import android.content.Context
import android.util.Log
import com.example.btvideostream.bluetooth.BluetoothManager
import com.example.btvideostream.bluetooth.protocol.Message
import com.example.btvideostream.bluetooth.protocol.VideoQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class VideoRepository(private val context: Context, private val bt: BluetoothManager) {

    private val cacheDir = File(context.cacheDir, "video_cache").also { it.mkdirs() }
    private val TAG = "VideoRepository"

    private val IOS_UA =
        "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_1 like Mac OS X)"
    private val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    private val ANDROID_UA =
        "com.google.android.youtube/19.44.38 (Linux; U; Android 11; sdk_gphone_x86 Build/RSR1.210722.013) gzip"
    private val ANDROID_VR_UA =
        "com.google.android.apps.youtube.vr.oculus/1.57.29 (Linux; U; Android 12L) gzip"

    init {
        // Forzar IPv4: el parámetro ip= del CDN de YouTube queda vinculado a la IPv4
        // de la petición Innertube; las privacy extensions de IPv6 rotan la dirección
        // y provocan 403 en el CDN.
        System.setProperty("java.net.preferIPv4Stack", "true")
        System.setProperty("java.net.preferIPv6Addresses", "false")
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            val addrs = ifaces.flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress }.map { it.hostAddress }
            Log.d(TAG, "Device IPs: $addrs")
        } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  BÚSQUEDAS
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun handleSearch(query: String, source: String = "yt") = withContext(Dispatchers.IO) {
        try {
            bt.send(Message.Status("Buscando \"$query\" en $source…"))
            when (source) {
                "tt"  -> handleTikTokSearch(query)
                "web" -> handleWebSearch(query)
                else  -> handleYouTubeSearch(query)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error búsqueda", e)
            bt.send(Message.VideoError("Error en búsqueda: ${e.message}"))
        }
    }

    private fun handleYouTubeSearch(query: String) {
        val results = YouTubeService.search(query)
        if (results.isEmpty()) {
            bt.send(Message.VideoError("YouTube no devolvió resultados para \"$query\""))
            return
        }
        bt.send(Message.SearchResults(JSONArray().apply {
            results.forEach { r -> put(JSONObject().apply {
                put("id", r.videoId); put("title", r.title)
                put("channel", r.channel); put("duration", r.duration); put("thumb", r.thumbnailUrl)
            }) }
        }.toString()))
    }

    private fun handleTikTokSearch(query: String) {
        val results = TikTokService.search(query)
        if (results.isEmpty()) {
            bt.send(Message.VideoError("TikTok no devolvió resultados para \"$query\""))
            return
        }
        bt.send(Message.SearchResults(JSONArray().apply {
            results.forEach { r -> put(JSONObject().apply {
                put("id", r.videoId); put("title", r.title)
                put("channel", r.channel); put("duration", r.duration); put("thumb", r.thumbnailUrl)
                put("source", "tt")
            }) }
        }.toString()))
    }

    private fun handleWebSearch(query: String) {
        val results = GoogleService.search(query)
        if (results.isEmpty()) { bt.send(Message.VideoError("Sin resultados web")); return }
        bt.send(Message.SearchResults(JSONArray().apply {
            results.forEach { r -> put(JSONObject().apply {
                put("id", "web_${r.url.hashCode()}")
                put("title", r.title); put("channel", r.domain)
                put("duration", r.snippet); put("url", r.url)
            }) }
        }.toString()))
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DESCARGA Y ENVÍO DE VIDEO
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun handleVideoRequest(videoId: String, quality: VideoQuality) =
        withContext(Dispatchers.IO) {
            try {
                val preferLow = quality == VideoQuality.LOW || quality == VideoQuality.AUDIO_ONLY
                val cacheFile = File(cacheDir, "$videoId.mp4")

                if (cacheFile.exists() && cacheFile.length() > 50_000) {
                    bt.send(Message.Status("Caché: enviando $videoId…"))
                    bt.sendVideoData(cacheFile.readBytes())
                    return@withContext
                }

                bt.send(Message.Status("Obteniendo enlace de stream…"))
                val streamInfo = when {
                    videoId.startsWith("tt_") -> TikTokService.getVideoUrl(videoId)
                    else -> YouTubeService.getStreamUrl(videoId, preferLow)
                }

                if (streamInfo == null) {
                    bt.send(Message.VideoError("No se pudo extraer el video (Servidores ocupados)"))
                    return@withContext
                }

                bt.send(Message.Status("Descargando: ${streamInfo.title.take(20)}…"))

                val bytes = try {
                    downloadUrl(streamInfo.url, streamInfo.cookies)
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "Download primario falló: ${e.message}")
                    // Si el CDN de YouTube rechaza con 403, intentar via proxy Invidious
                    if (e.message?.contains("403") == true && !videoId.startsWith("tt_")) {
                        bt.send(Message.Status("CDN rechazó (403), intentando proxy…"))
                        val targetRes = if (preferLow) "360p" else "720p"
                        downloadViaInvidiousProxy(videoId, targetRes)
                            ?: run {
                                bt.send(Message.VideoError("HTTP 403: CDN y proxies rechazaron el video. Prueba con otro."))
                                return@withContext
                            }
                    } else {
                        bt.send(Message.VideoError("Error al descargar: ${e.message}"))
                        return@withContext
                    }
                }

                if (bytes.size < 1_000) {
                    bt.send(Message.VideoError("Error: video descargado vacío o corrupto"))
                    return@withContext
                }

                cacheFile.writeBytes(bytes)
                bt.send(Message.Status("Enviando por Bluetooth (${bytes.size / 1024} KB)…"))
                bt.sendVideoData(bytes)
            } catch (e: Exception) {
                Log.e(TAG, "Error handleVideoRequest", e)
                bt.send(Message.VideoError("Error al descargar: ${e.message ?: e.javaClass.simpleName}"))
            }
        }

    // ══════════════════════════════════════════════════════════════════════════
    //  DESCARGA DIRECTA
    // ══════════════════════════════════════════════════════════════════════════

    fun downloadUrl(urlStr: String, cookies: String? = null): ByteArray {
        val isYouTube = urlStr.contains("googlevideo.com")

        // Detectar qué cliente Innertube generó la URL y usar el mismo UA para descarga.
        // El CDN valida la coherencia del cliente; un UA distinto puede provocar 403.
        val clientParam = Regex("[?&]c=([^&]+)").find(urlStr)?.groupValues?.get(1) ?: ""
        val isAppClient = clientParam.startsWith("ANDROID") || clientParam == "IOS"

        // Solo eliminar &n= en URLs de cliente web (sin ratebypass).
        // Los clientes Android/VR incluyen ratebypass=yes — no tocar.
        val finalUrl = if (isYouTube && !isAppClient) {
            urlStr
                .replace(Regex("&n=[^&]+"), "")
                .replace(Regex("\\?n=[^&]+&"), "?")
                .replace(Regex("\\?n=[^&]+$"), "")
        } else {
            urlStr
        }

        val ua = when {
            clientParam.startsWith("ANDROID_VR")  -> ANDROID_VR_UA
            clientParam.startsWith("ANDROID")      -> ANDROID_UA
            clientParam == "IOS"                   -> IOS_UA
            isYouTube                              -> BROWSER_UA
            urlStr.contains("tikwm.com")           -> IOS_UA
            else                                   -> BROWSER_UA
        }

        val conn = (URL(finalUrl).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", ua)
            if (isYouTube) {
                setRequestProperty("Referer", "https://www.youtube.com/")
                setRequestProperty("Origin", "https://www.youtube.com")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
                // Range solo en cliente web — Android/iOS no lo necesitan
                if (!isAppClient) setRequestProperty("Range", "bytes=0-")
                if (!cookies.isNullOrEmpty()) setRequestProperty("Cookie", cookies)
            }
            connectTimeout = 30_000
            readTimeout = 180_000
            instanceFollowRedirects = true
        }

        val code = conn.responseCode
        Log.d(TAG, "Download HTTP $code client=${clientParam.ifEmpty { "web" }} — ${finalUrl.take(100)}")
        if (code !in 200..206) {
            val errSnippet = conn.errorStream?.bufferedReader()?.readText()?.take(120) ?: ""
            Log.e(TAG, "Download error: $errSnippet")
            throw java.io.IOException("HTTP $code al descargar")
        }
        return conn.inputStream.use { it.readBytes() }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  FALLBACK: DESCARGA VÍA PROXY INVIDIOUS (para errores 403 del CDN)
    // ══════════════════════════════════════════════════════════════════════════

    private fun downloadViaInvidiousProxy(videoId: String, targetRes: String): ByteArray? {
        for (api in YouTubeService.INVIDIOUS_INSTANCES) {
            try {
                // Obtener URL proxy de la instancia Invidious (local=true = el servidor Invidious
                // descarga de YouTube con su propia IP, evitando el IP binding del CDN)
                val apiConn = (URL("$api/api/v1/videos/$videoId?local=true")
                    .openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", BROWSER_UA)
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 10_000
                    readTimeout = 15_000
                }
                if (apiConn.responseCode != 200) {
                    Log.w(TAG, "InvidiusProxy API $api HTTP ${apiConn.responseCode}")
                    continue
                }
                val text = apiConn.inputStream.bufferedReader().use { it.readText() }
                if (!text.startsWith("{")) continue
                val streams = JSONObject(text).optJSONArray("formatStreams") ?: continue

                var proxyUrl: String? = null
                for (i in 0 until streams.length()) {
                    val s = streams.getJSONObject(i)
                    val url = s.optString("url")
                    if (url.isEmpty()) continue
                    if (s.optString("resolution") == targetRes) { proxyUrl = url; break }
                    if (proxyUrl == null) proxyUrl = url
                }
                if (proxyUrl == null) continue

                Log.d(TAG, "403 fallback: descargando via proxy $api")
                val dlConn = (URL(proxyUrl).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", BROWSER_UA)
                    setRequestProperty("Referer", "$api/")
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Encoding", "identity")
                    connectTimeout = 30_000
                    readTimeout = 180_000
                    instanceFollowRedirects = true
                }
                val code = dlConn.responseCode
                Log.d(TAG, "Proxy download HTTP $code")
                if (code !in 200..206) { Log.w(TAG, "Proxy $api download HTTP $code"); continue }
                return dlConn.inputStream.use { it.readBytes() }
            } catch (e: Exception) {
                Log.w(TAG, "InvidiusProxy $api: ${e.message}")
            }
        }
        return null
    }
}
