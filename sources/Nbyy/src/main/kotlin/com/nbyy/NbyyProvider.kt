package com.nbyy

import com.csasian.shared.CsBackup
import com.csasian.shared.CsQuality

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * 泥巴影院 (nbyy.cc) is a custom server-rendered site (not MacCMS):
 *  - lists:   /filter.html?channel={movie|tv|show|anime}[&region=cn|hk|tw|jp|kr|west][&page=n]  (48 per page)
 *  - search:  /search.html?keyword={q}[&page=n]
 *  - detail:  /detail/{id}.html — episodes are `#play_list_0 li[slug]` (series: ep1..epN, newest first;
 *             movies: versions such as hd / zhong_zi)
 *  - streams: GET /d0vod/{id}-{slug} (or /d0vod/{id} for every version of a movie) ->
 *             {"aps":[{"pd":"<m3u8>","ss":"<server>"}, ...]}
 * Servers differ a lot in quality: some splice ads into the playlist and some hosts are dead, so every
 * m3u8 is probed in parallel, dead ones are dropped and the rest are sorted clean-first.
 */
class NbyyProvider : MainAPI() {
    override var mainUrl = "https://www.nbyy.cc"
    override var name = "泥巴影院 Nbyy"
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
    private val pageHeaders get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
    private val jsonMapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private data class Row(val label: String, val query: String, val type: TvType)

    private val rows = listOf(
        Row("最新电影", "channel=movie", TvType.Movie),
        Row("最新电视剧", "channel=tv", TvType.TvSeries),
        Row("国产剧", "channel=tv&region=cn", TvType.TvSeries),
        Row("港剧", "channel=tv&region=hk", TvType.TvSeries),
        Row("台剧", "channel=tv&region=tw", TvType.TvSeries),
        Row("韩剧", "channel=tv&region=kr", TvType.AsianDrama),
        Row("日剧", "channel=tv&region=jp", TvType.AsianDrama),
        Row("欧美剧", "channel=tv&region=west", TvType.TvSeries),
        Row("综艺", "channel=show", TvType.TvSeries),
        Row("动漫", "channel=anime", TvType.Anime),
    )

    override val mainPage = rows.map { MainPageData(it.label, it.query) }

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        val type = rows.firstOrNull { it.query == request.data }?.type ?: TvType.TvSeries
        val document = getPage("$mainUrl/filter.html?${request.data}&page=$page")
        val items = cards(document) { type }
        return newHomePageResponse(request.name, items, items.size >= PAGE_SIZE)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val document = getPage("$mainUrl/search.html?keyword=$encoded&page=$page")
        val results = cards(document) { remark -> guessType(remark) }
        return results to (results.size >= PAGE_SIZE)
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
        val detailUrl = url.substringBefore('#')
        val id = idOf(detailUrl) ?: throw ErrorLoadingException("无效的影片地址")
        val document = getPage(detailUrl)
        val title = (document.selectFirst("h1.player-title")?.text()?.trim()
            ?: document.title().substringBefore(" - ").trim()).ifBlank { throw ErrorLoadingException("找不到影片标题") }
        val poster = document.selectFirst("img#cover")?.attr("src")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            ?: "$mainUrl/imgs1/$id.jpg"
        val info = { label: String ->
            document.select(".intro-detail-item").firstOrNull { it.selectFirst(".item-title")?.text()?.contains(label) == true }
                ?.selectFirst(".item-content")?.text()?.trim()?.ifBlank { null }
        }
        val people = { label: String, limit: Int ->
            info(label)?.split(',', '，', '/', '、')?.map { it.trim() }
                ?.filter { it.isNotBlank() && it != "内详" }?.distinct()?.take(limit).orEmpty()
        }
        val tagTexts = document.select(".qy-player-tag span.tag-item").map { it.text().trim() }.filter { it.isNotBlank() }
        val year = tagTexts.firstOrNull { Regex("""^\d{4}$""").matches(it) }?.toIntOrNull()
        val tags = tagTexts.filter { !Regex("""^\d{4}$""").matches(it) }
            .flatMap { it.split('/') }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val actors = (people("主演", 20).map { ActorData(Actor(it)) } +
            people("导演", 3).map { ActorData(Actor(it), roleString = "导演") }).ifEmpty { null }
        val plot = info("简介")

