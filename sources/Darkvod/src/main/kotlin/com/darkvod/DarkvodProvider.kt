package com.darkvod

import android.util.Log
import com.csasian.shared.CsAdStrip
import com.csasian.shared.CsBackup
import com.csasian.shared.CsQuality
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 黑夜影院 (darkvod.com): MacCMS with the "mytheme" (myui) template.
 *  - lists:   /video/{slug}/by/{time|hits|score}/page/{n}/  (36 cards; top slugs dianying, lianxuju,
 *             zongyi, dongman, shuangwenduanju; a top slug also lists its sub-categories)
 *  - search:  /tag/wd/{kw}/, /tag/page/{n}/wd/{kw}/  (each result names its 分类)
 *  - detail:  /dianying/{id}/ for every kind of title; the breadcrumb (/mov/{sub}/) gives the category,
 *             a.route-button[data-route-index=r] the servers and #playlist{r} their episodes
 *  - player:  /dyplay/{id}-{r}-{ep}/ embeds `player_aaaa={...}`: "url" (plain, encrypt 0) and "from".
 * hnm3u8 (红牛) and wjm3u8 (无尽) are clean; the other networks splice ad clips into their playlists, so
 * those links play through shared/CsAdStrip. The 伦理片 sub-category is left out everywhere.
 */
class DarkvodProvider : MainAPI() {
    override var mainUrl = "https://darkvod.com"
    override var name = "黑夜影院 Darkvod"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
    private val headers get() = mapOf("User-Agent" to userAgent)
    private val json = ObjectMapper()

    private data class Category(val slug: String, val label: String, val type: TvType, val subs: List<String>)

    private val categories = listOf(
        Category(
            "dianying", "电影", TvType.Movie,
            listOf(
                "dongzuopian", "xijupian", "aiqingpian", "kehuanpian", "kongbupian", "juqingpian", "zhanzhengpian",
                "yingshijieshuo", "xuanyipian", "fanzuipian", "wangluodianying", "guzhuangpian", "jilupian",
                "lishipian", LUNLI_SLUG,
            ),
        ),
        Category("lianxuju", "连续剧", TvType.TvSeries, listOf("guochanju", "gangtaiju", "oumeiju", "rihanju", "taiguoju")),
        Category("zongyi", "综艺", TvType.TvSeries, listOf("daluzongyi", "gangtaizongyi", "oumeizongyi", "rihanzongyi")),
        Category("dongman", "动漫", TvType.Anime, listOf("guochandongman", "oumeidongman", "rihandongman")),
        Category(
            "shuangwenduanju", "爽文短剧", TvType.AsianDrama,
            listOf("chuanyuechongsheng", "doushiyanqing", "fanzhuannixi", "guzhuangxianxia", "nvpinlianai"),
        ),
    )

    /** Sub-category names as shown in search results (分类：xxx) -> type. */
    private val searchLabels = mapOf(
        "电影" to TvType.Movie, "网络电影" to TvType.Movie, "影视解说" to TvType.Movie, "记录片" to TvType.Movie,
        "连续剧" to TvType.TvSeries, "综艺" to TvType.TvSeries, "动漫" to TvType.Anime, "爽文短剧" to TvType.AsianDrama,
        "穿越重生" to TvType.AsianDrama, "都市言情" to TvType.AsianDrama, "反转逆袭" to TvType.AsianDrama,
        "古装仙侠" to TvType.AsianDrama, "女频恋爱" to TvType.AsianDrama,
    )

    // 热门观看 from the home page, then the newest and the most watched of each category.
    override val mainPage = listOf(MainPageData("热门观看", HOME_ROW)) +
        categories.map { MainPageData("最新${it.label}", "${it.slug}|time") } +
        categories.map { MainPageData("${it.label}热播榜", if (it.slug == "dianying") MOVIE_HITS_ROW else "${it.slug}|hits") }

