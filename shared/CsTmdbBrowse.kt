package com.csasian.shared

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.app
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Category browsing (shared/CsBrowse.kt) for sources whose catalogue is TMDB: five sections
 * (电影 Movies, 电视剧 TV Series, 动漫 Anime, 韩剧 K-Drama, 国产剧 C-Drama) from TMDB's
 * `discover/movie` and `discover/tv`, with filters for genre (TMDB's movie or TV genre list),
 * country (`with_origin_country`), year (`primary_release_year` / `first_air_date_year`) and
 * original language, sorted newest first (released titles only), by popularity or by rating.
 *
 * The source keeps its own cards and URLs: [create] gets the source's TMDB call (its own proxy,
 * or [wecollege]) and its own mapping from a TMDB `results` array to its search responses, so
 * opening a title and playing it still go through the source.
 *
 *     private val browse = CsTmdbBrowse.create(this, name, get = { tmdb(it) }) { results, kind -> toResults(results, kind) }
 *
 * Adult titles never show: every request has include_adult=false, and titles TMDB marks adult or
 * whose names read adult are dropped before the source maps them. [extraParams] go on every
 * discover request (ZXCStream: `without_companies=149142`).
 * A search inside a section uses TMDB's search (`search/movie|tv`), filtered to the section and
 * the chosen filters here.
 */
object CsTmdbBrowse {
    const val WECOLLEGE = "https://db.wecollege.net/3"

    const val GENRE = "genre"
    const val COUNTRY = "country"
    const val YEAR = "year"
    const val LANGUAGE = "language"

    const val NEWEST = "newest"
    const val POPULAR = "popular"
    const val TOP = "top"

    private val mapper = ObjectMapper()

    // The proxy refuses some default user agents; okhttp's own is accepted.
    private val headers = mapOf("User-Agent" to "okhttp/4.12.0", "Accept" to "application/json")

    /** The keyless TMDB proxy: [pathAndQuery] is a TMDB v3 path, e.g. "discover/movie?page=2". */
    suspend fun wecollege(pathAndQuery: String): JsonNode? {
        val response = app.get("$WECOLLEGE/$pathAndQuery", headers = headers, timeout = 20L)
        if (!response.isSuccessful) return null
        return mapper.readTree(response.text)
    }

    // TMDB's genre lists (genre/movie/list, genre/tv/list), English names.
    private val MOVIE_GENRES = listOf(
        "28" to "Action", "12" to "Adventure", "16" to "Animation", "35" to "Comedy", "80" to "Crime",
        "99" to "Documentary", "18" to "Drama", "10751" to "Family", "14" to "Fantasy", "36" to "History",
        "27" to "Horror", "10402" to "Music", "9648" to "Mystery", "10749" to "Romance", "878" to "Science Fiction",
        "10770" to "TV Movie", "53" to "Thriller", "10752" to "War", "37" to "Western",
    )
    private val TV_GENRES = listOf(
        "10759" to "Action & Adventure", "16" to "Animation", "35" to "Comedy", "80" to "Crime",
        "99" to "Documentary", "18" to "Drama", "10751" to "Family", "10762" to "Kids", "9648" to "Mystery",
        "10763" to "News", "10764" to "Reality", "10765" to "Sci-Fi & Fantasy", "10766" to "Soap",
        "10767" to "Talk", "10768" to "War & Politics", "37" to "Western",
    )

    /** Not dramas: kept out of the K-Drama and C-Drama sections (and their genre chips). */
    private val NOT_DRAMA = setOf("16", "10763", "10764", "10767", "10762")

    private val COUNTRIES = listOf(
        "US" to "United States", "GB" to "United Kingdom", "KR" to "South Korea", "JP" to "Japan",
        "CN" to "China", "HK" to "Hong Kong", "TW" to "Taiwan", "TH" to "Thailand", "IN" to "India",
        "FR" to "France", "DE" to "Germany", "ES" to "Spain", "IT" to "Italy", "CA" to "Canada",
        "AU" to "Australia", "MX" to "Mexico", "BR" to "Brazil", "TR" to "Turkey",
    )
    private val LANGUAGES = listOf(
        "en" to "English", "ko" to "Korean", "ja" to "Japanese", "zh" to "Mandarin", "cn" to "Cantonese",
        "th" to "Thai", "hi" to "Hindi", "es" to "Spanish", "fr" to "French", "de" to "German",
        "it" to "Italian", "tr" to "Turkish",
    )

