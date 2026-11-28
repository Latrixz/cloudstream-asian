package com.huarw

import com.csasian.shared.CsBackup
import com.csasian.shared.CsBrowse
import com.csasian.shared.CsMacCms
import com.csasian.shared.CsQuality
import com.csasian.shared.CsSearch

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
 * 华人影院 (huarw.com) is a MacCMS site:
 *  - lists:   /show/{slug}/by/{time|hits|score}/page/{n}  (slugs: dianying, dianshiju, zongyi, dongman, duanju)
 *  - search:  /search/page/{n}/wd/{query}
 *  - detail:  /movie/{vodId} (sources in .anthology-tab, episodes in .anthology-list-box; each episode
 *             link carries data-play-sid and href /play/{vodId}-{nid})
 *  - player:  /play/{vodId}-{sid}-{nid} embeds `player_aaaa={...}` whose "url" is the m3u8
 *             (MacCMS "encrypt": 0 plain, 1 escape(), 2 base64(escape())).
 * The "modum3u8" (极速) streams have ad clips spliced into the playlist; we can't strip those, so the
 * site's order (ad-free sources first) is kept.
 */
class HuarwProvider : MainAPI() {
    override var mainUrl = "https://huarw.com"
    override var name = "华人影院 Huarw"
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

    /**
     * The site's own filters (/show/{slug}/area/{a}/by/{by}/lang/{l}/page/{n}/wd/{q}/year/{y}), as listed
     * on its filter pages; a sub-category is its own slug.
     */
    private val site = CsMacCms.Site(
        CsMacCms.Path("/show/", suffix = ""),
        types = listOf(
            CsMacCms.Type(
                "dianying", "电影",
                subtypes = listOf(
                    "xijupian" to "喜剧片", "kongbupian" to "恐怖片", "dongzuopian" to "动作片", "juqingpian" to "剧情片",
                    "aiqingpian" to "爱情片", "kehuanpian" to "科幻片", "donghuapian" to "动画片", "zhanzhengpian" to "战争片",
                ),
            ),
            CsMacCms.Type(
                "dianshiju", "电视剧",
                subtypes = listOf(
                    "guochangju" to "国产剧", "hanguoju" to "韩国剧", "ribenju" to "日本剧", "oumeiju" to "欧美剧", "haiwaiju" to "海外剧",
                ),
            ),
            CsMacCms.Type(
                "zongyi", "综艺",
                subtypes = listOf("daluzongyi" to "大陆综艺", "rihanzongyi" to "日韩综艺", "oumeizongyi" to "欧美综艺"),
                areas = listOf("内地", "港台", "日韩", "欧美"),
                langs = listOf("国语", "英语", "粤语", "闽南语", "韩语", "日语", "其它"),
            ),
            CsMacCms.Type(
                "dongman", "动漫",
                subtypes = listOf(
                    "guochandongman" to "国产动漫", "rihandongman" to "日韩动漫", "oumeidongman" to "欧美动漫", "haiwaidongman" to "海外动漫",
                ),
                areas = listOf("国产", "日本", "欧美", "其他"),
                langs = listOf("国语", "英语", "粤语", "闽南语", "韩语", "日语", "其它"),
            ),
            CsMacCms.Type(
                "duanju", "短剧",
                subtypes = listOf(
                    "shenhaoduanju" to "神豪短剧", "chongshengduanju" to "重生短剧", "fuchouduanju" to "复仇短剧", "chuanyueduanju" to "穿越短剧",
                ),
                areas = emptyList(), langs = emptyList(), years = emptyList(),
            ),
        ),
        areas = listOf("大陆", "香港", "台湾", "美国", "法国", "英国", "日本", "韩国", "德国", "泰国", "印度", "意大利", "西班牙", "加拿大", "其他"),
        langs = listOf("国语", "英语", "粤语", "闽南语", "韩语", "日语", "法语", "德语", "其它"),
        years = CsMacCms.years(17),
        sorts = listOf("time" to "最新", "hits" to "最热", "score" to "评分"),
    )

    // Category browsing (shared/CsBrowse.kt): our own category page on CloudStream, the app's own on SkyStream Dev.
    private val browse = CsBrowse(this, name, site.sections) { id, filters, query, page ->
        val type = categories.firstOrNull { it.slug == id }?.type ?: TvType.TvSeries
        val document = getPage(site.url(mainUrl, id, filters, query, page))
        val items = document.select(".public-list-box").mapNotNull { it.toListResult(type) }.distinctBy { it.url }
        items to (items.isNotEmpty() && page < lastPage(document, page))
    }

