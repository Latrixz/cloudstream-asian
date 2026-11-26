package com.mvffm

import com.csasian.shared.CsBackup
import com.csasian.shared.CsQuality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

class MvffmProvider : MainAPI() {
    override var mainUrl = "https://www.mvffm.net"
    override var name = "Movieffm"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Documentary,
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        "Referer" to "$mainUrl/",
    )
    private val jsonMapper = ObjectMapper()

    // Category = "<path>|<tvtype>", mirroring the site's own filters.
    // Rows per category: 熱播 (orderby=week), 最新添加 (date), 最近更新 (modified).
    override val mainPage = listOf(
        "movies|" to "電影",
        "drama|" to "連續劇",
        "drama|10" to "綜藝",
        "drama|anime" to "動漫",
        "drama|documentary-film" to "紀錄片",
    ).flatMap { (category, label) ->
        listOf(
            "week" to "熱播",
            "date" to "最新添加",
            "modified" to "最近更新",
        ).map { (order, orderLabel) ->
            MainPageData("$label · $orderLabel", "$category|$order")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (path, tvtype, order) = request.data.split('|').let {
            Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "date" })
        }
        val pagePath = if (page > 1) "page/$page/" else ""
        val query = buildList {
            if (tvtype.isNotBlank()) add("tvtype=$tvtype")
            add("orderby=$order")
        }.joinToString("&")
        val document = app.get("$mainUrl/$path/$pagePath?$query", headers = browserHeaders).document
        val items = document.select("article.item").mapNotNull { it.toListResult() }
        val lastPage = Regex("""頁面\s*\d+\s*of\s*(\d+)""")
            .find(document.select(".pagination").text())
            ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: page
        return newHomePageResponse(request.name, items, page < lastPage)
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
        val document = app.get("$mainUrl/xssearch?q=$encoded&f=_all&p=$page", headers = browserHeaders).document
        val results = document.select("div.result-item").mapNotNull { item ->
            val link = item.selectFirst(".thumbnail a, .title a") ?: return@mapNotNull null
            val href = fixUrl(link.attr("href"))
            // /tvshows/ pages are only hubs that link to the playable /drama/ season pages.
            if (!href.contains("/movies/") && !href.contains("/drama/")) return@mapNotNull null
            val title = item.selectFirst(".title a")?.text()?.trim().orEmpty()
                .ifBlank { item.selectFirst("img")?.attr("alt").orEmpty() }
            val poster = imageOf(item.selectFirst("img"))
            searchResult(title, href, poster, typeOf(href, ""))
        }
        val hasNext = document.select(".pagination a").any { it.attr("href").contains("p=${page + 1}") }
        return results.distinctBy { it.url } to hasNext
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
        val document = app.get(url, headers = browserHeaders).document
        val header = document.selectFirst(".sheader") ?: throw ErrorLoadingException("找不到影片資訊")
        val title = header.selectFirst(".data h1")?.text()?.trim().orEmpty()
            .ifBlank { throw ErrorLoadingException("找不到影片標題") }
        val poster = imageOf(header.selectFirst(".poster img"))
        val infoLines = header.select(".dramacbox .dbl").associate { line ->
            line.ownText().substringBefore('：').trim() to line
        }
        val typeText = infoLines["類型"]?.text().orEmpty()
        val type = typeOf(url, typeText)
        val year = (infoLines["上映"] ?: infoLines["年份"])?.selectFirst("span")?.text()?.trim()?.take(4)?.toIntOrNull()
        val rating = document.selectFirst(".dt_rating_vgs")?.text()?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }
        val plot = document.selectFirst("[itemprop=description] p, .sbox .wp-content p")?.text()?.trim()
        val tags = (
            header.select(".sgeneros a").map { it.text() } +
                (infoLines["類型"]?.select("a")?.map { it.text() } ?: emptyList()) +
                (infoLines["地區"]?.select("a")?.map { it.text() } ?: emptyList()) +
                listOfNotNull(header.selectFirst(".country b")?.text())
            ).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        // Movie pages have a TMDB-backed #cast tab (photo + character); series pages only list
        // names under 主演.
        val castCards = document.select("#cast .person").mapNotNull { person ->
            val name = person.selectFirst(".name")?.text()?.trim().orEmpty()
            val role = person.selectFirst(".caracter")?.text()?.trim().orEmpty()
            if (name.isBlank() || role == "導演") return@mapNotNull null
            val photo = imageOf(person.selectFirst(".img img"))
                ?.takeUnless { it.trimEnd('/').endsWith("/w92") }
                ?.replace("/t/p/w92/", "/t/p/w185/")
            ActorData(Actor(name, photo), roleString = role.ifBlank { null })
        }
        val listedNames = (infoLines["主演"]?.select("a")?.map { it.text() }?.takeIf { it.isNotEmpty() }
            ?: infoLines["主演"]?.text()?.substringAfter('：')?.split('/', ',', '，', '、', ' ').orEmpty())
            .map { it.trim() }.filter { it.isNotBlank() }
        val actors = (castCards + listedNames.map { ActorData(Actor(it)) })
            .distinctBy { it.actor.name }
            .take(15)
        // "片長：<b>97 分鐘</b>" (movies only).
        val duration = Regex("""(\d+)\s*分""").find(header.select(".runtime").text())
            ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        // 劇照 gallery: full-size TMDB stills.
        val background = document.select("#dt_galery .g-item a[href]")
            .map { it.attr("href").trim() }
            .firstOrNull { it.startsWith("http") }
        // 猜你喜歡 is the first (active) tab; the others are site-wide 日/周/月人氣 lists.
        val current = url.substringBefore('#').trimEnd('/')
        val relatedTab = document.selectFirst("#single_relacionados_b .relacion_tab.active")
            ?: document.selectFirst("#single_relacionados_b .relacion_tab")
        val recommended = relatedTab?.select("article")?.mapNotNull { it.toRelatedResult() }
            ?.filter { it.url.trimEnd('/') != current }
            ?.distinctBy { it.url }
            ?.take(16)
            .orEmpty()
        val status = infoLines["狀態"]?.selectFirst("span")?.text()?.trim()
        val plotText = listOfNotNull(status?.let { "（$it）" }, plot).joinToString("\n").ifBlank { null }

        val sources = parseSources(document)
        if (sources.isEmpty()) throw ErrorLoadingException("此影片暫無播放來源")

        return if (type == TvType.Movie) {
            newMovieLoadResponse(title, url, type, "$url#ep=") {
                posterUrl = poster
                this.plot = plotText
                this.year = year
                this.tags = tags
                this.score = rating?.let { Score.from10(it) }
                this.actors = actors
                this.duration = duration
                this.backgroundPosterUrl = background
                this.recommendations = recommended
            }
        } else {
            // Episode lists differ slightly between sources; use the most complete one.
            val canonical = sources.maxByOrNull { it.size }.orEmpty()
            val episodes = canonical.mapIndexed { position, entry ->
                newEpisode("$url#ep=${URLEncoder.encode(normalizeEpisode(entry.name), "UTF-8")}") {
                    name = episodeName(entry.name, position)
                    episode = position + 1
                }
            }
            newTvSeriesLoadResponse(title, url, type, episodes) {
                posterUrl = poster
                this.plot = plotText
                this.year = year
                this.tags = tags
                this.score = rating?.let { Score.from10(it) }
                this.actors = actors
                this.duration = duration
                this.backgroundPosterUrl = background
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
        val wanted = URLDecoder.decode(data.substringAfter("#ep=", ""), "UTF-8")
        val document = app.get(pageUrl, headers = browserHeaders).document
        val sources = parseSources(document)
        val labels = sourceLabels(document)
        val candidates = sources.mapIndexedNotNull { index, entries ->
            val entry = if (wanted.isBlank()) {
                entries.firstOrNull()
            } else {
                entries.firstOrNull { normalizeEpisode(it.name) == wanted }
            } ?: return@mapIndexedNotNull null
            (labels.getOrNull(index) ?: "片源 ${index + 1}") to entry.url
        }
        if (candidates.isEmpty()) return false

        // Roughly a third of the site's sources are dead at any time. Check every playlist in
        // parallel and offer only the live ones, keeping the site's order; fall back to all.
        val alive = candidates.amap { (label, url) -> Triple(label, url, isPlayable(url)) }
        val usable = alive.filter { it.third }.ifEmpty { alive }
        usable.forEach { (label, url, _) ->
            val isHls = url.contains(".m3u8", ignoreCase = true)
            callback(
                newExtractorLink(
                    name,
                    "$name $label",
                    url,
                    if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    quality = Qualities.Unknown.value
                },
            )
        }
        return true
    }

    private suspend fun isPlayable(url: String): Boolean {
        if (!url.contains(".m3u8", ignoreCase = true)) return true
        return runCatching {
            app.get(url, headers = mapOf("User-Agent" to browserHeaders.getValue("User-Agent")), timeout = 6L)
                .text.contains("#EXTM3U")
        }.getOrDefault(false)
    }

    private data class SourceEntry(val name: String, val url: String)

    /**
     * Reads the player's inline `videourls` data. Series pages hold one list (or index-keyed
     * object) of {name, url} per source; movie pages hold a flat list of {source, url, qname}.
     * URLs are base64-encoded, and movie URLs are additionally percent-encoded.
     */
    private fun parseSources(document: Document): List<List<SourceEntry>> {
        val html = document.html()
        val start = html.indexOf("videourls:")
        if (start < 0) return emptyList()
        val json = extractJsonArray(html, start + "videourls:".length) ?: return emptyList()
        val root = runCatching { jsonMapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!root.isArray || root.size() == 0) return emptyList()

        val first = root[0]
        return if (first.isObject && first.has("source")) {
            root.groupBy { it["source"]?.asInt() ?: 0 }
                .toSortedMap()
                .values
                .map { group ->
                    group.mapNotNull { node ->
                        decodeUrl(node["url"]?.asText())?.let {
                            SourceEntry(node["qname"]?.asText().orEmpty(), it)
                        }
                    }
                }
                .filter { it.isNotEmpty() }
        } else {
            root.map { source -> sourceEntries(source) }.filter { it.isNotEmpty() }
        }
    }

    private fun sourceEntries(source: JsonNode): List<SourceEntry> {
        val nodes = when {
            source.isArray -> source.toList()
            source.isObject -> source.fields().asSequence()
                .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
                .map { it.value }
                .toList()
            else -> emptyList()
        }
        return nodes.mapNotNull { node ->
            val url = decodeUrl(node["url"]?.asText()) ?: return@mapNotNull null
            SourceEntry(node["name"]?.asText().orEmpty(), url)
        }
    }

    private fun sourceLabels(document: Document): List<String> {
        val html = document.html()
        val start = html.indexOf("tables:")
        if (start < 0) return emptyList()
        val json = extractJsonArray(html, start + "tables:".length) ?: return emptyList()
        val root = runCatching { jsonMapper.readTree(json) }.getOrNull() ?: return emptyList()
        return root.map { node ->
            org.jsoup.Jsoup.parse(node["ht"]?.asText().orEmpty()).text().trim()
        }
    }

    /** Returns the JSON array that starts at [from], respecting strings and escapes. */
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

    private fun decodeUrl(encoded: String?): String? {
        if (encoded.isNullOrBlank()) return null
        val decoded = runCatching {
            String(Base64.getDecoder().decode(encoded.trim().padEnd((encoded.trim().length + 3) / 4 * 4, '=')))
        }.getOrNull() ?: return null
        val plain = if (decoded.contains('%')) URLDecoder.decode(decoded, "UTF-8") else decoded
        return plain.trim().takeIf { it.startsWith("http") }
    }

    private fun normalizeEpisode(name: String): String {
        val trimmed = name.trim()
        val digits = Regex("""^第?\s*0*(\d+)\s*[集話话]?$""").find(trimmed)?.groupValues?.get(1)
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

    private fun typeOf(url: String, typeText: String): TvType = when {
        url.contains("/movies/") -> TvType.Movie
        typeText.contains("動漫") -> TvType.Anime
        typeText.contains("紀錄") -> TvType.Documentary
        else -> TvType.TvSeries
    }

    private fun imageOf(img: Element?): String? {
        if (img == null) return null
        val candidates = listOf(img.attr("data-lazy-src"), img.attr("data-src"), img.attr("src"))
        return candidates.firstOrNull { it.isNotBlank() && !it.startsWith("data:") }?.let { fixUrl(it) }
    }

    private fun Element.toListResult(): SearchResponse? {
        val link = selectFirst(".data h3 a") ?: selectFirst(".poster a") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = link.text().trim().ifBlank { selectFirst("img")?.attr("alt").orEmpty() }
        if (title.isBlank()) return null
        val poster = imageOf(selectFirst(".poster img"))
        val typeText = selectFirst(".dramaleixing")?.text().orEmpty()
        // Movie cards carry a quality tag (".mepo .quality": "HD", "TC中字", "HC中字"); drama cards don't.
        val quality = CsQuality.from(selectFirst(".mepo .quality")?.text())
        return searchResult(title, href, poster, typeOf(href, typeText), quality)
    }

    private fun Element.toRelatedResult(): SearchResponse? {
        val link = selectFirst("a[href]") ?: return null
        val href = fixUrl(link.attr("href"))
        // Same rule as search: /tvshows/ hubs are not playable.
        if (!href.contains("/movies/") && !href.contains("/drama/")) return null
        val img = selectFirst("img")
        val title = img?.attr("alt")?.trim().orEmpty().ifBlank { link.text().trim() }
        if (title.isBlank()) return null
        return searchResult(title, href, imageOf(img), typeOf(href, ""))
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
}
