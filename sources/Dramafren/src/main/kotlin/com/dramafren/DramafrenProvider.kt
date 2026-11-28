package com.dramafren

import android.util.Log
import com.csasian.shared.CsBackup
import com.csasian.shared.CsBrowse
import com.csasian.shared.CsCategories
import com.csasian.shared.CsLogos
import com.csasian.shared.CsMore
import com.csasian.shared.CsSearch
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * DramaFren (v2.dramafren.org) is a WordPress site (MovieStream theme) with two halves:
 *
 *  - Chinese short dramas with English subtitles burned in (about 13,700 series). A series page
 *    (/series/<slug>/) links its episode pages (/watch/<slug>/); most series are a single
 *    "FULL EPISODE" of about two hours, some are split into Part 1 / Part 2. Each episode page lists
 *    mirrors in data-em attributes: a base64 <iframe>. Only some mirrors still play: StreamMain
 *    (a plain MP4, on newer uploads), ok.ru and the VidHide family (packed JS with an m3u8). The rest
 *    are encrypted (Abyss), need a login (TeraBox), or are dead. Roughly half the titles have a
 *    mirror that plays; when none does, CsBackup looks on our other sources.
 *
 *  - DramaBox in English (about 2,000 titles), served by the site's own plugin:
 *    /?msdb_catalog=1&msdb_page=N lists 24 titles a page, /?msdb_book=<id>&msdb_lang=en is a title,
 *    and POST /wp-admin/admin-ajax.php action=msdb_playlist&book=<id>&lang=en returns every episode
 *    with its MP4s (1080/720/540). Those links expire after about two weeks, so they're fetched when
 *    an episode is played. The plugin's search replies 502, so DramaBox titles are searched by
 *    filtering the catalogue pages, read once and cached.
 *
 * Everything here is served to guests; nothing needs a login. Cloudflare only runs its passive check.
 */
