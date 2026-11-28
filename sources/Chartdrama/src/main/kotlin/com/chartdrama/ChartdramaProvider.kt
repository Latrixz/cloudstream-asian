package com.chartdrama

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
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * ChartDrama (chartdrama.com, branded "FindDrama") lists vertical short dramas from many apps and
 * plays them to guests. Its JSON API needs no login or signing (Cloudflare only turns away curl's
 * User-Agent). Each app has its own subdomain, and filtering by app only works there:
 *  - https://<app>.chartdrama.com/api/series?source=<id>&subdomain_strip=1&page=&limit=&q=
 *    -> {items: [{slug, dramaId, title, cover, latestEpisodeLabel, playCount, source}], total}, by plays
 *  - /api/watch/<slug, url-encoded> -> {dramaId, title, synopsis, tags, cover, embedUrl, subtitleUrl?}
 *    (slugs repeat across apps, so it's asked on the app's own subdomain)
 *  - /api/drama/<dramaId>/episodes -> {items: [{ep, url, subtitleUrl?}]}
 *  - /api/drama/<dramaId>/refresh-episode?ep=N -> {ok, url, subtitleUrl}, for signed links that expired
 *  - https://chartdrama.com/api/trending -> {items: [{slug, title, cover, source}]}
 * Paths starting with /api/ are relative to the app's subdomain. Some links come wrapped in the
 * site's browser proxy (…workers.dev/proxy?url=…), which only exists for CORS; we play the inner link
 * and keep the proxied one as a fallback.
 *
 * Left out: the apps the site itself keeps for VIP members, iQIYI/WeTV, MuVpix (videos need a key
 * server), FlexTV (its video host no longer resolves), and KalosTV, Toonory and RaptDrama (dead feeds).
 */
class ChartdramaProvider : MainAPI() {
    override var mainUrl = "https://chartdrama.com"
    override var name = "ChartDrama Shorts"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    private val mapper = ObjectMapper()
    private val apiHeaders = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json")

    /** An app on ChartDrama: its subdomain, source id and name. [row]: it gets a home row. */
    private data class App(val key: String, val source: Int, val label: String, val row: Boolean = false) {
        val api get() = "https://$key.chartdrama.com/api"
    }

    private val apps = listOf(
        // Home rows (few rows keep Home fast): the biggest apps, and some ReelFren doesn't have.
        App("reelshort", 2, "ReelShort", row = true),
        App("dramabox", 5, "DramaBox", row = true),
        App("goodshort", 6, "GoodShort", row = true),
        App("netshort", 9, "NetShort", row = true),
        App("shortmax", 10, "ShortMax", row = true),
        App("dramawave", 7, "DramaWave", row = true),
        App("starshort", 14, "StarShort", row = true),
        App("sodatv", 24, "SodaTV", row = true),
        App("rivoshort", 90, "RivoShort", row = true),
        App("dramatv", 17, "DramaTV", row = true),
        App("4drama", 19, "4Drama", row = true),
        // Searchable only.
        App("stardusttv", 12, "StardustTV"),
        App("freereels", 13, "FreeReels"),
        App("ziptaletv", 47, "ZiptaleTV"),
        App("nebuluxe", 65, "Nebuluxe"),
        App("flikso", 57, "Flikso"),
        App("swoopreels", 56, "SwoopReels"),
        App("venixtv", 30, "VenixTV"),
        App("sanpplay", 55, "SanpPlay"),
        App("novafilck", 22, "NovaFilck"),
        App("plotify", 59, "Plotify"),
        App("leapreels", 34, "LeapReels"),
        App("vyntage", 51, "Vyntage"),
        App("joyreels", 46, "JoyReels"),
        App("aurareels", 29, "AuraReels"),
        App("starreel", 33, "StarReel"),
        App("flareflow", 43, "FlareFlow"),
        App("myrelle", 75, "Myrelle"),
        App("versevoyagetv", 91, "VerseVoyageTV"),
        App("lunory", 66, "Lunory"),
        App("cubetv", 92, "CubeTV"),
        App("pinedrama", 88, "PineDrama"),
        App("dramabite", 93, "DramaBite"),
        App("shorts", 21, "Shorts"),
        App("thisreels", 23, "ThisReels"),
        App("tastelife", 35, "TasteLife"),
    )
    private val byKey = apps.associateBy { it.key }
    private val bySource = apps.associateBy { it.source }

