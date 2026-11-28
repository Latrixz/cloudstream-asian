package com.gqc

import android.util.Log
import com.csasian.shared.CsAdStrip
import com.csasian.shared.CsBackup
import com.csasian.shared.CsQuality

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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 共青春影院 (gqc7.top) is a MacCMS v10 site (vfed template) behind a Cloudflare managed challenge.
 *  - lists:   /vodshow/{slug}--{by}------{page}---.html, 48 per page (by: time, hits_week, ...).
 *             Top-level slugs: dianying, lianxuju, zongyi (incl. jilupian, music), dongman, duanju.
 *  - search:  the site's search page needs a captcha, so it is not used. The category list page takes
 *             the same keyword filter without one: /vodshow/{slug}--------{page}---/wd/{query}.html,
 *             one request per top-level category.
 *  - detail:  /neirong/{id}.html. Line names in .fed-drop-tops, episode lists in .fed-drop-btms
 *             .fed-play-item (same order); episodes link to /bofang/{id}-{sid}-{nid}.html. The sid of a
 *             line differs between titles, so lines are known by name (XG线, YZ线 ...), not sid.
 *  - player:  /bofang/... has the direct stream in `#fed-play-iframe[data-play]` (no player_aaaa).
 * The "(弹)" lines (腾视, 爱奇, 优视) only hold qq/iqiyi/youku page URLs for a VIP parser: never used.
 */
