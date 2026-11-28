package com.animepahe

import com.csasian.anime.MegaPlay
import com.csasian.shared.CsBackup
import com.csasian.shared.CsBrowse
import com.csasian.shared.CsCategories
import com.csasian.shared.CsMore
import com.csasian.shared.CsSearch
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.util.Base64

/**
 * Animepahe (animepahe.ch): a WordPress anime site (not the animepahe.ru / kwik one).
 *
 *  - Lists:    /series/?type=&status=&genre[]=&order=&page=N  (article.bs cards)
 *  - Search:   /?s=query, /page/N/?s=query
 *  - Series:   /series/{slug}/ — info block and .eplister (newest first). English dubs are their
 *              own series, titled "… (Dub)".
 *  - Episode:  /{slug}-episode-N-english-subbed/ — the page's iframe plus select.mirror options
 *              (each value is base64 of an <iframe>).
 *
 * Players: mostly Blogger (www.blogger.com/video.g), whose page lists direct googlevideo MP4s
 * (itag 22 = 720p, itag 18 = 360p). Those addresses are bound to the viewer's IP, expire after
 * about 8 hours and answer 403 to non-browser User-Agents, so they are read fresh on every play
 * and handed on with a browser User-Agent. Some episodes use MegaPlay (shared/_anime-common).
 * Hosts that are gone or unusable here (livesportshd's parked m3u8 domain, megaup.cc down, the
 * vkspeed ad clip, vdohide/fembed, the gogo "streaming.php" mirrors) are skipped.
 */
class AnimepaheProvider : MainAPI() {
    override var mainUrl = "https://animepahe.ch"
    override var name = "Animepahe CS"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val userAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val siteHeaders get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")

    private companion object {
        /** Genres left out everywhere (their titles are hidden too). */
        val ADULT_GENRES = listOf("erotica", "hentai")

        val GENRES = listOf(
            "action" to "Action", "adult-cast" to "Adult Cast", "adventure" to "Adventure", "avant-garde" to "Avant Garde",
            "award-winning" to "Award Winning", "boys-love" to "Boys Love", "comedy" to "Comedy", "detective" to "Detective",
            "drama" to "Drama", "ecchi" to "Ecchi", "fantasy" to "Fantasy", "girls-love" to "Girls Love", "gore" to "Gore",
            "gourmet" to "Gourmet", "harem" to "Harem", "historical" to "Historical", "horror" to "Horror",
            "idols-female" to "Idols (Female)", "idols-male" to "Idols (Male)", "isekai" to "Isekai",
            "iyashikei" to "Iyashikei", "josei" to "Josei", "kids" to "Kids", "mahou-shoujo" to "Mahou Shoujo",
            "martial-arts" to "Martial Arts", "mecha" to "Mecha", "medical" to "Medical", "military" to "Military",
            "music" to "Music", "mystery" to "Mystery", "mythology" to "Mythology", "parody" to "Parody",
            "psychological" to "Psychological", "racing" to "Racing", "reincarnation" to "Reincarnation",
            "romance" to "Romance", "samurai" to "Samurai", "school" to "School", "sci-fi" to "Sci-Fi", "seinen" to "Seinen",
            "shoujo" to "Shoujo", "shounen" to "Shounen", "slice-of-life" to "Slice of Life", "space" to "Space",
            "sports" to "Sports", "super-power" to "Super Power", "supernatural" to "Supernatural", "survival" to "Survival",
            "suspense" to "Suspense", "time-travel" to "Time Travel", "urban-fantasy" to "Urban Fantasy",
            "vampire" to "Vampire", "video-game" to "Video Game", "villainess" to "Villainess", "workplace" to "Workplace",
        )

        /** The list page's type= per category section. */
        val SECTION_TYPES = mapOf("tv" to "anime", "movie" to "movie", "special" to "special")

        /** Blogger itags: progressive MP4s only (the rest are SABR streams no player here can use). */
        val BLOGGER_ITAGS = mapOf(22 to Qualities.P720.value, 18 to Qualities.P360.value)

        /** Blogger uploads this short are cut-off clips, not the episode: ranked after other players. */
        const val CLIP_MS = 5 * 60_000L

        val DUB_TITLE = Regex("""\(\s*dub\s*\)|\bdub\s*$""", RegexOption.IGNORE_CASE)
        val DUB_URL = Regex("""-dub(?:bed)?(?:-|/|$)""", RegexOption.IGNORE_CASE)
        val SKIPPED_HOSTS = Regex("""livesportshd\.|megaup\.|vkspeed\.|fembed\.|vdohide|embtaku\.|s3taku\.|gogoanime\.|filemoon\.""")
    }

