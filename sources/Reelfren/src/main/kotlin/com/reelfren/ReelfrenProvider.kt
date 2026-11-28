package com.reelfren

import android.util.Log
import com.csasian.shared.CsBackup
import com.csasian.shared.CsBrowse
import com.csasian.shared.CsLogos
import com.csasian.shared.CsSearch
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/**
 * ReelFren (reelfren.net) gathers vertical short dramas from many apps (ReelShort, DramaBox,
 * ShortMax, NetShort, Melolo, DramaWave, FlexTV, Vigloo, MovieBox Shorts and more). Its web pages sit
 * behind a Cloudflare challenge, but the JSON API at api.reelfren.net doesn't, and needs no cookies,
 * Referer or signing:
 *  - /api/home?lang=en[&provider=<app>][&offset=N] -> {data: [{id, provider, title, cover, intro, episodes}], offset}
 *    Without a provider it's the mixed home feed (about 2000 titles on the first page); with one,
 *    20-200 titles of that app.
 *  - /api/video?provider=<app>&id=<id>&ep=<n>&lang=en&server=1|2&cv=v21
 *    -> {videoUrl, qualityList[{label, url, format, meloloKey?}], subtitles[{label, srclang, url}], locked, totalEpisodes}
 *    Paths starting with /api/ are relative to api.reelfren.net. Signed URLs expire, so links are
 *    always fetched when an episode is played.
 * There's no usable detail or search endpoint (both are Cloudflare-gated pages), so a title's page is
 * built from its feed card, which travels in our URL, and search filters the feeds.
 * iQIYI and WeTV rows are left out, as is adult content.
 */
class ReelfrenProvider : MainAPI() {
    override var mainUrl = "https://reelfren.net"
    override var name = "ReelFren Shorts"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    private val mapper = ObjectMapper()
    private val headers = mapOf("User-Agent" to "okhttp/4.12.0", "Accept" to "application/json")

    /** An app, as shown in its home row; some apps have a second feed ("reelshort2"). */
    private data class App(val label: String, val feeds: List<String>, val row: Boolean = true)

    private val apps = listOf(
        App("ReelShort", listOf("reelshort", "reelshort2")),
        App("DramaBox", listOf("dramabox")),
        App("ShortMax", listOf("shortmax")),
        App("NetShort", listOf("netshort")),
        App("DramaWave", listOf("dramawave")),
        App("FlexTV", listOf("flextv", "flextv2")),
        App("Vigloo", listOf("vigloo2")),
        App("MovieBox Shorts", listOf("movieboxshorts")),
        // Every other app on ReelFren that plays (checked 2026-11-27; ReelyGo and CandyJar were down).
        // Only the biggest get a home row (many rows make Home slow); all of them are searchable.
        App("StoryReel", listOf("storyreel")),
        App("VibeShort", listOf("vibeshort")),
        App("BlinkDrama", listOf("blinkdrama")),
        App("ToonShort", listOf("toonshort")),
        App("StardustTV", listOf("stardusttv"), row = false),
        App("Shorten", listOf("shorten"), row = false),
        App("MiniShort", listOf("minishort"), row = false),
        App("RaptDrama", listOf("raptdrama"), row = false),
        App("Sereal", listOf("sereal"), row = false),
        App("CubeTV", listOf("cubetv"), row = false),
        App("KalosTV", listOf("kalostv"), row = false),
        App("MyMuse", listOf("mymuse"), row = false),
        App("FlareFlow", listOf("flareflow"), row = false),
        App("Shortical", listOf("shortical"), row = false),
        App("JoyReels", listOf("joyreels"), row = false),
        App("TallFlix", listOf("tallflix"), row = false),
        App("MomeShort", listOf("momeshort"), row = false),
        App("ReelAI", listOf("reelai"), row = false),
        App("SixthShort", listOf("sixthshort"), row = false),
        App("SarosTV", listOf("sarostv"), row = false),
        App("My Drama", listOf("mydrama"), row = false),
        App("WinkNovel", listOf("winknovel"), row = false),
        App("BonusTV", listOf("bonustv"), row = false),
        App("MoboReels", listOf("moboreels"), row = false),
        App("HappyShort", listOf("happyshort"), row = false),
        App("Storeel", listOf("storeel"), row = false),
        App("MoreShort", listOf("moreshort"), row = false),
        App("RapidTV", listOf("rapidtv","rapidtv2"), row = false),
        App("DramaNova", listOf("dramanova"), row = false),
        App("LoveShots", listOf("loveshots"), row = false),
        App("Anamana", listOf("anamana"), row = false),
        App("PlayLet", listOf("playlet"), row = false),
        App("ReelLife", listOf("reelife"), row = false),
        App("PineDrama", listOf("pinedrama"), row = false),
        App("FreeReels", listOf("freereels"), row = false),
        App("SoReel", listOf("soreel"), row = false),
        App("StoryGo", listOf("storygo"), row = false),
        // Melolo's videos are encrypted; only CloudStream can unlock them (our video interceptor).
        // SkyStream's player can't, so the row is left out there.
    ) + if (CsLogos.onSkyStream) emptyList() else listOf(App("Melolo", listOf("melolo")))