    /** How one section asks TMDB: the kind, fixed discover params, and which filters it offers. */
    private class Kind(
        val id: String,
        val kind: String,
        /** Fixed discover params, e.g. with_original_language=ko. */
        val fixed: Map<String, String>,
        val genres: List<Pair<String, String>>,
        val countries: List<Pair<String, String>>?,
        val languages: List<Pair<String, String>>?,
        /** For searches: whether a TMDB result belongs to this section. */
        val belongs: (JsonNode) -> Boolean,
        /** Votes a title needs for the "top rated" sort. */
        val minVotes: Int,
        /** Votes a title needs for the "newest" sort: keeps the broad sections from filling with titles nobody has seen. */
        val newestVotes: Int = 0,
    )

    private fun JsonNode.ints(field: String) = get(field)?.mapNotNull { it.asText().toIntOrNull() }.orEmpty()
    private fun JsonNode.strings(field: String) = get(field)?.mapNotNull { it.asText() }.orEmpty()
    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { !it.isNull && !it.isContainerNode }?.asText()?.takeIf { it.isNotBlank() }

    private val KINDS = listOf(
        Kind("movie", "movie", emptyMap(), MOVIE_GENRES, COUNTRIES, LANGUAGES, { true }, 300, newestVotes = 5),
        Kind("tv", "tv", emptyMap(), TV_GENRES, COUNTRIES, LANGUAGES, { true }, 150, newestVotes = 2),
        Kind(
            "anime", "tv", mapOf("with_genres" to "16", "with_original_language" to "ja"),
            TV_GENRES.filter { it.first != "16" && it.first !in setOf("10763", "10764", "10766", "10767") }, null, null,
            { 16 in it.ints("genre_ids") && it.text("original_language") == "ja" }, 50,
        ),
        Kind(
            "kdrama", "tv",
            mapOf("with_origin_country" to "KR", "with_original_language" to "ko", "without_genres" to NOT_DRAMA.joinToString(",")),
            TV_GENRES.filter { it.first !in NOT_DRAMA }, null, null,
            { "KR" in it.strings("origin_country") && it.ints("genre_ids").none { g -> g.toString() in NOT_DRAMA } }, 30,
        ),
        Kind(
            "cdrama", "tv",
            mapOf("with_origin_country" to "CN|TW|HK", "with_original_language" to "zh|cn", "without_genres" to NOT_DRAMA.joinToString(",")),
            TV_GENRES.filter { it.first !in NOT_DRAMA }, listOf("CN" to "Mainland China", "TW" to "Taiwan", "HK" to "Hong Kong"), null,
            { item ->
                item.strings("origin_country").any { it in setOf("CN", "TW", "HK") } &&
                    item.text("original_language") in setOf("zh", "cn") &&
                    item.ints("genre_ids").none { g -> g.toString() in NOT_DRAMA }
            },
            20,
        ),
    )

    private fun options(list: List<Pair<String, String>>) = list.map { CsCategories.Option(it.first, it.second) }

    private val SORTS = listOf(
        CsCategories.Option(NEWEST, "最新 Newest"),
        CsCategories.Option(POPULAR, "热门 Popular"),
        CsCategories.Option(TOP, "高分 Top rated"),
    )

    private val NAMES = mapOf(
        "movie" to Triple("电影", "Movies", "🎬"),
        "tv" to Triple("电视剧", "TV Series", "📺"),
        "anime" to Triple("动漫", "Anime", "🐉"),
        "kdrama" to Triple("韩剧", "K-Drama", "📺"),
        "cdrama" to Triple("国产剧", "C-Drama", "📺"),
    )

    /** The five sections; [only] keeps just those ids (in this order). */
    fun sections(only: Set<String>? = null): List<CsBrowse.Section> = KINDS.filter { only == null || it.id in only }.map { k ->
        val (name, english, glyph) = NAMES.getValue(k.id)
        val filters = listOfNotNull(
            CsCategories.Filter(GENRE, "类型 Genre", options(k.genres)),
            k.countries?.let { CsCategories.Filter(COUNTRY, "地区 Country", options(it)) },
            CsCategories.Filter(YEAR, "年份 Year", CsBrowse.years(30)),
            k.languages?.let { CsCategories.Filter(LANGUAGE, "语言 Language", options(it)) },
        )
        CsBrowse.Section(k.id, name, filters, SORTS, english = english, glyph = glyph)
    }

