package com.crackle

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
 * crackle.beauty is a single-page front end for TMDB. Its catalogue, search and details all come
 * from api.themoviedb.org with the key built into the site's JS bundle, and its player embeds
 * third-party hosts by TMDB id. Of those hosts, VixSrc serves plain HLS (standard AES-128 segment
 * keys, no DRM), so playback is resolved through VixSrc. VixSrc's availability lists are used to
 * hide titles it cannot play.
 */
class CrackleProvider : MainAPI() {
    override var mainUrl = "https://crackle.beauty"
    override var name = "Crackle"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper = ObjectMapper()
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    private val siteHeaders
        get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
    private val vixHeaders = mapOf("User-Agent" to userAgent, "Referer" to "$VIX/")

    // The site's home rows (Trending Today/This Week, Popular Movies/TV), plus the Newest and
    // Top Rated sorts from its Movies and TV Shows pages.
    override val mainPage = mainPageOf(
        "trending/all/day" to "Trending Today",
        "trending/all/week" to "Trending This Week",
        "movie/popular" to "Popular Movies",
        "tv/popular" to "Popular TV Shows",
        "newest/movie" to "Newest Movies",
        "newest/tv" to "Newest TV Shows",
        "movie/top_rated" to "Top Rated Movies",
        "tv/top_rated" to "Top Rated TV Shows",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val params = linkedMapOf("page" to page.toString())
        val path = when (request.data) {
            "newest/movie" -> {
                // The site sorts by release_date.desc unfiltered, which lists unreleased titles
                // years ahead. Limit to released titles with some votes.
                params["sort_by"] = "primary_release_date.desc"
                params["primary_release_date.lte"] = today()
                params["vote_count.gte"] = "20"
                "discover/movie"
            }
            "newest/tv" -> {
                params["sort_by"] = "first_air_date.desc"
                params["first_air_date.lte"] = today()
                params["vote_count.gte"] = "20"
                "discover/tv"
            }
            else -> request.data
        }
        val json = tmdb(path, params)
        val defaultType = when {
            path.startsWith("tv") || path.endsWith("/tv") -> "tv"
            path.startsWith("movie") || path.endsWith("/movie") -> "movie"
            else -> null
        }
        val items = playable(json["results"], defaultType)
        return newHomePageResponse(request.name, items, hasNextPage(json, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        val json = tmdb("search/multi", mapOf("query" to query.trim(), "page" to page.toString(), "include_adult" to "false"))
        return playable(json["results"], null) to hasNextPage(json, page)
    }

    // Remembers episodes and adds backup links from our other sources (see shared/CsBackup.kt).
    override suspend fun load(url: String): LoadResponse = loadPage(url).also { CsBackup.remember(this, it) }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = CsBackup.loadLinks(this, data, subtitleCallback, callback) {
        loadOwnLinks(data, isCasting, subtitleCallback, callback)
    }

    private suspend fun loadPage(url: String): LoadResponse {
        val (type, id) = parseTitleUrl(url) ?: throw ErrorLoadingException("Invalid Crackle URL: $url")
        val d = tmdb("$type/$id", mapOf("append_to_response" to "credits,external_ids,videos,recommendations"))
        val title = d.text("title") ?: d.text("name") ?: throw ErrorLoadingException("Title not found")
        val poster = image(d.text("poster_path"), "w500")
        val backdrop = image(d.text("backdrop_path"), "w1280")
        val plotText = d.text("overview")
        val yearValue = (d.text("release_date") ?: d.text("first_air_date"))?.take(4)?.toIntOrNull()
        val scoreValue = d["vote_average"]?.asDouble()?.takeIf { it > 0 }?.let { Score.from10(it) }
        val tagList = d["genres"]?.mapNotNull { it.text("name") }.orEmpty()
        val actorList = d["credits"]?.get("cast")?.take(20)?.mapNotNull { c ->
            val actorName = c.text("name") ?: return@mapNotNull null
            ActorData(Actor(actorName, image(c.text("profile_path"), "w185")), roleString = c.text("character"))
        }.orEmpty()
        val imdbId = d["external_ids"]?.text("imdb_id") ?: d.text("imdb_id")
        val trailer = d["videos"]?.get("results")
            ?.firstOrNull { it.text("site") == "YouTube" && it.text("type") == "Trailer" }
            ?.text("key")?.let { "https://www.youtube.com/watch?v=$it" }
        val recs = d["recommendations"]?.get("results")?.let { playable(it, type) }.orEmpty()

        if (type == "movie") {
            return newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/play/movie/$id") {
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
                addImdbId(imdbId)
                addTrailer(trailer)
            }
        }

        val episodes = loadEpisodes(id, d)
        if (episodes.isEmpty()) throw ErrorLoadingException("No aired episodes")
        val runtime = d["episode_run_time"]?.firstOrNull()?.asInt()?.takeIf { it > 0 }
            ?: d["last_episode_to_air"]?.get("runtime")?.asInt()?.takeIf { it > 0 }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            backgroundPosterUrl = backdrop
            plot = plotText
            year = yearValue
            score = scoreValue
            tags = tagList
            actors = actorList
            duration = runtime
            recommendations = recs
            showStatus = when (d.text("status")) {
                "Returning Series", "In Production" -> ShowStatus.Ongoing
                "Ended", "Canceled" -> ShowStatus.Completed
                else -> null
            }
            addTMDbId(id)
            addImdbId(imdbId)
            addTrailer(trailer)
        }
    }

