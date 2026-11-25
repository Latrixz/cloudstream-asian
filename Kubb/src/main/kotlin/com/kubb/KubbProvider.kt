package com.kubb

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import java.security.MessageDigest

class KubbProvider : MainAPI() {
    override var mainUrl = "https://www.kubb.tv"
    override var name = "酷播 Kubb"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Documentary,
    )

    private val listApi = "https://m10.kubb.tv/api"
    private val videoApi = "https://m10.kubb.tv/v3"
    private val browserUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val apiHeaders
        get() = mapOf(
            "User-Agent" to browserUserAgent,
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
        )

    // Mirrors the website's category pages: 热播 (orderby=2), 最新添加 (0), 最近更新 (1).
    override val mainPage = listOf(
        "3" to "电影",
        "4" to "电视剧",
        "5" to "综艺",
        "6" to "动漫",
        "7" to "纪录片",
        "8" to "短剧",
    ).flatMap { (category, label) ->
        listOf(
            "2" to "热播",
            "0" to "最新添加",
            "1" to "最近更新",
        ).map { (order, orderLabel) ->
            MainPageData("$label · $orderLabel", "$category:$order")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val category = request.data.substringBefore(':')
        val order = request.data.substringAfter(':', "2")
        val result = listSearch(tags = "", cid = "0,1,$category", order = order, page = page)
        return newHomePageResponse(
            request.name,
            result.result.map { it.toSearchResponse() },
            page * PAGE_SIZE < result.recordcount,
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
        val result = listSearch(tags = query.trim(), cid = "", order = "0", page = page)
        val items = result.result.distinctBy { it.key }.map { it.toSearchResponse() }
        return items to (page * PAGE_SIZE < result.recordcount && result.result.isNotEmpty())
    }

    private suspend fun listSearch(tags: String, cid: String, order: String, page: Int): SearchResult {
        val response = app.get(
            signed(
                "$listApi/list/Search",
                linkedMapOf(
                    "cinema" to "1",
                    "tags" to tags,
                    "page" to page.toString(),
                    "size" to PAGE_SIZE.toString(),
                    "orderby" to order,
                    "desc" to "1",
                    "cid" to cid,
                    "isserial" to "-1",
                    "isIndex" to "-1",
                    "isfree" to "-1",
                ),
            ),
            headers = apiHeaders,
        ).parsed<SearchApiResponse>()
        checkApi(response.data)
        return response.data?.info?.firstOrNull() ?: SearchResult()
    }

    override suspend fun load(url: String): LoadResponse {
        val key = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (key.isBlank()) throw ErrorLoadingException("无效影片地址")

        val detailResponse = app.get(
            signed(
                "$videoApi/video/detail",
                linkedMapOf(
                    "ispath" to "false",
                    "cinema" to "1",
                    "device" to "1",
                    "player" to "CkPlayer",
                    "tech" to "HLS",
                    "country" to "HU",
                    "lang" to "cns",
                    "region" to "GL.",
                    "id" to key,
                ),
            ),
            headers = apiHeaders,
        ).parsed<DetailApiResponse>()
        checkApi(detailResponse.data)
        val video = detailResponse.data?.info?.firstOrNull()
            ?: throw ErrorLoadingException("影片不存在")

        val playlistResponse = app.get(
            signed(
                "$videoApi/video/languagesplaylist",
                linkedMapOf(
                    "cinema" to "1",
                    "vid" to video.key.ifBlank { key },
                    "lsk" to "1",
                    "taxis" to "1",
                    "cid" to video.cid,
                ),
            ),
            headers = apiHeaders,
        ).parsed<PlaylistApiResponse>()
        checkApi(playlistResponse.data)
        val allEpisodes = playlistResponse.data?.info?.firstOrNull()?.playList.orEmpty()
        val freeEpisodes = allEpisodes.filter { !it.isVip && it.key.isNotBlank() }
        if (freeEpisodes.isEmpty()) throw ErrorLoadingException("此影片没有可播放的非 VIP 片源")

        val type = typeFor(video.cid)
        val tags = (listOf(video.channel, video.regional, video.language) + video.videoType.split(','))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val actors = video.stars.filter { it.isNotBlank() }.take(15).map { ActorData(Actor(it)) }
        val score = video.score.toDoubleOrNull()?.takeIf { it > 0 }?.let { Score.from10(it) }
        val basePlot = video.contxt.replace(Regex("<[^>]+>"), "").trim()
        val plotText = if (type != TvType.Movie && freeEpisodes.size < allEpisodes.size) {
            "（免费集数：${freeEpisodes.size}/${allEpisodes.size}，其余为 VIP）\n$basePlot"
        } else {
            basePlot
        }
        val year = video.postYear.toIntOrNull()
        // The detail API sometimes points at a small "...s.gif" variant that doesn't exist (HTTP 404);
        // the full-size file without the "s" is what the list API uses and always loads.
        val poster = video.imgPath.ifBlank { null }?.replace(Regex("""(/\d{15,})s(\.\w+)$"""), "$1$2")

        return if (type == TvType.Movie) {
            newMovieLoadResponse(video.title, url, type, freeEpisodes.first().key) {
                posterUrl = poster
                plot = plotText
                this.year = year
                this.tags = tags
                this.score = score
                this.actors = actors
            }
        } else {
            val episodes = freeEpisodes.mapIndexed { position, episode ->
                newEpisode(episode.key) {
                    name = episodeName(episode.name, position)
                    this.episode = position + 1
                }
            }
            newTvSeriesLoadResponse(video.title, url, type, episodes) {
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
        // CloudStream turns plain episode data into a URL (https://www.kubb.tv/{key}), so keep only the key.
        val episodeKey = data.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (episodeKey.isBlank()) return false
        val response = app.get(
            signed(
                "$videoApi/video/play",
                linkedMapOf(
                    "cinema" to "1",
                    "id" to episodeKey,
                    "a" to "0",
                    "lang" to "cns",
                    "usersign" to "1",
                    "region" to "GL.",
                    "device" to "1",
                    "isMasterSupport" to "1",
                ),
            ),
            headers = apiHeaders,
        ).parsed<PlayApiResponse>()
        checkApi(response.data)
        val play = response.data?.info?.firstOrNull() ?: return false
        if (play.isPreView) return false

        // Only HLS entries are the real stream; the MP4 entry is a short placeholder clip.
        val streams = play.flvPathList
            .filter { it.isHls && it.result.isNotBlank() }
            .map { it.result }
            .distinct()
        val quality = play.customData?.v?.substringAfter(':', "")?.toIntOrNull() ?: Qualities.Unknown.value
        streams.forEachIndexed { index, stream ->
            callback(
                newExtractorLink(
                    name,
                    if (streams.size > 1) "$name ${index + 1}" else name,
                    stream,
                    ExtractorLinkType.M3U8,
                ) {
                    // The CDN rejects requests that carry a Referer, but requires a browser User-Agent.
                    referer = ""
                    this.quality = quality
                    headers = mapOf("User-Agent" to browserUserAgent)
                },
            )
        }
        return streams.isNotEmpty()
    }

    private fun episodeName(raw: String, position: Int): String {
        val trimmed = raw.trim()
        return when {
            trimmed.isBlank() -> "第 ${position + 1} 集"
            trimmed.all { it.isDigit() } -> "第 ${trimmed.toInt()} 集"
            else -> trimmed
        }
    }

    private fun typeFor(cid: String): TvType = when (cid.split(',').getOrNull(2)) {
        "3" -> TvType.Movie
        "6" -> TvType.Anime
        "7" -> TvType.Documentary
        else -> TvType.TvSeries
    }

    private fun checkApi(data: ApiStatus?) {
        if (data == null) throw ErrorLoadingException("API 无响应")
        if (data.code != 0) throw ErrorLoadingException(data.msg.ifBlank { "API 错误 ${data.code}" })
    }

    private fun ListItem.toSearchResponse(): SearchResponse {
        val loadUrl = "$mainUrl/play/$key"
        val type = typeFor(videoClassID)
        val itemQuality = getQualityFromString(vipResource)
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, loadUrl, type) {
                posterUrl = image.ifBlank { null }
                quality = itemQuality
            }
        } else {
            newTvSeriesSearchResponse(title, loadUrl, type) {
                posterUrl = image.ifBlank { null }
                quality = itemQuality
            }
        }
    }

    /**
     * Reproduces the website's request signature: vv = md5(pub & lowercase(query) & privateKey),
     * where pub is the current time in milliseconds and the private key is picked by pub % 8.
     */
    internal fun signed(base: String, params: Map<String, String>, now: Long = System.currentTimeMillis()): String {
        val all = LinkedHashMap(params).apply { put("v", "1") }
        val rawQuery = all.entries.joinToString("&") { "${it.key}=${it.value}" }
        val encodedQuery = all.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8").replace("+", "%20")}"
        }
        val publicKey = now.toString()
        val privateKey = PRIVATE_KEYS[(now % PRIVATE_KEYS.size).toInt()]
        val digest = MessageDigest.getInstance("MD5")
            .digest("$publicKey&${rawQuery.lowercase()}&$privateKey".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$base?$encodedQuery&vv=$digest&pub=$publicKey"
    }

    companion object {
        private const val PAGE_SIZE = 24
        private val PRIVATE_KEYS = listOf(
            "version001", "vers1on001", "vers1on00i", "bersion001",
            "vcrsion001", "versi0n001", "versio_001", "version0o1",
        )
    }
}

interface ApiStatus {
    val code: Int
    val msg: String
}

data class SearchApiResponse(val ret: Int = 0, val data: SearchApiData? = null)

data class SearchApiData(
    override val code: Int = -1,
    override val msg: String = "",
    val info: List<SearchResult>? = null,
) : ApiStatus

data class SearchResult(
    val recordcount: Int = 0,
    val result: List<ListItem> = emptyList(),
)

data class ListItem(
    val key: String = "",
    val title: String = "",
    val image: String = "",
    val videoClassID: String = "",
    val vipResource: String = "",
    val year: Int = 0,
)

data class DetailApiResponse(val ret: Int = 0, val data: DetailApiData? = null)

data class DetailApiData(
    override val code: Int = -1,
    override val msg: String = "",
    val info: List<VideoDetail>? = null,
) : ApiStatus

data class VideoDetail(
    val key: String = "",
    val title: String = "",
    val imgPath: String = "",
    val contxt: String = "",
    val cid: String = "",
    val channel: String = "",
    val videoType: String = "",
    val regional: String = "",
    val language: String = "",
    val score: String = "",
    @JsonProperty("post_Year") val postYear: String = "",
    val stars: List<String> = emptyList(),
)

data class PlaylistApiResponse(val ret: Int = 0, val data: PlaylistApiData? = null)

data class PlaylistApiData(
    override val code: Int = -1,
    override val msg: String = "",
    val info: List<Playlist>? = null,
) : ApiStatus

data class Playlist(val playList: List<PlaylistEpisode> = emptyList())

data class PlaylistEpisode(
    val key: String = "",
    val name: String = "",
    val isVip: Boolean = false,
)

data class PlayApiResponse(val ret: Int = 0, val data: PlayApiData? = null)

data class PlayApiData(
    override val code: Int = -1,
    override val msg: String = "",
    val info: List<PlayInfo>? = null,
) : ApiStatus

data class PlayInfo(
    val isPreView: Boolean = false,
    val customData: PlayCustomData? = null,
    val flvPathList: List<StreamPath> = emptyList(),
)

data class PlayCustomData(val v: String = "")

data class StreamPath(
    val isHls: Boolean = false,
    val result: String = "",
)
