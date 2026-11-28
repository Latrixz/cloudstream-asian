package com.ddys

import com.csasian.shared.CsBackup

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DdysProvider : MainAPI() {
    override var mainUrl = "https://ddys.io"
    override var name = "低端影视 ddys"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // ddys.io sits behind a Cloudflare challenge. CloudStream's CloudflareKiller solves it in a
    // hidden WebView and reuses the clearance cookie for later requests.
    private val cloudflare = CloudflareKiller()
    private val jsonMapper = ObjectMapper()

    private suspend fun page(url: String): Document =
        app.get(url, interceptor = cloudflare, referer = "$mainUrl/").document

    // The site's sort options are 最新更新 (default), 近期热门 (/popular) and 豆瓣评分 (/rating).
    // It has no "newly added" sort, so the rows are 热门 / 最近更新 / 高分.
    override val mainPage = listOf(
        "movie" to "电影",
        "series" to "剧集",
        "variety" to "综艺",
        "anime" to "动漫",
    ).flatMap { (type, label) ->
        listOf(
            "popular" to "热门",
            "" to "最近更新",
            "rating" to "高分",
        ).map { (sort, sortLabel) ->
            MainPageData("$label · $sortLabel", "$type|$sort")
        }
    }

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        val type = request.data.substringBefore('|')
        val sort = request.data.substringAfter('|', "")
        val base = if (sort.isBlank()) "$mainUrl/$type" else "$mainUrl/$sort/$type"
        val url = if (page > 1) "$base/page/$page" else base
        val document = page(url)
        val items = document.select("div.movie-card").mapNotNull { it.toResult() }
        val hasNext = document.select("a[href]").any {
            it.attr("href").trimEnd('/').endsWith("/page/${page + 1}")
        }
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page > 1) "$mainUrl/search?q=$encoded&page=$page" else "$mainUrl/search?q=$encoded"
        val document = page(url)
        val items = document.select("div.movie-card").mapNotNull { it.toResult() }.distinctBy { it.url }
        val hasNext = document.select("a[href]").any {
            val href = it.attr("href")
            href.contains("page=${page + 1}") || href.trimEnd('/').endsWith("/page/${page + 1}")
        }
        return items to hasNext
    }

    // Remembers episodes and adds backup links from our other sources (see shared/CsBackup.kt).
    override suspend fun load(url: String): LoadResponse =
        com.csasian.shared.CsMore.load(this, url) ?: loadPage(url).also { CsBackup.remember(this, it) }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = CsBackup.loadLinks(this, data, subtitleCallback, callback) { callback ->
        loadOwnLinks(data, isCasting, subtitleCallback, callback)
    }

    private suspend fun loadPage(url: String): LoadResponse {
        val document = page(url)
        val content = document.selectFirst("#movie-content-wrap") ?: document
        val heading = content.selectFirst("h1") ?: throw ErrorLoadingException("找不到影片资讯")
        val title = heading.ownText().trim().ifBlank { heading.text().trim() }
        val poster = imageOf(content.selectFirst("img[alt]"))
        // "2016 · 中国 · 纪录片"
        val metaLine = heading.nextElementSibling()?.text().orEmpty()
        val metaParts = metaLine.split('·').map { it.trim() }.filter { it.isNotBlank() }
        val year = metaParts.firstOrNull()?.take(4)?.toIntOrNull()
        val tags = metaParts.drop(1)
        val rating = Regex("""豆瓣[（(]\s*([\d.]+)""").find(content.select(".rating-display").text())
            ?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0 }
        val actors = labelled(content, "主演").split('/', '、', ',').map { it.trim() }
            .filter { it.isNotBlank() }.take(15).map { ActorData(Actor(it)) }
        // The 剧情介绍 box holds several paragraphs; keep them all. Fall back to the longest <p>.
        val plot = sectionAfter(document, "剧情介绍")?.select("p")
            ?.map { it.text().trim() }?.filter { it.isNotBlank() }?.joinToString("\n\n")?.takeIf { it.length > 20 }
            ?: content.select("p").map { it.text().trim() }.maxByOrNull { it.length }?.takeIf { it.length > 20 }
        // 相关推荐 cards use the same markup as the list pages; they come from this page, no extra request.
        val recommended = sectionAfter(document, "相关推荐")?.select("div.movie-card")
            ?.mapNotNull { it.toResult() }
            ?.filter { it.url.trimEnd('/') != url.substringBefore('#').trimEnd('/') }
            ?.distinctBy { it.url }
            ?.take(16)
            .orEmpty()

        val sources = parseSources(document)
        if (sources.isEmpty()) throw ErrorLoadingException("此影片暂无在线播放源（可能只有网盘资源）")
        val longest = sources.maxByOrNull { it.episodes.size }!!
        val isSeries = longest.episodes.size > 1 || tags.any { it.contains("剧") || it.contains("动漫") || it.contains("综艺") }
        val type = when {
            tags.any { it.contains("动漫") || it.contains("动画") } && isSeries -> TvType.Anime
            isSeries -> TvType.TvSeries
            else -> TvType.Movie
        }

        return if (!isSeries) {
            newMovieLoadResponse(title, url, TvType.Movie, "$url#ep=0") {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = rating?.let { Score.from10(it) }
                this.actors = actors
                this.recommendations = recommended
            }
        } else {
            val episodes = longest.episodes.mapIndexed { index, ep ->
                newEpisode("$url#ep=$index") {
                    name = ep.name.ifBlank { "第 ${index + 1} 集" }
                    episode = index + 1
                }
            }
            newTvSeriesLoadResponse(title, url, type, episodes) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = rating?.let { Score.from10(it) }
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
        val pageUrl = data.substringBefore("#ep=")
        val index = data.substringAfter("#ep=", "0").toIntOrNull() ?: 0
        val sources = parseSources(page(pageUrl))
        val wantedName = sources.maxByOrNull { it.episodes.size }?.episodes?.getOrNull(index)?.name

        val candidates = sources.mapNotNull { source ->
            val episode = when {
                // A single-URL source is the whole movie, or the first episode of a series.
                source.episodes.size == 1 -> if (index == 0) source.episodes.first() else null
                wantedName != null -> source.episodes.firstOrNull { sameEpisode(it.name, wantedName) }
                    ?: source.episodes.getOrNull(index)
                else -> source.episodes.getOrNull(index)
            } ?: return@mapNotNull null
            source to episode.url
        }
        if (candidates.isEmpty()) return false

        // Check each playlist in parallel and offer the live ones first.
        val checked = candidates.amap { (source, url) -> Triple(source, url, isPlayable(url)) }
        val usable = checked.filter { it.third }.ifEmpty { checked }
        usable.forEach { (source, url, _) ->
            val isHls = url.contains(".m3u8", ignoreCase = true) || source.format.equals("m3u8", true)
            callback(
                newExtractorLink(
                    name,
                    listOf(name, source.name, source.quality).filter { it.isNotBlank() }.joinToString(" "),
                    url,
                    if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    quality = getQualityFromName(source.quality).takeIf { it != Qualities.Unknown.value }
                        ?: Qualities.Unknown.value
                },
            )
        }
        return true
    }

    private suspend fun isPlayable(url: String): Boolean {
        if (!url.contains(".m3u8", ignoreCase = true)) return true
        return runCatching { app.get(url, timeout = 6L).text.contains("#EXTM3U") }.getOrDefault(false)
    }

    private data class Episode(val name: String, val url: String)
    private data class Source(val name: String, val quality: String, val format: String, val episodes: List<Episode>)

    /** Reads `allSources = [...]`. A source's url is either one stream or "name$url#name$url…". */
    private fun parseSources(document: Document): List<Source> {
        val html = document.html()
        val marker = Regex("""allSources\s*=\s*""").find(html) ?: return emptyList()
        val json = extractJsonArray(html, marker.range.last + 1) ?: return emptyList()
        val root = runCatching { jsonMapper.readTree(json) }.getOrNull() ?: return emptyList()
        return root.mapNotNull { node ->
            val raw = node["url"]?.asText().orEmpty().trim()
            if (raw.isBlank()) return@mapNotNull null
            val episodes = if (raw.contains('$')) {
                raw.split('#').mapNotNull { part ->
                    val name = part.substringBefore('$').trim()
                    val link = part.substringAfter('$', "").trim()
                    if (link.startsWith("http")) Episode(name, link) else null
                }
            } else if (raw.startsWith("http")) {
                listOf(Episode("", raw))
            } else {
                emptyList()
            }
            if (episodes.isEmpty()) return@mapNotNull null
            Source(
                node["name"]?.asText().orEmpty(),
                node["quality"]?.asText().orEmpty(),
                node["format"]?.asText().orEmpty(),
                episodes,
            )
        }
    }

    private fun extractJsonArray(text: String, from: Int): String? {
        val begin = text.indexOf('[', from).takeIf { it >= 0 && it - from < 5 } ?: return null
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
                '[', '{' -> depth++
                ']', '}' -> {
                    depth--
                    if (depth == 0) return text.substring(begin, i + 1)
                }
            }
        }
        return null
    }

    private fun sameEpisode(a: String, b: String): Boolean = normalize(a) == normalize(b)

    private fun normalize(name: String): String =
        Regex("""\d+""").find(name)?.value?.trimStart('0')?.ifBlank { "0" } ?: name.trim()

    private fun labelled(root: Element, label: String): String =
        root.select("div").firstOrNull { div ->
            div.children().firstOrNull()?.text()?.trim()?.startsWith(label) == true
        }?.children()?.drop(1)?.joinToString(" ") { it.text() }.orEmpty()

    /** The box that holds an `<h2>` titled [heading] (e.g. 剧情介绍, 相关推荐). */
    private fun sectionAfter(document: Document, heading: String): Element? =
        document.select("h2").firstOrNull { it.text().trim() == heading }?.parent()

    private fun imageOf(img: Element?): String? {
        if (img == null) return null
        return listOf(img.attr("data-src"), img.attr("src"))
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { fixUrl(it) }
    }

    private fun Element.toResult(): SearchResponse? {
        val link = selectFirst("h3 a[href]") ?: selectFirst("a[href]") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = link.text().trim().ifBlank { selectFirst("img")?.attr("alt").orEmpty() }
        if (title.isBlank()) return null
        val poster = imageOf(selectFirst("img"))
        // "2023 · 剧集"; the first div.text-xs is the hidden 暂无海报 placeholder, so match on the separator.
        val kind = select("div.text-xs").firstOrNull { it.text().contains('·') }?.text().orEmpty()
        return if (kind.contains("电影")) {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        } else {
            val type = if (kind.contains("动漫")) TvType.Anime else TvType.TvSeries
            newTvSeriesSearchResponse(title, href, type) { posterUrl = poster }
        }
    }

}
