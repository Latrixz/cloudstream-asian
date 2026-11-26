package com.haotv

import com.csasian.shared.CsBackup

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 华视界 (www.haotv.app, same site as huashijie.tv) is a MacCMS 10 site (template "huarenok") whose pages
 * are mostly filled by JavaScript from MacCMS's JSON API, so we use the same API:
 *  - lists:   /api.php/vod/get_list?type_id={id}&limit={n}&offset={o}[&orderby=hits]  (type-show.js)
 *             -> {"code":1,"info":{"offset","limit","total","rows":[{vod_id, vod_name, vod_pic, ...}]}}
 *  - search:  /api.php/search/index?module=all&limit=20&page={n}&wd={q} -> info.vod.{total,list}
 *  - detail:  /api.php/vod/get_detail?vod_id={id} (vod-detail-ajax-render.js) -> info with
 *             vod_play_list[{sid, from, note, urls[{name, url}]}] = every source and its full episode list
 *  - player:  /index.php/vod/play/id/{id}/sid/{s}/nid/{n}.html embeds `player_aaaa={...}` (fallback only;
 *             "encrypt": 0 plain, 1 escape(), 2 base64(escape())).
 * The home page's "推荐" row is server-rendered HTML; the category rows come from the list API.
 * The site's own "华视界VIP线路" (xpgm3u8 / xpgm3u8_4k) streams are ad-free and listed first; the other
 * lines (H/X/I/B线路) are collected sources that may carry ads, kept after them as alternatives.
 */
class HaotvProvider : MainAPI() {
    override var mainUrl = "https://www.haotv.app"
    override var name = "华视界 Haotv"
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
    private val pageHeaders get() = mapOf("User-Agent" to userAgent)
    private val jsonMapper = ObjectMapper()

    private data class Category(val id: Int, val label: String, val type: TvType)

    // Live TV (直播) is skipped.
    private val categories = listOf(
        Category(1, "电影", TvType.Movie),
        Category(2, "连续剧", TvType.TvSeries),
        Category(3, "综艺", TvType.TvSeries),
        Category(4, "动漫", TvType.Anime),
        Category(37, "短剧", TvType.AsianDrama),
        Category(38, "纪录片", TvType.Documentary),
    )