    /** Fetches every regular season (specials are skipped; VixSrc has no season 0), 20 per request. */
    private suspend fun loadEpisodes(id: String, show: JsonNode): List<Episode> {
        val seasonNumbers = show["seasons"]?.mapNotNull { it["season_number"]?.asInt() }
            ?.filter { it > 0 }?.distinct()?.sorted().orEmpty()
        val now = today()
        val episodes = mutableListOf<Episode>()
        seasonNumbers.chunked(20).forEach { chunk ->
            val json = tmdb("tv/$id", mapOf("append_to_response" to chunk.joinToString(",") { "season/$it" }))
            chunk.forEach { seasonNumber ->
                json["season/$seasonNumber"]?.get("episodes")?.forEach { ep ->
                    val number = ep["episode_number"]?.asInt() ?: return@forEach
                    val airDate = ep.text("air_date")
                    // Unaired episodes have nothing to play.
                    if (airDate == null || airDate > now) return@forEach
                    episodes += newEpisode("$mainUrl/play/tv/$id/$seasonNumber/$number") {
                        name = ep.text("name")
                        season = seasonNumber
                        episode = number
                        description = ep.text("overview")
                        posterUrl = image(ep.text("still_path"), "w300")
                        runTime = ep["runtime"]?.asInt()?.takeIf { it > 0 }
                        score = ep["vote_average"]?.asDouble()?.takeIf { it > 0 }?.let { Score.from10(it) }
                        addDate(airDate)
                    }
                }
            }
        }
        return episodes
    }

    private suspend fun loadOwnLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data is .../play/movie/{id} or .../play/tv/{id}/{season}/{episode}.
        val match = PLAY_REGEX.find(data) ?: return false
        val (type, id, season, episode) = match.destructured
        val apiPath = if (type == "tv") "tv/$id/$season/$episode" else "movie/$id"

        val apiResponse = app.get("$VIX/api/$apiPath", headers = vixHeaders)
        if (!apiResponse.isSuccessful) return false
        val src = runCatching { mapper.readTree(apiResponse.text).text("src") }.getOrNull() ?: return false

        // The embed page holds the playlist token and the list of CDN servers. It needs a Referer.
        val embed = app.get(VIX + src.replace("\\/", "/"), headers = vixHeaders).text
        val token = JS_TOKEN.find(embed)?.groupValues?.get(1) ?: return false
        val expires = JS_EXPIRES.find(embed)?.groupValues?.get(1) ?: return false
        val playlistUrl = JS_URL.find(embed)?.groupValues?.get(1) ?: "$VIX/playlist/${src.substringAfter("/embed/").substringBefore('?')}"
        val fhd = embed.contains("window.canPlayFHD = true")
        val servers = parseServers(embed).ifEmpty { listOf("Server 1" to "$playlistUrl?cf=1", "Server 2" to "$playlistUrl?cf=0") }

        val candidates = servers.map { (label, base) ->
            // lang=en makes the English audio track the default (otherwise Italian is).
            val sep = if (base.contains('?')) "&" else "?"
            label to "$base${sep}token=$token&expires=$expires&lang=en${if (fhd) "&h=1" else ""}"
        }

        // Check every server in parallel (master playlist, variant playlist and the first bytes of
        // a segment from its CDN) and offer the working ones, in the site's order.
        val checked = candidates.amap { (label, url) -> Triple(label, url, probe(url)) }
        val alive = checked.filter { it.third != null }
        val offered = alive.ifEmpty { checked }

        offered.forEach { (label, url, master) ->
            val height = master?.let { m ->
                RESOLUTION.findAll(m).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
            }
            callback(
                newExtractorLink(name, "$name VixSrc $label", url, ExtractorLinkType.M3U8) {
                    referer = "$VIX/"
                    quality = height ?: Qualities.Unknown.value
                    headers = mapOf("User-Agent" to userAgent)
                },
            )
        }

