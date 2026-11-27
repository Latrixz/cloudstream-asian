package com.kpkuang

import android.util.Base64
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 看片狂人 (www.kpkuang.us) runs MacCMS with the "vfed" template behind Cloudflare.
 *  - lists:   /vodtype/{id}/ and /label/news.html are open; each is a single page of cards (no paging:
 *             the /vodshow/ filter pages sit behind a Cloudflare challenge).
 *  - search:  the site's own instant-search API, kpdata.flixfiend.top/esearch/index?kw= (JSONP whose
 *             "js" field is base64 JSON); the /vodsearch/ page is behind the Cloudflare challenge too.
 *  - detail:  /voddetail/{id}/ ; servers are `li.fed-play-item` (label in the "来自 X 的播放列表" header),
 *             episode links /vodplay/{id}-{sid}-{nid}.html ; `a.line-select` maps a label to its line id.
 *  - player:  /vodplay/... has data-play="xxx" + base64(stream url) and data-trys (trial minutes for
 *             paid content; we only take free lines with data-trys=0).
 * Lines marked "VIP解析" re-play paid platforms (Tencent/iQiyi/Youku/Bilibili) and the "超清XX线" lines are
 * third-party embed hosts: neither is used. Type 26 ("考驾照") is the site's adult category and is excluded.
 * Most supply networks splice ad clips into their playlists; shared/CsAdStrip.kt removes what it can and
 * every playlist is checked before it is offered, so feeds that still carry ads are ranked last.
 */
class KpkuangProvider : MainAPI() {
    override var mainUrl = "https://www.kpkuang.us"
    override var name = "看片狂人 Kpkuang"
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

