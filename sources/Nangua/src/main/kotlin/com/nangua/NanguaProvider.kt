package com.nangua

import android.util.Log
import com.csasian.shared.CsAdStrip
import com.csasian.shared.CsBackup
import com.csasian.shared.CsBrowse
import com.csasian.shared.CsCategories
import com.csasian.shared.CsQuality
import com.csasian.shared.CsSearch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 南瓜影视 (nangua1.tv) is a Vue app over a plain JSON API (replies are `{code: 0, data: ...}`):
 *  - home:    /api/home -> hero[] and rows[] (each row: channel, name, videos[], ranking[])
 *  - lists:   /api/channel/{movie|drama|variety|anime|short|doc}?page=N&size=60&sort={all|new|hot|rating}
 *             -> {list[], total}; "all" (the site's default) is pinned titles first, then by views
 *  - search:  /api/search?q=..&page=N&size=60 -> {list[], total}
 *  - detail:  /api/video/{id} -> the card fields plus actors[] and sources[] {name, episodes[] {num, name, url}}
 * Cards and details carry channel, category, genres, region, year, rating, latest ("HD中字", "更新至第16集").
 * Streams are plain m3u8s, no Referer needed: 西瓜 (mostly the site's own api.apinangua.com proxy, clean),
 * 天堂 (dytt) and 非凡 (ffzy), both of which splice ad clips into their playlists.
 * The sports channel (World Cup replays) and vip titles are left out, as is adult content.
 */
class NanguaProvider : MainAPI() {
    override var mainUrl = "https://nangua1.tv"
    override var name = "南瓜影视 Nangua"
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

    private val apiUrl get() = "$mainUrl/api"
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
    private val headers get() = mapOf("User-Agent" to userAgent, "Accept" to "application/json")
    private val mapper = ObjectMapper()

    private data class Channel(val key: String, val label: String)

    private val channels = listOf(
        Channel("movie", "电影"),
        Channel("drama", "电视剧"),
        Channel("variety", "综艺"),
        Channel("anime", "动漫"),
        Channel("short", "短剧"),
        Channel("doc", "纪录片"),
    )

    /**
     * Category browsing (shared/CsBrowse.kt) with the site's own channel filters (its Category page):
     * /api/channel/{key}?catalog=1&region=&language=&year=&sort=: 地区 / 语言 / 年份, sorts 最新 / 最热 / 评分 / 综合.
     * (Its 类型 per channel come from /api/catalog/types, which did not answer when this was written.) The channel list has no keyword, so a
     * search inside a category is the site's search, keeping that channel's titles.
     */
    private val browse = CsBrowse(this, name, browseSections(), popularSort = "hot") { id, filters, query, page ->
        if (query.isNotBlank()) {
            val (items, hasNext) = searchOwn(query, page, id)
            return@CsBrowse items to hasNext
        }
        val params = buildString {
            append("catalog=1&page=$page&size=$PAGE_SIZE&sort=").append(filters[CsCategories.SORT] ?: "new")
            listOf("region", "language", "year").forEach { key ->
                filters[key]?.let { append('&').append(key).append('=').append(enc(it)) }
            }
        }
        val data = api("/channel/$id?$params")
        val list = data.path("list").toList()
        list.filter { playable(it) }.map { it.toSearchResult() } to
            (list.isNotEmpty() && page * PAGE_SIZE < data.path("total").asInt(0))
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun browseSections(): List<CsBrowse.Section> {
        fun opts(vararg labels: String) = labels.map { CsCategories.Option(it) }
        val regions = opts(
            "大陆", "香港", "台湾", "美国", "日本", "韩国", "英国", "法国", "德国", "印度", "泰国", "丹麦", "瑞典", "巴西", "加拿大",
            "俄罗斯", "意大利", "比利时", "爱尔兰", "西班牙", "澳大利亚", "其他",
        )
        val languages = opts("国语", "粤语", "英语", "日语", "韩语", "法语", "其他")
        val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val years = (now downTo 2020).map { CsCategories.Option(it.toString()) } + listOf(
            CsCategories.Option("2010s", "10年代"), CsCategories.Option("2000s", "00年代"), CsCategories.Option("1990s", "90年代"),
            CsCategories.Option("1980s", "80年代"), CsCategories.Option("earlier", "更早"),
        )
        // The site's sorts, newest first (its default on the category page).
        val sorts = listOf("new" to "最新", "hot" to "最热", "rating" to "评分", "all" to "综合").map { CsCategories.Option(it.first, it.second) }
        return channels.map { channel ->
            CsBrowse.Section(
                channel.key, channel.label,
                filters = listOf(
                    CsCategories.Filter("region", "地区", regions),
                    CsCategories.Filter("language", "语言", languages),
                    CsCategories.Filter("year", "年份", years),
                ),
                sorts = sorts,
            )
        }
    }

    // 热播推荐 (the home page's hero + 热播 rankings), then per channel the site's default order
    // (pinned, then most watched) and the newest updates. On CloudStream the "分类 Categories" row comes first.
    override val mainPage = browse.mainPage(
        listOf(MainPageData("热播推荐", HOME_ROW)) +
            channels.flatMap { channel ->
                listOf(
                    MainPageData("热门${channel.label}", "${channel.key}|all"),
                    MainPageData("最新${channel.label}", "${channel.key}|new"),
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
            val home = api("/home")
            val hero = home.path("hero").toList()
            val ranked = home.path("rows").flatMap { it.path("ranking").toList() }
                .sortedByDescending { it.path("views").asLong(0) }
            val items = (hero + ranked).filter { playable(it) }.distinctBy { it.path("id").asLong() }
                .map { it.toSearchResult() }
            Log.i(TAG, "home row '${request.name}': ${items.size} cards parsed")
            return newHomePageResponse(request.name, items, false)
        }
        val (channel, sort) = request.data.split('|').let { it[0] to it.getOrElse(1) { "all" } }
        val data = api("/channel/$channel?page=$page&size=$PAGE_SIZE&sort=$sort")
        val list = data.path("list").toList()
        val items = list.filter { playable(it) }.map { it.toSearchResult() }
        val hasNext = list.isNotEmpty() && page * PAGE_SIZE < data.path("total").asInt(0)
        Log.i(TAG, "row '${request.name}' page $page: ${items.size}/${list.size} cards parsed, next=$hasNext")
        return newHomePageResponse(request.name, items, hasNext)
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

    /** The site's search; with [channel], only that channel's titles. */
    private suspend fun searchOwn(query: String, page: Int, channel: String? = null): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val data = api("/search?q=$encoded&page=$page&size=$PAGE_SIZE")
        val list = data.path("list").toList()
        val results = list.filter { playable(it) && (channel == null || it.path("channel").asText("") == channel) }
            .map { it.toSearchResult() }
        Log.i(TAG, "search '$query' page $page: ${results.size}/${list.size} cards parsed")
        return results to (list.isNotEmpty() && page * PAGE_SIZE < data.path("total").asInt(0))
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

    /** Strips the ad clips dytt / ffzy splice into their playlists; clean playlists pass unchanged. */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = CsAdStrip.interceptor

    private suspend fun loadPage(url: String): LoadResponse {
        val id = videoId(url) ?: throw ErrorLoadingException("无效的影片链接")
        val video = api("/video/$id")
        val pageUrl = "$mainUrl/play/$id"
        val title = video.text("title") ?: throw ErrorLoadingException("找不到影片标题")
        if (video.path("vip").asBoolean(false)) throw ErrorLoadingException("此影片仅限会员观看")

        val sources = parseSources(video)
        Log.i(TAG, "detail '$title' ($id): ${sources.size} servers (${sources.joinToString { "${it.name}:${it.episodes.size}" }})")
        if (sources.isEmpty()) throw ErrorLoadingException("此影片暂无播放来源")

        val channel = video.path("channel").asText("")
        val poster = video.text("cover") ?: video.text("wide")
        val background = video.text("hero_image") ?: video.text("wide")
        val description = video.text("description")?.let { Jsoup.parse(it).text().trim() }?.ifBlank { null }
        val latest = video.text("latest")
        val plotText = listOfNotNull(latest?.let { "（$it）" }, description).joinToString("\n").ifBlank { null }
        val year = video.path("year").asInt(0).takeIf { it in 1900..2100 }
        val score = video.path("rating").asDouble(0.0).takeIf { it > 0 }?.let { Score.from10(it) }
        val tags = (listOfNotNull(video.text("category")) +
            video.path("genres").flatMap { it.asText("").split(' ', '/', ',') } +
            listOfNotNull(video.text("region"), video.text("language")))
            .map { it.trim() }.filter { it.isNotBlank() && it != "其它" }.distinct()
        val actors = (video.path("actors").map { Parser.unescapeEntities(it.asText(""), false).trim() }.filter { it.isNotBlank() }.distinct()
            .take(20).map { ActorData(Actor(it)) } +
            (video.text("director")?.let { Parser.unescapeEntities(it, false) }?.split(',', '，', '/')?.map { it.trim() }?.filter { it.isNotBlank() }
                ?.distinct()?.take(3).orEmpty().map { ActorData(Actor(it), roleString = "导演") }))
            .ifEmpty { null }

        // Movies list their versions as entries ("HD中字", "HD国语"); a movie-channel title whose entries
        // are numbered episodes (mini-series filed as 喜剧片 etc.) is shown as a series.
        val keys = canonicalEpisodes(sources)
        val numbered = keys.any { it.key.all(Char::isDigit) }
        // Anime / documentary films show up the same way, as version entries only.
        val isMovie = !numbered && keys.isNotEmpty() &&
            (channel == "movie" || (channel != "variety" && keys.all { isVersionLabel(it.name) }))
        val type = if (isMovie) TvType.Movie else seriesType(channel)

        return if (isMovie) {
            newMovieLoadResponse(title, pageUrl, type, "$pageUrl#ep=") {
                posterUrl = poster
                backgroundPosterUrl = background
                this.plot = plotText
                this.year = year
                this.tags = tags
                this.score = score
                this.actors = actors
            }
        } else {
            val episodes = keys.mapIndexed { position, entry ->
                newEpisode("$pageUrl#ep=${URLEncoder.encode(entry.key, "UTF-8")}") {
                    name = episodeName(entry, position)
                    episode = entry.key.toIntOrNull() ?: (position + 1)
                }
            }
            newTvSeriesLoadResponse(title, pageUrl, type, episodes) {
                posterUrl = poster
                backgroundPosterUrl = background
                this.plot = plotText
                this.year = year
                this.tags = tags
                this.score = score
                this.actors = actors
                showStatus = when {
                    latest == null -> null
                    latest.contains("完结") || latest.contains("全集") -> ShowStatus.Completed
                    latest.contains("更新") -> ShowStatus.Ongoing
                    else -> null
                }
            }
        }
    }

    private suspend fun loadOwnLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data = https://nangua1.tv/play/{id}#ep={episode key}  (empty key = movie: every version)
        val id = videoId(data) ?: return false
        val wanted = URLDecoder.decode(data.substringAfter("#ep=", ""), "UTF-8")
        val sources = parseSources(api("/video/$id"))

        val links = sources.sortedBy { serverRank(it) }.flatMap { source ->
            val entries = if (wanted.isBlank()) source.episodes else source.episodes.filter { it.key == wanted }
            entries.map { source to it }
        }.distinctBy { it.second.url }
        Log.i(TAG, "links for $id ep='$wanted': ${links.size} found (${links.joinToString { "${it.first.name}/${it.second.name}" }})")

        links.forEach { (source, entry) ->
            val ads = serverRank(source) > 0
            val label = buildString {
                append(source.name)
                if (wanted.isBlank() && source.episodes.size > 1) append(" ").append(entry.name)
            }
            callback(
                newExtractorLink(
                    name,
                    "$name $label",
                    entry.url,
                    if (entry.url.contains(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8,
                ) {
                    // The ad-spliced networks are only tried after the clean one.
                    quality = if (ads) Qualities.P144.value else Qualities.Unknown.value
                    headers = mapOf("User-Agent" to userAgent)
                },
            )
        }
        return links.isNotEmpty()
    }

    /** 西瓜 (clean) first, then 天堂 (dytt: CsAdStrip knows its ad clips), then 非凡 (ffzy) and others. */
    private fun serverRank(source: Source): Int = when {
        source.code.startsWith("xigua") || source.name == "西瓜" -> 0
        source.code.startsWith("dytt") -> 1
        else -> 2
    }

    private data class Entry(val key: String, val name: String, val url: String)
    private data class Source(val name: String, val code: String, val episodes: List<Entry>)

    private fun parseSources(video: JsonNode): List<Source> =
        video.path("sources").mapIndexedNotNull { index, node ->
            val episodes = node.path("episodes").mapNotNull { ep ->
                val url = ep.text("url")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val name = ep.text("name") ?: "第${ep.path("num").asInt(0)}集"
                Entry(episodeKey(name), name, url)
            }.distinctBy { it.key }
            if (episodes.isEmpty()) return@mapIndexedNotNull null
            val code = node.path("episodes").firstOrNull()?.path("source")?.asText("").orEmpty().lowercase()
            Source(node.text("name") ?: "线路 ${index + 1}", code, episodes)
        }

    /** Every episode any server has, in order: numbered ones sorted, others in first-seen order. */
    private fun canonicalEpisodes(sources: List<Source>): List<Entry> {
        val all = sources.sortedBy { serverRank(it) }.flatMap { it.episodes }.distinctBy { it.key }
        val (numbered, other) = all.partition { it.key.all(Char::isDigit) }
        if (numbered.isEmpty()) return other
        // A lone "全集"/"HD" file next to real episodes is not an episode of its own.
        return numbered.sortedBy { it.key.toInt() } + other.filter { numbered.size < 2 }
    }

    /** "第01集" / "第1集" / "01" -> "1"; anything else (HD中字, 20261126期) stays as it is. */
    private fun episodeKey(name: String): String {
        val trimmed = name.trim()
        return Regex("""^第?\s*0*(\d{1,4})\s*[集话話期]?$""").find(trimmed)?.groupValues?.get(1) ?: trimmed
    }

    private fun episodeName(entry: Entry, position: Int): String = when {
        entry.key.isBlank() -> "第 ${position + 1} 集"
        entry.key.all(Char::isDigit) -> "第 ${entry.key} 集"
        else -> entry.name.trim()
    }

    /** "HD中字", "HD国语", "TC中字|国语", "正片": a version of a film rather than an episode. */
    private fun isVersionLabel(name: String): Boolean =
        name.isNotBlank() && VERSION_WORDS.replace(name.uppercase(), "").isBlank()

    private fun seriesType(channel: String): TvType = when (channel) {
        "anime" -> TvType.Anime
        "short" -> TvType.AsianDrama
        "doc" -> TvType.Documentary
        else -> TvType.TvSeries
    }

    /** Not sports, not vip-only, not adult. */
    private fun playable(node: JsonNode): Boolean {
        if (node.path("id").asLong(0) <= 0 || node.text("title") == null) return false
        if (node.path("vip").asBoolean(false) || node.path("channel").asText("") == "sports") return false
        val labels = listOfNotNull(node.text("category")) + node.path("genres").map { it.asText("") }
        return labels.none { label -> ADULT.any { label.contains(it) } }
    }

    private fun JsonNode.toSearchResult(): SearchResponse {
        val id = path("id").asLong()
        val title = text("title").orEmpty()
        val channel = path("channel").asText("")
        val latest = text("latest")
        val poster = text("cover") ?: text("wide")
        val href = "$mainUrl/play/$id"
        // Movie-channel mini-series say "已完结" / "更新至第N集" where movies show "HD中字".
        // Anime / documentary films show a version label ("HD国语") like movies do.
        val movie = when (channel) {
            "movie" -> latest?.let { Regex("""更新|完结|集""").containsMatchIn(it) } != true
            "variety", "short" -> false
            else -> latest != null && isVersionLabel(latest)
        }
        return if (movie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                quality = CsQuality.from(latest)
                year = path("year").asInt(0).takeIf { it in 1900..2100 }
            }
        } else {
            newTvSeriesSearchResponse(title, href, seriesType(channel)) {
                posterUrl = poster
                quality = CsQuality.from(latest)
                year = path("year").asInt(0).takeIf { it in 1900..2100 }
            }
        }
    }

    private fun JsonNode.text(field: String): String? =
        path(field).takeIf { it.isTextual }?.asText()?.trim()?.ifBlank { null }

    /** https://nangua1.tv/play/{id}, /video/{id} or a bare id. */
    private fun videoId(url: String): String? =
        Regex("""(?:/play/|/video/|^)(\d+)""").find(url.substringBefore('#'))?.groupValues?.get(1)

    /** GETs an API path and returns `data`, failing on a non-zero `code`. */
    private suspend fun api(path: String): JsonNode {
        val root = mapper.readTree(app.get("$apiUrl$path", headers = headers).text)
        if (root.path("code").asInt(-1) != 0) {
            throw ErrorLoadingException(root.path("message").asText("").ifBlank { "南瓜影视接口出错" })
        }
        return root.path("data")
    }

    companion object {
        private const val TAG = "Nangua"
        private const val HOME_ROW = "home"
        private const val PAGE_SIZE = 60
        private val VERSION_WORDS = Regex(
            """HD|BD|TC|TS|4K|1080P?|720P?|蓝光|藍光|高清|超清|标清|正片|完整版|未删减|无删减|中字|中英|国语|國語|粤语|粵語|英语|日语|韩语|原声|双语|[\s|/·._()（）\-]"""
        )
        private val ADULT = listOf("伦理", "情色", "福利", "写真", "三级", "成人", "倫理")
    }
}
