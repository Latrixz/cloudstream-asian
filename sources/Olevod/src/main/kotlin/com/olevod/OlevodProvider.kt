package com.olevod

import com.csasian.shared.CsBackup
import com.csasian.shared.CsBrowse
import com.csasian.shared.CsCategories
import com.csasian.shared.CsQuality
import com.csasian.shared.CsSearch

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.security.MessageDigest

class OlevodProvider : MainAPI() {
    override var mainUrl = "https://www.olevod.com"
    override var name = "欧乐影院 Olevod"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val apiUrl = "https://api.olelive.com"
    private val imageUrl = "https://static.olelive.com"

    /**
     * Category browsing (shared/CsBrowse.kt) with the site's own filter page (/nav/...), whose list is
     * /v1/pub/vod/list/true/{2 = free}/{letter}/{area}/{type}/{sub-type}/{year}/{order}/{page}/{size}:
     * 类型 (sub-types), 地区, 年份 and 字母 from /v1/pub/vod/list/type, orders 最新添加 / 最近更新 / 热播 / 评分.
     * Its 午夜影院 (adult) and VIP蓝光影院 (VIP only) types are left out. The list has no keyword, so a search
     * inside a category is the site's search limited to that type.
     */
    private val browse = CsBrowse(this, name, browseSections(), popularSort = "hot") { id, filters, query, page ->
        if (query.isNotBlank()) return@CsBrowse searchOwn(query, page, id)
        val pageSize = 30
        val path = listOf(
            filters["letter"] ?: "0", filters["area"]?.let { enc(it) } ?: "0", id, filters["type"] ?: "0",
            filters["year"] ?: "0", filters[CsCategories.SORT] ?: "desc",
        ).joinToString("/")
        val response = app.get(signed("/v1/pub/vod/list/true/2/$path/$page/$pageSize")).parsed<CatalogueResponse>()
        checkApi(response.code, response.msg)
        val list = response.data?.list.orEmpty()
        list.filterNot { it.vip }.map { it.toSearchResponse() } to (list.isNotEmpty() && page * pageSize < (response.data?.total ?: 0))
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun browseSections(): List<CsBrowse.Section> {
        fun opts(vararg pairs: Pair<String, String>) = pairs.map { (v, label) -> CsCategories.Option(v, label) }
        val subtypes = mapOf(
            "1" to opts(
                "101" to "动作片", "102" to "喜剧片", "103" to "爱情片", "104" to "科幻片", "105" to "恐怖片", "106" to "剧情片",
                "107" to "战争片", "108" to "动画片", "109" to "悬疑片", "110" to "惊悚片", "111" to "纪录片", "112" to "奇幻片",
                "113" to "犯罪片",
            ),
            "2" to opts("202" to "国产剧", "201" to "欧美剧", "203" to "港台剧", "204" to "日韩剧", "1207" to "短剧"),
            "3" to opts("305" to "真人秀", "302" to "音乐", "304" to "搞笑", "301" to "家庭", "303" to "曲艺"),
            "4" to opts("401" to "日本", "402" to "国产", "403" to "欧美"),
            "14" to opts(
                "1209" to "言情", "1210" to "都市", "1211" to "甜宠", "1212" to "逆袭", "1213" to "玄幻", "1214" to "仙侠",
                "1215" to "穿越", "1216" to "重生", "1217" to "王妃", "1218" to "总裁", "1219" to "离婚", "1220" to "其他",
            ),
        )
        val areas = CsBrowse.options(
            "大陆", "香港", "台湾", "美国", "韩国", "日本", "印度", "英国", "法国", "加拿大", "西班牙", "德国", "俄罗斯", "意大利",
            "泰国", "新加坡", "马来西亚", "其它",
        )
        val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val years = (now downTo 2000).map { CsCategories.Option(it.toString()) }
        val letters = ('A'..'Z').map { CsCategories.Option(it.toString()) }
        // Newest added first.
        val sorts = opts("desc" to "最新添加", "update" to "最近更新", "hot" to "热播", "score" to "评分")
        return categories.map { (id, label) ->
            CsBrowse.Section(
                id, label,
                filters = listOf(
                    CsCategories.Filter("type", "类型", subtypes[id].orEmpty()),
                    CsCategories.Filter("area", "地区", areas),
                    CsCategories.Filter("year", "年份", years),
                    CsCategories.Filter("letter", "字母", letters),
                ),
                sorts = sorts,
            )
        }
    }

    private val categories get() = listOf("1" to "电影", "2" to "连续剧", "3" to "综艺", "4" to "动漫", "14" to "短剧")

    // Mirrors the website's category pages (tabs-{id}.html): 热播, 最新添加, then 最近更新.
    // On CloudStream the "分类 Categories" row comes first.
    override val mainPage = browse.mainPage(listOf(
        "1" to "电影",
        "2" to "连续剧",
        "3" to "综艺",
        "4" to "动漫",
        "14" to "短剧",
    ).flatMap { (category, label) ->
        listOf(
            "hot" to "热播",
            "desc" to "最新添加",
            "update" to "最近更新",
        ).map { (order, orderLabel) ->
            MainPageData("$label · $orderLabel", "$category:$order")
        }
    })

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        browse.rowPage(request) ?: com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    // Categories SkyStream Dev shows itself (shared/CsCategories.kt); the engine finds these by name.
    fun csCategories(): String = browse.categoriesJson()

    fun csCategoryPage(categoryId: String, filtersJson: String, query: String, page: Int): SearchResponseList =
        browse.categoryPage(categoryId, filtersJson, query, page)

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        val pageSize = 20
        val category = request.data.substringBefore(':')
        val order = request.data.substringAfter(':', "update")
        val response = app.get(
            signed("/v1/pub/vod/list/true/2/0/0/$category/0/0/$order/$page/$pageSize"),
        ).parsed<CatalogueResponse>()
        checkApi(response.code, response.msg)
        return newHomePageResponse(
            request.name,
            response.data?.list.orEmpty().filterNot { it.vip }.map { it.toSearchResponse() },
            page * pageSize < (response.data?.total ?: 0),
        )
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

    /** The site's search; [typeId] limits it to one type ("0" = all). */
    private suspend fun searchOwn(query: String, page: Int, typeId: String = "0"): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        val pageSize = 30
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val response = app.get(signed("/v1/pub/index/search/$encoded/vod/$typeId/$page/$pageSize"))
            .parsed<SearchResponseData>()
        checkApi(response.code, response.msg)
        val items = response.data?.data.orEmpty()
            .filter { it.type == "vod" }
            .flatMap { it.list.orEmpty() }
        val results = items
            .filterNot { it.vip }
            .distinctBy { it.id }
            .map { it.toSearchResponse() }
        return results to (page * pageSize < (response.data?.total ?: 0) && items.isNotEmpty())
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

    private suspend fun loadPage(url: String): LoadResponse {
        val id = Regex("(?:details-\\d+-)?(\\d+)(?:\\.html)?$")
            .find(url.substringBefore('?'))
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: throw ErrorLoadingException("无效影片地址")
        val response = app.get(signed("/v1/pub/vod/detail/$id/true"))
            .parsed<VodDetailResponse>()
        checkApi(response.code, response.msg)
        val vod = response.data ?: throw ErrorLoadingException("影片不存在")
        val freeEpisodes = vod.urls.orEmpty().filter { !it.vip && it.url.isNotBlank() && it.title != "全集播放" }
        if (freeEpisodes.isEmpty()) throw ErrorLoadingException("此影片没有公开的非 VIP 播放源")

        val poster = poster(vod.picThumb.ifBlank { vod.pic })
        // Wide banner (about 1680x580) used by the site's home slider; empty for many titles.
        val background = poster(vod.picSlide)
        val year = vod.year.toIntOrNull()
        val tags = (listOf(vod.typeIdName, vod.area, vod.lang) + vod.className.split(',', '/', ' '))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val actors = splitNames(vod.actor).map { ActorData(Actor(it)) }
        val score = vod.score.takeIf { it > 0 }?.let { Score.from10(it) }
        val basePlot = vod.content.ifBlank { vod.blurb }.replace(Regex("<[^>]+>"), "").trim()
        val totalEpisodes = vod.urls.orEmpty().count { !it.title.contains("全集") }
        val plotText = if (vod.typeId1 != 1 && freeEpisodes.size < totalEpisodes) {
            "（免费集数：${freeEpisodes.size}/$totalEpisodes，其余为 VIP）\n$basePlot"
        } else {
            basePlot
        }

        val recommended = recommendationsFor(vod)

        return if (vod.typeId1 == 1) {
            val first = freeEpisodes.first()
            newMovieLoadResponse(vod.name, url, TvType.Movie, playbackData(vod.id, first.index)) {
                posterUrl = poster
                backgroundPosterUrl = background
                plot = plotText
                this.year = year
                this.tags = tags
                this.score = score
                this.actors = actors
                this.recommendations = recommended
            }
        } else {
            val type = if (vod.typeId1 == 4) TvType.Anime else TvType.TvSeries
            val episodes = freeEpisodes.mapIndexed { position, episode ->
                newEpisode(playbackData(vod.id, episode.index)) {
                    name = episode.title.ifBlank { "第 ${position + 1} 集" }
                    this.episode = position + 1
                }
            }
            newTvSeriesLoadResponse(vod.name, url, type, episodes) {
                posterUrl = poster
                backgroundPosterUrl = background
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
        // Data is "id:index", but the app may turn an episode's data into a link
        // ("https://www.olevod.com/87818:1"), so read both numbers from the end.
        val match = Regex("(\\d+):(\\d+)\\s*$").find(data) ?: return false
        val id = match.groupValues[1].toIntOrNull() ?: return false
        val index = match.groupValues[2].toIntOrNull() ?: return false
        val response = app.get(signed("/v1/pub/vod/detail/$id/true"))
            .parsed<VodDetailResponse>()
        if (response.code != 0) return false
        val episode = response.data?.urls.orEmpty()
            .firstOrNull { it.index == index && !it.vip && it.url.isNotBlank() }
            ?: return false

        callback(
            newExtractorLink(name, name, episode.url, ExtractorLinkType.M3U8) {
                referer = "$mainUrl/"
                quality = Qualities.Unknown.value
            },
        )
        return true
    }

    /** Same as the website's "猜你喜欢": random titles from the same category and sub-type. */
    private suspend fun recommendationsFor(vod: VodDetail): List<SearchResponse> = runCatching {
        app.get(signed("/v1/pub/vod/random/${vod.typeId1}/${vod.typeId}/16"))
            .parsed<RandomResponse>()
            .data.orEmpty()
            .filter { !it.vip && it.id != vod.id }
            .map { it.toSearchResponse() }
    }.getOrDefault(emptyList())

    private fun signed(path: String): String {
        val separator = if ('?' in path) '&' else '?'
        return "$apiUrl$path${separator}_vv=${signature()}"
    }

    private fun checkApi(code: Int, message: String) {
        if (code != 0) {
            throw ErrorLoadingException(message.ifBlank { "API 错误 $code" })
        }
    }

    private fun VodItem.toSearchResponse(): SearchResponse {
        val loadUrl = "$mainUrl/details-${typeId1}-${id}.html"
        val type = when (typeId1) {
            1 -> TvType.Movie
            4 -> TvType.Anime
            else -> TvType.TvSeries
        }
        return if (type == TvType.Movie) {
            newMovieSearchResponse(name, loadUrl, type) {
                posterUrl = poster(picThumb.ifBlank { pic })
                quality = CsQuality.from(version.ifBlank { remarks })
            }
        } else {
            newTvSeriesSearchResponse(name, loadUrl, type) {
                posterUrl = poster(picThumb.ifBlank { pic })
                quality = CsQuality.from(version.ifBlank { remarks })
            }
        }
    }

    private fun splitNames(value: String): List<String> =
        value.split('、', ',', '，', '/', '|').map { it.trim() }.filter { it.isNotBlank() }.take(15)

    private fun poster(path: String): String? = when {
        path.isBlank() -> null
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith('/') -> "$imageUrl$path"
        else -> "$imageUrl/$path"
    }

    private fun playbackData(id: Int, index: Int) = "$id:$index"

    internal fun signature(epochSeconds: Long = System.currentTimeMillis() / 1000): String {
        val value = epochSeconds.toString()
        val rows = Array(4) { StringBuilder() }
        value.forEach { digit ->
            val bits = digit.code.toString(2)
            rows[0].append(bits.substring(2, 3))
            rows[1].append(bits.substring(3, 4))
            rows[2].append(bits.substring(4, 5))
            rows[3].append(bits.substring(5))
        }
        val inserted = rows.map { row -> row.toString().toLong(2).toString(16).padStart(3, '0') }
        val md5 = MessageDigest.getInstance("MD5").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return md5.substring(0, 3) + inserted[0] +
            md5.substring(6, 11) + inserted[1] +
            md5.substring(14, 19) + inserted[2] +
            md5.substring(22, 27) + inserted[3] +
            md5.substring(30)
    }
}

data class HomeResponse(
    val code: Int = -1,
    val data: HomeData? = null,
    val msg: String = "",
)

data class HomeData(val list: List<VodItem> = emptyList())

data class CatalogueResponse(
    val code: Int = -1,
    val data: CatalogueData? = null,
    val msg: String = "",
)

data class CatalogueData(
    val total: Int = 0,
    val list: List<VodItem> = emptyList(),
)

data class SearchResponseData(
    val code: Int = -1,
    val data: SearchData? = null,
    val msg: String = "",
)

data class SearchData(
    val total: Int = 0,
    val data: List<SearchGroup> = emptyList(),
)

data class SearchGroup(
    val type: String = "",
    val list: List<VodItem>? = null,
)

data class VodItem(
    val id: Int = 0,
    val typeId: Int = 0,
    val typeId1: Int = 0,
    val name: String = "",
    val pic: String = "",
    val picThumb: String = "",
    val remarks: String = "",
    val version: String = "",
    val vip: Boolean = false,
)

data class RandomResponse(
    val code: Int = -1,
    val data: List<VodItem>? = null,
    val msg: String = "",
)

data class VodDetailResponse(
    val code: Int = -1,
    val data: VodDetail? = null,
    val msg: String = "",
)

data class VodDetail(
    val id: Int = 0,
    val name: String = "",
    val typeId: Int = 0,
    val typeId1: Int = 0,
    val pic: String = "",
    val picThumb: String = "",
    val picSlide: String = "",
    val blurb: String = "",
    val content: String = "",
    val year: String = "",
    val score: Double = 0.0,
    @JsonProperty("class") val className: String = "",
    val typeIdName: String = "",
    val area: String = "",
    val lang: String = "",
    val actor: String = "",
    val urls: List<VodEpisode>? = null,
)

data class VodEpisode(
    val index: Int = 0,
    val title: String = "",
    val url: String = "",
    val vip: Boolean = false,
)
