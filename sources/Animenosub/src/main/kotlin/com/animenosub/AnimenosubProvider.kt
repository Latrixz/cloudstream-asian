package com.animenosub

import com.csasian.anime.MegaPlay
import com.csasian.shared.CsBackup
import com.csasian.shared.CsBrowse
import com.csasian.shared.CsCategories
import com.csasian.shared.CsMore
import com.csasian.shared.CsSearch
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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.util.Base64

/**
 * Animenosub (animenosub.to): a WordPress anime site.
 *
 *  - Lists:    /anime/?type=&status=&sub=&genre[]=&season[]=&order=&page=N  (article.bs cards)
 *  - Search:   /?s=query, /page/N/?s=query
 *  - Series:   /anime/{slug}/ — info block and .eplister (newest first)
 *  - Episode:  /{slug}-episode-N/ — select.mirror options; each value is base64 of an <iframe>.
 *              Labels read "SUB - Omega", "DUB - Nova", "RAW - Moon": one episode often has the
 *              English dub, the English sub and the raw Japanese on the same page.
 *
 * Players that work: Vidmoly ("Omega", HLS in the embed page), Vidaraa ("Nova", POST /api/stream
 * returns the HLS master) and MegaPlay (older episodes, "HD"/"Beta"; play.php wraps it). Byse
 * ("Moon"/FileMoon) needs a browser device attestation, vtube is behind a Cloudflare block and
 * StreamSB is gone, so those are skipped.
 */
class AnimenosubProvider : MainAPI() {
    override var mainUrl = "https://animenosub.to"
    override var name = "Animenosub CS"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val userAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val siteHeaders get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
    private val mapper = ObjectMapper()

    private companion object {
        /** Genres left out everywhere (their titles are hidden too). */
        val ADULT_GENRES = listOf("hentai", "erotica")

        val GENRES = listOf(
            "action" to "Action", "adult-cast" to "Adult Cast", "adventure" to "Adventure", "avant-garde" to "Avant Garde",
            "award-winning" to "Award Winning", "boys-love" to "Boys Love", "comedy" to "Comedy", "crime" to "Crime",
            "demons" to "Demons", "detective" to "Detective", "drama" to "Drama", "ecchi" to "Ecchi", "family" to "Family",
            "fantasy" to "Fantasy", "game" to "Game", "girls-love" to "Girls Love", "gore" to "Gore", "gourmet" to "Gourmet",
            "harem" to "Harem", "historical" to "Historical", "horror" to "Horror", "idols-female" to "Idols (Female)",
            "idols-male" to "Idols (Male)", "isekai" to "Isekai", "iyashikei" to "Iyashikei", "josei" to "Josei",
            "kids" to "Kids", "mahou-shoujo" to "Mahou Shoujo", "martial-arts" to "Martial Arts", "mecha" to "Mecha",
            "medical" to "Medical", "military" to "Military", "music" to "Music", "mystery" to "Mystery",
            "mythology" to "Mythology", "parody" to "Parody", "police" to "Police", "psychological" to "Psychological",
            "racing" to "Racing", "reincarnation" to "Reincarnation", "romance" to "Romance", "samurai" to "Samurai",
            "school" to "School", "sci-fi" to "Sci-Fi", "seinen" to "Seinen", "shoujo" to "Shoujo", "shounen" to "Shounen",
            "slice-of-life" to "Slice of Life", "space" to "Space", "sports" to "Sports", "super-power" to "Super Power",
            "supernatural" to "Supernatural", "survival" to "Survival", "suspense" to "Suspense", "thriller" to "Thriller",
            "time-travel" to "Time Travel", "urban-fantasy" to "Urban Fantasy", "vampire" to "Vampire",
            "video-game" to "Video Game", "villainess" to "Villainess", "workplace" to "Workplace",
        )

        /** The list page's type= per category section (the first is the default). */
        val SECTION_TYPES = mapOf(
            "tv" to listOf("tv"),
            "movie" to listOf("movie"),
            "ova" to listOf("ova", "ona", "special"),
        )

        /**
         * Audio order of the episode page's players: English dub first, then sub, then raw. Older
         * pages label players with the host only ("Omega"); there the episode's address tells
         * ("…-english-dubbed/", "one-piece-dub-episode-…").
         */
        fun audioRank(label: String, episodeUrl: String): Int = when {
            label.startsWith("DUB", true) -> 0
            label.startsWith("SUB", true) -> 1
            label.startsWith("RAW", true) -> 2
            DUB_URL.containsMatchIn(episodeUrl) -> 0
            else -> 1
        }

        val DUB_URL = Regex("""-dub(?:bed)?(?:-|/|$)""", RegexOption.IGNORE_CASE)
    }

    // ---------------------------------------------------------------- categories

    private fun sorts() = listOf(
        CsCategories.Option("latest", "最新 Newest"),
        CsCategories.Option("update", "最近更新 Last updated"),
        CsCategories.Option("popular", "热门 Popular"),
        CsCategories.Option("rating", "高分 Top rated"),
        CsCategories.Option("title", "片名 A-Z"),
    )