    // ---------------------------------------------------------------- categories

    private fun sorts() = listOf(
        CsCategories.Option("latest", "最新 Newest"),
        CsCategories.Option("update", "最近更新 Last updated"),
        CsCategories.Option("popular", "热门 Popular"),
        CsCategories.Option("rating", "高分 Top rated"),
        CsCategories.Option("title", "片名 A-Z"),
    )

    private fun filters() = listOf(
        CsCategories.Filter("genre", "题材 Genre", GENRES.map { (v, l) -> CsCategories.Option(v, l) }),
        CsCategories.Filter(
            "status", "状态 Status",
            listOf("ongoing" to "Ongoing", "completed" to "Completed", "upcoming" to "Upcoming")
                .map { (v, l) -> CsCategories.Option(v, l) },
        ),
    )

    private val browse by lazy {
        CsBrowse(
            this, name,
            listOf(
                CsBrowse.Section("tv", "动漫", filters(), sorts(), english = "TV Series", glyph = "🐉"),
                CsBrowse.Section("movie", "剧场版", filters(), sorts(), english = "Anime Movies", glyph = "🎬"),
                CsBrowse.Section("special", "OVA 特别篇", filters(), sorts(), english = "Specials", glyph = "🎞"),
            ),
            popularSort = "popular",
        ) { id, filters, query, page ->
            val type = SECTION_TYPES[id] ?: "anime"
            if (query.isNotBlank()) {
                // The list page has no keyword: search the site and keep this section's type.
                val (items, hasNext) = searchOwn(query, page)
                return@CsBrowse items.filter { it.type == tvTypeOf(type) } to hasNext
            }
            val params = buildList {
                add("type=$type")
                filters["genre"]?.let { add("genre%5B%5D=$it") }
                filters["status"]?.let { add("status=$it") }
                add("order=" + (filters[CsCategories.SORT] ?: "latest"))
            }
            listPage(params.joinToString("&"), page)
        }
    }

    fun csCategories(): String = browse.categoriesJson()

    fun csCategoryPage(categoryId: String, filtersJson: String, query: String, page: Int): SearchResponseList =
        browse.categoryPage(categoryId, filtersJson, query, page)

    // ---------------------------------------------------------------- home

    private val rows = listOf(
        MainPageData("Recently Updated", "order=update"),
        MainPageData("Popular", "order=popular"),
        MainPageData("Ongoing", "status=ongoing&order=popular"),
        MainPageData("New Anime", "order=latest"),
        MainPageData("Top Rated", "order=rating"),
        MainPageData("Movies", "type=movie&order=update"),
    )

