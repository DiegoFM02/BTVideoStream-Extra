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
 * Busca en TikTok scrapeando su página web e intenta obtener URLs directas de video.
 * El CDN de TikTok es más accesible que el de YouTube.
 */
object TikTokService {

    private const val TAG = "TikTokService"
    private val UA = "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/90.0.4430.91 Mobile Safari/537.36"

    fun search(query: String, maxResults: Int = 10): List<VideoResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        // Usar la API de sugerencias de TikTok para obtener videos
        val url = URL("https://www.tiktok.com/api/search/general/full/?keyword=$encoded&offset=0&count=$maxResults")
        Log.d(TAG, "Buscando en TikTok: $query")
        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", "https://www.tiktok.com/")
                connectTimeout = 8_000; readTimeout = 10_000
            }
            val code = conn.responseCode
            Log.d(TAG, "TikTok HTTP $code")
            if (code != 200) return fallbackSearch(query, maxResults)
            val json = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            conn.disconnect()
            parseTikTokResults(json, maxResults).ifEmpty { fallbackSearch(query, maxResults) }
        } catch (e: Exception) {
            Log.w(TAG, "TikTok API falló: ${e.message}, intentando scraping")
            fallbackSearch(query, maxResults)
        }
    }

    private fun parseTikTokResults(json: String, max: Int): List<VideoResult> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val items = root.optJSONArray("data") ?: return emptyList()
        val results = mutableListOf<VideoResult>()
        for (i in 0 until minOf(items.length(), max)) {
            val item = items.optJSONObject(i)?.optJSONObject("item") ?: continue
            val id = item.optString("id").ifEmpty { continue }
            val desc = item.optString("desc").ifEmpty { "TikTok video" }
            val author = item.optJSONObject("author")?.optString("nickname") ?: ""
            val duration = item.optJSONObject("video")?.optInt("duration", 0)?.let { "${it}s" } ?: ""
            val thumb = item.optJSONObject("video")?.optString("cover") ?: ""
            results.add(VideoResult("tt_$id", desc, "@$author", duration, thumb))
        }
        Log.d(TAG, "TikTok resultados: ${results.size}")
        return results
    }

    private fun fallbackSearch(query: String, max: Int): List<VideoResult> {
        // Resultados de ejemplo cuando la API falla — para demo
        return listOf(
            VideoResult("tt_demo1", "🎵 $query - Trending en TikTok", "@tiktok_creator", "0:15", ""),
            VideoResult("tt_demo2", "✨ $query viral 2026", "@popular_user", "0:30", ""),
            VideoResult("tt_demo3", "$query challenge #fyp", "@another_creator", "0:10", ""),
        ).take(max)
    }

    // Video demo de TikTok — distinto al de YouTube para que la demo se vea diferente
    private const val TIKTOK_DEMO_URL = "https://media.w3.org/2010/05/sintel/trailer.mp4"

    /** Obtiene la URL directa de un video de TikTok. Siempre devuelve algo (demo si falla). */
    fun getVideoUrl(videoId: String): StreamInfo {
        val realId = videoId.removePrefix("tt_")

        if (!realId.startsWith("demo")) {
            Log.d(TAG, "Obteniendo URL TikTok para $realId")
            tryRealTikTok(realId)?.let { return it }
        }

        // Fallback demo — video corto diferente al de YouTube
        Log.w(TAG, "Usando video demo de TikTok")
        return StreamInfo(
            url = TIKTOK_DEMO_URL,
            quality = "TikTok-demo",
            mimeType = "video/mp4",
            title = "TikTok Demo (CDN de TikTok requiere firma, se usa video de muestra)",
            durationSeconds = 52L
        )
    }

    private fun tryRealTikTok(awemeId: String): StreamInfo? {
        return try {
            val url = URL("https://api16-normal-c-useast1a.tiktokv.com/aweme/v1/feed/?aweme_id=$awemeId&version_code=262036&app_name=musical_ly")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "TikTok 26.2.0 rv:262016 (iPhone; iOS 14.4.2; en_US) Cronet")
                connectTimeout = 5_000; readTimeout = 6_000
            }
            val code = conn.responseCode
            if (code != 200) { conn.disconnect(); return null }
            val json = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            conn.disconnect()

            val aweme = JSONObject(json).optJSONArray("aweme_list")?.optJSONObject(0) ?: return null
            val video = aweme.optJSONObject("video") ?: return null
            val playUrl = video.optJSONObject("play_addr")?.optJSONArray("url_list")?.optString(0)
                ?: video.optJSONObject("download_addr")?.optJSONArray("url_list")?.optString(0)
                ?: return null
            Log.d(TAG, "TikTok URL real obtenida: ${playUrl.take(80)}")
            StreamInfo(playUrl, "TikTok", "video/mp4", aweme.optString("desc", "TikTok"), 0L)
        } catch (e: Exception) {
            Log.w(TAG, "TikTok real falló: ${e.message}")
            null
        }
    }
}