    private fun filters(withType: Boolean) = listOfNotNull(
        if (withType) CsCategories.Filter(
            "type", "类型 Type",
            listOf("ova" to "OVA", "ona" to "ONA", "special" to "Special").map { (v, l) -> CsCategories.Option(v, l) },
        ) else null,
        CsCategories.Filter("genre", "题材 Genre", GENRES.map { (v, l) -> CsCategories.Option(v, l) }),
        CsCategories.Filter(
            "status", "状态 Status",
            listOf("ongoing" to "Ongoing", "completed" to "Completed", "upcoming" to "Upcoming", "hiatus" to "Hiatus")
                .map { (v, l) -> CsCategories.Option(v, l) },
        ),
        CsCategories.Filter(
            "audio", "语言 Audio",
            listOf("dub" to "English Dub", "sub" to "English Sub", "raw" to "Raw").map { (v, l) -> CsCategories.Option(v, l) },
        ),
    )

    private val browse by lazy {
        CsBrowse(
            this, name,
            listOf(
                CsBrowse.Section("tv", "动漫", filters(false), sorts(), english = "TV Series", glyph = "🐉"),
                CsBrowse.Section("movie", "剧场版", filters(false), sorts(), english = "Anime Movies", glyph = "🎬"),
                CsBrowse.Section("ova", "OVA 特别篇", filters(true), sorts(), english = "OVA / ONA / Specials", glyph = "🎞"),
            ),
            popularSort = "popular",
        ) { id, filters, query, page ->
            val types = SECTION_TYPES[id].orEmpty()
            val type = filters["type"] ?: types.firstOrNull()
            if (query.isNotBlank()) {
                // The list page has no keyword: search the site and keep this section's types.
                val (items, hasNext) = searchOwn(query, page)
                return@CsBrowse items.filter { it.type == tvTypeOf(type.orEmpty()) } to hasNext
            }
            val params = buildList {
                if (type != null) add("type=$type")
                filters["genre"]?.let { add("genre%5B%5D=$it") }
                filters["status"]?.let { add("status=$it") }
                filters["audio"]?.let { add("sub=$it") }
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
        MainPageData("English Dub", "sub=dub&order=update"),
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
        val document = app.get("$mainUrl/anime/?page=$page&$params", headers = siteHeaders).document
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
        val a = selectFirst("a[href*='/anime/']") ?: return null
        val href = a.attr("abs:href").ifBlank { return null }
        val title = Parser.unescapeEntities(a.attr("title").ifBlank { selectFirst("h2")?.text().orEmpty() }, false).trim()
        if (title.isBlank() || isBlocked(href, title, blocked)) return null
        val type = tvTypeOf(selectFirst(".typez")?.text().orEmpty())
        fun count(kind: String) = selectFirst(".ans-badge.ans-$kind .ans-num")?.text()?.trim()?.toIntOrNull()
        val sub = count("sub")
        val dub = count("dub")
        return newAnimeSearchResponse(title, href, type) {
            posterUrl = imageOf(selectFirst("img"))
            if (sub != null || dub != null) addDubStatus(dub != null, sub != null, dub, sub)
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
            val response = app.get(url, headers = siteHeaders)
            if (response.code != 200) break
            val document = response.document
            val found = document.select("article.bs a[href*='/anime/']").mapNotNull { slugOf(it.attr("abs:href")) }
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
        if (!url.contains("/anime/")) {
            // An episode page: open its series, which lists every episode.
            val document = app.get(url, headers = siteHeaders).document
            seriesUrl = document.selectFirst(".year a[href*='/anime/'], .nvs a[href*='/anime/']")?.attr("abs:href")
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
        val isDub = Regex("""\bdub\b""", RegexOption.IGNORE_CASE).containsMatchIn(title)

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

    private suspend fun loadOwnLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data, headers = siteHeaders).document
        val players = players(document)
            .sortedWith(compareBy({ audioRank(it.label, data) }, { hostRank(it.url) }, { it.order }))
        if (players.isEmpty()) return false

        // All players are read at once; their links are handed on in the order above (English dub
        // first), since CloudStream keeps that order between links of the same quality.
        val found = coroutineScope {
            players.map { player ->
                async {
                    val links = mutableListOf<ExtractorLink>()
                    withTimeoutOrNull(15_000L) {
                        runCatching { resolve(player, data, subtitleCallback) { synchronized(links) { links += it } } }
                    }
                    synchronized(links) { links.toList() }
                }
            }.awaitAll()
        }
        found.flatten().forEach(callback)
        return found.any { it.isNotEmpty() }
    }

    /** The episode page's players: select.mirror options (base64 iframes), else the page's own iframe. */
    private fun players(document: Document): List<Player> {
        val options = document.select("select.mirror option[value]").mapIndexedNotNull { i, option ->
            val value = option.attr("value").trim().ifBlank { return@mapIndexedNotNull null }
            val html = runCatching { String(Base64.getDecoder().decode(value)) }.getOrNull() ?: return@mapIndexedNotNull null
            val src = Regex("""(?i)src\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: html.trim().takeIf { it.startsWith("http") || it.startsWith("//") }
                ?: return@mapIndexedNotNull null
            Player(option.text().trim(), fixUrlOf(Parser.unescapeEntities(src, true)), i)
        }
        if (options.isNotEmpty()) return options.distinctBy { it.url }
        val iframe = document.selectFirst("#pembed iframe, .player-embed iframe") ?: return emptyList()
        val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { return emptyList() }
        return listOf(Player("", fixUrlOf(src), 0))
    }

    private fun hostRank(url: String): Int = when {
        url.contains("vidmoly.") -> 0
        url.contains("vidaraa.") -> 1
        MegaPlay.handles(url) || url.contains("/play.php") -> 2
        else -> 3
    }

    private suspend fun resolve(
        player: Player,
        episodeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val url = player.url
        val audio = when (audioRank(player.label, episodeUrl)) {
            0 -> "English Dub"
            2 -> "Raw"
            else -> "English Sub"
        }
        val server = (if (player.label.contains('-')) player.label.substringAfter('-') else player.label).trim()
            .ifBlank { hostName(url) }
        val label = "$name $server ($audio)"
        when {
            url.contains("vidmoly.") -> vidmoly(url, label, callback)
            url.contains("vidaraa.") -> vidaraa(url, label, subtitleCallback, callback)
            url.contains("/play.php") -> {
                // The site's own wrapper page around a MegaPlay player.
                val inner = app.get(url, headers = mapOf("User-Agent" to userAgent, "Referer" to episodeUrl)).document
                    .selectFirst("iframe[src]")?.attr("src")?.let { fixUrlOf(it) } ?: return
                if (MegaPlay.handles(inner)) {
                    MegaPlay.extract(inner, name, label, "$mainUrl/", userAgent, subtitleCallback, callback)
                }
            }
            MegaPlay.handles(url) -> MegaPlay.extract(url, name, label, "$mainUrl/", userAgent, subtitleCallback, callback)
            // Device attestation (Byse / FileMoon), Cloudflare block (vtube) or gone (StreamSB).
            Regex("""bysesayeveum|byse|filemoon|vtube\.|vtbe\.|sbbrisk|sblona|streamsb""").containsMatchIn(url) -> Unit
            else -> loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
        }
    }

    /** Vidmoly's embed page has the HLS master in its player setup: `sources: [{ file: '…m3u8' }]`. */
    private suspend fun vidmoly(url: String, label: String, callback: (ExtractorLink) -> Unit) {
        val response = app.get(url, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/"))
        if (response.code != 200) return
        val origin = Regex("""^https?://[^/]+""").find(response.url)?.value ?: return
        val block = Regex("""sources\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(response.text)?.groupValues?.get(1)
            ?: return
        Regex("""file\s*:\s*['"]([^'"]+)['"]""").findAll(block).map { it.groupValues[1] }.distinct().forEach { file ->
            callback(
                newExtractorLink(name, label, file, if (file.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    referer = "$origin/"
                    quality = Qualities.Unknown.value
                    headers = mapOf("User-Agent" to userAgent)
                },
            )
        }
    }

    /** Vidaraa's player asks POST /api/stream {filecode} for the HLS master and subtitles. */
    private suspend fun vidaraa(
        url: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val origin = Regex("""^https?://[^/]+""").find(url)?.value ?: return
        val code = url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { return }
        val body = mapper.writeValueAsString(mapOf("filecode" to code, "device" to "web", "codecs" to emptyMap<String, Any>()))
        val text = app.post(
            "$origin/api/stream",
            headers = mapOf("User-Agent" to userAgent, "Referer" to url, "Origin" to origin),
            requestBody = body.toRequestBody("application/json".toMediaType()),
        ).text
        val json = runCatching { mapper.readTree(text) }.getOrNull() ?: return
        val stream = json.path("streaming_url").asText("").takeIf { it.startsWith("http") } ?: return
        json.path("subtitles").forEach { sub ->
            val file = sub.path("file").asText("").ifBlank { sub.path("url").asText("") }
            if (!file.startsWith("http")) return@forEach
            val lang = sub.path("label").asText("").ifBlank { sub.path("language").asText("") }.ifBlank { "Subtitles" }
            subtitleCallback(newSubtitleFile(lang, file))
        }
        callback(
            newExtractorLink(name, label, stream, ExtractorLinkType.M3U8) {
                referer = "$origin/"
                quality = Qualities.Unknown.value
                headers = mapOf("User-Agent" to userAgent, "Origin" to origin)
            },
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun tvTypeOf(text: String): TvType = when (text.trim().lowercase()) {
        "movie" -> TvType.AnimeMovie
        "ova", "special", "ona" -> TvType.OVA
        else -> TvType.Anime
    }

    private fun hostName(url: String): String =
        Regex("""^https?://(?:www\.)?([^./]+)""").find(url)?.groupValues?.get(1)?.replaceFirstChar { it.uppercase() } ?: "Server"

    private fun slugOf(url: String): String? =
        Regex("""/anime/([^/?#]+)""").find(url)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

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
