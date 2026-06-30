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

    // User-Agent del cliente IOS que obtiene la URL (YouTube valida que coincidan)
    private val IOS_UA = "com.google.ios.youtube/19.45.4 (iPhone14,3; U; CPU iOS 15_6 like Mac OS X)"

    suspend fun handleSearch(query: String, source: String = "yt") = withContext(Dispatchers.IO) {
        try {
            when (source) {
                "tt" -> handleTikTokSearch(query)
                "web" -> handleWebSearch(query)
                else -> handleYouTubeSearch(query)
            }
        } catch (e: Exception) {
            bt.send(Message.VideoError("Error en búsqueda: ${e.javaClass.simpleName} - ${e.message}"))
        }
    }

    private fun handleYouTubeSearch(query: String) {
        val results = YouTubeService.search(query)
        if (results.isEmpty()) { bt.send(Message.VideoError("Sin resultados para \"$query\"")); return }
        bt.send(Message.SearchResults(JSONArray().apply {
            results.forEach { r -> put(JSONObject().apply {
                put("id", r.videoId); put("title", r.title)
                put("channel", r.channel); put("duration", r.duration); put("thumb", r.thumbnailUrl)
            }) }
        }.toString()))
    }

    private fun handleTikTokSearch(query: String) {
        val results = TikTokService.search(query)
        // TikTok siempre devuelve algo (resultados reales o fallback)
        bt.send(Message.SearchResults(JSONArray().apply {
            results.forEach { r -> put(JSONObject().apply {
                put("id", r.videoId); put("title", r.title)
                put("channel", r.channel); put("duration", r.duration); put("thumb", "")
                put("source", "tt")
            }) }
        }.toString()))
    }

    private fun handleWebSearch(query: String) {
        val results = GoogleService.search(query)
        if (results.isEmpty()) { bt.send(Message.VideoError("Sin resultados web para \"$query\"")); return }
        bt.send(Message.SearchResults(JSONArray().apply {
            results.forEach { r -> put(JSONObject().apply {
                put("id", "web_${r.url.hashCode()}")
                put("title", r.title)
                put("channel", r.domain.ifEmpty { r.url.take(40) })
                put("duration", r.snippet.take(80))
                put("thumb", "")
                put("source", "web")
                put("url", r.url)
            }) }
        }.toString()))
    }

    suspend fun handleVideoRequest(videoId: String, quality: VideoQuality) =
        withContext(Dispatchers.IO) {
            try {
                val preferLow = quality == VideoQuality.LOW || quality == VideoQuality.AUDIO_ONLY
                val cacheFile = File(cacheDir, "$videoId.mp4")

                val videoBytes = if (cacheFile.exists() && cacheFile.length() > 1024) {
                    Log.d(TAG, "Cache HIT: $videoId (${cacheFile.length()} bytes)")
                    bt.send(Message.Status("Enviando desde caché…"))
                    cacheFile.readBytes()
                } else {
                    val streamInfo = when {
                        videoId.startsWith("tt_") -> TikTokService.getVideoUrl(videoId)
                            ?: YouTubeService.getStreamUrl(videoId, preferLow)
                        videoId.startsWith("web_") -> null // no hay video para resultados web
                        else -> YouTubeService.getStreamUrl(videoId, preferLow)
                    } ?: run {
                        bt.send(Message.VideoError("No se encontró stream para $videoId"))
                        return@withContext
                    }
                    val isDemoFallback = streamInfo.quality == "demo"
                    Log.d(TAG, "Descargando: ${streamInfo.url.take(100)}")
                    bt.send(Message.Status(if (isDemoFallback)
                        "⚠ Usando video de demostración…"
                    else "Descargando ${streamInfo.quality}…"))
                    val bytes = downloadUrl(streamInfo.url)
                    Log.d(TAG, "Descarga completa: ${bytes.size} bytes")
                    cacheFile.writeBytes(bytes)
                    bytes
                }

                bt.send(Message.Status("Enviando por Bluetooth…"))
                bt.sendVideoData(videoBytes)
            } catch (e: Exception) {
                Log.e(TAG, "Error al obtener video", e)
                bt.send(Message.VideoError("Error al obtener video: ${e.javaClass.simpleName}: ${e.message?.take(100)}"))
            }
        }

    private fun downloadUrl(urlStr: String): ByteArray {
        val userAgents = listOf(
            IOS_UA,
            "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.91 Mobile Safari/537.36",
            "Mozilla/5.0"
        )
        for (ua in userAgents) {
            try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", ua)
                    setRequestProperty("Accept", "*/*")
                    connectTimeout = 20_000
                    readTimeout = 180_000
                }
                val code = conn.responseCode
                Log.d(TAG, "Download HTTP $code | UA: ${ua.take(50)}")
                if (code in 200..299) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    conn.disconnect()
                    Log.d(TAG, "Download OK: ${bytes.size} bytes")
                    return bytes
                }
                val errBody = conn.errorStream?.bufferedReader()?.readText()?.take(200) ?: ""
                Log.w(TAG, "Download $code | $errBody")
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Download excepción con UA ${ua.take(30)}: ${e.javaClass.simpleName}: ${e.message?.take(100)}")
            }
        }
        throw Exception("No se pudo descargar el video (${urlStr.take(60)})")
    }

    fun clearCache() = cacheDir.listFiles()?.forEach { it.delete() }
}
