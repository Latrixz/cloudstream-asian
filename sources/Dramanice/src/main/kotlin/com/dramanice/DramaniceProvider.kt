package com.dramanice

import com.csasian.shared.CsBackup
import com.csasian.shared.CsBrowse
import com.csasian.shared.CsCategories
import com.csasian.shared.CsMore
import com.csasian.shared.CsSearch
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.MixDrop
import com.lagradost.cloudstream3.extractors.Vidmoly
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/** The built-in Vidmoly extractor doesn't list vidmoly.org, which zumivids also links. */
class DramaniceVidmolyOrg : Vidmoly() {
    override val mainUrl = "https://vidmoly.org"
}

/** zumivids links mixdrop.top, which no built-in MixDrop mirror covers. */
class DramaniceMixDropTop : MixDrop() {
    override var mainUrl = "https://mixdrop.top"
}

/**
 * Dramanice (dramanice.cyou): a WordPress site. Lists, filters and search use its public REST API
 * (/wp-json/wp/v2/series, with the country / genre / season(year) taxonomies); a title's kind
 * (Drama / Movie / TV Show) is only in its SEO description ("the popular Korean Movie in HD"), so
 * sections filter on that. Episode pages embed zumivids.com, which lists the video hosts.
 */
class DramaniceProvider : MainAPI() {
    override var mainUrl = "https://dramanice.cyou"
    override var name = "Dramanice"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie, TvType.TvSeries)

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val siteHeaders get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
    private val mapper = ObjectMapper()

    private val api get() = "$mainUrl/wp-json/wp/v2"
    private val cardFields = "id,link,slug,title,date,country,genre,season,yoast_head_json.og_image,yoast_head_json.description"

    // ---------------------------------------------------------------- taxonomy ids

    private companion object {
        /** Genres left out everywhere: Erotic and Hentai. */
        const val EXCLUDED_GENRES = "396,395"

        val COUNTRY = mapOf(
            "korean" to 2, "chinese" to 6, "japanese" to 45, "thailand" to 47, "philippines" to 345,
            "taiwanese" to 69, "hong-kong" to 348, "usa" to 350,
        )

        /** Year (the site's "season" taxonomy) to term id; newer years are looked up online. */
        val YEARS = mapOf(
            2026 to 357, 2025 to 347, 2024 to 3, 2023 to 124, 2022 to 121, 2021 to 135, 2020 to 129, 2019 to 167,
            2018 to 208, 2017 to 181, 2016 to 170, 2015 to 194, 2014 to 323, 2013 to 278, 2012 to 273, 2011 to 203,
            2010 to 55, 2009 to 339, 2008 to 364, 2007 to 299, 2006 to 352, 2005 to 362, 2004 to 366, 2003 to 358,
            2002 to 363, 2001 to 360, 2000 to 361,
        )

        val GENRES = listOf(
            "32" to "Action", "52" to "Adventure", "18" to "BL", "7" to "Business", "25" to "Comedy", "39" to "Crime",
            "111" to "Detective", "230" to "Documentary", "8" to "Drama", "13" to "Family", "20" to "Fantasy",
            "235" to "Food", "82" to "Friendship", "9" to "Historical", "109" to "Horror", "34" to "Investigation",
            "76" to "Law", "48" to "LGBTQ+", "23" to "Life", "148" to "Martial Arts", "329" to "Mature",
            "106" to "Medical", "28" to "Melodrama", "169" to "Military", "91" to "Music", "15" to "Mystery",
            "10" to "Novel", "178" to "Political", "36" to "Psychological", "53" to "Reality", "165" to "Revenge",
            "11" to "Romance", "72" to "School", "160" to "Sci-Fi", "213" to "Sports", "49" to "Supernatural",
            "37" to "Suspense", "16" to "Thriller", "123" to "Time Travel", "54" to "Variety", "285" to "War",
            "73" to "Web Series", "42" to "Webtoon", "21" to "Wuxia", "30" to "Youth", "313" to "Zombie",
        )

        /** Hosts that are gone or only redirect to ads: skipped quietly so backups take over. */
        val STOP_WORDS = setOf("the", "a", "an", "of", "and", "in", "on", "to", "my", "season")

        val DEAD_HOSTS = listOf("embasic.pro", "asianbxkiun.pro", "plcool1.com")

        val YEAR_SUFFIX = Regex("""\s*\((?:[^()]*?\s)?((?:19|20)\d{2})\)\s*$""")
    }

    private enum class Kind { DRAMA, MOVIE, SHOW }

    // ---------------------------------------------------------------- categories

    private fun sorts() = listOf(
        CsCategories.Option("date", "最新 Newest"),
        CsCategories.Option("modified", "最近更新 Last updated"),
        CsCategories.Option("title", "片名 A-Z"),
    )

    private fun genreFilter() = CsCategories.Filter("genre", "类型 Genre", GENRES.map { (id, label) -> CsCategories.Option(id, label) })
    private fun yearFilter() = CsCategories.Filter(
        "year", "年份 Year",
        (java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).coerceAtLeast(2026) downTo 2000)
            .map { CsCategories.Option(it.toString()) },
    )
    private fun countryFilter() = CsCategories.Filter(
        "country", "地区 Country",
        listOf("korean" to "Korea", "chinese" to "China", "japanese" to "Japan", "thailand" to "Thailand",
            "philippines" to "Philippines", "taiwanese" to "Taiwan", "hong-kong" to "Hong Kong", "usa" to "USA")
            .map { (v, l) -> CsCategories.Option(v, l) },
    )

    /** How a section maps onto the API: its country, kind, and REST page size / pages per screen page. */
    private data class Spec(val country: String?, val kind: Kind, val perPage: Int, val chunk: Int = 1)

    private val specs = mapOf(
        "korean" to Spec("korean", Kind.DRAMA, 50),
        "chinese" to Spec("chinese", Kind.DRAMA, 40),
        "japanese" to Spec("japanese", Kind.DRAMA, 40),
        "thai" to Spec("thailand", Kind.DRAMA, 40),
        "filipino" to Spec("philippines", Kind.DRAMA, 100),
        "taiwanese" to Spec("taiwanese", Kind.DRAMA, 40),
        "movie" to Spec(null, Kind.MOVIE, 100),
        "kshow" to Spec("korean", Kind.SHOW, 100, 2),
    )

    private val browse = CsBrowse(
        this, name,
        listOf(
            CsBrowse.Section("korean", "韩剧", listOf(genreFilter(), yearFilter()), sorts()),
            CsBrowse.Section("chinese", "国产剧", listOf(genreFilter(), yearFilter()), sorts()),
            CsBrowse.Section("japanese", "日剧", listOf(genreFilter(), yearFilter()), sorts()),
            CsBrowse.Section("thai", "泰剧", listOf(genreFilter(), yearFilter()), sorts()),
            CsBrowse.Section("filipino", "菲律宾剧", listOf(genreFilter(), yearFilter()), sorts(), english = "Philippine Dramas", glyph = "📺"),
            CsBrowse.Section("taiwanese", "台剧", listOf(genreFilter(), yearFilter()), sorts()),
            CsBrowse.Section("movie", "电影", listOf(countryFilter(), genreFilter(), yearFilter()), sorts()),
            CsBrowse.Section("kshow", "韩综", listOf(genreFilter(), yearFilter()), sorts(), english = "K-Shows", glyph = "🎤"),
        ),
    ) { id, filters, query, page ->
        val spec = specs[id] ?: return@CsBrowse emptyList<SearchResponse>() to false
        val country = filters["country"] ?: spec.country
        seriesPage(
            spec.copy(country = country), filters["genre"], filters["year"]?.toIntOrNull(),
            filters[CsCategories.SORT] ?: "date", query, page,
        )
    }

    fun csCategories(): String = browse.categoriesJson()

    fun csCategoryPage(categoryId: String, filtersJson: String, query: String, page: Int): SearchResponseList =
        browse.categoryPage(categoryId, filtersJson, query, page)

    // ---------------------------------------------------------------- home

    // "html|<path>" = a list page of the site; "rest|<section>|<sort>" = a section through the API.
    override val mainPage = browse.mainPage(mainPageOf(
        "html|latest" to "Latest Episodes",
        "hot" to "Hot Dramas",
        "html|popular-ongoing-dramas" to "Popular Ongoing Dramas",
        "html|popular-ongoing-kshows" to "Popular Ongoing K-Shows",
        "rest|korean|modified" to "Korean Dramas",
        "rest|chinese|modified" to "Chinese Dramas",
        "rest|japanese|modified" to "Japanese Dramas",
        "rest|thai|modified" to "Thai Dramas",
        "rest|filipino|modified" to "Philippine Dramas",
        "rest|taiwanese|modified" to "Taiwanese Dramas",
        "rest|movie|date" to "Movies",
        "rest|kshow|modified" to "K-Shows",
    ))

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        browse.rowPage(request) ?: CsMore.wrap(this, request, page, mainPageOwn(page, request))

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        val parts = request.data.split('|')
        val (items, hasNext) = when (parts[0]) {
            "html" -> htmlList(parts[1], page)
            "hot" -> if (page > 1) emptyList<SearchResponse>() to false else hotDramas()
            "rest" -> {
                val spec = specs[parts[1]] ?: return newHomePageResponse(request.name, emptyList(), false)
                seriesPage(spec, null, null, parts.getOrElse(2) { "date" }, "", page)
            }
            else -> emptyList<SearchResponse>() to false
        }
        return newHomePageResponse(request.name, items, hasNext)
    }

    /** The home page's latest episodes, or one of the site's list pages (/popular-ongoing-dramas/). */
    private suspend fun htmlList(path: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val base = if (path == "latest") "$mainUrl/" else "$mainUrl/$path/"
        val url = if (page > 1) "${base}page/$page/" else base
        val document = app.get(url, headers = siteHeaders).document
        val blocked = blockedSlugs()
        val items = document.select(".content_left ul.items > li").mapNotNull { li ->
            val a = li.selectFirst(".name a[href], .img a[href]") ?: return@mapNotNull null
            val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
            val raw = a.attr("title").ifBlank { a.text() }.trim()
            if (raw.isBlank() || isBlocked(href, raw, blocked)) return@mapNotNull null
            val img: Element? = li.selectFirst("img")
            val poster: String? = imageOf(img)
            val isMovie = !href.contains("/series/") && Regex("""-movie/?$""").containsMatchIn(href)
            if (href.contains("/series/")) {
                val (title, year) = splitYear(raw)
                newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { posterUrl = poster; this.year = year }
            } else if (isMovie) {
                newMovieSearchResponse(raw, href, TvType.Movie) { posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(raw, href, TvType.AsianDrama) { posterUrl = poster }
            }
        }.distinctBy { it.url }
        val hasNext = document.selectFirst(".content_left a[href*='/page/${page + 1}/']") != null
        return items to hasNext
    }

    /** The sidebar's "Hot Dramas" (titles only), with posters from the API. */
    private suspend fun hotDramas(): Pair<List<SearchResponse>, Boolean> {
        val document = app.get("$mainUrl/", headers = siteHeaders).document
        val slugs = document.select(".commingbox .item a[href*='/series/']")
            .mapNotNull { slugOf(it.attr("abs:href")) }.distinct()
        if (slugs.isEmpty()) return emptyList<SearchResponse>() to false
        val json = restJson("$api/series?slug=${slugs.joinToString(",")}&per_page=${slugs.size}&genre_exclude=$EXCLUDED_GENRES&_fields=$cardFields")
            ?.first ?: return emptyList<SearchResponse>() to false
        val bySlug = json.mapNotNull { node -> node.text("slug")?.let { it to node } }.toMap()
        return slugs.mapNotNull { bySlug[it]?.let { node -> toCard(node) } } to false
    }

    // ---------------------------------------------------------------- REST lists

    private suspend fun seriesPage(
        spec: Spec,
        genre: String?,
        year: Int?,
        sort: String,
        query: String,
        page: Int,
    ): Pair<List<SearchResponse>, Boolean> {
        val params = buildString {
            append("genre_exclude=").append(EXCLUDED_GENRES)
            spec.country?.let { COUNTRY[it] }?.let { append("&country=").append(it) }
            genre?.let { append("&genre=").append(it) }
            if (year != null) {
                val term = yearTerm(year) ?: return emptyList<SearchResponse>() to false
                append("&season=").append(term)
            }
            if (query.isNotBlank()) {
                append("&search=").append(URLEncoder.encode(query.trim(), "UTF-8"))
            } else when (sort) {
                "title" -> append("&orderby=title&order=asc")
                "modified" -> append("&orderby=modified&order=desc")
                else -> append("&orderby=date&order=desc")
            }
            append("&per_page=").append(spec.perPage)
            append("&_fields=").append(cardFields)
        }
        val restPages = ((page - 1) * spec.chunk + 1)..(page * spec.chunk)
        val results = coroutineScope {
            restPages.map { p -> async { restJson("$api/series?$params&page=$p") } }.map { it.await() }
        }
        val totalPages = results.firstNotNullOfOrNull { it?.second } ?: 0
        val items = results.flatMap { it?.first.orEmpty() }
            .filter { kindOf(it) == spec.kind }
            .mapNotNull { toCard(it) }
            .distinctBy { it.url }
            .let { if (query.isNotBlank()) rankByTitle(it, query) else it }
        return items to (restPages.last < totalPages)
    }

    /** A JSON list from the API and its X-WP-TotalPages; null when the request fails (e.g. past the last page). */
    private suspend fun restJson(url: String): Pair<List<JsonNode>, Int>? {
        val response = runCatching { app.get(url, headers = siteHeaders) }.getOrNull() ?: return null
        if (response.code != 200) return null
        val root = runCatching { mapper.readTree(response.text) }.getOrNull() ?: return null
        if (!root.isArray) return null
        val total = response.headers["X-WP-TotalPages"]?.toIntOrNull() ?: 1
        return root.toList() to total
    }

    private var yearTerms: Map<Int, Int>? = null

    private suspend fun yearTerm(year: Int): Int? {
        YEARS[year]?.let { return it }
        val terms = yearTerms ?: restJson("$api/season?per_page=100&_fields=id,name")?.first
            ?.mapNotNull { n -> n.text("name")?.toIntOrNull()?.let { it to n.path("id").asInt() } }?.toMap()
            ?.also { yearTerms = it }
        return terms?.get(year)
    }

    private var blocked: Pair<Long, Set<String>>? = null

    /** Slugs of the titles in the left-out genres, for lists that come from HTML pages. */
    private suspend fun blockedSlugs(): Set<String> {
        blocked?.let { (at, set) -> if (System.currentTimeMillis() - at < 3_600_000L) return set }
        val set = restJson("$api/series?genre=$EXCLUDED_GENRES&per_page=100&_fields=slug")?.first
            ?.mapNotNull { it.text("slug") }?.toSet() ?: return blocked?.second.orEmpty()
        blocked = System.currentTimeMillis() to set
        return set
    }

    private fun isBlocked(url: String, title: String, blocked: Set<String>): Boolean {
        if (CsCategories.isAdultTitle(title)) return true
        val slug = slugOf(url) ?: return false
        return blocked.any { slug == it || slug.startsWith("$it-") }
    }

    private fun kindOf(node: JsonNode): Kind {
        val description = node.path("yoast_head_json").path("description").asText("")
        val phrase = Regex("""the popular (.+?) in HD""").find(description)?.groupValues?.get(1).orEmpty()
        return when {
            phrase.endsWith("Movie") -> Kind.MOVIE
            phrase.endsWith("TV Show") -> Kind.SHOW
            else -> Kind.DRAMA
        }
    }

    private fun toCard(node: JsonNode): SearchResponse? {
        val link = node.text("link") ?: return null
        val raw = unescape(node.path("title").path("rendered").asText(""))
        if (raw.isBlank() || CsCategories.isAdultTitle(raw)) return null
        val (title, year) = splitYear(raw)
        val poster = node.path("yoast_head_json").path("og_image").path(0).path("url").asText("").ifBlank { null }
        return when (kindOf(node)) {
            Kind.MOVIE -> newMovieSearchResponse(title, link, TvType.Movie) { posterUrl = poster; this.year = year }
            Kind.SHOW -> newTvSeriesSearchResponse(title, link, TvType.TvSeries) { posterUrl = poster; this.year = year }
            Kind.DRAMA -> newTvSeriesSearchResponse(title, link, TvType.AsianDrama) { posterUrl = poster; this.year = year }
        }
    }

    // ---------------------------------------------------------------- search

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    // Page 1 also searches the English title when the query is in another language (shared/CsSearch.kt).
    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> =
        CsSearch.withExtra(query, page, lang) { q, p -> searchOwn(q, p) }

    private suspend fun searchOwn(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val (nodes, total) = restJson("$api/series?search=$q&per_page=30&page=$page&genre_exclude=$EXCLUDED_GENRES&_fields=$cardFields")
            ?: return emptyList<SearchResponse>() to false
        return rankByTitle(nodes.mapNotNull { toCard(it) }, query) to (page < total)
    }

    /**
     * The API also matches synopses ("the untamed" finds any drama whose story mentions "untamed"):
     * keep the titles that contain every word of the query, the exact title first.
     */
    private fun rankByTitle(items: List<SearchResponse>, query: String): List<SearchResponse> {
        val words = query.lowercase().split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.isNotBlank() && it !in STOP_WORDS }
        if (words.isEmpty() || query.any { it.code > 0x2E7F }) return items
        val wanted = norm(query)
        return items.filter { card -> norm(card.name).let { name -> words.all { name.contains(norm(it)) } } }
            .sortedBy { if (norm(it.name) == wanted) 0 else 1 }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    // ---------------------------------------------------------------- load

    // Remembers episodes and adds backup links from our other sources (see shared/CsBackup.kt).
    // A "分类 Categories" card opens the category page (browse.load first: it checks who is asking).
    override suspend fun load(url: String): LoadResponse =
        browse.load(url) ?: CsMore.load(this, url) ?: loadPage(url).also { CsBackup.remember(this, it) }

    private suspend fun loadPage(url: String): LoadResponse {
        var seriesUrl = url
        if (!url.contains("/series/")) {
            // An episode (or movie) page: open its drama page, which lists every episode.
            val document = app.get(url, headers = siteHeaders).document
            seriesUrl = document.selectFirst("main.main_body a[href*='/series/']")?.attr("abs:href")
                ?: return loadLoneEpisode(url, document)
        }
        val slug = slugOf(seriesUrl)
        val (document, info) = coroutineScope {
            val page = async { app.get(seriesUrl, headers = siteHeaders).document }
            val rest = async { slug?.let { restJson("$api/series?slug=$it&_fields=id,country,genre,season,yoast_head_json.description") }?.first?.firstOrNull() }
            page.await() to rest.await()
        }
        val body = document.selectFirst(".drama_info_body") ?: throw ErrorLoadingException("Drama not found")
        fun field(label: String): String? = body.select(".info_right p").firstOrNull {
            it.selectFirst("span")?.text()?.trim()?.removeSuffix(":")?.trim()?.equals(label, true) == true
        }?.let { p -> p.text().substringAfter(':').trim().ifBlank { null } }

        val rawTitle = body.selectFirst(".img_cover img")?.attr("alt")?.trim().orEmpty()
            .ifBlank { document.selectFirst("h1")?.text()?.substringBeforeLast(" - Dramanice")?.trim().orEmpty() }
            .ifBlank { throw ErrorLoadingException("Drama title not found") }
        val (title, titleYear) = splitYear(rawTitle)
        val poster = imageOf(body.selectFirst(".img_cover img"))
        val year = field("Released")?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() } ?: titleYear
        val status = when (field("Status")?.lowercase()) {
            "ongoing", "upcoming" -> ShowStatus.Ongoing
            "completed" -> ShowStatus.Completed
            else -> null
        }
        val genres = field("Genre")?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        val country = field("Country")
        val tags = (genres + listOfNotNull(country)).map { it.replaceFirstChar { c -> c.uppercase() } }.distinct()
        val plot = body.select(".info_right p").let { ps ->
            val at = ps.indexOfFirst { it.selectFirst("span")?.text()?.startsWith("Description") == true }
            if (at < 0) null else ps.drop(at + 1).joinToString("\n\n") { it.text().trim() }.ifBlank { null }
        }
        val otherNames = field("Other name")
        val kind = info?.let { kindOf(it) } ?: run {
            val og = document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()
            when {
                Regex("""popular .*Movie in HD""").containsMatchIn(og) -> Kind.MOVIE
                Regex("""popular .*TV Show in HD""").containsMatchIn(og) -> Kind.SHOW
                else -> Kind.DRAMA
            }
        }

        val episodes = body.select("ul.list_episode li a[href]").mapNotNull { a ->
            val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
            val label = a.select("span").lastOrNull()?.text()?.substringBefore('|')?.trim().orEmpty()
            Triple(href, label, a.attr("title"))
        }.distinctBy { it.first }.reversed() // The site lists the newest first.

        val recommendations = info?.let { recommendations(it) }.orEmpty()

        if (kind == Kind.MOVIE && episodes.size <= 1) {
            val movieUrl = episodes.firstOrNull()?.first ?: throw ErrorLoadingException("No video for this movie yet")
            return newMovieLoadResponse(title, seriesUrl, TvType.Movie, movieUrl) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
            }
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("No episodes yet")
        val list = episodes.map { (href, label, linkTitle) ->
            val numberText = Regex("""(?:Ep(?:isode)?\.?\s*)([\d.]+(?:-\d+)?)""", RegexOption.IGNORE_CASE).find(label)?.groupValues?.get(1)
                ?: Regex("""-ep-([\d-]+?)-eng-sub""").find(href)?.groupValues?.get(1)?.replace('-', '.')
            newEpisode(href) {
                episode = numberText?.toIntOrNull()
                name = when {
                    numberText == null -> linkTitle.ifBlank { label }.ifBlank { null }
                    episode != null -> null
                    else -> "Episode $numberText"
                }
            }
        }
        return newTvSeriesLoadResponse(title, seriesUrl, if (kind == Kind.SHOW) TvType.TvSeries else TvType.AsianDrama, list) {
            posterUrl = poster
            this.plot = listOfNotNull(plot, otherNames?.let { "Also known as: $it" }).joinToString("\n\n").ifBlank { null }
            this.year = year
            this.tags = tags
            showStatus = status
            this.recommendations = recommendations
        }
    }

    /** Titles from the same country sharing the first genre (left-out genres excluded). */
    private suspend fun recommendations(info: JsonNode): List<SearchResponse> {
        val id = info.path("id").asInt()
        val country = info.path("country").path(0).asInt(0)
        val genres = info.path("genre").map { it.asInt() }.filter { g -> GENRES.any { it.first == g.toString() } && g != 8 }
        val params = buildString {
            append("genre_exclude=").append(EXCLUDED_GENRES).append("&exclude=").append(id)
            if (country > 0) append("&country=").append(country)
            genres.firstOrNull()?.let { append("&genre=").append(it) }
            append("&orderby=modified&per_page=16&_fields=").append(cardFields)
        }
        return restJson("$api/series?$params")?.first?.mapNotNull { toCard(it) }.orEmpty()
    }

    /** An episode page that doesn't link its drama: play just this page. */
    private suspend fun loadLoneEpisode(url: String, document: Document): LoadResponse {
        val heading = document.selectFirst("main.main_body h1")?.text()?.trim().orEmpty()
        val (title, year) = splitYear(heading.replace(Regex("""\s+(Ep(isode)?\s.*|Full\s.*)$""", RegexOption.IGNORE_CASE), ""))
        return newMovieLoadResponse(title.ifBlank { heading }, url, TvType.Movie, url) { this.year = year }
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

    private suspend fun loadOwnLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data, headers = siteHeaders).document
        val main = document.selectFirst("main.main_body") ?: document
        val embeds = (main.select("[data-video]").map { it.attr("data-video") } +
            main.select("iframe").map { it.attr("data-src").ifBlank { it.attr("src") } })
            .map { fixUrlOf(Parser.unescapeEntities(it.trim(), true)) }
            .filter { it.startsWith("http") && !isDead(it) }
            .distinct()

        val hosts = embeds.flatMap { if (it.contains("zumivids.")) zumividsHosts(it) else listOf(it) }
            .filter { !isDead(it) }.distinct()
        if (hosts.isEmpty()) return false

        // Vidmoly first: other hosts' links wait until Vidmoly is done (or gives up).
        val count = AtomicInteger(0)
        val lock = Any()
        val held = mutableListOf<ExtractorLink>()
        var vidmolyDone = false
        val (vidmoly, others) = hosts.partition { it.contains("vidmoly.") }
        coroutineScope {
            val first = async {
                withTimeoutOrNull(12_000L) {
                    vidmoly.amap { host -> resolveHost(host, subtitleCallback) { link -> count.incrementAndGet(); callback(link) } }
                }
                synchronized(lock) {
                    vidmolyDone = true
                    held.forEach(callback)
                    held.clear()
                }
            }
            others.amap { host ->
                resolveHost(host, subtitleCallback) { link ->
                    count.incrementAndGet()
                    val now = synchronized(lock) { if (vidmolyDone) true else { held += link; false } }
                    if (now) callback(link)
                }
            }
            first.await()
        }
        return count.get() > 0
    }

    private fun isDead(url: String): Boolean {
        val host = Regex("""^https?://([^/:]+)""").find(url)?.groupValues?.get(1)?.lowercase() ?: return true
        return DEAD_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private suspend fun zumividsHosts(url: String): List<String> {
        val response = runCatching { app.get(url, headers = siteHeaders) }.getOrNull() ?: return emptyList()
        if (response.code != 200) return emptyList()
        val doc = response.document
        return (doc.select("[data-video]").map { it.attr("data-video") } +
            doc.select("iframe").map { it.attr("src").ifBlank { it.attr("data-src") } })
            .map { fixUrlOf(it.trim()) }
            .filter { it.startsWith("http") && !it.contains("zumivids.") }
            .distinct()
    }

    /** The app's extractor for the host; our own reading of Vidmoly / Mixdrop when it finds nothing. */
    private suspend fun resolveHost(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val referer = "https://zumivids.com/"
        val found = AtomicInteger(0)
        val counting: (ExtractorLink) -> Unit = { found.incrementAndGet(); callback(it) }
        runCatching { loadExtractor(url, referer, subtitleCallback, counting) }
        if (found.get() > 0) return
        when {
            url.contains("vidmoly.") -> {
                if (url.contains("vidmoly.org")) runCatching { DramaniceVidmolyOrg().getUrl(url, referer, subtitleCallback, counting) }
                if (found.get() == 0) runCatching { readVidmoly(url, counting) }
            }
            url.contains("mixdrop.") || url.contains("mxdrop.") ->
                runCatching { DramaniceMixDropTop().getUrl(url, referer, subtitleCallback, counting) }
        }
    }

    /** Vidmoly's embed page has the HLS master in its player setup: `sources: [{ file: '…m3u8' }]`. */
    private suspend fun readVidmoly(url: String, callback: (ExtractorLink) -> Unit) {
        val embed = if (url.contains("/embed-")) url else url.replace(Regex("""/(?:w|e|v)/"""), "/embed-").let {
            if (it.endsWith(".html")) it else "$it.html"
        }
        val origin = Regex("""^https?://[^/]+""").find(embed)?.value ?: return
        val html = app.get(embed, headers = mapOf("User-Agent" to userAgent, "Sec-Fetch-Dest" to "iframe")).text
        val block = Regex("""sources\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: return
        Regex("""file\s*:\s*['"]([^'"]+)['"]""").findAll(block).map { it.groupValues[1] }.distinct().forEach { file ->
            callback(
                newExtractorLink("Vidmoly", "Vidmoly", file, if (file.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    referer = "$origin/"
                    quality = Qualities.P720.value
                },
            )
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun JsonNode.text(field: String): String? = get(field)?.asText()?.takeIf { it.isNotBlank() }

    private fun unescape(s: String) = Parser.unescapeEntities(s, false).trim()

    /** "Queen of Tears (2024)" → ("Queen of Tears", 2024); "Gift (Korean Drama 2026)" → ("Gift", 2026). */
    private fun splitYear(raw: String): Pair<String, Int?> {
        val m = YEAR_SUFFIX.find(raw) ?: return raw.trim() to null
        val title = raw.substring(0, m.range.first).trim()
        return (title.ifBlank { raw.trim() }) to m.groupValues[1].toIntOrNull()
    }

    private fun slugOf(url: String): String? =
        Regex("""^https?://[^/]+/(?:series/)?([^/?#]+)""").find(url)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

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
