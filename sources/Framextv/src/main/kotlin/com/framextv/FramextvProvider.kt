package com.framextv

import com.csasian.shared.CsTmdbBrowse
import com.csasian.shared.CsSearch
import com.csasian.shared.CsBackup

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * framextv.cc is a Vite single-page front end for its own JSON backend (api.framextv.cc), which
 * wraps TMDB for the catalogue (`/api/discover`, `/api/search`, `/api/details`, `/api/season`)
 * and resolves streams server side by TMDB id (`/api/stream`). Each "provider" of the stream API
 * is one upstream host (vidly.pro, vidrock/cinemaos via MovieBox, hscow, ...); the API returns
 * plain HLS or MP4 URLs, mostly already wrapped in the site's own proxies, plus OpenSubtitles VTTs.
 */
class FramextvProvider : MainAPI() {
    override var mainUrl = "https://framextv.cc"
    override var name = "frameXTV"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper = ObjectMapper()
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    private val siteHeaders
        get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/", "Origin" to mainUrl)

    // Category browsing (shared/CsTmdbBrowse.kt): TMDB discover through the keyless TMDB proxy (the site's
    // API only offers fixed lists), shown as our own cards, so details and links still go through the site.
    private val browse = CsTmdbBrowse.create(this, name) { results, kind -> toResults(results, kind) }

    // The rows of the site's Movies and TV pages (the home page shows a subset of them).
    override val mainPage = browse.mainPage(mainPageOf(
        "movie/trending" to "Trending Movies",
        "movie/popular" to "Popular Movies",
        "movie/now_playing" to "In Theaters / Now Playing",
        "movie/top_rated" to "Top Rated Movies",
        "tv/trending" to "Trending TV",
        "tv/popular" to "Popular TV",
        "tv/on_the_air" to "On The Air",
        "tv/top_rated" to "Top Rated TV",
    ))

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        browse.rowPage(request) ?: com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    // Categories SkyStream Dev shows itself (shared/CsCategories.kt); the engine finds these by name.
    fun csCategories(): String = browse.categoriesJson()

