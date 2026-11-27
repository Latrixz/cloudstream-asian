package com.kankanya

import android.util.Log
import com.csasian.shared.CsBackup
import com.csasian.shared.CsQuality
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.random.Random

/**
 * 看看鸭 (www.kankanya.me) is a MacCMS site (mxtheme template) behind a hosting-panel firewall:
 *  - firewall: the first visit returns a "人机验证" page with a fresh PHPSESSID. Posting
 *              value=enc(page url)&token=enc(<token in the page>) to /robot.php ({"msg":"ok"})
 *              unlocks that session; see [fetchText].
 *  - lists:    /show/{dianying|juji|dongman|zongyi}--{time|hits}------{page}---.html
 *  - search:   /search/{kw}----------{page}---.html (the site allows one search every 3 seconds)
 *  - detail:   /detail/{id}.html; servers are tabs named 地址N, episodes /play/{id}-{sid}-{nid}.html
 *  - play:     the play page's `player_aaaa.url` is posted as vid to /kkyplayer/api.php, which answers
 *              {url, type: mp4|customHls, urlmode}; url is obfuscated, see [decodeMode1]/[decodeMode2].
 * The m3u8 links on yun2.jiexicn.top expire within about a minute, so links are only resolved in
 * loadLinks. Some of those playlists disguise their segments as 1x1 PNG images with the MPEG-TS
 * data after the image; [segmentCleaner] removes the image part while playing.
 */
