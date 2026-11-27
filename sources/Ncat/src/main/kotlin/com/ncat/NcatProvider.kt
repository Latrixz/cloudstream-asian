package com.ncat

import android.util.Log
import com.csasian.shared.CsAdStrip
import com.csasian.shared.CsBackup
import com.csasian.shared.CsQuality
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * 网飞猫 (www.ncat30.com), the "kekys" template:
 *  - every request first gets HTTP 850, a "cdndefend" proof-of-work page. The page holds a 40-hex
 *    challenge c; the answer is the first i where sha1(c + i) has bytes 0xB0 0x0B at offset
 *    hex(c[0]). "c + i" goes into the cookie cdndefend_js_cookie, which we keep and reuse.
 *  - home sections: .section-box with a .section-header-title and .module-item cards
 *  - lists:  /show/{type}-{genre}-{area}-{lang}-{year}-{sort}-{page}.html (18 per page, sort 2 = 最新)
 *  - search: /search?k={query}&page={n}&t={token}; the token is the hidden input "t" on every page
 *  - detail: /detail/{id}.html; servers in .source-item, episode lists in .episode-list (same order)
 *  - play:   /play/{id}-{sid}-{epid}.html holds `playSource = { src: "...m3u8?sign=..&timestamp=.." }`.
 *    The signature is made when the page is built and the page is cached for 30 minutes, so play
 *    pages are fetched fresh (cache-busting query) right before playing.
 * 超清2 is the site's own 1080p server; the others are common supply feeds with spliced-in ads.
 */
class NcatProvider : MainAPI() {
    override var mainUrl = "https://www.ncat30.com"
    override var name = "网飞猫 Ncat"
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

    private data class Channel(val id: Int, val label: String, val type: TvType)

    private val channels = listOf(
        Channel(1, "电影", TvType.Movie),
        Channel(2, "连续剧", TvType.TvSeries),
        Channel(3, "动漫", TvType.Anime),
        Channel(4, "综艺纪录", TvType.TvSeries),
        Channel(6, "短剧", TvType.AsianDrama),
    )

    /** Home page sections, in the site's order. null type = mixed, guessed from the card badge. */
    private val homeSections = listOf(
        "近期热门电影" to TvType.Movie,
        "近期热门剧集" to TvType.TvSeries,
        "最近更新" to null,
        "近期热播国产剧" to TvType.TvSeries,
        "近期热门日韩剧" to TvType.TvSeries,
        "近期热门欧美剧" to TvType.TvSeries,
        "近期热门港台剧" to TvType.TvSeries,
        "热播动漫" to TvType.Anime,
        "热播综艺纪录" to TvType.TvSeries,
    )

    override val mainPage = homeSections.map { MainPageData(it.first, "home|${it.first}") } +
        channels.map { MainPageData("最新${it.label}", "show|${it.id}") }

    // ---------------------------------------------------------------- cdndefend + page fetching

    @Volatile private var defendCookie: String? = null
    private val solveLock = Mutex()
    @Volatile private var searchToken: String? = null