    fun csCategoryPage(categoryId: String, filtersJson: String, query: String, page: Int): SearchResponseList =
        browse.categoryPage(categoryId, filtersJson, query, page)

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        val (type, category) = request.data.split("/", limit = 2)
        val json = api("discover", mapOf("type" to type, "category" to category, "page" to page.toString()))
        return newHomePageResponse(request.name, toResults(json["results"], type), hasNextPage(json, page))
    }

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
        val json = api("search", mapOf("q" to query.trim(), "page" to page.toString(), "per_page" to "24"))
        return toResults(json["results"], null) to hasNextPage(json, page)
    }

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
        val m = TITLE_REGEX.find(url) ?: throw ErrorLoadingException("Invalid frameXTV URL: $url")
        val type = m.groupValues[1]
        val id = m.groupValues[2]
        // Without type=tv the API answers with the movie that has the same TMDB id.
        val d = api("details", mapOf("id" to id, "type" to type))
        if (d["success"]?.asBoolean() == false) throw ErrorLoadingException("Title not found")
        val title = d.text("title") ?: throw ErrorLoadingException("Title not found")
        val poster = d.text("poster")
        val backdrop = d.text("backdrop")
        val plotText = d.text("overview")
        val yearValue = (d.text("year") ?: d.text("release_date"))?.take(4)?.toIntOrNull()
        val scoreValue = d.text("rating")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { Score.from10(it) }
        val tagList = d["genres"]?.mapNotNull { g -> if (g.isTextual) g.asText() else g.text("name") }.orEmpty()
        val actorList = d["cast"]?.take(20)?.mapNotNull { c ->
            val actorName = c.text("name") ?: return@mapNotNull null
            ActorData(Actor(actorName, c.text("profile") ?: c.text("profile_path")), roleString = c.text("character"))
        }.orEmpty()
        val trailer = d["trailer"]?.text("key")?.let { "https://www.youtube.com/watch?v=$it" }
        val recs = (toResults(d["recommendations"], type) + toResults(d["similar"], type)).distinctBy { it.url }

        if (type == "movie") {
            return newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/embed/movie/$id") {
                posterUrl = poster
                backgroundPosterUrl = backdrop
                plot = plotText
                year = yearValue
                score = scoreValue
                tags = tagList
                actors = actorList
                duration = d["runtime"]?.asInt()?.takeIf { it > 0 }
                recommendations = recs
                addTMDbId(id)
                addImdbId(d.text("imdb_id"))
                addTrailer(trailer)
            }
        }

        val episodes = loadEpisodes(id, d)
        if (episodes.isEmpty()) throw ErrorLoadingException("No aired episodes")
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            backgroundPosterUrl = backdrop
            plot = plotText
            year = yearValue
            score = scoreValue
            tags = tagList
            actors = actorList
            duration = d["runtime"]?.asInt()?.takeIf { it > 0 }
            recommendations = recs
            addTMDbId(id)
            addImdbId(d.text("imdb_id"))
            addTrailer(trailer)
        }
    }

    /** Every regular season (specials are skipped: the stream providers do not serve season 0). */
    private suspend fun loadEpisodes(id: String, show: JsonNode): List<Episode> {
        val seasonNumbers = show["seasons"]?.mapNotNull { it["season_number"]?.asInt() }
            ?.filter { it > 0 }?.distinct()?.sorted().orEmpty()
        val now = today()
        val seasons = seasonNumbers.amap { s ->
            s to runCatching { api("season", mapOf("id" to id, "season" to s.toString())) }.getOrNull()
        }
        return seasons.sortedBy { it.first }.flatMap { (seasonNumber, json) ->
            json?.get("episodes")?.mapNotNull { ep ->
                val number = (ep["episode_number"] ?: ep["number"])?.asInt()?.takeIf { it > 0 } ?: return@mapNotNull null
                val airDate = ep.text("air_date")
                // Unaired episodes have nothing to play.
                if (airDate != null && airDate > now) return@mapNotNull null
                newEpisode("$mainUrl/embed/tv/$id/$seasonNumber/$number") {
                    name = ep.text("name")
                    season = seasonNumber
                    episode = number
                    description = ep.text("overview")
                    posterUrl = ep.text("still")
                    runTime = ep["runtime"]?.asInt()?.takeIf { it > 0 }
                    score = ep["vote_average"]?.asDouble()?.takeIf { it > 0 }?.let { Score.from10(it) }
                    addDate(airDate)
                }
            }.orEmpty()
        }
    }

    private suspend fun loadOwnLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data is .../embed/movie/{id} or .../embed/tv/{id}/{season}/{episode}.
        val match = PLAY_REGEX.find(data) ?: return false
        val (type, id, season, episode) = match.destructured
        val baseParams = linkedMapOf("type" to type, "id" to id)
        if (type == "tv") {
            baseParams["season"] = season
            baseParams["episode"] = episode
        }

        // The automatic pick (null) first, then every provider the backend reports as available.
        val providers = listOf<String?>(null) + availableProviders()
        val responses = providers.amap { provider ->
            val params = if (provider == null) baseParams else baseParams + ("provider" to provider)
            runCatching { api("stream", params, timeout = 30L) }.getOrNull()
                ?.takeIf { it["success"]?.asBoolean() == true }
        }.filterNotNull()

        val sources = responses.flatMap { r ->
            r["sources"]?.mapNotNull { s ->
                val url = s.text("url") ?: return@mapNotNull null
                Source(
                    url = if (url.startsWith("/")) "$API$url" else url,
                    server = s.text("server") ?: r.text("provider") ?: name,
                    quality = s.text("quality"),
                    mp4 = s.text("type")?.lowercase() == "mp4" || url.substringBefore('?').endsWith(".mp4"),
                    headers = s["headers"]?.takeIf { it.isObject }?.fields()?.asSequence()
                        ?.associate { it.key to it.value.asText() }.orEmpty(),
                )
            }.orEmpty()
        }.distinctBy { it.url }

        // Check every source in parallel and offer the working ones first, in the API's order.
        val checked = sources.amap { it to probe(it) }
        val alive = checked.filter { it.second != null }
        val offered = alive.ifEmpty { checked }
        offered.forEach { (src, master) ->
            val height = qualityOf(src.quality) ?: master?.let { m ->
                RESOLUTION.findAll(m).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
            }
            callback(
                newExtractorLink(name, src.server, src.url, if (src.mp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8) {
                    referer = src.headers["Referer"] ?: "$mainUrl/"
                    quality = height ?: Qualities.Unknown.value
                    headers = playHeaders(src)
                },
            )
        }

        // OpenSubtitles tracks from the backend (VTT, served without restrictions).
        responses.flatMap { r -> r["subtitles"]?.toList().orEmpty() }
            .mapNotNull { s -> s.text("url")?.let { it to (s.text("label") ?: s.text("lang") ?: "Unknown") } }
            .distinctBy { it.first }
            .take(MAX_SUBTITLES)
            .forEach { (url, label) ->
                subtitleCallback(newSubtitleFile(label.removePrefix("Subtitles • ").trim(), url))
            }
        return offered.isNotEmpty()
    }

    private data class Source(
        val url: String,
        val server: String,
        val quality: String?,
        val mp4: Boolean,
        val headers: Map<String, String>,
    )

    private fun playHeaders(src: Source): Map<String, String> =
        mapOf("User-Agent" to userAgent, "Origin" to mainUrl) + src.headers.filterKeys { it != "Referer" }

    /**
     * Returns the playlist text (or "" for MP4) when the source answers with real media,
     * otherwise null. For HLS the master and the first variant playlist must both parse.
     */
    private suspend fun probe(src: Source): String? = runCatching {
        val headers = playHeaders(src) + ("Referer" to (src.headers["Referer"] ?: "$mainUrl/"))
        if (src.mp4) {
            val r = app.get(src.url, headers = headers + ("Range" to "bytes=0-1"), timeout = 12L)
            val ct = r.headers["Content-Type"].orEmpty()
            return@runCatching if ((r.code == 200 || r.code == 206) && !ct.contains("text") && !ct.contains("json")) "" else null
        }
        val master = app.get(src.url, headers = headers, timeout = 12L).text
        if (!master.trimStart().startsWith("#EXTM3U")) return@runCatching null
        var playlist = master
        var playlistUrl = src.url
        if (!master.contains("#EXTINF")) {
            val variant = firstUri(master) ?: return@runCatching null
            playlistUrl = resolve(src.url, variant)
            playlist = app.get(playlistUrl, headers = headers, timeout = 12L).text
            if (!playlist.contains("#EXTINF")) return@runCatching null
        }
        // Some hosts serve segments as images with the video appended after them, which the
        // player cannot decode. Only accept real MPEG-TS / fMP4 (or AES-128 encrypted) segments.
        if (playlist.contains("#EXT-X-KEY:METHOD=AES-128")) return@runCatching master
        val segment = firstUri(playlist) ?: return@runCatching null
        val r = app.get(resolve(playlistUrl, segment), headers = headers + ("Range" to "bytes=0-1023"), timeout = 12L)
        if (r.code != 200 && r.code != 206) return@runCatching null
        val bytes = r.body.byteStream().use { readHead(it, 16) }
        val box = String(bytes, 4, 4, Charsets.ISO_8859_1)
        if (bytes[0] == 0x47.toByte() || box == "ftyp" || box == "styp" || box == "moof") master else null
    }.getOrNull()

    /** Reads the first [size] bytes (zero-padded if the body is shorter). */
    private fun readHead(stream: java.io.InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = stream.read(buffer, read, size - read)
            if (n < 0) break
            read += n
        }
        return buffer
    }

    private fun firstUri(playlist: String): String? =
        playlist.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }

    private fun resolve(base: String, ref: String): String =
        if (ref.startsWith("http")) ref else java.net.URI(base).resolve(ref).toString()

    private fun qualityOf(label: String?): Int? {
        val l = label?.lowercase() ?: return null
        return when {
            l.contains("4k") || l.contains("2160") -> Qualities.P2160.value
            else -> Regex("""(\d{3,4})p""").find(l)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    /** Provider ids the backend reports as available, cached for ten minutes. */
    private suspend fun availableProviders(): List<String> = providerLock.withLock {
        val cached = providerCache
        if (cached != null && System.currentTimeMillis() - cached.first < PROVIDER_TTL) return@withLock cached.second
        val ids = runCatching {
            api("providers", emptyMap(), timeout = 15L)["providers"]?.mapNotNull { p ->
                val id = p.text("id") ?: return@mapNotNull null
                val category = p.text("category")
                if (p.text("status") == "available" && (category == null || category == "movie_tv")) id else null
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (ids != null) providerCache = System.currentTimeMillis() to ids
        ids ?: cached?.second ?: FALLBACK_PROVIDERS
    }

    // ---- API ----

    private suspend fun api(path: String, params: Map<String, String>, timeout: Long = 20L): JsonNode {
        val query = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val response = app.get("$API/api/$path${if (query.isEmpty()) "" else "?$query"}", headers = siteHeaders, timeout = timeout)
        if (!response.isSuccessful && response.code != 404) throw ErrorLoadingException("frameXTV API error ${response.code}")
        return mapper.readTree(response.text)
    }

    private fun hasNextPage(json: JsonNode, page: Int): Boolean {
        val total = json["totalPages"]?.asInt() ?: json["total_pages"]?.asInt() ?: 0
        return page < minOf(total, 500)
    }

    private fun toResults(results: JsonNode?, defaultType: String?): List<SearchResponse> {
        if (results == null || !results.isArray) return emptyList()
        return results.mapNotNull { item ->
            val type = item.text("type") ?: item.text("media_type") ?: defaultType ?: return@mapNotNull null
            if (type != "movie" && type != "tv") return@mapNotNull null
            val id = item.text("id")?.takeIf { it.all(Char::isDigit) } ?: return@mapNotNull null
            val title = item.text("title") ?: item.text("name") ?: return@mapNotNull null
            val url = "$mainUrl/$type/$id"
            // The site's API gives poster/year/rating; plain TMDB results (categories) poster_path etc.
            val poster = item.text("poster") ?: item.text("image") ?: item.text("poster_path")?.let { "https://image.tmdb.org/t/p/w342$it" }
            val yearValue = (item.text("year") ?: item.text("release_date") ?: item.text("first_air_date"))?.take(4)?.toIntOrNull()
            val scoreValue = (item.text("rating") ?: item.text("vote_average"))?.toDoubleOrNull()?.takeIf { it > 0 }?.let { Score.from10(it) }
            if (type == "movie") {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    posterUrl = poster
                    year = yearValue
                    score = scoreValue
                }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    posterUrl = poster
                    year = yearValue
                    score = scoreValue
                }
            }
        }.distinctBy { it.url }
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun JsonNode.text(field: String): String? =
        this[field]?.takeIf { !it.isNull && !it.isContainerNode }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val API = "https://api.framextv.cc"
        private const val PROVIDER_TTL = 10 * 60 * 1000L
        private const val MAX_SUBTITLES = 60

        // Used when /api/providers cannot be read.
        private val FALLBACK_PROVIDERS = listOf(
            "vidly", "bowler", "giant", "executioner", "barbarian_king", "super_barbarian", "royal_champion",
        )

        private val providerLock = Mutex()
        @Volatile private var providerCache: Pair<Long, List<String>>? = null

        private val TITLE_REGEX = Regex("""/(movie|tv)/(\d+)""")
        private val PLAY_REGEX = Regex("""/embed/(movie|tv)/(\d+)(?:/(\d+)/(\d+))?""")
        private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")
    }
}