class GqcProvider : MainAPI() {
    // Mirrors of the same site: gqc1.top, gqc5.top, gqc6.top, gqc8.top. Change only this line to switch.
    override var mainUrl = "https://gqc7.top"
    override var name = "共青春影院 Gqc"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
        TvType.Documentary,
    )

    // Every page request goes through CloudflareKiller: on a Cloudflare 403/503 it solves the challenge in
    // a hidden WebView and then sends the clearance cookie together with the WebView's user agent.
    private val cloudflare = CloudflareKiller()
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val pageHeaders get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")

    // Until the challenge is passed once, requests go one at a time so the home rows don't open several
    // solver WebViews at once.
    private val challengeLock = Mutex()
    @Volatile private var cleared = false
    @Volatile private var loggedHome = false

    private data class Category(val slug: String, val label: String, val type: TvType)

    /** Top-level categories (search covers these; zongyi includes jilupian and music). */
    private val categories = listOf(
        Category("dianying", "电影", TvType.Movie),
        Category("lianxuju", "连续剧", TvType.TvSeries),
        Category("zongyi", "综艺", TvType.TvSeries),
        Category("dongman", "动漫", TvType.Anime),
        Category("duanju", "短剧", TvType.AsianDrama),
    )
    private val documentaries = Category("jilupian", "纪录片", TvType.Documentary)
    // 音乐 (concerts) is left out: those titles have no play lines on the site, only a description.

    override val mainPage = listOf(MainPageData("影院热映", HOME_ROW)) +
        (categories + documentaries).flatMap { c ->
            listOf(
                MainPageData("最新${c.label}", "${c.slug}|time"),
                MainPageData("${c.label}热播", "${c.slug}|hits_week"),
            )
        }

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == HOME_ROW) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val document = getPage("$mainUrl/", home = true)
            val heading = document.select(".fed-list-head h2").firstOrNull { it.text().trim() == "影院热映" }
            val items = heading?.parent()?.parent()?.select("li.fed-list-item")
                ?.mapNotNull { it.toCard(null) }?.distinctBy { it.url }.orEmpty()
            Log.i(TAG, "home row '${request.name}': ${items.size} cards")
            return newHomePageResponse(request.name, items, false)
        }
        val (slug, by) = request.data.split('|').let { it[0] to it.getOrElse(1) { "time" } }
        val type = categoryOf(slug)?.type ?: TvType.TvSeries
        val url = "$mainUrl/vodshow/$slug--$by------$page---.html"
        val document = getPage(url, home = !loggedHome)
        val items = cards(document, type)
        Log.i(TAG, "row '${request.name}' page $page: ${items.size} cards")
        return newHomePageResponse(request.name, items, items.isNotEmpty() && page < lastPage(document, page))
    }

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    /** One keyword-filtered list page per top-level category, merged in category order. */
    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val pages = coroutineScope {
            categories.map { c ->
                async {
                    runCatching {
                        val document = getPage("$mainUrl/vodshow/${c.slug}--------$page---/wd/$encoded.html")
                        cards(document, c.type) to (page < lastPage(document, page))
                    }.onFailure { Log.i(TAG, "search ${c.slug} '$query' failed: $it") }
                        .getOrDefault(emptyList<SearchResponse>() to false)
                }
            }.awaitAll()
        }
        val results = pages.flatMap { it.first }.distinctBy { it.url }
        Log.i(TAG, "search '$query' page $page: ${results.size} results (${pages.joinToString { it.first.size.toString() }})")
        return results to pages.any { it.second }
    }

    // Remembers episodes and adds backup links from our other sources (see shared/CsBackup.kt).
    override suspend fun load(url: String): LoadResponse =
        com.csasian.shared.CsMore.load(this, url) ?: loadPage(url).also { CsBackup.remember(this, it) }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = CsBackup.loadLinks(this, data, subtitleCallback, callback) { callback ->
        loadOwnLinks(data, callback)
    }

    /**
     * Strips the ad clips the YZ/LZ/BF feeds splice into their playlists (see shared/CsAdStrip.kt). The XG
     * and RY feeds cut every few segments with a DISCONTINUITY; CsAdStrip leaves those alone.
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = CsAdStrip.interceptor

    private suspend fun loadPage(url: String): LoadResponse {
        val detailUrl = url.substringBefore('#')
        val document = getPage(detailUrl)
        val content = document.selectFirst(".fed-deta-content")
        val title = content?.selectFirst("h1 a")?.text()?.trim().orEmpty()
            .ifBlank { content?.selectFirst("h1")?.ownText()?.trim().orEmpty() }
            .ifBlank {
                document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore('_')?.trim().orEmpty()
            }
            .ifBlank { throw ErrorLoadingException("找不到影片标题（可能未通过 Cloudflare 验证）") }
        val poster = imageOf(document.selectFirst(".fed-deta-images .fed-list-pics"))
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        val infoLine = { label: String ->
            content?.select("ul > li")?.firstOrNull { it.selectFirst("span.fed-text-muted")?.text()?.contains(label) == true }
        }
        val names = { label: String, limit: Int ->
            infoLine(label)?.select("a")?.map { it.text().trim() }
                ?.filter { it.isNotBlank() && it != "内详" && it != "未知" }?.distinct()?.take(limit).orEmpty()
        }
        val year = names("年份", 1).firstOrNull()?.take(4)?.toIntOrNull()
        val tags = (names("分类", 5) + names("地区", 3)).distinct()
        val actors = (names("主演", 20).map { ActorData(Actor(it)) } +
            names("导演", 3).map { ActorData(Actor(it), roleString = "导演") }).ifEmpty { null }
        // The 剧情简介 tab is the last tab box (the only one when a title has no play lines).
        val plot = document.select(".fed-play-data .fed-tabs-item p").lastOrNull()?.text()?.trim()?.ifBlank { null }
            ?: content?.selectFirst(".fed-part-esan")?.text()?.removePrefix("简介：")?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[name=description]")?.attr("content")
        val status = document.selectFirst(".fed-deta-images .fed-list-remarks")?.text()?.trim()?.ifBlank { null }
        val plotText = listOfNotNull(status?.let { "（$it）" }, plot).joinToString("\n").ifBlank { null }

        val all = parseLines(document)
        val lines = all.filter { it.playable }
        Log.i(TAG, "detail '$title': lines ${all.joinToString { "${it.label}:${it.episodes.size}${if (it.playable) "" else "(skip)"}" }}")
        if (lines.isEmpty()) throw ErrorLoadingException("此影片暂无可直接播放的线路")
        val canonical = lines.maxByOrNull { it.episodes.size }!!.episodes
        val navType = typeOf(document)
        // A film stays a film when a line lists several versions (e.g. "TS中字" then "HD中字").
        val isMovie = navType == TvType.Movie ||
            canonical.size == 1 && (navType == null || navType == TvType.Documentary)
        val type = when {
            isMovie -> navType ?: TvType.Movie
            navType == null || navType == TvType.Movie -> TvType.TvSeries
            else -> navType
        }
        val recommended = recommendations(document, detailUrl, type)

        return if (isMovie) {
            newMovieLoadResponse(title, detailUrl, type, episodeData(detailUrl, canonical.last(), movie = true)) {
                posterUrl = poster
                this.plot = plotText
                this.year = year
                this.tags = tags
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
                this.actors = actors
                this.recommendations = recommended
            }
        }
    }

    private suspend fun loadOwnLinks(data: String, callback: (ExtractorLink) -> Unit): Boolean {
        // data = https://gqc7.top/neirong/{id}.html#ep={normalized name}&nid={n}  (ep is empty for movies)
        val detailUrl = data.substringBefore('#')
        val params = data.substringAfter('#', "").split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val wanted = URLDecoder.decode(params["ep"].orEmpty(), "UTF-8")
        val wantedNid = params["nid"]?.toIntOrNull()

        val lines = parseLines(getPage(detailUrl)).filter { it.playable }
        val targets = lines.mapNotNull { line ->
            val entry = if (wanted.isNotBlank()) {
                line.episodes.firstOrNull { normalizeEpisode(it.name) == wanted }
                    ?: line.episodes.firstOrNull { it.nid == wantedNid && !it.name.contains("全集") }
            } else {
                // Films: the newest version of each line, which the site lists last (HD after TS).
                line.episodes.lastOrNull()
            }
            entry?.let { line.label to it }
        }.sortedBy { (label, _) -> rankOf(label, "") }
        Log.i(TAG, "loadLinks $data: wanted='$wanted' nid=$wantedNid, ${lines.size} lines, ${targets.size} match")
        if (targets.isEmpty()) return false

        // Three play pages at a time, each with a time limit; every link goes to the player as soon as
        // it's found. Ranking is by quality: XG first, then the other clean lines, the ad-spliced ones last.
        val gate = Semaphore(3)
        val sent = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        coroutineScope {
            targets.map { (label, entry) ->
                async {
                    val url = gate.withPermit { withTimeoutOrNull(12_000L) { playUrl(entry) } } ?: return@async
                    if (!sent.add(url)) return@async
                    val rank = rankOf(label, url)
                    callback(
                        newExtractorLink(
                            name,
                            "$name $label",
                            url,
                            if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        ) {
                            referer = "$mainUrl/"
                            quality = RANK_QUALITY.getOrElse(rank) { Qualities.P144.value }
                            headers = mapOf("User-Agent" to userAgent)
                        },
                    )
                }
            }.awaitAll()
        }
        Log.i(TAG, "links for $data: ${sent.size}/${targets.size} lines gave a stream")
        return sent.isNotEmpty()
    }

    /** The direct stream of /bofang/{id}-{sid}-{nid}.html, or null for anything that isn't one. */
    private suspend fun playUrl(entry: EpisodeEntry): String? = runCatching {
        val page = "$mainUrl/bofang/${entry.vodId}-${entry.sid}-${entry.nid}.html"
        val raw = getPage(page).selectFirst("#fed-play-iframe")?.attr("data-play")?.trim().orEmpty()
        val url = if (raw.startsWith("http%3A", true) || raw.startsWith("https%3A", true)) {
            URLDecoder.decode(raw, "UTF-8")
        } else {
            raw
        }
        if (!isDirectStream(url)) {
            Log.i(TAG, "skipped ${entry.vodId}-${entry.sid}-${entry.nid}: '${url.take(100)}'")
            return null
        }
        url
    }.onFailure { Log.i(TAG, "playUrl ${entry.vodId}-${entry.sid}-${entry.nid} failed: $it") }.getOrNull()

    /** Only direct m3u8/mp4 files; never a video-platform page that needs a VIP parser. */
    private fun isDirectStream(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
        if (VIP_HOSTS.any { host == it || host.endsWith(".$it") }) return false
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".mp4")
    }

    /** Fetches through CloudflareKiller and logs whether the challenge was passed. */
    private suspend fun getPage(url: String, home: Boolean = false): Document {
        val fetch = suspend { app.get(url, headers = pageHeaders, interceptor = cloudflare) }
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
        if (challenged) throw ErrorLoadingException("共青春影院 Cloudflare 验证未通过 (HTTP ${response.code})")
        return document
    }

    private fun cards(document: Document, type: TvType?): List<SearchResponse> =
        document.select("ul.fed-list-info > li.fed-list-item").mapNotNull { it.toCard(type) }.distinctBy { it.url }

    /** [type] null = guess from the badge (the mixed 影院热映 row). */
    private fun Element.toCard(type: TvType?): SearchResponse? {
        val pic = selectFirst("a.fed-list-pics[href*=/neirong/]") ?: return null
        val href = fixUrl(pic.attr("href").trim()).takeIf { NEIRONG_REGEX.containsMatchIn(it) } ?: return null
        val title = selectFirst("a.fed-list-title")?.text()?.trim().orEmpty().ifBlank { return null }
        val remark = pic.selectFirst(".fed-list-remarks")?.text()?.trim().orEmpty()
        val year = pic.selectFirst(".fed-list-score")?.text()?.trim()
            ?.takeIf { Regex("""^(19|20)\d\d$""").matches(it) }?.toIntOrNull()
        val finalType = type ?: guessType(remark)
        val poster = imageOf(pic)
        return if (finalType == TvType.Movie) {
            newMovieSearchResponse(title, href, finalType) {
                posterUrl = poster
                this.year = year
                quality = CsQuality.from(remark)
            }
        } else {
            newTvSeriesSearchResponse(title, href, finalType) {
                posterUrl = poster
                this.year = year
                quality = CsQuality.from(remark)
            }
        }
    }

    /** The "相关热播" grid on the detail page (same request), without the current title. */
    private fun recommendations(document: Document, detailUrl: String, type: TvType): List<SearchResponse> =
        runCatching {
            val heading = document.select(".fed-list-head h2").firstOrNull { it.text().trim() == "相关热播" }
                ?: return@runCatching emptyList()
            heading.parent()?.parent()?.select("li.fed-list-item")?.mapNotNull { it.toCard(type) }
                ?.filter { it.url != detailUrl }?.distinctBy { it.url }?.take(MAX_RECOMMENDATIONS).orEmpty()
        }.getOrDefault(emptyList())

    /** The page number of the "尾页" (last page) link, or of the highest page link. */
    private fun lastPage(document: Document, page: Int): Int {
        val numbers = document.select("a[href*=/vodshow/]").mapNotNull { a ->
            PAGE_REGEX.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                ?.let { (a.text().trim() == "尾页") to it }
        }
        return numbers.firstOrNull { it.first }?.second ?: numbers.maxOfOrNull { it.second } ?: page
    }

    private data class EpisodeEntry(val name: String, val vodId: String, val sid: Int, val nid: Int)
    private data class Line(val label: String, val episodes: List<EpisodeEntry>) {
        /** "(弹)" lines (腾视/爱奇/优视) only point to video-platform pages for a VIP parser. */
        val playable: Boolean get() = !label.contains("弹") && VIP_LINE_NAMES.none { label.contains(it) }
    }

    /** Line names come from the tabs, episode lists from the boxes below; both are matched by sid. */
    private fun parseLines(document: Document): List<Line> {
        val tabs = document.select(".fed-drop-tops a[href*=/bofang/]").map { a ->
            val sid = BOFANG_REGEX.find(a.attr("href"))?.groupValues?.get(2)?.toIntOrNull()
            sid to a.ownText().replace(' ', ' ').trim()
        }
        return document.select(".fed-drop-btms .fed-play-item").mapIndexedNotNull { index, box ->
            val episodes = box.select("a[href*=/bofang/]").mapNotNull { link ->
                val (vodId, sid, nid) = BOFANG_REGEX.find(link.attr("href"))?.destructured ?: return@mapNotNull null
                EpisodeEntry(link.text().trim(), vodId, sid.toInt(), nid.toInt())
            }.distinctBy { it.nid }
            if (episodes.isEmpty()) return@mapIndexedNotNull null
            val label = tabs.firstOrNull { it.first == episodes[0].sid }?.second?.ifBlank { null }
                ?: tabs.getOrNull(index)?.second?.ifBlank { null }
                ?: "线路 ${index + 1}"
            Line(label, episodes)
        }
    }

    /** The header nav marks the title's top-level category green; else map the 分类 sub-category. */
    private fun typeOf(document: Document): TvType? {
        val navSlug = document.selectFirst("a.fed-menu-title.fed-text-green[href*=/fenlei/]")?.attr("href")
            ?.let { FENLEI_REGEX.find(it)?.groupValues?.get(1) }
        val subSlug = document.select(".fed-deta-content a[href*=/fenlei/]").firstNotNullOfOrNull {
            FENLEI_REGEX.find(it.attr("href"))?.groupValues?.get(1)
        }
        // A documentary/concert is filed under 综艺 in the nav, so its own sub-category wins there.
        return subSlug?.let { SUB_CATEGORY[it] ?: categoryOf(it)?.type }
            ?: navSlug?.let { categoryOf(it)?.type }
    }

    private fun categoryOf(slug: String): Category? =
        (categories + documentaries).firstOrNull { it.slug == slug }

    /** 0 = XG (1080p, no ads), 1-2 = RY/JS (1080p, no ads), 3-5 = YZ/LZ/BF (ads stripped), 6 = other. */
    private fun rankOf(label: String, url: String): Int {
        val l = label.uppercase()
        return when {
            l.startsWith("XG") || url.contains("xgplay") -> 0
            l.startsWith("RY") || url.contains("ryiplay") -> 1
            l.startsWith("JS") || url.contains("jisu") -> 2
            l.startsWith("YZ") || url.contains("yzzy") -> 3
            l.startsWith("LZ") || url.contains("lzcdn") -> 4
            l.startsWith("BF") || url.contains("fengbao") -> 5
            else -> 6
        }
    }

    private fun episodeData(detailUrl: String, entry: EpisodeEntry, movie: Boolean): String {
        // Movie lines name their entry differently (HD中字 / 正片 / HD国语): films take each line's last entry.
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

    private fun guessType(remark: String): TvType = when {
        Regex("""集|期|更新|完结""").containsMatchIn(remark) -> TvType.TvSeries
        else -> TvType.Movie
    }

    private fun imageOf(element: Element?): String? {
        if (element == null) return null
        return listOf(element.attr("data-original"), element.attr("data-src"), element.attr("src"))
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { fixUrl(it) }
    }

    companion object {
        private const val TAG = "Gqc"
        private const val HOME_ROW = "home"
        private const val MAX_RECOMMENDATIONS = 16
        private val NEIRONG_REGEX = Regex("""/neirong/\d+\.html""")
        private val BOFANG_REGEX = Regex("""/bofang/(\d+)-(\d+)-(\d+)\.html""")
        /** The page field of /vodshow/{slug}--{by}------{page}---.html (and .../wd/{q}.html). */
        private val PAGE_REGEX = Regex("""-(\d+)---(?:\.html|/)""")
        private val FENLEI_REGEX = Regex("""/fenlei/([a-z0-9]+)\.html""")
        private val VIP_LINE_NAMES = listOf("腾视", "爱奇", "优视", "芒果", "哔哩")
        private val VIP_HOSTS = listOf(
            "qq.com", "iqiyi.com", "youku.com", "mgtv.com", "bilibili.com", "le.com", "sohu.com", "pptv.com",
        )
        private val RANK_QUALITY = listOf(
            Qualities.P1080.value, // XG
            Qualities.P720.value, // RY
            Qualities.P720.value, // JS
            Qualities.P480.value, // YZ
            Qualities.P360.value, // LZ
            Qualities.P240.value, // BF
        )

        /** Sub-categories (the 分类 link on a detail page) whose type differs from "series". */
        private val SUB_CATEGORY = mapOf(
            "dongzuo" to TvType.Movie, "xiju" to TvType.Movie, "juqing" to TvType.Movie,
            "kehuan" to TvType.Movie, "aiqing" to TvType.Movie, "kongbu" to TvType.Movie,
            "zhanzheng" to TvType.Movie,
            "mainland" to TvType.TvSeries, "tvb" to TvType.TvSeries, "hanju" to TvType.TvSeries,
            "meiju" to TvType.TvSeries, "japan" to TvType.TvSeries, "other" to TvType.TvSeries,
            "taiwan" to TvType.TvSeries,
            "jilupian" to TvType.Documentary,
            "music" to TvType.Movie,
        )
    }
}