    /** Row data: "{path}|{section heading}" — the cards under that heading on that page ("*": every section). */
    override val mainPage = mainPageOf(
        "/label/news.html|最近更新" to "最近更新",
        // The movie and series pages show 12 new titles overall, then the 12 newest of each genre.
        "/vodtype/1/|*" to "电影",
        "/vodtype/2/|*" to "剧集",
        "/vodtype/13/|国产剧" to "国产剧",
        "/vodtype/23/|韩剧" to "韩剧",
        "/vodtype/15/|日剧" to "日剧",
        "/vodtype/16/|欧美剧" to "欧美剧",
        "/vodtype/14/|港剧" to "港剧",
        "/vodtype/3/|综艺" to "综艺",
        "/vodtype/4/|动漫" to "动漫",
        "/vodtype/37/|最新视频" to "短剧",
    )

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        // Every list is a single page on this site.
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        val path = request.data.substringBefore('|')
        val heading = request.data.substringAfter('|', "")
        val document = getPage("$mainUrl$path")
        val section = if (heading == "*") document else document.select(".fed-part-layout").firstOrNull { layout ->
            layout.selectFirst(".fed-list-head h2")?.text()?.trim() == heading
        } ?: document
        val fallbackType = typeForPath(path)
        val items = section.select("li.fed-list-item").mapNotNull { it.toCard(fallbackType) }.distinctBy { it.url }
        Log.i(TAG, "row '${request.name}' ($path): ${items.size} cards")
        return newHomePageResponse(request.name, items, false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        // The API answers "too short" below 2 Chinese characters / 5 other characters.
        val chinese = keyword.count { it in '一'..'龥' }
        if (chinese < 2 && keyword.length < 5) return emptyList()
        val encoded = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        val body = app.get(
            "$SEARCH_API?kw=$encoded&ts=${System.currentTimeMillis()}&callback=cb",
            headers = pageHeaders,
        ).text
        val json = body.substringAfter('(', "").substringBeforeLast(')', "").ifBlank { body }
        val root = runCatching { jsonMapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (root.path("code").asInt(0) != 1) {
            Log.i(TAG, "search '$keyword': no results (code=${root.path("code").asText()})")
            return emptyList()
        }
        val decoded = runCatching {
            String(Base64.decode(root.path("js").asText(""), Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: return emptyList()
        val list = runCatching { jsonMapper.readTree(decoded) }.getOrNull() ?: return emptyList()
        val results = list.mapNotNull { hit -> hit.toSearchResult() }.distinctBy { it.url }
        Log.i(TAG, "search '$keyword': ${results.size}/${list.size()} results")
        return results
    }

    private fun JsonNode.toSearchResult(): SearchResponse? {
        val id = path("id").asText("").takeIf { it.all(Char::isDigit) && it.isNotEmpty() } ?: return null
        val data = path("data")
        val typeId = data.path("type_id").asInt(0)
        if (typeId == ADULT_TYPE) return null
        val title = data.path("vod_name").asText("").trim().ifBlank { return null }
        val poster = data.path("vod_imdb_poster").asText("").takeIf { it.startsWith("http") }
            ?: data.path("vod_pic").asText("").takeIf { it.isNotBlank() }?.let { imageUrl(it) }
            ?: data.path("vod_douban_cover").asText("").takeIf { it.isNotBlank() }?.let { imageUrl(it) }
        val year = data.path("vod_year").asText("").take(4).toIntOrNull()
        return searchResult(title, "$mainUrl/voddetail/$id/", poster, typeForId(typeId), year, null)
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

    /**
     * Feeds checked as ad-free play untouched: some (闪电云/qqqrst) switch to a mirror host mid-episode, which
     * CsAdStrip's "foreign block" rule would cut out. Everything else goes through CsAdStrip.
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? =
        if (synchronized(cleanFeeds) { extractorLink.url in cleanFeeds }) null else CsAdStrip.interceptor

    private val cleanFeeds = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            if (size >= 500) remove(first())
            return super.add(element)
        }
    }

    private suspend fun loadPage(url: String): LoadResponse {
        val detailUrl = url.substringBefore('#')
        val document = getPage(detailUrl)
        // The cover's title is the Chinese name; the h1 appends the original title ("玩具总动员5 Toy Story 5").
        val title = document.selectFirst("#cover_showbox")?.attr("title")?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("h1.uk-card-title a")?.text()?.trim().orEmpty()
                .ifBlank { throw ErrorLoadingException("找不到影片标题") }

        val info = { label: String ->
            document.select(".vodbox li").firstOrNull {
                it.selectFirst("span.fed-text-muted")?.text()?.trim()?.startsWith(label) == true
            }
        }
        val categoryLink = info("分类")?.selectFirst("a[href*=/vodtype/]")
        val typeId = categoryLink?.attr("href")?.let { Regex("""/vodtype/(\d+)""").find(it) }
            ?.groupValues?.get(1)?.toIntOrNull()
        if (typeId == ADULT_TYPE) throw ErrorLoadingException("此影片不可用")
        val category = categoryLink?.text()?.trim().orEmpty()

        val textOf = { label: String ->
            info(label)?.let { li ->
                li.text().substringAfter(li.selectFirst("span.fed-text-muted")?.text().orEmpty()).trim()
            }?.ifBlank { null }
        }
        val year = textOf("年份")?.take(4)?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(document.selectFirst("h1.uk-card-title")?.text().orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()
        val area = textOf("地区")
        val tags = (listOf(category) + textOf("TAG").orEmpty().split(',', '，', '/', ' ') + listOfNotNull(area))
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val names = { label: String, limit: Int ->
            info(label)?.select("a")?.map { it.text().trim() }
                ?.filter { it.isNotBlank() && it != "内详" }?.distinct()?.take(limit).orEmpty()
        }
        val actors = (names("主演", 20).map { ActorData(Actor(it)) } +
            names("导演", 3).map { ActorData(Actor(it), roleString = "导演") }).ifEmpty { null }
        val minutes = textOf("片长")?.let { Regex("""(\d+)\s*分""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        // "看片狂人(...)为您奉上...以下是剧情简介：<plot>"
        val rawPlot = info("简介")?.text()?.substringAfter("简介：")?.trim().orEmpty()
        val plot = (if (rawPlot.contains("剧情简介：")) rawPlot.substringAfter("剧情简介：") else rawPlot)
            .trim().trim('　').trim().ifBlank { null }
            ?: document.selectFirst("meta[name=description]")?.attr("content")
        val status = Regex("""最近更新:\s*(?:\d{4}-\d{2}-\d{2}\s*)?(.+)""")
            .find(document.selectFirst(".uk-card-header .uk-width-expand")?.ownText().orEmpty())
            ?.groupValues?.get(1)?.trim()?.ifBlank { null }
        val plotText = listOfNotNull(status?.let { "（$it）" }, plot).joinToString("\n").ifBlank { null }

        // "8.0 (138391票)" / "暂无评分"; TMDB: "7.20 (热度:132.45)".
        val doubanScore = document.selectFirst("#db_score")?.text()?.trim()?.substringBefore(' ')
            ?.toDoubleOrNull()?.takeIf { it > 0 }
        val tmdbScore = document.select(".vodbox li a[href*=themoviedb.org]").firstOrNull()?.text()
            ?.trim()?.substringBefore(' ')?.toDoubleOrNull()?.takeIf { it > 0 }
        val score = doubanScore ?: tmdbScore
        val poster = document.selectFirst("#cover_showbox")?.attr("data-original")?.takeIf { it.isNotBlank() }
            ?.let { imageUrl(it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.let { imageUrl(it) }

        val sources = parseSources(document)
        Log.i(TAG, "detail '$title' ($detailUrl): ${sources.size} usable servers " +
            sources.joinToString(prefix = "(", postfix = ")") { "${it.label}:${it.episodes.size}" })
        if (sources.isEmpty()) throw ErrorLoadingException("此影片暂无可用的免费线路")
        val canonical = canonicalEpisodes(sources)
        val baseType = typeForId(typeId ?: 0)
        val isMovie = canonical.size == 1 && (baseType == TvType.Movie || typeId == null)
        val type = when {
            isMovie -> TvType.Movie
            baseType == TvType.Movie -> TvType.TvSeries
            else -> baseType
        }
        val recommended = recommendations(document, detailUrl)

        return if (isMovie) {
            newMovieLoadResponse(title, detailUrl, type, episodeData(detailUrl, canonical[0], movie = true)) {
                posterUrl = poster
                this.plot = plotText
                this.year = year
                this.tags = tags
                this.score = score?.let { Score.from10(it) }
                this.actors = actors
                this.duration = minutes
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
                this.duration = minutes
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
        // data = https://www.kpkuang.us/voddetail/{id}/#ep={normalized name}&nid={n}  (ep is empty for movies)
        val detailUrl = data.substringBefore('#')
        val params = data.substringAfter('#', "").split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val wanted = URLDecoder.decode(params["ep"].orEmpty(), "UTF-8")
        val wantedNid = params["nid"]?.toIntOrNull()

        val sources = parseSources(getPage(detailUrl))
        // One episode per server, matched by episode number/name (falling back to the same position).
        val targets = sources.mapNotNull { source ->
            val entry = if (wanted.isNotBlank()) {
                source.episodes.firstOrNull { normalizeEpisode(it.name) == wanted }
                    ?: source.episodes.firstOrNull { it.nid == wantedNid && !it.name.contains("全集") }
            } else {
                source.episodes.firstOrNull()
            }
            entry?.let { source to it }
        }.sortedBy { (source, _) -> lineRank(source.lineId) } // likely-good servers are tried first
        Log.i(TAG, "links for $detailUrl ep='$wanted' nid=$wantedNid: ${targets.size} servers to resolve")
        if (targets.isEmpty()) return false

        val permits = Semaphore(MAX_PARALLEL)
        val seenUrls = mutableSetOf<String>()
        val found = coroutineScope {
            targets.map { (source, entry) ->
                async {
                    val stream = permits.withPermit {
                        withTimeoutOrNull(SERVER_TIMEOUT_MS) { resolve(detailUrl, entry) }
                    }
                    if (stream == null) {
                        Log.i(TAG, "server '${source.label}' (${source.lineId}): no working stream")
                        return@async false
                    }
                    val isNew = synchronized(seenUrls) { seenUrls.add(stream.url) }
                    if (!isNew) return@async false
                    if (stream.feed == Feed.CLEAN) synchronized(cleanFeeds) { cleanFeeds.add(stream.url) }
                    Log.i(TAG, "server '${source.label}' (${source.lineId}): ${stream.feed} ${stream.url}")
                    val label = when (stream.feed) {
                        Feed.ADS -> "${source.label} (含广告)"
                        else -> source.label
                    }
                    callback(
                        newExtractorLink(
                            name,
                            "$name $label",
                            stream.url,
                            if (stream.url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        ) {
                            referer = "$mainUrl/"
                            // Ranking: clean feeds by resolution, then feeds whose ads were stripped, then the rest.
                            quality = when (stream.feed) {
                                Feed.CLEAN -> stream.quality
                                Feed.STRIPPED -> Qualities.P360.value
                                Feed.ADS -> Qualities.P240.value
                            }
                            headers = mapOf("User-Agent" to userAgent)
                        },
                    )
                    true
                }
            }.awaitAll().count { it }
        }
        Log.i(TAG, "links for $detailUrl ep='$wanted': $found working of ${targets.size}")
        return found > 0
    }

    private enum class Feed { CLEAN, STRIPPED, ADS }
    private data class Stream(val url: String, val feed: Feed, val quality: Int)

    /** Reads the stream URL from the play page and checks its playlist (reachable, ads, resolution). */
    private suspend fun resolve(detailUrl: String, entry: EpisodeEntry): Stream? = runCatching {
        val html = app.get(
            "$mainUrl/vodplay/${entry.vodId}-${entry.sid}-${entry.nid}.html",
            headers = mapOf("User-Agent" to userAgent, "Referer" to detailUrl),
        ).text
        // Trial minutes mean the line is for paying members: never used.
        val trys = Regex("""data-trys="(\d+)"""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val tryMinutes = Regex(""""tryMinutes"\s*:\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (trys > 0 || tryMinutes > 0) return null
        val encoded = Regex("""data-play="([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null
        if (encoded.length <= 3) return null
        val url = String(Base64.decode(encoded.substring(3), Base64.DEFAULT), Charsets.UTF_8).trim()
        if (!url.startsWith("http")) return null
        val path = url.substringBefore('?').lowercase()
        if (path.endsWith(".mp4")) return Stream(url, Feed.CLEAN, Qualities.Unknown.value)
        if (!path.endsWith(".m3u8")) return null
        checkPlaylist(url)
    }.getOrNull()

    private suspend fun checkPlaylist(url: String): Stream? {
        val streamHeaders = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
        var playlistUrl = url
        var text = app.get(url, headers = streamHeaders, timeout = PLAYLIST_TIMEOUT_S).text
        if (!text.trimStart().startsWith("#EXTM3U")) return null
        var height: Int? = null
        if (text.contains("#EXT-X-STREAM-INF")) {
            // Master playlist: follow the highest-bandwidth variant.
            val lines = text.lines().map { it.trim() }
            val variants = lines.mapIndexedNotNull { index, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
                val uri = lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    ?: return@mapIndexedNotNull null
                val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val h = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                Triple(uri, bandwidth, h)
            }
            val best = variants.maxByOrNull { it.second } ?: return null
            height = best.third
            playlistUrl = URI(playlistUrl).resolve(best.first).toString()
            text = app.get(playlistUrl, headers = streamHeaders, timeout = PLAYLIST_TIMEOUT_S).text
            if (!text.trimStart().startsWith("#EXTM3U")) return null
        }
        if (!text.contains("#EXTINF")) return null
        // Only playlists that look ad-spliced are handed to CsAdStrip; the rest are clean as they are.
        val feed = when {
            !looksAdSpliced(text) -> Feed.CLEAN
            CsAdStrip.analyze(text, playlistUrl)?.let { !looksAdSpliced(it.text) } == true -> Feed.STRIPPED
            else -> Feed.ADS
        }
        val quality = when {
            height == null -> Qualities.Unknown.value
            height >= 1000 -> Qualities.P1080.value
            height >= 700 -> Qualities.P720.value
            else -> Qualities.Unknown.value // keeps every clean feed above the ad-stripped ones
        }
        return Stream(url, feed, quality)
    }

    /**
     * An ad clip is a short discontinuity block (≤ 30 s) inside a long programme (blocks of minutes),
     * or a segment from an ad folder. Playlists split evenly into short blocks are not ad-spliced.
     */
    private fun looksAdSpliced(text: String): Boolean {
        if (Regex("""/(adjump|ads?)/""").containsMatchIn(text)) return true
        if (!text.contains("#EXT-X-DISCONTINUITY")) return false
        val blocks = mutableListOf(0.0)
        text.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("#EXT-X-DISCONTINUITY") && !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") ->
                    blocks.add(0.0)
                line.startsWith("#EXTINF:") ->
                    blocks[blocks.lastIndex] += line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
            }
        }
        val real = blocks.filter { it > 0.0 }
        return real.size > 1 && real.any { it >= 120.0 } && real.any { it <= 30.0 }
    }

    /** Lower first. Line ids come from the detail page's `a.line-select[data-lineid]`. */
    private fun lineRank(lineId: String): Int = when (lineId) {
        // No ad splicing seen.
        "ukm3u8", "hnm3u8", "jinyingm3u8", "sdm3u8", "rym3u8", "wjm3u8" -> 0
        // Ad clips that CsAdStrip removes.
        "1080zyk", "dyttplay", "ffm3u8", "moduplay", "ikunyun", "360zy", "iqyplay", "bfzym3u8" -> 1
        // Hosts often unreachable outside mainland China.
        "huyaplay", "hohuplay", "subplay", "gsm3u8", "xlm3u8", "suoniplay" -> 3
        else -> 2
    }

    /** The detail page's "喜欢该作品的人也喜欢" grid (same page, no extra request). */
    private fun recommendations(document: Document, detailUrl: String): List<SearchResponse> = runCatching {
        val layout = document.select(".fed-part-layout").firstOrNull { layout ->
            layout.selectFirst("h2")?.text()?.trim() == "喜欢该作品的人也喜欢"
        } ?: return@runCatching emptyList()
        layout.select("li.fed-list-item").mapNotNull { it.toCard(null) }
            .filter { it.url != detailUrl }
            .distinctBy { it.url }
            .take(MAX_RECOMMENDATIONS)
    }.getOrDefault(emptyList())

    /** Backs off and retries when the site answers a burst (e.g. all home rows at once) with 429/503. */
    private suspend fun getPage(url: String): Document {
        repeat(2) { attempt ->
            val response = app.get(url, headers = pageHeaders)
            if (response.code != 429 && response.code != 503) return response.document
            delay(1500L * (attempt + 1))
        }
        return app.get(url, headers = pageHeaders).document
    }

    private data class EpisodeEntry(val name: String, val vodId: String, val sid: Int, val nid: Int)
    private data class Source(val label: String, val lineId: String, val episodes: List<EpisodeEntry>)

    private fun parseSources(document: Document): List<Source> {
        val lineIds = document.select("a.line-select[data-lineid]").associate {
            it.attr("data-linename").trim() to it.attr("data-lineid").trim()
        }
        return document.select("li.fed-play-item").mapNotNull { item ->
            val label = item.selectFirst(".fed-drop-head .uk-label")?.text()?.trim().orEmpty()
            val lineId = lineIds[label].orEmpty()
            if (!usableLine(label, lineId)) return@mapNotNull null
            val episodes = item.select("a.fed-btns-info[href*=/vodplay/]").mapNotNull { link ->
                val match = Regex("""/vodplay/(\d+)-(\d+)-(\d+)\.html""").find(link.attr("href"))
                    ?: return@mapNotNull null
                val (vodId, sid, nid) = match.destructured
                EpisodeEntry(link.text().trim(), vodId, sid.toInt(), nid.toInt())
            }.distinctBy { it.nid }.distinctBy { normalizeEpisode(it.name) }
            if (episodes.isEmpty()) null else Source(label.ifBlank { "线路 ${episodes[0].sid}" }, lineId, episodes)
        }
    }

    /** Free HLS lines only: no "VIP解析" (paid platforms) and no third-party embed hosts. */
    private fun usableLine(label: String, lineId: String): Boolean {
        if (label.contains("VIP", ignoreCase = true) || label.contains("解析")) return false
        if (lineId in EMBED_LINES) return false
        if (Regex("""^超清.{1,4}线$""").matches(label)) return false
        return true
    }

    /** The episode list most servers agree on (ties: the longer one). */
    private fun canonicalEpisodes(sources: List<Source>): List<EpisodeEntry> {
        val candidates = sources.filterNot { it.episodes.size == 1 && it.episodes[0].name.contains("全集") }
            .ifEmpty { sources }
        val size = candidates.groupingBy { it.episodes.size }.eachCount()
            .maxWithOrNull(compareBy<Map.Entry<Int, Int>>({ it.value }, { it.key }))!!.key
        return candidates.first { it.episodes.size == size }.episodes
    }

    private fun episodeData(detailUrl: String, entry: EpisodeEntry, movie: Boolean): String {
        // Movie servers name their single entry differently (HD中字 / 正片 / 720P), so movies match by position.
        val ep = if (movie) "" else URLEncoder.encode(normalizeEpisode(entry.name), "UTF-8")
        return "$detailUrl#ep=$ep&nid=${entry.nid}"
    }

    /** "第01集" / "01" / "EP1" / "S1_EP1" -> "1". */
    private fun normalizeEpisode(name: String): String {
        val trimmed = name.trim()
        Regex("""^S\d+[_\s-]*E(?:P)?\s*0*(\d+)$""", RegexOption.IGNORE_CASE).find(trimmed)?.let {
            return it.groupValues[1]
        }
        Regex("""^(?:第|EP?\s*)?\s*0*(\d+)\s*[集话話期]?$""", RegexOption.IGNORE_CASE).find(trimmed)?.let {
            return it.groupValues[1]
        }
        return trimmed
    }

    private fun episodeName(raw: String, position: Int): String {
        val normalized = normalizeEpisode(raw)
        return when {
            normalized.isBlank() -> "第 ${position + 1} 集"
            normalized.all { it.isDigit() } -> "第 $normalized 集"
            else -> raw.trim()
        }
    }

    private fun typeForId(id: Int): TvType = when (id) {
        in MOVIE_TYPES -> TvType.Movie
        4 -> TvType.Anime
        in SHORT_DRAMA_TYPES -> TvType.AsianDrama
        else -> TvType.TvSeries
    }

    private fun typeForPath(path: String): TvType? =
        Regex("""/vodtype/(\d+)/""").find(path)?.groupValues?.get(1)?.toIntOrNull()?.let { typeForId(it) }

    private fun typeForCategory(name: String): TvType? = when (name) {
        "电影", "动作片", "喜剧片", "爱情片", "科幻片", "恐怖片", "剧情片", "战争片", "纪录片", "卡通片", "动画片" -> TvType.Movie
        "动漫" -> TvType.Anime
        "短剧", "爽文短剧", "现代都市", "脑洞悬疑", "年代穿越", "古装仙侠", "反转爽剧", "女频恋爱", "成长逆袭", "有声动漫" ->
            TvType.AsianDrama
        "" -> null
        else -> TvType.TvSeries
    }

    /** Site images: relative /upload paths and the old kpkuang.one domain go to [mainUrl]; Douban via the site's proxy. */
    private fun imageUrl(raw: String): String {
        val url = raw.trim()
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> {
                val host = runCatching { URI(url).host }.getOrNull().orEmpty()
                when {
                    host.endsWith("doubanio.com") -> "https://dpic.flixfiend.top/" + url.substringAfter("://")
                    host.startsWith("www.kpkuang.") || host.startsWith("kpkuang.") ->
                        mainUrl + url.substringAfter(host)
                    else -> url
                }
            }
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    /** A poster card: `a.fed-list-pics` with title + data-original, remark badge and hover labels. */
    private fun Element.toCard(fallbackType: TvType?): SearchResponse? {
        val link = selectFirst("a.fed-list-pics[href*=/voddetail/]") ?: return null
        val title = link.attr("title").trim()
            .ifBlank { selectFirst(".fed-list-title")?.text()?.trim().orEmpty() }
            .ifBlank { return null }
        val labels = select(".uk-position-center .uk-label").map { it.text().trim() }
        if (labels.any { it == ADULT_LABEL }) return null
        val year = labels.firstOrNull { Regex("""^\d{4}$""").matches(it) }?.toIntOrNull()
        val type = labels.firstNotNullOfOrNull { label ->
            typeForCategory(label).takeIf { label in KNOWN_CATEGORIES }
        } ?: fallbackType ?: TvType.TvSeries
        val remark = selectFirst(".fed-list-remarks")?.text()?.trim()
        val poster = link.attr("data-original").takeIf { it.isNotBlank() }?.let { imageUrl(it) }
        return searchResult(title, fixUrl(link.attr("href")), poster, type, year, CsQuality.from(remark))
    }

    private fun searchResult(
        title: String,
        href: String,
        poster: String?,
        type: TvType,
        year: Int?,
        quality: SearchQuality?,
    ): SearchResponse =
        if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, type) {
                posterUrl = poster
                this.year = year
                this.quality = quality
            }
        } else {
            newTvSeriesSearchResponse(title, href, type) {
                posterUrl = poster
                this.year = year
                this.quality = quality
            }
        }

    companion object {
        private const val TAG = "Kpkuang"
        private const val SEARCH_API = "https://kpdata.flixfiend.top/esearch/index"
        private const val ADULT_TYPE = 26
        private const val ADULT_LABEL = "考驾照"
        private const val MAX_RECOMMENDATIONS = 18
        private const val MAX_PARALLEL = 3
        private const val SERVER_TIMEOUT_MS = 15_000L
        private const val PLAYLIST_TIMEOUT_S = 8L
        private val MOVIE_TYPES = setOf(1, 6, 7, 8, 9, 10, 11, 12, 29, 31)
        private val SHORT_DRAMA_TYPES = setOf(37, 36, 39, 40, 41, 42, 43, 44, 45, 46)
        private val EMBED_LINES = setOf(
            "qq", "youku", "bilibili", "qiyi", "iqiyi", "mgtv", "letv", "sohu", "pptv",
            "aby", "bys", "dara", "mixdrop", "dood", "streamtape",
        )
        private val KNOWN_CATEGORIES = setOf(
            "电影", "动作片", "喜剧片", "爱情片", "科幻片", "恐怖片", "剧情片", "战争片", "纪录片", "卡通片", "动画片",
            "连续剧", "国产剧", "港剧", "日剧", "欧美剧", "台剧", "泰剧", "越南剧", "韩剧", "海外剧", "综艺", "动漫",
            "短剧", "爽文短剧", "现代都市", "脑洞悬疑", "年代穿越", "古装仙侠", "反转爽剧", "女频恋爱", "成长逆袭", "有声动漫",
        )
    }
}
