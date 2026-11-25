package com.xiaobao

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

/**
 * 小宝影院 (www.xiaobaotv.com), a MacCMS v10 site with the "mytheme" (myui) template.
 *
 * - Lists:   /vod/show/by/{hits|id|time}/id/{typeId}/page/{n}.html
 * - Search:  /search/page/{n}/wd/{query}.html
 * - Detail:  /vod/detail/{vodId}.html (one `div#playlistN` per 线路)
 * - Play:    /vod/play/{vodId}-{sid}-{nid}.html with `player_aaaa` holding the stream URL.
 *            The play page only lists its own 线路, so episode data carries every source's play URL.
 */
class XiaobaoProvider : MainAPI() {
    override var mainUrl = "https://www.xiaobaotv.com"
    override var name = "小宝影院 Xiaobao"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
        TvType.Documentary,
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
     * Top-level types are 1 电影, 2 电视剧, 3 动漫, 4 综艺 and 11 短剧. 纪录片 is the 电影 subtype 124
     * (labelled 记录片 on the site) and is offered as its own category.
     * Sorts: `by/hits` (人气), `by/id` (newest id = 最新添加, not linked in the UI but honoured by
     * MacCMS) and `by/time` (时间 = last update, the site's default).
     */
    override val mainPage = listOf(
        "1" to "电影",
        "2" to "电视剧",
        "4" to "综艺",
        "3" to "动漫",
        "11" to "短剧",
        "124" to "纪录片",
    ).flatMap { (category, label) ->
        listOf(
            "hits" to "热播",
            "id" to "最新添加",
            "time" to "最近更新",
        ).map { (order, orderLabel) ->
            MainPageData("$label · $orderLabel", "$category|$order")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val category = request.data.substringBefore('|')
        val order = request.data.substringAfter('|', "time")
        val pagePart = if (page > 1) "/page/$page" else ""
        val document = getDocument("$mainUrl/vod/show/by/$order/id/$category$pagePart.html")
        val (current, last) = pageInfo(document)
        val type = typeForCategory(category)
        val items = listItems(document).map { it.toSearchResponse(type) }
        return newHomePageResponse(request.name, items, current < last && items.isNotEmpty())
    }

    /** Reads "current/last" from the pager (`<li class="visible-xs"><a>2/38</a>`). */
    private fun pageInfo(document: Document): Pair<Int, Int> {
        val text = document.select(".myui-page .visible-xs a").map { it.text() }.firstOrNull { it.contains('/') }.orEmpty()
        val match = Regex("""(\d+)\s*/\s*(\d+)""").find(text) ?: return 1 to 1
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
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
        val document = getDocument("$mainUrl/search/page/${page.coerceAtLeast(1)}/wd/$encoded.html")
        val results = document.select("#searchList > li").mapNotNull { item ->
            val link = item.selectFirst("a.myui-vodlist__thumb") ?: return@mapNotNull null
            val href = link.attr("href").takeIf { it.contains("/vod/detail/") } ?: return@mapNotNull null
            val title = link.attr("title").trim()
                .ifBlank { item.selectFirst("h4.title a")?.text()?.trim().orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            val categoryText = item.select("p").firstOrNull { it.text().contains("分类：") }
                ?.text()?.substringAfter("分类：")?.substringBefore("地区")?.trim().orEmpty()
            ListItem(title, fixUrl(href), imageOf(link)).toSearchResponse(typeForCategoryName(categoryText))
        }.distinctBy { it.url }
        val (current, last) = pageInfo(document)
        return results to (current < last && results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url)
        val detail = document.selectFirst(".myui-content__detail") ?: throw ErrorLoadingException("找不到影片信息")
        val title = detail.selectFirst("h1.title")?.text()?.trim().orEmpty()
            .ifBlank { throw ErrorLoadingException("找不到影片标题") }
        val poster = imageOf(document.selectFirst(".myui-content__thumb img"))

        var categoryId = ""
        var categoryName = ""
        var year: Int? = null
        val tags = mutableListOf<String>()
        var actorNames = emptyList<String>()
        var directors = emptyList<String>()
        var updated: String? = null
        detail.select("p.data").forEach { line ->
            line.select("span.text-muted").forEach { label ->
                val value = label.nextElementSibling()
                when (label.text().trim().removeSuffix("：")) {
                    "分类" -> {
                        categoryId = Regex("""/id/(\d+)""").find(value?.attr("href").orEmpty())?.groupValues?.get(1).orEmpty()
                        categoryName = value?.text()?.trim().orEmpty()
                        tags += categoryName
                    }
                    "地区" -> value?.text()?.let { tags += it }
                    "年份" -> year = value?.text()?.trim()?.take(4)?.toIntOrNull()
                    "更新" -> updated = value?.text()?.trim()
                    "主演" -> actorNames = line.select("a[href*=/search/actor/]").map { it.text().trim() }
                    "导演" -> directors = line.select("a[href*=/search/director/]").map { it.text().trim() }
                }
            }
        }
        val type = typeForSubCategory(categoryId, categoryName)
        val plot = (document.selectFirst("#desc .content .data")?.text()
            ?: document.selectFirst("#desc .content .sketch")?.text())?.trim()?.ifBlank { null }

        val sources = parseSources(document)
        if (sources.isEmpty()) throw ErrorLoadingException("此影片暂无播放来源")

        // Genres (vod_class) and the full cast are only in the play page's player_aaaa.
        val vodData = runCatching { playerData(getDocument(sources.first().entries.first().url))?.get("vod_data") }.getOrNull()
        val genres = vodData?.get("vod_class")?.asText().orEmpty().split(',', '，', '/')
        val fullCast = vodData?.get("vod_actor")?.asText().orEmpty().split(',', '，', '/')
            .map { it.trim() }.filter { it.isNotBlank() && it != "内详" }
        val allTags = (tags + genres).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val actors = (fullCast.ifEmpty { actorNames })
            .filter { it.isNotBlank() && it != "内详" }.distinct().take(20).map { ActorData(Actor(it)) }
        val plotText = listOfNotNull(
            updated?.let { "（更新：$it）" },
            directors.filter { it.isNotBlank() && it != "内详" }.takeIf { it.isNotEmpty() }?.let { "导演：${it.joinToString("、")}" },
            plot,
        ).joinToString("\n").ifBlank { null }

        // 猜你喜欢 has two tabs on this page: 同类型 (ul#type) and 同主演 (ul#actor).
        val current = url.substringBefore('#')
        val recommended = document.select("ul#type, ul#actor")
            .flatMap { listItems(it) }
            .filter { it.url != current }
            .distinctBy { it.url }
            .take(16)
            .map { it.toSearchResponse(type) }

        // Sources can differ in length; list the most complete one and match the others by number.
        val canonical = sources.maxByOrNull { it.entries.size }!!
        val episodes = canonical.entries.mapIndexed { position, entry ->
            val alternatives = sources.mapNotNull { source ->
                val match = if (source === canonical) entry else matchEntry(source, entry, position, canonical.entries.size)
                match?.let { source.label to it.url }
            }
            entry to episodeData(alternatives)
        }

        return if (type == TvType.Movie || type == TvType.Documentary && episodes.size == 1) {
            newMovieLoadResponse(title, url, type, episodes.first().second) {
                posterUrl = poster
                this.plot = plotText
                this.year = year
                this.tags = allTags
                this.actors = actors
                this.recommendations = recommended
            }
        } else {
            val list = episodes.mapIndexed { position, (entry, data) ->
                newEpisode(data) {
                    name = episodeName(entry.name, position)
                    episode = position + 1
                }
            }
            newTvSeriesLoadResponse(title, url, type, list) {
                posterUrl = poster
                this.plot = plotText
                this.year = year
                this.tags = allTags
                this.actors = actors
                this.recommendations = recommended
            }
        }
    }

    private fun matchEntry(source: Source, entry: SourceEntry, position: Int, canonicalSize: Int): SourceEntry? {
        val key = normalizeEpisode(entry.name)
        source.entries.firstOrNull { normalizeEpisode(it.name) == key }?.let { return it }
        // Non-numbered single entries (HD中字, 正片 …) are the same film on every source.
        if (canonicalSize == 1 && source.entries.size == 1) return source.entries.first()
        return if (!key.all { it.isDigit() } && source.entries.size == canonicalSize) source.entries.getOrNull(position) else null
    }

    /**
     * Full play URL of the first source (so CloudStream leaves it alone), followed by
     * `#src=` and every source as `label~sid-nid` joined with `|`.
     */
    private fun episodeData(alternatives: List<Pair<String, String>>): String {
        val first = alternatives.first().second
        val packed = alternatives.joinToString("|") { (label, url) -> "${label.replace("|", " ").replace("~", " ")}~${playKey(url)}" }
        return "$first#src=${URLEncoder.encode(packed, "UTF-8")}"
    }

    private fun playKey(url: String): String = url.substringAfter("/vod/play/").substringBefore(".html")

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data.substringBefore("#src="))
        val packed = URLDecoder.decode(data.substringAfter("#src=", ""), "UTF-8")
        val candidates = packed.split('|').filter { it.contains('~') }.map { part ->
            part.substringBefore('~') to "$mainUrl/vod/play/${part.substringAfter('~')}.html"
        }.ifEmpty { listOf("线路 1" to pageUrl) }

        val streams = candidates.withIndex().toList().amap { (index, candidate) ->
            val (label, playUrl) = candidate
            val stream = runCatching { streamUrl(getDocument(playUrl)) }.getOrNull() ?: return@amap null
            Stream(index, label, stream, playUrl, isPlayable(stream))
        }.filterNotNull()
        if (streams.isEmpty()) return false

        // Working sources first, each group in the site's order.
        val ordered = streams.sortedWith(compareBy<Stream> { !it.alive }.thenBy { it.index })
        ordered.forEach { stream ->
            val sourceName = if (stream.alive) "$name ${stream.label}" else "$name ${stream.label}（可能失效）"
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
                loadExtractor(stream.url, stream.playUrl, subtitleCallback, callback)
            }
        }
        return true
    }

