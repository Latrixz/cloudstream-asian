package com.trjvod

import com.csasian.shared.CsBackup

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 唐人街影院 (trjvod.com) is a MacCMS 10 site ("conch" template):
 *  - lists:   /index.php/vod/show/by/{time|hits}/id/{tid}/page/{n}.html  (60 per page)
 *  - search:  /index.php/vod/search/page/{n}/wd/{q}.html; quick search: /index.php/ajax/suggest?mid=1&wd=
 *  - detail:  /index.php/vod/detail/id/{id}.html — source tabs `#NumTab a` pair up by index with
 *             `.play_list_box` (the tab order is not the sid order; sids come from the links)
 *  - player:  /index.php/vod/play/id/{id}/sid/{s}/nid/{n}.html embeds `player_aaaa={...}` with the
 *             server code in "from" and the m3u8 in "url" ("encrypt": 0 plain, 1 escape(), 2 base64(escape())).
 * Servers: rym3u8 (ryplay, clean 1080p) first; 1080zyk (yzzy) splices ad clips in, so it goes last and is
 * labelled; tianya / leshi / fsvod hosts are often dead, so every m3u8 is probed and dead ones are dropped.
 */
class TrjvodProvider : MainAPI() {
    override var mainUrl = "https://www.trjvod.com"
    override var name = "唐人街 Trjvod"
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
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val pageHeaders get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
    private val jsonMapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /** [type] null = decide per card (sports has single matches and whole seasons). */
    private data class Category(val tid: Int, val label: String, val type: TvType?)

    private val categories = listOf(
        Category(1, "电影", TvType.Movie),
        Category(2, "连续剧", TvType.TvSeries),
        Category(3, "综艺", TvType.TvSeries),
        Category(4, "动漫", TvType.Anime),
        Category(53, "短剧", TvType.AsianDrama),
        Category(59, "体育", null),
    )

