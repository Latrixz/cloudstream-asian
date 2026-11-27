package com.qwfilm

import android.util.Log
import com.csasian.shared.CsBackup
import com.csasian.shared.CsQuality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 七味网 (qwfilm.com), MacCMS "pianku" template, behind a Cloudflare managed challenge.
 * TRIAL: checks whether CloudStream's CloudflareKiller (hidden WebView) can pass the challenge.
 *  - lists:   /ms/{typeId}--{by}------{page}---.html   (MacCMS show: id-area-by-class-lang-letter-level-order-page-state-tag-year)
 *             typeIds: 1 电影, 2 剧集, 3 综艺, 4 动漫, 30 短剧
 *  - search:  /vs/{wd}----------{page}---.html          (MacCMS search: wd-actor-area-by-class-director-lang-letter-level-order-page-state-tag-year)
 *  - detail:  /mv/{id}.html (server names in ul.py-tabs li, episodes in #url .bd ul.player)
 *  - player:  /py/{id}-{sid}-{nid}.html embeds `player_aaaa={...}` (encrypt 0 plain, 1 escape(), 2 base64(escape())).
 * Magnet/ed2k download links on the detail page are ignored.
 */
class QwfilmProvider : MainAPI() {
    override var mainUrl = "https://www.qwfilm.com"
    override var name = "七味网 Qwfilm"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    // Every request goes through CloudflareKiller: on a Cloudflare 403/503 it solves the challenge in a
    // hidden WebView and reuses the clearance cookie (and the WebView's user agent) afterwards.
    private val cloudflare = CloudflareKiller()
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val pageHeaders get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
    private val jsonMapper = ObjectMapper()

    // Until the challenge is passed once, requests are made one at a time so the home rows don't
    // open several solver WebViews at once.
    private val challengeLock = Mutex()
    @Volatile private var cleared = false
    @Volatile private var loggedHome = false

    private data class Category(val id: Int, val label: String, val type: TvType)

    private val categories = listOf(
        Category(1, "电影", TvType.Movie),
        Category(2, "剧集", TvType.TvSeries),
        Category(3, "综艺", TvType.TvSeries),
        Category(4, "动漫", TvType.Anime),
        Category(30, "短剧", TvType.AsianDrama),
    )

    override val mainPage = listOf(MainPageData("七味推荐", HOME_ROW)) +
        categories.flatMap { c ->
            listOf(
                MainPageData("最新${c.label}", "${c.id}|time"),
                MainPageData("${c.label}热播", "${c.id}|hits"),
            )
        }

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == HOME_ROW) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val document = getPage("$mainUrl/", home = true)
            val items = cards(document)
            Log.i(TAG, "home row '${request.name}': ${items.size} cards parsed")
            return newHomePageResponse(request.name, items, false)
        }
        val (id, order) = request.data.split('|').let { it[0] to it.getOrElse(1) { "time" } }
        val type = categories.firstOrNull { it.id.toString() == id }?.type ?: TvType.TvSeries
        val url = "$mainUrl/ms/$id--$order------$page---.html"
        val document = getPage(url, home = !loggedHome)
        val items = cards(document, type)
        Log.i(TAG, "row '${request.name}' page $page ($url): ${items.size} cards parsed")
        return newHomePageResponse(request.name, items, items.isNotEmpty() && hasNextPage(document, page))
    }

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        var document = getPage("$mainUrl/vs/$encoded----------$page---.html")
        var results = cards(document)
        if (results.isEmpty() && page == 1) {
            // The search form submits GET /vs/-------------.html?wd=...
            document = getPage("$mainUrl/vs/-------------.html?wd=$encoded")
            results = cards(document)
        }
        Log.i(TAG, "search '$query' page $page: ${results.size} cards parsed")
        return results to (results.isNotEmpty() && hasNextPage(document, page))
    }

    override suspend fun load(url: String): LoadResponse =
        com.csasian.shared.CsMore.load(this, url) ?: loadPage(url).also { CsBackup.remember(this, it) }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = CsBackup.loadLinks(this, data, subtitleCallback, callback) {
        loadOwnLinks(data, callback)
    }

    // Strips ad segments spliced into the public feeds' playlists (see shared/CsAdStrip.kt).
    override fun getVideoInterceptor(extractorLink: ExtractorLink) = com.csasian.shared.CsAdStrip.interceptor

    private suspend fun loadPage(url: String): LoadResponse {
        val detailUrl = url.substringBefore('#')
        val document = getPage(detailUrl)
        val meta = document.selectFirst(".main-ui-meta")
        val title = (meta?.selectFirst("h1")?.ownText()?.trim()
            ?: document.selectFirst("h1")?.ownText()?.trim()).orEmpty()
            .ifBlank { document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty() }
            .ifBlank { throw ErrorLoadingException("找不到影片标题（可能未通过 Cloudflare 验证）") }
        val year = meta?.selectFirst("h1 .year")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val poster = imageOf(document.selectFirst(".main-left-1 .img img"))
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        val plot = (document.selectFirst(".movie-introduce .sqjj_a") ?: document.selectFirst(".movie-introduce"))
            ?.text()?.replace("[收起部分]", "")?.replace("[展开全部]", "")?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[name=description]")?.attr("content")
        val infoLine = { label: String ->
            meta?.select("div")?.firstOrNull { it.selectFirst("> span")?.text()?.contains(label) == true }
        }
        val names = { label: String, limit: Int ->
            infoLine(label)?.select("a")?.map { it.text().trim() }
                ?.filter { it.isNotBlank() && it != "内详" }?.distinct()?.take(limit).orEmpty()
        }
        val tags = names("类型", 10)
        val actors = (names("主演", 20).map { ActorData(Actor(it)) } +
            names("导演", 3).map { ActorData(Actor(it), roleString = "导演") }).ifEmpty { null }
        val status = meta?.selectFirst(".otherbox em")?.text()?.trim()?.ifBlank { null }
        val score = Regex("""豆瓣\s*([\d.]+)""").find(infoLine("评分")?.text().orEmpty())
            ?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0 }
        val plotText = listOfNotNull(status?.let { "（$it）" }, plot).joinToString("\n").ifBlank { null }

        val sources = parseSources(document)
        Log.i(TAG, "detail '$title': ${sources.size} servers (${sources.joinToString { "${it.label}:${it.episodes.size}" }})")
        if (sources.isEmpty()) throw ErrorLoadingException("此影片暂无在线播放来源")
        val canonical = sources.maxByOrNull { it.episodes.size }!!.episodes
        val navType = typeOf(document)
        val isMovie = canonical.size == 1 && (navType == TvType.Movie || navType == null) ||
            navType == TvType.Movie
        val type = when {
            isMovie -> TvType.Movie
            navType == null -> TvType.TvSeries
            else -> navType
        }
        val recommended = document.select("ul.content-list2 > li").mapNotNull { it.toCard(type) }
            .filter { it.url != detailUrl }.distinctBy { it.url }.take(MAX_RECOMMENDATIONS)

        return if (isMovie) {
            newMovieLoadResponse(title, detailUrl, type, episodeData(detailUrl, canonical[0], movie = true)) {
                posterUrl = poster
                this.plot = plotText
                this.year = year
                this.tags = tags
                this.score = score?.let { Score.from10(it) }
                this.actors = actors
                this.recommendations = recommended
            }
        } else {
            val episodes = canonical.mapIndexed { position, entry ->
                newEpisode(episodeData(detailUrl, entry, movie = false)) {
                    name = episodeName(entry.name, position)
                    episode = position + 1
                }
            }
            newTvSeriesLoadResponse(title, detailUrl, type, episodes) {
                posterUrl = poster
                this.plot = plotText
                this.year = year
                this.tags = tags
                this.score = score?.let { Score.from10(it) }
                this.actors = actors
                this.recommendations = recommended
            }
        }
    }

    private suspend fun loadOwnLinks(data: String, callback: (ExtractorLink) -> Unit): Boolean {
        // data = https://www.qwfilm.com/mv/{id}.html#ep={normalized name}&nid={n}  (ep is empty for movies)
        val detailUrl = data.substringBefore('#')
        val params = data.substringAfter('#', "").split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val wanted = URLDecoder.decode(params["ep"].orEmpty(), "UTF-8")
        val wantedNid = params["nid"]?.toIntOrNull()

        val sources = parseSources(getPage(detailUrl))
        val targets = sources.mapNotNull { source ->
            val entry = (if (wanted.isNotBlank()) source.episodes.firstOrNull { normalizeEpisode(it.name) == wanted } else null)
                ?: source.episodes.firstOrNull { it.nid == wantedNid }
                ?: if (wanted.isBlank()) source.episodes.firstOrNull() else null
            entry?.let { source.label to it }
        }
        Log.i(TAG, "loadLinks $data: wanted='$wanted' nid=$wantedNid, ${sources.size} servers, ${targets.size} match")
        if (targets.isEmpty()) return false

        // A few player pages at a time (many at once stall behind the Cloudflare check), each with a
        // time limit, and every link goes to the player as soon as it's found. Ad-spliced feeds
        // (暴风 bfzy, 非凡 ffzy, modu) are ranked last through their quality.
        val gate = Semaphore(3)
        val found = java.util.concurrent.atomic.AtomicInteger()
        coroutineScope {
            targets.map { (label, entry) ->
                async {
                    val got = gate.withPermit { withTimeoutOrNull(12_000L) { playUrl(entry) } } ?: return@async
                    val link = Link(label, entry, got.first, got.second)
                    found.incrementAndGet()
                    callback(
                        newExtractorLink(
                            name,
                            "$name ${link.label}",
                            link.url,
                            if (link.url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        ) {
                            referer = "$mainUrl/"
                            quality = if (link.hasAds) Qualities.P144.value else qualityOf("${link.label} ${link.entry.name}")
                            headers = mapOf("User-Agent" to userAgent)
                        },
                    )
                }
            }.awaitAll()
        }
        Log.i(TAG, "links for $data: ${found.get()}/${targets.size} servers gave a stream")
        return found.get() > 0
    }

    private data class Link(val label: String, val entry: EpisodeEntry, val url: String, val from: String) {
        val hasAds: Boolean
            get() = AD_FEEDS.any { from.contains(it, true) || url.contains(it, true) } || label.contains("暴风")
    }

    /** Reads `player_aaaa` from /py/{id}-{sid}-{nid}.html; returns (stream URL, "from" feed code). */
    private suspend fun playUrl(entry: EpisodeEntry): Pair<String, String>? = runCatching {
        val html = getPage("$mainUrl/py/${entry.vodId}-${entry.sid}-${entry.nid}.html").outerHtml()
        val start = html.indexOf("player_aaaa")
        if (start < 0) { Log.i(TAG, "no player_aaaa for ${entry.vodId}-${entry.sid}-${entry.nid} (${html.length} chars, title='${Regex("<title>(.*?)</title>").find(html)?.groupValues?.get(1)}')"); return null }
        val eq = html.indexOf('=', start)
        val config: JsonNode = extractJsonObject(html, eq + 1)?.let { jsonMapper.readTree(it) } ?: return null
        val raw = config.path("url").asText("").trim()
        if (raw.isBlank()) return null
        val url = when (config.path("encrypt").asInt(0)) {
            1 -> jsUnescape(raw)
            2 -> jsUnescape(String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8))
            else -> raw
        }.trim()
        if (url.startsWith("http")) url to config.path("from").asText("") else { Log.i(TAG, "non-http url '${url.take(80)}' from=${config.path("from").asText("")}"); null }
    }.onFailure { Log.i(TAG, "playUrl ${entry.vodId}-${entry.sid}-${entry.nid} failed: $it") }.getOrNull()

    /** Fetches through CloudflareKiller and logs whether the challenge was passed. */
    private suspend fun getPage(url: String, home: Boolean = false): Document {
        val fetch = suspend {
            app.get(url, headers = pageHeaders, interceptor = cloudflare)
        }
        val response = if (cleared) fetch() else challengeLock.withLock { fetch() }
        val document = response.document
        val title = document.title().trim()
        val challenged = response.code == 403 || response.code == 503 ||
            title.contains("Just a moment", true) || title.contains("Attention Required", true) ||
            document.selectFirst("#challenge-form, #cf-wrapper, script[src*=challenges.cloudflare.com]") != null
        val host = runCatching { java.net.URI(mainUrl).host }.getOrNull().orEmpty()
        val hasClearance = cloudflare.savedCookies[host]?.containsKey("cf_clearance") == true
        if (!challenged && !cleared) {
            cleared = true
            Log.i(TAG, "Cloudflare challenge PASSED (cf_clearance=$hasClearance) at $url")
        }
        if (home && !loggedHome) {
            loggedHome = true
            Log.i(
                TAG,
                "first home request $url -> HTTP ${response.code}, title='$title', " +
                    "challengePassed=${!challenged}, cf_clearance=$hasClearance",
            )
        } else if (challenged) {
            Log.i(TAG, "Cloudflare challenge NOT passed: $url -> HTTP ${response.code}, title='$title', cf_clearance=$hasClearance")
        }
        if (challenged) throw ErrorLoadingException("七味网 Cloudflare 验证未通过 (HTTP ${response.code})")
        return document
    }

    /**
     * Cards from a list/search/home page. Prefers the template's `ul.content-list > li`, otherwise any
     * element holding a /mv/ link and a poster (search pages use a different list markup).
     */
    private fun cards(document: Document, type: TvType? = null): List<SearchResponse> {
        val primary = document.select("ul[class*=content-list] > li").mapNotNull { it.toCard(type) }
        if (primary.isNotEmpty()) return primary.distinctBy { it.url }
        return document.select("a[href*=/mv/]").mapNotNull { link ->
            if (link.parents().any { it.hasClass("hisBox") || it.tagName() == "header" }) return@mapNotNull null
            val box = link.parents().firstOrNull { it.selectFirst("img") != null && it.select("a[href*=/mv/]").map { a -> a.attr("href") }.distinct().size == 1 }
                ?: return@mapNotNull null
            box.toCard(type)
        }.distinctBy { it.url }
    }

    private fun Element.toCard(type: TvType?): SearchResponse? {
        val link = selectFirst("a[href*=/mv/]") ?: return null
        val href = fixUrl(link.attr("href").trim()).takeIf { MV_REGEX.containsMatchIn(it) } ?: return null
        val img = selectFirst("img")
        val title = link.attr("title").trim()
            .ifBlank { selectFirst("h3 a, h3, .title")?.ownText()?.trim().orEmpty() }
            .ifBlank { img?.attr("alt")?.trim().orEmpty() }
            .ifBlank { link.text().trim() }
            .ifBlank { return null }
        val remark = selectFirst(".bottom2, .note, .pic-text")?.text()?.trim().orEmpty()
        val tag = selectFirst(".tag")?.text().orEmpty()
        val finalType = type ?: guessType(remark, tag)
        val poster = imageOf(img)
        return if (finalType == TvType.Movie) {
            newMovieSearchResponse(title, href, finalType) { posterUrl = poster; quality = CsQuality.from(remark) }
        } else {
            newTvSeriesSearchResponse(title, href, finalType) { posterUrl = poster; quality = CsQuality.from(remark) }
        }
    }

    private fun hasNextPage(document: Document, page: Int): Boolean =
        document.select("a[href]").any { a ->
            val href = a.attr("href")
            val text = a.text().trim()
            (text == "下一页" || text == "下页") && href.isNotBlank() && !href.startsWith("javascript") ||
                Regex("""-${page + 1}---\.html""").containsMatchIn(href)
        }

    private data class EpisodeEntry(val name: String, val vodId: String, val sid: Int, val nid: Int)
    private data class Source(val label: String, val episodes: List<EpisodeEntry>)

    private fun parseSources(document: Document): List<Source> {
        val labels = document.select("ul.py-tabs > li").map { it.ownText().replace(' ', ' ').trim() }
        val lists = document.select("#url .bd ul.player").ifEmpty { document.select("ul.player") }
        return lists.mapIndexedNotNull { index, list ->
            val episodes = list.select("a[href*=/py/]").mapNotNull { link ->
                val match = PY_REGEX.find(link.attr("href")) ?: return@mapNotNull null
                val (vodId, sid, nid) = match.destructured
                EpisodeEntry(link.text().trim(), vodId, sid.toInt(), nid.toInt())
            }
            if (episodes.isEmpty()) return@mapIndexedNotNull null
            Source(labels.getOrNull(index)?.ifBlank { null } ?: "线路 ${index + 1}", episodes)
        }
    }

    /** The header nav marks the title's category (/vt/{id}.html) as active. */
    private fun typeOf(document: Document): TvType? {
        val id = document.selectFirst("li.active a[href*=/vt/]")?.attr("href")
            ?.let { Regex("""/vt/(\d+)""").find(it)?.groupValues?.get(1) }
        return categories.firstOrNull { it.id.toString() == id }?.type
    }

    /** JavaScript's unescape(): %XX and %uXXXX sequences. */
    private fun jsUnescape(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                if (i + 5 < text.length && text[i + 1] == 'u') {
                    text.substring(i + 2, i + 6).toIntOrNull(16)?.let { out.append(it.toChar()); i += 6; continue }
                }
                if (i + 2 < text.length) {
                    text.substring(i + 1, i + 3).toIntOrNull(16)?.let { out.append(it.toChar()); i += 3; continue }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** Returns the JSON object that starts at [from], respecting strings and escapes. */
    private fun extractJsonObject(text: String, from: Int): String? {
        val begin = text.indexOf('{', from).takeIf { it >= 0 && it - from < 5 } ?: return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in begin until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return text.substring(begin, i + 1)
                }
            }
        }
        return null
    }

    private fun episodeData(detailUrl: String, entry: EpisodeEntry, movie: Boolean): String {
        // Movie servers name their single entry differently (HD中字 / 正片 / HD[国语]): match movies by position.
        val ep = if (movie) "" else URLEncoder.encode(normalizeEpisode(entry.name), "UTF-8")
        return "$detailUrl#ep=$ep&nid=${entry.nid}"
    }

    private fun normalizeEpisode(name: String): String {
        val trimmed = name.trim()
        return Regex("""^第?\s*0*(\d+)\s*[集话話期]?$""").find(trimmed)?.groupValues?.get(1) ?: trimmed
    }

    private fun episodeName(raw: String, position: Int): String {
        val normalized = normalizeEpisode(raw)
        return when {
            normalized.isBlank() -> "第 ${position + 1} 集"
            normalized.all { it.isDigit() } -> "第 $normalized 集"
            else -> raw.trim()
        }
    }

    private fun qualityOf(text: String): Int = when {
        text.contains("4K", true) || text.contains("2160") -> Qualities.P2160.value
        text.contains("1080") || text.contains("蓝光") -> Qualities.P1080.value
        text.contains("720") || text.contains("HD", true) || text.contains("高清") -> Qualities.P720.value
        else -> Qualities.Unknown.value
    }

    private fun guessType(remark: String, tag: String): TvType = when {
        Regex("""集|期|更新|完结""").containsMatchIn(remark) -> TvType.TvSeries
        Regex("""正片|HD|高清|抢先|TC|TS|蓝光|中字|4K""", RegexOption.IGNORE_CASE).containsMatchIn(remark) -> TvType.Movie
        tag.contains("动画") -> TvType.Anime
        else -> TvType.TvSeries
    }

    private fun imageOf(img: Element?): String? {
        if (img == null) return null
        return listOf(img.attr("data-original"), img.attr("data-src"), img.attr("src"))
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { fixUrl(it) }
    }

    companion object {
        private const val TAG = "Qwfilm"
        private const val HOME_ROW = "home"
        private const val MAX_RECOMMENDATIONS = 16
        private val MV_REGEX = Regex("""/mv/\d+\.html""")
        private val PY_REGEX = Regex("""/py/(\d+)-(\d+)-(\d+)\.html""")
        private val AD_FEEDS = listOf("bfzy", "ffzy", "modu")
    }
}
