package com.uvod

import com.csasian.shared.CsBackup
import com.csasian.shared.CsBrowse
import com.csasian.shared.CsCategories
import com.csasian.shared.CsMore
import com.csasian.shared.CsSearch

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 优视频 UVOD (www.uvod.tv): a Vue site backed by the encrypted JSON API at api-h5.uvod.tv
 * (see [UvodApi] for the crypto). Endpoints used: /video/category, /video/list (category pages,
 * search via `keyword`), /video/rank, /video/info (title + video_fragment_list) and /video/source
 * (one fragment at one quality -> m3u8, ad-free, no headers needed).
 *
 * PLAYBACK POLICY — only what a free guest is entitled to:
 *  - Quality tiers: 1 = 标清 (guest), 2 = 高清 (logged-in user), 3 = 超清 and 4 = 蓝光 (VIP).
 *    The site only enforces VIP inside its own player, and /video/source hands out any tier on
 *    request. We do NOT use that: we request tier 1 only, list only the episodes that offer
 *    tier 1, and drop the answer if the server returns any other tier (it falls back to the
 *    best tier when the requested one doesn't exist). We are a guest (no X-TOKEN), so tier 2 is
 *    not ours either; tiers 3/4 are never requested.
 *  - Titles marked paid:1 are VIP-only: they are filtered out of every list, search and
 *    recommendation, and opening one directly shows a clear message instead of playing.
 *  - The hidden adult category 午夜版 (id 108, show:0) is excluded everywhere.
 *
 * Most titles added since ~2024 only offer tiers 3/4, so the home rows are sorted by popularity
 * (the older, popular catalogue still has the free tier) rather than by date.
 */
class UvodProvider : MainAPI() {
    override var mainUrl = "https://www.uvod.tv"
    override var name = "优视频 UVOD"
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

    private val apiUrl = "https://api-h5.uvod.tv"
    private val userAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /**
     * Category browsing (shared/CsBrowse.kt): our own category page on CloudStream, the app's own
     * category chips on SkyStream Dev. One page is one /video/list call: the section, its 类型 as
     * category_id, region, language, year, the sort and the query as keyword.
     */
    private val browse = CsBrowse.detailed(this, "优视频 UVOD", UvodCategories.sections, popularSort = "hits") { q ->
        val list = api<ListData>(
            "/video/list",
            linkedMapOf(
                "parent_category_id" to q.section.id.toInt(),
                "category_id" to q.filters["genre"]?.toIntOrNull(),
                "region" to q.filters["region"],
                "language" to q.filters["language"],
                "year" to q.filters["year"],
                "keyword" to q.query.trim().ifBlank { null },
                "paid" to 0,
                "sort_field" to (q.sort ?: "hits"),
                "sort_type" to "desc",
                "page" to q.page,
                "pagesize" to PAGE_SIZE,
            ),
        )
        val items = list.list.filter { it.allowed }.distinctBy { it.id }.map { CsBrowse.Title(it.toSearchResponse(), subtitleOf(it)) }
        CsBrowse.Page(items, list.list.isNotEmpty() && q.page * PAGE_SIZE < list.total, list.total)
    }

    // The first row, "分类 Categories" (CloudStream only, see CsBrowse), has one card per section.
    override val mainPage = browse.mainPage(listOf(MainPageData("排行榜", RANK_ROW)) + listOf(
        100 to "电影",
        101 to "电视剧",
        102 to "综艺",
        103 to "动漫",
        105 to "纪录片",
        106 to "粤台专区",
        107 to "儿童",
    ).map { (id, label) -> MainPageData("$label · 热门", id.toString()) })

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        browse.rowPage(request) ?: CsMore.wrap(this, request, page, mainPageOwn(page, request))

    // Categories SkyStream Dev shows itself (shared/CsCategories.kt); the engine finds these by name.
    fun csCategories(): String = browse.categoriesJson()

    fun csCategoryPage(categoryId: String, filtersJson: String, query: String, page: Int): SearchResponseList =
        browse.categoryPage(categoryId, filtersJson, query, page)

    private fun subtitleOf(item: VideoItem): String = listOfNotNull(
        item.year?.trim()?.takeIf { it.isNotBlank() && it != "更早" },
        item.region?.trim()?.takeIf { it.isNotBlank() && it != "其他" },
        item.score.takeIf { it > 0 }?.let { "★ $it" },
    ).joinToString(" · ")

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == RANK_ROW) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val rank = api<RankData>("/video/rank", emptyMap())
            return newHomePageResponse(request.name, rank.list.filter { it.allowed }.map { it.toSearchResponse() }, false)
        }
        val list = api<ListData>(
            "/video/list",
            linkedMapOf(
                "parent_category_id" to request.data.toInt(),
                "paid" to 0,
                "sort_field" to "hits",
                "sort_type" to "desc",
                "page" to page,
                "pagesize" to PAGE_SIZE,
            ),
        )
        val items = list.list.filter { it.allowed }.map { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, list.list.isNotEmpty() && page * PAGE_SIZE < list.total)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> = coroutineScope {
        if (query.isBlank()) return@coroutineScope emptyList<SearchResponse>() to false
        // Page 1 also searches the title in Chinese when it was typed in another language
        // ("The Untamed" → 陈情令; see shared/CsSearch.kt), in parallel; our own results come first.
        val translated = if (page == 1) async { CsSearch.extra(query, lang) { searchOwn(it, 1).first } } else null
        val (own, hasNext) = searchOwn(query, page)
        (own + translated?.await().orEmpty()).distinctBy { it.url } to hasNext
    }

    private suspend fun searchOwn(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val list = api<ListData>(
            "/video/list",
            linkedMapOf(
                "keyword" to query.trim(),
                "paid" to 0,
                "page" to page,
                "pagesize" to PAGE_SIZE,
                "need_fragment" to 1,
            ),
        )
        val results = list.list.filter { it.allowed }.distinctBy { it.id }.map { it.toSearchResponse() }
        return results to (list.list.isNotEmpty() && page * PAGE_SIZE < list.total)
    }

    // Remembers episodes and adds backup links from our other sources (see shared/CsBackup.kt).
    // A card of the "分类 Categories" row opens the category browser (first: it checks who is asking).
    override suspend fun load(url: String): LoadResponse =
        browse.load(url) ?: CsMore.load(this, url) ?: loadPage(url).also { CsBackup.remember(this, it) }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // ▶ on a category's own page opens the category browser again.
        browse.reopen(data)
        if (CsMore.isMore(data)) return false
        return CsBackup.loadLinks(this, data, subtitleCallback, callback) { callback ->
            loadOwnLinks(data, isCasting, subtitleCallback, callback)
        }
    }

    private suspend fun loadPage(url: String): LoadResponse {
        val id = param(url, "video_id")?.toIntOrNull() ?: throw ErrorLoadingException("无效影片地址")
        val info = api<InfoData>("/video/info", linkedMapOf("id" to id))
        val video = info.video ?: throw ErrorLoadingException("影片不存在")
        if (video.parentCategoryId == HIDDEN_CATEGORY) throw ErrorLoadingException("不支持此内容")
        if (video.paid == 1) {
            throw ErrorLoadingException("《${video.title}》是付费（VIP）影片，本插件只提供免费内容")
        }
        val allFragments = info.fragments.orEmpty()
        // Policy (see class comment): only fragments that offer the guest tier.
        // A title with no free tier still opens: its episodes find no link here (only the guest
        // tier is ever requested) and play from our other sources instead (shared/CsBackup.kt).
        val vipOnly = allFragments.none { GUEST_QUALITY in it.qualities }
        val freeFragments = if (vipOnly) allFragments else allFragments.filter { GUEST_QUALITY in it.qualities }
        if (freeFragments.isEmpty()) throw ErrorLoadingException("《${video.title}》暂无可播放的集数")

        val detailUrl = playUrl(video.id)
        val type = typeOf(video.parentCategoryId, video.categoryId, video.multipleFragment)
        val isMovie = allFragments.size == 1 && video.multipleFragment == 0
        val categoryName = categoryNames()[video.categoryId]
        val tags = listOfNotNull(categoryName, video.region, video.language)
            .map { it.trim() }.filter { it.isNotBlank() && it != "其他" }.distinct()
        val actors = (names(video.starring.orEmpty(), 20).map { ActorData(Actor(it)) } +
            names(video.director.orEmpty(), 3).map { ActorData(Actor(it), roleString = "导演") }).ifEmpty { null }
        val notes = listOfNotNull(
            video.state.orEmpty().trim().takeIf { it.isNotBlank() },
            if (vipOnly) {
                "UVOD 只有 VIP 画质，将从我们的其他来源播放 · VIP-only on UVOD, plays from our other sources"
            } else if (!isMovie && freeFragments.size < allFragments.size) {
                "免费标清集数：${freeFragments.size}/${allFragments.size}，其余需 VIP"
            } else {
                null
            },
        )
        val plotText = listOfNotNull(
            notes.takeIf { it.isNotEmpty() }?.joinToString("，", "（", "）"),
            video.description.orEmpty().trim().ifBlank { null },
        ).joinToString("\n").ifBlank { null }
        val score = video.score.takeIf { it > 0 }?.let { Score.from10(it) }
        val year = video.year.orEmpty().trim().take(4).toIntOrNull()
        val recommended = info.related.orEmpty()
            .filter { it.allowed && it.id != video.id }
            .distinctBy { it.id }
            .take(MAX_RECOMMENDATIONS)
            .map { it.toSearchResponse() }

        return if (isMovie) {
            val movieType = if (type == TvType.TvSeries || type == TvType.AsianDrama) TvType.Movie else type
            newMovieLoadResponse(video.title, detailUrl, movieType, episodeUrl(video.id, freeFragments[0].id)) {
                posterUrl = video.pic?.ifBlank { null }
                plot = plotText
                this.year = year
                this.tags = tags
                this.score = score
                this.actors = actors
                this.recommendations = recommended
            }
        } else {
            val episodes = freeFragments.mapIndexed { position, fragment ->
                val number = fragment.symbol.trim().toIntOrNull()
                newEpisode(episodeUrl(video.id, fragment.id)) {
                    name = when {
                        number != null -> "第 $number 集"
                        fragment.symbol.isNotBlank() -> fragment.symbol.trim()
                        else -> "第 ${position + 1} 集"
                    }
                    episode = number ?: (position + 1)
                }
            }
            newTvSeriesLoadResponse(video.title, detailUrl, type, episodes) {
                posterUrl = video.pic?.ifBlank { null }
                plot = plotText
                this.year = year
                this.tags = tags
                this.score = score
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
        // data = https://www.uvod.tv/play?video_id={id}&lid={fragment id}
        val videoId = param(data, "video_id")?.toIntOrNull() ?: return false
        val fragmentId = param(data, "lid")?.toIntOrNull() ?: return false
        val source = api<SourceData>(
            "/video/source",
            linkedMapOf("video_id" to videoId, "video_fragment_id" to fragmentId, "quality" to GUEST_QUALITY),
        ).source ?: return false
        // Policy: the server answers with its best tier when tier 1 doesn't exist; never play that.
        if (source.quality != GUEST_QUALITY || !source.url.startsWith("http")) return false
        callback(
            newExtractorLink(name, "$name 标清", playable(source.url), ExtractorLinkType.M3U8) {
                // The free tier's actual resolution varies per title (448p-720p seen), so no fixed label.
                quality = Qualities.Unknown.value
                headers = mapOf("User-Agent" to userAgent)
            },
        )
        return true
    }

    /**
     * The *.oledsa.com video hosts use an SSL.com certificate that older Android (e.g. Android TV 9-11)
     * doesn't trust, so playback failed with ERR_CERT_AUTHORITY_INVALID. The same hosts serve the
     * same playlist and segments over plain http, which needs no certificate.
     */
    private fun playable(url: String): String =
        if (Regex("^https://[^/]*\\.oledsa\\.com/").containsMatchIn(url)) "http://" + url.removePrefix("https://") else url

    /** POSTs an encrypted, signed request and decrypts the answer. */
    private suspend inline fun <reified T> api(path: String, params: Map<String, Any?>): T {
        val headers = UvodApi.headers(params) + mapOf(
            "User-Agent" to userAgent,
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
        )
        // The site posts the encrypted text as a JSON string.
        val body = mapper.writeValueAsString(UvodApi.encrypt(mapper.writeValueAsString(params))).toRequestBody("application/json".toMediaType())
        val text = app.post("$apiUrl$path", headers = headers, requestBody = body).text.trim()
        val raw = if (text.startsWith("\"")) mapper.readValue(text, String::class.java) else text
        val response = mapper.readValue(UvodApi.decrypt(raw), object : TypeReference<ApiResponse<T>>() {})
        if (response.error != 0) throw ErrorLoadingException(response.message.ifBlank { "API 错误 ${response.error}" })
        return response.data ?: throw ErrorLoadingException("API 无数据")
    }

    private var categoryCache: Map<Int, String>? = null

    /** Sub-category names (喜剧, 国产剧, ...) for tags; an empty map if the request fails. */
    private suspend fun categoryNames(): Map<Int, String> = categoryCache ?: runCatching {
        api<CategoryData>("/video/category", emptyMap()).list
            .filter { it.id != HIDDEN_CATEGORY && it.pid != HIDDEN_CATEGORY }
            .associate { it.id to it.name }
    }.getOrNull()?.also { categoryCache = it }.orEmpty()

    private fun playUrl(id: Int) = "$mainUrl/play?video_id=$id"

    private fun episodeUrl(id: Int, fragmentId: Int) = "$mainUrl/play?video_id=$id&lid=$fragmentId"

    private fun param(url: String, key: String): String? =
        Regex("""[?&#]${Regex.escape(key)}=([^&#]*)""").find(url)?.groupValues?.get(1)

    private fun names(raw: String, limit: Int): List<String> =
        raw.split(Regex("""[，,、/|]|\s{2,}"""))
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "未知" && it != "内详" }
            .distinct()
            .take(limit)

    private fun typeOf(parentId: Int, categoryId: Int, multipleFragment: Int?): TvType = when (parentId) {
        100 -> TvType.Movie
        101 -> if (categoryId in ASIAN_DRAMA_CATEGORIES) TvType.AsianDrama else TvType.TvSeries
        102 -> TvType.TvSeries
        103 -> TvType.Anime
        105 -> TvType.Documentary
        else -> if (multipleFragment == 0) TvType.Movie else TvType.TvSeries
    }

    private val VideoItem.allowed get() =
        paid != 1 && parentCategoryId != HIDDEN_CATEGORY && id > 0 && title.isNotBlank() && !CsCategories.isAdultTitle(title)

    // No poster quality badge: the API only says which tiers exist, not what the free tier is.
    private fun VideoItem.toSearchResponse(): SearchResponse {
        val type = typeOf(parentCategoryId, categoryId, multipleFragment)
        val poster = pic?.ifBlank { null }
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, playUrl(id), type) { posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, playUrl(id), type) { posterUrl = poster }
        }
    }

    companion object {
        private const val RANK_ROW = "rank"
        private const val PAGE_SIZE = 24
        private const val MAX_RECOMMENDATIONS = 16
        private const val GUEST_QUALITY = 1
        private const val HIDDEN_CATEGORY = 108
        private val ASIAN_DRAMA_CATEGORIES = setOf(123, 125, 126, 127)
    }
}

