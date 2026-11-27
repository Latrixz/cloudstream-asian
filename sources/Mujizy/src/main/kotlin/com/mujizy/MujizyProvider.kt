package com.mujizy

import android.util.Log
import com.csasian.shared.CsAdStrip
import com.csasian.shared.CsBackup
import com.csasian.shared.CsQuality
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
import kotlinx.coroutines.delay
import okhttp3.Interceptor
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * 母鸡资源站 (mujiziyuan.com) is a MacCMS collection network ("mjzy", sold to aggregators as "4K60帧").
 * Everything comes from its public JSON API, /api.php/provide/vod/:
 *  - ac=detail&pg=&pagesize=    latest updates with posters and play lists (ac=list has no posters)
 *  - ac=detail&t={type_id}      one category; a parent id (1-5) includes its sub-categories
 *  - ac=detail&wd={query}       search
 *  - ac=detail&ids={vod_id}     one title
 * vod_play_from / vod_play_url hold the lines ("$$$"), episodes ("#") and "name$url" pairs. The urls are
 * m3u8 playlists on mujizybf08.com (a master playlist pointing at index.m3u8, segments on mujizybfNN.com):
 * 1080p, no spliced ads, no referer needed.
 *
 * The class list has no adult (伦理/福利/写真) or sports/live categories today; [isBlocked] still drops
 * any such category by type_id or name in case the feed adds one.
 */
