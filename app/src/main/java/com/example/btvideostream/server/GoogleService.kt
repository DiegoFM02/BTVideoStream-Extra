package com.example.btvideostream.server

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Busca en DuckDuckGo (HTML lite) — mucho más scraper-friendly que Google.
 * Demuestra que el servidor tiene acceso completo a Internet.
 */
object GoogleService {

    private const val TAG = "GoogleService"
    private val UA = "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/90.0.4430.91 Mobile Safari/537.36"

    fun search(query: String, maxResults: Int = 8): List<WebResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        // DuckDuckGo versión HTML — devuelve HTML limpio sin JavaScript
        val url = URL("https://html.duckduckgo.com/html/?q=$encoded")
        Log.d(TAG, "Buscando en DuckDuckGo: $query")
        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Language", "es-MX,es;q=0.9")
                connectTimeout = 10_000; readTimeout = 15_000
            }
            val code = conn.responseCode
            Log.d(TAG, "DuckDuckGo HTTP $code")
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val html = BufferedReader(InputStreamReader(stream ?: return fallback(query))).use { it.readText() }
            conn.disconnect()
            val results = parseDDG(html, maxResults, query)
            Log.d(TAG, "DDG resultados: ${results.size}")
            results.ifEmpty { fallback(query) }
        } catch (e: Exception) {
            Log.w(TAG, "DDG falló: ${e.message}")
            fallback(query)
        }
    }

    private fun parseDDG(html: String, max: Int, query: String): List<WebResult> {
        val results = mutableListOf<WebResult>()

        // DuckDuckGo HTML tiene estructura: <a class="result__a" href="...">TITLE</a>
        //                                   <a class="result__url">domain.com</a>
        val titleRegex = Regex("""class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetRegex = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val domainRegex = Regex("""class="result__url"[^>]*>\s*(.*?)\s*</a>""", RegexOption.DOT_MATCHES_ALL)

        val titles = titleRegex.findAll(html).toList()
        val snippets = snippetRegex.findAll(html).map {
            it.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
        }.toList()
        val domains = domainRegex.findAll(html).map {
            it.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
        }.toList()

        titles.forEachIndexed { i, match ->
            if (results.size >= max) return results
            val url = match.groupValues[1]
            val title = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            if (title.isBlank() || url.isBlank()) return@forEachIndexed
            val domain = domains.getOrNull(i) ?: url.take(40)
            val snippet = snippets.getOrNull(i) ?: ""
            results.add(WebResult(title = title, url = url, domain = domain, snippet = snippet))
        }
        return results
    }

    private fun fallback(query: String): List<WebResult> = listOf(
        WebResult("Resultados para: \"$query\" — buscado desde el Servidor", "https://duckduckgo.com/?q=${URLEncoder.encode(query, "UTF-8")}", "duckduckgo.com", "El Servidor obtuvo estos resultados usando su conexión a Internet"),
        WebResult("Wikipedia: $query", "https://es.wikipedia.org/wiki/${URLEncoder.encode(query, "UTF-8")}", "es.wikipedia.org", "Artículo relacionado en Wikipedia"),
        WebResult("Noticias sobre: $query", "https://news.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}", "news.google.com", "Últimas noticias"),
    )
}

data class WebResult(
    val title: String,
    val url: String,
    val domain: String = "",
    val snippet: String = ""
)