    /**
     * Category browsing (shared/CsBrowse.kt): one section per app (every app above, rows or not), its
     * feed in the site's own order; the API has no filters or sorts. A search in a section reads the
     * app's whole feed and matches titles.
     */
    private val browse = CsBrowse(
        this, name,
        apps.map { CsBrowse.Section(it.feeds.first(), it.label, english = it.label, glyph = "📱") },
    ) { id, _, query, page -> appPage(id, query, page) }

    // On CloudStream the "分类 Categories" row comes first.
    override val mainPage = browse.mainPage(
        listOf(MainPageData("Latest on ReelFren", LATEST)) +
            apps.filter { it.row }.map { MainPageData(it.label, it.feeds.joinToString(",")) },
    )

    // Categories SkyStream Dev shows itself (shared/CsCategories.kt); the engine finds these by name.
    fun csCategories(): String = browse.categoriesJson()

    fun csCategoryPage(categoryId: String, filtersJson: String, query: String, page: Int): SearchResponseList =
        browse.categoryPage(categoryId, filtersJson, query, page)

    // ---- feeds ----

    private data class Card(
        val provider: String,
        val id: String,
        val title: String,
        val cover: String?,
        val intro: String?,
        val episodes: Int,
    ) {
        val key get() = "$provider/$id"
    }

    /** A feed read page by page; cached for [CACHE_MS] so rows, paging and search share it. */
    private class Feed(val provider: String?) {
        val cards = ArrayList<Card>()
        val seen = HashSet<String>()
        var offset = 0
        var done = false
        var fetched = 0L
    }

    private val feeds = HashMap<String, Feed>()

    private fun feed(provider: String?): Feed = synchronized(feeds) {
        val key = provider ?: LATEST
        val existing = feeds[key]
        if (existing != null && System.currentTimeMillis() - existing.fetched < CACHE_MS) existing
        else Feed(provider).also { feeds[key] = it }
    }