class KankanyaProvider : MainAPI() {
    override var mainUrl = "https://www.kankanya.me"
    override var name = "看看鸭 Kankanya"
    override var lang = "zh"
    override val hasMainPage = true
    // The site rejects searches less than 3 seconds apart, so no search-as-you-type.
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie)

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
    private val pageHeaders get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
    private val jsonMapper = ObjectMapper()

    private data class Category(val slug: String, val typeId: Int, val label: String, val type: TvType, val ranked: Boolean)

    private val categories = listOf(
        Category("dianying", 1, "电影", TvType.Movie, ranked = true),
        Category("juji", 2, "剧集", TvType.TvSeries, ranked = true),
        Category("dongman", 4, "动漫", TvType.Anime, ranked = true),
        // Only a dozen variety titles: one row is enough.
        Category("zongyi", 3, "综艺", TvType.TvSeries, ranked = false),
    )

    override val mainPage = listOf(MainPageData("热播推荐", HOME_ROW)) +
        categories.flatMap { c ->
            if (c.ranked) {
                listOf(MainPageData("最新${c.label}", "${c.slug}|time"), MainPageData("热门${c.label}", "${c.slug}|hits"))
            } else {
                listOf(MainPageData("最新${c.label}", "${c.slug}|time"))
            }
        }

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == HOME_ROW) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val document = fetchDocument("$mainUrl/")
            val module = document.select("div.module").firstOrNull {
                it.selectFirst("h2.module-title")?.text()?.trim()?.startsWith("热播") == true
            }
            val items = module?.select("a.module-poster-item")?.mapNotNull { it.toCard(null) }?.distinctBy { it.url }.orEmpty()
            Log.i(TAG, "home row '${request.name}': ${items.size} cards")
            return newHomePageResponse(request.name, items, false)
        }
        val slug = request.data.substringBefore('|')
        val order = request.data.substringAfter('|', "time")
        val category = categories.firstOrNull { it.slug == slug }
        val document = fetchDocument("$mainUrl/show/$slug--$order------$page---.html")
        val items = document.select("a.module-poster-item").mapNotNull { it.toCard(category?.type) }.distinctBy { it.url }
        val hasNext = items.isNotEmpty() && page < lastPage(document)
        Log.i(TAG, "row '${request.name}' page $page: ${items.size} cards, next=$hasNext")
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    private val searchLock = Mutex()
    private var lastSearchAt = 0L

    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        val keyword = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$mainUrl/search/$keyword-------------.html"
        else "$mainUrl/search/$keyword----------$page---.html"
        // One search at a time, at least 3 seconds apart; once more if the site still says "too often".
        val html = searchLock.withLock {
            var text = ""
            for (attempt in 0..2) {
                val wait = lastSearchAt + SEARCH_INTERVAL_MS - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                text = fetchText(url)
                lastSearchAt = System.currentTimeMillis()
                if (!text.contains(SEARCH_TOO_OFTEN)) break
                Log.i(TAG, "search '$query': rate limited, retrying")
            }
            text
        }
        val document = Jsoup.parse(html, "$mainUrl/")
        val results = document.select(".module-card-item").mapNotNull { box ->
            val link = box.selectFirst(".module-card-item-title a[href*=/detail/]") ?: return@mapNotNull null
            val title = link.text().trim().ifBlank { return@mapNotNull null }
            val type = when (box.selectFirst(".module-card-item-class")?.text()?.trim()) {
                "电影" -> TvType.Movie
                "动漫" -> TvType.Anime
                else -> TvType.TvSeries
            }
            searchResult(title, fixUrl(link.attr("href")), imageOf(box.selectFirst("img")), type,
                box.selectFirst(".module-item-note")?.text())
        }.distinctBy { it.url }
        val hasNext = results.isNotEmpty() && page < lastPage(document)
        Log.i(TAG, "search '$query' page $page: ${results.size} results, next=$hasNext")
        return results to hasNext
    }

    // Remembers episodes and adds backup links from our other sources (see shared/CsBackup.kt).
    override suspend fun load(url: String): LoadResponse =
        com.csasian.shared.CsMore.load(this, url) ?: loadPage(url).also { CsBackup.remember(this, it) }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = CsBackup.loadLinks(this, data, subtitleCallback, callback) {
        loadOwnLinks(data, isCasting, subtitleCallback, callback)
    }

    /** Removes the fake PNG image in front of the MPEG-TS data of disguised HLS segments. */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = segmentCleaner

    private suspend fun loadPage(url: String): LoadResponse {
        val detailUrl = url.substringBefore('#')
        val document = fetchDocument(detailUrl)
        val title = document.selectFirst(".module-info-heading h1")?.text()?.trim().orEmpty()
            .ifBlank { throw ErrorLoadingException("找不到影片标题") }
        val poster = imageOf(document.selectFirst(".module-info-poster img"))
        val plot = document.selectFirst(".module-info-introduction-content")?.text()?.trim()?.ifBlank { null }
        val tagLinks = document.select(".module-info-tag-link a")
        val year = tagLinks.firstOrNull { Regex("""^\d{4}$""").matches(it.text().trim()) }?.text()?.trim()?.toIntOrNull()
        val tags = tagLinks.map { it.text().trim() }
            .filter { it.isNotBlank() && !Regex("""^\d{4}$""").matches(it) }.distinct()
        val typeId = tagLinks.firstNotNullOfOrNull {
            Regex("""^/show/(\d+)-""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }
        val info = { label: String ->
            document.select(".module-info-item").firstOrNull {
                it.selectFirst(".module-info-item-title")?.text()?.contains(label) == true
            }
        }
        val names = { label: String, limit: Int ->
            info(label)?.select(".module-info-item-content a")?.map { it.text().trim() }
                ?.filter { it.isNotBlank() && it != "内详" && it != "未知" }?.distinct()?.take(limit).orEmpty()
        }
        val actors = (names("主演", 20).map { ActorData(Actor(it)) } +
            names("导演", 3).map { ActorData(Actor(it), roleString = "导演") }).ifEmpty { null }
        val status = info("集数")?.selectFirst(".module-info-item-content")?.text()?.trim()?.ifBlank { null }
        val duration = info("片长")?.text()?.let { Regex("""(\d+)\s*分钟""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val plotText = listOfNotNull(status?.let { "（$it）" }, plot).joinToString("\n").ifBlank { null }

        val servers = parseServers(document)
        if (servers.isEmpty()) throw ErrorLoadingException("此影片暂无播放来源")
        val longest = servers.maxByOrNull { it.entries.size }!!.entries
        val category = categories.firstOrNull { it.typeId == typeId }
        // A few titles filed as movies are really series ("第01集" ... "第30集").
        val episodic = longest.size > 1 && longest.count { isEpisodeName(it.name) } >= 2
        val type = when {
            category?.type == TvType.Movie -> if (episodic) TvType.TvSeries else TvType.Movie
            category?.type == TvType.Anime -> if (longest.size == 1 && !isEpisodeName(longest[0].name)) TvType.AnimeMovie else TvType.Anime
            category != null -> TvType.TvSeries
            longest.size == 1 -> TvType.Movie
            else -> TvType.TvSeries
        }
        val recommended = document.select("a.module-poster-item").mapNotNull { it.toCard(null) }
            .filter { it.url != detailUrl }.distinctBy { it.url }.take(MAX_RECOMMENDATIONS)
        Log.i(TAG, "load '$title' ($type): ${servers.size} servers, ${longest.size} entries")

        return if (type == TvType.Movie || type == TvType.AnimeMovie) {
            newMovieLoadResponse(title, detailUrl, type, detailUrl) {
                posterUrl = poster
                this.plot = plotText
                this.year = year
                this.tags = tags
                this.actors = actors
                this.duration = duration
                this.recommendations = recommended
            }
        } else {
            val episodes = longest.mapIndexed { index, entry ->
                val key = URLEncoder.encode(normalizeEpisode(entry.name), "UTF-8")
                newEpisode("$detailUrl#ep=$key&i=$index") {
                    name = episodeName(entry.name, index)
                    episode = index + 1
                }
            }
            newTvSeriesLoadResponse(title, detailUrl, type, episodes) {
                posterUrl = poster
                this.plot = plotText
                this.year = year
                this.tags = tags
                this.actors = actors
                this.duration = duration
                this.recommendations = recommended
            }
        }
    }

    private suspend fun loadOwnLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data = {detailUrl} for movies, {detailUrl}#ep={normalized name}&i={index} for episodes.
        val detailUrl = data.substringBefore('#')
        val params = data.substringAfter('#', "").split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val wanted = URLDecoder.decode(params["ep"].orEmpty(), "UTF-8")
        val index = params["i"]?.toIntOrNull()
        val servers = parseServers(fetchDocument(detailUrl))

        // A movie may list several files per server ("1080P正片", "1080Ptc"); an episode one per server.
        val targets: List<Pair<Server, Entry>> = if (wanted.isBlank() && index == null) {
            servers.flatMap { s -> s.entries.take(MAX_MOVIE_FILES).map { s to it } }
        } else {
            servers.mapNotNull { s -> s.entries.firstOrNull { normalizeEpisode(it.name) == wanted }?.let { s to it } }
                .ifEmpty { servers.mapNotNull { s -> index?.let { s.entries.getOrNull(it) }?.let { s to it } } }
        }
        Log.i(TAG, "loadLinks $data: ${targets.size} candidates on ${servers.size} servers")
        if (targets.isEmpty()) return false

        val sent = mutableSetOf<String>()
        val gate = Semaphore(RESOLVE_CONCURRENCY)
        coroutineScope {
            targets.map { (server, entry) ->
                async {
                    val stream = gate.withPermit {
                        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { runCatching { resolve(entry) }.getOrNull() }
                    } ?: return@async
                    val several = server.entries.size > 1 && wanted.isBlank() && index == null
                    val label = if (several) "${server.label} ${entry.name}" else server.label
                    val first = synchronized(sent) { sent.add(stream.url) }
                    if (!first) return@async
                    Log.i(TAG, "link $label: ${stream.from} ${stream.type}")
                    callback(
                        newExtractorLink(name, "$name $label", stream.url, stream.type) {
                            referer = "$mainUrl/"
                            quality = qualityOf(entry.name)
                            headers = mapOf("User-Agent" to userAgent)
                        },
                    )
                }
            }.awaitAll()
        }
        Log.i(TAG, "loadLinks $data: ${sent.size} links")
        return sent.isNotEmpty()
    }

    private class Stream(val url: String, val type: ExtractorLinkType, val from: String)

    /** play page -> player_aaaa -> /kkyplayer/api.php -> decoded stream URL; null when unusable. */
    private suspend fun resolve(entry: Entry): Stream? {
        val html = fetchText("$mainUrl${entry.path}")
        val json = Regex("""player_aaaa\s*=\s*(\{.*?\})\s*</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return null
        val config = jsonMapper.readTree(json)
        val from = config.path("from").asText("")
        // Paid or trial-only episodes.
        if (config.path("trysee").asInt(0) > 0 || config.path("points").asInt(0) > 0) {
            Log.i(TAG, "${entry.path}: paid/trial, skipped")
            return null
        }
        val vid = config.path("url").asText("").ifBlank { return null }
        val reply = app.post(
            "$mainUrl/kkyplayer/api.php",
            headers = pageHeaders + mapOf("X-Requested-With" to "XMLHttpRequest"),
            data = mapOf("vid" to vid),
        ).text
        // Broken titles answer with PHP warnings in front of the JSON, or with no JSON at all.
        val start = reply.indexOf("{\"code\"").takeIf { it >= 0 } ?: run {
            Log.i(TAG, "${entry.path} ($from): no player data")
            return null
        }
        val node: JsonNode = jsonMapper.readTree(reply.substring(start, reply.lastIndexOf('}') + 1))
        val payload = node.path("data")
        val encoded = payload.path("url").asText("")
        if (node.path("code").asInt(0) != 200 || encoded.isBlank()) {
            Log.i(TAG, "${entry.path} ($from): api ${node.path("code").asInt(0)} ${node.path("message").asText("")}")
            return null
        }
        val url = when (payload.path("urlmode").asInt(0)) {
            1 -> decodeMode1(encoded)
            2 -> decodeMode2(encoded)
            else -> encoded
        }.trim()
        if (!url.startsWith("http") || PLACEHOLDER.containsMatchIn(url)) {
            Log.i(TAG, "${entry.path} ($from): placeholder ${url.take(60)}")
            return null
        }
        val hls = payload.path("type").asText("").contains("hls", true) || url.contains(".m3u8", true)
        if (hls && !playlistUsable(url)) {
            Log.i(TAG, "${entry.path} ($from): playlist has dead segments, skipped")
            return null
        }
        return Stream(url, if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO, from)
    }

    /**
     * Some playlists (e.g. the 1ljx.com ones) mix absolute segment URLs with relative ones whose host
     * answers "402 Payment Required"; such a link stops partway, so it's dropped. The short-lived
     * yun2.jiexicn.top playlists are left alone (only absolute URLs, and fetching them costs time).
     */
    private suspend fun playlistUsable(url: String): Boolean {
        if (URI(url).host.orEmpty().endsWith("jiexicn.top")) return true
        return runCatching {
            val response = app.get(url, headers = pageHeaders, timeout = 10)
            if (!response.isSuccessful) return false
            val text = response.text
            if (!text.contains("#EXTM3U")) return false
            if (text.contains("#EXT-X-STREAM-INF")) return true
            val relative = text.lineSequence().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("http") }
                ?: return true
            val segment = URI(response.url).resolve(relative).toString()
            app.get(segment, headers = pageHeaders + mapOf("Range" to "bytes=0-187"), timeout = 10).isSuccessful
        }.getOrDefault(true)
    }

    // ---- Firewall ----

    private val firewallLock = Mutex()
    @Volatile private var sessionId: String? = null
    @Volatile private var sessionGeneration = 0

    private suspend fun fetchDocument(url: String): Document = Jsoup.parse(fetchText(url), url)

    /**
     * GET with the unlocked PHPSESSID. A firewall page brings a new session id: it is unlocked via
     * /robot.php (once, even when several requests hit the firewall at the same time) and the page
     * is fetched again.
     */
    private suspend fun fetchText(url: String): String {
        repeat(3) { attempt ->
            val generation = sessionGeneration
            val sid = sessionId
            val response = app.get(url, headers = pageHeaders, cookies = sid?.let { mapOf("PHPSESSID" to it) } ?: emptyMap())
            if (response.code == 429 || response.code >= 500) {
                delay(1500L * (attempt + 1))
                return@repeat
            }
            val text = response.text
            if (!isFirewallPage(text)) return text
            passFirewall(url, text, response.cookies["PHPSESSID"] ?: sid, generation)
        }
        throw ErrorLoadingException("看看鸭防火墙验证失败，请稍后再试")
    }

    private fun isFirewallPage(text: String) = text.contains("robot.php") && text.contains("人机验证")

    private suspend fun passFirewall(url: String, page: String, challengeSid: String?, generation: Int) {
        firewallLock.withLock {
            // Another request already unlocked a session while this one waited.
            if (sessionGeneration != generation) return
            val token = Regex("""token\s*=\s*encrypt\("([^"]+)"\)""").find(page)?.groupValues?.get(1)
                ?: Regex("""encrypt\("([^"]+)"\)""").find(page)?.groupValues?.get(1)
                ?: return
            val reply = app.post(
                "$mainUrl/robot.php",
                headers = mapOf("User-Agent" to userAgent, "Referer" to url),
                cookies = challengeSid?.let { mapOf("PHPSESSID" to it) } ?: emptyMap(),
                data = mapOf("value" to firewallEncode(url), "token" to firewallEncode(token)),
            )
            val sid = reply.cookies["PHPSESSID"] ?: challengeSid
            val ok = reply.text.contains("\"ok\"")
            Log.i(TAG, "firewall check: ${if (ok) "passed" else "failed: ${reply.text.take(80)}"}")
            if (ok) {
                sessionId = sid
                sessionGeneration++
            }
        }
    }

    /** The firewall page's encrypt(): each character shifted by 3 in [ALPHABET], between two random fillers. */
    private fun firewallEncode(text: String): String {
        val out = StringBuilder(text.length * 3)
        for (c in text) {
            val i = ALPHABET.indexOf(c)
            out.append(ALPHABET[Random.nextInt(62)])
            out.append(if (i < 0) c else ALPHABET[(i + 3) % 62])
            out.append(ALPHABET[Random.nextInt(62)])
        }
        return android.util.Base64.encodeToString(out.toString().toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
    }

    // ---- Stream URL decoding (kkyplayer) ----

    private fun base64(text: String): ByteArray {
        val clean = text.trim().replace("\\s".toRegex(), "")
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
    }

    /**
     * urlmode 1: base64, XOR with md5("test") as text, base64 again, giving "A/B/C": A and B are
     * base64 JSON arrays of characters and C the base64 URL with every B[i] written as A[i].
     */
    private fun decodeMode1(encoded: String): String {
        val raw = base64(encoded)
        val unmasked = String(CharArray(raw.size) { i -> ((raw[i].toInt() and 0xff) xor MODE1_KEY[i % MODE1_KEY.length].code).toChar() })
        val parts = String(base64(unmasked), Charsets.UTF_8).split('/', limit = 3)
        if (parts.size < 3) return ""
        val plain = jsonMapper.readTree(String(base64(parts[0]), Charsets.UTF_8)).map { it.asText() }
        val masked = jsonMapper.readTree(String(base64(parts[1]), Charsets.UTF_8)).map { it.asText() }
        val table = masked.zip(plain).toMap()
        val body = String(base64(parts[2]), Charsets.UTF_8)
        return buildString { body.forEach { c -> append(table[c.toString()] ?: c.toString()) } }
    }

    /** urlmode 2: base64 of three-character groups; the middle one is the URL character shifted by 3. */
    private fun decodeMode2(encoded: String): String {
        val raw = String(base64(encoded), Charsets.ISO_8859_1)
        return buildString {
            var i = 1
            while (i < raw.length) {
                val j = ALPHABET.indexOf(raw[i])
                append(if (j < 0) raw[i] else ALPHABET[(j + 59) % 62])
                i += 3
            }
        }
    }

    // ---- Disguised segments ----

    private val segmentCleaner = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return@Interceptor response
        val head = runCatching { response.peekBody(8).bytes() }.getOrNull() ?: return@Interceptor response
        if (head.size < 8 || !head.contentEquals(PNG_SIGNATURE)) return@Interceptor response
        val data = response.body.bytes()
        val start = (0 until minOf(4096, data.size - 2 * TS_PACKET)).firstOrNull {
            data[it] == TS_SYNC && data[it + TS_PACKET] == TS_SYNC && data[it + 2 * TS_PACKET] == TS_SYNC
        }
        val clean = if (start == null) data else data.copyOfRange(start, data.size)
        response.newBuilder()
            .code(200)
            .removeHeader("Content-Range")
            .removeHeader("Content-Length")
            .removeHeader("Content-Encoding")
            .header("Content-Type", if (start == null) response.header("Content-Type") ?: "image/png" else "video/mp2t")
            .body(clean.toResponseBody((if (start == null) "image/png" else "video/mp2t").toMediaTypeOrNull()))
            .build()
    }

    // ---- Parsing ----

    private data class Entry(val name: String, val path: String)
    private data class Server(val label: String, val entries: List<Entry>)

    /** The 地址N tabs and their episode lists (every list is id="panel1"; they're in tab order). */
    private fun parseServers(document: Document): List<Server> {
        val labels = document.select("#y-playList .module-tab-item").map {
            it.attr("data-dropdown-value").ifBlank { it.selectFirst("span")?.text().orEmpty() }.trim()
        }
        return document.select(".module-list.tab-list").mapIndexedNotNull { i, panel ->
            val entries = panel.select("a.module-play-list-link[href*=/play/]").map {
                Entry(it.text().trim(), it.attr("href").trim())
            }.distinctBy { it.path }
            if (entries.isEmpty()) null else Server(labels.getOrNull(i)?.ifBlank { null } ?: "线路${i + 1}", entries)
        }.let { servers ->
            // Two tabs can share a name ("地址3" twice): number the repeats.
            val seen = mutableMapOf<String, Int>()
            servers.map { s ->
                val n = (seen[s.label] ?: 0) + 1
                seen[s.label] = n
                if (n > 1) s.copy(label = "${s.label}-$n") else s
            }
        }
    }

    /** Pager links end in "-{page}---.html" (or "-{page}---{year}.html"): the highest number is the last page. */
    private fun lastPage(document: Document): Int =
        document.select("#page a[href]").mapNotNull {
            Regex("""-(\d+)---\d*\.html$""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 1

    private fun normalizeEpisode(name: String): String {
        val trimmed = name.trim()
        return Regex("""^第?\s*0*(\d+)\s*[集话話期]?$""").find(trimmed)?.groupValues?.get(1) ?: trimmed
    }

    private fun isEpisodeName(name: String) = Regex("""^第?\s*\d+\s*[集话話期]?$""").matches(name.trim())

    private fun episodeName(raw: String, index: Int): String {
        val normalized = normalizeEpisode(raw)
        return when {
            normalized.isBlank() -> "第 ${index + 1} 集"
            normalized.all { it.isDigit() } && normalized.length < 5 -> "第 $normalized 集"
            else -> raw.trim()
        }
    }

    /** Movie files are named like "1080P正片", "蓝光超清", "1080Ptc"; cam copies rank last. */
    private fun qualityOf(name: String): Int {
        val t = name.lowercase()
        return when {
            Regex("""tc|ts|cam|枪版|抢先""").containsMatchIn(t) -> Qualities.P240.value
            "4k" in t || "2160" in t -> Qualities.P2160.value
            "1080" in t || "蓝光" in t -> Qualities.P1080.value
            "720" in t -> Qualities.P720.value
            else -> Qualities.Unknown.value
        }
    }

    private fun imageOf(img: Element?): String? {
        if (img == null) return null
        return listOf(img.attr("data-original"), img.attr("data-src"), img.attr("src"))
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") && !it.endsWith("load.gif") }
            ?.let { fixUrl(it) }
    }

    /** [type] null = guess from the card's note ("正片" / "HD" are movies, "更新至16集" series). */
    private fun Element.toCard(type: TvType?): SearchResponse? {
        val href = attr("href").takeIf { it.contains("/detail/") } ?: return null
        val title = attr("title").trim()
            .ifBlank { selectFirst(".module-poster-item-title")?.text()?.trim().orEmpty() }
            .ifBlank { return null }
        val note = selectFirst(".module-item-note")?.text()?.trim()
        val guessed = type ?: if (note != null && Regex("""集|期""").containsMatchIn(note)) TvType.TvSeries else TvType.Movie
        return searchResult(title, fixUrl(href), imageOf(selectFirst("img")), guessed, note)
    }

    private fun searchResult(title: String, href: String, poster: String?, type: TvType, note: String?): SearchResponse =
        if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, type) {
                posterUrl = poster
                quality = CsQuality.from(note)
            }
        } else {
            newTvSeriesSearchResponse(title, href, type) {
                posterUrl = poster
                quality = CsQuality.from(note)
            }
        }

    companion object {
        private const val TAG = "Kankanya"
        private const val HOME_ROW = "home"
        private const val MAX_RECOMMENDATIONS = 16
        private const val MAX_MOVIE_FILES = 3
        private const val RESOLVE_CONCURRENCY = 3
        private const val RESOLVE_TIMEOUT_MS = 15_000L
        private const val SEARCH_INTERVAL_MS = 3_200L
        private const val SEARCH_TOO_OFTEN = "请不要频繁操作"
        private const val ALPHABET = "PXhw7UT1B0a9kQDKZsjIASmOezxYG4CHo5Jyfg2b8FLpEvRr3WtVnlqMidu6cN"
        private const val MODE1_KEY = "098f6bcd4621d373cade4e832627b4f6" // md5("test")
        private const val TS_PACKET = 188
        private const val TS_SYNC: Byte = 0x47
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val PLACEHOLDER = Regex("""^https?://(www\.)?baidu\.com/""")
    }
}
