package com.cilixiong

import android.util.Log
import com.csasian.shared.CsAdStrip
import com.csasian.shared.CsBackup
import com.csasian.shared.CsBrowse
import com.csasian.shared.CsCategories
import com.csasian.shared.CsSearch

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Interceptor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * 磁力熊 (www.cilixiong.org) is an Empire CMS site with static pages:
 *  - lists:   /movie/, /drama/, /top250/, /s/imdbtop250/ (page n: index_{n}.html) and the filters
 *             /{1 movie|2 drama}-{genre}-{region}-{page-1}.html
 *  - search:  POST /e/search/index.php redirects to /e/search/result/?searchid=N; further pages are
 *             /e/search/result/index.php?page={page-1}&searchid=N
 *  - detail:  /movie/{id}.html or /drama/{id}.html: info lines, synopsis and a list of bare magnets
 *             ("release name[size]")
 *  - stream:  /e/extend/jx.php?id={id} holds `var vurl='…m3u8'`. Series only stream episode 1.
 * The ffzy / lz-cdn feeds splice ad clips into their playlists; shared/CsAdStrip.kt removes them.
 */
class CilixiongProvider : MainAPI() {
    override var mainUrl = "https://www.cilixiong.org"
    override var name = "磁力熊 Cilixiong"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
    private val pageHeaders get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")

    /**
     * Category browsing (shared/CsBrowse.kt) with the site's filter pages /{1 movie|2 drama}-{genre}-{region}-{page-1}.html
     * (类型 and 地区 ids as the site lists them; its 情色 genre is left out), plus the 豆瓣 and IMDb Top250
     * lists. The site has one order only, newest added first. A search inside 电影 or 剧集 is the site's
     * search limited to that class; inside a Top250 list, the site's whole search.
     */
    private val browse = CsBrowse(this, name, browseSections()) { id, filters, query, page ->
        if (query.isNotBlank()) return@CsBrowse searchOwn(query, page, id.takeIf { it == "1" || it == "2" } ?: "1,2")
        val url = if (id.endsWith("/")) {
            if (page <= 1) "$mainUrl/$id" else "$mainUrl/${id}index_$page.html"
        } else {
            "$mainUrl/$id-${filters["genre"] ?: "0"}-${filters["region"] ?: "0"}-${page - 1}.html"
        }
        val document = app.get(url, headers = pageHeaders).document
        val items = cards(document)
        items to (items.isNotEmpty() && hasNextPage(document))
    }

    private fun browseSections(): List<CsBrowse.Section> {
        val genres = listOf(
            "剧情", "喜剧", "惊悚", "动作", "爱情", "犯罪", "恐怖", "冒险", "悬疑", "科幻", "家庭", "奇幻", "动画", "战争",
            "历史", "传记", "音乐", "歌舞", "运动", "西部", "灾难", "古装", "情色", "同性", "儿童", "纪录片",
        ).mapIndexedNotNull { i, label -> if (label == "情色") null else CsCategories.Option((i + 1).toString(), label) }
        val regions = listOf(
            "大陆", "香港", "台湾", "美国", "日本", "韩国", "英国", "法国", "德国", "印度", "泰国", "丹麦", "瑞典", "巴西",
            "加拿大", "俄罗斯", "意大利", "比利时", "爱尔兰", "西班牙", "澳大利亚", "波兰", "土耳其", "越南",
        ).mapIndexed { i, label -> CsCategories.Option((i + 1).toString(), label) }
        val filters = listOf(CsCategories.Filter("genre", "类型", genres), CsCategories.Filter("region", "地区", regions))
        return listOf(
            CsBrowse.Section("1", "电影", filters),
            CsBrowse.Section("2", "剧集", filters, english = "TV Series"),
            CsBrowse.Section("top250/", "豆瓣 Top250", english = "Douban Top 250", glyph = "🏆", slug = "douban-top250"),
            CsBrowse.Section("s/imdbtop250/", "IMDb Top250", english = "IMDb Top 250", glyph = "🏆", slug = "imdb-top250"),
        )
    }

