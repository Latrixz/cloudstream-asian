package com.csxyy

import com.csasian.shared.CsAdStrip
import com.csasian.shared.CsBackup

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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * CN影院 (www.csxyy.com) is a MacCMS site (template ds3, same layout as huarw.com):
 *  - lists:   /show/{slug}/by/{time|hits}/page/{n}  (slugs: dianying, dianshiju, zongyi, dongman, duanju)
 *  - search:  /search/page/{n}/wd/{query}
 *  - detail:  /movie/{vodId} (sources in .anthology-tab, episodes in .anthology-list-box; each episode
 *             link carries data-play-sid and href /movie-play/{vodId}-{nid})
 *  - player:  /movie-play/{vodId}-{sid}-{nid} embeds `player_aaaa={...}` whose "url" is the m3u8 and
 *             "from" the supply network (1080zyk = yzzy, bfzym3u8, modum3u8).
 * Every network splices ad clips into its playlists; shared/CsAdStrip.kt removes them while playing.
 * The bfzy lists sometimes carry "[备用]" duplicates and unrelated extra episodes, so the episode list
 * is the one most sources agree on.
 */
class CsxyyProvider : MainAPI() {
    override var mainUrl = "https://www.csxyy.com"
    override var name = "CN影院 Csxyy"
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
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
    private val pageHeaders get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
    private val jsonMapper = ObjectMapper()

    private data class Category(val slug: String, val label: String, val type: TvType)

    private val categories = listOf(
        Category("dianying", "电影", TvType.Movie),
        Category("dianshiju", "电视剧", TvType.TvSeries),
        Category("zongyi", "综艺", TvType.TvSeries),
        Category("dongman", "动漫", TvType.Anime),
        Category("duanju", "短剧", TvType.AsianDrama),
    )

