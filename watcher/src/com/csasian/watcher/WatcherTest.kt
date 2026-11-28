package com.csasian.watcher

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.Collections

/**
 * Daily source watcher. Runs the real provider code on the JVM, the way CloudStream would:
 * home page (or search) -> load a title -> loadLinks -> fetch the stream and check it is real video.
 *
 * Run with: ./gradlew watch   (results land in build/watch/<Source>.json)
 * The test never fails; the verdict is in the JSON so every source gets checked.
 */
class WatcherTest {
    companion object {
        const val APP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
    }
    private val log = mutableListOf<String>()
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private fun note(s: String) { log += s; println("[watch] $s") }

    @Test
    fun watch() {
        val module = System.getProperty("watch.module") ?: return
        val providerClass = System.getProperty("watch.provider") ?: return
        val out = File(System.getProperty("watch.out"))
        out.parentFile.mkdirs()

        val started = System.currentTimeMillis()
        var status = "down"   // up = site answers; playable = a stream actually played
        var stage = "init"
        var site: String? = null
        var detail = ""
        var title: String? = null
        var link: String? = null
        var httpCode: Int? = null
        // Sources that solve Cloudflare or scrape inside the app's WebView can't be fully played here.
        val needsWebView = System.getProperty("watch.webview") == "true"

        // Same HTTP client set-up as the app (RequestsHelper.initClient / buildDefaultClient): a desktop
        // Chrome user agent on every request, redirects followed, SSL errors ignored. Without it some
        // sites answer with a block page and the watcher reports false failures.
        app.defaultHeaders = mapOf("user-agent" to APP_USER_AGENT)
        app.baseClient = trustAll(okhttp3.OkHttpClient.Builder().followRedirects(true).followSslRedirects(true))
            .addInterceptor { chain ->
                // Keep the recent requests: on a failure they show which call went wrong.
                val req = chain.request()
                val res = runCatching { chain.proceed(req) }
                    .onFailure { requests += "${it.javaClass.simpleName} ${req.method} ${req.url}" }.getOrThrow()
                requests += "${res.code} ${req.method} ${req.url} ua=${req.headers.values("User-Agent")}"
                res
            }.build()

        try {
            val api = Class.forName(providerClass).getDeclaredConstructor().newInstance() as MainAPI
            site = api.mainUrl
            runBlocking {
                httpCode = runCatching { app.get(api.mainUrl, timeout = 20).code }
                    .onFailure { note("site: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()
                withTimeout(if (needsWebView) 90_000 else 240_000) {
                    stage = "home"
                    val items = listTitles(api)
                    if (items.isEmpty()) error("home page and search returned nothing")
                    status = "up"
                    note("${items.size} titles listed")

                    stage = "play"
                    // Try a few titles: one dead title shouldn't mark the whole source broken.
                    var lastProblem = "no title produced links"
                    for (item in items.distinctBy { it.url }.take(4)) {
                        val result = runCatching { tryPlay(api, item) }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
                        if (result.startsWith("OK ")) {
                            status = "playable"; title = item.name; link = result.removePrefix("OK ")
                            return@withTimeout
                        }
                        lastProblem = "${item.name}: $result"
                        note(lastProblem)
                    }
                    detail = lastProblem
                }
            }
        } catch (t: Throwable) {
            detail = "${t.javaClass.simpleName}: ${t.message}".take(300)
            note("failed at $stage: $detail")
        }

        if (status != "playable") synchronized(requests) { requests.takeLast(6).forEach { note("http: ${it.take(180)}") } }
        if (status == "playable") stage = "done"
        else if (needsWebView) {
            // Not a real failure: CloudStream's WebView isn't available off-device.
            status = if (httpCode != null && httpCode!! < 500) "unverified" else "down"
            detail = "needs the app's WebView to verify (site HTTP ${httpCode ?: "no answer"}). $detail"
        }
        out.writeText(
            """{"module":${q(module)},"site":${q(site)},"status":${q(status)},"stage":${q(stage)},""" +
                """"detail":${q(detail.take(300))},"title":${q(title)},"link":${q(link)},""" +
                """"http":${httpCode ?: "null"},"seconds":${(System.currentTimeMillis() - started) / 1000},"log":[${log.takeLast(14).joinToString(",") { q(it.take(200)) }}]}"""
        )
    }

    private suspend fun listTitles(api: MainAPI): List<SearchResponse> {
        if (api.hasMainPage) {
            for (data in api.mainPage.take(3)) {
                val page = runCatching {
                    api.getMainPage(1, MainPageRequest(data.name, data.data, data.horizontalImages))
                }.onFailure { note("home '${data.name}': ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()
                val list = page?.items?.flatMap { it.list }.orEmpty()
                if (list.isNotEmpty()) return list
            }
        }
        val query = if (api.lang.startsWith("zh")) "爱" else "love"
        return runCatching { api.search(query, 1)?.items.orEmpty() }
            .onFailure { note("search: ${it.javaClass.simpleName}: ${it.message}") }.getOrDefault(emptyList())
    }

    /** Returns "OK <how>" when a stream really answers, else the reason it didn't. */
    private suspend fun tryPlay(api: MainAPI, item: SearchResponse): String {
        val loaded = api.load(item.url) ?: return "load returned nothing"
        val data = when (loaded) {
            is MovieLoadResponse -> loaded.dataUrl
            is LiveStreamLoadResponse -> loaded.dataUrl
            is TvSeriesLoadResponse -> loaded.episodes.firstOrNull()?.data
            is AnimeLoadResponse -> loaded.episodes.values.flatten().firstOrNull()?.data
            is TorrentLoadResponse -> return "OK torrent/magnet (not test-played)"
            else -> null
        } ?: return "no episodes"

        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        runCatching {
            withTimeout(90_000) { api.loadLinks(data, false, {}, { links += it }) }
        }.onFailure { if (links.isEmpty()) return "loadLinks: ${it.javaClass.simpleName}: ${it.message}" }
        if (links.isEmpty()) return "no links found (${data.take(120)})"

        var problem = ""
        for (l in links.take(5)) {
            if (l.type == ExtractorLinkType.TORRENT || l.type == ExtractorLinkType.MAGNET || l.url.startsWith("magnet:"))
                return "OK ${l.source}: magnet/torrent (not test-played)"
            val r = runCatching { probe(l) }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            if (r == null) return "OK ${l.source} (${l.type.name})"
            problem = "${l.source}: $r"
        }
        return "${links.size} link(s), none played. $problem"
    }

    /** null = playable. Fetches the playlist (and a segment) or the first KB of the file. */
    private suspend fun probe(link: ExtractorLink): String? {
        val headers = buildMap {
            putAll(link.headers)
            if (link.referer.isNotBlank() && keys.none { it.equals("referer", true) }) put("Referer", link.referer)
        }
        if (link.type == ExtractorLinkType.M3U8 || link.url.contains(".m3u8")) {
            var url = link.url
            repeat(3) {
                val res = app.get(url, headers = headers, timeout = 20)
                if (!res.isSuccessful) return "playlist HTTP ${res.code}"
                val body = res.text
                if (!body.trimStart().startsWith("#EXTM3U")) return "playlist is not HLS"
                val first = body.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    ?: return "playlist has no entries"
                val next = URI(url).resolve(first).toString()
                if (body.contains("#EXT-X-STREAM-INF")) { url = next; return@repeat }
                return segment(next, headers)
            }
            return "playlist nests too deep"
        }
        if (link.type == ExtractorLinkType.DASH) {
            val res = app.get(link.url, headers = headers, timeout = 20)
            return if (res.isSuccessful && res.text.contains("<MPD")) null else "DASH manifest HTTP ${res.code}"
        }
        return segment(link.url, headers)
    }

    private suspend fun segment(url: String, headers: Map<String, String>): String? {
        val res = app.get(url, headers = headers + ("Range" to "bytes=0-2047"), timeout = 20)
        if (res.code != 200 && res.code != 206) return "video HTTP ${res.code}"
        val type = res.headers["content-type"].orEmpty()
        if (type.contains("text/html")) return "video returned a web page"
        return null
    }

    private fun trustAll(builder: okhttp3.OkHttpClient.Builder): okhttp3.OkHttpClient.Builder {
        val trust = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }
        val ssl = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), java.security.SecureRandom()) }
        return builder.sslSocketFactory(ssl.socketFactory, trust).hostnameVerifier { _, _ -> true }
    }

    private fun q(s: String?): String = if (s == null) "null" else
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ").replace("\t", " ") + "\""
}