        // Pass the playlist's subtitle tracks through as plain VTT files.
        alive.firstOrNull()?.third?.let { master ->
            SUBTITLE_MEDIA.findAll(master).toList().amap { m ->
                val label = m.groupValues[1]
                val vtt = runCatching {
                    app.get(m.groupValues[2], headers = vixHeaders).text.lines()
                        .firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim()
                }.getOrNull()
                if (vtt != null) subtitleCallback(newSubtitleFile(label, vtt))
            }
        }
        return offered.isNotEmpty()
    }

    /** Returns the master playlist text when the server works end to end, otherwise null. */
    private suspend fun probe(masterUrl: String): String? = runCatching {
        val master = app.get(masterUrl, headers = vixHeaders, timeout = 10L).text
        if (!master.contains("#EXT-X-STREAM-INF")) return@runCatching null
        val variantUrl = master.lines().lastOrNull { it.startsWith("http") } ?: return@runCatching null
        val variant = app.get(variantUrl, headers = vixHeaders, timeout = 10L).text
        val segment = variant.lines().firstOrNull { it.startsWith("http") } ?: return@runCatching null
        val code = app.get(segment, headers = vixHeaders + ("Range" to "bytes=0-1023"), timeout = 10L).code
        if (code == 200 || code == 206) master else null
    }.getOrNull()

    private fun parseServers(embed: String): List<Pair<String, String>> {
        val raw = JS_STREAMS.find(embed)?.groupValues?.get(1) ?: return emptyList()
        return runCatching {
            mapper.readTree(raw).mapNotNull { node ->
                val url = node.text("url") ?: return@mapNotNull null
                (node.text("name") ?: "Server") to url
            }
        }.getOrDefault(emptyList())
    }

    // ---- TMDB ----

    private suspend fun tmdb(path: String, params: Map<String, String> = emptyMap()): JsonNode {
        val query = (mapOf("api_key" to TMDB_KEY, "language" to "en-US") + params).entries
            .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val response = app.get("$TMDB_API/$path?$query", headers = siteHeaders)
        if (!response.isSuccessful) throw ErrorLoadingException("TMDB error ${response.code}")
        return mapper.readTree(response.text)
    }

    private fun hasNextPage(json: JsonNode, page: Int): Boolean {
        // TMDB refuses pages above 500.
        val total = json["total_pages"]?.asInt() ?: 0
        return page < minOf(total, 500)
    }

    /** Converts TMDB results to search responses, keeping only titles VixSrc can play. */
    private suspend fun playable(results: JsonNode?, defaultType: String?): List<SearchResponse> {
        if (results == null) return emptyList()
        val movies = availability("movie")
        val shows = availability("tv")
        return results.mapNotNull { item ->
            val type = item.text("media_type") ?: defaultType
                ?: if (item.has("title")) "movie" else "tv"
            if (type != "movie" && type != "tv") return@mapNotNull null
            val id = item["id"]?.asInt() ?: return@mapNotNull null
            val available = if (type == "movie") movies else shows
            if (available != null && id !in available) return@mapNotNull null
            val title = item.text("title") ?: item.text("name") ?: return@mapNotNull null
            val url = "$mainUrl/details/$type/$id"
            val poster = image(item.text("poster_path"), "w342")
            val yearValue = (item.text("release_date") ?: item.text("first_air_date"))?.take(4)?.toIntOrNull()
            val scoreValue = item["vote_average"]?.asDouble()?.takeIf { it > 0 }?.let { Score.from10(it) }
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

    /** VixSrc's list of TMDB ids it can play, cached for six hours. Null when unavailable (no filtering). */
    private suspend fun availability(type: String): Set<Int>? = availabilityLock.withLock {
        val cached = availabilityCache[type]
        if (cached != null && System.currentTimeMillis() - cached.first < AVAILABILITY_TTL) return@withLock cached.second
        val ids = runCatching {
            val text = app.get("$VIX/api/list/$type/", headers = vixHeaders, timeout = 30L).text
            mapper.readTree(text).mapNotNull { it["tmdb_id"]?.asInt() }.toHashSet()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (ids != null) availabilityCache[type] = System.currentTimeMillis() to ids
        ids ?: cached?.second
    }

    private fun parseTitleUrl(url: String): Pair<String, String>? {
        val m = TITLE_REGEX.find(url) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    private fun image(path: String?, size: String): String? =
        path?.takeIf { it.startsWith("/") }?.let { "$TMDB_IMAGE$size$it" }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun JsonNode.text(field: String): String? =
        this[field]?.takeIf { !it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_IMAGE = "https://image.tmdb.org/t/p/"
        // Public key embedded in crackle.beauty's JS bundle.
        private const val TMDB_KEY = "e1a8efff4415028c5c266b3fcd50db6e"
        private const val VIX = "https://vixsrc.to"
        private const val AVAILABILITY_TTL = 6 * 60 * 60 * 1000L

        private val availabilityLock = Mutex()
        private val availabilityCache = HashMap<String, Pair<Long, Set<Int>>>()

        private val TITLE_REGEX = Regex("""/(?:details|play)/(movie|tv)/(\d+)""")
        private val PLAY_REGEX = Regex("""/play/(movie|tv)/(\d+)(?:/(\d+)/(\d+))?""")
        private val JS_TOKEN = Regex("""['"]token['"]\s*:\s*['"]([^'"]+)['"]""")
        private val JS_EXPIRES = Regex("""['"]expires['"]\s*:\s*['"](\d+)['"]""")
        private val JS_URL = Regex("""url\s*:\s*['"](https://[^'"]+/playlist/\d+)['"]""")
        private val JS_STREAMS = Regex("""window\.streams\s*=\s*(\[.*?]);""")
        private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")
        private val SUBTITLE_MEDIA = Regex("""TYPE=SUBTITLES[^\n]*?NAME="([^"]+)"[^\n]*?URI="([^"]+)"""")
    }
}