    // Same order as the site's home page: 推荐, then 最新X per category; plus X热播 (by hits) rows.
    override val mainPage = listOf(MainPageData("推荐", HOME_ROW)) +
        categories.flatMap { category ->
            listOf(
                MainPageData("最新${category.label}", "${category.id}|time"),
                MainPageData("${category.label}热播", "${category.id}|hits"),
            )
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == HOME_ROW) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val document = Jsoup.parse(getText("$mainUrl/"), "$mainUrl/")
            val list = document.selectFirst("#js-home-recommend9-list")
                ?: document.selectFirst("ul.hu-vlist")
            val items = list?.select("li.vodlist_item")?.mapNotNull { it.toListResult() }?.distinctBy { it.url }.orEmpty()
            return newHomePageResponse(request.name, items, false)
        }
        val (typeId, order) = request.data.split('|').let { it[0].toInt() to it.getOrElse(1) { "time" } }
        val offset = (page - 1) * PAGE_SIZE
        val orderParam = if (order == "hits") "&orderby=hits" else ""
        val info = getJson("$mainUrl/api.php/vod/get_list?type_id=$typeId&limit=$PAGE_SIZE&offset=$offset$orderParam")
            ?.path("info")
        val rows = info?.path("rows")?.toList().orEmpty()
        val items = rows.mapNotNull { rowResult(it) }.distinctBy { it.url }
        val total = info?.path("total")?.asInt(0) ?: 0
        return newHomePageResponse(request.name, items, rows.isNotEmpty() && offset + rows.size < total)
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
        val vod = getJson("$mainUrl/api.php/search/index?module=all&limit=$SEARCH_LIMIT&page=$page&wd=$encoded")
            ?.path("info")?.path("vod")
        val list = vod?.path("list")?.toList().orEmpty()
        val results = list.mapNotNull { item ->
            if (item.path("module").asText("vod") != "vod") return@mapNotNull null
            if (item.path("type_is_vip_exclusive").asInt(0) == 1) return@mapNotNull null
            val id = item.path("id").asText("").ifBlank { return@mapNotNull null }
            val title = item.path("name").asText("").trim().ifBlank { return@mapNotNull null }
            searchResult(title, detailUrl(id), image(item.path("pic").asText("")), typeOfCategory(item.path("type_id_1").asInt(0)))
        }.distinctBy { it.url }
        val total = vod?.path("total")?.asInt(0) ?: 0
        return results to (list.isNotEmpty() && page * SEARCH_LIMIT < total)
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
        val vodId = vodIdOf(url) ?: throw ErrorLoadingException("无效的影片地址")
        val detailUrl = detailUrl(vodId)
        val info = getDetail(vodId) ?: throw ErrorLoadingException("找不到影片资料")
        if (info.path("type_is_vip_exclusive").asInt(0) == 1) throw ErrorLoadingException("此影片为会员专享，暂不支持")
        val title = info.path("vod_name").asText("").trim().ifBlank { throw ErrorLoadingException("找不到影片标题") }
        val poster = image(info.path("vod_pic").asText(""))
        val plot = (info.path("vod_content").asText("").takeIf { it.isNotBlank() }?.let { Jsoup.parse(it).text() }
            ?: info.path("vod_blurb").asText("")).trim().ifBlank { null }
        val status = info.path("vod_remarks").asText("").trim().ifBlank { null }
        val plotText = listOfNotNull(status?.let { "（$it）" }, plot).joinToString("\n").ifBlank { null }
        val year = info.path("vod_year").asText("").take(4).toIntOrNull()
        val tags = (splitNames(info.path("vod_class").asText("")) + splitNames(info.path("vod_area").asText("")))
            .distinct()
        val actors = (splitNames(info.path("vod_actor").asText("")).take(20).map { ActorData(Actor(it)) } +
            splitNames(info.path("vod_director").asText("")).take(3).map { ActorData(Actor(it), roleString = "导演") })
            .ifEmpty { null }
        val score = listOf("vod_score", "vod_douban_score")
            .firstNotNullOfOrNull { key -> info.path(key).asText("").toDoubleOrNull()?.takeIf { it > 0 } }

        val sources = parseSources(info)
        if (sources.isEmpty()) throw ErrorLoadingException("此影片暂无播放来源")
        // Sources usually share one episode list; use the most complete one (site order on ties).
        val canonicalSource = sources.maxByOrNull { it.episodes.size }!!
        val canonical = canonicalSource.episodes
        val categoryType = typeOfCategory(info.path("type_id_1").asInt(0))
        val isMovie = canonical.size == 1 && (categoryType == TvType.Movie || info.path("type_id_1").asInt(0) == 0)
        val type = when {
            isMovie -> TvType.Movie
            categoryType == TvType.Movie -> TvType.TvSeries
            else -> categoryType
        }
        val recommended = recommendations(info.path("type_id").asInt(0), vodId, type)

        return if (isMovie) {
            newMovieLoadResponse(title, detailUrl, type, episodeData(vodId, canonicalSource.sid, 1, canonical[0], movie = true)) {
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
                newEpisode(episodeData(vodId, canonicalSource.sid, position + 1, entry, movie = false)) {
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
        // data = https://www.haotv.app/index.php/vod/play/id/{id}/sid/{s}/nid/{n}.html#ep={normalized name}
        // (ep is empty for movies, which are matched by position because sources name them 蓝光 / 正片 / HD).
        val playUrl = data.substringBefore('#')
        val params = data.substringAfter('#', "").split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val wanted = URLDecoder.decode(params["ep"].orEmpty(), "UTF-8")
        val match = PLAY_REGEX.find(playUrl) ?: return false
        val vodId = match.groupValues[1]
        val wantedNid = match.groupValues[3].toInt()

        val sources = getDetail(vodId)?.let { parseSources(it) }.orEmpty()
        // One episode per source, matched by episode number/name (falling back to the same position).
        val targets = sources.mapNotNull { source ->
            val index = (if (wanted.isNotBlank()) source.episodes.indexOfFirst { normalizeEpisode(it.name) == wanted } else -1)
                .takeIf { it >= 0 }
                ?: (wantedNid - 1).takeIf { it in source.episodes.indices && (wanted.isBlank() || source.episodes.size > 1) }
                ?: return@mapNotNull null
            Triple(source, index + 1, source.episodes[index])
        }

        var found = 0
        targets.forEach { (source, nid, entry) ->
            // The API normally returns plain URLs; anything else is resolved through the play page.
            val streamUrl = entry.url.takeIf { it.startsWith("http") }
                ?: playerUrl("$mainUrl/index.php/vod/play/id/$vodId/sid/${source.sid}/nid/$nid.html")
                ?: return@forEach
            emit(streamUrl, source.label, "${source.from} ${source.label} ${entry.name}", callback)
            found++
        }
        if (found == 0) {
            // API unavailable: read player_aaaa from the play page itself.
            playerUrl(playUrl)?.let { emit(it, "华视界VIP线路", "", callback); found++ }
        }
        return found > 0
    }

    private suspend fun emit(streamUrl: String, label: String, qualityHint: String, callback: (ExtractorLink) -> Unit) {
        callback(
            newExtractorLink(
                name,
                "$name $label",
                streamUrl,
                if (streamUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                referer = "$mainUrl/"
                quality = qualityOf(qualityHint)
                headers = mapOf("User-Agent" to userAgent)
            },
        )
    }

    /**
     * The VIP playlists (down.jsybox.com/hls/<b64>.m3u8) use AES-128 (EXT-X-KEY /m3u8key/<b64>.key, no IV
     * attribute), and every segment (served as image/jpeg) decrypts to a 188-byte fake BMP header, then
     * the real MPEG-TS, then zero padding. ExoPlayer's TS extractor fails on that ("Cannot find sync
     * byte"), so this interceptor fetches the key itself, drops EXT-X-KEY from the playlist, and serves
     * each segment decrypted with everything before the first TS packet removed.
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = hlsFixer

    private class SegmentKey(val key: ByteArray, val iv: ByteArray)

    private val segmentKeys = object : LinkedHashMap<String, SegmentKey>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SegmentKey>?) = size > MAX_SEGMENT_KEYS
    }
    private val keyCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 50
    }

    private val hlsFixer = Interceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()
        val segment = synchronized(segmentKeys) { segmentKeys[url] }
        if (segment != null) {
            // Always fetch the whole segment (it can only be decrypted as a whole); ExoPlayer skips
            // to the wanted position itself when a range request is answered with 200.
            val response = chain.proceed(request.newBuilder().removeHeader("Range").build())
            fixSegment(response, segment)
        } else {
            val response = chain.proceed(request)
            fixPlaylist(response, request)
        }
    }

    private fun fixSegment(response: Response, segment: SegmentKey): Response = try {
        if (!response.isSuccessful) response else {
            val data = response.body.bytes()
            val clean = decryptSegment(data, segment)?.let { stripToTs(it) } ?: data
            response.newBuilder()
                .code(200)
                .removeHeader("Content-Range")
                .removeHeader("Content-Length")
                .removeHeader("Content-Encoding")
                .header("Content-Type", "video/mp2t")
                .body(clean.toResponseBody("video/mp2t".toMediaTypeOrNull()))
                .build()
        }
    } catch (e: IOException) {
        throw e
    } catch (_: Throwable) {
        response
    }

    private fun decryptSegment(data: ByteArray, segment: SegmentKey): ByteArray? {
        if (data.isEmpty() || data.size % 16 != 0) return null
        fun run(mode: String) = Cipher.getInstance(mode).run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(segment.key, "AES"), IvParameterSpec(segment.iv))
            doFinal(data)
        }
        return runCatching { run("AES/CBC/PKCS5Padding") }.recoverCatching { run("AES/CBC/NoPadding") }.getOrNull()
    }

    /** Drops the bytes before the first of three consecutive TS packets, and any partial packet at the end. */
    private fun stripToTs(data: ByteArray): ByteArray {
        val start = (0 until minOf(4096, data.size - 376)).firstOrNull {
            data[it] == TS_SYNC && data[it + 188] == TS_SYNC && data[it + 376] == TS_SYNC
        } ?: return data
        var end = start + (data.size - start) / 188 * 188
        // Segments end with zero padding after the real packets; ExoPlayer gives up ("Cannot find
        // sync byte") when it reaches it, so drop every trailing packet that doesn't start with 0x47.
        while (end - 188 >= start && data[end - 188] != TS_SYNC) end -= 188
        return if (start == 0 && end == data.size) data else data.copyOfRange(start, end)
    }

    private fun fixPlaylist(response: Response, request: Request): Response {
        try {
            if (!response.isSuccessful) return response
            val type = response.header("Content-Type").orEmpty().lowercase()
            if (!type.contains("mpegurl") && !request.url.encodedPath.endsWith(".m3u8", true)) return response
            val body = response.body
            if (body.contentLength() > 4_000_000) return response
            val text = body.string()
            val unchanged = { response.newBuilder().body(text.toResponseBody(body.contentType())).build() }
            val rewritten = rewritePlaylist(text, response.request) ?: return unchanged()
            return response.newBuilder()
                .removeHeader("Content-Length")
                .removeHeader("Content-Encoding")
                .body(rewritten.toResponseBody(body.contentType() ?: "application/vnd.apple.mpegurl".toMediaTypeOrNull()))
                .build()
        } catch (e: IOException) {
            throw e
        } catch (_: Throwable) {
            return response
        }
    }

    /**
     * For a media playlist encrypted with AES-128: records each segment's key and IV, removes the
     * EXT-X-KEY lines and makes segment URLs absolute. Returns null to keep the playlist as it is.
     */
    private fun rewritePlaylist(text: String, request: Request): String? {
        if (!text.trimStart().startsWith("#EXTM3U")) return null
        if (!text.contains("#EXT-X-KEY", true) || text.contains("#EXT-X-STREAM-INF")) return null
        // Init segments and byte ranges would need more than whole-segment decryption.
        if (text.contains("#EXT-X-MAP") || text.contains("#EXT-X-BYTERANGE")) return null
        val base = request.url
        val keyHeaders = request.headers.newBuilder().removeAll("Range").build()
        val out = StringBuilder(text.length)
        val found = mutableMapOf<String, SegmentKey>()
        var sequence = 0L
        var key: ByteArray? = null
        var explicitIv: ByteArray? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                    out.append(raw).append('\n')
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    when (attrs["METHOD"]?.uppercase()) {
                        "NONE" -> { key = null; explicitIv = null }
                        "AES-128" -> {
                            val format = attrs["KEYFORMAT"]
                            if (format != null && format != "identity") return null
                            val keyUrl = attrs["URI"]?.let { base.resolve(it) } ?: return null
                            key = fetchKey(keyUrl.toString(), keyHeaders) ?: return null
                            explicitIv = attrs["IV"]?.let { parseIv(it) ?: return null }
                        }
                        else -> return null
                    }
                    // Dropped: the segments are served decrypted.
                }
                line.isEmpty() -> out.append(raw).append('\n')
                line.startsWith("#") -> out.append(raw).append('\n')
                else -> {
                    val absolute = base.resolve(line)?.toString() ?: return null
                    key?.let { k -> found[absolute] = SegmentKey(k, explicitIv ?: sequenceIv(sequence)) }
                    out.append(absolute).append('\n')
                    sequence++
                }
            }
        }
        if (found.isEmpty()) return null
        synchronized(segmentKeys) { segmentKeys.putAll(found) }
        return out.toString()
    }

    private fun fetchKey(url: String, headers: okhttp3.Headers): ByteArray? {
        synchronized(keyCache) { keyCache[url] }?.let { return it }
        val bytes = runCatching {
            app.baseClient.newCall(Request.Builder().url(url).headers(headers).build()).execute().use {
                if (it.isSuccessful) it.body.bytes() else null
            }
        }.getOrNull()?.takeIf { it.size == 16 } ?: return null
        synchronized(keyCache) { keyCache[url] = bytes }
        return bytes
    }

    /** EXT-X-KEY attributes; quoted values may contain commas. */
    private fun parseAttributes(text: String): Map<String, String> =
        Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""").findAll(text).associate {
            it.groupValues[1].uppercase() to it.groupValues[2].trim().removeSurrounding("\"")
        }

    private fun parseIv(text: String): ByteArray? {
        val hex = text.removePrefix("0x").removePrefix("0X")
        if (hex.isEmpty() || hex.length > 32 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        val padded = hex.padStart(32, '0')
        return ByteArray(16) { padded.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** HLS default IV: the media sequence number as a 16-byte big-endian integer. */
    private fun sequenceIv(sequence: Long): ByteArray =
        ByteArray(16).also { iv -> for (i in 0 until 8) iv[15 - i] = (sequence ushr (8 * i)).toByte() }

    /** Reads `player_aaaa` from a play page and returns the decoded stream URL. */
    private suspend fun playerUrl(pageUrl: String): String? = runCatching {
        val html = getText(pageUrl)
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

    /** The detail page's "猜你喜欢" row: same sub-category, by monthly hits (as the site's own script does). */
    private suspend fun recommendations(typeId: Int, vodId: String, type: TvType): List<SearchResponse> =
        runCatching {
            if (typeId <= 0) return@runCatching emptyList()
            getJson("$mainUrl/api.php/vod/get_list?type_id=$typeId&limit=$MAX_RECOMMENDATIONS&offset=0&orderby=hits_month")
                ?.path("info")?.path("rows")?.toList().orEmpty()
                .filter { it.path("vod_id").asText("") != vodId }
                .mapNotNull { rowResult(it, type) }
                .distinctBy { it.url }
        }.getOrDefault(emptyList())

    private suspend fun getDetail(vodId: String): JsonNode? =
        getJson("$mainUrl/api.php/vod/get_detail?vod_id=$vodId")?.path("info")?.takeIf { it.isObject }

    private suspend fun getJson(url: String): JsonNode? = runCatching {
        jsonMapper.readTree(getText(url)).takeIf { it.path("code").asInt(0) == 1 }
    }.getOrNull()

    /** Backs off and retries when the site answers a burst (e.g. all home rows at once) with HTTP 429. */
    private suspend fun getText(url: String): String {
        repeat(3) { attempt ->
            val response = app.get(url, headers = pageHeaders)
            if (response.code != 429) return response.text
            delay(1500L * (attempt + 1))
        }
        return app.get(url, headers = pageHeaders).text
    }

    private data class EpisodeEntry(val name: String, val url: String)
    private data class Source(val sid: Int, val from: String, val label: String, val episodes: List<EpisodeEntry>)

    private fun parseSources(info: JsonNode): List<Source> =
        info.path("vod_play_list").toList().mapIndexedNotNull { index, node ->
            val episodes = node.path("urls").toList().map {
                EpisodeEntry(it.path("name").asText("").trim(), it.path("url").asText("").trim())
            }
            if (episodes.isEmpty()) return@mapIndexedNotNull null
            val from = node.path("from").asText("")
            val label = node.path("note").asText("").trim().ifBlank { null }
                ?: node.path("player_info").path("show").asText("").trim().ifBlank { null }
                ?: "线路 ${index + 1}"
            Source(node.path("sid").asInt(index + 1), from, label, episodes)
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

    private fun detailUrl(vodId: String) = "$mainUrl/index.php/vod/detail/id/$vodId.html"

    private fun vodIdOf(url: String): String? =
        Regex("""/id/(\d+)""").find(url)?.groupValues?.get(1)

    private fun episodeData(vodId: String, sid: Int, nid: Int, entry: EpisodeEntry, movie: Boolean): String {
        val ep = if (movie) "" else URLEncoder.encode(normalizeEpisode(entry.name), "UTF-8")
        return "$mainUrl/index.php/vod/play/id/$vodId/sid/$sid/nid/$nid.html#ep=$ep"
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

    private fun splitNames(text: String): List<String> =
        text.split(',', '，', '/', '、').map { it.trim() }
            .filter { it.isNotBlank() && it != "内详" && it != "未知" }.distinct()

    private fun typeOfCategory(typeId1: Int): TvType =
        categories.firstOrNull { it.id == typeId1 }?.type ?: TvType.TvSeries

    private fun qualityOf(text: String): Int = when {
        // The 4K line is H.265, which many TVs can't decode: rank it with 1080p so the normal VIP
        // line (listed before it) plays first; "4K" stays in the link name for manual choice.
        text.contains("4K", true) || text.contains("2160") -> Qualities.P1080.value
        text.contains("1080") || text.contains("蓝光") || text.contains("VIP", true) -> Qualities.P1080.value
        text.contains("720") || text.contains("HD", true) || text.contains("高清") -> Qualities.P720.value
        else -> Qualities.Unknown.value
    }

    private fun image(url: String?): String? =
        url?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let { fixUrl(it) }

    /** A row from /api.php/vod/get_list; [type] overrides the category-derived type. */
    private fun rowResult(row: JsonNode, type: TvType? = null): SearchResponse? {
        if (row.path("type_is_vip_exclusive").asInt(0) == 1) return null
        val id = row.path("vod_id").asText("").ifBlank { return null }
        val title = row.path("vod_name").asText("").trim().ifBlank { return null }
        return searchResult(
            title, detailUrl(id), image(row.path("vod_pic").asText("")),
            type ?: typeOfCategory(row.path("type_id_1").asInt(0)),
        )
    }

    /** A card from the home page's server-rendered "推荐" row (mixed categories, so the type is guessed). */
    private fun Element.toListResult(): SearchResponse? {
        val link = selectFirst("a.vodlist_thumb[href*=/vod/detail/]") ?: return null
        val title = link.attr("title").trim()
            .ifBlank { selectFirst(".vodlist_title a")?.text()?.trim().orEmpty() }
            .ifBlank { return null }
        val tagText = select(".detail .tag").text()
        val type = when {
            tagText.contains("动漫") -> TvType.Anime
            tagText.contains("纪录") -> TvType.Documentary
            tagText.contains("片") && !tagText.contains("剧") -> TvType.Movie
            else -> TvType.TvSeries
        }
        return searchResult(title, fixUrl(link.attr("href")), image(link.attr("data-original").ifBlank { link.attr("src") }), type)
    }

    private fun searchResult(title: String, href: String, poster: String?, type: TvType): SearchResponse =
        if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, type) { posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, type) { posterUrl = poster }
        }

    companion object {
        private const val HOME_ROW = "home"
        private const val PAGE_SIZE = 24
        private const val SEARCH_LIMIT = 20
        private const val MAX_RECOMMENDATIONS = 16
        private const val MAX_SEGMENT_KEYS = 5000
        private const val TS_SYNC: Byte = 0x47
        private val PLAY_REGEX = Regex("""/vod/play/id/(\d+)/sid/(\d+)/nid/(\d+)""")
    }
}