    /**
     * The site's own 电影 hit list is mostly 伦理片 and other adult titles, so 电影热播榜 merges the hit
     * lists of the mainstream film genres instead (one page of each per row page, taken in turn).
     */
    private val movieHitGenres = listOf("dongzuopian", "juqingpian", "kehuanpian", "xijupian", "aiqingpian", "kongbupian")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == HOME_ROW) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val document = getPage("$mainUrl/")
            val panel = document.select(".myui-panel").firstOrNull {
                it.selectFirst(".myui-panel__head h3.title")?.text()?.trim() == "热门观看"
            }
            val blocked = lunliIds("hits", 1..2) + lunliIds("time", 1..1)
            // The slider at the top (#index-hot) plus the 热门观看 panel.
            val boxes = document.select("#index-hot .myui-vodlist__box") + panel?.select(".myui-vodlist__box").orEmpty()
            val items = boxes.mapNotNull { it.toCard(null) }
                .filterNot { it.id in blocked || isAdultTitle(it.title) }
                .distinctBy { it.url }.map { it.response }
            Log.i(TAG, "home row: ${items.size} cards")
            return newHomePageResponse(request.name, items, false)
        }
        if (request.data == MOVIE_HITS_ROW) {
            val lists = coroutineScope {
                movieHitGenres.map { genre ->
                    async {
                        runCatching { getPage("$mainUrl/video/$genre/by/hits/page/$page/") }.getOrNull()?.let { doc ->
                            doc.select(".myui-vodlist__box").mapNotNull { it.toCard(TvType.Movie) } to
                                (page < lastPage(doc, page))
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            val longest = lists.maxOfOrNull { it.first.size } ?: 0
            val items = (0 until longest).flatMap { i -> lists.mapNotNull { it.first.getOrNull(i) } }
                .filterNot { isAdultTitle(it.title) }.distinctBy { it.url }.map { it.response }
            Log.i(TAG, "row '${request.name}' page $page: ${items.size} cards from ${lists.size} genres")
            return newHomePageResponse(request.name, items, lists.any { it.second })
        }
        val slug = request.data.substringBefore('|')
        val sort = request.data.substringAfter('|', "time")
        val category = categories.firstOrNull { it.slug == slug }
        val document = getPage("$mainUrl/video/$slug/by/$sort/page/$page/")
        // 伦理片 is a sub-category of 电影, so it shows up in the 电影 lists. It's a subset ranked the same
        // way, so whatever is on 电影 page p sits on its own pages 1..p (in practice ~1/10 of that).
        val blocked = if (slug == "dianying") lunliIds(sort, lunliPagesFor(page)) else emptySet()
        val cards = document.select(".myui-vodlist__box").mapNotNull { it.toCard(category?.type) }
        val items = cards.filterNot { it.id in blocked || isAdultTitle(it.title) }.distinctBy { it.url }.map { it.response }
        Log.i(TAG, "row '${request.name}' page $page: ${items.size}/${cards.size} cards")
        return newHomePageResponse(request.name, items, cards.isNotEmpty() && page < lastPage(document, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        val keyword = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val url = if (page <= 1) "$mainUrl/tag/wd/$keyword/" else "$mainUrl/tag/page/$page/wd/$keyword/"
        val document = getPage(url)
        val rows = document.select("#searchList > li")
        val results = rows.mapNotNull { row ->
            // <p><span class="text-muted">分类：</span>国产剧<span class="split-line"></span>...
            val after: org.jsoup.nodes.Node? =
                row.select("span.text-muted").firstOrNull { it.text().startsWith("分类") }?.nextSibling()
            val label = (after as? org.jsoup.nodes.TextNode)?.text()?.trim().orEmpty()
            if (isAdultLabel(label)) return@mapNotNull null
            row.toCard(searchLabels[label] ?: typeFromLabel(label))
        }.filterNot { isAdultTitle(it.title) }.distinctBy { it.url }.map { it.response }
        Log.i(TAG, "search '$query' page $page: ${results.size}/${rows.size}")
        return results to (rows.isNotEmpty() && page < lastPage(document, page))
    }

    // Remembers episodes and adds backup links from our other sources (see shared/CsBackup.kt).
    override suspend fun load(url: String): LoadResponse = loadPage(url).also { CsBackup.remember(this, it) }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = CsBackup.loadLinks(this, data, subtitleCallback, callback) {
        loadOwnLinks(data, isCasting, subtitleCallback, callback)
    }

    /** Only the ad-spliced networks go through the ad stripper; 红牛 and 无尽 play untouched. */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val label = extractorLink.name.removePrefix("$name ").substringBefore(' ')
        return if (FEEDS.values.any { it.clean && it.show == label }) null else CsAdStrip.interceptor
    }

    private suspend fun loadPage(url: String): LoadResponse {
        val detailUrl = url.substringBefore('#')
        val document = getPage(detailUrl)
        val thumb = document.selectFirst(".myui-content__thumb")
        val title = (thumb?.selectFirst("img")?.attr("title")?.trim()?.ifBlank { null }
            ?: document.selectFirst(".myui-content__detail h1.title")?.text()?.trim()?.removeSuffix("在线观看"))
            ?.trim().orEmpty().ifBlank { throw ErrorLoadingException("找不到影片标题") }
        val subSlug = document.select(".mbxdh a[href^=/mov/]").lastOrNull()?.attr("href")
            ?.trim('/')?.substringAfterLast('/')
        val detail = document.selectFirst(".myui-content__detail")
        val classTags = detail?.select("a[href*=/tag/class/]")?.map { it.text().trim() }?.filter { it.isNotBlank() }.orEmpty()
        if (subSlug == LUNLI_SLUG || isAdultTitle(title) || classTags.any { isAdultLabel(it) }) {
            Log.i(TAG, "blocked $detailUrl ($subSlug, $title)")
            throw ErrorLoadingException("此影片不提供")
        }

        val posterImg: Element? = thumb?.selectFirst("img")
        val poster = posterImg?.let { imageOf(it) }
        val remark = thumb?.selectFirst(".pic-text")?.text()?.trim()?.ifBlank { null }
        val field = { label: String ->
            detail?.select("p.data")?.firstOrNull { it.selectFirst(".text-muted")?.text()?.startsWith(label) == true }
        }
        val names = { label: String, limit: Int ->
            field(label)?.select("a")?.map { it.text().trim() }
                ?.filter { it.isNotBlank() && it != "内详" && it != "未知" }?.distinct()?.take(limit).orEmpty()
        }
        val actors = (names("主演", 20).map { ActorData(Actor(it)) } +
            names("导演", 3).map { ActorData(Actor(it), roleString = "导演") }).ifEmpty { null }
        val area = detail?.select("a[href*=/area/]")?.map { it.text().trim() }.orEmpty()
        val tags = (classTags + area).filter { it.isNotBlank() }.distinct()
        val year = detail?.select("a[href*=/year/]")?.firstNotNullOfOrNull {
            Regex("""/year/(\d{4})""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }
        val score = document.selectFirst("#rating .branch")?.text()?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }
        // <p class="data"><span>状态：</span>更新至16集 / 共24集<span class="split-line"></span><span>更新：</span>...
        val statusNode: org.jsoup.nodes.Node? = field("状态")?.selectFirst(".text-muted")?.nextSibling()
        val status = (statusNode as? org.jsoup.nodes.TextNode)?.text()?.trim()?.ifBlank { null }
        val plot = listOfNotNull(status?.let { "（$it）" }, plotOf(document, title)).joinToString("\n").ifBlank { null }

        val routes = parseRoutes(document)
        if (routes.isEmpty()) throw ErrorLoadingException("此影片暂无播放来源")
        val episodes = mainEpisodes(routes)
        val categoryType = categories.firstOrNull { it.slug == subSlug || subSlug in it.subs }?.type
        val type = when {
            // Films list their cuts (TC, HD) as entries; numbered entries mean a multi-part title.
            categoryType == TvType.Movie && episodes.size > 1 &&
                episodes.any { episodeKey(it.name).all(Char::isDigit) } -> TvType.TvSeries
            categoryType != null -> categoryType
            episodes.size == 1 -> TvType.Movie
            else -> TvType.TvSeries
        }
        val recommended = recommendations(document, detailUrl, type)
        Log.i(TAG, "load $detailUrl: '$title' $type, ${routes.size} routes, ${episodes.size} eps")

        return if (type == TvType.Movie) {
            newMovieLoadResponse(title, detailUrl, type, "$detailUrl#ep=&i=0") {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score?.let { Score.from10(it) }
                this.actors = actors
                this.recommendations = recommended
            }
        } else {
            val list = episodes.mapIndexed { index, entry ->
                newEpisode("$detailUrl#ep=${URLEncoder.encode(episodeKey(entry.name), "UTF-8")}&i=$index") {
                    name = episodeName(entry.name, index)
                    episode = index + 1
                }
            }
            newTvSeriesLoadResponse(title, detailUrl, type, list) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score?.let { Score.from10(it) }
                this.actors = actors
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
        // data = https://darkvod.com/dianying/{id}/#ep={episode key}&i={position}  (ep is empty for movies)
        val detailUrl = data.substringBefore('#')
        val params = data.substringAfter('#', "").split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val wanted = URLDecoder.decode(params["ep"].orEmpty(), "UTF-8")
        val position = params["i"]?.toIntOrNull() ?: 0

        val routes = parseRoutes(getPage(detailUrl))
        val mainSize = mainEpisodes(routes).size
        val targets = routes.mapNotNull { route ->
            val entry = when {
                // Films: the last entry is the newest cut (HD after TC).
                wanted.isBlank() -> route.episodes.lastOrNull()
                else -> route.episodes.firstOrNull { episodeKey(it.name) == wanted }
                    ?: route.episodes.getOrNull(position)?.takeIf { route.episodes.size == mainSize }
            }
            entry?.let { route to it }
        }.sortedBy { (route, _) -> route.feed?.rank ?: UNKNOWN_RANK }
        Log.i(TAG, "loadLinks $data: '$wanted' #$position, ${routes.size} routes, ${targets.size} match")
        if (targets.isEmpty()) return false

        // Three play pages at a time, each with a time limit; every link goes to the player as soon as
        // it's found. The order in the player comes from the quality: clean networks first.
        val gate = Semaphore(3)
        val sent = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val labels = ConcurrentHashMap<String, Int>()
        coroutineScope {
            targets.map { (route, entry) ->
                async {
                    val (url, from) = gate.withPermit { withTimeoutOrNull(12_000L) { playUrl(entry.path) } }
                        ?: return@async
                    if (from == SKIPPED_FEED || !sent.add(url)) return@async
                    val feed = FEEDS[from] ?: route.feed
                    val base = feed?.show ?: route.label
                    val count = labels.merge(base, 1, Int::plus) ?: 1
                    val label = if (count > 1) "$base $count" else base
                    callback(
                        newExtractorLink(
                            name,
                            "$name $label",
                            url,
                            if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        ) {
                            referer = "$mainUrl/"
                            quality = RANK_QUALITY.getOrElse(feed?.rank ?: UNKNOWN_RANK) { Qualities.P360.value }
                            headers = this@DarkvodProvider.headers
                        },
                    )
                }
            }.awaitAll()
        }
        Log.i(TAG, "links for $data: ${sent.size}/${targets.size} routes gave a stream")
        return sent.isNotEmpty()
    }

    /** `player_aaaa` of a /dyplay/ page: the stream URL and the network code ("from"). */
    private suspend fun playUrl(path: String): Pair<String, String>? = runCatching {
        val html = app.get(fixUrl(path), headers = headers).text
        val start = html.indexOf("player_aaaa")
        if (start < 0) return null
        val open = html.indexOf('{', start)
        val end = html.indexOf("</script>", open)
        if (open < 0 || end < 0) return null
        val config = json.readTree(html.substring(open, end).trim().trimEnd(';'))
        val raw = config.path("url").asText("").trim()
        val url = when (config.path("encrypt").asInt(0)) {
            1 -> unescape(raw)
            2 -> unescape(String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8))
            else -> raw
        }.trim()
        val from = config.path("from").asText("").trim().lowercase()
        if (!url.startsWith("http")) {
            Log.i(TAG, "no stream on $path ($from): '${url.take(80)}'")
            return null
        }
        url to from
    }.onFailure { Log.i(TAG, "playUrl $path failed: $it") }.getOrNull()

    /** JavaScript unescape(): %XX and %uXXXX. */
    private fun unescape(text: String): String =
        Regex("""%u([0-9a-fA-F]{4})|%([0-9a-fA-F]{2})""").replace(text) { m ->
            val hex = m.groupValues[1].ifEmpty { m.groupValues[2] }
            hex.toInt(16).toChar().toString()
        }

    private data class Feed(val code: String, val show: String, val rank: Int, val clean: Boolean)
    private data class Entry(val name: String, val path: String)
    private data class Route(val index: Int, val label: String, val feed: Feed?, val episodes: List<Entry>)

    /** The server tabs with their episode lists; the AI高清 (91jpzyw) tab is left out. */
    private fun parseRoutes(document: Document): List<Route> =
        document.select("a.route-button[data-route-index]").mapNotNull { tab ->
            val index = tab.attr("data-route-index").toIntOrNull() ?: return@mapNotNull null
            val label = tab.attr("title").ifBlank { tab.text() }.trim()
            val feed = FEEDS.values.firstOrNull { it.show == label }
            if (feed?.code == SKIPPED_FEED) return@mapNotNull null
            val all = document.select("#playlist$index a[href*=/dyplay/]").map {
                Entry(it.text().trim(), it.attr("href").trim())
            }.filter { it.path.isNotBlank() }
            val episodes = all.filterNot { it.name.contains("备用") }.ifEmpty { all }.distinctBy { episodeKey(it.name) }
            if (episodes.isEmpty()) null else Route(index, label.ifBlank { "线路 $index" }, feed, episodes)
        }

    /**
     * The episode list shown in the app: the length most routes agree on (ties: the longer list),
     * preferring the best-ranked route of that length. A lone "全集" file never counts.
     */
    private fun mainEpisodes(routes: List<Route>): List<Entry> {
        val usable = routes.filterNot { it.episodes.size == 1 && it.episodes[0].name.contains("全集") }.ifEmpty { routes }
        val size = usable.groupingBy { it.episodes.size }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })
            .first().key
        return usable.filter { it.episodes.size == size }.minBy { it.feed?.rank ?: UNKNOWN_RANK }.episodes
    }

    /** "第01集" / "01" / "1" -> "1"; anything else as is. */
    private fun episodeKey(name: String): String =
        Regex("""^第?\s*0*(\d+)\s*[集话話期]?$""").find(name.trim())?.groupValues?.get(1) ?: name.trim()

    private fun episodeName(raw: String, index: Int): String {
        val key = episodeKey(raw)
        return when {
            key.isBlank() -> "第 ${index + 1} 集"
            key.all { it.isDigit() } -> "第 $key 集"
            else -> raw.trim()
        }
    }

    /** The 剧情简介 panel without the site's generated "X讲述了：" lead-in and its boilerplate trailer. */
    private fun plotOf(document: Document, title: String): String? {
        val full = document.selectFirst("#desc .sketch")?.html()?.substringBefore("<br")
            ?.let { Jsoup.parse(it).text().trim() }
        val text = full?.replace(Regex("""^.{0,24}?${Regex.escape(title)}讲述了[：:]\s*"""), "")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".myui-content__detail .sketch")?.ownText()?.trim()
        return text?.ifBlank { null }
    }

    /** 猜你喜欢 on the detail page (same category, so the same type). */
    private fun recommendations(document: Document, detailUrl: String, type: TvType): List<SearchResponse> =
        runCatching {
            val panel = document.select(".myui-panel").firstOrNull {
                it.selectFirst(".myui-panel__head h3.title")?.text()?.trim() == "猜你喜欢"
            } ?: return@runCatching emptyList()
            panel.select(".myui-vodlist__box").mapNotNull { it.toCard(type) }
                .filter { it.url != detailUrl && !isAdultTitle(it.title) }
                .distinctBy { it.url }.take(MAX_RECOMMENDATIONS).map { it.response }
        }.getOrDefault(emptyList())

    // ---- 伦理片 filter ----

    private val lunliCache = ConcurrentHashMap<String, Pair<Long, Set<String>>>()

    /** Pages of the 伦理片 list (same sort) that can hold the 伦理 titles of 电影 page [page]. */
    private fun lunliPagesFor(page: Int): IntRange {
        if (page <= 4) return 1..page
        val first = maxOf(1, page / 20)
        return first..minOf(page, first + 7)
    }

    /** Ids on the given pages of /video/lunlipian/ (cached for half an hour). */
    private suspend fun lunliIds(sort: String, pages: IntRange): Set<String> = coroutineScope {
        pages.map { page ->
            async {
                val key = "$sort|$page"
                lunliCache[key]?.takeIf { System.currentTimeMillis() - it.first < LUNLI_TTL }?.second
                    ?: runCatching {
                        getPage("$mainUrl/video/$LUNLI_SLUG/by/$sort/page/$page/").select(".myui-vodlist__box")
                            .mapNotNull { it.toCard(TvType.Movie)?.id }.toSet()
                    }.onFailure { Log.i(TAG, "lunli $key failed: $it") }.getOrNull()
                        ?.also { lunliCache[key] = System.currentTimeMillis() to it }
                    ?: emptySet()
            }
        }.awaitAll().flatten().toSet()
    }

    private fun isAdultTitle(title: String) = ADULT_WORDS.any { title.contains(it) }

    private fun isAdultLabel(label: String) = ADULT_WORDS.any { label.contains(it) }

    // ---- helpers ----

    private fun typeFromLabel(label: String): TvType? = when {
        label.endsWith("片") -> TvType.Movie
        label.contains("动漫") -> TvType.Anime
        label.contains("剧") || label.contains("综艺") -> TvType.TvSeries
        else -> null
    }

    /** Backs off and retries when the site answers a burst with HTTP 429. */
    private suspend fun getPage(url: String): Document {
        repeat(2) { attempt ->
            val response = app.get(url, headers = headers)
            if (response.code != 429) return response.document
            delay(1500L * (attempt + 1))
        }
        return app.get(url, headers = headers).document
    }

    /** The mobile pager says "x/y"; otherwise the highest /page/ link. */
    private fun lastPage(document: Document, page: Int): Int =
        document.select(".myui-page .visible-xs a").firstNotNullOfOrNull {
            Regex("""\d+\s*/\s*(\d+)""").find(it.text())?.groupValues?.get(1)?.toIntOrNull()
        } ?: document.select(".myui-page a[href*=/page/]").mapNotNull {
            Regex("""/page/(\d+)""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: page

    private fun imageOf(element: Element): String? {
        val style = Regex("""url\(['"]?([^'")]+)""").find(element.attr("style"))?.groupValues?.get(1)
        return listOf(element.attr("data-original"), element.attr("data-src"), element.attr("src"), style.orEmpty())
            .map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("data:") }?.let { fixUrl(it) }
    }

    private data class Card(val id: String, val title: String, val url: String, val response: SearchResponse)

    /** A list / search card. [type] null = guess from the badge (home row). */
    private fun Element.toCard(type: TvType?): Card? {
        val link = selectFirst("a.myui-vodlist__thumb[href*=/dianying/]") ?: return null
        val href = link.attr("href").trim()
        val id = Regex("""/dianying/(\d+)""").find(href)?.groupValues?.get(1) ?: return null
        val title = (selectFirst(".title a")?.text()?.trim()?.ifBlank { null } ?: link.attr("title").trim())
            .removeSuffix("在线观看").trim().ifBlank { return null }
        val img: Element? = link.selectFirst("img")
        val poster = imageOf(link) ?: img?.let { imageOf(it) }
        val remark = selectFirst(".pic-text")?.text()?.trim().orEmpty()
        val url = fixUrl(href)
        val kind = type ?: guessType(remark)
        val response = if (kind == TvType.Movie) {
            newMovieSearchResponse(title, url, kind) { posterUrl = poster; quality = CsQuality.from(remark) }
        } else {
            newTvSeriesSearchResponse(title, url, kind) { posterUrl = poster; quality = CsQuality.from(remark) }
        }
        return Card(id, title, url, response)
    }

    private fun guessType(remark: String): TvType =
        if (Regex("""集|期|更新|完结|全\d""").containsMatchIn(remark)) TvType.TvSeries else TvType.Movie

    companion object {
        private const val TAG = "Darkvod"
        private const val HOME_ROW = "home"
        private const val MOVIE_HITS_ROW = "movies|hits"
        private const val LUNLI_SLUG = "lunlipian"
        private const val SKIPPED_FEED = "91jpzyw"
        private const val UNKNOWN_RANK = 3
        private const val MAX_RECOMMENDATIONS = 16
        private const val LUNLI_TTL = 30 * 60 * 1000L
        private val ADULT_WORDS = listOf("伦理", "情色", "三级", "福利")

        /** Network code -> the name the site shows for it (playerconfig.js) and its rank. */
        private val FEEDS = listOf(
            Feed("hnm3u8", "高清线上播放", 0, clean = true), // 红牛
            Feed("wjm3u8", "高清极速播放", 0, clean = true), // 无尽
            Feed("ffm3u8", "飞速播放", 1, clean = false), // 非凡
            Feed("bfzym3u8", "极速高清", 1, clean = false), // 暴风
            Feed("ikm3u8", "高清播放器", 1, clean = false), // iKun
            Feed("snm3u8", "线上高清播放", 1, clean = false), // 索尼
            Feed("modum3u8", "超清001", 2, clean = false), // 魔都: slowest to start
            Feed(SKIPPED_FEED, "AI高清", 9, clean = false),
        ).associateBy { it.code }

        private val RANK_QUALITY = listOf(
            Qualities.P1080.value,
            Qualities.P720.value,
            Qualities.P480.value,
            Qualities.P360.value,
        )
    }
}
