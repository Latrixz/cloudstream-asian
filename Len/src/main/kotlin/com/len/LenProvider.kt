package com.len

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class LenProvider : MainAPI() {
    override var mainUrl = "https://len.tv"
    override var name = "冷映 Len"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
    )

    private val api = "$mainUrl/api"
    private val browserUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val apiHeaders
        get() = mapOf(
            "User-Agent" to browserUserAgent,
            "Referer" to "$mainUrl/",
        )

    // Category = "<endpoint>:<type slug>". The type slug is the site's own /type/<media>/<slug> filter
    // (empty = the whole media type). Rows mirror the list pages' sort buttons:
    // 最热 (sort=views), 最新 (default, newest updated_at first) and 评分 (sort=rating).
    override val mainPage = listOf(
        "movies:" to "电影",
        "tv-series:" to "电视剧",
        "tv-series:chinese-drama" to "国产剧",
        "tv-series:american-drama" to "欧美剧",
        "tv-series:korean-drama" to "韩剧",
        "tv-series:japanese-drama" to "日剧",
        "tv-series:hk-tw-drama" to "港台剧",
        "tv-series:thai" to "泰剧",
        "tv-series:dongman" to "动漫",
    ).flatMap { (category, label) ->
        listOf(
            "views" to "热播",
            "" to "最近更新",
            "rating" to "高分",
        ).map { (order, orderLabel) ->
            MainPageData("$label · $orderLabel", "$category:$order")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val parts = request.data.split(':')
        val endpoint = parts[0]
        val typeSlug = parts.getOrElse(1) { "" }
        val sort = parts.getOrElse(2) { "" }
        val query = buildList {
            add("page=$page")
            add("pageSize=$LIST_PAGE_SIZE")
            if (typeSlug.isNotBlank()) add("type=$typeSlug")
            if (sort.isNotBlank()) add("sort=$sort")
        }.joinToString("&")
        val result = app.get("$api/$endpoint?$query", headers = apiHeaders).parsed<ListApiResponse>()
        val list = result.data ?: throw ErrorLoadingException(result.error.ifBlank { "列表加载失败" })
        val isMovie = endpoint == "movies"
        val items = list.data.map { it.toSearchResponse(isMovie) }
        // The API clamps out-of-range pages to the last page, so rely on totalPages.
        return newHomePageResponse(request.name, items, items.isNotEmpty() && page < list.totalPages)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    private val searchCache = object : LinkedHashMap<String, List<SearchItem>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SearchItem>>?) = size > 4
    }

    /**
     * The site's search page POSTs to /api/search, which returns every match at once (ranked),
     * and then pages through them 24 at a time in the browser. We do the same.
     */
    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val keyword = query.trim()
        // The API rejects keywords shorter than 2 characters.
        if (keyword.length < 2) return emptyList<SearchResponse>() to false
        val all = synchronized(searchCache) { searchCache[keyword] } ?: run {
            val response = app.get(
                "$api/search?q=${URLEncoder.encode(keyword, "UTF-8")}",
                headers = apiHeaders,
            ).parsed<SearchApiResponse>()
            response.results.distinctBy { "${it.resultType}-${it.id}" }
                .also { synchronized(searchCache) { searchCache[keyword] = it } }
        }
        val from = (page - 1) * SEARCH_PAGE_SIZE
        val slice = all.drop(from).take(SEARCH_PAGE_SIZE).map { it.toSearchResponse() }
        return slice to (from + SEARCH_PAGE_SIZE < all.size)
    }

    override suspend fun load(url: String): LoadResponse {
        val (kind, id) = parseVodUrl(url) ?: throw ErrorLoadingException("无效影片地址")
        val detail = fetchDetail(kind, id)
        val isMovie = kind == "movie"
        val categoryName = detail.category?.name ?: detail.categoryName
        val type = typeFor(isMovie, categoryName, detail.categoryId)

        val tags = (listOf(categoryName, detail.region, detail.language) + detail.tags.orEmpty().map(::tagName))
            .mapNotNull { it?.trim() }
            .filter { it.isNotBlank() && it != "其它" }
            .distinct()
        val actors = splitNames(detail.actors).take(20).map { ActorData(Actor(it)) }
        val score = detail.rating?.toDoubleOrNull()?.takeIf { it > 0 }?.let { Score.from10(it) }
        val basePlot = cleanText(detail.description)
        val year = detail.year?.takeIf { it > 0 }
            ?: (detail.releaseDate ?: detail.firstAirDate)?.take(4)?.toIntOrNull()
        val poster = detail.poster?.takeIf { it.startsWith("http") }
        val canonicalUrl = "$mainUrl/vod/$kind-$id"

        val sources = detail.sourceOptions.filter { it.episodes.isNotEmpty() }
        if (sources.isEmpty()) throw ErrorLoadingException("此影片暂无播放来源")

        return if (isMovie) {
            if (sources.none { source -> source.episodes.any { it.isAvailable && it.videoUrl.isNotBlank() } }) {
                throw ErrorLoadingException("此影片没有可免费播放的片源")
            }
            newMovieLoadResponse(detail.title, canonicalUrl, type, canonicalUrl) {
                posterUrl = poster
                plot = basePlot
                this.year = year
                this.tags = tags
                this.score = score
                this.actors = actors
            }
        } else {
            // Sources sometimes lag behind each other; list episodes from the most complete one.
            val canonical = sources.maxByOrNull { it.episodes.size }!!.episodes
                .filter { it.number > 0 }
                .distinctBy { it.season to it.number }
                .sortedWith(compareBy({ it.season }, { it.number }))
            val freeNumbers = sources.flatMap { source ->
                source.episodes.filter { it.isAvailable && it.videoUrl.isNotBlank() }.map { it.season to it.number }
            }.toSet()
            val free = canonical.filter { (it.season to it.number) in freeNumbers }
            if (free.isEmpty()) throw ErrorLoadingException("此影片没有可免费播放的集数")
            val plotText = if (free.size < canonical.size) {
                "（免费集数：${free.size}/${canonical.size}，其余为 VIP）\n$basePlot"
            } else {
                basePlot
            }
            val episodes = free.map { episode ->
                newEpisode("$canonicalUrl?season=${episode.season}&ep=${episode.number}") {
                    name = episodeName(episode.title, episode.number)
                    this.season = episode.season.takeIf { it > 1 }
                    this.episode = episode.number
                }
            }
            newTvSeriesLoadResponse(detail.title, canonicalUrl, type, episodes) {
                posterUrl = poster
                plot = plotText
                this.year = year
                this.tags = tags
                this.score = score
                this.actors = actors
                showStatus = if (detail.isFinished == 1) ShowStatus.Completed else ShowStatus.Ongoing
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // Data is always a full https://len.tv/vod/<kind>-<id>[?season=S&ep=N] URL.
        val (kind, id) = parseVodUrl(data) ?: return false
        val params = data.substringAfter('?', "").split('&').mapNotNull {
            val (k, v) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            k to v
        }.toMap()
        val wantedEpisode = params["ep"]?.toIntOrNull()
        val wantedSeason = params["season"]?.toIntOrNull() ?: 1
        val detail = fetchDetail(kind, id)

        val candidates = detail.sourceOptions.mapNotNull { source ->
            val episode = if (kind == "movie" || wantedEpisode == null) {
                source.episodes.firstOrNull { it.isAvailable && it.videoUrl.isNotBlank() }
            } else {
                source.episodes.firstOrNull {
                    it.number == wantedEpisode && it.season == wantedSeason && it.isAvailable && it.videoUrl.isNotBlank()
                }
            } ?: return@mapNotNull null
            source.name to episode.videoUrl.trim()
        }.distinctBy { it.second }
        if (candidates.isEmpty()) return false

        // Some CDNs drop connections from some networks (nginx 444). Check each playlist in parallel
        // and only offer the ones that answer; if none answer, offer them all anyway.
        val reachable = candidates.amap { candidate ->
            candidate to runCatching {
                app.get(candidate.second, headers = mapOf("User-Agent" to browserUserAgent), timeout = 8L)
                    .text.trimStart().startsWith("#EXTM3U")
            }.getOrDefault(false)
        }
        val chosen = reachable.filter { it.second }.map { it.first }.ifEmpty { candidates }

        chosen.forEach { (sourceName, url) ->
            val isHls = url.contains(".m3u8", ignoreCase = true)
            callback(
                newExtractorLink(
                    name,
                    "$name $sourceName",
                    url,
                    if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    referer = "$mainUrl/"
                    quality = Qualities.Unknown.value
                    headers = mapOf("User-Agent" to browserUserAgent)
                },
            )
        }
        return true
    }

    private suspend fun fetchDetail(kind: String, id: String): VodDetail {
        val endpoint = if (kind == "movie") "movies" else "tv-series"
        val response = app.get("$api/$endpoint/$id", headers = apiHeaders).parsed<DetailApiResponse>()
        return response.data ?: throw ErrorLoadingException(response.error.ifBlank { "影片不存在" })
    }

    /** Accepts /vod/movie-123, /vod/tv-<title>-<year>-123 and the same with a query string. */
    private fun parseVodUrl(url: String): Pair<String, String>? {
        val match = Regex("""/vod/(movie|tv)-(?:[^/?#]*-)?(\d+)(?:[/?#]|$)""").find(url) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun typeFor(isMovie: Boolean, categoryName: String?, categoryId: Int?): TvType = when {
        isMovie && (categoryId == ANIMATION_MOVIE_CATEGORY || categoryName == "动画") -> TvType.AnimeMovie
        isMovie -> TvType.Movie
        categoryName == "动漫" -> TvType.Anime
        else -> TvType.TvSeries
    }

    private fun tagName(tag: Any?): String? = when (tag) {
        is String -> tag
        is Map<*, *> -> tag["name"] as? String
        else -> null
    }

    private fun splitNames(raw: String?): List<String> =
        raw.orEmpty().split(',', '，', '/', '、', '|').map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun cleanText(raw: String?): String =
        raw.orEmpty()
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace(Regex("[ \\t\\u00a0]+"), " ")
            .trim()

    private fun episodeName(raw: String, number: Int): String {
        val trimmed = raw.trim()
        return when {
            trimmed.isBlank() -> "第 $number 集"
            Regex("""^第?\s*0*\d+\s*集?$""").matches(trimmed) -> "第 $number 集"
            else -> trimmed
        }
    }

    private fun ListItem.toSearchResponse(isMovie: Boolean): SearchResponse {
        val kind = if (isMovie) "movie" else "tv"
        val type = typeFor(isMovie, categoryName, categoryId)
        return searchResult(title, "$mainUrl/vod/$kind-$id", poster, type)
    }

    private fun SearchItem.toSearchResponse(): SearchResponse {
        val isMovie = resultType == "movie"
        val kind = if (isMovie) "movie" else "tv"
        return searchResult(title, "$mainUrl/vod/$kind-$id", poster, if (isMovie) TvType.Movie else TvType.TvSeries)
    }

    private fun searchResult(title: String, url: String, poster: String?, type: TvType): SearchResponse {
        val image = poster?.takeIf { it.startsWith("http") }
        return if (type == TvType.Movie || type == TvType.AnimeMovie) {
            newMovieSearchResponse(title, url, type) { posterUrl = image }
        } else {
            newTvSeriesSearchResponse(title, url, type) { posterUrl = image }
        }
    }

    companion object {
        // The list API caps page at 1000, so 60 per page is needed to reach the end of the movie catalogue.
        private const val LIST_PAGE_SIZE = 60
        private const val SEARCH_PAGE_SIZE = 24
        private const val ANIMATION_MOVIE_CATEGORY = 8
    }
}

// ---- /api/movies, /api/tv-series (lists) ----

data class ListApiResponse(
    val success: Boolean = false,
    val error: String = "",
    val data: ListPage? = null,
)

data class ListPage(
    val data: List<ListItem> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 0,
    val totalPages: Int = 0,
)

data class ListItem(
    val id: Long = 0,
    val title: String = "",
    val poster: String? = null,
    @JsonProperty("category_id") val categoryId: Int? = null,
    @JsonProperty("category_name") val categoryName: String? = null,
)

// ---- /api/search ----

data class SearchApiResponse(
    val results: List<SearchItem> = emptyList(),
    val total: Int = 0,
    val error: String = "",
)

data class SearchItem(
    val id: Long = 0,
    val title: String = "",
    val poster: String? = null,
    val resultType: String = "",
)

// ---- /api/movies/{id}, /api/tv-series/{id} ----

data class DetailApiResponse(
    val success: Boolean = false,
    val error: String = "",
    val data: VodDetail? = null,
)

data class VodDetail(
    val id: Long = 0,
    val title: String = "",
    val poster: String? = null,
    val description: String? = null,
    val year: Int? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    val region: String? = null,
    val language: String? = null,
    val actors: String? = null,
    val rating: String? = null,
    @JsonProperty("category_id") val categoryId: Int? = null,
    @JsonProperty("category_name") val categoryName: String? = null,
    val category: VodCategory? = null,
    // Usually empty; may hold strings or {name} objects.
    val tags: List<Any?>? = null,
    @JsonProperty("is_finished") val isFinished: Int? = null,
    @JsonProperty("source_options") val sourceOptions: List<PlaySource> = emptyList(),
)

data class VodCategory(
    val id: Int = 0,
    val name: String = "",
    val slug: String = "",
)

data class PlaySource(
    val key: String = "",
    val name: String = "",
    val episodes: List<PlayEpisode> = emptyList(),
)

data class PlayEpisode(
    val id: Long? = null,
    @JsonProperty("number") val rawNumber: Int? = null,
    @JsonProperty("season") val rawSeason: Int? = null,
    @JsonProperty("title") val rawTitle: String? = null,
    @JsonProperty("videoUrl") val rawVideoUrl: String? = null,
    @JsonProperty("isAvailable") val rawIsAvailable: Boolean? = null,
) {
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val number: Int get() = rawNumber ?: 0
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val season: Int get() = rawSeason ?: 1
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val title: String get() = rawTitle.orEmpty()
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val videoUrl: String get() = rawVideoUrl.orEmpty()
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val isAvailable: Boolean get() = rawIsAvailable != false
}
