package com.netflixgc

import android.util.Log
import com.csasian.shared.CsAdStrip
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections

/**
 * 奈飞工厂 (www.netflixgc.com): MacCMS v10 with the DS "dsn2" template.
 *  - lists:   POST /index.php/ds_api/vod  type=N&by=time|hits&page=N  -> JSON (40 per page)
 *  - search:  /vodsearch/{wd}-------------.html, page n: /vodsearch/{wd}----------{n}---.html
 *  - detail:  /voddetail/{id}.html  (server tabs in .anthology-tab, lists in .anthology-list-box)
 *  - player:  /vodplay/{id}-{sid}-{nid}.html embeds `player_aaaa` (encrypt 2 = base64 + unescape).
 * Servers: the site's own "NBY" (蓝光-1) gives an opaque id that mplayer.nfgc.net turns into either a
 * one-shot HLS playlist whose segments hide behind a fake PNG header (movies) or a signed 540p MP4
 * (episodes). The other servers are the usual supply feeds (ffzy, bfzy, yzzy, lzzy, dytt, wsy, ...),
 * which splice ads into their playlists: shared/CsAdStrip.kt removes them while playing.
 * Type 57 (直播, live sports and its sub-types) is never shown.
 */
class NetflixgcProvider : MainAPI() {
    override var mainUrl = "https://www.netflixgc.com"
    override var name = "奈飞工厂 Netflixgc"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Documentary)

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
    private val pageHeaders get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
    private val json = ObjectMapper()

    private data class Category(val id: Int, val label: String, val type: TvType)

    private val categories = listOf(
        Category(1, "电影", TvType.Movie),
        Category(2, "连续剧", TvType.TvSeries),
        Category(3, "漫剧", TvType.Anime),
        Category(23, "综艺", TvType.TvSeries),
        Category(24, "纪录片", TvType.Documentary),
    )

    /** Home page sections worth a row (the rest are topics, news or empty). */
    private val homeRows = listOf("热播榜", "近期开播新剧", "最新热播电影", "热播新番")

    override val mainPage = homeRows.map { MainPageData(it, "$HOME_PREFIX$it") } +
        categories.flatMap { c ->
            listOf(
                MainPageData("最新${c.label}", "${c.id}|time"),
                MainPageData("热门${c.label}", "${c.id}|hits"),
            )
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.startsWith(HOME_PREFIX)) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val section = request.data.removePrefix(HOME_PREFIX)
            return newHomePageResponse(request.name, homeSection(section), false)
        }
        val typeId = request.data.substringBefore('|').toInt()
        val order = request.data.substringAfter('|', "time")
        val type = categories.first { it.id == typeId }.type
        val root = listing(typeId, order, page) ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = root.path("list").mapNotNull { it.toApiResult(type) }.distinctBy { it.url }
        val hasNext = page < root.path("pagecount").asInt(page)
        return newHomePageResponse(request.name, items, items.isNotEmpty() && hasNext)
    }

    private suspend fun listing(typeId: Int, order: String, page: Int): JsonNode? = runCatching {
        val form = mapOf(
            "type" to typeId.toString(), "class" to "", "area" to "", "lang" to "", "year" to "",
            "state" to "", "letter" to "", "level" to "", "by" to order, "page" to page.toString(),
        )
        var text = ""
        for (attempt in 0..2) {
            val response = app.post("$mainUrl/index.php/ds_api/vod", headers = pageHeaders + mapOf(
                "X-Requested-With" to "XMLHttpRequest",
            ), data = form)
            text = response.text
            if (response.code != 429 && text.trimStart().startsWith("{\"code\":1")) break
            delay(1200L * (attempt + 1))
        }
        json.readTree(text).takeIf { it.path("list").isArray }
    }.onFailure { Log.w(TAG, "listing $typeId/$order/$page failed: ${it.message}") }.getOrNull()

    private fun JsonNode.toApiResult(type: TvType): SearchResponse? {
        val id = path("vod_id").asText("").ifBlank { return null }
        val title = path("vod_name").asText("").trim().ifBlank { return null }
        val tag = Regex("<[^>]+>").replace(path("vod_tag").asText(""), " ").trim()
        if (isBlocked(listOf(tag))) return null
        val remark = path("vod_remarks").asText("").trim()
        return searchResult(title, "$mainUrl/voddetail/$id.html", unwrapImage(path("vod_pic").asText("")), type, remark)
    }

    // The home page is shared by several rows: fetch it once a minute at most.
    private val homeLock = Mutex()
    private var homeCache: Pair<Long, Document>? = null

    private suspend fun homeSection(section: String): List<SearchResponse> {
        val document = homeLock.withLock {
            homeCache?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.second
                ?: getPage("$mainUrl/").also { homeCache = System.currentTimeMillis() to it }
        }
        val heading = document.select("h4.title-h").firstOrNull { it.text().trim().startsWith(section) }
            ?: return emptyList()
        val block = heading.parents().firstOrNull { it.hasClass("title") }?.nextElementSibling() ?: return emptyList()
        val type = if (section.contains("电影")) TvType.Movie else null
        return block.select(".public-list-box").mapNotNull { it.toCardResult(type) }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        val wd = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val url = if (page <= 1) "$mainUrl/vodsearch/$wd-------------.html"
        else "$mainUrl/vodsearch/$wd----------$page---.html"
        val document = getPage(url)
        val results = document.select(".search-list").mapNotNull { box ->
            val link = box.selectFirst("a[href*=/voddetail/]") ?: return@mapNotNull null
            val title = (box.selectFirst(".slide-info-title")?.text() ?: link.text()).trim()
                .ifBlank { return@mapNotNull null }
            val genres = infoNames(box, "类型")
            val remark = box.selectFirst(".slide-info-remarks.cor5")?.text()?.trim().orEmpty()
            if (isBlocked(genres + remark)) return@mapNotNull null
            searchResult(title, fixUrl(link.attr("href")), imageOf(box.selectFirst(".detail-pic img")), guessType(remark), remark)
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

    /**
     * NBY playlists get the PNG-header stripper for their segments; every other link (the ad-spliced
     * supply feeds) gets the shared playlist ad remover.
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor =
        if (extractorLink.url in nbyPlaylists || extractorLink.url.contains("nbyjson")) pngStripper
        else CsAdStrip.interceptor

    private suspend fun loadPage(url: String): LoadResponse {
        val detailUrl = url.substringBefore('#')
        val document = getPage(detailUrl)
        val navType = navTypeId(document)
        if (navType == LIVE_TYPE) throw ErrorLoadingException("不支持直播内容")

        val title = (document.selectFirst(".slide-info-title")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")).orEmpty().trim()
            .ifBlank { throw ErrorLoadingException("找不到影片标题") }
        val genres = infoNames(document, "类型")
        if (isBlocked(genres)) throw ErrorLoadingException("不支持此类内容")

        val poster = imageOf(document.selectFirst(".detail-pic img"))
        val remarkLinks = document.select(".slide-info-remarks a")
        val year = remarkLinks.firstNotNullOfOrNull { Regex("""^(19|20)\d{2}$""").find(it.text().trim())?.value?.toInt() }
        val areas = remarkLinks.map { it.text().trim() }.filter { it.isNotBlank() && !it.all(Char::isDigit) }
        val status = document.selectFirst(".slide-info-remarks.cor5")?.text()?.trim()?.ifBlank { null }
        val description = document.selectFirst("#height_limit")?.text()?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val plotText = listOfNotNull(status?.let { "（$it）" }, description).joinToString("\n").ifBlank { null }
        val actors = (infoNames(document, "演员").take(20).map { ActorData(Actor(it)) } +
            infoNames(document, "导演").take(3).map { ActorData(Actor(it), roleString = "导演") }).ifEmpty { null }
        val score = document.selectFirst(".play-score .fraction")?.text()?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }
        val tags = (genres + areas).distinct()

        val sources = parseSources(document)
        if (sources.isEmpty()) throw ErrorLoadingException("此影片暂无播放来源")
        val episodes = canonicalEpisodes(sources)
        val categoryType = categories.firstOrNull { it.id == navType }?.type
        val isMovie = episodes.size == 1 && (navType == 1 || navType == 24 || navType == null)
        val type = when {
            isMovie -> if (categoryType == TvType.Documentary) TvType.Documentary else TvType.Movie
            categoryType == null || categoryType == TvType.Movie -> TvType.TvSeries
            else -> categoryType
        }
        val recommended = recommendations(document, detailUrl)

        return if (isMovie) {
            newMovieLoadResponse(title, detailUrl, type, episodeData(detailUrl, episodes[0], movie = true)) {
                posterUrl = poster
                plot = plotText
                this.year = year
                this.tags = tags
                this.actors = actors
                this.score = score?.let { Score.from10(it) }
                recommendations = recommended
            }
        } else {
            val list = episodes.mapIndexed { index, entry ->
                newEpisode(episodeData(detailUrl, entry, movie = false)) {
                    name = episodeTitle(entry.name, index)
                    episode = index + 1
                }
            }
            newTvSeriesLoadResponse(title, detailUrl, type, list) {
                posterUrl = poster
                plot = plotText
                this.year = year
                this.tags = tags
                this.actors = actors
                this.score = score?.let { Score.from10(it) }
                recommendations = recommended
            }
        }
    }

    private suspend fun loadOwnLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data = https://www.netflixgc.com/voddetail/{id}.html#ep={normalized name}&nid={n} (ep empty for movies)
        val detailUrl = data.substringBefore('#')
        val params = data.substringAfter('#', "").split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val wanted = URLDecoder.decode(params["ep"].orEmpty(), "UTF-8")
        val wantedNid = params["nid"]?.toIntOrNull()

        val sources = parseSources(getPage(detailUrl))
        val targets = sources.mapNotNull { source ->
            val entry = if (wanted.isBlank()) source.episodes.firstOrNull()
            else source.episodes.firstOrNull { normalizeEpisode(it.name) == wanted }
                ?: source.episodes.firstOrNull { it.nid == wantedNid && !it.name.contains("全集") }
            entry?.let { source.label to it }
        }.sortedBy { (label, _) -> if (PLAYER_NAMES.entries.firstOrNull { it.value == label }?.key in OWN_SERVERS) 0 else 1 }
        Log.i(TAG, "links for $detailUrl ep='$wanted' nid=$wantedNid: ${targets.size} servers")
        if (targets.isEmpty()) return false

        val permits = Semaphore(MAX_PARALLEL)
        val seen = Collections.synchronizedSet(mutableSetOf<String>())
        val found = coroutineScope {
            targets.map { (label, entry) ->
                async {
                    val stream = permits.withPermit { withTimeoutOrNull(SERVER_TIMEOUT_MS) { resolve(entry) } }
                    if (stream == null) {
                        Log.i(TAG, "server '$label': nothing")
                        return@async false
                    }
                    if (!seen.add(stream.url)) return@async false
                    val display = PLAYER_NAMES[stream.from] ?: label
                    Log.i(TAG, "server '$display' (${stream.from}): ${stream.kind} ${stream.url.take(120)}")
                    callback(stream.toLink(display))
                    true
                }
            }.awaitAll().count { it }
        }
        Log.i(TAG, "links for $detailUrl: $found of ${targets.size} servers")
        return found > 0
    }

    private enum class Kind { NBY_HLS, NBY_MP4, FEED }
    private data class Stream(val url: String, val from: String, val kind: Kind)

    private suspend fun Stream.toLink(label: String): ExtractorLink {
        val hls = kind != Kind.NBY_MP4 && (kind == Kind.NBY_HLS || url.contains(".m3u8", true))
        return newExtractorLink(name, "$name $label", url, if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
            // Ranking: the site's own clean 1080p HLS, then the ad-spliced 1080p feeds, then the own
            // 540p HEVC episode MP4s. Feeds are 1080p too; they're given 720 only so they sort below.
            quality = when (kind) {
                Kind.NBY_HLS -> Qualities.P1080.value
                Kind.FEED -> Qualities.P720.value
                Kind.NBY_MP4 -> 540
            }
            if (kind == Kind.FEED) {
                referer = "$mainUrl/"
                headers = mapOf("User-Agent" to userAgent)
            } else {
                referer = "$MPLAYER/"
                headers = mapOf("User-Agent" to userAgent)
            }
        }
    }

    /** Reads `player_aaaa` from the play page and turns it into a playable stream. */
    private suspend fun resolve(entry: EpisodeEntry): Stream? = runCatching {
        val html = app.get(
            "$mainUrl/vodplay/${entry.vodId}-${entry.sid}-${entry.nid}.html",
            headers = pageHeaders, timeout = 10L,
        ).text
        val start = html.indexOf("player_aaaa")
        if (start < 0) return null
        val config = jsonObjectAt(html, html.indexOf('=', start) + 1)?.let { json.readTree(it) } ?: return null
        val from = config.path("from").asText("").trim()
        if (from in SKIPPED_SERVERS) return null
        val raw = config.path("url").asText("").trim().ifBlank { return null }
        val url = when (config.path("encrypt").asInt(0)) {
            1 -> jsUnescape(raw)
            2 -> jsUnescape(String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8))
            else -> raw
        }.trim()
        when {
            url.startsWith("http") -> Stream(encodeNonAscii(url), from, Kind.FEED)
            url.isNotBlank() -> mplayer(url, from)
            else -> null
        }
    }.onFailure { Log.w(TAG, "resolve ${entry.vodId}-${entry.sid}-${entry.nid}: ${it.message}") }.getOrNull()

    /**
     * The site's own servers: mplayer.nfgc.net hands out a time + vkey pair on its page, and its JSON
     * endpoint trades them for the stream. HLS answers are one-shot playlists: never fetched here.
     */
    private suspend fun mplayer(id: String, from: String): Stream? {
        val pageUrl = "$MPLAYER/?url=${URLEncoder.encode(id, "UTF-8")}"
        val page = app.get(pageUrl, headers = pageHeaders, timeout = 8L).text
        val time = Regex(""""time"\s*:\s*"(\d+)"""").find(page)?.groupValues?.get(1) ?: return null
        val vkey = Regex(""""vkey"\s*:\s*"([^"]*)"""").find(page)?.groupValues?.get(1) ?: return null
        val answer = app.post(
            "$MPLAYER/admin/mizhi_json.php",
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to pageUrl,
                "Origin" to MPLAYER,
                "X-Requested-With" to "XMLHttpRequest",
            ),
            data = mapOf("url" to id, "time" to time, "key" to "", "vkey" to vkey),
            timeout = 8L,
        ).text
        val root = json.readTree(answer)
        val url = root.path("url").asText("").trim()
        if (!url.startsWith("http")) {
            Log.i(TAG, "mplayer ($from): no url: ${answer.take(160)}")
            return null
        }
        val hls = root.path("is_hls").asBoolean(false) || url.contains("m3u8", true) ||
            root.path("type").asText("").contains("m3u8", true)
        if (hls) nbyPlaylists.add(url)
        return Stream(url, from, if (hls) Kind.NBY_HLS else Kind.NBY_MP4)
    }

    private val nbyPlaylists: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /**
     * NBY segments (*.xhscdn.com, also behind the play3 redirects) are a small image (a 112-byte PNG
     * for movies, a JPEG for some series) followed by the MPEG-TS data, served as image/png. Drops
     * everything before the first spot where TS sync bytes repeat every 188 bytes.
     */
    private val pngStripper = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return@Interceptor response
        val head = runCatching { response.peekBody(8).bytes() }.getOrNull() ?: return@Interceptor response
        val disguised = IMAGE_MAGICS.any { magic -> head.size >= magic.size && magic.indices.all { head[it] == magic[it] } } ||
            (head.isNotEmpty() && head[0] != SYNC &&
                response.header("Content-Type").orEmpty().startsWith("image/", ignoreCase = true))
        if (!disguised) return@Interceptor response
        val bytes = response.body.bytes()
        val offset = tsStart(bytes)
        val body = if (offset == null) bytes else bytes.copyOfRange(offset, bytes.size)
        val type = if (offset == null) response.header("Content-Type") ?: "image/png" else "video/mp2t"
        response.newBuilder()
            .removeHeader("Content-Length")
            .removeHeader("Content-Range")
            .removeHeader("Content-Encoding")
            .header("Content-Type", type)
            .body(body.toResponseBody(type.toMediaTypeOrNull()))
            .build()
    }

    private fun tsStart(bytes: ByteArray): Int? {
        val last = minOf(SCAN_LIMIT, bytes.size - 2 * TS_PACKET - 1)
        var i = 0
        while (i <= last) {
            if (bytes[i] == SYNC && bytes[i + TS_PACKET] == SYNC && bytes[i + 2 * TS_PACKET] == SYNC) return i
            i++
        }
        return null
    }

    // ---- Parsing helpers ----

    private data class EpisodeEntry(val name: String, val vodId: String, val sid: Int, val nid: Int)
    private data class Source(val label: String, val episodes: List<EpisodeEntry>)

    private fun parseSources(document: Document): List<Source> {
        val labels = document.select(".anthology-tab a.swiper-slide").map {
            it.ownText().replace(' ', ' ').trim()
        }
        return document.select(".anthology-list .anthology-list-box").mapIndexedNotNull { index, box ->
            val label = labels.getOrNull(index)?.ifBlank { null } ?: "线路${index + 1}"
            if (label.contains("APP", true) || label.contains("独享")) return@mapIndexedNotNull null
            val episodes = box.select("a[href*=/vodplay/]").mapNotNull { a ->
                val (vodId, sid, nid) = Regex("""/vodplay/(\d+)-(\d+)-(\d+)\.html""").find(a.attr("href"))
                    ?.destructured ?: return@mapNotNull null
                EpisodeEntry(a.text().trim(), vodId, sid.toInt(), nid.toInt())
            }.distinctBy { normalizeEpisode(it.name) }
            if (episodes.isEmpty()) null else Source(label, episodes)
        }
    }

    /** The episode count most servers agree on (ties: the longer list); one-file "全集" servers don't vote. */
    private fun canonicalEpisodes(sources: List<Source>): List<EpisodeEntry> {
        val voters = sources.filterNot { it.episodes.size == 1 && it.episodes[0].name.contains("全集") }.ifEmpty { sources }
        val votes = voters.groupBy { it.episodes.size }
        val size = votes.keys.maxWith(compareBy<Int>({ votes.getValue(it).size }, { it }))
        return votes.getValue(size).first().episodes
    }

    private fun episodeData(detailUrl: String, entry: EpisodeEntry, movie: Boolean): String {
        // Movie servers name their single file differently (1080P / 4K / HD中字): match movies by position.
        val ep = if (movie) "" else URLEncoder.encode(normalizeEpisode(entry.name), "UTF-8")
        return "$detailUrl#ep=$ep&nid=${entry.nid}"
    }

    /** "第01集" / "01" / "1" -> "1"; anything else stays as it is. */
    private fun normalizeEpisode(name: String): String {
        val text = name.trim()
        return Regex("""^第?\s*0*(\d+)\s*[集话話期]?$""").find(text)?.groupValues?.get(1) ?: text
    }

    private fun episodeTitle(raw: String, index: Int): String {
        val n = normalizeEpisode(raw)
        return when {
            n.isBlank() -> "第 ${index + 1} 集"
            n.all(Char::isDigit) -> "第 $n 集"
            else -> raw.trim()
        }
    }

    /** The header nav marks the title's top-level category: /vodshow/{id}-----------.html or /vodtype/{id}.html. */
    private fun navTypeId(document: Document): Int? = document.select("a.current[href]").firstNotNullOfOrNull {
        Regex("""^/vod(?:show|type)/(\d+)[-.]""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Names after a "导演 :" / "演员 :" / "类型 :" label. */
    private fun infoNames(root: Element, label: String): List<String> =
        root.select(".slide-info").firstOrNull { it.selectFirst("strong")?.text()?.contains(label) == true }
            ?.select("a")?.map { it.text().trim() }?.filter { it.isNotBlank() && it != "内详" }?.distinct().orEmpty()

    private fun recommendations(document: Document, detailUrl: String): List<SearchResponse> = runCatching {
        val heading = document.select("h4.title-h").firstOrNull { it.text().trim() == "相关作品" } ?: return@runCatching emptyList()
        val block = heading.parents().firstOrNull { it.hasClass("title") }?.nextElementSibling() ?: return@runCatching emptyList()
        block.select(".public-list-box").mapNotNull { it.toCardResult(null) }
            .filter { it.url != detailUrl }.distinctBy { it.url }.take(16)
    }.getOrDefault(emptyList())

    /** A poster card. Live/sports cards link straight to /vodplay/ and are skipped with the rest. */
    private fun Element.toCardResult(type: TvType?): SearchResponse? {
        val link = selectFirst("a.public-list-exp[href*=/voddetail/]") ?: return null
        val title = link.attr("title").trim().ifBlank { selectFirst(".time-title")?.text()?.trim().orEmpty() }
            .ifBlank { return null }
        val tag = selectFirst(".public-prt")?.text()?.trim().orEmpty()
        val remark = selectFirst(".public-list-prb")?.text()?.trim().orEmpty()
        if (isBlocked(listOf(tag, remark))) return null
        return searchResult(title, fixUrl(link.attr("href")), imageOf(link.selectFirst("img")), type ?: guessType(remark), remark)
    }

    private fun searchResult(title: String, url: String, poster: String?, type: TvType, remark: String): SearchResponse {
        val badge = CsQuality.from(remark)
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, url, type) { posterUrl = poster; quality = badge }
        } else {
            newTvSeriesSearchResponse(title, url, type) { posterUrl = poster; quality = badge }
        }
    }

    /** Adult classes and live sports (type 57's 足球/篮球/... classes) never show up. */
    private fun isBlocked(labels: List<String>): Boolean = labels.any { label ->
        BLOCKED_WORDS.any { label.contains(it, ignoreCase = true) }
    }

    private fun guessType(remark: String): TvType =
        if (Regex("""正片|HD|高清|抢先|TC|TS|蓝光|中字|4K|1080""", RegexOption.IGNORE_CASE).containsMatchIn(remark) &&
            !Regex("""更新|集|期""").containsMatchIn(remark)
        ) TvType.Movie else TvType.TvSeries

    private fun imageOf(img: Element?): String? {
        img ?: return null
        val src = listOf(img.attr("data-src"), img.attr("data-original"), img.attr("src"))
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") } ?: return null
        return unwrapImage(fixUrl(src))
    }

    /** Posters are wrapped as https://image.baidu.com/search/down?url=<real url>: use the real one. */
    private fun unwrapImage(url: String): String? {
        val trimmed = url.trim().ifBlank { return null }
        if (!trimmed.contains("image.baidu.com/search/down")) return trimmed
        val inner = trimmed.substringAfter("url=", "").ifBlank { return trimmed }
        return if (inner.startsWith("http%3A", true) || inner.startsWith("https%3A", true)) {
            URLDecoder.decode(inner, "UTF-8")
        } else inner
    }

    private fun lastPage(document: Document, page: Int): Int =
        Regex("""当前\s*\d+\s*/\s*(\d+)\s*页""").find(document.select(".page-tip").text())
            ?.groupValues?.get(1)?.toIntOrNull() ?: page

    private suspend fun getPage(url: String): Document {
        repeat(2) { attempt ->
            val response = app.get(url, headers = pageHeaders)
            if (response.code != 429 && response.code < 500) return response.document
            delay(1500L * (attempt + 1))
        }
        return app.get(url, headers = pageHeaders).document
    }

    /** JavaScript unescape(): %XX and %uXXXX. */
    private fun jsUnescape(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '%') {
                val unicode = if (i + 5 < text.length && (text[i + 1] == 'u' || text[i + 1] == 'U'))
                    text.substring(i + 2, i + 6).toIntOrNull(16) else null
                if (unicode != null) {
                    out.append(unicode.toChar()); i += 6; continue
                }
                val byte = if (i + 2 < text.length) text.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (byte != null) {
                    out.append(byte.toChar()); i += 3; continue
                }
            }
            out.append(text[i])
            i++
        }
        return out.toString()
    }

    /** bfzy paths carry Chinese episode names (/第01集/index.m3u8): send them percent-encoded. */
    private fun encodeNonAscii(url: String): String {
        if (url.all { it.code in 0x21..0x7e }) return url
        val out = StringBuilder()
        url.forEach { c ->
            if (c.code in 0x21..0x7e) out.append(c)
            else c.toString().toByteArray(Charsets.UTF_8).forEach { out.append("%%%02X".format(it.toInt() and 0xff)) }
        }
        return out.toString()
    }

    /** The JSON object starting at [from] (skipping a little whitespace), aware of strings and escapes. */
    private fun jsonObjectAt(text: String, from: Int): String? {
        val begin = text.indexOf('{', from).takeIf { it >= 0 && it - from < 5 } ?: return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in begin until text.length) {
            val c = text[i]
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
                continue
            }
            when (c) {
                '"' -> quoted = true
                '{', '[' -> depth++
                '}', ']' -> if (--depth == 0) return text.substring(begin, i + 1)
            }
        }
        return null
    }

    companion object {
        private const val TAG = "Netflixgc"
        private const val HOME_PREFIX = "home|"
        private const val LIVE_TYPE = 57
        private const val MPLAYER = "https://mplayer.nfgc.net"
        private const val MAX_PARALLEL = 3
        private const val SERVER_TIMEOUT_MS = 12_000L
        private const val TS_PACKET = 188
        private const val SCAN_LIMIT = 65_536
        private const val SYNC: Byte = 0x47
        private val IMAGE_MAGICS = listOf(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), // PNG
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), // JPEG
            byteArrayOf(0x47, 0x49, 0x46, 0x38), // GIF8 (starts with 'G' = 0x47, but no 188-byte rhythm)
            byteArrayOf(0x42, 0x4D), // BMP
        )

        private val BLOCKED_WORDS = listOf(
            "伦理", "情色", "福利", "写真", "三级",
            "直播", "足球", "篮球", "体育", "赛事",
        )

        /** The site's own servers (resolved through mplayer.nfgc.net); tried first. */
        private val OWN_SERVERS = setOf("NBY", "ntflixzx")

        /** App-only servers (cjbfq.netflixgc.tv) and the live-TV player. */
        private val SKIPPED_SERVERS = setOf("zxskplayer", "zxlgplayer", "zb")

        /** Server names from the site's playerconfig.js (`from` -> tab name). */
        private val PLAYER_NAMES = linkedMapOf(
            "NBY" to "蓝光-1",
            "ntflixzx" to "蓝光-2",
            "bfzym3u8" to "蓝光-3",
            "wsym3u8" to "蓝光-4",
            "ffm3u8" to "蓝光-5",
            "dm295" to "蓝光-6",
            "anfun2" to "蓝光-8",
            "1080zyk" to "1080P-2",
            "lzm3u8" to "1080P-3",
            "dyttm3u8" to "1080P-4",
            "xiguam3u8" to "蓝光-5",
        )
    }
}
