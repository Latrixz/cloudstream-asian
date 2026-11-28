package com.dramatip

import android.util.Log
import com.csasian.shared.CsBackup
import com.csasian.shared.CsBrowse
import com.csasian.shared.CsCategories
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
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * DramaTip (dramatip.net) lists vertical short dramas from about 90 apps, every episode free to
 * guests. It's a Next.js site; its pages carry everything we need:
 *  - listings (/category/<genre>, /source/<app>, /search?q=) are server-rendered <article> cards;
 *  - a title page (/series/<slug>) has JSON-LD (title, synopsis, poster) and, in the React flight
 *    data, the bookId, firstSerial and the episode list;
 *  - an episode page (/series/<slug>/episode-<n>) has "sourceFirst": the app's name and a chain of
 *    stream links, each {"enc": base64(iv[12] + AES-256-GCM ciphertext)} under a key the site's
 *    player ships, plus an optional subtitle in the same form.
 * Cached pages can hold expired signed links, so a link is checked first and, when it fails,
 * /api/episode-source/<bookId>/<serial>?refresh=1 gives fresh ones (that call wants a browser
 * User-Agent and a Referer of the episode page).
 * Left out: ShortPeak and QLAX (adult-toy catalogues), and streams the apps encrypt themselves
 * (ShortTV/JoyReels "hls-enc", IDrama keys, DramaBox/StoReel ".encrypt.mp4"), plus anything that only
 * plays through the site's third-party proxies (shortdizilab.com, dramadizilerim).
 */
class DramatipProvider : MainAPI() {
    override var mainUrl = "https://dramatip.net"
    override var name = "DramaTip Shorts"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    private val mapper = ObjectMapper()
    private val streamKey by lazy { base64(STREAM_KEY_B64) }

    /**
     * Category browsing (shared/CsBrowse.kt): two sections, "Genres" (the site's genres, /category/<slug>)
     * and "Apps" (/source/<app>); with none chosen, both list every title (/series). The site has no sorts,
     * and its New Releases is all Vigloo at the moment (hidden: it doesn't play here), so it isn't the default. Apps whose titles we can't play are left out. A search in a
     * section is the site's search (it can't be narrowed to a genre or app).
     */
    private val browse = CsBrowse(
        this, name,
        listOf(
            CsBrowse.Section(
                GENRES_SECTION, "Genres", english = "By genre", glyph = "🎭",
                filters = listOf(CsCategories.Filter(GENRE, "Genre", GENRES.map { (slug, label) -> CsCategories.Option(slug, label) })),
            ),
            CsBrowse.Section(
                APPS_SECTION, "Apps", english = "By app", glyph = "📱",
                filters = listOf(CsCategories.Filter(APP, "App", APPS.map { (slug, label) -> CsCategories.Option(slug, label) })),
            ),
        ),
    ) { id, filters, query, page ->
        if (query.isNotBlank()) return@CsBrowse (if (page == 1) searchOwn(query) else emptyList()) to false
        val path = (if (id == GENRES_SECTION) filters[GENRE]?.let { "/category/$it" } else filters[APP]?.let { "/source/$it" })
            ?: "/series"
        listing(path, page).let { (cards, hasNext) -> cards.map { it.toSearchResult() } to hasNext }
    }

    // Categories SkyStream Dev shows itself (shared/CsCategories.kt); the engine finds these by name.
    fun csCategories(): String = browse.categoriesJson()

    fun csCategoryPage(categoryId: String, filtersJson: String, query: String, page: Int): SearchResponseList =
        browse.categoryPage(categoryId, filtersJson, query, page)

    // A few rows only: every row is one page load, and Home got slow with many.
    // Every other app stays searchable.
    // On CloudStream the "分类 Categories" row comes first.
    override val mainPage = browse.mainPage(mainPageOf(
        "/category/trending" to "Trending Now",
        // The site's own New Releases is all Vigloo at the moment (hidden: it doesn't play here),
        // so this row is its /series list, newest first, which has playable titles.
        "/series" to "New Releases",
        "/category/revenge" to "Revenge",
        "/category/romance" to "Romance",
        "/category/mafia" to "Mafia",
        "/source/yourchannel" to "YourChannel",
        "/source/shortwave" to "ShortWave",
        "/source/mydramawave" to "MyDramaWave",
        "/source/flickreels" to "FlickReels",
        "/source/rivoshort" to "RivoShort",
        "/source/cinerrashort" to "CinerraShort",
        "/source/loopshort" to "LoopShort",
    ))

