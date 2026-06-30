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

    private val IOS_UA = "com.google.ios.youtube/19.45.4 (iPhone14,3; U; CPU iOS 15_6 like Mac OS X)"
    private val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    init {
        // Forzar IPv4 para que el token IP del stream de YouTube coincida con la descarga.
        // Las privacy extensions de IPv6 en Android rotan la dirección temporal entre
        // peticiones causando 403 en el CDN de googlevideo.com
        System.setProperty("java.net.preferIPv4Stack", "true")
        System.setProperty("java.net.preferIPv6Addresses", "false")
        // Diagnóstico: loguear IPs del dispositivo para comparar con ip= del CDN
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            val addrs = ifaces.flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress }
                .map { it.hostAddress }
            Log.d(TAG, "Device IPs: $addrs")
        } catch (e: Exception) { /* ignorar */ }
    }

    suspend fun handleSearch(query: String, source: String = "yt") = withContext(Dispatchers.IO) {
        try {
            bt.send(Message.Status("Buscando \"$query\" en $source…"))
            when (source) {
                "tt" -> handleTikTokSearch(query)
                "web" -> handleWebSearch(query)
                else -> handleYouTubeSearch(query)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en búsqueda", e)
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
                put("title", r.title)
                put("channel", r.domain)
                put("duration", r.snippet)
                put("url", r.url)
            }) }
        }.toString()))
    }

    suspend fun handleVideoRequest(videoId: String, quality: VideoQuality) =
        withContext(Dispatchers.IO) {
            try {
                val preferLow = quality == VideoQuality.LOW || quality == VideoQuality.AUDIO_ONLY
                val cacheFile = File(cacheDir, "$videoId.mp4")

                if (cacheFile.exists() && cacheFile.length() > 50000) {
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
                val bytes = downloadUrl(streamInfo.url, streamInfo.cookies)
                
                if (bytes.size < 1000) {
                    bt.send(Message.VideoError("Error: Video descargado corrupto o vacío"))
                    return@withContext
                }

                cacheFile.writeBytes(bytes)
                bt.send(Message.Status("Enviando por Bluetooth (${(bytes.size/1024)} KB)…"))
                bt.sendVideoData(bytes)
            } catch (e: Exception) {
                Log.e(TAG, "Error handleVideoRequest", e)
                bt.send(Message.VideoError("Error al descargar: ${e.javaClass.simpleName}"))
            }
        }

    fun downloadUrl(urlStr: String, cookies: String? = null): ByteArray {
        val isYouTube = urlStr.contains("googlevideo.com")
        // El parámetro n requiere transformación JS para el CDN; quitarlo evita el 403
        // (YouTube throttlea la velocidad pero sirve el contenido)
        val finalUrl = if (isYouTube) urlStr.replace(Regex("&n=[^&]+"), "") else urlStr
        val conn = (URL(finalUrl).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", if (isYouTube) BROWSER_UA else IOS_UA)
            if (isYouTube) {
                setRequestProperty("Range", "bytes=0-")
                setRequestProperty("Referer", "https://www.youtube.com/")
                setRequestProperty("Origin", "https://www.youtube.com")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
                if (!cookies.isNullOrEmpty()) setRequestProperty("Cookie", cookies)
            }
            connectTimeout = 30000
            readTimeout = 120000
            instanceFollowRedirects = true
        }
        val code = conn.responseCode
        Log.d(TAG, "Download HTTP $code — ${finalUrl.take(80)}")
        if (code !in 200..206) {
            throw java.io.IOException("HTTP $code al descargar")
        }
        return conn.inputStream.use { it.readBytes() }
    }
}
