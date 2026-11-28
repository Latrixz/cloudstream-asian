package com.aigua

import com.csasian.shared.CsBackup
import com.csasian.shared.CsBrowse
import com.csasian.shared.CsCategories
import com.csasian.shared.CsSearch

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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * 爱瓜TV (aigua9.com, formerly 瓜子TV) is a Yii2 site with a few JSON/HTML-fragment endpoints:
 *  - lists:    /video/refresh-cate?channel_id={1 电影|2 电视剧|3 综艺|4 动漫|32 纪录片}&sort={new|hot|score}&page_num={n}
 *              -> JSON data.list[] (video_id, video_name, cover, score, flag, ...)
 *  - search:   /video/refresh-video?keyword={q}&page_num={n} -> HTML fragment of .SSbox cards
 *  - detail:   /video/detail?video_id={id}; one div#srctab-{sourceId} per server with li[data-chapter-id]
 *              (all servers share the same chapter ids)
 *  - playback: /video/play-url?videoId=&chapterId=&sourceId= -> data.urlinfo.resource_url, a clean 1080p m3u8.
 *              Only sourceId 1 (普快线路), the free server, is used. 超快线路 is for logged-in members, so it is
 *              left out on purpose (no member-only servers).
 * The pre-roll ad (data.advertlist.playbefore) is a separate m3u8 and is simply not used.
 */