    // On CloudStream the "分类 Categories" row comes first.
    override val mainPage: List<MainPageData> by lazy { browse.mainPage(rows) }

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        browse.rowPage(request) ?: CsMore.wrap(this, request, page, mainPageOwn(page, request))

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        val (items, hasNext) = listPage(request.data, page)
        return newHomePageResponse(request.name, items, hasNext)
    }

    private suspend fun listPage(params: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val document = app.get("$mainUrl/series/?page=$page&$params", headers = siteHeaders).document
        val items = cards(document)
        val hasNext = document.selectFirst(".hpage a.r, .pagination a.next") != null
        return items to (items.isNotEmpty() && hasNext)
    }

    // ---------------------------------------------------------------- search

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (items, hasNext) = searchPage(query, page)
        return newSearchResponseList(items, hasNext)
    }

    // Page 1 also searches the English title when the query is in another language (shared/CsSearch.kt).
    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> =
        CsSearch.withExtra(query, page, lang) { q, p -> searchOwn(q, p) }

    private suspend fun searchOwn(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page > 1) "$mainUrl/page/$page/?s=$q" else "$mainUrl/?s=$q"
        val response = app.get(url, headers = siteHeaders)
        if (response.code == 404) return emptyList<SearchResponse>() to false // past the last page
        val document = response.document
        val items = cards(document)
        val hasNext = document.selectFirst(".pagination a.next, .hpage a.r") != null
        return items to (items.isNotEmpty() && hasNext)
    }

    // ---------------------------------------------------------------- cards

    private suspend fun cards(document: Document): List<SearchResponse> {
        val blocked = blockedSlugs()
        return document.select("article.bs").mapNotNull { it.toCard(blocked) }.distinctBy { it.url }
    }

    private fun Element.toCard(blocked: Set<String>): SearchResponse? {
        val a = selectFirst("a[href*='/series/']") ?: return null
        val href = a.attr("abs:href").ifBlank { return null }
        val title = Parser.unescapeEntities(a.attr("title").ifBlank { selectFirst("h2")?.text().orEmpty() }, false).trim()
        if (title.isBlank() || isBlocked(href, title, blocked)) return null
        val type = tvTypeOf(selectFirst(".typez")?.text().orEmpty())
        val dub = DUB_TITLE.containsMatchIn(title) || selectFirst(".sb.Dub") != null
        return newAnimeSearchResponse(title, href, type) {
            posterUrl = imageOf(selectFirst("img"))
            addDubStatus(dub, !dub)
        }
    }

    private var blocked: Pair<Long, Set<String>>? = null

    /** Slugs of the titles in the left-out genres (their genre pages), refreshed hourly. */
    private suspend fun blockedSlugs(): Set<String> {
        blocked?.let { (at, set) -> if (System.currentTimeMillis() - at < 3_600_000L) return set }
        val set = runCatching {
            coroutineScope {
                ADULT_GENRES.map { genre -> async { genreSlugs(genre) } }.awaitAll().flatten().toSet()
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return blocked?.second.orEmpty()
        blocked = System.currentTimeMillis() to set
        return set
    }

    private suspend fun genreSlugs(genre: String): List<String> {
        val slugs = mutableListOf<String>()
        for (page in 1..10) {
            val url = if (page == 1) "$mainUrl/genres/$genre/" else "$mainUrl/genres/$genre/page/$page/"
            val response = app.get(url, headers = siteHeaders, allowRedirects = false)
            if (response.code != 200) break // e.g. a genre the site doesn't have redirects home
            val document = response.document
            val found = document.select("article.bs a[href*='/series/']").mapNotNull { slugOf(it.attr("abs:href")) }
            slugs += found
            if (found.isEmpty() || document.selectFirst(".pagination a.next, .hpage a.r") == null) break
        }
        return slugs
    }

    private fun isBlocked(url: String, title: String, blocked: Set<String>): Boolean {
        if (CsCategories.isAdultTitle(title)) return true
        val slug = slugOf(url) ?: return false
        return slug in blocked
    }

    // ---------------------------------------------------------------- details

    // Remembers episodes and adds backup links from our other sources (see shared/CsBackup.kt).
    // A "分类 Categories" card opens the category page (browse.load first: it checks who is asking).
    override suspend fun load(url: String): LoadResponse =
        browse.load(url) ?: CsMore.load(this, url) ?: loadPage(url).also { CsBackup.remember(this, it) }

    private suspend fun loadPage(url: String): LoadResponse {
        var seriesUrl = url
        if (!url.contains("/series/")) {
            // An episode page: open its series, which lists every episode.
            val document = app.get(url, headers = siteHeaders).document
            seriesUrl = document.selectFirst(".year a[href*='/series/'], .nvs a[href*='/series/']")?.attr("abs:href")
                ?: throw ErrorLoadingException("Anime not found")
        }
        val document = app.get(seriesUrl, headers = siteHeaders).document
        val info = document.selectFirst(".infox") ?: throw ErrorLoadingException("Anime not found")
        val title = info.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
            .ifBlank { throw ErrorLoadingException("Anime title not found") }
        val genres = info.select(".genxed a")
        if (CsCategories.isAdultTitle(title) ||
            genres.any { g -> ADULT_GENRES.any { g.attr("href").contains("/genres/$it/") } }
        ) throw ErrorLoadingException("Not available")

        fun field(label: String): String? = info.select(".spe span").firstOrNull {
            it.selectFirst("b")?.text()?.trim()?.removeSuffix(":")?.equals(label, true) == true
        }?.text()?.substringAfter(':')?.trim()?.ifBlank { null }

        val type = tvTypeOf(field("Type").orEmpty())
        val poster = imageOf(document.selectFirst(".thumb img"))
        val plot = document.select(".entry-content[itemprop=description] p").joinToString("\n\n") { it.text().trim() }
            .ifBlank { document.selectFirst(".entry-content[itemprop=description]")?.text()?.trim().orEmpty() }
            .ifBlank { null }
        val year = (field("Released") ?: field("Season"))?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
        val status = when (field("Status")?.lowercase()) {
            "ongoing", "upcoming" -> ShowStatus.Ongoing
            "completed" -> ShowStatus.Completed
            else -> null
        }
        val duration = field("Duration")?.let { d ->
            val hours = Regex("""(\d+)\s*hr""").find(d)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val minutes = Regex("""(\d+)\s*min""").find(d)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            (hours * 60 + minutes).takeIf { it > 0 }
        }
        val score = document.selectFirst(".rating meta[itemprop=ratingValue]")?.attr("content")?.toDoubleOrNull()
            ?.takeIf { it > 0 }
        val synonyms = info.selectFirst(".alter")?.text()?.split(',')
            ?.map { it.trim() }?.filter { it.isNotBlank() && it != title }?.distinct().orEmpty()
        val tags = genres.map { it.text().trim() }.filter { it.isNotBlank() }
        val isDub = DUB_TITLE.containsMatchIn(title)

        val episodes = document.select(".eplister li a[href]").mapNotNull { a ->
            val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
            val num = a.selectFirst(".epl-num")?.text()?.trim().orEmpty()
            val epTitle = a.selectFirst(".epl-title")?.text()?.trim().orEmpty()
            Triple(href, num, epTitle)
        }.distinctBy { it.first }.reversed() // The site lists the newest first.

        val blocked = blockedSlugs()
        val recommendations = document.select(".listupd article.bs").mapNotNull { it.toCard(blocked) }
            .filter { it.url != seriesUrl }.distinctBy { it.url }

        if (type == TvType.AnimeMovie && episodes.size <= 1) {
            val movieUrl = episodes.firstOrNull()?.first ?: throw ErrorLoadingException("No video yet (not released)")
            return newMovieLoadResponse(title, seriesUrl, TvType.AnimeMovie, movieUrl) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = duration
                this.score = score?.let { Score.from10(it) }
                this.recommendations = recommendations
            }
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("No episodes yet")

        val list = episodes.map { (href, num, epTitle) ->
            newEpisode(href) {
                episode = num.toIntOrNull()
                name = when {
                    episode != null -> epTitle.takeUnless { Regex("""(?i)\bEpisode\s*\d+\b""").containsMatchIn(it) }
                        ?.ifBlank { null }
                    num.isNotBlank() -> "Episode $num"
                    else -> epTitle.ifBlank { null }
                }
            }
        }
        return newAnimeLoadResponse(title, seriesUrl, type) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.synonyms = synonyms
            this.duration = duration
            this.score = score?.let { Score.from10(it) }
            showStatus = status
            this.recommendations = recommendations
            addEpisodes(if (isDub) DubStatus.Dubbed else DubStatus.Subbed, list)
        }
    }

    // ---------------------------------------------------------------- links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        browse.reopen(data) // ▶ on a category's own page opens the category page again.
        return CsBackup.loadLinks(this, data, subtitleCallback, callback) { callback ->
            loadOwnLinks(data, subtitleCallback, callback)
        }
    }

    private data class Player(val label: String, val url: String, val order: Int)

    /** Links of one player, and whether they are a cut-off Blogger clip (ranked last). */
    private data class Found(val links: List<ExtractorLink>, val clip: Boolean)

    private suspend fun loadOwnLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data, headers = siteHeaders).document
        val players = players(document).filterNot { SKIPPED_HOSTS.containsMatchIn(it.url) }
        if (players.isEmpty()) return false
        val audio = if (DUB_URL.containsMatchIn(data)) "English Dub" else "English Sub"

        // Every player is read fresh (Blogger's MP4 addresses are IP-bound and expire), all at once;
        // links are handed on in page order with Blogger 720p before 360p, short clips last.
        val found = coroutineScope {
            players.map { player ->
                async {
                    val links = mutableListOf<ExtractorLink>()
                    var clip = false
                    withTimeoutOrNull(15_000L) {
                        runCatching {
                            clip = resolve(player, audio, subtitleCallback) { synchronized(links) { links += it } }
                        }
                    }
                    Found(synchronized(links) { links.toList() }, clip)
                }
            }.awaitAll()
        }
        val ordered = found.filter { !it.clip } + found.filter { it.clip }
        val seen = mutableSetOf<String>()
        var any = false
        ordered.flatMap { it.links }.forEach { link ->
            // Several mirrors are often the same Blogger video: one link per video id and quality.
            val id = if (link.url.contains("googlevideo.com")) Regex("""[?&]id=([^&]+)""").find(link.url)?.groupValues?.get(1) else null
            if (seen.add((id ?: link.url) + "|" + link.quality)) {
                callback(link)
                any = true
            }
        }
        return any
    }

    /** The page's own iframe first, then the select.mirror options (base64 iframes). */
    private fun players(document: Document): List<Player> {
        val players = mutableListOf<Player>()
        document.select("#pembed iframe, .player-embed iframe, .video-content iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) players += Player("", fixUrlOf(Parser.unescapeEntities(src, true).trim()), players.size)
        }
        document.select("select.mirror option[value]").forEach { option ->
            val value = option.attr("value").trim().ifBlank { return@forEach }
            val html = runCatching { String(Base64.getDecoder().decode(value)) }.getOrNull() ?: return@forEach
            val src = Regex("""(?i)src\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: html.trim().takeIf { it.startsWith("http") || it.startsWith("//") }
                ?: return@forEach
            players += Player(option.text().trim(), fixUrlOf(Parser.unescapeEntities(src, true).trim()), players.size)
        }
        return players.distinctBy { it.url.trim() }
    }

    /** Resolves one player; true when it is a Blogger upload too short to be the episode. */
    private suspend fun resolve(
        player: Player,
        audio: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val url = player.url
        val server = player.label.ifBlank { if (url.contains("blogger.com")) "Blogger" else hostName(url) }
        val label = "$name $server ($audio)"
        when {
            url.contains("blogger.com/video") -> return blogger(url, "$name ${player.label.ifBlank { "Blogger" }}", audio, callback)
            url.contains("megaplay.") && url.contains("/stream/") ->
                MegaPlay.extract(url, name, label, "$mainUrl/", userAgent, subtitleCallback, callback)
            else -> loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
        }
        return false
    }

    /**
     * Blogger's video page lists its progressive MP4s as `["https://…googlevideo.com/videoplayback?…",[itag]]`
     * (HTML-escaped). Only itag 22 (720p) and 18 (360p) play as plain files.
     */
    private suspend fun blogger(url: String, label: String, audio: String, callback: (ExtractorLink) -> Unit): Boolean {
        val raw = app.get(url, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")).text
        val text = unicodeUnescape(Parser.unescapeEntities(raw, false))
        val streams = Regex("""\["(https://[^"]+?googlevideo\.com/videoplayback[^"]*)",\s*\[(\d+)]]""")
            .findAll(text)
            .mapNotNull { m -> BLOGGER_ITAGS[m.groupValues[2].toInt()]?.let { q -> m.groupValues[1] to q } }
            .distinctBy { it.second }
            .sortedByDescending { it.second }
            .toList()
        if (streams.isEmpty()) return false
        val durationMs = Regex("""approxDurationMs\\*"\s*:\s*\\*"(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
        val clip = durationMs != null && durationMs < CLIP_MS
        val clipNote = if (clip) " · ${(durationMs!! / 60_000L).coerceAtLeast(1)} min clip" else ""
        streams.forEach { (file, quality) ->
            callback(
                newExtractorLink(name, "$label ${quality}p ($audio)$clipNote", file, ExtractorLinkType.VIDEO) {
                    referer = ""
                    this.quality = quality
                    // googlevideo refuses non-browser User-Agents (e.g. the player's default one).
                    headers = mapOf("User-Agent" to userAgent)
                },
            )
        }
        return clip
    }

    private fun unicodeUnescape(s: String): String =
        Regex("""\\u([0-9a-fA-F]{4})""").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }

    // ---------------------------------------------------------------- helpers

    private fun tvTypeOf(text: String): TvType = when (text.trim().lowercase()) {
        "movie" -> TvType.AnimeMovie
        "special", "ova", "ona" -> TvType.OVA
        else -> TvType.Anime
    }

    private fun hostName(url: String): String =
        Regex("""^https?://(?:www\.)?([^./]+)""").find(url)?.groupValues?.get(1)?.replaceFirstChar { it.uppercase() } ?: "Server"

    private fun slugOf(url: String): String? =
        Regex("""/series/([^/?#]+)""").find(url)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    private fun fixUrlOf(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$mainUrl$url"
        else -> url
    }

    private fun imageOf(img: Element?): String? {
        if (img == null) return null
        return listOf(img.attr("data-src"), img.attr("data-lazy-src"), img.attr("src"))
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { fixUrlOf(it) }
    }
}
