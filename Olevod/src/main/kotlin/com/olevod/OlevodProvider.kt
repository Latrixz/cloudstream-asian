package com.olevod

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.security.MessageDigest

class OlevodProvider : MainAPI() {
    override var mainUrl = "https://www.olevod.com"
    override var name = "欧乐影院"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val apiUrl = "https://api.olelive.com"
    private val imageUrl = "https://static.olelive.com"

    // Mirrors the website's category pages (tabs-{id}.html): 热播, 最新添加, then 最近更新.
    override val mainPage = listOf(
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
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
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

    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        val pageSize = 30
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val response = app.get(signed("/v1/pub/index/search/$encoded/vod/0/$page/$pageSize"))
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

    override suspend fun load(url: String): LoadResponse {
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

        return if (vod.typeId1 == 1) {
            val first = freeEpisodes.first()
            newMovieLoadResponse(vod.name, url, TvType.Movie, playbackData(vod.id, first.index)) {
                posterUrl = poster
                plot = plotText
                this.year = year
                this.tags = tags
                this.score = score
                this.actors = actors
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
                plot = plotText
                this.year = year
                this.tags = tags
                this.score = score
                this.actors = actors
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parts = data.split(':')
        val id = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val index = parts.getOrNull(1)?.toIntOrNull() ?: return false
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
                quality = getQualityFromString(version.ifBlank { remarks })
            }
        } else {
            newTvSeriesSearchResponse(name, loadUrl, type) {
                posterUrl = poster(picThumb.ifBlank { pic })
                quality = getQualityFromString(version.ifBlank { remarks })
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