    // data = list path ("movie/", "top250/", ...) or a filter prefix ("1-0-6" = Korean movies).
    // On CloudStream the "分类 Categories" row comes first.
    override val mainPage = browse.mainPage(mainPageOf(
        "movie/" to "最新电影",
        "drama/" to "最新剧集",
        "top250/" to "豆瓣电影 Top250",
        "s/imdbtop250/" to "IMDb Top250",
        "1-0-1" to "华语电影",
        "1-0-2" to "香港电影",
        "1-0-6" to "韩国电影",
        "1-0-5" to "日本电影",
        "2-0-6" to "韩剧",
        "2-0-5" to "日剧",
        "2-0-4" to "美剧",
    ))

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        browse.rowPage(request) ?: com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    // Categories SkyStream Dev shows itself (shared/CsCategories.kt); the engine finds these by name.
    fun csCategories(): String = browse.categoriesJson()

    fun csCategoryPage(categoryId: String, filtersJson: String, query: String, page: Int): SearchResponseList =
        browse.categoryPage(categoryId, filtersJson, query, page)

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val url = when {
            path.endsWith("/") -> if (page <= 1) "$mainUrl/$path" else "$mainUrl/${path}index_$page.html"
            else -> "$mainUrl/$path-${page - 1}.html"
        }
        val document = app.get(url, headers = pageHeaders).document
        val items = cards(document)
        return newHomePageResponse(request.name, items, items.isNotEmpty() && hasNextPage(document))
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