    /**
     * Category browsing (shared/CsBrowse.kt): one section per app above (the VIP-only ones stay out),
     * from its own subdomain, most played first (the API's only order; it has no sort). The site's tags
     * work as a genre filter there; its 663 tags fall into about 14 groups that give the same titles
     * (CEO = Billionaire, Werewolf = Fantasy = Vampire, ...), so the filter lists one tag per group.
     * StarShort and NovaFilck have no tags, so no filter.
     */
    private val browse = CsBrowse(
        this, name,
        apps.map { app ->
            CsBrowse.Section(
                app.key, app.label, english = app.label, glyph = "📱",
                filters = if (app.key in UNTAGGED) emptyList()
                else listOf(CsCategories.Filter(TAG_FILTER, "Genre", GENRES.map { (tag, label) -> CsCategories.Option(tag, label) })),
            )
        },
    ) { id, filters, query, page ->
        val app = byKey[id] ?: return@CsBrowse emptyList<SearchResponse>() to false
        val root = series(app, page, CATEGORY_PAGE, query.ifBlank { null }, filters[TAG_FILTER])
        val items = root.path("items").mapNotNull { it.toCard(app) }.map { it.toSearchResult() }
        items to (root.path("items").size() > 0 && page * CATEGORY_PAGE < root.path("total").asInt(0))
    }

    // On CloudStream the "分类 Categories" row comes first.
    override val mainPage = browse.mainPage(
        listOf(MainPageData("Trending on FindDrama", TRENDING)) +
            apps.filter { it.row }.map { MainPageData(it.label, it.key) },
    )

    // Categories SkyStream Dev shows itself (shared/CsCategories.kt); the engine finds these by name.
    fun csCategories(): String = browse.categoriesJson()

    fun csCategoryPage(categoryId: String, filtersJson: String, query: String, page: Int): SearchResponseList =
        browse.categoryPage(categoryId, filtersJson, query, page)

    // ---- cards ----

    private data class Card(val app: App, val slug: String, val title: String, val cover: String?, val plays: Long)

    private fun JsonNode.toCard(app: App? = null): Card? {
        val owner = app ?: bySource[path("source").asInt(-1)] ?: return null
        if (app != null && path("source").asInt(app.source) != app.source) return null
        val slug = text("slug") ?: return null
        val title = text("title") ?: return null
        // Only clearly explicit words in the title; the apps' romance shorts are mainstream and stay.
        if (ADULT.containsMatchIn(title)) return null
        return Card(owner, slug, title, text("cover"), path("playCount").asLong(0))
    }

    private fun Card.toSearchResult(): SearchResponse =
        newTvSeriesSearchResponse(title, titleUrl(app, slug), typeOf(title)) {
            posterUrl = poster(cover)
            posterHeaders = POSTER_HEADERS
        }