    // Same order as the site's home page: 最新热播推荐, then 最新X / X榜单 (by hits) per category.
    override val mainPage = listOf(MainPageData("最新热播推荐", HOME_ROW)) +
        categories.flatMap { category ->
            listOf(
                MainPageData("最新${category.label}", "${category.slug}|time"),
                MainPageData("${category.label}榜单", "${category.slug}|hits"),
            )
        }

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == HOME_ROW) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val document = getPage("$mainUrl/")
            val heading = document.select("h2.this-name").firstOrNull { it.text().trim() == "最新热播推荐" }
            val items = heading?.parent()?.parent()?.select(".public-list-box")
                ?.mapNotNull { it.toListResult(null) }?.distinctBy { it.url }.orEmpty()
            return newHomePageResponse(request.name, items, false)
        }
        val (slug, order) = request.data.split('|').let { it[0] to it.getOrElse(1) { "time" } }
        val type = categories.firstOrNull { it.slug == slug }?.type ?: TvType.TvSeries
        val document = getPage("$mainUrl/show/$slug/by/$order/page/$page")
        val items = document.select(".public-list-box").mapNotNull { it.toListResult(type) }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, items.isNotEmpty() && page < lastPage(document, page))
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
        val document = getPage("$mainUrl/search/page/$page/wd/$encoded")
        val results = document.select(".search-box").mapNotNull { box ->
            val link = box.selectFirst(".thumb-txt a[href*=/movie/]")
                ?: box.selectFirst("a[href*=/movie/]") ?: return@mapNotNull null
            val title = link.text().trim().ifBlank { return@mapNotNull null }
            val remark = box.selectFirst(".public-list-prb")?.text()?.trim().orEmpty()
            searchResult(title, fixUrl(link.attr("href")), imageOf(box.selectFirst("img")), guessType(remark))
        }.distinctBy { it.url }
        return results to (results.isNotEmpty() && page < lastPage(document, page))
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

    /** Strips the ad clips spliced into the playlists of every server. */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = CsAdStrip.interceptor

    private suspend fun loadPage(url: String): LoadResponse {
        val detailUrl = url.substringBefore('#')
        val document = getPage(detailUrl)
        val title = (document.selectFirst(".slide-info-title")?.text()?.trim()
            ?: document.selectFirst("h1.seo-h1")?.text()?.trim()).orEmpty()
            .ifBlank { throw ErrorLoadingException("找不到影片标题") }
        val poster = imageOf(document.selectFirst(".detail-pic img"))
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        val plot = document.selectFirst("#height_limit")?.text()?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[name=description]")?.attr("content")
        val remarks = document.select(".slide-info-remarks a")
        val year = remarks.firstOrNull { it.attr("href").contains("/year/") }?.text()?.trim()?.take(4)?.toIntOrNull()
        val tags = remarks.filter { it.attr("href").contains("/class/") || it.attr("href").contains("/area/") }
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val infoLine = { label: String ->
            document.select(".slide-info").firstOrNull { it.selectFirst("strong")?.text()?.contains(label) == true }
        }
        val names = { label: String, limit: Int ->
            infoLine(label)?.select("a")?.map { it.text().trim() }
                ?.filter { it.isNotBlank() && it != "内详" }?.distinct()?.take(limit).orEmpty()
        }
        val actors = (names("演员", 20).map { ActorData(Actor(it)) } +
            names("导演", 3).map { ActorData(Actor(it), roleString = "导演") }).ifEmpty { null }
        val status = infoLine("备注")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
        val score = document.selectFirst(".detail-score .fraction")?.text()?.trim()?.toDoubleOrNull()
            ?.takeIf { it > 0 }
        val plotText = listOfNotNull(status?.let { "（$it）" }, plot).joinToString("\n").ifBlank { null }

        val sources = parseSources(document)
        if (sources.isEmpty()) throw ErrorLoadingException("此影片暂无播放来源")
        val canonical = canonicalEpisodes(sources)
        val navType = typeOf(document)
        val isMovie = canonical.size == 1 && (navType == TvType.Movie || navType == null)
        val type = when {
            isMovie -> TvType.Movie
            navType == null || navType == TvType.Movie -> TvType.TvSeries
            else -> navType
        }
        val recommended = recommendations(document, detailUrl, type)

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
        // data = https://www.csxyy.com/movie/{id}#ep={normalized name}&nid={n}  (ep is empty for movies)
        val detailUrl = data.substringBefore('#')
        val fragment = data.substringAfter('#', "")
        val params = fragment.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val wanted = URLDecoder.decode(params["ep"].orEmpty(), "UTF-8")
        val wantedNid = params["nid"]?.toIntOrNull()

        val sources = parseSources(getPage(detailUrl))
        // One episode per source, matched by episode number/name (falling back to the same position).
        // A source that only has one "全集" file never stands in for a single episode.
        val targets = sources.mapNotNull { source ->
            val entry = if (wanted.isNotBlank()) {
                source.episodes.firstOrNull { normalizeEpisode(it.name) == wanted }
                    ?: source.episodes.firstOrNull { it.nid == wantedNid && !it.name.contains("全集") }
            } else {
                source.episodes.firstOrNull()
            }
            entry?.let { source.label to it }
        }
        if (targets.isEmpty()) return false

        val links = coroutineScope {
            targets.map { (label, entry) ->
                async { playUrl(entry)?.let { (url, from) -> Link(label, from, url) } }
            }.awaitAll().filterNotNull().distinctBy { it.url }
        }.sortedBy { serverRank(it.from) } // stable: keeps the site's order within a rank

        val seen = mutableMapOf<String, Int>()
        links.forEach { link ->
            val count = (seen[link.label] ?: 0) + 1
            seen[link.label] = count
            val label = if (count > 1) "${link.label} $count" else link.label
            callback(
                newExtractorLink(
                    name,
                    "$name $label",
                    link.url,
                    if (link.url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    referer = "$mainUrl/"
                    // The 极速 (modu) server is the slowest to start: only tried after the others.
                    quality = if (link.from.startsWith("modu")) Qualities.P144.value else Qualities.Unknown.value
                    headers = mapOf("User-Agent" to userAgent)
                },
            )
        }
        return links.isNotEmpty()
    }

    private data class Link(val label: String, val from: String, val url: String)

    /** yzzy (1080zyk) first, then bfzy, other networks, and modu last. */
    private fun serverRank(from: String): Int = when {
        from.contains("1080zyk") || from.contains("yzzy") -> 0
        from.startsWith("bfzy") -> 1
        from.startsWith("modu") -> 3
        else -> 2
    }

    /** Reads `player_aaaa` from /movie-play/{id}-{sid}-{nid}: the decoded stream URL and its "from". */
    private suspend fun playUrl(entry: EpisodeEntry): Pair<String, String>? = runCatching {
        val html = app.get(
            "$mainUrl/movie-play/${entry.vodId}-${entry.sid}-${entry.nid}",
            headers = pageHeaders,
        ).text
        val start = html.indexOf("player_aaaa")
        if (start < 0) return null
        val eq = html.indexOf('=', start)
        val config: JsonNode = extractJsonObject(html, eq + 1)?.let { jsonMapper.readTree(it) } ?: return null
        val raw = config.path("url").asText("").trim()
        if (raw.isBlank()) return null
        val url = when (config.path("encrypt").asInt(0)) {
            1 -> jsUnescape(raw)
            2 -> jsUnescape(String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8))
            else -> raw
        }.trim()
        if (!url.startsWith("http")) return null
        url to config.path("from").asText("").lowercase()
    }.getOrNull()

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

    /** Backs off and retries when the site answers a burst (e.g. all home rows at once) with HTTP 429. */
    private suspend fun getPage(url: String): Document {
        repeat(3) { attempt ->
            val response = app.get(url, headers = pageHeaders)
            if (response.code != 429) return response.document
            delay(1500L * (attempt + 1))
        }
        return app.get(url, headers = pageHeaders).document
    }

    /** "共N条数据,当前x/y页" -> y. */
    private fun lastPage(document: Document, page: Int): Int =
        Regex("""当前\s*\d+\s*/\s*(\d+)\s*页""").find(document.select(".page-tip").text())
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: document.select("a[href*=/page/]").mapNotNull {
                Regex("""/page/(\d+)""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            }.maxOrNull()
            ?: page

    private data class EpisodeEntry(val name: String, val vodId: String, val sid: Int, val nid: Int)
    private data class Source(val label: String, val episodes: List<EpisodeEntry>)

    private fun parseSources(document: Document): List<Source> {
        val labels = document.select(".anthology-tab a").map {
            it.ownText().replace('\u00a0', ' ').trim().removePrefix("【").removeSuffix("】").trim()
        }
        return document.select(".anthology-list .anthology-list-box").mapIndexedNotNull { index, box ->
            val all = box.select("a[href*=/movie-play/]").mapNotNull { link ->
                val match = Regex("""/movie-play/(\d+)-(\d+)$""").find(link.attr("href").trim())
                    ?: return@mapNotNull null
                val (vodId, nid) = match.destructured
                val sid = link.attr("data-play-sid").toIntOrNull() ?: (index + 1)
                EpisodeEntry(link.text().trim(), vodId, sid, nid.toInt())
            }
            // bfzy repeats its list as "第01集 [备用]" entries.
            val episodes = all.filterNot { it.name.contains("备用") }.ifEmpty { all }
                .distinctBy { normalizeEpisode(it.name) }
            if (episodes.isEmpty()) return@mapIndexedNotNull null
            Source(labels.getOrNull(index)?.ifBlank { null } ?: "线路 ${index + 1}", episodes)
        }
    }

    /**
     * The episode list most sources agree on (ties: the longer one). Sources whose list is a single
     * "全集" file are ignored when others have real episodes; bfzy lists can carry unrelated extras.
     */
    private fun canonicalEpisodes(sources: List<Source>): List<EpisodeEntry> {
        val candidates = sources.filterNot { it.episodes.size == 1 && it.episodes[0].name.contains("全集") }
            .ifEmpty { sources }
        val size = candidates.groupingBy { it.episodes.size }.eachCount()
            .maxWithOrNull(compareBy<Map.Entry<Int, Int>>({ it.value }, { it.key }))!!.key
        return candidates.first { it.episodes.size == size }.episodes
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
        // Movie sources name their single entry differently (HD / 正片 / HD国语), so match movies by position.
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
            normalized.all { it.isDigit() } -> "第 $normalized 集"
            else -> raw.trim()
        }
    }

    /** The header nav marks the title's top-level category (/type/{slug}) as "current". */
    private fun typeOf(document: Document): TvType? {
        val slug = document.selectFirst("a.current[href^=/type/]")?.attr("href")?.substringAfterLast('/')
        return categories.firstOrNull { it.slug == slug }?.type
    }

    private fun guessType(remark: String): TvType =
        if (Regex("""正片|HD|高清|抢先|TC|TS|蓝光|中字|4K""", RegexOption.IGNORE_CASE).containsMatchIn(remark)) {
            TvType.Movie
        } else {
            TvType.TvSeries
        }

    private fun imageOf(img: Element?): String? {
        if (img == null) return null
        return listOf(img.attr("data-src"), img.attr("data-original"), img.attr("src"))
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { fixUrl(it) }
    }

    /** [type] null = guess from the badge (e.g. the mixed 最新热播推荐 row). */
    private fun Element.toListResult(type: TvType?): SearchResponse? {
        val link = selectFirst("a.public-list-exp[href*=/movie/]") ?: return null
        val title = link.attr("title").trim()
            .ifBlank { selectFirst(".time-title")?.text()?.trim().orEmpty() }
            .ifBlank { return null }
        val remark = selectFirst(".public-list-prb")?.text()?.trim().orEmpty()
        return searchResult(title, fixUrl(link.attr("href")), imageOf(link.selectFirst("img")), type ?: guessType(remark))
    }

    /** No quality badges: the cards only say "全集", "更新至10集" etc. */
    private fun searchResult(title: String, href: String, poster: String?, type: TvType): SearchResponse =
        if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, type) { posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, type) { posterUrl = poster }
        }

    companion object {
        private const val HOME_ROW = "home"
        private const val MAX_RECOMMENDATIONS = 16
    }
}