    override val mainPage = listOf(MainPageData("热播推荐", HOME_ROW)) +
        categories.flatMap { category ->
            listOf(
                MainPageData("最新${category.label}", "${category.tid}|time"),
                MainPageData("热门${category.label}", "${category.tid}|hits"),
            )
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == HOME_ROW) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val document = getPage("$mainUrl/")
            val heading = document.select(".pannel_head h2.title").firstOrNull { it.text().contains("热播推荐") }
            val items = heading?.closest(".pannel")?.select(".cbox1 li.vodlist_item")
                ?.mapNotNull { it.toCard(null) }?.distinctBy { it.url }.orEmpty()
            return newHomePageResponse(request.name, items, false)
        }
        val (tid, order) = request.data.split('|').let { it[0] to it.getOrElse(1) { "time" } }
        val type = categories.firstOrNull { it.tid.toString() == tid }?.type
        val document = getPage("$mainUrl/index.php/vod/show/by/$order/id/$tid/page/$page.html")
        val items = document.select("ul.vodlist li.vodlist_item").mapNotNull { it.toCard(type) }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, items.isNotEmpty() && page < lastPage(document, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val suggestions = runCatching {
            jsonMapper.readValue(
                app.get("$mainUrl/index.php/ajax/suggest?mid=1&wd=$encoded&limit=20", headers = pageHeaders).text,
                SuggestResponse::class.java,
            ).list.orEmpty()
        }.getOrNull()
        // The suggest endpoint has no type/remark, so fall back to the full search when it fails.
        if (suggestions == null) return search(query)
        return suggestions.mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val title = item.name?.trim()?.ifBlank { null } ?: return@mapNotNull null
            newTvSeriesSearchResponse(title, "$mainUrl/index.php/vod/detail/id/$id.html", TvType.TvSeries) {
                posterUrl = item.pic?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val document = getPage("$mainUrl/index.php/vod/search/page/$page/wd/$encoded.html")
        val results = document.select("li.searchlist_item").mapNotNull { item ->
            val label = item.selectFirst(".vodlist_title .info_right")?.text()?.trim().orEmpty()
            item.toCard(typeOfLabel(label))
        }.distinctBy { it.url }
        return results to (results.isNotEmpty() && page < lastPage(document, page))
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
        val title = (document.selectFirst(".content_detail h2.title")?.text()?.trim()
            ?: document.selectFirst(".content_thumb a[title]")?.attr("title")?.trim()).orEmpty()
            .ifBlank { throw ErrorLoadingException("找不到影片标题") }
        val poster = document.selectFirst(".content_thumb a.vodlist_thumb")?.attr("data-original")
            ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        val plot = document.selectFirst(".content_desc.full_text span")?.text()?.trim()?.ifBlank { null }
            ?: document.selectFirst(".content_desc.context span")?.text()?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[name=description]")?.attr("content")
        val dataLinks = document.select(".content_min li.data a")
        val year = dataLinks.firstOrNull { it.attr("href").contains("/year/") }?.text()?.trim()?.take(4)?.toIntOrNull()
        val tags = dataLinks.filter { it.attr("href").contains("/class/") || it.attr("href").contains("/area/") }
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        // 主演 / 导演 links point at /vod/search/actor/… and /vod/search/director/….
        val names = { kind: String, limit: Int ->
            document.select(".content_min li.data a[href*=/$kind/]").map { it.text().trim() }
                .filter { it.isNotBlank() && it != "内详" && it != "未知" }.distinct().take(limit)
        }
        val actors = (names("actor", 20).map { ActorData(Actor(it)) } +
            names("director", 3).map { ActorData(Actor(it), roleString = "导演") }).ifEmpty { null }
        val status = document.selectFirst(".content_min .data_style")?.text()?.trim()?.ifBlank { null }
        val score = document.selectFirst("#detail_rating .star_tips")?.text()?.trim()?.toDoubleOrNull()
            ?.takeIf { it > 0 && it <= 10 }
        val plotText = listOfNotNull(status?.let { "（$it）" }, plot).joinToString("\n").ifBlank { null }

        val sources = parseSources(document)
        if (sources.isEmpty()) throw ErrorLoadingException("此影片暂无播放来源")
        // Sources usually share one episode list; use the most complete one as the canonical list.
        val canonical = sources.maxByOrNull { it.episodes.size }!!.episodes
        val tid = document.selectFirst("li.active a[href*=/vod/type/id/]")?.attr("href")
            ?.let { Regex("""/type/id/(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val navType = categories.firstOrNull { it.tid == tid }?.type
        val isMovie = canonical.size == 1 && (navType == TvType.Movie || navType == null)
        val type = when {
            isMovie -> TvType.Movie
            navType == null || navType == TvType.Movie -> TvType.TvSeries
            else -> navType
        }
        val recommended = recommendations(document, detailUrl, navType)

        return if (isMovie) {
            newMovieLoadResponse(title, detailUrl, type, episodeData(detailUrl, canonical[0], movie = true)) {
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
                newEpisode(episodeData(detailUrl, entry, movie = false)) {
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
        // data = https://www.trjvod.com/index.php/vod/detail/id/{id}.html#ep={normalized name}&nid={n}
        // (ep is empty for movies)
        val detailUrl = data.substringBefore('#')
        val fragment = data.substringAfter('#', "")
        val params = fragment.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val wanted = URLDecoder.decode(params["ep"].orEmpty(), "UTF-8")
        val wantedNid = params["nid"]?.toIntOrNull()

        val sources = parseSources(getPage(detailUrl))
        // One episode per source, matched by episode number/name (falling back to the same position).
        val targets = sources.mapNotNull { source ->
            val entry = (if (wanted.isNotBlank()) source.episodes.firstOrNull { normalizeEpisode(it.name) == wanted } else null)
                ?: source.episodes.firstOrNull { it.nid == wantedNid }
                ?: if (wanted.isBlank()) source.episodes.firstOrNull() else null
            entry?.let { source.label to it }
        }
        if (targets.isEmpty()) return false

        // Resolve every server's stream and probe it at once; dead hosts are dropped.
        val streams = coroutineScope {
            targets.map { (label, entry) ->
                async {
                    val player = playerOf(entry)
                    if (player == null) return@async null
                    val height = probe(player.url)
                    if (height == null) return@async null
                    Stream(label, player.from, player.url, height)
                }
            }.awaitAll().filterNotNull()
        }.sortedBy { rank(it) } // stable: keeps the site's tab order within a rank

        streams.forEach { stream ->
            val ads = rank(stream) == RANK_ADS
            callback(
                newExtractorLink(
                    name,
                    "$name ${stream.label}" + if (ads) " (有广告 ads)" else "",
                    stream.url,
                    if (stream.url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    referer = "$mainUrl/"
                    quality = when (rank(stream)) {
                        RANK_CLEAN -> Qualities.P1080.value
                        // Servers with spliced-in ads are only tried after the clean ones.
                        RANK_ADS -> Qualities.P144.value
                        // Other servers stay below the ryplay one.
                        else -> stream.height.coerceAtMost(Qualities.P720.value)
                    }
                    headers = mapOf("User-Agent" to userAgent)
                },
            )
        }
        return streams.isNotEmpty()
    }

    private data class Stream(val label: String, val from: String, val url: String, val height: Int)

    private data class Player(val from: String, val url: String)

    /** Reads `player_aaaa` from the play page and returns the server code and decoded stream URL. */
    private suspend fun playerOf(entry: EpisodeEntry): Player? = runCatching {
        val html = app.get(entry.playUrl, headers = pageHeaders).text
        val start = html.indexOf("player_aaaa")
        if (start < 0) return null
        val eq = html.indexOf('=', start)
        val json = extractJsonObject(html, eq + 1) ?: return null
        val config = jsonMapper.readValue(json, PlayerConfig::class.java)
        val raw = config.url?.trim().orEmpty()
        if (raw.isBlank()) return null
        val url = when (config.encrypt ?: 0) {
            1 -> jsUnescape(raw)
            2 -> jsUnescape(String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8))
            else -> raw
        }.trim()
        if (!url.startsWith("http")) return null
        Player(config.from.orEmpty(), url)
    }.getOrNull()

    /**
     * Fetches the playlist with a short timeout. Returns null when the host is dead or the reply isn't a
     * playlist (some hosts answer with a redirect script); otherwise the best RESOLUTION height in a
     * master playlist, or Qualities.Unknown.
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

    private fun rank(stream: Stream): Int {
        val host = runCatching { URI(stream.url).host.orEmpty() }.getOrDefault("").lowercase()
        return when {
            stream.from == "rym3u8" || host.contains("ryplay") || host.contains("ryiplay") -> RANK_CLEAN
            stream.from == "1080zyk" || host.contains("yzzy") -> RANK_ADS
            else -> RANK_OTHER
        }
    }

    /** JavaScript's unescape(): %XX and %uXXXX sequences. */
    private fun jsUnescape(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                if (i + 5 < text.length && text[i + 1] == 'u') {
                    text.substring(i + 2, i + 6).toIntOrNull(16)?.let {
                        out.append(it.toChar()); i += 6; continue
                    }
                }
                if (i + 2 < text.length) {
                    text.substring(i + 1, i + 3).toIntOrNull(16)?.let {
                        out.append(it.toChar()); i += 3; continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** The detail page's "相关推荐" row (same page, no extra request); the current title is skipped. */
    private fun recommendations(document: Document, detailUrl: String, type: TvType?): List<SearchResponse> =
        runCatching {
            val heading = document.select(".pannel_head h3.title").firstOrNull { it.text().trim() == "相关推荐" }
                ?: return@runCatching emptyList()
            val container = heading.closest(".pannel") ?: return@runCatching emptyList()
            container.select("li.vodlist_item").mapNotNull { it.toCard(type) }
                .filter { it.url != detailUrl }
                .distinctBy { it.url }
                .take(MAX_RECOMMENDATIONS)
        }.getOrDefault(emptyList())

    /** Backs off and retries when the site answers a burst (e.g. all home rows at once) with HTTP 429. */
    private suspend fun getPage(url: String): Document {
        repeat(3) { attempt ->
            val response = app.get(url, headers = pageHeaders)
            if (response.code != 429) return response.document
            delay(1500L * (attempt + 1))
        }
        return app.get(url, headers = pageHeaders).document
    }

    /** Highest page number in the pager links (…/page/{n}.html or …/page/{n}/wd/…). */
    private fun lastPage(document: Document, page: Int): Int =
        document.select("a[href*=/page/]").mapNotNull {
            Regex("""/page/(\d+)""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: page

    private data class EpisodeEntry(val name: String, val playUrl: String, val sid: Int, val nid: Int)
    private data class Source(val label: String, val episodes: List<EpisodeEntry>)

    private fun parseSources(document: Document): List<Source> {
        val labels = document.select("#NumTab a").map { tab ->
            tab.attr("alt").trim().ifBlank { tab.ownText().replace(' ', ' ').trim() }
        }
        return document.select(".play_source .play_list_box").mapIndexedNotNull { index, box ->
            val episodes = box.select("a[href*=/vod/play/]").mapNotNull { link ->
                val href = link.attr("href").trim()
                val match = Regex("""/sid/(\d+)/nid/(\d+)""").find(href) ?: return@mapNotNull null
                EpisodeEntry(link.text().trim(), fixUrl(href), match.groupValues[1].toInt(), match.groupValues[2].toInt())
            }.distinctBy { it.playUrl }
            if (episodes.isEmpty()) return@mapIndexedNotNull null
            Source(labels.getOrNull(index)?.ifBlank { null } ?: "线路 ${index + 1}", episodes)
        }
    }

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

    private fun episodeData(detailUrl: String, entry: EpisodeEntry, movie: Boolean): String {
        // Movie sources name their single entry differently (HD / HD中字 / 正片), so match movies by position.
        val ep = if (movie) "" else URLEncoder.encode(normalizeEpisode(entry.name), "UTF-8")
        return "$detailUrl#ep=$ep&nid=${entry.nid}"
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
            normalized.all { it.isDigit() } && normalized.length <= 4 -> "第 $normalized 集"
            else -> raw.trim()
        }
    }

    /** Search results carry the category name ("电影", "国产剧", "动漫", "短剧", ...). */
    private fun typeOfLabel(label: String): TvType? = when {
        label.contains("动漫") || label.contains("动画") -> TvType.Anime
        label.contains("短剧") -> TvType.AsianDrama
        label.contains("剧") || label.contains("综艺") -> TvType.TvSeries
        label.contains("电影") || label.endsWith("片") -> TvType.Movie
        else -> null
    }

    private fun guessType(remark: String): TvType =
        if (Regex("""集|期|更新|完结""").containsMatchIn(remark)) TvType.TvSeries else TvType.Movie

    /**
     * A poster card (`li.vodlist_item` / `li.searchlist_item`). [type] null = guess from the card's remark.
     * The cards' corner number is a score, not a quality label, so no quality badge is set.
     */
    private fun Element.toCard(type: TvType?): SearchResponse? {
        val link = selectFirst("a.vodlist_thumb[href*=/vod/detail/]") ?: return null
        val title = link.attr("title").trim()
            .ifBlank { selectFirst(".vodlist_title a")?.text()?.trim().orEmpty() }
            .ifBlank { return null }
        val href = fixUrl(link.attr("href"))
        val poster = link.attr("data-original").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        val remark = link.selectFirst(".pic_text:not(.text_dy)")?.text()?.trim().orEmpty()
        val resolved = type ?: guessType(remark)
        return if (resolved == TvType.Movie) {
            newMovieSearchResponse(title, href, resolved) { posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, resolved) { posterUrl = poster }
        }
    }

    data class SuggestResponse(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("list") val list: List<SuggestItem>? = null,
    )

    data class SuggestItem(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("pic") val pic: String? = null,
    )

    data class PlayerConfig(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("encrypt") val encrypt: Int? = null,
        @JsonProperty("from") val from: String? = null,
    )

    companion object {
        private const val HOME_ROW = "home"
        private const val MAX_RECOMMENDATIONS = 18
        private const val PROBE_TIMEOUT_MS = 5000L
        private const val RANK_CLEAN = 0
        private const val RANK_OTHER = 50
        private const val RANK_ADS = 100
    }
}
