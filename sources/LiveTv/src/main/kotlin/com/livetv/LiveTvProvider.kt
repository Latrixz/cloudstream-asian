package com.livetv

import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream

/**
 * Free, publicly available live channels listed by the iptv-org project.
 *
 * The catalogue comes from the iptv-org JSON API (channels, streams, feeds, logos, blocklist).
 * The files are large uncompressed (several MB each) but GitHub Pages serves them gzipped
 * (about 1 MB each), and they are parsed one object at a time with a streaming parser, keeping
 * only the channels shown here. If the API cannot be read, the smaller per-country and
 * per-category M3U playlists are used instead. Everything is cached in memory for 30 minutes.
 *
 * Filtering: blocklisted, NSFW (`is_nsfw` or the `xxx` category) and closed channels are left
 * out, as are streams labelled "Geo-blocked" or "Not 24/7".
 */
class LiveTvProvider : MainAPI() {
    override var mainUrl = "https://iptv-org.github.io"
    override var name = "直播 Live TV"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "country/CN" to "中国 China",
        "country/HK" to "香港 Hong Kong",
        "country/TW" to "台灣 Taiwan",
        "country/SG" to "新加坡 Singapore",
        "country/MY" to "马来西亚 Malaysia",
        "country/JP" to "日本 Japan",
        "country/KR" to "한국 Korea",
        "country/ID" to "Indonesia 印尼",
        "country/VN" to "Việt Nam 越南",
        "country/TH" to "ประเทศไทย 泰国",
        "country/PH" to "Philippines 菲律宾",
        "country/KH" to "កម្ពុជា 柬埔寨",
        "country/MM" to "မြန်မာ 缅甸",
        "country/LA" to "ລາວ 老挝",
        "country/US" to "United States 美国",
        "country/UK" to "United Kingdom 英国",
        "country/CA" to "Canada 加拿大",
        "country/AU" to "Australia 澳大利亚",
        "country/NZ" to "New Zealand 新西兰",
        "country/IN" to "India 印度",
        "category/news" to "新闻 News",
        "category/sports" to "体育 Sports",
        "category/movies" to "电影 Movies",
        "category/kids" to "少儿 Kids",
        "category/music" to "音乐 Music",
        "category/documentary" to "纪录 Documentary",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val all = catalog().rows[request.data].orEmpty()
        val from = (page - 1) * PAGE_SIZE
        val items = all.drop(from).take(PAGE_SIZE).map { toCard(it) }
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = from + PAGE_SIZE < all.size,
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        return catalog().channels.values.mapNotNull { c ->
            val names = (listOf(c.name) + c.altNames).map { normalize(it) }
            val rank = when {
                names.any { it == q } -> 0
                names.any { it.startsWith(q) } -> 1
                names.any { it.contains(q) } -> 2
                else -> return@mapNotNull null
            }
            rank to c
        }.sortedWith(compareBy<Pair<Int, Channel>> { it.first }.thenBy(NATURAL) { it.second.name })
            .take(MAX_SEARCH)
            .map { toCard(it.second) }
    }

    override suspend fun load(url: String): LoadResponse {
        val cat = catalog()
        val id = channelId(url) ?: throw ErrorLoadingException("Invalid channel URL: $url")
        val c = cat.channels[id] ?: throw ErrorLoadingException("Channel not found: $id")
        val categories = c.categories.map { cat.categoryNames[it] ?: it.replaceFirstChar { ch -> ch.uppercase() } }
        val languages = c.languages.map { cat.languageNames[it] ?: it }
        val plot = buildString {
            append(listOfNotNull(
                cat.countryNames[c.country] ?: c.country,
                categories.joinToString(", ").ifEmpty { null },
                languages.joinToString(", ").ifEmpty { null },
            ).joinToString(" · "))
            if (c.altNames.isNotEmpty()) append("\n").append(c.altNames.joinToString(" / "))
            c.website?.let { append("\nWebsite: ").append(it) }
            append("\n").append(c.streams.size).append(if (c.streams.size == 1) " stream" else " streams")
        }
        return newLiveStreamLoadResponse(c.name, channelUrl(c.id), channelUrl(c.id)) {
            posterUrl = c.logo
            this.plot = plot
            tags = (categories + languages).distinct()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val id = channelId(data) ?: return false
        val c = catalog().channels[id] ?: return false
        // Highest quality first; the sort is stable, so iptv-org's order is kept otherwise.
        val streams = c.streams.sortedByDescending { it.height ?: 0 }
        val checked = streams.amap { it to isLive(it) }
        val alive = checked.filter { it.second }.map { it.first }
        val offered = alive.ifEmpty { streams }
        offered.forEach { s ->
            val label = listOfNotNull(s.title.ifBlank { c.name }, s.quality).joinToString(" ")
            callback(
                newExtractorLink(name, label, s.url, if (s.isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8) {
                    s.referrer?.let { referer = it }
                    quality = s.height ?: Qualities.Unknown.value
                    headers = streamHeaders(s)
                },
            )
        }
        return offered.isNotEmpty()
    }

    // ---- Cards ----

    private fun toCard(c: Channel): SearchResponse =
        newLiveSearchResponse(c.name, channelUrl(c.id), TvType.Live) { posterUrl = c.logo }

    private fun channelUrl(id: String) = "$mainUrl/channel/$id"

    private fun channelId(url: String): String? =
        url.substringAfter("/channel/", "").substringBefore('?').substringBefore('#').trim('/').ifEmpty { null }

    // ---- Stream checks ----

    private fun streamHeaders(s: Stream): Map<String, String> = buildMap {
        put("User-Agent", s.userAgent ?: USER_AGENT)
        s.referrer?.let { put("Referer", it) }
    }

    /** The master playlist (or DASH manifest) must answer within about six seconds. */
    private suspend fun isLive(s: Stream): Boolean = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
        runCatching {
            val r = app.get(s.url, headers = streamHeaders(s), timeout = CHECK_TIMEOUT_MS / 1000)
            if (!r.isSuccessful) return@runCatching false
            val head = r.body.byteStream().use { String(readHead(it, 4096), Charsets.UTF_8) }
                .trimStart('﻿', ' ', '\r', '\n', '\t', '\u0000')
            if (s.isDash) head.contains("<MPD") else head.startsWith("#EXTM3U")
        }.getOrDefault(false)
    } ?: false

    private fun readHead(stream: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = stream.read(buffer, read, size - read)
            if (n < 0) break
            read += n
        }
        return buffer.copyOf(read)
    }

    // ---- Catalogue ----

    private class Stream(
        val url: String,
        val title: String,
        val quality: String?,
        val referrer: String?,
        val userAgent: String?,
    ) {
        val height: Int? = quality?.let { Regex("""(\d{3,4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val isDash: Boolean = url.substringBefore('?').endsWith(".mpd", ignoreCase = true)
    }

    private class Channel(
        val id: String,
        val name: String,
        val altNames: List<String>,
        val country: String,
        val categories: List<String>,
        val website: String?,
        var logo: String? = null,
        var languages: List<String> = emptyList(),
        val streams: MutableList<Stream> = mutableListOf(),
    )

    private class Catalog(
        val channels: Map<String, Channel>,
        val rows: Map<String, List<Channel>>,
        val countryNames: Map<String, String>,
        val categoryNames: Map<String, String>,
        val languageNames: Map<String, String>,
        val time: Long,
    )

    private suspend fun catalog(): Catalog = catalogLock.withLock {
        cache?.takeIf { System.currentTimeMillis() - it.time < CACHE_TTL }?.let { return@withLock it }
        val fresh = runCatching { loadFromApi() }.getOrNull()?.takeIf { it.channels.isNotEmpty() }
            ?: runCatching { loadFromPlaylists() }.getOrNull()?.takeIf { it.channels.isNotEmpty() }
        if (fresh != null) cache = fresh
        fresh ?: cache ?: throw ErrorLoadingException("Could not load the iptv-org channel list")
    }

    private val mapper = ObjectMapper()

    private suspend fun download(file: String): InputStream =
        app.get("$API/$file", headers = mapOf("User-Agent" to USER_AGENT), timeout = 90L).body.byteStream()

    /** Calls [block] with each object of a top-level JSON array, without reading the whole tree. */
    private fun eachObject(stream: InputStream, block: (JsonNode) -> Unit) {
        stream.use { input ->
            mapper.factory.createParser(input).use { p ->
                if (p.nextToken() != JsonToken.START_ARRAY) return
                while (p.nextToken() == JsonToken.START_OBJECT) {
                    block(mapper.readTree<JsonNode>(p))
                }
            }
        }
    }

    private suspend fun loadFromApi(): Catalog = coroutineScope {
        val channelsJob = async(Dispatchers.IO) {
            val out = HashMap<String, Channel>()
            eachObject(download("channels.json")) { n ->
                val id = n.text("id") ?: return@eachObject
                val country = n.text("country") ?: return@eachObject
                val categories = n.strings("categories")
                if (n["is_nsfw"]?.asBoolean() == true || "xxx" in categories) return@eachObject
                if (n.text("closed") != null) return@eachObject
                // The Asian countries, plus news channels anywhere (English ones are kept later).
                if (country !in COUNTRIES && "news" !in categories) return@eachObject
                out[id] = Channel(id, n.text("name") ?: id, n.strings("alt_names"), country, categories, n.text("website"))
            }
            out
        }
        val streamsJob = async(Dispatchers.IO) {
            val out = HashMap<String, MutableList<Stream>>()
            eachObject(download("streams.json")) { n ->
                val channel = n.text("channel") ?: return@eachObject
                val url = n.text("url") ?: return@eachObject
                if (n.strings("labels").isNotEmpty()) return@eachObject // Geo-blocked / Not 24/7
                // Only web streams play in CloudStream; rtmp:// and similar would always fail.
                if (!url.startsWith("http://") && !url.startsWith("https://")) return@eachObject
                out.getOrPut(channel) { mutableListOf() } +=
                    Stream(url, n.text("title") ?: "", n.text("quality"), n.text("referrer"), n.text("user_agent"))
            }
            out
        }
        val feedsJob = async(Dispatchers.IO) {
            // channel -> (main feed languages, all feed languages)
            val main = HashMap<String, List<String>>()
            val all = HashMap<String, LinkedHashSet<String>>()
            runCatching {
                eachObject(download("feeds.json")) { n ->
                    val channel = n.text("channel") ?: return@eachObject
                    val langs = n.strings("languages")
                    if (n["is_main"]?.asBoolean() == true && langs.isNotEmpty()) main[channel] = langs
                    all.getOrPut(channel) { LinkedHashSet() } += langs
                }
            }
            all.mapValues { (k, v) -> main[k] ?: v.toList() }
        }
        val logosJob = async(Dispatchers.IO) {
            val best = HashMap<String, Pair<Int, String>>()
            runCatching {
                eachObject(download("logos.json")) { n ->
                    val channel = n.text("channel") ?: return@eachObject
                    val url = n.text("url") ?: return@eachObject
                    val format = n.text("format")?.uppercase() ?: url.substringAfterLast('.').uppercase()
                    // Prefer the channel's own current logo, and raster images (SVG may not render).
                    // Rank hosts by how reliably a TV can load many logos at once:
                    // Imgur/GitHub handle bursts; Wikimedia rate-limits (429) and Fandom blocks
                    // hotlinks (403), so those are mirrored on our logo branch and only used as a
                    // fallback; free image hosts such as ibb.co often delete uploads.
                    val host = url.substringAfter("://").substringBefore('/').lowercase()
                    val hostScore = when {
                        host == "i.imgur.com" || host.endsWith("githubusercontent.com") -> 16
                        host.endsWith("wikimedia.org") -> 2
                        host.contains("wikia") || host.contains("fandom") || host.contains("tvtropes") -> -12
                        host.endsWith("ibb.co") || host.contains("postimg") || host.contains("imgbb") -> -16
                        else -> 0
                    }
                    val score = hostScore +
                        (if (n["in_use"]?.asBoolean() != false) 4 else 0) +
                        (if (n.text("feed") == null) 2 else 0) +
                        (if (format != "SVG") 1 else 0)
                    if ((best[channel]?.first ?: -1) < score) best[channel] = score to url
                }
            }
            best.mapValues { it.value.second }
        }
        val blockJob = async(Dispatchers.IO) {
            val out = HashSet<String>()
            eachObject(download("blocklist.json")) { n -> n.text("channel")?.let { out += it } }
            out
        }
        val namesJob = async(Dispatchers.IO) { referenceNames() }
        // Our own copies of logos whose original hosts don't load on TVs (see scripts/livetv-mirror-logos.py).
        val mirrorJob = async(Dispatchers.IO) {
            runCatching {
                val root = mapper.readTree(app.get("$LOGO_MIRROR/index.json", timeout = 20L).text)
                root.fields().asSequence().associate { (id, file) -> id to "$LOGO_MIRROR/${file.asText()}" }
            }.getOrDefault(emptyMap())
        }

        val channels = channelsJob.await()
        val streams = streamsJob.await()
        val languages = feedsJob.await()
        val logos = logosJob.await()
        val mirrored = mirrorJob.await()
        val blocked = blockJob.await()
        val (countryNames, categoryNames, languageNames) = namesJob.await()

        val kept = LinkedHashMap<String, Channel>()
        for (c in channels.values) {
            if (c.id in blocked) continue
            val s = streams[c.id]?.distinctBy { it.url } ?: continue
            c.languages = languages[c.id].orEmpty()
            if (c.country !in COUNTRIES && "eng" !in c.languages) continue // English news only
            c.streams += s
            c.logo = mirrored[c.id] ?: logos[c.id]
            kept[c.id] = c
        }
        Catalog(kept, buildRows(kept.values), countryNames, categoryNames, languageNames, System.currentTimeMillis())
    }

    private fun buildRows(channels: Collection<Channel>): Map<String, List<Channel>> {
        val sorted = channels.sortedWith(compareBy(NATURAL) { it.name })
        val asian = sorted.filter { it.country in COUNTRIES }
        val rows = LinkedHashMap<String, List<Channel>>()
        COUNTRIES.forEach { cc -> rows["country/$cc"] = asian.filter { it.country == cc } }
        CATEGORIES.forEach { cat ->
            val inAsia = asian.filter { cat in it.categories }
            rows["category/$cat"] = if (cat == "news") {
                inAsia + sorted.filter { it.country !in COUNTRIES && "news" in it.categories && "eng" in it.languages }
            } else inAsia
        }
        return rows
    }

    /** Display names for countries, categories and languages (small files; optional). */
    private suspend fun referenceNames(): Triple<Map<String, String>, Map<String, String>, Map<String, String>> {
        suspend fun names(file: String, key: String): Map<String, String> = runCatching {
            val out = HashMap<String, String>()
            eachObject(download(file)) { n ->
                val k = n.text(key)
                val v = n.text("name")
                if (k != null && v != null) out[k] = v
            }
            out
        }.getOrDefault(emptyMap())
        return Triple(names("countries.json", "code"), names("categories.json", "id"), names("languages.json", "code"))
    }

    /** Fallback: the per-country and per-category M3U playlists (already filtered by iptv-org). */
    private suspend fun loadFromPlaylists(): Catalog = coroutineScope {
        val countryJobs = COUNTRIES.map { cc ->
            async(Dispatchers.IO) { cc to runCatching { parseM3u(app.get("$IPTV/countries/${cc.lowercase()}.m3u", timeout = 60L).text) }.getOrDefault(emptyList()) }
        }
        val categoryJobs = CATEGORIES.map { cat ->
            async(Dispatchers.IO) { cat to runCatching { parseM3u(app.get("$IPTV/categories/$cat.m3u", timeout = 60L).text) }.getOrDefault(emptyList()) }
        }
        val channels = LinkedHashMap<String, Channel>()
        fun add(e: M3uEntry, country: String?) {
            val cc = country ?: e.id.substringAfterLast('.', "").uppercase().takeIf { it in COUNTRIES } ?: return
            val c = channels.getOrPut(e.id) {
                Channel(e.id, e.name, emptyList(), cc, e.groups.map { it.lowercase() }.filter { it != "undefined" }, null, e.logo)
            }
            if (c.streams.none { it.url == e.stream.url }) c.streams += e.stream
        }
        countryJobs.forEach { job -> val (cc, entries) = job.await(); entries.forEach { add(it, cc) } }
        categoryJobs.forEach { job -> job.await().second.forEach { add(it, null) } }
        Catalog(channels, buildRows(channels.values), emptyMap(), emptyMap(), emptyMap(), System.currentTimeMillis())
    }

    private class M3uEntry(val id: String, val name: String, val logo: String?, val groups: List<String>, val stream: Stream)

    private fun parseM3u(text: String): List<M3uEntry> {
        val out = mutableListOf<M3uEntry>()
        var info: String? = null
        var userAgent: String? = null
        var referrer: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> { info = line; userAgent = null; referrer = null }
                line.startsWith("#EXTVLCOPT:http-user-agent=") -> userAgent = line.substringAfter('=')
                line.startsWith("#EXTVLCOPT:http-referrer=") -> referrer = line.substringAfter('=')
                line.isEmpty() || line.startsWith("#") -> Unit
                info != null -> {
                    val inf = info
                    info = null
                    val title = if (inf.contains("\",")) inf.substringAfterLast("\",") else inf.substringAfterLast(',')
                    if (title.contains("[Geo-blocked]") || title.contains("[Not 24/7]")) continue
                    if (!line.startsWith("http://") && !line.startsWith("https://")) continue
                    fun attr(name: String) = Regex("""$name="([^"]*)"""").find(inf)?.groupValues?.get(1)?.ifBlank { null }
                    val quality = Regex("""\((\d{3,4}[pi])\)""").find(title)?.groupValues?.get(1)
                    val name = title.replace(Regex("""\s*\(\d{3,4}[pi]\)|\s*\[[^]]*]"""), "").trim()
                    val id = attr("tvg-id")?.substringBefore('@') ?: continue
                    val stream = Stream(line, name, quality, referrer ?: attr("http-referrer"), userAgent ?: attr("http-user-agent"))
                    out += M3uEntry(id, name, attr("tvg-logo"), attr("group-title")?.split(';').orEmpty(), stream)
                }
            }
        }
        return out
    }

    // ---- Helpers ----

    private fun normalize(s: String) = s.lowercase().replace(Regex("""[\s\-_.·]+"""), "")

    private fun JsonNode.text(field: String): String? =
        this[field]?.takeIf { !it.isNull && !it.isContainerNode }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonNode.strings(field: String): List<String> =
        this[field]?.takeIf { it.isArray }?.mapNotNull { it.asText()?.trim()?.ifEmpty { null } }.orEmpty()

    companion object {
        private const val API = "https://iptv-org.github.io/api"
        private const val IPTV = "https://iptv-org.github.io/iptv"
        private const val LOGO_MIRROR = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/livetv-logos"
        private const val PAGE_SIZE = 40
        private const val MAX_SEARCH = 100
        private const val CACHE_TTL = 30 * 60 * 1000L
        private const val CHECK_TIMEOUT_MS = 6_000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

        private val COUNTRIES = listOf("CN", "HK", "TW", "SG", "MY", "JP", "KR", "ID", "VN", "TH", "PH", "KH", "MM", "LA", "US", "UK", "CA", "AU", "NZ", "IN")
        private val CATEGORIES = listOf("news", "sports", "movies", "kids", "music", "documentary")

        private val catalogLock = Mutex()
        @Volatile private var cache: Catalog? = null

        /** Name order with numbers compared by value (CCTV-2 before CCTV-10). */
        private val NATURAL = Comparator<String> { a, b ->
            val ra = Regex("""\d+|\D+""").findAll(a.lowercase()).map { it.value }.toList()
            val rb = Regex("""\d+|\D+""").findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(ra.size, rb.size)) {
                val x = ra[i]
                val y = rb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) {
                    x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 }
                        ?: x.trimStart('0').compareTo(y.trimStart('0'))
                } else x.compareTo(y)
                if (c != 0) return@Comparator c
            }
            ra.size.compareTo(rb.size)
        }
    }
}
