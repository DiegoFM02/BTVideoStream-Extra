package com.example.btvideostream.server

import android.util.Log
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

object TikTokService {
    private const val TAG = "TikTokService"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    // TikWM tiene cadena SSL incompleta; este TrustManager la acepta de todas formas
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
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val formBody = "keywords=$encoded&count=$maxResults&cursor=0&web=1&hd=1"
            val conn = openConn("https://www.tikwm.com/api/feed/search").apply {
                requestMethod = "POST"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Referer", "https://www.tikwm.com/")
                setRequestProperty("Origin", "https://www.tikwm.com")
                doOutput = true
                connectTimeout = 12000
                readTimeout = 12000
            }
            conn.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "TikWM raw: ${text.take(300)}")
                val json = JSONObject(text)
                val data = json.optJSONObject("data") ?: run {
                    Log.w(TAG, "TikWM: 'data' ausente. code=${json.optInt("code")} msg=${json.optString("msg")}")
                    return emptyList()
                }
                val videos = data.optJSONArray("videos") ?: run {
                    Log.w(TAG, "TikWM: 'videos' ausente")
                    return emptyList()
                }
                val list = mutableListOf<VideoResult>()
                for (i in 0 until minOf(videos.length(), maxResults)) {
                    val item = videos.optJSONObject(i) ?: continue
                    val id = item.optString("video_id").ifEmpty { item.optString("id") }
                    val title = item.optString("title").ifEmpty { "TikTok Video" }
                    val authorNick = item.optJSONObject("author")?.optString("nickname")
                        ?: item.optString("author").ifEmpty { "user" }
                    val cover = item.optString("cover").ifEmpty { item.optString("origin_cover") }
                    if (id.isNotEmpty()) list.add(VideoResult("tt_$id", title, "@$authorNick", "", cover))
                }
                list
            } else {
                Log.w(TAG, "TikWM search HTTP ${conn.responseCode}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "TikTok search failed: ${e.message}")
            emptyList()
        }
    }

    fun getVideoUrl(videoId: String): StreamInfo? {
        val id = videoId.removePrefix("tt_")
        val urls = listOf(
            "https://www.tikwm.com/api/?url=https://www.tiktok.com/@user/video/$id",
            "https://api.tikmate.app/api/lookup?url=https://www.tiktok.com/@user/video/$id"
        )
        for (apiUrl in urls) {
            try {
                val conn = openConn(apiUrl).apply {
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 12000
                    readTimeout = 12000
                }
                if (conn.responseCode == 200) {
                    val res = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val play = res.optJSONObject("data")?.optString("play") ?: res.optString("url")
                    if (!play.isNullOrEmpty()) {
                        val finalUrl = if (play.startsWith("http")) play else "https://www.tikwm.com$play"
                        return StreamInfo(finalUrl, "Real", "video/mp4", "TikTok", 0L)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TikTok getVideoUrl $apiUrl falló: ${e.message}")
            }
        }
        return null
    }
}