    private data class Card(val slug: String, val title: String, val cover: String?)

    // ---- listings ----

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        browse.rowPage(request) ?: com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        val (cards, hasNext) = listing(request.data, page)
        Log.i(TAG, "row '${request.name}' page $page: ${cards.size} cards, next=$hasNext")
        return newHomePageResponse(request.name, cards.map { it.toSearchResult() }, hasNext)
    }

    /** Page [page] of a listing ("/category/<genre>", "/source/<app>", "/series") and whether another follows. */
    private suspend fun listing(path: String, page: Int): Pair<List<Card>, Boolean> {
        val html = page(mainUrl + if (page > 1) "$path/page/$page" else path)
        return cards(html) to html.contains("$path/page/${page + 1}\"")
    }

    /**
     * The site's search is loose (any word, even a nonsense query gets suggestions), so this reads
     * the first pages and keeps titles that contain every word of the query.
     */
    // Also searches the title in English when it was typed in another language (shared/CsSearch.kt).
    override suspend fun search(query: String): List<SearchResponse> =
        CsSearch.withExtra(query, 1, "en") { q, _ -> searchOwn(q) to false }.first

    private suspend fun searchOwn(query: String): List<SearchResponse> {
        val words = normalize(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val pages = coroutineScope {
            (1..SEARCH_PAGES).map { n ->
                async {
                    withTimeoutOrNull(20_000L) {
                        runCatching { cards(page("$mainUrl/search?q=${enc(query)}&page=$n")) }
                            .onFailure { Log.w(TAG, "search '$query' page $n: ${it.message}") }
                            .getOrNull()
                    }.orEmpty()
                }
            }.awaitAll()
        }
        val all = pages.flatten().distinctBy { it.slug }
        val phrase = words.joinToString(" ")
        val hits = all.filter { c -> normalize(c.title).let { t -> words.all { w -> Regex("""\b${Regex.escape(w)}""").containsMatchIn(t) } } }
            .sortedWith(compareBy({ normalize(it.title) != phrase }, { !normalize(it.title).contains(phrase) }))
            // Several white-label apps carry the same show under the same name; one is enough.
            .distinctBy { normalize(it.title) }
        Log.i(TAG, "search '$query': ${hits.size} of ${all.size} cards match")
        return hits.map { it.toSearchResult() }
    }

    /** The <article> cards of a listing page, minus adult titles and apps we can't play. */
    private fun cards(html: String): List<Card> = Jsoup.parse(html).select("article").mapNotNull { art ->
        val a = art.selectFirst("a[href^=/series/]") ?: return@mapNotNull null
        val slug = a.attr("href").removePrefix("/series/").substringBefore('?').substringBefore('/')
        if (slug.isBlank()) return@mapNotNull null
        val img: org.jsoup.nodes.Element? = a.selectFirst("img")
        val heading: String? = art.selectFirst("h2, h3")?.text()
        val alt: String? = img?.attr("alt")
        val title: String = (heading?.takeIf { it.isNotBlank() } ?: alt).orEmpty().trim()
        if (title.isEmpty() || isAdult(title)) return@mapNotNull null
        val cover: String? = if (img == null) null else pickImage(img.attr("srcset"), img.attr("src"))
        if (cover != null && hostOf(cover)?.let { h -> HIDDEN_COVER_HOSTS.any { h == it || h.endsWith(".$it") } } == true) {
            return@mapNotNull null
        }
        Card(slug, title, cover)
    }.distinctBy { it.slug }

    /** The 384px picture of a srcset (the plain src is 3840px wide). */
    private fun pickImage(srcset: String, src: String): String? {
        val options = srcset.split(", ").mapNotNull { part ->
            val bits = part.trim().split(' ')
            val w = bits.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: return@mapNotNull null
            bits[0] to w
        }
        return (options.filter { it.second >= 384 }.minByOrNull { it.second } ?: options.maxByOrNull { it.second })?.first
            ?: src.ifBlank { null }
    }

    private fun Card.toSearchResult(): SearchResponse =
        newTvSeriesSearchResponse(title, "$mainUrl/series/$slug", typeOf(title, slug)) { posterUrl = cover }

    // ---- title page ----

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
        val slug = Regex("""/series/([^/?#]+)""").find(url)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Not a DramaTip title link")
        val pageUrl = "$mainUrl/series/$slug"
        val html = page(pageUrl)
        val doc = Jsoup.parse(html)
        val fl = flight(html)

        val ld = doc.select("script[type=application/ld+json]").asSequence()
            .mapNotNull { runCatching { mapper.readTree(it.data()) }.getOrNull() }
            .flatMap { root -> (root.path("@graph").takeIf { it.isArray }?.toList() ?: listOf(root)).asSequence() }
            .firstOrNull { it.text("@type") == "TVSeries" }
        val title = ld?.text("name")
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBeforeLast(" | DramaTip")
            ?: throw ErrorLoadingException("Couldn't read this DramaTip title")
        val plot = ld?.text("description") ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        val poster = ld?.text("image") ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val bookId = Regex(""""bookId":"([^"]+)"""").find(fl)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("This DramaTip title has no episodes")
        val firstSerial = Regex(""""firstSerial":(\d+)""").find(fl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val source = jsonAt(fl, "\"sourceFirst\":")
        val app = source?.text("source")
        if (isBanned(app, poster, source)) throw ErrorLoadingException("This title isn't available")
        if (isAdult(title)) throw ErrorLoadingException("This title isn't available")

        var serials = jsonAt(fl, "\"episodes\":")?.takeIf { it.isArray }
            ?.mapNotNull { it.path("serial_number").takeIf { n -> n.isInt }?.asInt() }
            ?.distinct()?.sorted().orEmpty()
        if (serials.isEmpty()) {
            val count = ld?.path("numberOfEpisodes")?.asInt(0) ?: 0
            serials = (firstSerial until firstSerial + maxOf(count, 1)).toList()
        }
        val locked = Regex(""""is_locked":true""").findAll(fl).count()
        Log.i(TAG, "detail '$title' ($slug, book $bookId, ${app ?: "?"}, ${source?.path("chain")?.firstOrNull()?.text("type") ?: "no chain"}): " +
            "${serials.size} episodes, $locked marked locked")

        val episodes = serials.map { serial ->
            val n = serial - firstSerial + 1
            newEpisode("$pageUrl/episode-$n#b=${enc(bookId)}&s=$serial") {
                name = "Episode $n"
                episode = n
            }
        }
        return newTvSeriesLoadResponse(title, pageUrl, typeOf(title, slug), episodes) {
            posterUrl = poster
            this.plot = plot
            tags = listOfNotNull(app, "Short drama")
        }
    }

    // ---- links ----

    private data class Stream(val url: String, val type: String, val app: String?, val stale: Boolean)

    private suspend fun loadOwnLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data = https://dramatip.net/series/<slug>/episode-<n>#b=<bookId>&s=<serial>
        val episodeUrl = data.substringBefore('#')
        val params = data.substringAfter('#', "").split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        val bookId = params["b"]
        val serial = params["s"]?.toIntOrNull() ?: Regex("""episode-(\d+)""").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull()

        // 1. The episode page's own chain.
        var descriptor = runCatching {
            val fl = flight(page(episodeUrl))
            jsonAt(fl, "\"sourceFirst\":")?.takeIf { it.isObject }?.let { it to fl }
        }.onFailure { Log.w(TAG, "episode page $episodeUrl: ${it.message}") }.getOrNull()
        var good = descriptor?.let { (d, fl) -> working(streams(d, fl, fromPage = true)) }.orEmpty()
        var subtitleNode = descriptor?.first?.path("subtitle")

        // 2. Fresh links from the API when the page had none that work (expired signatures etc.).
        if (good.isEmpty() && bookId != null && serial != null) {
            Log.i(TAG, "$episodeUrl: no working link on the page, asking for fresh ones")
            val fresh = runCatching { refreshed(bookId, serial, episodeUrl) }
                .onFailure { Log.w(TAG, "episode-source $bookId/$serial: ${it.message}") }.getOrNull()
            if (fresh != null) {
                good = working(streams(fresh, "", fromPage = false))
                if (subtitleNode?.isObject != true) subtitleNode = fresh.path("subtitle")
            }
        }

        good.forEachIndexed { i, s ->
            val linkName = buildString {
                append(name)
                s.app?.let { append(' ').append(it) }
                if (good.size > 1) append(" #").append(i + 1)
            }
            callback(newExtractorLink(name, linkName, s.url, if (s.type == "m3u8") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                quality = qualityOf(s.url)
                headers = mediaHeaders(s.url)
                mediaHeaders(s.url)["Referer"]?.let { referer = it }
            })
        }

        // One subtitle per episode at most; English is what the site picks for the English catalogue.
        subtitleNode?.takeIf { it.isObject }?.let { sub ->
            val url = sub.text("enc")?.let { decrypt(it) }
            if (url != null) {
                val code = sub.text("language").orEmpty()
                subtitleCallback(newSubtitleFile(languageName(code) ?: code.ifBlank { "English" }, url))
            }
        }
        Log.i(TAG, "links $episodeUrl: ${good.size} working (${good.joinToString { it.type + " " + (hostOf(it.url) ?: "?") }})")
        return good.isNotEmpty()
    }

    /** The playable entries of a descriptor's chain, decrypted. [fl] resolves "$xx" text references. */
    private fun streams(descriptor: JsonNode, fl: String, fromPage: Boolean): List<Stream> {
        val app = descriptor.text("source")
        val now = System.currentTimeMillis() / 1000
        return descriptor.path("chain").toList().mapNotNull { c ->
            val type = c.text("type").orEmpty()
            val encValue = c.text("enc") ?: return@mapNotNull null
            val enc = if (encValue.startsWith("$")) textRow(fl, encValue.substring(1)) else encValue
            val url = enc?.let { decrypt(it) } ?: return@mapNotNull null
            val why = skipReason(type, url, app)
            if (why != null) {
                Log.i(TAG, "skipping ${app ?: "?"} $type link on ${hostOf(url)}: $why")
                return@mapNotNull null
            }
            val after = c.path("refreshAfter").takeIf { it.isNumber }?.asLong()
            // Only the page's (cached) links go stale; fresh ones from the API are used as they come.
            Stream(url, type, app, fromPage && c.path("refresh").asBoolean(false) && after != null && now >= after)
        }
    }

    private fun skipReason(type: String, url: String, app: String?): String? {
        val host = hostOf(url) ?: return "bad link"
        val a = app.orEmpty().lowercase()
        return when {
            type in SKIPPED_TYPES -> "encrypted by the app"
            a in BANNED_APPS || host.endsWith("hls-store.b-cdn.net") -> "not carried"
            a in ENCRYPTED_APPS -> "encrypted by the app"
            url.substringBefore('?').contains(".encrypt.", true) -> "encrypted by the app"
            type == "dramapops-mp4" -> "encrypted by the app"
            PROXY_HOSTS.any { host == it || host.endsWith(".$it") } -> "third-party proxy"
            else -> null
        }
    }

    /** Checks the links (all at once) and keeps those that answer with a playlist or a video. */
    private suspend fun working(streams: List<Stream>): List<Stream> = coroutineScope {
        streams.map { s ->
            async {
                if (s.stale) { Log.i(TAG, "${s.app} link on ${hostOf(s.url)} has expired"); return@async null }
                val kind = withTimeoutOrNull(CHECK_TIMEOUT_MS) { check(s.url) }
                if (kind == null) Log.i(TAG, "${s.app} ${s.type} link on ${hostOf(s.url)} doesn't answer")
                kind?.let { s.copy(type = it) }
            }
        }.awaitAll().filterNotNull()
    }

    /** "m3u8", "video", or null when the link doesn't work. */
    private suspend fun check(url: String): String? = runCatching {
        val res = app.get(url, headers = mediaHeaders(url) + mapOf("Range" to "bytes=0-2047"), timeout = 10)
        res.body.byteStream().use { input ->
            if (res.code !in 200..299) {
                Log.i(TAG, "check ${hostOf(url)}: HTTP ${res.code}")
                return@runCatching null
            }
            val buf = ByteArray(512)
            val n = input.read(buf).coerceAtLeast(0)
            val head = String(buf, 0, n, Charsets.ISO_8859_1).trimStart('﻿', ' ', '\n', '\r')
            when {
                head.startsWith("#EXTM3U") -> "m3u8"
                head.startsWith("<") -> null // an error page
                n > 0 -> "video"
                else -> null
            }
        }
    }.onFailure { Log.i(TAG, "check ${hostOf(url)}: ${it.message}") }.getOrNull()

    /** Fresh links. Cloudflare lets this through for some browser User-Agents only, so try two. */
    private suspend fun refreshed(bookId: String, serial: Int, episodeUrl: String): JsonNode? {
        var code = 0
        for (ua in listOf(BROWSER_UA, MOBILE_UA)) {
            val res = app.get(
                "$mainUrl/api/episode-source/${enc(bookId)}/$serial?lang=en&refresh=1",
                headers = mapOf("User-Agent" to ua, "Referer" to episodeUrl, "Accept" to "application/json"),
                timeout = 30,
            )
            code = res.code
            if (res.isSuccessful) return mapper.readTree(res.text).path("descriptor").takeIf { it.isObject }
            Log.w(TAG, "episode-source $bookId/$serial: HTTP $code")
        }
        throw ErrorLoadingException("DramaTip replied $code")
    }

    /** The site's player proxy on b-cdn wants a DramaTip Referer; the apps' own CDNs want none. */
    private fun mediaHeaders(url: String): Map<String, String> =
        if (hostOf(url)?.endsWith(".b-cdn.net") == true) mapOf("User-Agent" to BROWSER_UA, "Referer" to "$mainUrl/")
        else mapOf("User-Agent" to BROWSER_UA)

    // ---- decryption ----

    /** base64(iv[12] + AES-256-GCM ciphertext + tag) under the key in the site's player script. */
    private fun decrypt(enc: String): String? = try {
        val raw = base64(enc)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(streamKey, "AES"), GCMParameterSpec(128, raw, 0, 12))
        String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    } catch (e: Throwable) {
        Log.e(TAG, "Couldn't decrypt a DramaTip stream link (has the site changed its key?): ${e.javaClass.simpleName} ${e.message}")
        null
    }

    /** Plain base64 (android.util.Base64 isn't there on SkyStream, java.util.Base64 needs Android 8). */
    private fun base64(s: String): ByteArray {
        val clean = s.filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '-' || it == '_' }
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (ch in clean) {
            val v = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '+', '-' -> 62
                else -> 63
            }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }

    // ---- page data ----

    private suspend fun page(url: String): String {
        // Pages answer okhttp's User-Agent; some browser ones get a Cloudflare challenge.
        val res = app.get(url, headers = mapOf("User-Agent" to PAGE_UA), timeout = 30)
        if (!res.isSuccessful) throw ErrorLoadingException("DramaTip replied ${res.code}")
        return res.text
    }

    /** The React flight data: every self.__next_f.push([1,"..."]) string, unescaped and joined. */
    private fun flight(html: String): String {
        val out = StringBuilder()
        var i = html.indexOf(FLIGHT)
        while (i >= 0) {
            val start = i + FLIGHT.length
            var j = start
            while (j < html.length) {
                val c = html[j]
                if (c == '\\') j += 2 else if (c == '"') break else j++
            }
            val raw = html.substring(start, minOf(j, html.length))
            runCatching { out.append(mapper.readValue("\"$raw\"", String::class.java)) }
            i = html.indexOf(FLIGHT, j)
        }
        return out.toString()
    }

    /** The JSON value right after [key] in the flight data. */
    private fun jsonAt(fl: String, key: String): JsonNode? {
        val i = fl.indexOf(key)
        if (i < 0) return null
        return runCatching {
            mapper.createParser(fl.substring(i + key.length)).use { p -> p.readValueAsTree<JsonNode>() }
        }.getOrNull()?.takeUnless { it.isNull || it.isMissingNode }
    }

    /** A text row ("5a:T494,<text>") of the flight data, which long strings point at as "$5a". */
    private fun textRow(fl: String, id: String): String? {
        val m = Regex("""(?<![0-9a-zA-Z])${Regex.escape(id)}:T([0-9a-fA-F]+),""").find(fl) ?: return null
        val len = m.groupValues[1].toInt(16)
        val start = m.range.last + 1
        return fl.substring(start, minOf(fl.length, start + len))
    }

    // ---- helpers ----

    private fun isBanned(app: String?, poster: String?, source: JsonNode?): Boolean {
        if (app?.lowercase() in BANNED_APPS) return true
        val host = poster?.let { hostOf(it) }
        if (host != null && BANNED_COVER_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        return source?.path("chain")?.any { c -> c.text("enc")?.let { decrypt(it) }?.let { hostOf(it) }?.endsWith("hls-store.b-cdn.net") == true } == true
    }

    private fun typeOf(title: String, slug: String): TvType =
        if (DUBBED.containsMatchIn(title) || slug.contains("-dubbed")) TvType.AsianDrama else TvType.TvSeries

    private fun qualityOf(url: String): Int =
        Regex("""(?:/|_|-)(2160|1080|720|540|480|360)(?:p|/|_|\.)""", RegexOption.IGNORE_CASE).find(url)
            ?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value

    /** Only clearly explicit words in the title; synopses aren't looked at. */
    private fun isAdult(title: String): Boolean = ADULT.containsMatchIn(title)

    private fun normalize(s: String): String = s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    private fun languageName(code: String): String? = when (code.lowercase().substringBefore('-')) {
        "en" -> "English"; "es" -> "Spanish"; "pt" -> "Portuguese"; "id" -> "Indonesian"; "fr" -> "French"
        "de" -> "German"; "it" -> "Italian"; "ja" -> "Japanese"; "ko" -> "Korean"; "zh" -> "Chinese"
        "th" -> "Thai"; "vi" -> "Vietnamese"; "ar" -> "Arabic"; "tr" -> "Turkish"; "ru" -> "Russian"
        else -> null
    }

    private fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun JsonNode.text(field: String): String? =
        path(field).takeIf { it.isTextual || it.isNumber }?.asText()?.trim()?.ifBlank { null }

    companion object {
        private const val TAG = "Dramatip"
        private const val FLIGHT = "self.__next_f.push([1,\""
        private const val SEARCH_PAGES = 3

        private const val GENRES_SECTION = "genres"
        private const val APPS_SECTION = "apps"
        private const val GENRE = "genre"
        private const val APP = "app"

        /** The site's genres (/categories), slug to name. */
        private val GENRES = listOf(
            "new-releases" to "New Releases",
            "popular" to "Most Watched", "revenge" to "Revenge & Payback", "rise-comeback" to "Rise & Comeback",
            "hidden-identity" to "Hidden Identity", "second-chance" to "Second Chance & Rebirth",
            "romance" to "Romance & Sweet Love", "enemies-to-lovers" to "Enemies to Lovers",
            "marriage-love" to "Marriage & Love", "ceo-billionaire" to "CEO & Billionaire",
            "rich-family" to "Rich Family & Elite", "strong-women" to "Strong Women",
            "family-drama" to "Family Drama", "modern-drama" to "Modern Drama",
            "historical" to "Historical & Period", "fantasy" to "Fantasy & Supernatural",
            "powerful-hero" to "Powerful Hero", "adored" to "Adored & Pampered", "anime" to "Anime",
            "trending" to "Trending Now", "mafia" to "Mafia", "regret" to "Regret & Too Late",
            "mystery-suspense" to "Mystery & Suspense", "dark-romance" to "Dark Romance",
            "divorce-remarriage" to "Divorce & Remarriage", "reunion" to "Reunion",
            "youth-campus" to "Youth & Campus", "pregnancy-babies" to "Pregnancy & Babies",
            "harem-polyamory" to "Harem & Polyamory", "system" to "System", "bl-lgbtq" to "BL & LGBTQ+",
        )

        /**
         * The site's apps (/source/<slug>), slug to name, checked 2026-11-27. Left out: ShortPeak and QLAX,
         * the apps that encrypt their streams (IDrama, DramaBox, ShortTV, JoyReels, StoReel), and those whose
         * titles we hide because nothing of theirs plays here (Vigloo, PetaDrama, StoryReel, Toonory,
         * ReelNova, ReelSolis, Flareshort, HiviDrama).
         */
        private val APPS = listOf(
            "anyreel" to "AnyReel", "aura" to "AuraReel", "bibishort" to "BibiShort", "bilitv" to "BiliTV",
            "blazeflick" to "BlazeFlick", "blinkdrama" to "BlinkDrama", "cinerrashort" to "CinerraShort",
            "cinerratv" to "CinerraTV", "byte" to "CrispClip", "dotdrama" to "DotDrama", "dramabite" to "DramaBite",
            "dramabluff" to "DramaBluff", "hive" to "DramaHive", "fictifify" to "Fictifify",
            "flareflow" to "FlareFlow", "flickreels" to "FlickReels", "fliksotv" to "FliksoTV",
            "freereels" to "FreeReels", "goodshort" to "GoodShort", "hahip" to "HaHip", "happyshort" to "HappyShort",
            "joyshort" to "JoyShort", "lix" to "Kaelixs", "kalostv" to "KalosTV", "kynesttv" to "KynestTV",
            "livoshort" to "LivoShort", "loopshort" to "LoopShort", "lumiloreqj" to "LumiLoreQj",
            "lunoryqj" to "Lunoryqj", "miraluneqj" to "MiraLuneQj", "miraluneshort" to "MiraLuneShort",
            "moboreels" to "MoboReels", "moreshort" to "MoreShort", "mydrama" to "MyDrama",
            "mydramawave" to "MyDramaWave", "myla" to "MylaTV", "mymuse" to "MyMuse", "netshort" to "NetShort",
            "tread" to "NovaFlick", "novalyratv" to "NovalyraTV", "noviboo" to "Noviboo", "noxtaleda" to "Noxtaleda",
            "pagejoytv" to "PageJoyTV", "pinedrama" to "PineDrama", "playlet" to "Playlet", "ploryxtv" to "PloRyxTV",
            "plotify" to "PlotifyTV", "plotsnax" to "PlotSnax", "plotzaqj" to "PlotZaQj", "popiboo" to "PopiBoo",
            "popmeloqj" to "PopMeloQj", "popnovava" to "PopNovava", "quickeltv" to "QuickelTV", "reelai" to "ReelAI",
            "reelshort" to "ReelShort", "rivoshort" to "RivoShort", "rovashort" to "RovaShort", "play" to "SanpPlay",
            "shortical" to "Shortical", "shortwave" to "ShortWave", "sixthshort" to "SixthShort",
            "sodatv" to "SodaTV", "stardust" to "Stardust", "drame" to "StarReel", "swoopreels" to "SwoopReels",
            "toonscroll" to "ToonScroll", "toonshort" to "ToonShort", "toptaletv" to "ToptaleTV",
            "versevoyagetv" to "VersevoyageTV", "bf86d973" to "VibeOFlix", "virexotv" to "VirexoTV",
            "viscame" to "VisCame", "vivi" to "Vividra", "vyntagetv" to "VyntageTV", "winknoveltv" to "WinkNovelTV",
            "yourchannel" to "YourChannel", "va" to "Zenva", "ziptaletv" to "ZiptaleTV",
        )
        private const val CHECK_TIMEOUT_MS = 10_000L
        private const val PAGE_UA = "okhttp/4.12.0"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        /** AES-256 key (base64) for the "enc" links, from the site's player script. */
        private const val STREAM_KEY_B64 = "QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc="

        /** Adult-toy style catalogues: never shown. */
        private val BANNED_APPS = setOf("shortpeak", "qlax")
        private val BANNED_COVER_HOSTS = listOf("qlax.ai", "shortpeak.biz")

        /** Apps whose streams are encrypted by the app itself. */
        private val ENCRYPTED_APPS = setOf("idrama", "dramabox", "shorttv", "joyreels")
        private val SKIPPED_TYPES = setOf("hls-enc")

        /** The site's third-party proxies, which we don't go through. */
        private val PROXY_HOSTS = listOf("shortdizilab.com", "dramadizilerim.xyz", "dramadizilerim.com")

        /**
         * Cards hidden from rows and search, by their cover's host, because nothing of theirs plays
         * here (checked 2026-11-27): the banned apps, IDrama, DramaBox, ShortTV and JoyReels (encrypted),
         * Vigloo and PetaDrama (only through a third-party proxy), StoryReel, Toonory, ReelNova and
         * ReelSolis (their CDNs refuse us), HiviDrama (expired certificate).
         */
        private val HIDDEN_COVER_HOSTS = BANNED_COVER_HOSTS + listOf(
            "idrama.video", "dramaboxdb.com", "shorttv.live", "blinkdrama.life", "joyreels.com",
            "vigloo.com", "petadrama.com", "vibeshort.live", "toonory.com", "veryshortvideos.com", "serivibe.com",
        )

        private val DUBBED = Regex("""\bdubbed\b""", RegexOption.IGNORE_CASE)
        private val ADULT = Regex(
            """18\+|\bnsfw\b|erotic|porn|\bxxx\b|hentai|\bnude\b|\bnudity\b|\bnaked\b|\bbdsm\b""",
            RegexOption.IGNORE_CASE,
        )
    }
}
