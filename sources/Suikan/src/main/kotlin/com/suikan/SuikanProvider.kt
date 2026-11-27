package com.suikan

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
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 随便看影院 (suikan365.vip) is a Vue SSR app backed by a JSON API:
 *  - lists:   /api/movies?page=N&pageSize=24&typeId=T&sort={updatedAt|monthViews|views|score|createdAt}
 *             (typeId 1 movies, 2 TV, 3 variety, 4 anime/kids, 5 sports (skipped), 6 short dramas)
 *  - banners: /api/banners?active=true&sort=order
 *  - search:  /api/movies/search?keyword=..&page=N&per_page=24&with_total=1 -> {results, pages}
 *  - detail:  /api/movies/{id} -> MacCMS-style vod_play_from ("qiyi$$$xiguam3u8$$$...") and video_url
 *             (servers split by $$$, episodes by #, "name$url"); 403 = blocked for copyright.
 *  - play:    m3u8 servers play directly. Official-platform links (qiyi/mgtv/qq/youku pages) are resolved
 *             by /api/movies/{id}/play-url?source=&episode= (or /api/movies/parse-url?url=) to a signed,
 *             ad-free m3u8.
 *  - related: /api/movies/guess-you-like?movie_class=..&excludeId=..
 * The "dyttm3u8" (天堂) streams have short ad clips spliced in, so they're listed last.
 */
class SuikanProvider : MainAPI() {
    override var mainUrl = "https://www.suikan365.vip"
    override var name = "随便看 Suikan"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
    )

    private val userAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private val apiHeaders get() = mapOf("User-Agent" to userAgent, "Accept" to "application/json")
    private val jsonMapper = ObjectMapper()

    private data class Category(val typeId: Int, val label: String, val type: TvType)

    private val categories = listOf(
        Category(1, "电影", TvType.Movie),
        Category(2, "电视剧", TvType.TvSeries),
        Category(3, "综艺", TvType.TvSeries),
        Category(4, "动漫", TvType.Anime),
        Category(6, "短剧", TvType.AsianDrama),
    )

    // 正在热播 (the site's banner carousel), then 最新X / X热门 (most watched this month) per category.
    override val mainPage = listOf(MainPageData("正在热播", BANNER_ROW)) +
        categories.flatMap { category ->
            listOf(
                MainPageData("最新${category.label}", "${category.typeId}|updatedAt"),
                MainPageData("${category.label}热门", "${category.typeId}|monthViews"),
            )
        }

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == BANNER_ROW) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val root = getJson("$mainUrl/api/banners?active=true&sort=order&pageSize=12")
            val items = root?.path("items")?.mapNotNull { banner ->
                val id = Regex("""/movie/(\d+)""").find(banner.text("link_url"))?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val title = banner.text("title").ifBlank { return@mapNotNull null }
                val remark = banner.text("remarks")
                searchResult(title, detailUrl(id), banner.text("image_url").ifBlank { null }, guessType(remark), remark)
            }?.distinctBy { it.url }.orEmpty()
            return newHomePageResponse(request.name, items, false)
        }
        val (typeId, sort) = request.data.split('|').let { it[0].toInt() to it.getOrElse(1) { "updatedAt" } }
        val root = getJson("$mainUrl/api/movies?page=$page&pageSize=$PAGE_SIZE&typeId=$typeId&sort=$sort")
        val items = root?.path("items")?.mapNotNull { it.toListResult() }?.distinctBy { it.url }.orEmpty()
        val total = root?.path("total")?.asInt(0) ?: 0
        return newHomePageResponse(request.name, items, items.isNotEmpty() && page * PAGE_SIZE < total)
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
        val root = getJson("$mainUrl/api/movies/search?keyword=$encoded&page=$page&per_page=$PAGE_SIZE&with_total=1")
            ?: return emptyList<SearchResponse>() to false
        val results = root.path("results").mapNotNull { it.toListResult() }.distinctBy { it.url }
        val pages = root.path("pages").asInt(page)
        return results to (results.isNotEmpty() && page < pages)
    }

    // Remembers episodes and adds backup links from our other sources (see shared/CsBackup.kt).
    override suspend fun load(url: String): LoadResponse =
        com.csasian.shared.CsMore.load(this, url) ?: loadPage(url).also { CsBackup.remember(this, it) }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = CsBackup.loadLinks(this, data, subtitleCallback, callback) {
        loadOwnLinks(data, isCasting, subtitleCallback, callback)
    }

    private suspend fun loadPage(url: String): LoadResponse {
        val id = idOf(url) ?: throw ErrorLoadingException("无效的影片地址")
        val pageUrl = detailUrl(id)
        val detail = getDetail(id)
        if (detail.path("is_vip").asBoolean(false)) {
            throw ErrorLoadingException("此影片需要会员，暂不提供观看 / VIP-only title, not available")
        }
        val title = detail.text("title").ifBlank { throw ErrorLoadingException("找不到影片标题") }
        val poster = detail.text("poster_url").ifBlank { null }
        val description = detail.text("description").let { if (it.isBlank()) "" else Jsoup.parse(it).text().trim() }
        val remarks = detail.text("remarks")
        val plotText = listOfNotNull(remarks.ifBlank { null }?.let { "（$it）" }, description.ifBlank { null })
            .joinToString("\n").ifBlank { null }
        val year = detail.text("year").take(4).toIntOrNull()
        val tags = (detail.text("movie_class").split(',', '，', '/') + detail.text("region") + detail.text("category"))
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val splitNames = { text: String, limit: Int ->
            text.split(',', '，', '/').map { it.trim() }.filter { it.isNotBlank() && it != "内详" }.distinct().take(limit)
        }
        val actors = (splitNames(detail.text("actors"), 20).map { ActorData(Actor(it)) } +
            splitNames(detail.text("director"), 3).map { ActorData(Actor(it), roleString = "导演") }).ifEmpty { null }
        val score = detail.path("rating").asDouble(0.0).takeIf { it > 0 }
        val typeId = detail.path("typeId").asInt(0)

        val sources = parseSources(detail)
        if (sources.isEmpty()) throw ErrorLoadingException("此影片暂无播放来源")
        // Prefer a clean m3u8 server's episode list as the canonical one (most complete wins).
        val canonical = sources.sortedBy { rank(it.key) }.maxByOrNull { it.episodes.size }!!.episodes
        val type = typeOf(typeId)
        val isMovie = typeId == 1 || (typeId == 0 && canonical.size == 1)
        val recommended = recommendations(detail, id)

        return if (isMovie) {
            newMovieLoadResponse(title, pageUrl, TvType.Movie, episodeData(pageUrl, canonical[0], 0, movie = true)) {
                posterUrl = poster
                this.plot = plotText
                this.year = year
                this.tags = tags
                this.score = score?.let { Score.from10(it) }
                this.actors = actors
                this.recommendations = recommended
            }
        } else {
            val episodes = canonical.mapIndexed { position, entry ->
                newEpisode(episodeData(pageUrl, entry, position, movie = false)) {
                    name = episodeName(entry.name, position)
                    episode = position + 1
                }
            }
            newTvSeriesLoadResponse(title, pageUrl, if (type == TvType.Movie) TvType.TvSeries else type, episodes) {
                posterUrl = poster
                this.plot = plotText
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
        // data = https://www.suikan365.vip/movie/{id}#ep={normalized name}&idx={0-based position}  (ep empty for movies)
        val id = idOf(data) ?: return false
        val fragment = data.substringAfter('#', "")
        val params = fragment.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val wanted = URLDecoder.decode(params["ep"].orEmpty(), "UTF-8")
        val wantedIdx = params["idx"]?.toIntOrNull() ?: 0

        val sources = parseSources(getDetail(id))
        // One episode per server, matched by episode number/name (falling back to the same position).
        val targets = sources.mapNotNull { source ->
            val index = (if (wanted.isNotBlank()) source.episodes.indexOfFirst { normalizeEpisode(it.name) == wanted } else -1)
                .takeIf { it >= 0 }
                ?: wantedIdx.takeIf { it < source.episodes.size }
                ?: return@mapNotNull null
            Target(source.key, index, source.episodes[index])
        }
        if (targets.isEmpty()) return false

        val links = coroutineScope {
            targets.map { target -> async { resolve(id, target)?.let { target to it } } }.awaitAll().filterNotNull()
        }.sortedBy { (target, _) -> rank(target.key) }
            // Several official platforms often resolve to the same stream: keep the first.
            .distinctBy { (_, streamUrl) -> streamUrl.substringBefore('?') }

        links.forEach { (target, streamUrl) ->
            val label = buildString {
                append(sourceLabel(target.key))
                if (!isDirect(target.entry.url)) append(" (解析)")
                if (target.key == "dyttm3u8") append(" (有广告 ads)")
            }
            callback(
                newExtractorLink(
                    name,
                    "$name $label",
                    streamUrl,
                    if (streamUrl.contains(".mp4", true) && !streamUrl.contains(".m3u8", true)) {
                        ExtractorLinkType.VIDEO
                    } else {
                        ExtractorLinkType.M3U8
                    },
                ) {
                    // Only the site's own servers expect our Referer; resolved official-platform
                    // streams (e.g. ByteDance hosts) refuse a foreign one with 403.
                    referer = if (isDirect(target.entry.url)) "$mainUrl/" else ""
                    // The ad-spliced server is only tried after the clean ones.
                    quality = if (target.key == "dyttm3u8") Qualities.P144.value else qualityOf(target.entry.name)
                    headers = mapOf("User-Agent" to userAgent)
                },
            )
        }
        return links.isNotEmpty()
    }

    private data class Target(val key: String, val index: Int, val entry: EpisodeEntry)

    /** Direct m3u8/mp4 links play as-is; official-platform pages go through the site's resolver. */
    private suspend fun resolve(id: String, target: Target): String? {
        val raw = target.entry.url
        if (isDirect(raw)) return proxied(raw, target.key)
        val viaPlayUrl = runCatching {
            getJson("$mainUrl/api/movies/$id/play-url?source=${enc(target.key)}&episode=${target.index + 1}")
                ?.text("url")?.takeIf { it.startsWith("http") }
        }.getOrNull()
        if (viaPlayUrl != null) return proxied(viaPlayUrl, target.key)
        return runCatching {
            getJson("$mainUrl/api/movies/parse-url?url=${enc(raw)}")?.text("url")?.takeIf { it.startsWith("http") }
        }.getOrNull()?.let { proxied(it, target.key) }
    }

    /** Same rewrites as the site's player for the 量子 (lzm3u8) CDN. */
    private fun proxied(url: String, key: String): String {
        if (key.equals("lzm3u8", true)) {
            if (url.contains("lfthirtytwo")) {
                Regex("""^https?://v\.lfthirtytwo\.com/(.+)$""", RegexOption.IGNORE_CASE).find(url)?.let {
                    return "https://proxy-m3u8.zyq524719556.workers.dev/proxy/${it.groupValues[1]}"
                }
            } else if (url.contains("lz")) {
                return "https://lz.zyq524719556.workers.dev/proxy/$url"
            }
        }
        return url
    }

    private fun isDirect(url: String): Boolean =
        url.contains(".m3u8", true) || url.contains(".mp4", true)

    /** xigua / ruyi are clean, resolved official links are ad-free too; dytt splices ads in. */
    private fun rank(key: String): Int = when (key.lowercase()) {
        "xiguam3u8" -> 0
        "rym3u8" -> 1
        "qiyi", "qq", "mgtv", "youku" -> 2
        "dyttm3u8" -> 9
        else -> 5
    }

    private fun sourceLabel(key: String): String = when (key.lowercase()) {
        "lzm3u8" -> "量子"
        "qq" -> "腾讯"
        "qiyi" -> "爱奇艺"
        "dyttm3u8" -> "天堂"
        "youku" -> "优酷"
        "mgtv" -> "芒果TV"
        "1080zyk" -> "优质"
        "xlm3u8" -> "新浪"
        "xiguam3u8" -> "西瓜"
        "rym3u8" -> "如意"
        else -> key
    }

    private suspend fun recommendations(detail: JsonNode, id: String): List<SearchResponse> = runCatching {
        val movieClass = detail.text("movie_class").ifBlank { detail.text("category") }
        if (movieClass.isBlank()) return@runCatching emptyList()
        getJson("$mainUrl/api/movies/guess-you-like?movie_class=${enc(movieClass)}&excludeId=$id")
            ?.path("data")?.mapNotNull { it.toListResult() }
            ?.filter { idOf(it.url) != id }
            ?.distinctBy { it.url }
            ?.take(MAX_RECOMMENDATIONS)
            .orEmpty()
    }.getOrDefault(emptyList())

    private suspend fun getDetail(id: String): JsonNode {
        val response = app.get("$mainUrl/api/movies/$id", headers = apiHeaders)
        if (response.code == 403) throw ErrorLoadingException("因版权问题暂不提供观看 / Not available (copyright)")
        if (!response.isSuccessful) throw ErrorLoadingException("加载失败 HTTP ${response.code}")
        return jsonMapper.readTree(response.text)
    }

    private suspend fun getJson(url: String): JsonNode? = runCatching {
        val response = app.get(url, headers = apiHeaders)
        if (!response.isSuccessful) null else jsonMapper.readTree(response.text)
    }.getOrNull()

    private data class EpisodeEntry(val name: String, val url: String)
    private data class Source(val key: String, val episodes: List<EpisodeEntry>)

    private fun parseSources(detail: JsonNode): List<Source> {
        val keys = detail.text("vod_play_from").split("$$$").map { it.trim() }
        val lists = detail.text("video_url").ifBlank { detail.text("vod_play_url") }.split("$$$")
        return lists.mapIndexedNotNull { index, list ->
            val episodes = list.split('#').map { it.trim() }.filter { it.isNotBlank() }.mapIndexedNotNull { position, item ->
                val dollar = item.indexOf('$')
                val (name, url) = if (dollar < 0) "" to item else item.substring(0, dollar).trim() to item.substring(dollar + 1).trim()
                if (!url.startsWith("http")) return@mapIndexedNotNull null
                EpisodeEntry(name.ifBlank { "第${position + 1}集" }, url)
            }
            if (episodes.isEmpty()) return@mapIndexedNotNull null
            Source(keys.getOrNull(index)?.ifBlank { null } ?: "线路${index + 1}", episodes)
        }
    }

    private fun episodeData(pageUrl: String, entry: EpisodeEntry, position: Int, movie: Boolean): String {
        // Movie servers name their single entry differently (HD / 正片 / HD中字), so match movies by position.
        val ep = if (movie) "" else URLEncoder.encode(normalizeEpisode(entry.name), "UTF-8")
        return "$pageUrl#ep=$ep&idx=$position"
    }

    private fun normalizeEpisode(name: String): String {
        val trimmed = name.trim()
        val digits = Regex("""^第?\s*0*(\d+)\s*[集话話期]?$""").find(trimmed)?.groupValues?.get(1)
        return digits ?: trimmed
    }

    private fun episodeName(raw: String, position: Int): String {
        val normalized = normalizeEpisode(raw)
        return when {
            normalized.isBlank() -> "第 ${position + 1} 集"
            normalized.all { it.isDigit() } -> "第 $normalized 集"
            else -> raw.trim()
        }
    }

    private fun typeOf(typeId: Int): TvType = categories.firstOrNull { it.typeId == typeId }?.type ?: TvType.TvSeries

    private fun qualityOf(text: String): Int = when {
        text.contains("4K", true) || text.contains("2160") -> Qualities.P2160.value
        text.contains("1080") || text.contains("蓝光") -> Qualities.P1080.value
        text.contains("720") || text.contains("HD", true) || text.contains("高清") -> Qualities.P720.value
        else -> Qualities.Unknown.value
    }

    private fun guessType(remark: String): TvType =
        if (Regex("""正片|HD|高清|抢先|TC|TS|蓝光|中字|4K""", RegexOption.IGNORE_CASE).containsMatchIn(remark)) {
            TvType.Movie
        } else {
            TvType.TvSeries
        }

    private fun idOf(url: String): String? = Regex("""/movie/(\d+)""").find(url)?.groupValues?.get(1)

    private fun detailUrl(id: String) = "$mainUrl/movie/$id"

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")

    private fun JsonNode.text(field: String): String =
        path(field).let { if (it.isNull || it.isMissingNode) "" else it.asText("").trim() }

    /** List/search/recommendation item; VIP-only titles (not playable without an account) are skipped. */
    private fun JsonNode.toListResult(): SearchResponse? {
        if (path("is_vip").asBoolean(false)) return null
        val id = path("id").asText("").ifBlank { return null }
        val typeId = path("typeId").asInt(0)
        if (typeId == 5) return null
        val title = text("title").ifBlank { return null }
        val poster = text("poster_url").ifBlank { text("poster") }.ifBlank { null }
        val type = if (typeId == 0) guessType(text("remarks")) else typeOf(typeId)
        return searchResult(title, detailUrl(id), poster, type, text("remarks"))
    }

    /** [remark] is the card's own label ("HD中字", "TC", "更新至10集"); only real quality labels become a badge. */
    private fun searchResult(title: String, href: String, poster: String?, type: TvType, remark: String? = null): SearchResponse =
        if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, type) { posterUrl = poster; quality = CsQuality.from(remark) }
        } else {
            newTvSeriesSearchResponse(title, href, type) { posterUrl = poster; quality = CsQuality.from(remark) }
        }

    companion object {
        private const val BANNER_ROW = "banners"
        private const val PAGE_SIZE = 24
        private const val MAX_RECOMMENDATIONS = 16
    }
}