    // Same order as the site's home page: 最新热播推荐, then 最新X / X榜单 (by hits) per category;
    // on CloudStream the "分类 Categories" row comes first.
    override val mainPage = browse.mainPage(
        listOf(MainPageData("最新热播推荐", HOME_ROW)) +
            categories.flatMap { category ->
                listOf(
                    MainPageData("最新${category.label}", "${category.slug}|time"),
                    MainPageData("${category.label}热播", "${category.slug}|hits"),
                )
            },
    )

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        browse.rowPage(request) ?: com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    // Categories SkyStream Dev shows itself (shared/CsCategories.kt); the engine finds these by name.
    fun csCategories(): String = browse.categoriesJson()

    fun csCategoryPage(categoryId: String, filtersJson: String, query: String, page: Int): SearchResponseList =
        browse.categoryPage(categoryId, filtersJson, query, page)

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

    // Page 1 also searches the title in Chinese when it was typed in another language (shared/CsSearch.kt).
    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> =
        CsSearch.withExtra(query, page, lang) { q, p -> searchOwn(q, p) }

    private suspend fun searchOwn(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val document = getPage("$mainUrl/search/page/$page/wd/$encoded")
        val results = document.select(".search-box").mapNotNull { box ->
            val link = box.selectFirst(".thumb-txt a[href*=/movie/]")
                ?: box.selectFirst("a[href*=/movie/]") ?: return@mapNotNull null
            val title = link.text().trim().ifBlank { return@mapNotNull null }
            val remark = box.selectFirst(".public-list-prb")?.text()?.trim().orEmpty()
            searchResult(title, fixUrl(link.attr("href")), imageOf(box.selectFirst("img")), guessType(remark), remark)
        }.distinctBy { it.url }
        return results to (results.isNotEmpty() && page < lastPage(document, page))
    }

    // Remembers episodes and adds backup links from our other sources (see shared/CsBackup.kt).
    // A "分类 Categories" card opens the category page (browse.load first: it checks who is asking).
    override suspend fun load(url: String): LoadResponse =
        browse.load(url) ?: com.csasian.shared.CsMore.load(this, url) ?: loadPage(url).also { CsBackup.remember(this, it) }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        browse.reopen(data) // ▶ on a category's own page opens the category page again.
        return CsBackup.loadLinks(this, data, subtitleCallback, callback) {
            loadOwnLinks(data, isCasting, subtitleCallback, callback)
        }
    }

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
        // Sources usually share one episode list; use the most complete one as the canonical list.
        val canonical = sources.maxByOrNull { it.episodes.size }!!.episodes
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
        // data = https://huarw.com/movie/{id}#ep={normalized name}&nid={n}  (ep is empty for movies)
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

        val links = coroutineScope {
            targets.map { (label, entry) ->
                async { playUrl(entry)?.let { Triple(label, entry, it) } }
            }.awaitAll().filterNotNull()
        }

        // Keep the site's source order (ad-free sources first).
        links.forEach { (label, entry, streamUrl) ->
            callback(
                newExtractorLink(
                    name,
                    "$name $label",
                    streamUrl,
                    if (streamUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    referer = "$mainUrl/"
                    // The 极速 (modu) server has spliced-in ads: only tried after the clean ones.
                    quality = if (label.contains("极速")) Qualities.P144.value else qualityOf("$label ${entry.name}")
                    headers = mapOf("User-Agent" to userAgent)
                },
            )
        }
        return links.isNotEmpty()
    }

    /** Reads `player_aaaa` from /play/{id}-{sid}-{nid} and returns the decoded stream URL. */
    private suspend fun playUrl(entry: EpisodeEntry): String? = runCatching {
        val html = app.get(
            "$mainUrl/play/${entry.vodId}-${entry.sid}-${entry.nid}",
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
        url.takeIf { it.startsWith("http") }
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
        val labels = document.select(".anthology-tab a").map { it.ownText().replace('\u00a0', ' ').trim() }
        return document.select(".anthology-list .anthology-list-box").mapIndexedNotNull { index, box ->
            val episodes = box.select("a[href*=/play/]").mapNotNull { link ->
                val match = Regex("""/play/(\d+)-(\d+)$""").find(link.attr("href").trim())
                    ?: return@mapNotNull null
                val (vodId, nid) = match.destructured
                val sid = link.attr("data-play-sid").toIntOrNull() ?: (index + 1)
                EpisodeEntry(link.text().trim(), vodId, sid, nid.toInt())
            }
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
        return searchResult(title, fixUrl(link.attr("href")), imageOf(link.selectFirst("img")), type ?: guessType(remark), remark)
    }

    /** [remark] is the card's own label ("HD中字", "TC", "更新至10集"); only real quality labels become a badge. */
    private fun searchResult(title: String, href: String, poster: String?, type: TvType, remark: String? = null): SearchResponse =
        if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, type) { posterUrl = poster; quality = CsQuality.from(remark) }
        } else {
            newTvSeriesSearchResponse(title, href, type) { posterUrl = poster; quality = CsQuality.from(remark) }
        }

    companion object {
        private const val HOME_ROW = "home"
        private const val MAX_RECOMMENDATIONS = 16
    }
}