data class ApiResponse<T>(
    val error: Int = -1,
    val message: String = "",
    val data: T? = null,
)

data class CategoryData(@JsonProperty("category_list") val list: List<Category> = emptyList())

data class Category(
    val id: Int = 0,
    val pid: Int = 0,
    val name: String = "",
)

// The API sends null, not [], for an empty list (e.g. a search with no hits).
data class ListData(
    @JsonProperty("video_total") val total: Int = 0,
    @JsonProperty("video_list") val videoList: List<VideoItem>? = null,
) {
    val list: List<VideoItem> get() = videoList.orEmpty()
}

data class RankData(@JsonProperty("video_rank_list") val rankList: List<VideoItem>? = null) {
    val list: List<VideoItem> get() = rankList.orEmpty()
}

data class VideoItem(
    val id: Int = 0,
    @JsonProperty("parent_category_id") val parentCategoryId: Int = 0,
    @JsonProperty("category_id") val categoryId: Int = 0,
    val title: String = "",
    val pic: String? = null,
    val paid: Int = 0,
    @JsonProperty("multiple_fragment") val multipleFragment: Int? = null,
    val year: String? = null,
    val region: String? = null,
    val score: Double = 0.0,
)

data class InfoData(
    val video: VideoDetail? = null,
    @JsonProperty("video_fragment_list") val fragments: List<Fragment>? = null,
    @JsonProperty("related_list") val related: List<VideoItem>? = null,
)

data class VideoDetail(
    val id: Int = 0,
    @JsonProperty("parent_category_id") val parentCategoryId: Int = 0,
    @JsonProperty("category_id") val categoryId: Int = 0,
    val title: String = "",
    val description: String? = null,
    val pic: String? = null,
    val score: Double = 0.0,
    val year: String? = null,
    val director: String? = null,
    val starring: String? = null,
    val region: String? = null,
    val language: String? = null,
    val paid: Int = 0,
    val state: String? = null,
    @JsonProperty("multiple_fragment") val multipleFragment: Int = 0,
)

data class Fragment(
    val id: Int = 0,
    val symbol: String = "",
    val qualities: List<Int> = emptyList(),
)

data class SourceData(@JsonProperty("video_soruce") val source: VideoSource? = null)

data class VideoSource(
    val url: String = "",
    val quality: Int = 0,
)