        val entries = parseEpisodes(document)
        if (entries.isEmpty()) throw ErrorLoadingException("此影片暂无播放来源")
        val isSeries = entries.any { it.number != null }
        val type = when {
            !isSeries -> TvType.Movie
            tags.any { it.contains("动画") || it.contains("动漫") } -> TvType.Anime
            else -> TvType.TvSeries
        }
        val recommended = cards(document) { remark -> guessType(remark) }
            .filter { it.url != detailUrl }.take(MAX_RECOMMENDATIONS)

        return if (!isSeries) {
            // /d0vod/{id} returns the servers of every version (HD, 中字, ...) at once.
            newMovieLoadResponse(title, detailUrl, type, detailUrl) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.actors = actors
                this.recommendations = recommended
            }
        } else {
            // The site lists the newest episode first; numbered episodes go in order, extras after.
            val ordered = entries.filter { it.number != null }.sortedBy { it.number } +
                entries.filter { it.number == null }
            val episodes = ordered.mapIndexed { position, entry ->
                newEpisode("$detailUrl#${entry.slug}") {
                    name = entry.name.ifBlank { "第 ${entry.number ?: (position + 1)} 集" }
                    episode = entry.number ?: (position + 1)
                }
            }
            newTvSeriesLoadResponse(title, detailUrl, type, episodes) {
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
        // data = https://www.nbyy.cc/detail/{id}.html[#{slug}]  (no slug for movies)
        val detailUrl = data.substringBefore('#')
        val id = idOf(detailUrl) ?: return false
        val slug = data.substringAfter('#', "").trim()
        var servers = servers(id, slug)
        if (servers.isEmpty() && slug.isBlank()) {
            // Fallback for movies: ask for the first listed version explicitly.
            parseEpisodes(getPage(detailUrl)).firstOrNull()?.let { servers = servers(id, it.slug) }
        }
        val candidates = servers.filter { !it.pd.isNullOrBlank() && it.pd.startsWith("http") }
            .distinctBy { it.pd }
            .sortedBy { rank(it.server) } // stable: keeps the site's order within a rank
        if (candidates.isEmpty()) return false

        // Probe every m3u8 at once and drop the dead/blocked ones.
        val alive = coroutineScope {
            candidates.map { server -> async { probe(server.pd!!)?.let { server to it } } }.awaitAll().filterNotNull()
        }

        val seen = mutableMapOf<String, Int>()
        alive.forEach { (server, height) ->
            val count = (seen[server.server] ?: 0) + 1
            seen[server.server] = count
            val label = buildString {
                append("泥巴 ").append(server.server)
                if (count > 1) append(' ').append(count)
                if (server.server in ADS_SERVERS) append(" (有广告 ads)")
            }
            callback(
                newExtractorLink(name, label, server.pd!!, ExtractorLinkType.M3U8) {
                    referer = "$mainUrl/"
                    // Servers with spliced-in ads are only tried after the clean ones.
                    quality = if (server.server in ADS_SERVERS) Qualities.P144.value else height
                },
            )
        }
        return alive.isNotEmpty()
    }

    private suspend fun servers(id: String, slug: String): List<Ap> = runCatching {
        val url = if (slug.isBlank()) "$mainUrl/d0vod/$id" else "$mainUrl/d0vod/$id-$slug"
        jsonMapper.readValue(app.get(url, headers = pageHeaders).text, D0vodResponse::class.java).aps.orEmpty()
    }.getOrDefault(emptyList())

    /**
     * Fetches the playlist with a short timeout. Returns null when the host is dead or the reply isn't a
     * playlist (some hosts answer 200 with an empty body or a "region denied" page); otherwise the best
     * RESOLUTION height in a master playlist, or Qualities.Unknown.
     */
    private suspend fun probe(url: String): Int? = withTimeoutOrNull(PROBE_TIMEOUT_MS + 500) {
        runCatching {
            val response = app.get(url, headers = mapOf("User-Agent" to userAgent), timeout = PROBE_TIMEOUT_MS / 1000)
            val text = response.text
            if (!response.isSuccessful || !text.contains("#EXTM3U")) return@runCatching null
            Regex("""RESOLUTION=\d+x(\d+)""").findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
                ?: Qualities.Unknown.value
        }.getOrNull()
    }

    private fun rank(server: String): Int = when (server) {
        in CLEAN_SERVERS -> CLEAN_SERVERS.indexOf(server)
        in ADS_SERVERS -> 100
        else -> 50
    }

    private suspend fun getPage(url: String): Document = app.get(url, headers = pageHeaders).document

    private fun idOf(url: String): String? = Regex("""/detail/(\d+)""").find(url)?.groupValues?.get(1)

    private data class EpisodeEntry(val slug: String, val name: String) {
        val number: Int? get() = Regex("""^ep(\d+)$""").find(slug)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun parseEpisodes(document: Document): List<EpisodeEntry> =
        document.select("#play_list_0 li[slug]").mapNotNull { li ->
            val slug = li.attr("slug").trim().ifBlank { return@mapNotNull null }
            EpisodeEntry(slug, li.text().trim())
        }.distinctBy { it.slug }

    private fun guessType(remark: String): TvType =
        if (Regex("""集|期|更新""").containsMatchIn(remark)) TvType.TvSeries else TvType.Movie

    /** Every poster card (li.qy-mod-li) on a list/search/detail page. */
    private fun cards(document: Document, typeOf: (String) -> TvType): List<SearchResponse> =
        document.select("li.qy-mod-li").mapNotNull { it.toCard(typeOf) }.distinctBy { it.url }

    private fun Element.toCard(typeOf: (String) -> TvType): SearchResponse? {
        val link = selectFirst("a[href*=/detail/]") ?: return null
        val href = fixUrl(link.attr("href"))
        val id = idOf(href) ?: return null
        val title = (selectFirst(".title-wrap a[title]")?.attr("title")?.trim()?.ifBlank { null }
            ?: selectFirst("img[alt]")?.attr("alt")?.trim()
            ?: selectFirst(".title-wrap .main")?.text()?.trim()).orEmpty().ifBlank { return null }
        val remark = selectFirst(".icon-br .qy-mod-label")?.text()?.trim().orEmpty()
        val score = (selectFirst(".label-score")?.text() ?: selectFirst(".icon-tr .qy-mod-label")?.text())
            ?.trim()?.toDoubleOrNull()?.takeIf { it > 0 && it <= 10 }
        val poster = "$mainUrl/imgs1/$id.jpg"
        val type = typeOf(remark)
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, type) {
                posterUrl = poster
                this.score = score?.let { Score.from10(it) }
                quality = CsQuality.from(remark)
            }
        } else {
            newTvSeriesSearchResponse(title, href, type) {
                posterUrl = poster
                this.score = score?.let { Score.from10(it) }
                quality = CsQuality.from(remark)
            }
        }
    }

    data class D0vodResponse(@JsonProperty("aps") val aps: List<Ap>? = null)

    data class Ap(
        @JsonProperty("pd") val pd: String? = null,
        @JsonProperty("ss") val ss: String? = null,
    ) {
        @get:JsonIgnore
        val server: String get() = ss?.trim()?.ifBlank { null } ?: "线路"
    }

    companion object {
        private const val PAGE_SIZE = 48
        private const val MAX_RECOMMENDATIONS = 18
        private const val PROBE_TIMEOUT_MS = 5000L

        /** Ad-free servers, best first. */
        private val CLEAN_SERVERS = listOf("wjzy2", "hnzy", "ukzy")

        /** Servers that splice ad clips into the playlist; listed last and labelled. */
        private val ADS_SERVERS = setOf("ffzy", "lzzy", "mdzy")
    }
}