    private data class Stream(val index: Int, val label: String, val url: String, val playUrl: String, val alive: Boolean)

    /** Decodes player_aaaa.url the way the site's player.js does (encrypt 1 = unescape, 2 = base64 + unescape). */
    private fun streamUrl(document: Document): String? {
        val data = playerData(document) ?: return null
        // Paid (points) and trial-only entries need an account.
        if ((data["points"]?.asInt() ?: 0) > 0 || (data["trysee"]?.asInt() ?: 0) > 0) return null
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
        val script = document.select("script").map { it.data() }.firstOrNull { it.contains("player_aaaa") }
            ?: return null
        val json = Regex("""player_aaaa\s*=\s*(\{.*\})""", RegexOption.DOT_MATCHES_ALL).find(script)?.groupValues?.get(1)
            ?: return null
        return runCatching { jsonMapper.readTree(json) }.getOrNull()
    }

    /** The playlist must answer quickly with #EXTM3U; some 线路 hosts are dead or time out. */
    private suspend fun isPlayable(url: String): Boolean {
        if (!url.contains(".m3u8", ignoreCase = true)) return true
        return runCatching {
            app.get(url, headers = mapOf("User-Agent" to browserUserAgent), timeout = 8L).text.contains("#EXTM3U")
        }.getOrDefault(false)
    }