    /**
     * The category browser for a TMDB source. [get] asks the source's TMDB (a v3 path and query,
     * e.g. "discover/tv?with_genres=18&page=2"; null or an error counts as nothing found); [map]
     * turns a TMDB `results` array of [kind] ("movie" or "tv") into the source's own cards.
     */
    fun create(
        api: MainAPI,
        siteName: String,
        extraParams: String = "",
        only: Set<String>? = null,
        get: suspend (String) -> JsonNode? = { wecollege(it) },
        map: suspend (results: JsonNode, kind: String) -> List<SearchResponse>,
    ): CsBrowse = CsBrowse(api, siteName, sections(only), popularSort = POPULAR) { id, filters, query, page ->
        val k = KINDS.firstOrNull { it.id == id } ?: return@CsBrowse emptyList<SearchResponse>() to false
        val json = runCatching { get(if (query.isBlank()) discover(k, filters, page, extraParams) else search(k, filters, query, page)) }
            .getOrNull() ?: return@CsBrowse emptyList<SearchResponse>() to false
        val raw = json.get("results")?.toList().orEmpty()
        val kept = raw.filter { item ->
            item.get("adult")?.asBoolean() != true &&
                item.text("poster_path") != null &&
                listOfNotNull(item.text("title"), item.text("name"), item.text("original_title"), item.text("original_name"))
                    .none { CsCategories.isAdult(it) } &&
                (query.isBlank() || matches(k, item, filters))
        }
        val items = if (kept.isEmpty()) emptyList() else map(mapper.valueToTree(kept), k.kind)
        val total = json.get("total_pages")?.asInt() ?: 0
        items to (raw.isNotEmpty() && page < minOf(total, 500))
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun discover(k: Kind, filters: Map<String, String>, page: Int, extraParams: String): String {
        val p = LinkedHashMap(k.fixed)
        val movie = k.kind == "movie"
        filters[GENRE]?.let { g -> p["with_genres"] = listOfNotNull(k.fixed["with_genres"], g).joinToString(",") }
        filters[COUNTRY]?.let { p["with_origin_country"] = it }
        filters[LANGUAGE]?.let { p["with_original_language"] = it }
        filters[YEAR]?.let { p[if (movie) "primary_release_year" else "first_air_date_year"] = it }
        val date = if (movie) "primary_release_date" else "first_air_date"
        when (filters[CsCategories.SORT]) {
            POPULAR -> p["sort_by"] = "popularity.desc"
            TOP -> {
                p["sort_by"] = "vote_average.desc"
                p["vote_count.gte"] = (if (filters[YEAR] != null) k.minVotes / 5 else k.minVotes).coerceAtLeast(5).toString()
            }
            // Newest: released titles only (TMDB also lists announced ones with future dates).
            else -> {
                p["sort_by"] = "$date.desc"
                p["$date.lte"] = today()
                if (k.newestVotes > 0) p["vote_count.gte"] = k.newestVotes.toString()
            }
        }
        p["include_adult"] = "false"
        p["language"] = "en-US"
        p["page"] = page.toString()
        val query = p.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        return "discover/${k.kind}?$query" + extraParams.trim('&').takeIf { it.isNotEmpty() }?.let { "&$it" }.orEmpty()
    }

    private fun search(k: Kind, filters: Map<String, String>, query: String, page: Int): String {
        val year = filters[YEAR]?.let { (if (k.kind == "movie") "&primary_release_year=" else "&first_air_date_year=") + it }.orEmpty()
        return "search/${k.kind}?query=${enc(query)}&include_adult=false&language=en-US&page=$page$year"
    }

    /** For searches (TMDB's search has no filters): the section's own rule and the chosen filters. */
    private fun matches(k: Kind, item: JsonNode, filters: Map<String, String>): Boolean {
        if (!k.belongs(item)) return false
        filters[GENRE]?.toIntOrNull()?.let { if (it !in item.ints("genre_ids")) return false }
        // Movie results carry no origin_country (only details do), so only TV results are checked.
        val countries = item.strings("origin_country")
        filters[COUNTRY]?.let { c -> if (countries.isNotEmpty() && countries.none { it in c.split('|') }) return false }
        filters[LANGUAGE]?.let { if (item.text("original_language") != it) return false }
        return true
    }
}