    /** Reads the next page of [f]; marks it done when a page brings nothing new. */
    private suspend fun more(f: Feed) {
        if (f.done) return
        val query = buildString {
            append("/api/home?lang=en")
            f.provider?.let { append("&provider=").append(it) }
            if (f.offset > 0) append("&offset=").append(f.offset)
        }
        val root = try {
            api(query)
        } catch (e: Throwable) {
            Log.w(TAG, "feed ${f.provider ?: "home"} offset ${f.offset} failed: ${e.message}")
            f.done = true
            return
        }
        val data = root.path("data").toList()
        var added = 0
        synchronized(f) {
            if (f.fetched == 0L) f.fetched = System.currentTimeMillis()
            for (node in data) {
                val card = node.toCard() ?: continue
                if (f.seen.add(card.key)) { f.cards += card; added++ }
            }
            f.offset += data.size
            if (data.isEmpty() || added == 0) f.done = true
        }
        Log.i(TAG, "feed ${f.provider ?: "home"}: +$added (${data.size} read), ${f.cards.size} cards, next offset ${f.offset}")
    }

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        browse.rowPage(request) ?: com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    /** Page [page] of an app's section: its feed(s) in order, or, for [query], the titles that match. */
    private suspend fun appPage(id: String, query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val app = apps.firstOrNull { it.feeds.first() == id } ?: return emptyList<SearchResponse>() to false
        val sources = app.feeds.map { feed(it) }
        val want = page * CATEGORY_PAGE
        var rounds = 0
        // A search needs the whole feed; a plain page only enough of it.
        while ((query.isNotBlank() || merged(sources).size < want) && sources.any { !it.done } && rounds++ < MAX_ROUNDS) {
            coroutineScope { sources.filter { !it.done }.map { async { more(it) } }.awaitAll() }
        }
        val all = if (query.isBlank()) merged(sources) else matching(merged(sources), query)
        val items = all.drop((page - 1) * CATEGORY_PAGE).take(CATEGORY_PAGE).map { it.toSearchResult() }
        val hasNext = all.size > want || (query.isBlank() && sources.any { !it.done })
        Log.i(TAG, "section ${app.label} '$query' page $page: ${items.size} cards, next=$hasNext")
        return items to hasNext
    }

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        val sources = if (request.data == LATEST) listOf(feed(null)) else request.data.split(',').map { feed(it) }
        val want = page * ROW_PAGE
        var rounds = 0
        while (merged(sources).size < want && sources.any { !it.done } && rounds++ < 6) {
            coroutineScope { sources.filter { !it.done }.map { async { more(it) } }.awaitAll() }
        }
        val all = merged(sources)
        val items = all.drop((page - 1) * ROW_PAGE).take(ROW_PAGE).map { it.toSearchResult() }
        val hasNext = all.size > want || sources.any { !it.done }
        Log.i(TAG, "row '${request.name}' page $page: ${items.size} cards, next=$hasNext")
        return newHomePageResponse(request.name, items, hasNext)
    }

    /** The cards of several feeds, taken in turn (one from each), without duplicates. */
    private fun merged(sources: List<Feed>): List<Card> {
        if (sources.size == 1) return synchronized(sources[0]) { sources[0].cards.toList() }
        val lists = sources.map { synchronized(it) { it.cards.toList() } }
        val out = ArrayList<Card>()
        val seen = HashSet<String>()
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) {
            for (l in lists) l.getOrNull(i)?.let { if (seen.add(normalize(it.title))) out += it }
        }
        return out
    }

    // ---- search ----

    /**
     * There's no search API, so this reads the mixed home feed (two pages) and the first page of
     * every app's own feed, all at once, then matches titles. The feeds stay cached for 10 minutes.
     */
    // Also searches the title in English when it was typed in another language (shared/CsSearch.kt).
    override suspend fun search(query: String): List<SearchResponse> =
        CsSearch.withExtra(query, 1, "en") { q, _ -> searchOwn(q) to false }.first

    private suspend fun searchOwn(query: String): List<SearchResponse> {
        if (normalize(query).isEmpty()) return emptyList()
        val started = System.currentTimeMillis()
        val home = feed(null)
        val appFeeds = (KNOWN_FEEDS + synchronized(home) { home.cards.map { it.provider } }).distinct()
            .filter { it !in SKIPPED }.map { feed(it) }
        withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            coroutineScope {
                val jobs = appFeeds.filter { it.offset == 0 && !it.done }.map { async { more(it) } }
                val homeJob = async {
                    if (home.offset == 0) more(home)
                    if (synchronized(home) { home.cards.size } < 2500 && !home.done) more(home)
                }
                (jobs + homeJob).awaitAll()
            }
        }
        val all = (listOf(home) + appFeeds).flatMap { f -> synchronized(f) { f.cards.toList() } }
            .distinctBy { it.key }
        val hits = matching(all, query).take(MAX_RESULTS)
        Log.i(TAG, "search '$query': ${hits.size} of ${all.size} titles in ${System.currentTimeMillis() - started}ms")
        return hits.map { it.toSearchResult() }
    }

    /** The cards whose title has every word of [query], closest first, without repeats. */
    private fun matching(all: List<Card>, query: String): List<Card> {
        val words = normalize(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val phrase = words.joinToString(" ")
        return all.filter { card -> normalize(card.title).let { t -> words.all { it in t } } }
            .sortedWith(compareBy<Card>(
                { normalize(it.title) != phrase },
                { !normalize(it.title).startsWith(phrase) },
                { !Regex("""\b${Regex.escape(phrase)}\b""").containsMatchIn(normalize(it.title)) },
            ))
            .distinctBy { normalize(it.title) + "|" + it.episodes }
    }

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

    /** Decrypts Melolo's encrypted MP4s (links that carry a key); everything else passes through. */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = ReelfrenCenc.interceptor

    private suspend fun loadPage(url: String): LoadResponse {
        val (provider, id) = parseTitleUrl(url) ?: throw ErrorLoadingException("Not a ReelFren title link")
        val params = fragmentParams(url)
        var card = params["t"]?.let {
            Card(provider, id, it, params["c"], params["i"], params["n"]?.toIntOrNull() ?: 0)
        } ?: findCard(provider, id) ?: throw ErrorLoadingException("Couldn't find this title on ReelFren")

        // The feed's count can be missing (NetShort: 0) or too low (a ShortMax title says 23 but has
        // 130), and the episode reply's total can be wrong the other way (Vigloo, Melolo say 1): use
        // whichever is bigger.
        val total = runCatching { video(provider, id, 1, 1).path("totalEpisodes").asInt(0) }.getOrDefault(0)
        if (total != card.episodes) Log.i(TAG, "'${card.title}' ($provider/$id): feed says ${card.episodes} episodes, ep 1 says $total")
        card = card.copy(episodes = maxOf(card.episodes, total, 1))
        val app = appLabel(provider)
        Log.i(TAG, "detail '${card.title}' ($provider/$id): ${card.episodes} episodes")
        val episodes = (1..card.episodes).map { n ->
            newEpisode(episodeData(provider, id, n)) {
                name = "Episode $n"
                episode = n
            }
        }
        return newTvSeriesLoadResponse(card.title, titleUrl(card), typeOf(card), episodes) {
            posterUrl = card.cover
            plot = card.intro
            tags = listOf(app, "Short drama")
        }
    }

    /** For a link without the card in it (e.g. an old bookmark): look in the app's feed. */
    private suspend fun findCard(provider: String, id: String): Card? {
        val f = feed(provider)
        var rounds = 0
        while (rounds++ < 8) {
            synchronized(f) { f.cards.firstOrNull { it.id == id } }?.let { return it }
            if (f.done) break
            more(f)
        }
        val home = feed(null)
        if (home.offset == 0) more(home)
        return synchronized(home) { home.cards.firstOrNull { it.provider == provider && it.id == id } }
    }

    // ---- links ----

    private suspend fun loadOwnLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data = https://api.reelfren.net/api/video?provider=<app>&id=<id>&ep=<n>
        val q = queryParams(data)
        val provider = q["provider"] ?: return false
        val id = q["id"] ?: return false
        val ep = q["ep"]?.toIntOrNull() ?: return false

        // Server 1 first; server 2 (another CDN for some apps, 502s for others) is the backup.
        val replies = coroutineScope {
            listOf(1, 2).map { server ->
                async {
                    withTimeoutOrNull(if (server == 1) 25_000L else 12_000L) {
                        runCatching { video(provider, id, ep, server) }
                            .onFailure { Log.w(TAG, "video $provider/$id ep $ep server $server: ${it.message}") }
                            .getOrNull()
                    }
                }
            }.awaitAll()
        }

        val seen = HashSet<String>()
        var count = 0
        replies.forEachIndexed { index, reply ->
            if (reply == null) return@forEachIndexed
            val server = index + 1
            if (reply.path("locked").asBoolean(false)) Log.w(TAG, "$provider/$id ep $ep server $server: locked")
            val qualities = ArrayList<JsonNode>(reply.path("qualityList").toList())
            reply.text("videoUrl")?.let { main ->
                if (qualities.none { it.text("url") == main }) {
                    qualities.add(mapper.createObjectNode().put("url", main).put("label", "Default"))
                }
            }
            for ((position, entry) in qualities.withIndex()) {
                val url = absolute(entry.text("url") ?: continue)
                if (!seen.add(url)) continue
                val label = entry.text("label") ?: "Default"
                val type = when (entry.text("format")?.lowercase()) {
                    "hls", "m3u8" -> ExtractorLinkType.M3U8
                    "mp4" -> ExtractorLinkType.VIDEO
                    else -> if (URLDecoder.decode(url, "UTF-8").contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
                val linkName = buildString {
                    append(name).append(' ').append(label)
                    if (qualities.count { (it.text("label") ?: "Default") == label } > 1) append(" #").append(position + 1)
                    if (server > 1) append(" (server 2)")
                }
                val keyHex = entry.text("meloloKey")
                if (provider == "melolo" && keyHex == null) {
                    // Server 2's Melolo files are just as encrypted, but come without a key.
                    Log.i(TAG, "melolo: skipping '$label' from server $server (encrypted, no key)")
                    continue
                }
                val link = if (keyHex != null) {
                    melolLink(linkName, url, keyHex, label) ?: continue
                } else {
                    newExtractorLink(name, linkName, url, type) {
                        quality = qualityOf(label)
                        headers = mapOf("User-Agent" to USER_AGENT)
                    }
                }
                callback(link)
                count++
            }
        }

        // Subtitles from whichever server gave them, English first.
        val subtitles = replies.filterNotNull().map { it.path("subtitles").toList() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        subtitles.mapNotNull { s ->
            val url = s.text("url")?.let { absolute(it) } ?: return@mapNotNull null
            val code = s.text("srclang").orEmpty().lowercase()
            val label = s.text("label")?.takeIf { it.length > 2 } ?: languageName(code) ?: s.text("label") ?: code
            Triple(url, label, code)
        }.distinctBy { it.first }
            .sortedBy { (_, label, code) -> if (code.startsWith("en") || label.contains("english", true)) 0 else 1 }
            .forEach { (url, label, _) -> subtitleCallback(newSubtitleFile(label, url)) }

        Log.i(TAG, "links $provider/$id ep $ep: $count links, ${subtitles.size} subtitles")
        return count > 0
    }

    /**
     * Melolo's MP4s are CENC-encrypted. In CloudStream, the link carries the key and our video
     * interceptor decrypts the file (ClearKey there only works for DASH). SkyStream doesn't run plugin
     * interceptors, so there it's offered as a ClearKey link for the host player to try.
     */
    private suspend fun melolLink(linkName: String, url: String, keyHex: String, label: String): ExtractorLink? {
        val key = ReelfrenCenc.hexToBytes(keyHex)?.takeIf { it.size == 16 } ?: return null
        if (!CsLogos.onSkyStream) {
            return newExtractorLink(name, linkName, ReelfrenCenc.linkUrl(url, keyHex), ExtractorLinkType.VIDEO) {
                quality = qualityOf(label)
                headers = mapOf("User-Agent" to USER_AGENT)
            }
        }
        val head = runCatching {
            app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Range" to "bytes=0-65535")).body.bytes()
        }.getOrNull()
        val kid = head?.let { ReelfrenCenc.defaultKid(it) } ?: run {
            Log.w(TAG, "melolo: no KID in the file header, skipping $label")
            return null
        }
        return newDrmExtractorLink(name, linkName, url, ExtractorLinkType.VIDEO, CLEARKEY_UUID) {
            quality = qualityOf(label)
            headers = mapOf("User-Agent" to USER_AGENT)
            this.kid = base64Url(kid)
            this.key = base64Url(key)
            kty = "oct"
        }
    }

    private suspend fun video(provider: String, id: String, ep: Int, server: Int): JsonNode =
        api("/api/video?provider=${enc(provider)}&id=${enc(id)}&ep=$ep&lang=en&server=$server&cv=v21")

    // ---- helpers ----

    private fun JsonNode.toCard(): Card? {
        val provider = text("provider") ?: return null
        val id = text("id") ?: return null
        val title = text("title") ?: return null
        if (provider in SKIPPED) return null
        if (provider == "melolo" && CsLogos.onSkyStream) return null // can't play there (encrypted)
        val intro = text("intro")?.takeUnless { isFeedLabel(it) }
        if (isAdult(title, intro)) return null
        return Card(provider, id, title, text("cover"), intro, path("episodes").asInt(0))
    }

    private fun Card.toSearchResult(): SearchResponse =
        newTvSeriesSearchResponse(title, titleUrl(this), typeOf(this)) {
            posterUrl = cover
        }

    /** https://reelfren.net/drama/<app>/<id>#t=..&c=..&i=..&n=.. : the card travels with the link. */
    private fun titleUrl(card: Card): String = buildString {
        append(mainUrl).append("/drama/").append(card.provider).append('/').append(enc(card.id))
        append("#t=").append(enc(card.title))
        card.cover?.let { append("&c=").append(enc(it)) }
        card.intro?.let { append("&i=").append(enc(it.take(600))) }
        append("&n=").append(card.episodes)
    }

    private fun parseTitleUrl(url: String): Pair<String, String>? {
        val path = url.substringBefore('#').substringBefore('?')
        val m = Regex("""/drama/([a-z0-9]+)/([^/]+)""").find(path) ?: return null
        val provider = m.groupValues[1]
        var id = URLDecoder.decode(m.groupValues[2], "UTF-8")
        // The site's own links add a slug: /drama/dramabox/42000035891-an-unreliable-guide...
        if (!url.contains("#t=") && id.contains('-')) id = id.substringBefore('-')
        return provider to id
    }

    private fun fragmentParams(url: String): Map<String, String> =
        url.substringAfter('#', "").split('&').mapNotNull { part ->
            val k = part.substringBefore('=', "")
            if (k.isEmpty()) null else k to URLDecoder.decode(part.substringAfter('='), "UTF-8")
        }.toMap()

    private fun queryParams(url: String): Map<String, String> = fragmentParams("#" + url.substringAfter('?', ""))

    private fun episodeData(provider: String, id: String, ep: Int) =
        "$API/api/video?provider=${enc(provider)}&id=${enc(id)}&ep=$ep"

    private fun absolute(url: String) = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> API + url
        else -> url
    }

    private fun appLabel(provider: String): String =
        apps.firstOrNull { provider in it.feeds }?.label ?: APP_NAMES[provider] ?: provider.replaceFirstChar { it.uppercase() }

    /** Dubbed Chinese / Korean productions are Asian dramas; the apps' own English shorts are series. */
    private fun typeOf(card: Card): TvType = when {
        card.provider in ASIAN_APPS -> TvType.AsianDrama
        DUBBED.containsMatchIn(card.title) -> TvType.AsianDrama
        else -> TvType.TvSeries
    }

    private fun qualityOf(label: String): Int =
        Regex("""(\d{3,4})\s*[pP]""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: when {
            label.contains("4k", true) -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }

    /** "NetShort new releases", "Anamana tab 1", "首页-双列推荐-英语": the feed's name, not a synopsis. */
    private fun isFeedLabel(intro: String): Boolean =
        intro.length < 30 && (FEED_LABEL.containsMatchIn(intro) || intro.any { it.code in 0x3000..0x9fff })

    private fun isAdult(title: String, intro: String?): Boolean =
        // Only clearly explicit terms in the title: the apps' romance shorts are mainstream and stay,
        // even when a synopsis mentions BDSM or nudity (e.g. GoodShort's "Spark My Body and Master My Soul").
        ADULT_ANYWHERE.containsMatchIn(title)

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    private fun languageName(code: String): String? = when (code.substringBefore('-')) {
        "en" -> "English"; "es" -> "Spanish"; "pt" -> "Portuguese"; "id" -> "Indonesian"; "fr" -> "French"
        "de" -> "German"; "it" -> "Italian"; "ja" -> "Japanese"; "ko" -> "Korean"; "zh" -> "Chinese"
        "th" -> "Thai"; "vi" -> "Vietnamese"; "ar" -> "Arabic"; "tr" -> "Turkish"; "ru" -> "Russian"
        "pl" -> "Polish"; "hi" -> "Hindi"; "ms" -> "Malay"; "tl", "fil" -> "Filipino"
        else -> null
    }

    /** Unpadded base64url, as ClearKey licences use (java.util.Base64 needs Android 8). */
    private fun base64Url(bytes: ByteArray): String {
        val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val n = minOf(3, bytes.size - i)
            var v = 0
            for (k in 0 until 3) v = (v shl 8) or (if (k < n) bytes[i + k].toInt() and 0xff else 0)
            for (k in 0..n) out.append(abc[(v shr (18 - 6 * k)) and 0x3f])
            i += 3
        }
        return out.toString()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun JsonNode.text(field: String): String? =
        path(field).takeIf { it.isTextual || it.isNumber }?.asText()?.trim()?.ifBlank { null }

    private suspend fun api(path: String): JsonNode {
        val res = app.get(API + path, headers = headers, timeout = 60)
        if (!res.isSuccessful) throw ErrorLoadingException("ReelFren replied ${res.code}")
        return mapper.readTree(res.text)
    }

    companion object {
        private const val TAG = "Reelfren"
        private const val API = "https://api.reelfren.net"
        private const val LATEST = "latest"
        private const val ROW_PAGE = 40
        private const val CATEGORY_PAGE = 30
        private const val MAX_ROUNDS = 10
        private const val CACHE_MS = 10 * 60_000L
        private const val SEARCH_TIMEOUT_MS = 20_000L
        private const val MAX_RESULTS = 80
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
        private val CLEARKEY_UUID: UUID = UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e")

        /** iQIYI / WeTV (broken, and not ours to take), and the first Vigloo feed (its videos 502; vigloo2 works). */
        private val SKIPPED = setOf("iqiyi", "wetv", "vigloo")

        /** Every app feed seen on ReelFren, read for search. */
        private val KNOWN_FEEDS = listOf(
            "reelshort", "reelshort2", "dramabox", "shortmax", "netshort", "dramawave", "flextv", "flextv2",
            "vigloo2", "movieboxshorts", "melolo", "anamana", "blinkdrama", "bonustv", "candyjar", "cubetv",
            "dramanova", "flareflow", "freereels", "happyshort", "joyreels", "kalostv", "loveshots", "minishort",
            "moboreels", "momeshort", "moreshort", "mydrama", "mymuse", "pinedrama", "playlet", "rapidtv",
            "rapidtv2", "raptdrama", "reelai", "reelife", "reelygo", "sarostv", "sereal", "shorten", "shortical",
            "sixthshort", "soreel", "stardusttv", "storeel", "storygo", "storyreel", "tallflix", "toonshort",
            "vibeshort", "winknovel",
        )

        private val APP_NAMES = mapOf(
            "blinkdrama" to "BlinkDrama", "freereels" to "FreeReels", "stardusttv" to "StardustTV",
            "storyreel" to "StoryReel", "vibeshort" to "VibeShort", "toonshort" to "ToonShort",
            "flareflow" to "FlareFlow", "mydrama" to "My Drama", "rapidtv" to "RapidTV", "rapidtv2" to "RapidTV",
            "reelife" to "ReelLife", "moboreels" to "MoboReels", "goodshort" to "GoodShort",
        )

        /** Apps whose catalogue is (dubbed) Chinese or Korean productions. */
        private val ASIAN_APPS = setOf("melolo", "vigloo2", "dramanova", "bonustv")
        private val DUBBED = Regex("""dubbed""", RegexOption.IGNORE_CASE)
        private val FEED_LABEL = Regex("""new releases|tab \d|home|feed|trending|for you|popular|recommend""", RegexOption.IGNORE_CASE)

        /** Explicit wherever they appear (title or synopsis). */
        private val ADULT_ANYWHERE = Regex(
            """18\+|\br18\b|\bnsfw\b|erotic|porn|\bxxx\b|hentai|\bnud(e|ity)\b|\bnaked\b|\bbdsm\b|\borgasm""",
            RegexOption.IGNORE_CASE,
        )
    }
}
