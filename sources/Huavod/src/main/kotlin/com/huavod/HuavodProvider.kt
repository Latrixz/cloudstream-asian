package com.huavod

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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 华视影院 (huavod.com) is a MacCMS site:
 *  - lists:   /vodshow/{typeId}/by/{hits|id|time}/page/{n}.html
 *  - search:  /vodsearch/page/{n}/wd/{query}.html
 *  - detail:  /voddetail/{vodId}.html (sources in .anthology-tab, episodes in .anthology-list-box)
 *  - player:  /Player/ec?episode={vodId}-{sid}-{nid} embeds `ConFig` with a one-time `mt` token.
 *             After the pre-roll ad window (ad_duration_ms), POST /Player/resolveUrl token={mt}
 *             returns the real m3u8. Asking too early invalidates the token, so we wait like
 *             the site's own player (EcPlayer6.js) does.
 */
class HuavodProvider : MainAPI() {
    override var mainUrl = "https://huavod.com"
    override var name = "华视影院 Huavod"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Documentary,
    )

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val pageHeaders get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
    private val jsonMapper = ObjectMapper()

    private data class Category(val id: Int, val label: String, val type: TvType)

    private val categories = listOf(
        Category(1, "电影", TvType.Movie),
        Category(2, "电视剧", TvType.TvSeries),
        Category(3, "综艺", TvType.TvSeries),
        Category(4, "动漫", TvType.Anime),
        Category(5, "短剧", TvType.TvSeries),
        Category(42, "纪录片", TvType.Documentary),
    )

    // The site's list tabs: 按最热 = hits, 按最新 = time (update time). `id` (newest vod_id first)
    // is MacCMS's "newly added" order.
    override val mainPage = categories.flatMap { category ->
        listOf(
            "hits" to "热播",
            "id" to "最新添加",
            "time" to "最近更新",
        ).map { (order, orderLabel) ->
            MainPageData("${category.label} · $orderLabel", "${category.id}|$order")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (typeId, order) = request.data.split('|').let { it[0].toInt() to it.getOrElse(1) { "time" } }
        val type = categories.firstOrNull { it.id == typeId }?.type ?: TvType.TvSeries
        val document = getPage("$mainUrl/vodshow/$typeId/by/$order/page/$page.html")
        val items = document.select(".public-list-box").mapNotNull { it.toListResult(type) }.distinctBy { it.url }
        val html = document.html()
        val total = Regex("""hl-total'\)\.html\('(\d+)'""").find(html)?.groupValues?.get(1)?.toIntOrNull()
        val lastPage = Regex("""\d+\s*(?:&nbsp;| )?/(?:&nbsp;| )?\s*(\d+)页""").find(html)
            ?.groupValues?.get(1)?.toIntOrNull() ?: page
        // The "N / M页" counter overstates the page count, so prefer the item total.
        val hasNext = items.isNotEmpty() &&
            if (total != null) (page - 1) * LIST_PAGE_SIZE + items.size < total else page < lastPage
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
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val document = getPage("$mainUrl/vodsearch/page/$page/wd/$encoded.html")
        val results = document.select(".search-box").mapNotNull { box ->
            val link = box.selectFirst(".thumb-txt a[href*=/voddetail/]")
                ?: box.selectFirst("a[href*=/voddetail/]") ?: return@mapNotNull null
            val title = link.text().trim().ifBlank { return@mapNotNull null }
            val remark = box.selectFirst(".public-list-prb")?.text()?.trim().orEmpty()
            searchResult(title, fixUrl(link.attr("href")), imageOf(box.selectFirst("img")), guessType(remark), CsQuality.from(remark))
        }.distinctBy { it.url }
        val pages = Regex("""当前(\d+)/(\d+)页""").find(document.select(".page-tip").text())
        val lastPage = pages?.groupValues?.get(2)?.toIntOrNull() ?: page
        return results to (results.isNotEmpty() && page < lastPage)
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

    private suspend fun loadPage(url: String): LoadResponse {
        val detailUrl = url.substringBefore('#')
        val document = getPage(detailUrl)
        val title = document.selectFirst("h1.slide-info-title")?.text()?.trim().orEmpty()
            .ifBlank { throw ErrorLoadingException("找不到影片标题") }
        val poster = imageOf(document.selectFirst(".detail-pic img"))
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        val plot = document.selectFirst("#height_limit")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
        val remarks = document.select(".slide-info-remarks a")
        val year = remarks.firstOrNull { it.attr("href").contains("/year/") }?.text()?.trim()?.take(4)?.toIntOrNull()
        val tags = (
            // .vod-tag is "#<title>免费在线观看 #genre… #area #language #主演:<actor> #<actor>…"; keep the part
            // before the cast/director entries.
            document.select(".vod-tag a").map { it.text().trim().removePrefix("#") }
                .takeWhile { !it.contains(':') && !it.contains('：') }
                .filterNot { it.contains("在线观看") } +
                remarks.filter { it.attr("href").contains("/class/") || it.attr("href").contains("/area/") }
                    .map { it.text() }
            ).map { it.trim() }.filter { it.length >= 2 }.distinct()
        val infoLine = { label: String ->
            document.select(".slide-info").firstOrNull { it.selectFirst("strong")?.text()?.contains(label) == true }
        }
        val names = { label: String, limit: Int ->
            infoLine(label)?.select("a")?.map { it.text().trim() }?.filter { it.isNotBlank() }?.distinct()?.take(limit)
                .orEmpty()
        }
        val actors = (names("演员", 20).map { ActorData(Actor(it)) } +
            names("导演", 3).map { ActorData(Actor(it), roleString = "导演") }).ifEmpty { null }
        val status = infoLine("备注")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
        val score = document.selectFirst(".detail-score .fraction")?.text()?.trim()?.toDoubleOrNull()
            ?.takeIf { it > 0 }
        val plotText = listOfNotNull(status?.let { "（$it）" }, plot).joinToString("\n").ifBlank { null }
        val type = typeOf(document)
        val recommended = recommendations(document, detailUrl, type)

        val sources = parseSources(document)
        if (sources.isEmpty()) throw ErrorLoadingException("此影片暂无播放来源")
        // Sources usually share one episode list; use the most complete one as the canonical list.
        val canonical = sources.maxByOrNull { it.episodes.size }!!.episodes

        return if (type == TvType.Movie && canonical.size == 1) {
            newMovieLoadResponse(title, detailUrl, type, episodeData(detailUrl, canonical[0])) {
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
                newEpisode(episodeData(detailUrl, entry)) {
                    name = episodeName(entry.name, position)
                    episode = position + 1
                }
            }
            newTvSeriesLoadResponse(title, detailUrl, type, episodes) {
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
        // data = https://huavod.com/voddetail/{id}.html#ep={normalized name}&nid={n}
        val detailUrl = data.substringBefore('#')
        val fragment = data.substringAfter('#', "")
        val params = fragment.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val wanted = URLDecoder.decode(params["ep"].orEmpty(), "UTF-8")
        val wantedNid = params["nid"]?.toIntOrNull()

        val sources = parseSources(getPage(detailUrl))
        // One episode per source, matched by episode number/name (falling back to the same position).
        val targets = sources.mapNotNull { source ->
            val entry = if (wanted.isNotBlank()) {
                source.episodes.firstOrNull { normalizeEpisode(it.name) == wanted }
            } else {
                source.episodes.firstOrNull { it.nid == wantedNid } ?: source.episodes.firstOrNull()
            } ?: return@mapNotNull null
            source.label to entry
        }
        if (targets.isEmpty()) return false

        val links = coroutineScope {
            // 1) Get a player token for every source at once, 2) wait out the ad window once,
            // 3) redeem all tokens.
            val tickets = targets.map { (label, entry) -> async { label to fetchTicket(entry) } }.awaitAll()
            val waitMs = tickets.mapNotNull { it.second?.takeIf { t -> t.inlineUrl == null }?.adDurationMs }
                .maxOrNull()?.let { it - AD_WINDOW_MARGIN_MS } ?: 0L
            if (waitMs > 0) delay(waitMs)
            tickets.map { (label, ticket) ->
                async {
                    val streamUrl = ticket?.let { it.inlineUrl ?: redeem(it) } ?: return@async null
                    val playable = runCatching {
                        app.get(streamUrl, headers = mapOf("User-Agent" to userAgent)).text
                            .trimStart().startsWith("#EXTM3U")
                    }.getOrDefault(false)
                    Triple(label, streamUrl, playable)
                }
            }.awaitAll().filterNotNull()
        }

        // CloudStream tries links in order: verified playlists first, in the site's source order.
        val ordered = links.filter { it.third } + links.filterNot { it.third }
        ordered.forEach { (label, streamUrl, _) ->
            callback(
                newExtractorLink(
                    name,
                    "$name $label",
                    streamUrl,
                    if (streamUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    quality = Qualities.Unknown.value
                    headers = mapOf("User-Agent" to userAgent)
                },
            )
        }
        return ordered.isNotEmpty()
    }

    /**
     * The detail page's "相关影片" grid (same page, no extra request). MacCMS fills it from the same
     * category, so items get the current title's type. The current title itself is skipped.
     */
    private fun recommendations(document: Document, detailUrl: String, type: TvType): List<SearchResponse> =
        runCatching {
            val heading = document.select("h2.this-name").firstOrNull { it.text().trim() == "相关影片" }
                ?: return@runCatching emptyList()
            val container = heading.parent()?.parent() ?: return@runCatching emptyList()
            container.select(".public-list-box").mapNotNull { it.toListResult(type) }
                .filter { it.url != detailUrl }
                .distinctBy { it.url }
                .take(MAX_RECOMMENDATIONS)
        }.getOrDefault(emptyList())

    /** Cloudflare answers bursts (e.g. all home rows at once) with HTTP 429; back off and retry. */
    private suspend fun getPage(url: String): Document {
        repeat(3) { attempt ->
            val response = app.get(url, headers = pageHeaders)
            if (response.code != 429) return response.document
            delay(1500L * (attempt + 1))
        }
        return app.get(url, headers = pageHeaders).document
    }

    private data class EpisodeEntry(val name: String, val vodId: String, val sid: Int, val nid: Int)
    private data class Source(val label: String, val episodes: List<EpisodeEntry>)
    private data class Ticket(val episode: EpisodeEntry, val token: String?, val inlineUrl: String?, val adDurationMs: Long)

    private fun parseSources(document: Document): List<Source> {
        val labels = document.select(".anthology-tab a").map { it.text().replace(' ', ' ').trim() }
        return document.select(".anthology-list .anthology-list-box").mapIndexedNotNull { index, box ->
            val episodes = box.select("a[href*=/vodplay/]").mapNotNull { link ->
                val match = Regex("""/vodplay/(\d+)-(\d+)-(\d+)\.html""").find(link.attr("href"))
                    ?: return@mapNotNull null
                val (vodId, sid, nid) = match.destructured
                EpisodeEntry(link.text().trim(), vodId, sid.toInt(), nid.toInt())
            }
            if (episodes.isEmpty()) return@mapIndexedNotNull null
            Source(labels.getOrNull(index)?.ifBlank { null } ?: "线路 ${index + 1}", episodes)
        }
    }

    /** Loads the player iframe and reads its embedded `ConFig` object. Returns null when no free stream. */
    private suspend fun fetchTicket(entry: EpisodeEntry): Ticket? = runCatching {
        val episodeKey = "${entry.vodId}-${entry.sid}-${entry.nid}"
        val html = app.get(
            "$mainUrl/Player/ec?episode=$episodeKey",
            headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/vodplay/$episodeKey.html"),
        ).text
        val start = html.indexOf("ConFig=")
        if (start < 0) return null
        val config = extractJsonObject(html, start + "ConFig=".length)?.let { jsonMapper.readTree(it) } ?: return null
        if (config.path("code").asInt(0) != 200) return null
        val inline = config.path("url").asText("").takeIf { it.startsWith("http") }
        val token = config.path("mt").asText("").takeIf { it.isNotBlank() }
        if (inline == null && token == null) return null
        Ticket(entry, token, inline, config.path("ad_duration_ms").asLong(0))
    }.getOrNull()

    private suspend fun redeem(ticket: Ticket): String? = runCatching {
        val token = ticket.token ?: return null
        val episodeKey = "${ticket.episode.vodId}-${ticket.episode.sid}-${ticket.episode.nid}"
        val body = app.post(
            "$mainUrl/Player/resolveUrl",
            headers = mapOf(
                "User-Agent" to userAgent,
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/Player/ec?episode=$episodeKey",
            ),
            data = mapOf("token" to token),
        ).text
        val json: JsonNode = jsonMapper.readTree(body)
        if (json.path("code").asInt(0) != 1) return null
        json.path("data").path("url").asText("").takeIf { it.startsWith("http") }
    }.getOrNull()

    /** Returns the JSON object that starts at [from], respecting strings and escapes. */
    private fun extractJsonObject(text: String, from: Int): String? {
        val begin = text.indexOf('{', from).takeIf { it >= 0 && it - from < 5 } ?: return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in begin until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return text.substring(begin, i + 1)
                }
            }
        }
        return null
    }

    private fun episodeData(detailUrl: String, entry: EpisodeEntry): String =
        "$detailUrl#ep=${URLEncoder.encode(normalizeEpisode(entry.name), "UTF-8")}&nid=${entry.nid}"

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

    /** Uses the breadcrumb's top-level /vodtype/{id}.html link, falling back to JSON-LD's @type. */
    private fun typeOf(document: Document): TvType {
        val html = document.html()
        val typeId = Regex(""""position":2,"name":"[^"]*","item":"[^"]*/vodtype/(\d+)\.html"""")
            .find(html)?.groupValues?.get(1)?.toIntOrNull()
        categories.firstOrNull { it.id == typeId }?.let { return it.type }
        return if (html.contains("\"@type\":\"Movie\"")) TvType.Movie else TvType.TvSeries
    }

    private fun guessType(remark: String): TvType =
        if (Regex("""正片|HD|高清|抢先|TC|TS|蓝光|中字|4K""", RegexOption.IGNORE_CASE).containsMatchIn(remark)) {
            TvType.Movie
        } else {
            TvType.TvSeries
        }

    private fun imageOf(img: Element?): String? {
        if (img == null) return null
        return listOf(img.attr("data-original"), img.attr("data-src"), img.attr("src"))
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { fixUrl(it) }
    }

    private fun Element.toListResult(type: TvType): SearchResponse? {
        val link = selectFirst("a.public-list-exp[href*=/voddetail/]") ?: return null
        val title = link.attr("title").trim()
            .ifBlank { selectFirst(".time-title")?.text()?.trim().orEmpty() }
            .ifBlank { return null }
        // Card remark is usually episode status ("已完结", "第7集"); only real labels ("高清", "抢先版", "1080P集全") map.
        val quality = CsQuality.from(link.selectFirst(".public-list-prb")?.text())
        return searchResult(title, fixUrl(link.attr("href")), imageOf(link.selectFirst("img")), type, quality)
    }

    private fun searchResult(
        title: String,
        href: String,
        poster: String?,
        type: TvType,
        quality: SearchQuality? = null,
    ): SearchResponse =
        if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, type) { posterUrl = poster; this.quality = quality }
        } else {
            newTvSeriesSearchResponse(title, href, type) { posterUrl = poster; this.quality = quality }
        }

    companion object {
        private const val LIST_PAGE_SIZE = 40
        private const val MAX_RECOMMENDATIONS = 16

        // EcPlayer6.js redeems the token at ad_duration_ms - 2000; stay a little later than that.
        private const val AD_WINDOW_MARGIN_MS = 1500L
    }
}