class DramafrenProvider : MainAPI() {
    override var mainUrl = "https://v2.dramafren.org"
    override var name = "DramaFren"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries)

    private val mapper = ObjectMapper()
    private val headers = mapOf("User-Agent" to USER_AGENT)

    /**
     * Category browsing (shared/CsBrowse.kt): "Chinese dramas" from the site's advanced search, with its
     * status filter (none chosen = all) and its orders, latest first; and "DramaBox", the English
     * catalogue in its own order. A search in "Chinese dramas" is the site's search (it can't be narrowed
     * by status); in "DramaBox" it filters the catalogue.
     */
    private val browse = CsBrowse(
        this, name,
        listOf(
            CsBrowse.Section(
                SECTION_CHINESE, "Chinese dramas", english = "Chinese dramas", glyph = "🀄",
                filters = listOf(
                    CsCategories.Filter(STATUS, "Status", listOf(CsCategories.Option("completed", "Completed"), CsCategories.Option("ongoing", "Ongoing"))),
                ),
                sorts = listOf(
                    CsCategories.Option("latest", "Latest"), CsCategories.Option("added", "Release date"),
                    CsCategories.Option("popular", "Most viewed"), CsCategories.Option("title", "Name"),
                ),
            ),
            CsBrowse.Section(SECTION_DRAMABOX, "DramaBox", english = "DramaBox", glyph = "📱"),
        ),
        popularSort = "popular",
    ) { id, filters, query, page ->
        when {
            id == SECTION_DRAMABOX && query.isNotBlank() ->
                (if (page <= 1) searchCatalogue(query).map { it.toSearchResult() } else emptyList()) to false
            id == SECTION_DRAMABOX -> catalogPages(page, 0)
            query.isNotBlank() -> searchSite(query, page)
            else -> {
                val url = buildString {
                    append(mainUrl).append("/advanced-search/")
                    if (page > 1) append("page/").append(page).append('/')
                    append("?type%5B0%5D=series")
                    filters[STATUS]?.let { append("&status%5B0%5D=").append(it) }
                    append("&order=").append(filters[CsCategories.SORT] ?: "latest")
                }
                sitePages(listOf(page)) { url }
            }
        }
    }

    // On CloudStream the "分类 Categories" row comes first.
    override val mainPage = browse.mainPage(
        listOfNotNull(
            MainPageData("Latest Chinese dramas", ROW_LATEST),
            MainPageData("More new Chinese dramas", ROW_MORE),
            // "Popular" left out: it is almost all older titles whose mirrors are dead (1 of 16 played).
            MainPageData("DramaBox (English)", ROW_DRAMABOX),
            MainPageData("More DramaBox (English)", ROW_DRAMABOX_MORE),
        ),
    )

    // Categories SkyStream Dev shows itself (shared/CsCategories.kt); the engine finds these by name.
    fun csCategories(): String = browse.categoriesJson()

    fun csCategoryPage(categoryId: String, filtersJson: String, query: String, page: Int): SearchResponseList =
        browse.categoryPage(categoryId, filtersJson, query, page)

    // ---- home ----

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        browse.rowPage(request)?.let { return it }
        val (items, hasNext) = when (request.data) {
            // 4 site pages (40 titles) per row page: SkyStream only asks for more once its grid scrolls.
            // SkyStream never asks for page 2 itself; there the row ends with a "More ›" card (CsMore).
            ROW_LATEST -> sitePages(rowPages(page, 0)) { "$mainUrl/series/page/$it/?order=latest" }
            // Starts where the first page of "Latest" ends.
            ROW_MORE -> sitePages(rowPages(page, 1)) { "$mainUrl/series/page/$it/?order=latest" }
            ROW_POPULAR -> sitePages(listOf(page)) { "$mainUrl/popular/page/$it/" }
            // Starts at the next catalogue page after the first "DramaBox" row page.
            ROW_DRAMABOX_MORE -> catalogPages(page, 1)
            else -> catalogPages(page, 0)
        }
        Log.i(TAG, "row '${request.name}' page $page: ${items.size} cards, next=$hasNext")
        return CsMore.wrap(this, request, page, newHomePageResponse(request.name, items, hasNext))
    }

    /** Reads listing pages at once and returns their cards in order, without duplicates. */
    /** Site pages (10 titles each) for row page [page]; [offset] shifts by one whole row page. */
    private fun rowPages(page: Int, offset: Int): List<Int> {
        val per = 4
        val first = (page - 1 + offset) * per + 1
        return (first until first + per).toList()
    }

    /** DramaBox catalogue pages (24 titles each): 3 per row page on SkyStream, 1 elsewhere. */
    private suspend fun catalogPages(page: Int, offset: Int): Pair<List<SearchResponse>, Boolean> {
        val per = 1
        val first = (page - 1 + offset) * per + 1
        val cards = coroutineScope { (first until first + per).map { async { catalogPage(it) } }.awaitAll() }.flatten()
        return cards.map { it.toSearchResult() }.distinctBy { it.url } to cards.isNotEmpty()
    }

    private suspend fun sitePages(pages: List<Int>, url: (Int) -> String): Pair<List<SearchResponse>, Boolean> {
        val docs = coroutineScope {
            pages.map { p -> async { runCatching { page(url(p)) }.onFailure { Log.w(TAG, "${url(p)}: ${it.message}") }.getOrNull() } }
                .awaitAll()
        }
        val items = docs.filterNotNull().flatMap { cards(it) }.distinctBy { it.name.lowercase() }
        val hasNext = docs.lastOrNull()?.selectFirst("a.next.page-numbers") != null
        return items to hasNext
    }

    /** The a.tip cards of a listing (series or episode links; both open the series). */
    private fun cards(doc: Document): List<SearchResponse> =
        doc.select("article.box a.tip[href]").mapNotNull { a ->
            val href = link(a)
            if (!href.contains("/series/") && !href.contains("/watch/")) return@mapNotNull null
            val title = (a.selectFirst("h2.entry-title")?.text() ?: a.attr("title")).trim().ifBlank { return@mapNotNull null }
            if (isAdult(title)) return@mapNotNull null
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                posterUrl = image(a.selectFirst("img"))
            }
        }

    // ---- search ----

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1)?.items.orEmpty()

    // Page 1 also searches the title in English when it was typed in another language (shared/CsSearch.kt).
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val (items, hasNext) = CsSearch.withExtra(query, page, "en") { q, p -> searchOwn(q, p) }
        return newSearchResponseList(items, hasNext)
    }

    private suspend fun searchOwn(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList<SearchResponse>() to false
        return coroutineScope {
            val site = async { searchSite(q, page) }
            // DramaBox titles come first on page 1 only (they're all filtered at once).
            val box = if (page <= 1) async { searchCatalogue(q) } else null
            val (chinese, hasNext) = site.await()
            val dramabox = box?.await().orEmpty()
            Log.i(TAG, "search '$q' page $page: ${chinese.size} Chinese, ${dramabox.size} DramaBox")
            (dramabox.map { it.toSearchResult() } + chinese) to hasNext
        }
    }

    /** The site's own search (Chinese dramas): one page of cards, and whether another follows. */
    private suspend fun searchSite(q: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val url = if (page <= 1) "$mainUrl/?s=${enc(q)}" else "$mainUrl/page/$page/?s=${enc(q)}"
        val doc = runCatching { page(url) }.onFailure { Log.w(TAG, "search '$q' page $page: ${it.message}") }.getOrNull()
        return doc?.let { cards(it) }.orEmpty() to (doc?.selectFirst("a.next.page-numbers") != null)
    }

    // ---- DramaBox catalogue ----

    private data class Book(val id: String, val title: String, val cover: String?, val episodes: Int)

    private class CachedPage(val books: List<Book>, val fetched: Long)

    private val catalogue = HashMap<Int, CachedPage>()
    @Volatile private var lastCataloguePage = Int.MAX_VALUE

    private suspend fun catalogPage(page: Int): List<Book> {
        synchronized(catalogue) {
            catalogue[page]?.takeIf { System.currentTimeMillis() - it.fetched < CATALOGUE_CACHE_MS }?.let { return it.books }
        }
        if (page > lastCataloguePage) return emptyList()
        val doc = page("$mainUrl/?msdb_catalog=1&msdb_page=$page")
        val books = doc.select("article.msdb-card").mapNotNull { card ->
            val a = card.selectFirst("a[href*=msdb_book=]") ?: return@mapNotNull null
            val id = Regex("""msdb_book=(\d+)""").find(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            val title = (card.selectFirst("h2")?.text() ?: card.selectFirst("img")?.attr("alt")).orEmpty().trim()
            if (title.isEmpty() || isAdult(title)) return@mapNotNull null
            val count = card.selectFirst(".msdb-badge")?.text()?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() } ?: 0
            Book(id, title, image(card.selectFirst("img")), count)
        }
        // The last page has no "Next" link; a page without the catalogue grid is an error, not the end.
        if (doc.selectFirst(".msdb-grid, .msdb-pagination") == null) throw ErrorLoadingException("DramaBox catalogue page $page didn't load")
        val next = doc.select(".msdb-pagination a[href*=msdb_page=]").any { Regex("""msdb_page=(\d+)""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() == page + 1 }
        if (!next) lastCataloguePage = minOf(lastCataloguePage, page)
        synchronized(catalogue) { catalogue[page] = CachedPage(books, System.currentTimeMillis()) }
        return books
    }

    /** Reads the whole catalogue (about 84 pages, 8 at a time; cached), then matches titles. */
    private suspend fun searchCatalogue(query: String): List<Book> {
        val words = normalize(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val started = System.currentTimeMillis()
        val gate = Semaphore(CATALOGUE_PARALLEL)
        val pages = withTimeoutOrNull(CATALOGUE_TIMEOUT_MS) {
            coroutineScope {
                (1..MAX_CATALOGUE_PAGES).map { p ->
                    async {
                        if (p > lastCataloguePage) emptyList()
                        else gate.withPermit { runCatching { catalogPage(p) }.getOrDefault(emptyList()) }
                    }
                }.awaitAll()
            }
        } ?: synchronized(catalogue) { catalogue.values.map { it.books } }
        val all = pages.flatten().distinctBy { it.id }
        val phrase = words.joinToString(" ")
        val hits = all.filter { b -> normalize(b.title).let { t -> words.all { it in t } } }
            .sortedWith(compareBy({ normalize(it.title) != phrase }, { !normalize(it.title).startsWith(phrase) }))
            .take(MAX_DRAMABOX_RESULTS)
        Log.i(TAG, "DramaBox search '$query': ${hits.size} of ${all.size} titles in ${System.currentTimeMillis() - started}ms")
        return hits
    }

    private fun Book.toSearchResult(): SearchResponse =
        newTvSeriesSearchResponse(title, bookUrl(id), TvType.TvSeries) {
            posterUrl = cover
        }

    // ---- title page ----

    // Remembers episodes and adds backup links from our other sources (see shared/CsBackup.kt).
    // A "分类 Categories" card opens the category page (browse.load first: it checks who is asking).
    override suspend fun load(url: String): LoadResponse =
        browse.load(url) ?: CsMore.load(this, url) ?: loadPage(url).also { CsBackup.remember(this, it) }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        browse.reopen(data) // ▶ on a category's own page opens the category page again.
        return if (CsMore.isMore(data)) false else CsBackup.loadLinks(this, data, subtitleCallback, callback) {
            loadOwnLinks(data, isCasting, subtitleCallback, callback)
        }
    }

    private suspend fun loadPage(url: String): LoadResponse {
        bookId(url)?.let { return loadBook(it) }
        var doc = page(url)
        if (url.contains("/watch/")) {
            // An episode link (Popular row, old bookmarks): open its series.
            val anchor: Element? = doc.selectFirst("[itemprop=partOfSeries] a[href], a.breadcrumb-link[href*=/series/]")
            val series: String? = anchor?.let { link(it) }
            if (series.isNullOrBlank()) return singleEpisode(url, doc)
            doc = page(series)
        }
        val seriesUrl = doc.selectFirst("link[rel=canonical]")?.attr("href")?.takeIf { it.contains("/series/") } ?: url
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()?.ifBlank { null }
            ?: throw ErrorLoadingException("Couldn't read this DramaFren title")
        val poster = image(doc.selectFirst(".limage img")) ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val status = doc.select("ul.data li").firstOrNull { it.text().startsWith("Status", true) }?.selectFirst("span span, span")?.text()
        // Most series have no synopsis; some carry SEO filler ("watch X full movie, ...") instead.
        val plot = (doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.ifBlank { null }
            ?: doc.select(".entry-content[itemprop=description] p:not(.key)").text().trim().ifBlank { null })
            ?.takeUnless { SEO_FILLER.containsMatchIn(it) }
        val listed = doc.select(".epsdlist li a[href*=/watch/]").mapNotNull { a ->
            val num = Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE).find(a.selectFirst(".epl-num")?.text().orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()
            Triple(link(a), num, a.selectFirst(".epl-title")?.text()?.trim())
        }.distinctBy { it.first }
            .sortedBy { it.second ?: Int.MAX_VALUE }
            .mapIndexed { i, (href, num, label) ->
                newEpisode(href) {
                    name = episodeName(label, num ?: (i + 1))
                    episode = num ?: (i + 1)
                }
            }
        // Some series pages list no episodes although the video sits on /watch/<same slug>/.
        val episodes = listed.ifEmpty {
            val slug = seriesUrl.trimEnd('/').substringAfterLast('/')
            val watch = "$mainUrl/watch/$slug/"
            val ok = runCatching { app.get(watch).let { it.isSuccessful && it.text.contains("data-em") } }.getOrDefault(false)
            Log.i(TAG, "series '$title' lists no episodes; $watch ${if (ok) "has" else "has no"} players")
            if (ok) listOf(newEpisode(watch) { name = "Full episode"; episode = 1 }) else emptyList()
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("This title has no episodes on DramaFren yet")
        Log.i(TAG, "series '$title': ${episodes.size} episodes")
        return newTvSeriesLoadResponse(title, seriesUrl, TvType.AsianDrama, episodes) {
            posterUrl = poster
            this.plot = plot
            showStatus = when {
                status == null -> null
                status.contains("complete", true) -> ShowStatus.Completed
                status.contains("ongoing", true) -> ShowStatus.Ongoing
                else -> null
            }
            tags = listOf("Chinese short drama", "English subtitles")
        }
    }

    /** An episode page whose series link is missing: show it as a one-episode series. */
    private suspend fun singleEpisode(url: String, doc: Document): LoadResponse {
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()?.ifBlank { null }
            ?: throw ErrorLoadingException("Couldn't read this DramaFren episode")
        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, listOf(newEpisode(url) { name = "Full episode"; episode = 1 })) {
            posterUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        }
    }

    private fun episodeName(label: String?, num: Int): String {
        val l = label?.trim().orEmpty()
        return when {
            l.isEmpty() -> "Episode $num"
            FULL.matches(l) -> "Full episode"
            else -> l.lowercase().replaceFirstChar { it.uppercase() }.replace(Regex("""\bend\b"""), "(end)")
        }
    }

    private suspend fun loadBook(id: String): LoadResponse {
        val (doc, list) = coroutineScope {
            val d = async { runCatching { page(bookUrl(id)) }.getOrNull() }
            val l = async { playlist(id, fresh = true) }
            d.await() to l.await()
        }
        val title = doc?.selectFirst(".msdb-detail h1")?.text()?.trim()?.ifBlank { null }
            ?: synchronized(catalogue) { catalogue.values.flatMap { it.books } }.firstOrNull { it.id == id }?.title
            ?: throw ErrorLoadingException("Couldn't read this DramaBox title")
        val poster = image(doc?.selectFirst(".msdb-detail-poster img"))
        val plot = doc?.select(".msdb-synopsis p")?.joinToString("\n\n") { it.text() }?.trim()?.ifBlank { null }
        val tags = doc?.select(".msdb-tags span")?.map { it.text().trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val episodes = list.mapIndexed { i, ep ->
            newEpisode(episodeData(id, ep.id, i + 1)) {
                name = "Episode ${i + 1}"
                episode = i + 1
            }
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("This DramaBox title has no episodes yet")
        Log.i(TAG, "DramaBox '$title' ($id): ${episodes.size} episodes")
        return newTvSeriesLoadResponse(title, bookUrl(id), TvType.TvSeries, episodes) {
            posterUrl = poster
            this.plot = plot
            this.tags = listOf("DramaBox") + tags
        }
    }

    // ---- DramaBox playlist ----

    private data class BoxEpisode(val id: String, val sources: Map<String, String>)

    private class CachedPlaylist(val episodes: List<BoxEpisode>, val fetched: Long)

    private val playlists = object : LinkedHashMap<String, CachedPlaylist>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedPlaylist>?) = size > 30
    }

    private suspend fun playlist(book: String, fresh: Boolean = false): List<BoxEpisode> {
        if (!fresh) synchronized(playlists) {
            playlists[book]?.takeIf { System.currentTimeMillis() - it.fetched < PLAYLIST_CACHE_MS }?.let { return it.episodes }
        }
        val res = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            headers = headers + mapOf("Referer" to bookUrl(book), "X-Requested-With" to "XMLHttpRequest"),
            data = mapOf("action" to "msdb_playlist", "book" to book, "lang" to "en"),
            timeout = 30,
        )
        val root = mapper.readTree(res.text)
        if (!root.path("success").asBoolean(false)) {
            throw ErrorLoadingException(root.path("data").path("message").asText("DramaBox didn't return episodes"))
        }
        val episodes = root.path("data").path("episodes").toList().mapNotNull { node ->
            val id = node.text("id") ?: return@mapNotNull null
            val sources = node.path("sources").fields().asSequence()
                .mapNotNull { (k, v) -> v.asText().takeIf { it.startsWith("https://") }?.let { k to it } }
                .toMap()
            BoxEpisode(id, sources)
        }.distinctBy { it.id }
        synchronized(playlists) { playlists[book] = CachedPlaylist(episodes, System.currentTimeMillis()) }
        return episodes
    }

    // ---- links ----

    private suspend fun loadOwnLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        bookId(data)?.let { return loadBookLinks(it, data, callback) }

        val doc = page(data)
        val mirrors = doc.select("[data-em]").mapNotNull { el -> embedSrc(el.attr("data-em")) }.distinct()
        Log.i(TAG, "links ${data.substringAfter("/watch/")}: ${mirrors.size} mirrors ${mirrors.map { host(it) }}")
        val found = coroutineScope {
            mirrors.mapIndexed { index, src ->
                async {
                    withTimeoutOrNull(MIRROR_TIMEOUT_MS) {
                        runCatching { mirror(src, index + 1, data, subtitleCallback, callback) }
                            .onFailure { Log.w(TAG, "mirror ${host(src)}: ${it.message}") }
                            .getOrDefault(0)
                    } ?: 0.also { Log.w(TAG, "mirror ${host(src)}: timed out") }
                }
            }.awaitAll()
        }
        val count = found.sum()
        Log.i(TAG, "links ${data.substringAfter("/watch/")}: $count playable links (${mirrors.zip(found).joinToString { "${host(it.first)}=${it.second}" }})")
        return count > 0
    }

    /** One mirror: sends what plays and returns how many links it sent. */
    private suspend fun mirror(
        src: String,
        index: Int,
        page: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val host = host(src)
        return when {
            SKIPPED_HOSTS.any { host == it || host.endsWith(".$it") } || SKIPPED_PREFIX.any { host.startsWith(it) } -> 0
            host.endsWith("streamain.com") -> streamain(src, callback)
            host == "ok.ru" || host.endsWith(".ok.ru") || host.endsWith("odnoklassniki.ru") -> {
                // CloudStream's extractor first; when it finds nothing (it can't in SkyStream), our own.
                val (sent, _) = viaExtractor(src, page, "ok.ru", verify = false, subtitleCallback, callback)
                if (sent > 0) sent else okru(src, callback)
            }
            VIDHIDE_HOSTS.any { host.endsWith(it) } -> {
                // CloudStream's own extractor where it knows the host, else our unpacker. When the
                // extractor did find links but none play, our unpacker would only find the same ones.
                val (sent, found) = if (BUILT_IN_VIDHIDE.any { host.endsWith(it) }) {
                    viaExtractor(src, page, mirrorName(host), verify = true, subtitleCallback, callback)
                } else 0 to 0
                if (sent > 0 || found > 0) sent else vidhide(src, mirrorName(host), callback)
            }
            else -> {
                // Unknown host: let CloudStream try, then see if it's another VidHide clone.
                val (sent, found) = viaExtractor(src, page, mirrorName(host), verify = true, subtitleCallback, callback)
                if (sent > 0 || found > 0) sent else vidhide(src, mirrorName(host), callback)
            }
        }.also { if (it > 0) Log.i(TAG, "mirror $index ($host): $it links") }
    }

    /** StreamMain: the embed page has a plain <video src> (an S3 MP4) that needs no headers. */
    private suspend fun streamain(src: String, callback: (ExtractorLink) -> Unit): Int {
        val doc = page(src)
        val video = doc.selectFirst("video[src], video source[src]")?.attr("src")?.ifBlank { null }
            ?: doc.selectFirst("video[data-link]")?.attr("data-link")?.ifBlank { null }
            ?: return 0
        if (!plays(video, emptyMap())) return 0
        callback(newExtractorLink(name, "StreamMain", video, ExtractorLinkType.VIDEO) {
            quality = Qualities.Unknown.value
        })
        return 1
    }

    /**
     * A CloudStream extractor; its links are renamed by mirror and (for HLS hosts) checked first,
     * all at once. Returns (links sent, links the extractor found).
     */
    private suspend fun viaExtractor(
        src: String,
        page: String,
        label: String,
        verify: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Pair<Int, Int> {
        val links = ArrayList<ExtractorLink>()
        runCatching { loadExtractor(src, page, subtitleCallback) { synchronized(links) { links += it } } }
            .onFailure { Log.w(TAG, "extractor for ${host(src)}: ${it.message}") }
        val found = synchronized(links) { links.toList() }
        val ok = if (!verify) found.map { true } else coroutineScope {
            found.map { link ->
                async {
                    val h = link.headers + (link.referer.takeIf { it.isNotBlank() }?.let { mapOf("Referer" to it) } ?: emptyMap())
                    plays(link.url, h).also { if (!it) Log.i(TAG, "$label: ${link.name} doesn't play, skipped") }
                }
            }.awaitAll()
        }
        var sent = 0
        found.zip(ok).filter { it.second }.forEach { (link, _) ->
            val q = link.quality
            callback(newExtractorLink(name, if (q > 0) "$label ${q}p" else label, link.url, link.type) {
                referer = link.referer
                quality = q
                headers = link.headers
                extractorData = link.extractorData
            })
            sent++
        }
        return sent to found.size
    }

    /**
     * ok.ru: the embed page's data-options carry the video's metadata, with an HLS manifest and MP4s
     * per size. The links are tied to the User-Agent that fetched the page, so they carry it.
     */
    private suspend fun okru(src: String, callback: (ExtractorLink) -> Unit): Int {
        val doc = page(src)
        val meta = doc.select("[data-options]").asSequence()
            .mapNotNull { el -> runCatching { mapper.readTree(el.attr("data-options")).path("flashvars").path("metadata") }.getOrNull() }
            .map { if (it.isTextual) runCatching { mapper.readTree(it.asText()) }.getOrNull() else it }
            .firstOrNull { it != null && it.isObject }
        if (meta == null) {
            Log.i(TAG, "ok.ru: no metadata (removed or private)")
            return 0
        }
        val h = mapOf("User-Agent" to USER_AGENT)
        Log.i(TAG, "ok.ru: hls=${meta.has("hlsManifestUrl")}, sizes ${meta.path("videos").map { it.text("name") }}")
        var sent = 0
        meta.text("hlsManifestUrl")?.let { hls ->
            callback(newExtractorLink(name, "ok.ru HLS", hls, ExtractorLinkType.M3U8) {
                referer = "https://ok.ru/"
                quality = Qualities.Unknown.value
                headers = h
            })
            sent++
        }
        meta.path("videos").toList().mapNotNull { v ->
            val url = v.text("url") ?: return@mapNotNull null
            val q = OKRU_SIZES[v.text("name")?.lowercase()] ?: return@mapNotNull null
            q to url
        }.sortedByDescending { it.first }.filter { it.first >= 360 }.take(3).forEach { (q, url) ->
            callback(newExtractorLink(name, "ok.ru ${q}p", url, ExtractorLinkType.VIDEO) {
                referer = "https://ok.ru/"
                quality = q
                headers = h
            })
            sent++
        }
        return sent
    }

    /**
     * VidHide family (Smoothpre, Ryderjet → Callistanise, Peytonepre, Bingezove, Dingtezuni, Dhtpre):
     * the embed page hides its player setup in p,a,c,k,e,d-packed JS, with up to three m3u8s
     * ("hls4" on the player's own host, "hls2" and "hls3" on CDNs). Each is checked down to its first
     * segment, since these CDNs are often down.
     */
    private suspend fun vidhide(src: String, label: String, callback: (ExtractorLink) -> Unit): Int {
        val res = app.get(src, headers = headers, timeout = 20)
        val final = res.url.ifBlank { src }
        val html = res.text
        if (html.contains("no longer available", true)) {
            Log.i(TAG, "$label: file deleted")
            return 0
        }
        val unpacked = Regex("""eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).mapNotNull { unpack(it.value) }.joinToString("\n")
        val script = unpacked.ifBlank { html }
        val streams = Regex(""""(hls\d)"\s*:\s*"([^"]+)"""").findAll(script).associate { it.groupValues[1] to it.groupValues[2] }
            .ifEmpty {
                Regex("""file\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(script)?.let { mapOf("hls" to it.groupValues[1]) } ?: emptyMap()
            }
        if (streams.isEmpty()) return 0
        val origin = origin(final)
        val h = mapOf("Referer" to "$origin/", "Origin" to origin, "User-Agent" to USER_AGENT)
        val candidates = listOf("hls4", "hls2", "hls3", "hls").mapNotNull { key -> streams[key]?.let { key to resolve(final, it) } }
        val ok = coroutineScope { candidates.map { (_, url) -> async { plays(url, h) } }.awaitAll() }
        var sent = 0
        for ((i, pair) in candidates.withIndex()) {
            val (key, url) = pair
            if (!ok[i]) {
                Log.i(TAG, "$label $key doesn't play, skipped")
                continue
            }
            callback(newExtractorLink(name, if (sent == 0) label else "$label ($key)", url, ExtractorLinkType.M3U8) {
                referer = "$origin/"
                quality = Qualities.Unknown.value
                headers = mapOf("Origin" to origin, "User-Agent" to USER_AGENT)
            })
            sent++
        }
        return sent
    }

    private suspend fun loadBookLinks(book: String, data: String, callback: (ExtractorLink) -> Unit): Boolean {
        val chapter = Regex("""msdb_episode=(\d+)""").find(data)?.groupValues?.get(1)
        val n = Regex("""[#&]n=(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull()
        fun pick(list: List<BoxEpisode>) = list.firstOrNull { it.id == chapter } ?: n?.let { list.getOrNull(it - 1) }
        var ep = pick(playlist(book))
        if (ep == null || ep.sources.isEmpty()) ep = pick(playlist(book, fresh = true))
        if (ep == null) {
            Log.w(TAG, "DramaBox $book: episode $chapter (#$n) not in the playlist")
            return false
        }
        var count = 0
        val seen = HashSet<String>()
        for (key in listOf("1080", "720", "540", "auto")) {
            val url = ep.sources[key] ?: continue
            if (!seen.add(url)) continue
            callback(newExtractorLink(name, if (key == "auto") "DramaBox" else "DramaBox ${key}p", url, ExtractorLinkType.VIDEO) {
                quality = key.toIntOrNull() ?: Qualities.Unknown.value
            })
            count++
        }
        Log.i(TAG, "DramaBox $book ep ${ep.id}: $count links")
        return count > 0
    }

    // ---- checks ----

    /** Whether a stream answers with video: an MP4/TS's first bytes, or an m3u8 down to its first segment. */
    private suspend fun plays(url: String, h: Map<String, String>): Boolean = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
        runCatching {
            val reqHeaders = mapOf("User-Agent" to USER_AGENT) + h
            if (url.contains(".m3u8", true) || url.contains("/hls", true) || url.contains("master.txt", true)) {
                var playlistUrl = url
                var text = app.get(playlistUrl, headers = reqHeaders, timeout = 15).let {
                    if (!it.isSuccessful) return@runCatching false
                    playlistUrl = it.url.ifBlank { playlistUrl }
                    it.text
                }
                if (!text.contains("#EXTM3U")) return@runCatching false
                if (text.contains("#EXT-X-STREAM-INF")) {
                    val variant = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                        ?: return@runCatching false
                    playlistUrl = resolve(playlistUrl, variant)
                    text = app.get(playlistUrl, headers = reqHeaders, timeout = 15).let {
                        if (!it.isSuccessful) return@runCatching false
                        it.text
                    }
                }
                val segment = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    ?: return@runCatching false
                looksLikeVideo(resolve(playlistUrl, segment), reqHeaders)
            } else {
                looksLikeVideo(url, reqHeaders)
            }
        }.getOrDefault(false)
    } ?: false

    private suspend fun looksLikeVideo(url: String, h: Map<String, String>): Boolean {
        val res = app.get(url, headers = h + mapOf("Range" to "bytes=0-511"), timeout = 15)
        if (!res.isSuccessful) return false
        val head = res.body.byteStream().use { s ->
            val buf = ByteArray(64)
            var read = 0
            while (read < buf.size) {
                val n = s.read(buf, read, buf.size - read)
                if (n <= 0) break
                read += n
            }
            buf.copyOf(read)
        }
        if (head.isEmpty()) return false
        val start = String(head, Charsets.ISO_8859_1).trimStart().lowercase()
        // Error pages ("error code: 522", HTML) aren't video; anything binary is taken as video.
        return !(start.startsWith("<") || start.startsWith("error") || start.startsWith("{") || start.startsWith("#extm3u"))
    }

    // ---- helpers ----

    /** data-em is a base64 <iframe>; returns its src. */
    private fun embedSrc(b64: String): String? {
        val html = runCatching { base64Decode(b64.trim()) }.getOrNull() ?: return null
        val src = Regex("""(?i)\bsrc\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: html.trim().takeIf { it.startsWith("http") || it.startsWith("//") }
            ?: return null
        val s = Jsoup.parse(src).text().trim()
        return when {
            s.startsWith("//") -> "https:$s"
            s.startsWith("http") -> s
            else -> null
        }
    }

    /** Our own unpacker for Dean Edwards' p,a,c,k,e,d packer. */
    private fun unpack(packed: String): String? {
        val m = Regex("""\}\('(.*)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL).find(packed) ?: return null
        val payload = m.groupValues[1].replace("\\'", "'")
        val radix = m.groupValues[2].toIntOrNull() ?: return null
        val words = m.groupValues[4].split('|')
        if (radix !in 2..62) return null
        return Regex("""\b\w+\b""").replace(payload) { word ->
            var n = 0L
            for (ch in word.value) {
                val d = DIGITS.indexOf(ch)
                if (d < 0 || d >= radix) return@replace word.value
                n = n * radix + d
                if (n > Int.MAX_VALUE) return@replace word.value
            }
            words.getOrNull(n.toInt())?.ifEmpty { null } ?: word.value
        }
    }

    private fun mirrorName(host: String): String = when {
        host.endsWith("smoothpre.com") -> "Smoothpre"
        host.endsWith("ryderjet.com") || host.endsWith("callistanise.com") -> "Ryderjet"
        host.endsWith("peytonepre.com") -> "Peytonepre"
        host.endsWith("bingezove.com") -> "Bingezove"
        host.endsWith("dingtezuni.com") || host.endsWith("dintezuvio.com") -> "Dingtezuni"
        host.endsWith("dhtpre.com") -> "Dhtpre"
        else -> host.removePrefix("www.").substringBeforeLast('.').substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    private fun bookId(url: String): String? = Regex("""[?&]msdb_book=(\d+)""").find(url)?.groupValues?.get(1)

    private fun bookUrl(id: String) = "$mainUrl/?msdb_book=$id&msdb_lang=en"

    private fun episodeData(book: String, chapter: String, n: Int) = "$mainUrl/?msdb_book=$book&msdb_episode=$chapter&msdb_lang=en#n=$n"

    private fun image(img: Element?): String? {
        img ?: return null
        val src = listOf("data-src", "data-lazy-src", "src").map { img.attr(it).trim() }.firstOrNull { it.startsWith("http") }
            ?: return null
        return src.replace("&#038;", "&")
    }

    private fun link(a: Element): String = resolve(mainUrl + "/", a.attr("href").trim())

    private fun host(url: String): String = runCatching { URI(url).host?.lowercase() }.getOrNull().orEmpty()

    private fun origin(url: String): String = runCatching { URI(url).let { "${it.scheme}://${it.host}" } }.getOrDefault(url)

    private fun resolve(base: String, ref: String): String = runCatching { URI(base).resolve(ref.replace(" ", "%20")).toString() }.getOrDefault(ref)

    private suspend fun page(url: String): Document {
        val res = app.get(url, headers = headers, timeout = 30)
        if (!res.isSuccessful) throw ErrorLoadingException("DramaFren replied ${res.code}")
        return res.document
    }

    private fun isAdult(title: String) = ADULT.containsMatchIn(title)

    private fun normalize(s: String): String = s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun JsonNode.text(field: String): String? =
        path(field).takeIf { it.isTextual || it.isNumber }?.asText()?.trim()?.ifBlank { null }

    companion object {
        private const val TAG = "Dramafren"
        private const val ROW_LATEST = "latest"
        private const val ROW_MORE = "more"
        private const val ROW_DRAMABOX_MORE = "dramabox-more"
        private const val ROW_POPULAR = "popular"
        private const val ROW_DRAMABOX = "dramabox"
        private const val SECTION_CHINESE = "chinese"
        private const val SECTION_DRAMABOX = "dramabox"
        private const val STATUS = "status"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
        private const val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        private const val CATALOGUE_CACHE_MS = 30 * 60_000L
        private const val PLAYLIST_CACHE_MS = 10 * 60_000L
        private const val CATALOGUE_PARALLEL = 8
        private const val CATALOGUE_TIMEOUT_MS = 25_000L
        private const val MAX_CATALOGUE_PAGES = 120
        private const val MAX_DRAMABOX_RESULTS = 40
        private const val MIRROR_TIMEOUT_MS = 22_000L
        private const val CHECK_TIMEOUT_MS = 8_000L

        private val OKRU_SIZES = mapOf(
            "mobile" to 144, "lowest" to 240, "low" to 360, "sd" to 480, "hd" to 720, "full" to 1080, "quad" to 1440, "ultra" to 2160,
        )

        /** VidHide clones; CloudStream has extractors for the first three. */
        private val BUILT_IN_VIDHIDE = listOf("smoothpre.com", "ryderjet.com", "dhtpre.com")
        private val VIDHIDE_HOSTS = BUILT_IN_VIDHIDE + listOf("callistanise.com", "peytonepre.com", "bingezove.com", "dingtezuni.com", "dintezuvio.com")

        /**
         * Mirrors that don't play: Abyss (encrypted), TeraBox (login), the site's own dead players,
         * dead DNS, and boosterx/newer/oiiaoiia. Google Drive previews need a Google session.
         */
        private val SKIPPED_HOSTS = listOf(
            "abyssplayer.com", "abyss.to", "1024tera.com", "terabox.com", "rpmstream.online", "playerp2p.online",
            "movearnpre.com", "sinemasub.com", "boosterx.stream", "newer.stream", "oiiaoiia.stream", "drive.google.com",
        )
        private val SKIPPED_PREFIX = listOf("dramafren", "mydramafren")

        private val FULL = Regex("""(?i)full(\s+episodes?)?(\s+end)?""")
        private val SEO_FILLER = Regex("""(?i)^watch .*(full movie|chinese drama)""")

        /** Only clearly explicit words in the title; romance shorts' titles stay, synopses aren't checked. */
        private val ADULT = Regex(
            """18\+|\bnsfw\b|erotic|porn|\bxxx\b|hentai|\bnude\b|\bnudity\b|\bnaked\b|\bbdsm\b""",
            RegexOption.IGNORE_CASE,
        )
    }
}