class AiguaProvider : MainAPI() {
    override var mainUrl = "https://www.aigua9.com"
    override var name = "爱瓜TV Aigua"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
        TvType.Documentary,
    )

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
    private val pageHeaders get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
    private val jsonMapper = ObjectMapper()

    private data class Channel(val id: Int, val label: String, val type: TvType)

    private val channels = listOf(
        Channel(1, "电影", TvType.Movie),
        Channel(2, "电视剧", TvType.TvSeries),
        Channel(3, "综艺", TvType.TvSeries),
        Channel(4, "动漫", TvType.Anime),
        Channel(32, "纪录片", TvType.Documentary),
    )

    /**
     * Category browsing (shared/CsBrowse.kt) with the site's own list page (/video/list?channel_id=):
     * /video/refresh-cate?channel_id=&tag=&area=&year=&sort=, with the 类型 / 地区 / 年份 ids it lists.
     * That endpoint has no keyword, so a search inside a category is the site's normal search.
     */
    private val browse = CsBrowse(this, name, browseSections(), popularSort = "hot") { id, filters, query, page ->
        if (query.isNotBlank()) return@CsBrowse searchOwn(query, page)
        val root = getJson(
            "$mainUrl/video/refresh-cate?channel_id=$id&tag=${filters["tag"].orEmpty()}&area=${filters["area"].orEmpty()}" +
                "&year=${filters["year"].orEmpty()}&page_num=$page&page_size=$PAGE_SIZE" +
                "&sort=${filters[CsCategories.SORT] ?: "new"}&sorttype=desc",
        )
        val data = root?.path("data")
        val items = data?.path("list")?.mapNotNull { it.toListResult() }?.distinctBy { it.url }.orEmpty()
        items to (items.isNotEmpty() && page < (data?.path("total_page")?.asInt(0) ?: 0))
    }

    private fun browseSections(): List<CsBrowse.Section> {
        fun opts(vararg pairs: Pair<String, String>) = pairs.map { (v, label) -> CsCategories.Option(v, label) }
        val tags = mapOf(
            1 to opts(
                "179" to "魔幻", "154" to "动作", "159" to "科幻", "156" to "惊悚", "157" to "犯罪", "161" to "剧情", "160" to "悬疑",
                "226" to "奇幻", "155" to "爱情", "164" to "战争", "169" to "恐怖", "153" to "喜剧", "280" to "冒险", "281" to "灾难",
                "282" to "歌舞", "283" to "动画", "284" to "经典", "285" to "同性", "286" to "网络电影", "178" to "其他",
            ),
            2 to opts(
                "364" to "短剧", "251" to "偶像", "252" to "爱情", "253" to "言情", "254" to "古装", "255" to "历史", "256" to "玄幻",
                "257" to "谍战", "258" to "历险", "259" to "都市", "260" to "科幻", "261" to "军旅", "262" to "喜剧", "263" to "武侠",
                "264" to "江湖", "265" to "罪案", "266" to "青春", "267" to "家庭", "268" to "战争", "269" to "悬疑", "270" to "穿越",
                "271" to "宫廷", "272" to "神话", "273" to "商战", "274" to "警匪", "275" to "动作", "276" to "惊悚", "277" to "剧情",
                "278" to "同性", "279" to "奇幻", "386" to "恐怖", "387" to "纪录片", "388" to "犯罪", "389" to "传记", "231" to "其他",
            ),
            3 to opts(
                "229" to "生活", "228" to "脱口秀", "227" to "真人秀", "168" to "访谈", "287" to "选秀", "288" to "网综",
                "289" to "搞笑", "290" to "竞技", "291" to "情感", "292" to "演唱会", "293" to "晚会", "232" to "其他",
            ),
            4 to opts(
                "363" to "儿童", "167" to "格斗", "165" to "热血", "166" to "机战", "294" to "少女", "295" to "竞技", "296" to "科幻",
                "297" to "爆笑", "298" to "推理", "299" to "魔幻", "300" to "冒险", "301" to "恋爱", "302" to "校园", "303" to "治愈",
                "304" to "泡面", "305" to "穿越", "306" to "灵异", "307" to "耽美", "308" to "剧场版", "170" to "其他",
            ),
            32 to opts(
                "234" to "文化", "309" to "科技", "310" to "历史", "311" to "人物", "312" to "自然", "236" to "军事",
                "237" to "解密", "235" to "探索", "238" to "其他",
            ),
        )
        val areas = opts(
            "18" to "国产", "19" to "欧美", "24" to "日本", "20" to "香港", "21" to "韩国", "31" to "泰国", "23" to "台湾",
            "22" to "英国", "29" to "东南亚", "30" to "其它",
        )
        val years = opts(
            "132" to "2026", "131" to "2025", "130" to "2024", "129" to "2023", "21" to "2022", "22" to "2021", "23" to "2020",
            "24" to "2019", "25" to "2018", "26" to "2017", "27" to "2016", "28" to "2015", "29" to "2014", "30" to "2013",
            "31" to "2012", "32" to "2011", "33" to "2010", "34" to "2009", "35" to "2008", "127" to "更早",
        )
        // The site's sorts, newest first.
        val sorts = opts("new" to "最新添加", "hot" to "人气", "score" to "评分")
        return channels.map { channel ->
            CsBrowse.Section(
                channel.id.toString(), channel.label,
                filters = listOf(
                    CsCategories.Filter("tag", "类型", tags[channel.id].orEmpty()),
                    CsCategories.Filter("area", "地区", areas),
                    CsCategories.Filter("year", "年份", years),
                ),
                sorts = sorts,
            )
        }
    }

    // On CloudStream the "分类 Categories" row comes first.
    override val mainPage = browse.mainPage(
        listOf(MainPageData("首页推荐", HOME_ROW)) +
            channels.flatMap { channel ->
                listOfNotNull(
                    MainPageData("最新${channel.label}", "${channel.id}|new"),
                    MainPageData("热门${channel.label}", "${channel.id}|hot"),
                    // Score rows only where there's enough rated content to make them interesting.
                    if (channel.id == 1 || channel.id == 2) MainPageData("高分${channel.label}", "${channel.id}|score") else null,
                )
            },
    )

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        browse.rowPage(request) ?: com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    // Categories SkyStream Dev shows itself (shared/CsCategories.kt); the engine finds these by name.
    fun csCategories(): String = browse.categoriesJson()

    fun csCategoryPage(categoryId: String, filtersJson: String, query: String, page: Int): SearchResponseList =
        browse.categoryPage(categoryId, filtersJson, query, page)

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == HOME_ROW) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            return newHomePageResponse(
                HomePageList(request.name, homeBanners(), isHorizontalImages = true),
                false,
            )
        }
        val (channelId, sort) = request.data.split('|').let { it[0].toInt() to it.getOrElse(1) { "new" } }
        val root = getJson(
            "$mainUrl/video/refresh-cate?channel_id=$channelId&tag=&area=&year=&page_num=$page" +
                "&page_size=$PAGE_SIZE&sort=$sort&sorttype=desc",
        )
        val data = root?.path("data")
        val items = data?.path("list")?.mapNotNull { it.toListResult() }?.distinctBy { it.url }.orEmpty()
        val hasNext = items.isNotEmpty() && page < (data?.path("total_page")?.asInt(0) ?: 0)
        return newHomePageResponse(request.name, items, hasNext)
    }

    /** The home page's big carousel (wide banner images, titles in the .iconList under it). */
    private suspend fun homeBanners(): List<SearchResponse> {
        val document = app.get("$mainUrl/video/index", headers = pageHeaders).document
        val titles = document.select(".iconList a[href*=video_id=]").associate {
            videoIdOf(it.attr("href")) to it.text().trim()
        }
        return document.select("#imgList a[href*=video_id=]").mapNotNull { link ->
            val id = videoIdOf(link.attr("href")) ?: return@mapNotNull null
            val title = titles[id]?.ifBlank { null } ?: return@mapNotNull null
            newTvSeriesSearchResponse(title, detailUrl(id), TvType.TvSeries) {
                posterUrl = imageOf(link.selectFirst("img"))
            }
        }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    // Page 1 also searches the title in Chinese when it was typed in another language (shared/CsSearch.kt).
    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> =
        CsSearch.withExtra(query, page, lang) { q, p -> searchOwn(q, p) }

    private suspend fun searchOwn(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        val document = searchDocument(query.trim(), page)
        val results = document.select(".SSbox").mapNotNull { box ->
            val link = box.selectFirst(".SSjgName a[href*=video_id=]") ?: return@mapNotNull null
            val id = videoIdOf(link.attr("href")) ?: return@mapNotNull null
            val title = link.text().trim().ifBlank { return@mapNotNull null }
            val type = channelByLabel(box.selectFirst(".SSjgTitle")?.text())?.type ?: TvType.TvSeries
            searchResult(title, detailUrl(id), imageOf(box.selectFirst("img")), type, null)
        }.distinctBy { it.url }
        val pages = document.selectFirst("#parpages")?.attr("value")?.toIntOrNull() ?: page
        return results to (results.isNotEmpty() && page < pages)
    }

    private suspend fun searchDocument(query: String, page: Int): Document {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return app.get(
            "$mainUrl/video/refresh-video?keyword=$encoded&page_num=$page&page_size=$PAGE_SIZE",
            headers = pageHeaders,
        ).document
    }

    // Remembers episodes and adds backup links from our other sources (see shared/CsBackup.kt).
    // A "分类 Categories" card opens the category page (browse.load first: it checks who is asking).
    override suspend fun load(url: String): LoadResponse =
        browse.load(url) ?: com.csasian.shared.CsMore.load(this, url) ?: loadPage(url).also { CsBackup.remember(this, it) }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        browse.reopen(data) // ▶ on a category's own page opens the category page again.
        return CsBackup.loadLinks(this, data, subtitleCallback, callback) {
            loadOwnLinks(data, isCasting, subtitleCallback, callback)
        }
    }

    private val cloudflare by lazy { com.lagradost.cloudstream3.network.CloudflareKiller() }

    private fun looksLikeChallenge(html: String) =
        html.contains("cf-chl", true) || html.contains("Just a moment", true) || html.contains("challenge-platform/h/", true)

    private suspend fun loadPage(url: String): LoadResponse {
        val videoId = queryParam(url, "video_id") ?: throw ErrorLoadingException("无效的影片链接")
        val pageUrl = detailUrl(videoId)
        var response = app.get(pageUrl, headers = pageHeaders)
        if (looksLikeChallenge(response.text)) {
            // Cloudflare sometimes checks phones/TVs that a desktop browser passes straight through.
            response = app.get(pageUrl, headers = pageHeaders, interceptor = cloudflare)
        }
        val document = response.document
        val title = document.selectFirst("h1.player-title .title-txt")?.text()?.trim()
            ?.ifBlank { null }
            ?: document.title().substringBefore(" - ").trim().ifBlank { throw ErrorLoadingException("找不到影片标题") }
        val poster = imageOf(document.selectFirst(".GNbox-xq-img img"))
        val plot = document.select(".GNbox-xq-text > div")
            .firstOrNull { it.ownText().contains("简介") }
            ?.selectFirst("span")?.text()?.trim()?.ifBlank { null }
        val typeSpans = document.select(".GNbox-type span").map { it.text().trim() }.filter { it.isNotBlank() }
        val year = typeSpans.firstOrNull { it.matches(Regex("""\d{4}""")) }?.toIntOrNull()
        val tags = typeSpans.filter { !it.matches(Regex("""\d{4}""")) && it != "更早" && it != "其他" }.distinct()

        // Single-entry titles (e.g. one variety special) have no episode list in the HTML, only the
        // current chapter id in the page script; play that one.
        val episodes = parseEpisodes(document).ifEmpty {
            Regex("""refreshchapterid\s*=\s*'(\d+)'""").find(response.text)?.groupValues?.get(1)
                ?.let { listOf(EpisodeEntry("正片", it)) }.orEmpty()
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("此影片暂无播放来源")
        // The detail page's side ranking is titled "<channel>·排行榜"; that's the only channel marker.
        val channel = document.select(".Title-04").map { it.text() }
            .firstOrNull { it.contains("排行榜") }?.let { channelByLabel(it.substringBefore("·")) }
        val navType = channel?.type
        val isMovie = episodes.size == 1 && (navType == TvType.Movie || navType == null)
        val type = when {
            isMovie -> TvType.Movie
            navType == null || navType == TvType.Movie -> TvType.TvSeries
            else -> navType
        }
        val actors = actorsFromSearch(title, videoId)
        val recommended = recommendations(document, pageUrl, navType ?: type)

        return if (isMovie) {
            newMovieLoadResponse(title, pageUrl, type, episodeUrl(videoId, episodes[0].chapterId)) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.actors = actors
                this.recommendations = recommended
            }
        } else {
            val list = episodes.mapIndexed { position, entry ->
                newEpisode(episodeUrl(videoId, entry.chapterId)) {
                    name = episodeName(entry.name, position)
                    episode = position + 1
                }
            }
            newTvSeriesLoadResponse(title, pageUrl, type, list) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
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
        // data = https://www.aigua9.com/video/detail?video_id={id}&chapter_id={chapter}
        val videoId = queryParam(data, "video_id") ?: return false
        val chapterId = queryParam(data, "chapter_id") ?: return false

        val answers = coroutineScope {
            PLAY_SOURCE_IDS.map { sourceId ->
                async {
                    getJson("$mainUrl/video/play-url?videoId=$videoId&chapterId=$chapterId&sourceId=$sourceId")
                        ?.path("data")?.path("urlinfo")
                }
            }.awaitAll().filterNotNull()
        }
        // label -> url, in server order (普快线路 first), without duplicates.
        val streams = LinkedHashMap<String, String>()
        answers.forEach { info ->
            val resource = info.path("resource_url")
            when {
                resource.isTextual -> resource.asText().trim().takeIf { it.startsWith("http") }?.let { url ->
                    val label = info.path("source_name").asText("").ifBlank { "普快线路" }
                    if (url !in streams.values) streams.putIfAbsent(label, url)
                }
                resource.isObject -> SOURCE_LABELS.forEach { (sourceId, label) ->
                    resource.path(sourceId).asText("").trim().takeIf { it.startsWith("http") }?.let { url ->
                        if (url !in streams.values) streams.putIfAbsent(label, url)
                    }
                }
            }
        }

        streams.forEach { (label, streamUrl) ->
            callback(
                newExtractorLink(
                    name,
                    "$name $label",
                    streamUrl,
                    if (streamUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    referer = "$mainUrl/"
                    quality = Qualities.P1080.value
                    headers = mapOf("User-Agent" to userAgent)
                },
            )
        }
        return streams.isNotEmpty()
    }

    private data class EpisodeEntry(val name: String, val chapterId: String)

    /** Episodes of the first server (all servers share chapter ids), oldest first. */
    private fun parseEpisodes(document: Document): List<EpisodeEntry> {
        val boxes = document.select("div[id^=srctab-]")
        val box = boxes.firstOrNull { it.id() == "srctab-1" }
            ?: boxes.maxByOrNull { it.select("li[data-chapter-id]").size }
            ?: return emptyList()
        val entries = box.select("li[data-chapter-id]").mapNotNull { item ->
            val chapterId = item.attr("data-chapter-id").trim().ifBlank { return@mapNotNull null }
            val name = (item.selectFirst(".select-link") ?: item.selectFirst(".title-link"))?.text()?.trim()
                ?: item.text().trim()
            EpisodeEntry(name, chapterId)
        }.distinctBy { it.chapterId }
        // Variety shows are listed newest first; chapter ids grow with upload time.
        val first = entries.firstOrNull()?.chapterId?.toLongOrNull()
        val last = entries.lastOrNull()?.chapterId?.toLongOrNull()
        return if (first != null && last != null && first > last) entries.reversed() else entries
    }

    /** The detail page lists no cast, but the search card for the same title does. */
    private suspend fun actorsFromSearch(title: String, videoId: String): List<ActorData>? = runCatching {
        val box = searchDocument(title, 1).select(".SSbox").firstOrNull { card ->
            card.selectFirst("a[href*=video_id=]")?.attr("href")?.let { videoIdOf(it) } == videoId
        } ?: return@runCatching null
        box.select(".SSjg p").firstOrNull { it.ownText().contains("主演") }
            ?.select("span")?.map { it.text().trim() }
            ?.filter { it.isNotBlank() && it != "内详" }?.distinct()?.take(20)
            ?.map { ActorData(Actor(it)) }?.ifEmpty { null }
    }.getOrNull()

    /** The detail page's "相关视频" list (same channel); the current title itself is skipped. */
    private fun recommendations(document: Document, pageUrl: String, type: TvType): List<SearchResponse> =
        document.select(".JJXG").mapNotNull { card ->
            val link = card.selectFirst(".JJXG-R > a[href*=video_id=]") ?: return@mapNotNull null
            val id = videoIdOf(link.attr("href")) ?: return@mapNotNull null
            val title = link.text().trim().ifBlank { return@mapNotNull null }
            searchResult(title, detailUrl(id), imageOf(card.selectFirst(".JJXG-L img")), type, null)
        }.filter { it.url != pageUrl }.distinctBy { it.url }.take(MAX_RECOMMENDATIONS)

    private suspend fun getJson(url: String): JsonNode? = runCatching {
        val root = jsonMapper.readTree(app.get(url, headers = pageHeaders).text)
        root.takeIf { it.path("errno").asInt(-1) == 0 }
    }.getOrNull()

    private fun JsonNode.toListResult(): SearchResponse? {
        val id = path("video_id").asText("").ifBlank { return null }
        val title = path("video_name").asText("").trim().ifBlank { return null }
        val type = channels.firstOrNull { it.id == path("channel_id").asInt() }?.type ?: TvType.TvSeries
        val poster = path("cover").asText("").takeIf { it.startsWith("http") }
        val score = path("score").asText("").toDoubleOrNull()?.takeIf { it > 0 }
        return searchResult(title, detailUrl(id), poster, type, score)
    }

    private fun searchResult(title: String, href: String, poster: String?, type: TvType, score: Double?): SearchResponse =
        if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, type) {
                posterUrl = poster
                this.score = score?.let { Score.from10(it) }
            }
        } else {
            newTvSeriesSearchResponse(title, href, type) {
                posterUrl = poster
                this.score = score?.let { Score.from10(it) }
            }
        }

    private fun channelByLabel(label: String?): Channel? {
        val text = label?.trim().orEmpty()
        if (text.isBlank()) return null
        return channels.firstOrNull { text.contains(it.label) }
            ?: if (text.contains("连续剧") || text.contains("剧集")) channels[1] else null
    }

    private fun episodeName(raw: String, position: Int): String {
        val trimmed = raw.trim()
        val digits = Regex("""^第?\s*0*(\d+)\s*[集话話期]?$""").find(trimmed)?.groupValues?.get(1)
        return when {
            trimmed.isBlank() -> "第 ${position + 1} 集"
            digits != null -> "第 $digits 集"
            else -> trimmed
        }
    }

    private fun imageOf(img: Element?): String? {
        if (img == null) return null
        return listOf(img.attr("originalSrc"), img.attr("data-src"), img.attr("src"))
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") && !it.contains("default-cover") }
            ?.let { fixUrl(it) }
    }

    private fun detailUrl(videoId: String) = "$mainUrl/video/detail?video_id=$videoId"

    private fun episodeUrl(videoId: String, chapterId: String) = "${detailUrl(videoId)}&chapter_id=$chapterId"

    private fun videoIdOf(href: String): String? = queryParam(href, "video_id")

    private fun queryParam(url: String, key: String): String? =
        Regex("""[?&]$key=(\d+)""").find(url)?.groupValues?.get(1)

    companion object {
        private const val HOME_ROW = "home"
        private const val PAGE_SIZE = 24
        private const val MAX_RECOMMENDATIONS = 16
        private val PLAY_SOURCE_IDS = listOf("1")
        // Free server only; the member-only "超快线路" (21) is deliberately not used.
        private val SOURCE_LABELS = listOf("1" to "普快线路")
    }
}