class MujizyProvider : MainAPI() {
    override var mainUrl = "https://mujiziyuan.com"
    override var name = "母鸡资源 Mujizy"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
    )

    private val api get() = "$mainUrl/api.php/provide/vod/"
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
    private val apiHeaders get() = mapOf("User-Agent" to userAgent, "Accept" to "application/json")
    private val jsonMapper = ObjectMapper()

    override val mainPage = listOf(
        MainPageData("最新更新", ROW_LATEST),
        MainPageData("电影", "$MOVIE"),
        MainPageData("电视剧", ROW_SERIES),
        MainPageData("综艺", "$VARIETY"),
        MainPageData("动漫", "$ANIME"),
        MainPageData("短剧", "$SHORT_DRAMA"),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (items, hasNext) = when (request.data) {
            ROW_LATEST -> listing("ac=detail&pg=$page")
            // The 电视剧 parent (t=2) is mostly 短剧, which has its own row: merge the real drama
            // sub-categories instead, newest first.
            ROW_SERIES -> coroutineScope {
                SERIES_TYPES.map { t -> async { runCatching { listing("ac=detail&t=$t&pg=$page&pagesize=$SERIES_PAGE") }.getOrNull() } }
                    .awaitAll().filterNotNull()
                    .let { parts ->
                        parts.flatMap { it.first }.sortedByDescending { it.second } to parts.any { it.second }
                    }
            }
            else -> listing("ac=detail&t=${request.data}&pg=$page")
        }.let { (list, next) -> list.map { it.first } to next }
        Log.i(TAG, "row '${request.name}' page $page: ${items.size} cards, next=$hasNext")
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val (items, hasNext) = listing("ac=detail&wd=$encoded&pg=$page")
        Log.i(TAG, "search '$query' page $page: ${items.size} results, next=$hasNext")
        return items.map { it.first } to hasNext
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

    /** The mjzy playlists are clean; CsAdStrip leaves such playlists unchanged. */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = CsAdStrip.interceptor

    private suspend fun loadPage(url: String): LoadResponse {
        val id = vodId(url) ?: throw ErrorLoadingException("无效的影片地址")
        val vod = detail(id) ?: throw ErrorLoadingException("找不到影片")
        if (isBlocked(vod)) throw ErrorLoadingException("此分类不提供")
        val title = vod.text("vod_name").ifBlank { throw ErrorLoadingException("找不到影片标题") }
        val detailUrl = detailUrl(id)
        val typeId = vod.path("type_id").asInt(0)
        val lines = playLines(vod)
        if (lines.isEmpty()) throw ErrorLoadingException("此影片暂无播放来源")
        val episodes = lines.maxByOrNull { it.episodes.size }!!.episodes

        val poster = vod.text("vod_pic").ifBlank { null }
        val remarks = vod.text("vod_remarks").ifBlank { null }
        val content = vod.text("vod_content").let { if (it.contains('<')) Jsoup.parse(it).text() else it }.trim()
        val plotText = listOfNotNull(remarks?.let { "（$it）" }, content.ifBlank { null }).joinToString("\n").ifBlank { null }
        val year = vod.text("vod_year").take(4).toIntOrNull()
        val tags = (listOf(vod.text("type_name")) + vod.text("vod_class").split(',', '/', '，') +
            vod.text("vod_area").split(',', '/', '，'))
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val people = { field: String, limit: Int ->
            vod.text(field).split(',', '/', '，').map { it.trim() }
                .filter { it.isNotBlank() && it != "内详" && it != "其他" && it != "未知" }.distinct().take(limit)
        }
        val actors = (people("vod_actor", 20).map { ActorData(Actor(it)) } +
            people("vod_director", 3).map { ActorData(Actor(it), roleString = "导演") }).ifEmpty { null }
        val score = vod.text("vod_score").toDoubleOrNull()?.takeIf { it > 0 }

        val categoryType = typeOf(typeId)
        val isMovie = episodes.size == 1 && (categoryType == TvType.Movie || isWholeFilm(episodes[0].name))
        val type = when {
            isMovie -> TvType.Movie
            categoryType == TvType.Movie -> TvType.TvSeries
            else -> categoryType
        }
        Log.i(TAG, "load '$title' (type_id $typeId, $type): ${lines.size} lines, ${episodes.size} episodes")

        return if (isMovie) {
            newMovieLoadResponse(title, detailUrl, type, episodeData(id, 0)) {
                posterUrl = poster
                this.plot = plotText
                this.year = year
                this.tags = tags
                this.score = score?.let { Score.from10(it) }
                this.actors = actors
            }
        } else {
            val list = episodes.mapIndexed { position, entry ->
                newEpisode(episodeData(id, position)) {
                    name = episodeName(entry.name, position)
                    episode = position + 1
                }
            }
            newTvSeriesLoadResponse(title, detailUrl, type, list) {
                posterUrl = poster
                this.plot = plotText
                this.year = year
                this.tags = tags
                this.score = score?.let { Score.from10(it) }
                this.actors = actors
            }
        }
    }

    private suspend fun loadOwnLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data = https://mujiziyuan.com/vod/{id}#ep={position}
        val id = vodId(data) ?: return false
        val position = data.substringAfter("#ep=", "0").toIntOrNull() ?: 0
        val vod = detail(id) ?: return false
        if (isBlocked(vod)) return false
        val lines = playLines(vod)
        val longest = lines.maxOfOrNull { it.episodes.size } ?: return false
        // Lines with fewer entries are matched by episode name, the full ones by position.
        val wanted = lines.firstOrNull { it.episodes.size == longest }?.episodes?.getOrNull(position)?.name
        val links = lines.mapNotNull { line ->
            val entry = if (line.episodes.size == longest) line.episodes.getOrNull(position)
            else line.episodes.firstOrNull { wanted != null && normalizeEpisode(it.name) == normalizeEpisode(wanted) }
            entry?.let { line.label to it.url }
        }.distinctBy { it.second }
        links.forEachIndexed { index, (label, url) ->
            val shown = if (links.count { it.first == label } > 1) "$label ${index + 1}" else label
            callback(
                newExtractorLink(
                    name,
                    "$name $shown",
                    url,
                    if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    quality = Qualities.P1080.value
                    headers = mapOf("User-Agent" to userAgent)
                },
            )
        }
        Log.i(TAG, "links vod $id ep $position: ${links.size}")
        return links.isNotEmpty()
    }

    private data class PlayEntry(val name: String, val url: String)
    private data class PlayLine(val label: String, val episodes: List<PlayEntry>)

    /** vod_play_from "a$$$b", vod_play_url "ep$url#ep$url$$$ep$url...". */
    private fun playLines(vod: JsonNode): List<PlayLine> {
        val froms = vod.text("vod_play_from").split("$$$")
        return vod.text("vod_play_url").split("$$$").mapIndexedNotNull { index, block ->
            val entries = block.split('#').mapIndexedNotNull { i, item ->
                val name = item.substringBefore('$', "").trim()
                val url = item.substringAfter('$', item).trim()
                if (!url.startsWith("http")) return@mapIndexedNotNull null
                PlayEntry(name.ifBlank { "${i + 1}" }, url)
            }
            if (entries.isEmpty()) return@mapIndexedNotNull null
            PlayLine(lineLabel(froms.getOrNull(index).orEmpty(), index), entries)
        }
    }

    private fun lineLabel(from: String, index: Int): String = when (from.trim().lowercase()) {
        "mjzy", "" -> if (index == 0) "1080P" else "线路 ${index + 1}"
        else -> from.trim()
    }

    /** One API page as cards (with their update time for sorting), and whether another page exists. */
    private suspend fun listing(query: String): Pair<List<Pair<SearchResponse, String>>, Boolean> {
        val root = getJson("$api?$query")
        val page = root.path("page").asInt(1)
        val pageCount = root.path("pagecount").asInt(page)
        val items = root.path("list").mapNotNull { vod ->
            if (isBlocked(vod)) return@mapNotNull null
            toCard(vod)?.let { it to vod.text("vod_time") }
        }
        return items to (page < pageCount)
    }

    private suspend fun detail(id: String): JsonNode? =
        getJson("$api?ac=detail&ids=$id").path("list").firstOrNull()

    /** Retries a couple of times when the API is busy or answers with something that isn't JSON. */
    private suspend fun getJson(url: String): JsonNode {
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                val response = app.get(url, headers = apiHeaders)
                if (response.code == 200) {
                    val node = jsonMapper.readTree(response.text)
                    if (node.has("list")) return node
                }
                last = ErrorLoadingException("API HTTP ${response.code}")
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
            }
            delay(1000L * (attempt + 1))
        }
        Log.w(TAG, "API failed: $url: ${last?.message}")
        throw ErrorLoadingException("母鸡资源接口暂时无法访问")
    }

    private fun toCard(vod: JsonNode): SearchResponse? {
        val id = vod.path("vod_id").asText("").ifBlank { return null }
        val title = vod.text("vod_name").ifBlank { return null }
        val poster = vod.text("vod_pic").ifBlank { null }
        val remarks = vod.text("vod_remarks")
        val categoryType = typeOf(vod.path("type_id").asInt(0))
        val episodeCount = vod.text("vod_play_url").substringBefore("$$$").split('#').count { it.contains('$') }
        val movie = categoryType == TvType.Movie && episodeCount <= 1
        return if (movie) {
            newMovieSearchResponse(title, detailUrl(id), TvType.Movie) {
                posterUrl = poster
                quality = CsQuality.from(remarks)
            }
        } else {
            newTvSeriesSearchResponse(title, detailUrl(id), if (categoryType == TvType.Movie) TvType.TvSeries else categoryType) {
                posterUrl = poster
                quality = CsQuality.from(remarks)
            }
        }
    }

    /** Adult, sports and live categories, by type_id or by name (in case the feed adds new ones). */
    private fun isBlocked(vod: JsonNode): Boolean {
        val typeId = vod.path("type_id").asInt(0)
        return typeId in BLOCKED_TYPES || BLOCKED_NAMES.containsMatchIn(vod.text("type_name"))
    }

    private fun typeOf(typeId: Int): TvType = when (typeId) {
        MOVIE, in MOVIE_TYPES -> TvType.Movie
        14 -> TvType.TvSeries // 美剧
        SERIES, in SERIES_TYPES -> TvType.AsianDrama
        SHORT_DRAMA, in SHORT_DRAMA_TYPES -> TvType.AsianDrama
        VARIETY, in VARIETY_TYPES -> TvType.TvSeries
        ANIME, in ANIME_TYPES -> TvType.Anime
        else -> TvType.TvSeries
    }

    private fun isWholeFilm(name: String) =
        Regex("""正片|HD|高清|蓝光|BD|全集|TC|TS|抢先|中字|1080|4K""", RegexOption.IGNORE_CASE).containsMatchIn(name)

    private fun detailUrl(id: String) = "$mainUrl/vod/$id"

    private fun episodeData(id: String, position: Int) = "${detailUrl(id)}#ep=$position"

    private fun vodId(url: String): String? =
        Regex("""/vod/(\d+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""ids=(\d+)""").find(url)?.groupValues?.get(1)

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

    private fun JsonNode.text(field: String): String =
        path(field).let { if (it.isMissingNode || it.isNull) "" else it.asText("") }.trim()

    companion object {
        private const val TAG = "Mujizy"
        private const val ROW_LATEST = "latest"
        private const val ROW_SERIES = "series"
        private const val SERIES_PAGE = 8

        // Top-level type_ids from ac=list's class list.
        private const val MOVIE = 1
        private const val SERIES = 2
        private const val VARIETY = 3
        private const val ANIME = 4
        private const val SHORT_DRAMA = 5 // listed under 电视剧 (type_pid 2)
        private val MOVIE_TYPES = setOf(6, 7, 8, 9, 10, 11, 12, 20, 23, 39)
        private val SERIES_TYPES = listOf(13, 14, 15, 16, 17, 18, 19) // 陆 美 韩 日 港 台 泰
        private val SHORT_DRAMA_TYPES = setOf(62, 63, 64, 69)
        private val VARIETY_TYPES = setOf(25, 26, 27, 28, 55, 60)
        private val ANIME_TYPES = setOf(29, 30, 31, 44, 61)

        /** No such categories in the class list today; kept for safety. */
        private val BLOCKED_TYPES = emptySet<Int>()
        private val BLOCKED_NAMES = Regex("""伦理|倫理|福利|写真|寫真|情色|成人|三级|三級|里番|体育|體育|赛事|賽事|直播|足球|篮球|籃球|NBA""", RegexOption.IGNORE_CASE)
    }
}