    // ---- home ----

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        browse.rowPage(request) ?: com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == TRENDING) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            var cards = runCatching { json("$mainUrl/api/trending").path("items").mapNotNull { it.toCard() } }
                .onFailure { Log.w(TAG, "trending: ${it.message}") }.getOrDefault(emptyList())
            if (cards.size < 6) {
                // The trending chart can go stale; most-played titles of our apps stand in.
                cards = cards + runCatching {
                    json("$mainUrl/api/series?page=1&limit=100").path("items").mapNotNull { it.toCard() }
                }.getOrDefault(emptyList())
            }
            val items = cards.distinctBy { it.app.key + "/" + it.slug }.map { it.toSearchResult() }
            Log.i(TAG, "row trending: ${items.size} cards")
            return newHomePageResponse(request.name, items, false)
        }
        val app = byKey[request.data] ?: throw ErrorLoadingException("Unknown row")
        val root = series(app, page, ROW_PAGE, null)
        val items = root.path("items").mapNotNull { it.toCard(app) }.map { it.toSearchResult() }
        val total = root.path("total").asInt(0)
        Log.i(TAG, "row ${app.key} page $page: ${items.size} cards of $total")
        return newHomePageResponse(request.name, items, page * ROW_PAGE < total)
    }

    private suspend fun series(app: App, page: Int, limit: Int, query: String?, tag: String? = null): JsonNode =
        json(buildString {
            append(app.api).append("/series?source=").append(app.source).append("&subdomain_strip=1")
            append("&page=").append(page).append("&limit=").append(limit)
            query?.let { append("&q=").append(enc(it)) }
            tag?.let { append("&tag=").append(enc(it)) }
        })

    // ---- search ----

    /** Every app is asked on its own subdomain, all at once (each answers in well under a second). */
    // Also searches the title in English when it was typed in another language (shared/CsSearch.kt).
    override suspend fun search(query: String): List<SearchResponse> =
        CsSearch.withExtra(query, 1, "en") { q, _ -> searchOwn(q) to false }.first

    private suspend fun searchOwn(query: String): List<SearchResponse> {
        val phrase = normalize(query)
        if (phrase.isEmpty()) return emptyList()
        val started = System.currentTimeMillis()
        val found = coroutineScope {
            apps.map { app ->
                async {
                    withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                        runCatching { series(app, 1, SEARCH_PER_APP, query).path("items").mapNotNull { it.toCard(app) } }
                            .onFailure { Log.w(TAG, "search ${app.key}: ${it.message}") }.getOrNull()
                    }.orEmpty()
                }
            }.awaitAll().flatten()
        }
        val hits = found.distinctBy { it.app.key + "/" + it.slug }
            .sortedWith(compareBy<Card>(
                { normalize(it.title) != phrase },
                { !normalize(it.title).startsWith(phrase) },
                { !normalize(it.title).contains(phrase) },
                { -it.plays },
            ))
            .take(MAX_RESULTS)
        Log.i(TAG, "search '$query': ${hits.size} of ${found.size} in ${System.currentTimeMillis() - started}ms")
        return hits.map { it.toSearchResult() }
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
        return CsBackup.loadLinks(this, data, subtitleCallback, callback) { callback ->
            loadOwnLinks(data, isCasting, subtitleCallback, callback)
        }
    }

    private suspend fun loadPage(url: String): LoadResponse {
        val (app, slug) = parseTitleUrl(url) ?: throw ErrorLoadingException("Not a ChartDrama title link")
        val watch = json("${app.api}/watch/${enc(decode(slug))}")
        val dramaId = watch.text("dramaId") ?: throw ErrorLoadingException("Couldn't find this title on ChartDrama")
        val title = watch.text("title") ?: "Untitled"
        val list = episodes(app, dramaId, fresh = true)
        val numbers = list.map { it.ep }.ifEmpty { if (watch.text("embedUrl") != null) listOf(1) else emptyList() }
        // The title's own subtitle belongs to episode 1; it's passed along if that episode has none.
        val firstSub = watch.text("subtitleUrl")?.takeIf { list.firstOrNull { it.ep == 1 }?.subtitle == null }
        Log.i(TAG, "detail '$title' (${app.key} $dramaId): ${numbers.size} episodes")
        val episodes = numbers.map { n ->
            newEpisode(episodeData(app, dramaId, n, if (n == 1) firstSub else null)) {
                name = "Episode $n"
                episode = n
            }
        }
        return newTvSeriesLoadResponse(title, titleUrl(app, slug), typeOf(title), episodes) {
            posterUrl = poster(watch.text("cover"))
            posterHeaders = POSTER_HEADERS
            plot = watch.text("synopsis")
            tags = listOf(app.label) + watch.path("tags").mapNotNull { it.asText().trim().ifBlank { null } }
        }
    }

    // ---- links ----

    private data class Ep(val ep: Int, val url: String?, val subtitle: String?)

    private val episodeCache = HashMap<String, Pair<Long, List<Ep>>>()

    /** A title's episode list; kept a few minutes so the title page and playing share one call. */
    private suspend fun episodes(app: App, dramaId: String, fresh: Boolean = false): List<Ep> {
        val key = "${app.key}/$dramaId"
        if (!fresh) synchronized(episodeCache) {
            episodeCache[key]?.takeIf { System.currentTimeMillis() - it.first < EPISODE_CACHE_MS }?.let { return it.second }
        }
        val list = json("${app.api}/drama/$dramaId/episodes").path("items").mapNotNull { node ->
            val n = node.path("ep").asInt(0).takeIf { it > 0 } ?: return@mapNotNull null
            Ep(n, node.text("url"), node.text("subtitleUrl"))
        }.distinctBy { it.ep }.sortedBy { it.ep }
        synchronized(episodeCache) { episodeCache[key] = System.currentTimeMillis() to list }
        return list
    }

    private suspend fun loadOwnLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data = https://<app>.chartdrama.com/api/drama/<dramaId>/episodes?ep=<n>[&s=<title subtitle>]
        val app = byKey[data.substringAfter("://").substringBefore(".chartdrama.com")] ?: return false
        val dramaId = Regex("""/drama/([^/?#]+)/episodes""").find(data)?.groupValues?.get(1) ?: return false
        val q = queryParams(data)
        val n = q["ep"]?.toIntOrNull() ?: return false

        val entry = runCatching { episodes(app, dramaId).firstOrNull { it.ep == n } }
            .onFailure { Log.w(TAG, "episodes ${app.key} $dramaId: ${it.message}") }.getOrNull()
        var subtitle = entry?.subtitle ?: q["s"]
        var stream = entry?.url?.let { playable(app, it) }

        // Signed links expire (403/401); the site then asks for a fresh one.
        if (stream == null) {
            Log.i(TAG, "${app.key} $dramaId ep $n: ${if (entry?.url == null) "no link" else "link failed"}, refreshing")
            val fresh = runCatching { json("${app.api}/drama/$dramaId/refresh-episode?ep=$n", timeout = 60) }
                .onFailure { Log.w(TAG, "refresh ${app.key} $dramaId ep $n: ${it.message}") }.getOrNull()
            val url = fresh?.takeIf { it.path("ok").asBoolean(false) }?.text("url")
            if (url != null) {
                fresh.text("subtitleUrl")?.let { subtitle = it }
                stream = playable(app, url)
                if (stream != null) synchronized(episodeCache) { episodeCache.remove("${app.key}/$dramaId") }
            } else {
                Log.w(TAG, "refresh ${app.key} $dramaId ep $n: ${fresh?.text("error") ?: "no link"}")
            }
        }

        subtitle?.let { subtitleCallback(newSubtitleFile("English", absolute(app, it))) }
        val s = stream ?: return false
        callback(newExtractorLink(name, "$name ${app.label}", s.url, s.type) {
            quality = s.quality
            headers = mapOf("User-Agent" to USER_AGENT)
        })
        Log.i(TAG, "links ${app.key} $dramaId ep $n: ${s.type} ${if (s.quality == Qualities.Unknown.value) "quality unknown" else "${s.quality}p"} ${s.url.take(90)}")
        return true
    }

    private data class Stream(val url: String, val type: ExtractorLinkType, val quality: Int)

    /**
     * Checks that a link answers, and reads its quality from the playlist. The inner link of a site
     * proxy is tried first, the proxied one after it.
     */
    private suspend fun playable(app: App, raw: String): Stream? {
        val url = absolute(app, raw)
        for (candidate in listOf(unwrapProxy(url), url).distinct()) {
            val stream = runCatching { check(candidate) }
                .onFailure { Log.w(TAG, "check ${candidate.take(90)}: ${it.message}") }.getOrNull()
            if (stream != null) return stream
        }
        return null
    }

    private suspend fun check(url: String): Stream? {
        val looksHls = decode(url).contains(".m3u8", true)
        val res = app.get(url, headers = if (looksHls) mapOf("User-Agent" to USER_AGENT)
            else mapOf("User-Agent" to USER_AGENT, "Range" to "bytes=0-2047"), timeout = 30)
        if (!res.isSuccessful) {
            Log.w(TAG, "check ${url.take(90)}: HTTP ${res.code}")
            res.body.close()
            return null
        }
        val head = if (looksHls) res.text else res.body.byteStream().use { s ->
            val buf = ByteArray(2048)
            var len = 0
            while (len < buf.size) { val r = s.read(buf, len, buf.size - len); if (r < 0) break; len += r }
            String(buf, 0, len, Charsets.ISO_8859_1)
        }
        if (!head.startsWith("#EXTM3U")) return Stream(url, ExtractorLinkType.VIDEO, Qualities.Unknown.value)
        val playlist = if (looksHls) head else app.get(url, headers = mapOf("User-Agent" to USER_AGENT), timeout = 30).text
        return Stream(url, ExtractorLinkType.M3U8, qualityOf(playlist))
    }

    /** The best variant's short side ("RESOLUTION=1080x1920" -> 1080); a plain media playlist doesn't say. */
    private fun qualityOf(playlist: String): Int =
        Regex("""RESOLUTION=(\d+)x(\d+)""").findAll(playlist)
            .map { minOf(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            .maxOrNull() ?: Qualities.Unknown.value

    // ---- helpers ----

    /** https://<app>.chartdrama.com/d/<slug>: the site's own title link. */
    private fun titleUrl(app: App, slug: String) = "https://${app.key}.chartdrama.com/d/$slug"

    private fun parseTitleUrl(url: String): Pair<App, String>? {
        val m = Regex("""https?://([a-z0-9]+)\.chartdrama\.com/(?:d|p)/([^?#]+)""").find(url) ?: return null
        val app = byKey[m.groupValues[1]] ?: return null
        return app to m.groupValues[2]
    }

    private fun episodeData(app: App, dramaId: String, ep: Int, subtitle: String?) = buildString {
        append(app.api).append("/drama/").append(dramaId).append("/episodes?ep=").append(ep)
        subtitle?.let { append("&s=").append(enc(it)) }
    }

    private fun queryParams(url: String): Map<String, String> =
        url.substringAfter('?', "").substringBefore('#').split('&').mapNotNull { part ->
            val k = part.substringBefore('=', "")
            if (k.isEmpty()) null else k to URLDecoder.decode(part.substringAfter('='), "UTF-8")
        }.toMap()

    private fun absolute(app: App, url: String) = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://${app.key}.chartdrama.com$url"
        else -> url
    }

    /** …workers.dev/proxy?url=<link> -> <link> (the site's browser proxies, for CORS only). */
    private fun unwrapProxy(url: String): String {
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?')
        if (!host.endsWith(".workers.dev") || !url.substringAfter(host).startsWith("/proxy?")) return url
        val inner = queryParams(url)["url"]?.trim() ?: return url
        return if (inner.startsWith("https://") || inner.startsWith("http://")) inner else url
    }

    /** res.chartdrama.com covers need a resize step (the originals are large); other hosts pass through. */
    private fun poster(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (!url.contains("://res.chartdrama.com/")) return url
        val sized = if (url.contains("x-oss-process")) url else url + (if ('?' in url) "&" else "?") + "x-oss-process=image/resize,w_640"
        // SkyStream ignores posterHeaders, so there the covers come through a local relay (ChartdramaCovers).
        return if (com.csasian.shared.CsLogos.onSkyStream) ChartdramaCovers.local(sized) else sized
    }

    /** "[Dubbed] …", "(Dubbed) …", "… (DUBBED)": dubbed Chinese productions. */
    private fun typeOf(title: String): TvType = if (DUBBED.containsMatchIn(title)) TvType.AsianDrama else TvType.TvSeries

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    /** Percent-decoding that leaves '+' alone (slugs are path segments, not form data). */
    private fun decode(s: String): String = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun JsonNode.text(field: String): String? =
        path(field).takeIf { it.isTextual || it.isNumber }?.asText()?.trim()?.ifBlank { null }

    private suspend fun json(url: String, timeout: Long = 30): JsonNode {
        val res = app.get(url, headers = apiHeaders, timeout = timeout)
        if (!res.isSuccessful) throw ErrorLoadingException("ChartDrama replied ${res.code}")
        return mapper.readTree(res.text)
    }

    companion object {
        private const val TAG = "Chartdrama"
        private const val TRENDING = "trending"
        private const val ROW_PAGE = 40
        private const val CATEGORY_PAGE = 30
        private const val TAG_FILTER = "tag"

        /** One tag per group of the site's tags (tag to label); checked 2026-11-27. */
        private val GENRES = listOf(
            "Romance" to "Romance", "CEO" to "CEO & Billionaire", "Revenge" to "Revenge",
            "Fantasy" to "Fantasy & Werewolf", "Rebirth" to "Rebirth & Time Travel", "Comedy" to "Sweet & Comedy",
            "Hidden Identity" to "Hidden Identity", "Family" to "Family & Marriage", "Action" to "Action & Thriller",
            "Historical" to "Historical", "Urban" to "Urban", "Betrayal" to "Betrayal",
            "Strong Female Lead" to "Strong Female Lead", "Campus" to "Campus",
        )

        /** Apps without tags on the site. */
        private val UNTAGGED = setOf("starshort", "novafilck")
        private const val SEARCH_PER_APP = 30
        private const val SEARCH_TIMEOUT_MS = 15_000L
        private const val MAX_RESULTS = 100
        private const val EPISODE_CACHE_MS = 5 * 60_000L
        private const val USER_AGENT = "okhttp/4.12.0"
        private val POSTER_HEADERS = mapOf("Referer" to "https://chartdrama.com/")
        private val DUBBED = Regex("""[\[(]\s*dubbed\s*[\])]""", RegexOption.IGNORE_CASE)
        private val ADULT = Regex(
            """18\+|\bnsfw\b|erotic|porn|\bxxx\b|hentai|\bnude\b|\bnaked\b|\bbdsm\b""",
            RegexOption.IGNORE_CASE,
        )
    }
}
