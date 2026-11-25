package com.duboku

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class DubokuProvider : MainAPI() {
    override var mainUrl = "https://www.dbku.tv"
    override var name = "独播库 Duboku"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    private val browserUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val browserHeaders
        get() = mapOf(
            "User-Agent" to browserUserAgent,
            "Referer" to "$mainUrl/",
        )
    private val jsonMapper = ObjectMapper()

    /**
     * MacCMS list pages: /vodshow/{type}-{area}-{by}-{class}-{lang}-{letter}---{page}---{year}.html.
     * The site only offers three sorts: 时间 (default, last update), 人气 (popularity) and 评分.
     * There is no "time added" sort, so 最新添加 walks the year filters newest first
     * (2026, 2025, …, 更早), which together cover the whole category.
     */
    override val mainPage = listOf(
        "2" to "连续剧",
        "13" to "陆剧",
        "15" to "日韩剧",
        "14" to "台泰剧",
        "21" to "短剧",
        "1" to "电影",
        "3" to "综艺",
        "4" to "动漫",
    ).flatMap { (category, label) ->
        listOf(
            ORDER_HITS to "热播",
            ORDER_NEW to "最新添加",
            ORDER_TIME to "最近更新",
        ).map { (order, orderLabel) ->
            MainPageData("$label · $orderLabel", "$category|$order")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val category = request.data.substringBefore('|')
        val order = request.data.substringAfter('|', ORDER_TIME)
        val type = typeForCategory(category)
        val (items, hasNext) = when (order) {
            ORDER_NEW -> newestByYear(category, page)
            else -> {
                val by = if (order == ORDER_HITS) BY_HITS else ""
                val document = getDocument(showUrl(category, by = by, page = page))
                val (current, last) = pageInfo(document)
                listItems(document) to (current < last)
            }
        }
        return newHomePageResponse(request.name, items.map { it.toSearchResponse(type) }, hasNext)
    }

    private data class YearBucket(val year: String, var pages: Int? = null)

    private val yearBuckets = ConcurrentHashMap<String, Pair<Long, List<YearBucket>>>()

    /** Page [page] of the concatenation of every year filter, newest year first. */
    private suspend fun newestByYear(category: String, page: Int): Pair<List<ListItem>, Boolean> {
        val buckets = yearBucketsFor(category)
        if (buckets.isEmpty()) {
            val document = getDocument(showUrl(category, page = page))
            val (current, last) = pageInfo(document)
            return listItems(document) to (current < last)
        }
        var remaining = page
        buckets.forEachIndexed { index, bucket ->
            var firstPage: Document? = null
            val pages = bucket.pages ?: run {
                val document = getDocument(showUrl(category, page = 1, year = bucket.year))
                firstPage = document
                (if (listItems(document).isEmpty()) 0 else pageInfo(document).second).also { bucket.pages = it }
            }
            if (remaining <= pages) {
                val document = firstPage?.takeIf { remaining == 1 }
                    ?: getDocument(showUrl(category, page = remaining, year = bucket.year))
                val hasNext = remaining < pages || index < buckets.lastIndex
                return listItems(document) to hasNext
            }
            remaining -= pages
        }
        return emptyList<ListItem>() to false
    }

    private suspend fun yearBucketsFor(category: String): List<YearBucket> {
        val cached = yearBuckets[category]
        if (cached != null && System.currentTimeMillis() - cached.first < CACHE_MILLIS) return cached.second
        val document = getDocument(showUrl(category))
        val pattern = Regex("""/vodshow/$category-{11}([^./-]+)\.html""")
        val years = document.select("a[href*=/vodshow/$category-]")
            .mapNotNull { pattern.find(it.attr("href"))?.groupValues?.get(1) }
            .distinct()
            .sortedWith(compareByDescending<String> { URLDecoder.decode(it, "UTF-8").toIntOrNull() ?: Int.MIN_VALUE })
            .map { YearBucket(it) }
        yearBuckets[category] = System.currentTimeMillis() to years
        return years
    }

    private fun showUrl(category: String, by: String = "", page: Int = 1, year: String = ""): String {
        val encodedBy = if (by.isBlank()) "" else URLEncoder.encode(by, "UTF-8")
        val pagePart = if (page > 1 || year.isNotBlank()) page.toString() else ""
        return "$mainUrl/vodshow/$category--$encodedBy------$pagePart---$year.html"
    }

    /** Reads "current/last" from the pager (`<li class="visible-xs"><a>2/38</a>`). */
    private fun pageInfo(document: Document): Pair<Int, Int> {
        val text = document.selectFirst(".myui-page .visible-xs a")?.text().orEmpty()
        val match = Regex("""(\d+)\s*/\s*(\d+)""").find(text) ?: return 1 to 1
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    /**
     * The site's search returns at most 30 results and ignores every paging parameter
     * (path or query), so only page 1 has results.
     */
    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank() || page > 1) return emptyList<SearchResponse>() to false
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = getDocument("$mainUrl/vodsearch/-------------.html?wd=$encoded")
        val results = document.select("#searchList > li").mapNotNull { item ->
            val link = item.selectFirst("a.myui-vodlist__thumb") ?: return@mapNotNull null
            val href = fixUrl(link.attr("href"))
            val title = item.selectFirst("h4.title a")?.text()?.trim().orEmpty()
                .ifBlank { link.attr("title").trim() }
            if (title.isBlank()) return@mapNotNull null
            val categoryText = item.select("p").firstOrNull { it.text().contains("分类：") }
                ?.text()?.substringAfter("分类：")?.substringBefore("地区")?.trim().orEmpty()
            ListItem(title, href, imageOf(link)).toSearchResponse(typeForCategoryName(categoryText))
        }
        val (current, last) = pageInfo(document)
        return results.distinctBy { it.url } to (current < last)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url)
        val detail = document.selectFirst(".myui-content__detail") ?: throw ErrorLoadingException("找不到影片信息")
        val title = detail.selectFirst("h1.title")?.text()?.trim().orEmpty()
            .ifBlank { throw ErrorLoadingException("找不到影片标题") }
        val poster = imageOf(document.selectFirst(".myui-content__thumb img"))
        val score = detail.selectFirst(".score .branch")?.text()?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }

        var categoryId = ""
        var year: Int? = null
        val tags = mutableListOf<String>()
        var actorNames = emptyList<String>()
        var updated: String? = null
        detail.select("p.data").forEach { line ->
            line.select("span.text-muted").forEach { label ->
                val value = label.nextElementSibling()
                when (label.text().trim().removeSuffix("：")) {
                    "分类" -> {
                        categoryId = Regex("""/vodshow/(\d+)-""").find(value?.attr("href").orEmpty())
                            ?.groupValues?.get(1).orEmpty()
                        value?.text()?.let { tags += it }
                    }
                    "地区" -> value?.text()?.let { tags += it }
                    "年份" -> year = value?.text()?.trim()?.take(4)?.toIntOrNull()
                    "更新" -> updated = value?.text()?.trim()
                    "主演" -> actorNames = line.select("a[href*=/vodsearch/]").map { it.text().trim() }
                }
            }
        }
        val type = typeForCategory(categoryId)
        val plot = cleanPlot(
            title,
            document.selectFirst("#desc .content .data")?.text()
                ?: document.selectFirst("#desc .content .sketch")?.text()
                ?: detail.select("p.data").firstOrNull { it.text().startsWith("简介") }?.text()?.substringAfter("："),
        )

        val sources = parseSources(document)
        if (sources.isEmpty() || sources.all { it.entries.isEmpty() }) {
            throw ErrorLoadingException("此影片暂无播放来源")
        }

        // The genres (vod_class) are only in the play page's player_data.
        val firstPlay = sources.first { it.entries.isNotEmpty() }.entries.first().url
        val genres = runCatching { playerData(getDocument(firstPlay))?.let { decodeVodText(it["vod_data"]?.get("vod_class")?.asText()) } }
            .getOrNull()
            ?.split(',', '，', '/')
            .orEmpty()
        val allTags = (tags + genres).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val actors = actorNames.filter { it.isNotBlank() }.distinct().take(15).map { ActorData(Actor(it)) }
        val plotText = listOfNotNull(updated?.let { "（更新：$it）" }, plot).joinToString("\n").ifBlank { null }

        return if (type == TvType.Movie) {
            val entry = sources.first { it.entries.isNotEmpty() }.entries.first()
            newMovieLoadResponse(title, url, type, episodeData(entry)) {
                posterUrl = poster
                this.plot = plotText
                this.year = year
                this.tags = allTags
                this.score = score?.let { Score.from10(it) }
                this.actors = actors
            }
        } else {
            // Sources can differ in length; list the most complete one and match the rest by number.
            val canonical = sources.maxByOrNull { it.entries.size }!!.entries
            val episodes = canonical.mapIndexed { position, entry ->
                newEpisode(episodeData(entry)) {
                    name = episodeName(entry.name, position)
                    episode = position + 1
                }
            }
            newTvSeriesLoadResponse(title, url, type, episodes) {
                posterUrl = poster
                this.plot = plotText
                this.year = year
                this.tags = allTags
                this.score = score?.let { Score.from10(it) }
                this.actors = actors
            }
        }
    }

    /** Full vodplay URL plus the normalized episode name, so CloudStream leaves it untouched. */
    private fun episodeData(entry: SourceEntry): String =
        "${entry.url}#ep=${URLEncoder.encode(normalizeEpisode(entry.name), "UTF-8")}"

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data.substringBefore("#ep="))
        val wanted = URLDecoder.decode(data.substringAfter("#ep=", ""), "UTF-8")
        val document = getDocument(pageUrl)

        // The play page carries the same playlist panel as the detail page, so every source's
        // copy of this episode can be found from here.
        val sources = parseSources(document)
        val candidates = sources.mapIndexedNotNull { index, source ->
            val entry = source.entries.firstOrNull { samePage(it.url, pageUrl) }
                ?: source.entries.firstOrNull { wanted.isNotBlank() && normalizeEpisode(it.name) == wanted }
                ?: return@mapIndexedNotNull null
            Triple(index, source.label, entry.url)
        }.ifEmpty { listOf(Triple(0, "线路 1", pageUrl)) }

        val streams = candidates.amap { (index, label, playUrl) ->
            val playDocument = if (samePage(playUrl, pageUrl)) document else runCatching { getDocument(playUrl) }.getOrNull()
            val stream = playDocument?.let { streamUrl(it) } ?: return@amap null
            Stream(index, label, stream, isPlayable(stream))
        }.filterNotNull()
        if (streams.isEmpty()) return false

        // Working sources first, each group in the site's order.
        val ordered = streams.sortedWith(compareBy<Stream> { !it.alive }.thenBy { it.index })
        val labelled = streams.size > 1 || sources.size > 1
        ordered.forEach { stream ->
            val sourceName = if (labelled) "$name ${stream.label}" else name
            if (isMedia(stream.url)) {
                callback(
                    newExtractorLink(
                        name,
                        sourceName,
                        stream.url,
                        if (stream.url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    ) {
                        referer = "$mainUrl/"
                        quality = Qualities.Unknown.value
                        headers = mapOf("User-Agent" to browserUserAgent)
                    },
                )
            } else {
                loadExtractor(stream.url, pageUrl, subtitleCallback, callback)
            }
        }
        return true
    }

    private data class Stream(val index: Int, val label: String, val url: String, val alive: Boolean)

    /** Decodes player_data.url the way the site's player.min.js does (encrypt 1 = escape, 2 = base64 + escape). */
    private fun streamUrl(document: Document): String? {
        val data = playerData(document) ?: return null
        // Paid (points) entries are not offered.
        if ((data["points"]?.asInt() ?: 0) > 0) return null
        val raw = data["url"]?.asText().orEmpty()
        if (raw.isBlank()) return null
        val decoded = when (data["encrypt"]?.asText()) {
            "1" -> unescape(raw)
            "2" -> runCatching { unescape(String(Base64.getDecoder().decode(padBase64(raw)), Charsets.ISO_8859_1)) }.getOrNull()
            else -> raw
        }?.trim()
        return decoded?.takeIf { it.startsWith("http") || it.startsWith("//") }?.let { if (it.startsWith("//")) "https:$it" else it }
    }

    private fun playerData(document: Document): JsonNode? {
        val script = document.select("script").map { it.data() }.firstOrNull { it.contains("player_") && it.contains("\"url\"") }
            ?: return null
        val json = Regex("""var\s+player_\w+\s*=\s*(\{.*\})""", RegexOption.DOT_MATCHES_ALL).find(script)?.groupValues?.get(1)
            ?: return null
        return runCatching { jsonMapper.readTree(json) }.getOrNull()
    }

    private suspend fun isPlayable(url: String): Boolean {
        if (!url.contains(".m3u8", ignoreCase = true)) return true
        return runCatching {
            app.get(url, headers = browserHeaders, timeout = 8L).text.contains("#EXTM3U")
        }.getOrDefault(false)
    }

    private data class SourceEntry(val name: String, val url: String)

    private data class Source(val label: String, val entries: List<SourceEntry>)

    /** Each `div#playlistN` is one source (线路); tab labels come from `a[href=#playlistN]` when present. */
    private fun parseSources(document: Document): List<Source> =
        document.select("div.tab-pane[id^=playlist]").mapIndexed { index, pane ->
            val id = pane.id()
            val label = document.selectFirst("a[href=#$id]")?.text()?.trim().orEmpty()
                .ifBlank { "线路 ${index + 1}" }
            val entries = pane.select("a[href*=/vodplay/]").map { link ->
                SourceEntry(link.text().trim(), fixUrl(link.attr("href")))
            }.distinctBy { it.url }
            Source(label, entries)
        }.filter { it.entries.isNotEmpty() }

    private fun samePage(a: String, b: String): Boolean =
        a.substringAfter("/vodplay/").substringBefore('.') == b.substringAfter("/vodplay/").substringBefore('.')

    private fun isMedia(url: String): Boolean =
        Regex("""\.(m3u8|mp4|mkv|flv|webm)(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)

    /** JavaScript unescape(): %XX and %uXXXX. */
    private fun unescape(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                val unicode = if (i + 6 <= text.length && text[i + 1] == 'u') text.substring(i + 2, i + 6).toIntOrNull(16) else null
                if (unicode != null) {
                    out.append(unicode.toChar())
                    i += 6
                    continue
                }
                val byte = if (i + 3 <= text.length) text.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (byte != null) {
                    out.append(byte.toChar())
                    i += 3
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** vod_data strings use "/uXXXX" for unicode escapes plus percent-encoding (e.g. "%2C"). */
    private fun decodeVodText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val unicode = Regex("""/u([0-9a-fA-F]{4})""").replace(text) { it.groupValues[1].toInt(16).toChar().toString() }
        return runCatching { URLDecoder.decode(unicode.replace("+", "%2B"), "UTF-8") }.getOrDefault(unicode)
    }

    private fun padBase64(text: String): String = text.trim().let { it.padEnd((it.length + 3) / 4 * 4, '=') }

    private fun cleanPlot(title: String, raw: String?): String? {
        var text = raw?.trim().orEmpty()
        if (text.isBlank()) return null
        // Descriptions start with SEO text such as "《片名》线上看，共26集，《片名》简介:".
        text = text.replace(Regex("""^《[^》]*》线上看[，,]?"""), "")
            .replace(Regex("""^共\d+集[，,]?"""), "")
            .replace(Regex("""^《[^》]*》简介[:：]\s*"""), "")
            .trim()
        return text.ifBlank { null }
    }

    private fun normalizeEpisode(name: String): String {
        val trimmed = name.trim()
        val digits = Regex("""^第?\s*0*(\d+)\s*[集話话期]?$""").find(trimmed)?.groupValues?.get(1)
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

    private fun typeForCategory(category: String): TvType = when (category) {
        "1" -> TvType.Movie
        "4" -> TvType.Anime
        else -> TvType.TvSeries
    }

    private fun typeForCategoryName(name: String): TvType = when {
        name.contains("电影") -> TvType.Movie
        name.contains("动漫") -> TvType.Anime
        else -> TvType.TvSeries
    }

    private data class ListItem(val title: String, val url: String, val poster: String?, val remark: String? = null)

    private fun listItems(document: Document): List<ListItem> =
        document.select(".myui-vodlist__box").mapNotNull { box ->
            val link = box.selectFirst("a.myui-vodlist__thumb") ?: return@mapNotNull null
            val href = link.attr("href").takeIf { it.contains("/voddetail/") } ?: return@mapNotNull null
            val title = link.attr("title").trim()
                .ifBlank { box.selectFirst(".title a")?.text()?.trim().orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            ListItem(title, fixUrl(href), imageOf(link), box.selectFirst(".pic-text")?.text()?.trim())
        }.distinctBy { it.url }

    private fun ListItem.toSearchResponse(type: TvType): SearchResponse =
        if (type == TvType.Movie) {
            newMovieSearchResponse(title, url, type) { posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, url, type) { posterUrl = poster }
        }

    private fun imageOf(element: Element?): String? {
        if (element == null) return null
        val candidates = listOf(element.attr("data-original"), element.attr("data-src"), element.attr("src"))
        return candidates.firstOrNull { it.isNotBlank() && !it.startsWith("data:") && !it.contains("/static/img/loading") }
            ?.let { fixUrl(it) }
    }

    private suspend fun getDocument(url: String): Document = app.get(url, headers = browserHeaders).document

    companion object {
        private const val ORDER_HITS = "hits"
        private const val ORDER_NEW = "new"
        private const val ORDER_TIME = "time"
        private const val BY_HITS = "人气"
        private const val CACHE_MILLIS = 30 * 60 * 1000L
    }
}