    /** GETs a site page, solving the cdndefend challenge when it shows up (once for all callers). */
    private suspend fun fetchText(url: String, timeoutSeconds: Long = 20L): String {
        repeat(3) {
            val used = defendCookie
            val response = app.get(
                url,
                headers = pageHeaders,
                cookies = used?.let { mapOf(DEFEND_COOKIE to it) } ?: emptyMap(),
                timeout = timeoutSeconds,
            )
            val body = response.text
            if (response.code != 850 && !body.contains("Protected by cdndefend")) {
                Regex("""name="t"\s+value="([^"]+)"""").find(body)?.groupValues?.get(1)?.let { searchToken = it }
                return body
            }
            solveLock.withLock {
                // Another request may have solved it while we waited.
                if (defendCookie == used) {
                    val started = System.currentTimeMillis()
                    defendCookie = solveChallenge(body) ?: throw ErrorLoadingException("无法通过网站验证")
                    Log.i(TAG, "cdndefend solved in ${System.currentTimeMillis() - started} ms")
                }
            }
        }
        throw ErrorLoadingException("网站验证失败，请稍后再试")
    }

    private suspend fun fetchDocument(url: String): Document = Jsoup.parse(fetchText(url), url)

    /** sha1(challenge + i) must carry 0xB0 0x0B at the offset given by the challenge's first hex digit. */
    private suspend fun solveChallenge(page: String): String? = withContext(Dispatchers.Default) {
        val challenge = Regex("""'([0-9A-F]{40})'""").find(page)?.groupValues?.get(1)
            ?: return@withContext null
        val offset = challenge[0].digitToInt(16)
        val digest = MessageDigest.getInstance("SHA-1")
        val prefix = challenge.toByteArray(Charsets.US_ASCII)
        for (i in 0 until MAX_POW_TRIES) {
            digest.update(prefix)
            digest.update(i.toString().toByteArray(Charsets.US_ASCII))
            val hash = digest.digest()
            if (hash[offset] == 0xB0.toByte() && hash[offset + 1] == 0x0B.toByte()) return@withContext "$challenge$i"
        }
        null
    }

    private var homeCache: Pair<Long, Document>? = null
    private val homeLock = Mutex()

    /** The home page feeds nine rows; fetch it once for all of them. */
    private suspend fun homeDocument(): Document = homeLock.withLock {
        homeCache?.takeIf { System.currentTimeMillis() - it.first < HOME_CACHE_MS }?.second
            ?: fetchDocument("$mainUrl/").also { homeCache = System.currentTimeMillis() to it }
    }

    // ---------------------------------------------------------------- home + search

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        val (kind, key) = request.data.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (kind == "home") {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val type = homeSections.firstOrNull { it.first == key }?.second
            val section = homeDocument().select(".section-header-title")
                .firstOrNull { it.text().trim() == key }?.closest(".section-box")
            val items = section?.select(".module-item")?.mapNotNull { it.toCard(type) }?.distinctBy { it.url }.orEmpty()
            Log.i(TAG, "home row '$key': ${items.size} cards")
            return newHomePageResponse(request.name, items, false)
        }
        val channel = channels.firstOrNull { it.id.toString() == key } ?: channels[0]
        val document = fetchDocument("$mainUrl/show/${channel.id}-----2-$page.html")
        val items = document.select(".module-item").mapNotNull { it.toCard(channel.type) }.distinctBy { it.url }
        val hasNext = document.selectFirst("a.page-item-next") != null
        Log.i(TAG, "row '${request.name}' page $page: ${items.size} cards, next=$hasNext")
        return newHomePageResponse(request.name, items, items.isNotEmpty() && hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        val keyword = encode(query.trim())
        val pagePart = if (page > 1) "&page=$page" else ""
        // Without a valid token the site answers with an empty page: get a fresh one and try again.
        var html = ""
        for (attempt in 0..1) {
            val token = searchToken.takeIf { attempt == 0 }
                ?: run { fetchText("$mainUrl/"); searchToken }
                ?: break
            html = fetchText("$mainUrl/search?k=$keyword$pagePart&t=${encode(token)}")
            if (html.contains("search-result")) break
        }
        val document = Jsoup.parse(html, "$mainUrl/")
        val results = document.select("a.search-result-item[href*=/detail/]").mapNotNull { item ->
            val title = item.selectFirst(".title")?.text()?.trim()?.takeIf { it.isNotBlank() && !isDecoy(it) }
                ?: return@mapNotNull null
            val tags = item.select(".tags span").flatMap { it.text().split(',', '，') }.map { it.trim() }
            if (isAdult(title, tags)) return@mapNotNull null
            val header = item.selectFirst(".search-result-item-header")?.text()?.trim().orEmpty()
            val type = channels.firstOrNull { header.startsWith(it.label) }?.type
                ?: if (header == "剧集") TvType.TvSeries else TvType.Movie
            val poster = posterOf(item)
            if (isAdultPoster(poster)) return@mapNotNull null
            searchResult(title, fixUrl(item.attr("href")), poster, type, null, null)
        }.distinctBy { it.url }
        val hasNext = document.selectFirst("a.page-item-next") != null
        Log.i(TAG, "search '$query' page $page: ${results.size} results, next=$hasNext")
        return results to (results.isNotEmpty() && hasNext)
    }

    // ---------------------------------------------------------------- detail

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

    /** Strips the ad clips spliced into the supply feeds' playlists (超清2 has none and passes through). */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = CsAdStrip.interceptor

    private suspend fun loadPage(url: String): LoadResponse {
        val detailUrl = url.substringBefore('#')
        val document = fetchDocument(detailUrl)
        // The title block carries decoy "𝕜𝕜𝕪𝕤𝟘𝟙.𝕔𝕠𝕞" entries around the real one.
        val title = document.select(".detail-title strong").map { it.text().trim() }
            .firstOrNull { it.isNotBlank() && !isDecoy(it) }
            ?: throw ErrorLoadingException("找不到影片标题")
        val tagLinks = document.select(".detail-tags a.detail-tags-item")
        val channelId = tagLinks.firstNotNullOfOrNull {
            Regex("""/show/(\d+)-""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }
        val tagTexts = tagLinks.map { it.text().trim() }.filter { it.isNotBlank() }
        val year = tagTexts.firstOrNull { YEAR.matches(it) }?.toIntOrNull()
            ?: infoRow(document, "首映")?.text()?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
        val tags = tagTexts.filterNot { YEAR.matches(it) }.distinct()
        val poster = posterOf(document.selectFirst(".detail-pic"))
        if (isAdult(title, tags) || isAdultPoster(poster) || channelId == ADULT_CHANNEL) {
            throw ErrorLoadingException("不提供此类内容")
        }

        val names = { label: String, limit: Int ->
            infoRow(document, label)?.select("a")?.map { it.text().trim() }
                ?.filter { it.isNotBlank() && it != "内详" }?.distinct()?.take(limit).orEmpty()
        }
        val actors = (names("演员", 20).map { ActorData(Actor(it)) } +
            names("导演", 3).map { ActorData(Actor(it), roleString = "导演") }).ifEmpty { null }
        val status = infoRow(document, "备注")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val description = document.selectFirst(".detail-desc")?.text()?.trim()?.ifBlank { null }
        val plot = listOfNotNull(status?.let { "（$it）" }, description).joinToString("\n").ifBlank { null }
        val recommended = document.select(".section-header-title").firstOrNull { it.text().trim() == "好片推荐" }
            ?.closest(".section-box")?.select(".module-item")?.mapNotNull { it.toCard(null) }
            ?.filter { it.url != detailUrl }?.distinctBy { it.url }

        val servers = parseServers(document)
        Log.i(TAG, "detail '$title': ${servers.size} servers (${servers.joinToString { "${it.label}:${it.episodes.size}" }})")
        if (servers.isEmpty()) throw ErrorLoadingException("此影片仅在网飞猫 APP 内提供，暂无可用线路")
        val canonical = canonicalEpisodes(servers)
        val channelType = channels.firstOrNull { it.id == channelId }?.type
        val isMovie = canonical.size == 1 && (channelId == 1 || channelId == null)
        val type = when {
            isMovie -> TvType.Movie
            channelType == null || channelType == TvType.Movie -> TvType.TvSeries
            else -> channelType
        }

        return if (isMovie) {
            newMovieLoadResponse(title, detailUrl, type, episodeData(detailUrl, servers, canonical, 0, movie = true)) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.actors = actors
                this.recommendations = recommended
            }
        } else {
            val episodes = canonical.indices.map { position ->
                val entry = canonical[position]
                newEpisode(episodeData(detailUrl, servers, canonical, position, movie = false)) {
                    name = episodeName(entry.name, position)
                    episode = position + 1
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

    private fun infoRow(document: Document, label: String): Element? =
        document.select(".detail-info-row")
            .firstOrNull { it.selectFirst(".detail-info-row-side")?.text()?.trim()?.startsWith(label) == true }
            ?.selectFirst(".detail-info-row-main")

    private data class EpisodeEntry(val name: String, val sid: String, val epid: String)
    private data class Server(val label: String, val episodes: List<EpisodeEntry>)

    /** Usable servers in our preferred order (dead and app-only ones left out). */
    private fun parseServers(document: Document): List<Server> {
        val labels = document.select(".source-list-box-main .source-item").map {
            it.selectFirst(".source-item-label")?.text()?.trim().orEmpty()
        }
        return document.select(".episode-list-box-main .episode-list").mapIndexedNotNull { index, box ->
            val label = labels.getOrNull(index)?.ifBlank { null } ?: "线路${index + 1}"
            if (label in SKIPPED_SERVERS) return@mapIndexedNotNull null
            val episodes = box.select("a[href*=/play/]").mapNotNull { link ->
                val match = Regex("""/play/\d+-(\d+)-(\d+)\.html""").find(link.attr("href"))
                    ?: return@mapNotNull null
                EpisodeEntry(link.text().trim(), match.groupValues[1], match.groupValues[2])
            }.filterNot { it.name.contains("预告") }.distinctBy { episodeKey(it.name) }
            episodes.takeIf { it.isNotEmpty() }?.let { Server(label, it) }
        }.sortedBy { serverRank(it.label) }
    }

    /** The episode count most servers agree on (ties: the longer list), taken from the best-ranked such server. */
    private fun canonicalEpisodes(servers: List<Server>): List<EpisodeEntry> {
        val size = servers.groupingBy { it.episodes.size }.eachCount()
            .maxWithOrNull(compareBy<Map.Entry<Int, Int>>({ it.value }, { it.key }))!!.key
        return servers.first { it.episodes.size == size }.episodes
    }

    /**
     * data = {detailUrl}#ep={key}&s={label}~{sid}~{epid},... with every usable server's matching
     * episode, best first. Movies take each server's only entry.
     */
    private fun episodeData(
        detailUrl: String,
        servers: List<Server>,
        canonical: List<EpisodeEntry>,
        position: Int,
        movie: Boolean,
    ): String {
        val key = if (movie) "" else episodeKey(canonical[position].name)
        val parts = servers.mapNotNull { server ->
            val entry = if (movie) {
                server.episodes.first()
            } else {
                server.episodes.firstOrNull { episodeKey(it.name) == key }
                    ?: server.episodes.getOrNull(position)?.takeIf { server.episodes.size == canonical.size }
            }
            entry?.let { "${encode(server.label)}~${it.sid}~${it.epid}" }
        }
        return "$detailUrl#ep=${encode(key)}&s=${parts.joinToString(",")}"
    }

    // ---------------------------------------------------------------- links

    private data class Target(val label: String, val sid: String, val epid: String)

    private suspend fun loadOwnLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val detailUrl = data.substringBefore('#')
        val vodId = Regex("""/detail/(\d+)""").find(detailUrl)?.groupValues?.get(1) ?: return false
        val params = data.substringAfter('#', "").split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val targets = params["s"].orEmpty().split(',').mapNotNull { part ->
            val bits = part.split('~')
            if (bits.size != 3) null else Target(URLDecoder.decode(bits[0], "UTF-8"), bits[1], bits[2])
        }.filterNot { it.label in SKIPPED_SERVERS }.sortedBy { serverRank(it.label) }
        if (targets.isEmpty()) return false

        // A few servers at a time, best first; each link goes out as soon as it is found.
        val found = AtomicInteger(0)
        val permits = Semaphore(PARALLEL_SERVERS)
        coroutineScope {
            targets.take(MAX_SERVERS_TRIED).map { target ->
                async {
                    permits.withPermit {
                        if (found.get() >= MAX_LINKS) return@withPermit
                        val link = withTimeoutOrNull(SERVER_TIMEOUT_MS) { resolve(vodId, target) }
                        if (link == null) {
                            Log.i(TAG, "server ${target.label}: no link")
                        } else {
                            // Always send a link once it's resolved (the best server can finish last).
                            found.incrementAndGet()
                            Log.i(TAG, "server ${target.label}: ${link.url.take(80)}")
                            callback(link)
                        }
                    }
                }
            }.awaitAll()
        }
        Log.i(TAG, "links for $vodId ep='${params["ep"]}': ${found.get()} of ${targets.size} servers")
        return found.get() > 0
    }

    /** Reads `playSource.src` from a freshly built play page (the signed URL expires). */
    private suspend fun resolve(vodId: String, target: Target): ExtractorLink? = runCatching {
        val html = fetchText(
            "$mainUrl/play/$vodId-${target.sid}-${target.epid}.html?_=${System.currentTimeMillis()}",
            timeoutSeconds = 10L,
        )
        val block = html.substringAfter("playSource", "")
        val src = Regex("""src\s*:\s*"([^"]*)"""").find(block.take(600))?.groupValues?.get(1)
            ?.replace("\\/", "/")?.trim()
        if (src.isNullOrBlank() || !src.startsWith("http")) return null
        // The own server answers with a redirect to its edge; resolve it so relative segments are right.
        val url = if (target.label == OWN_SERVER) followRedirect(src) else src
        newExtractorLink(
            name,
            "$name ${target.label}",
            url,
            if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
        ) {
            referer = "$mainUrl/"
            quality = qualityOf(url, target.label)
            headers = mapOf("User-Agent" to userAgent)
        }
    }.getOrNull()

    private suspend fun followRedirect(url: String): String = runCatching {
        val response = app.get(url, headers = mapOf("User-Agent" to userAgent), allowRedirects = false, timeout = 6L)
        val location = response.headers["Location"]
        if (response.code in 300..399 && !location.isNullOrBlank()) URI(url).resolve(location).toString() else url
    }.getOrDefault(url)

    /** The own server names its rendition folder by width (/1920/index.m3u8, /1280/..., /640/...). */
    private fun qualityOf(url: String, label: String): Int {
        val width = Regex("""/(\d{3,4})/index\.m3u8""").find(url)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            width != null && width >= 3800 -> Qualities.P2160.value
            width != null && width >= 1900 -> Qualities.P1080.value
            width != null && width >= 1200 -> Qualities.P720.value
            // Narrower folders (640) are portrait short dramas: width says little about quality there.
            label == OWN_SERVER -> Qualities.P1080.value
            label == "WJ线路" -> Qualities.P720.value
            else -> Qualities.Unknown.value
        }
    }

    private fun serverRank(label: String): Int =
        SERVER_ORDER.indexOf(label).takeIf { it >= 0 } ?: SERVER_ORDER.size

    // ---------------------------------------------------------------- cards + helpers

    private fun Element.toCard(type: TvType?): SearchResponse? {
        val link = selectFirst("a[href*=/detail/]") ?: return null
        // Cards hide decoy titles with display:none.
        val title = select(".v-item-title").filterNot { it.attr("style").replace(" ", "").contains("display:none") }
            .map { it.text().trim() }.firstOrNull { it.isNotBlank() && !isDecoy(it) } ?: return null
        val badge = selectFirst(".v-item-bottom span")?.text()?.trim()
        val score = Regex("""(\d+(?:\.\d+)?)\s*分""").find(selectFirst(".v-item-top-left")?.text().orEmpty())
            ?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0 }
        val resolvedType = type ?: if (badge != null && SERIES_BADGE.containsMatchIn(badge)) TvType.TvSeries else TvType.Movie
        val poster = posterOf(this)
        if (isAdultPoster(poster)) return null
        return searchResult(title, fixUrl(link.attr("href")), poster, resolvedType, badge, score)
    }

    private fun searchResult(
        title: String,
        href: String,
        poster: String?,
        type: TvType,
        badge: String?,
        score: Double?,
    ): SearchResponse =
        if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, type) {
                posterUrl = poster
                quality = CsQuality.from(badge)
                this.score = score?.let { Score.from10(it) }
            }
        } else {
            newTvSeriesSearchResponse(title, href, type) {
                posterUrl = poster
                quality = CsQuality.from(badge)
                this.score = score?.let { Score.from10(it) }
            }
        }

    /** Lazy images: data-original is a path on the image host; the placeholder and logo images are skipped. */
    private fun posterOf(root: Element?): String? {
        root ?: return null
        return root.select("img").asSequence()
            .filter { it.id() != "noneCoverImg" }
            .flatMap { sequenceOf(it.attr("data-original"), it.attr("src")) }
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") && !it.contains("placeholder") }
            ?.let {
                when {
                    it.startsWith("//") -> "https:$it"
                    it.startsWith("/") -> "$IMAGE_HOST$it"
                    else -> it
                }
            }
    }

    private fun isDecoy(text: String): Boolean =
        text.codePoints().anyMatch { it > 0xFFFF } || text.contains("kekys", true) || text.contains("kkys", true)

    private fun isAdult(title: String, tags: List<String>): Boolean =
        tags.any { it in ADULT_TAGS } || title.contains("三级片")

    /** The site keeps its adult channel (5, not linked from the menus) under /vod1/channel5/. */
    private fun isAdultPoster(poster: String?): Boolean = poster?.contains("/channel$ADULT_CHANNEL/") == true

    private fun episodeKey(name: String): String {
        val trimmed = name.trim()
        return Regex("""^第?\s*0*(\d+)\s*[集话話期]?$""").find(trimmed)?.groupValues?.get(1) ?: trimmed
    }

    private fun episodeName(raw: String, position: Int): String {
        val key = episodeKey(raw)
        return when {
            key.isBlank() -> "第 ${position + 1} 集"
            key.all { it.isDigit() } -> "第 $key 集"
            else -> raw.trim()
        }
    }

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    companion object {
        private const val TAG = "Ncat"
        private const val DEFEND_COOKIE = "cdndefend_js_cookie"
        private const val IMAGE_HOST = "https://vres.jzxjlj.com"
        private const val OWN_SERVER = "超清2"
        private const val ADULT_CHANNEL = 5
        private const val MAX_POW_TRIES = 20_000_000
        private const val HOME_CACHE_MS = 5 * 60 * 1000L
        private const val PARALLEL_SERVERS = 3
        private const val MAX_SERVERS_TRIED = 7
        private const val MAX_LINKS = 5
        private const val SERVER_TIMEOUT_MS = 12_000L

        /** Best first: the own 1080p server, then the feeds by speed/quality seen on the site. */
        private val SERVER_ORDER = listOf(
            "超清2", "FF线路", "蓝光3", "蓝光", "IK线路", "蓝光9", "HN线路", "蓝光4", "JY线路", "WJ线路",
        )

        /** "4K" only plays in the site's app; the rest answered 444 / did not resolve when tested. */
        private val SKIPPED_SERVERS = setOf("4K", "XL线路", "SB线路", "GS线路", "蓝光7", "DB线路")

        private val ADULT_TAGS = setOf("情色", "伦理", "伦理片", "三级", "三级片", "成人", "福利")
        private val YEAR = Regex("""^(19|20)\d{2}$""")
        private val SERIES_BADGE = Regex("""集|期|完结|更新""")
    }
}