    private data class SourceEntry(val name: String, val url: String)

    private data class Source(val label: String, val entries: List<SourceEntry>)

    /** Each `div#playlistN` is one source (线路); tab labels come from `a[href=#playlistN]`. */
    private fun parseSources(document: Document): List<Source> =
        document.select("div.tab-pane[id^=playlist]").mapIndexed { index, pane ->
            val id = pane.id()
            val label = document.selectFirst("a[href=#$id]")?.text()?.trim().orEmpty()
                .ifBlank { "线路 ${index + 1}" }
            val entries = pane.select("a[href*=/vod/play/]").map { link ->
                SourceEntry(link.text().trim(), fixUrl(link.attr("href")))
            }.distinctBy { it.url }
            Source(label, entries)
        }.filter { it.entries.isNotEmpty() }

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

    private fun padBase64(text: String): String = text.trim().let { it.padEnd((it.length + 3) / 4 * 4, '=') }

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
        "3" -> TvType.Anime
        "124" -> TvType.Documentary
        "11" -> TvType.AsianDrama
        else -> TvType.TvSeries
    }

    /** Subtype ids are {top}{nn}: 1xx 电影, 2xx 电视剧, 3xx 动漫, 4xx 综艺; 11 is 短剧. */
    private fun typeForSubCategory(id: String, name: String): TvType = when {
        id == "124" -> TvType.Documentary
        id == "11" -> TvType.AsianDrama
        id == "1" || id.length == 3 && id.startsWith("1") -> TvType.Movie
        id == "3" || id.length == 3 && id.startsWith("3") -> TvType.Anime
        id.isBlank() -> typeForCategoryName(name)
        else -> TvType.TvSeries
    }

    private fun typeForCategoryName(name: String): TvType = when {
        name.contains("记录") || name.contains("纪录") -> TvType.Documentary
        name.contains("动漫") -> TvType.Anime
        name.endsWith("片") -> TvType.Movie
        name.contains("短剧") -> TvType.AsianDrama
        else -> TvType.TvSeries
    }

    private data class ListItem(val title: String, val url: String, val poster: String?)

    private fun listItems(root: Element): List<ListItem> =
        root.select(".myui-vodlist__box").mapNotNull { box ->
            val link = box.selectFirst("a.myui-vodlist__thumb") ?: return@mapNotNull null
            val href = link.attr("href").takeIf { it.contains("/vod/detail/") } ?: return@mapNotNull null
            val title = link.attr("title").trim()
                .ifBlank { box.selectFirst(".title a")?.text()?.trim().orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            ListItem(title, fixUrl(href), imageOf(link))
        }.distinctBy { it.url }

    private fun ListItem.toSearchResponse(type: TvType): SearchResponse =
        if (type == TvType.Movie) {
            newMovieSearchResponse(title, url, type) { posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, url, type) { posterUrl = poster }
        }

    /** Posters are lazy-loaded through `data-original`; external ones end with a `#err` marker. */
    private fun imageOf(element: Element?): String? {
        if (element == null) return null
        val candidates = listOf(element.attr("data-original"), element.attr("data-src"), element.attr("src"))
        return candidates.firstOrNull { it.isNotBlank() && !it.startsWith("data:") && !it.endsWith(".gif") }
            ?.substringBefore('#')
            ?.let { fixUrl(it) }
    }

    private suspend fun getDocument(url: String): Document = app.get(url, headers = browserHeaders).document
}