    /** The site's search; [classId] "1" movies, "2" series, "1,2" both. */
    private suspend fun searchOwn(query: String, page: Int, classId: String = "1,2"): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        // The site gives the same searchid for the same keyword, so posting again for page n is cheap.
        val first = app.post(
            "$mainUrl/e/search/index.php",
            headers = pageHeaders,
            data = mapOf("keyboard" to query.trim(), "show" to "title", "classid" to classId, "tempid" to "1"),
        )
        val document = if (page <= 1) {
            first.document
        } else {
            val searchId = Regex("""searchid=(\d+)""").find(first.url)?.groupValues?.get(1)
                ?: return emptyList<SearchResponse>() to false
            app.get("$mainUrl/e/search/result/index.php?page=${page - 1}&searchid=$searchId", headers = pageHeaders)
                .document
        }
        val results = cards(document) // "没有搜索到相关的内容" page has no cards
        return results to (results.isNotEmpty() && hasNextPage(document))
    }

    // Remembers the streamable episodes and adds backup links from our other sources (see shared/CsBackup.kt).
    // A "分类 Categories" card opens the category page (browse.load first: it checks who is asking).
    override suspend fun load(url: String): LoadResponse =
        browse.load(url) ?: loadPage(url).also { CsBackup.remember(this, backupView(it)) }

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

    /** Strips the ad clips spliced into the ffzy / lz-cdn playlists. */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = CsAdStrip.interceptor

    private suspend fun loadPage(url: String): LoadResponse {
        val detailUrl = url.substringBefore('#')
        val id = idOf(detailUrl) ?: throw ErrorLoadingException("无效的影片地址")
        val (document, stream) = coroutineScope {
            val streamJob = async { streamUrl(id) }
            app.get(detailUrl, headers = pageHeaders).document to streamJob.await()
        }
        val title = document.selectFirst(".mv_detail h1, h1")?.text()?.trim().orEmpty()
            .ifBlank { throw ErrorLoadingException("找不到影片标题") }
        val info = infoLines(document)
        val genres = info["类型"].orEmpty().split('|', '/', ' ').map { it.trim() }.filter { it.isNotBlank() }
        val regions = info["上映地区"].orEmpty().split('/', ' ').map { it.trim() }.filter { it.isNotBlank() }
        if (genres.any { it in ADULT_TAGS }) throw ErrorLoadingException("此影片不提供")
        val tags = (genres + regions).distinct()
        val year = Regex("""(\d{4})""").find(info["上映日期"].orEmpty())?.value?.toIntOrNull()
        val duration = Regex("""(\d+)\s*分""").find(info["片长"] ?: info["单集片长"].orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        val actors = info["主演"].orEmpty().split('/').map { it.trim() }.filter { it.isNotBlank() }.distinct()
            .take(20).map { ActorData(Actor(it)) }.ifEmpty { null }
        val score = document.selectFirst(".db_rank")?.text()?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }
        val synopsis = document.selectFirst(".mv_card_box")?.wholeText()
            ?.lines()?.map { it.trim().trim('　') }?.filter { it.isNotBlank() }?.joinToString("\n")
            ?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
        val plot = listOfNotNull(synopsis, info["又名"]?.let { "又名：$it" }).joinToString("\n\n").ifBlank { null }
        val poster = document.selectFirst(".mv_detail")?.parent()?.selectFirst("img")?.attr("src")?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val magnets = magnets(document)

        return if (detailUrl.contains("/drama/")) {
            val episodes = buildList {
                if (stream != null) add(newEpisode("$detailUrl#$PREVIEW") { name = "第1集 (在线预览)"; episode = 1 })
                magnets.forEach { magnet ->
                    add(newEpisode("$detailUrl#$MAGNET=${magnet.hash}") { name = magnet.label })
                }
            }
            if (episodes.isEmpty()) throw ErrorLoadingException("此剧集暂无资源")
            newTvSeriesLoadResponse(title, detailUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score?.let { Score.from10(it) }
                this.actors = actors
                this.duration = duration
            }
        } else {
            if (stream == null && magnets.isEmpty()) throw ErrorLoadingException("此影片暂无资源")
            newMovieLoadResponse(title, detailUrl, TvType.Movie, detailUrl) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score?.let { Score.from10(it) }
                this.actors = actors
                this.duration = duration
            }
        }
    }

    /**
     * What CsBackup should know about: the movie, or only the series' streamed episode 1. The magnet
     * entries are season packs, not episodes, so they must not pull in "episode N" from other sources.
     */
    private suspend fun backupView(response: LoadResponse): LoadResponse {
        if (response !is TvSeriesLoadResponse) return response
        val preview = response.episodes.filter { it.data.endsWith("#$PREVIEW") }
        return newTvSeriesLoadResponse(response.name, response.url, response.type, preview) { year = response.year }
    }

    private suspend fun loadOwnLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // Movie: the detail URL. Series: detail URL + "#play" (episode 1 stream) or "#mag={btih}".
        val detailUrl = data.substringBefore('#')
        val fragment = data.substringAfter('#', "")
        val id = idOf(detailUrl) ?: return false
        val isMovie = fragment.isEmpty()
        val wantStream = isMovie || fragment == PREVIEW
        val wantedHash = fragment.substringAfter("$MAGNET=", "").ifBlank { null }

        val (stream, magnets) = coroutineScope {
            val streamJob = async { if (wantStream) streamUrl(id) else null }
            val magnetList = if (isMovie || wantedHash != null) {
                magnets(app.get(detailUrl, headers = pageHeaders).document)
            } else {
                emptyList()
            }
            streamJob.await() to magnetList
        }

        var streamQuality: Int? = null
        if (stream != null) {
            streamQuality = playlistQuality(stream)
            callback(
                newExtractorLink(name, "$name 在线播放", stream, ExtractorLinkType.M3U8) {
                    quality = streamQuality
                    headers = mapOf("User-Agent" to userAgent)
                },
            )
        }
        val picked = if (wantedHash != null) magnets.filter { it.hash == wantedHash } else magnets
        picked.forEach { magnet ->
            // Never above the stream, so the player keeps the stream first; the name says the real quality.
            val q = magnet.quality.let { if (streamQuality != null) minOf(it, streamQuality) else it }
            callback(
                newExtractorLink(name, "$name 磁力 ${magnet.label}", magnetUri(magnet), ExtractorLinkType.MAGNET) {
                    quality = q
                },
            )
        }
        if (stream == null && picked.isEmpty()) Log.w(TAG, "no links for $data")
        return stream != null || picked.isNotEmpty()
    }

    private data class Magnet(val hash: String, val release: String, val size: String?, val quality: Int) {
        val label: String get() = if (size != null) "$release [$size]" else release
    }

    /** `<a href="magnet:?xt=urn:btih:HASH">release.name.1080p.mkv[3.0G]</a>` */
    private fun magnets(document: Document): List<Magnet> =
        document.select(".mv_down a[href^=magnet:]").mapNotNull { link ->
            val hash = Regex("""btih:([0-9A-Za-z]+)""").find(link.attr("href"))?.groupValues?.get(1)?.uppercase()
                ?: return@mapNotNull null
            val text = link.text().trim()
            val sizeMatch = Regex("""\[([\d.]+\s*[KMGT]i?B?)]\s*$""", RegexOption.IGNORE_CASE).find(text)
            val release = (if (sizeMatch != null) text.substring(0, sizeMatch.range.first) else text).trim()
                .ifBlank { hash }
            Magnet(hash, release, sizeMatch?.groupValues?.get(1), qualityOf(release))
        }.distinctBy { it.hash }

    /** The bare links carry no trackers and DHT-only lookups are slow, so add well-known public ones. */
    private fun magnetUri(magnet: Magnet): String = buildString {
        append("magnet:?xt=urn:btih:").append(magnet.hash)
        append("&dn=").append(URLEncoder.encode(magnet.release, "UTF-8"))
        TRACKERS.forEach { append("&tr=").append(URLEncoder.encode(it, "UTF-8")) }
    }

    private fun qualityOf(release: String): Int = when {
        Regex("""2160|4K|UHD""", RegexOption.IGNORE_CASE).containsMatchIn(release) -> Qualities.P2160.value
        release.contains("1080") -> Qualities.P1080.value
        release.contains("720") -> Qualities.P720.value
        else -> Qualities.Unknown.value
    }

    /** vurl from the embedded player page; null when missing or on the dead i.cilixiong.pro host. */
    private suspend fun streamUrl(id: String): String? = runCatching {
        val html = app.get("$mainUrl/e/extend/jx.php?id=$id", headers = pageHeaders).text
        Regex("""var\s+vurl\s*=\s*'([^']*)'""").find(html)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.startsWith("http") && !it.contains(DEAD_HOST) }
    }.onFailure { Log.w(TAG, "jx.php failed id=$id: ${it.message}") }.getOrNull()

    /**
     * 4K when the master playlist's RESOLUTION says so, otherwise 1080. The feeds' lower RESOLUTION
     * values are placeholders (ffzy says "1080x608" for a 1920x808 stream), so they are not trusted.
     */
    private suspend fun playlistQuality(url: String): Int = runCatching {
        val text = app.get(url, headers = mapOf("User-Agent" to userAgent), timeout = 8L).text
        val is4k = Regex("""RESOLUTION=(\d+)x(\d+)""").findAll(text).any {
            (it.groupValues[1].toIntOrNull() ?: 0) >= 3800 || (it.groupValues[2].toIntOrNull() ?: 0) >= 2100
        }
        if (is4k) Qualities.P2160.value else Qualities.P1080.value
    }.getOrDefault(Qualities.P1080.value)

    /** "<p>类型：|剧情|战争|</p>" lines of the detail box, by label. */
    private fun infoLines(document: Document): Map<String, String> =
        document.select(".mv_detail p").mapNotNull { p ->
            val text = p.text().trim()
            val sep = text.indexOfFirst { it == '：' || it == ':' }.takeIf { it > 0 } ?: return@mapNotNull null
            text.substring(0, sep).trim() to text.substring(sep + 1).trim()
        }.filter { it.second.isNotBlank() }.toMap()

    private fun idOf(url: String): String? = Regex("""/(?:movie|drama)/(\d+)\.html""").find(url)?.groupValues?.get(1)

    private fun hasNextPage(document: Document): Boolean =
        document.select(".pagination a").any { it.text().trim() == "下一页" }

    /** Poster cards: the link wraps the background-image poster, <h2> title, score and year. */
    private fun cards(document: Document): List<SearchResponse> =
        document.select(".card a[href]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href")
        if (!Regex("""^/(movie|drama)/\d+\.html$""").matches(href)) return null // skips the site's ad card
        val title = selectFirst("h2")?.text()?.trim()?.ifBlank { null } ?: return null
        val poster = Regex("""url\(['"]?([^'")]+)""").find(selectFirst(".card-img")?.attr("style").orEmpty())
            ?.groupValues?.get(1)
        val score = selectFirst(".rank")?.text()?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }
        val year = select("li").map { it.text().trim() }.firstOrNull { Regex("""\d{4}""").matches(it) }?.toIntOrNull()
        val url = fixUrl(href)
        return if (href.startsWith("/drama/")) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
                this.score = score?.let { Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
                this.year = year
                this.score = score?.let { Score.from10(it) }
            }
        }
    }

    companion object {
        private const val TAG = "Cilixiong"
        private const val PREVIEW = "play"
        private const val MAGNET = "mag"
        private const val DEAD_HOST = "i.cilixiong.pro"
        private val ADULT_TAGS = setOf("情色", "伦理", "三级")
        private val TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://explodie.org:6969/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "https://tracker.opentrackr.org:443/announce",
        )
    }
}
