package com.rivestream

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
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * rivestream.app is a Next.js front end over one JSON endpoint, `/api/backendfetch`, which proxies
 * TMDB for the catalogue and resolves streams per "service" (astron, rift, lumen, pulse, ...).
 * Every request carries a `secretKey` that the page computes from the request's id or search
 * query (see [RiveKey]). Most services answer with HLS URLs wrapped in the cors.corsflix.net
 * proxy; the real playlist URL and the headers it needs are encoded in that wrapper.
 */
class RivestreamProvider : MainAPI() {
    override var mainUrl = "https://www.rivestream.app"
    override var name = "Rivestream"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val mapper = ObjectMapper()
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private val siteHeaders
        get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/", "Accept" to "application/json")

    // "requestID|type|extra query": the rows of the site's home, Movies, TV, K-Drama and Anime pages.
    override val mainPage = mainPageOf(
        "trendingMovie|movie|" to "Trending Movies",
        "trendingTv|tv|" to "Trending TV",
        "latestMovie|movie|" to "Latest Movies",
        "latestTv|tv|" to "Latest TV",
        "popularMovie|movie|sortBy=popularity.desc" to "Popular Movies",
        "popularTv|tv|sortBy=popularity.desc" to "Popular TV",
        "topRatedMovie|movie|" to "Top Rated Movies",
        "topRatedTv|tv|" to "Top Rated TV",
        "withKeywordsTv|tv|genreKeywords=,&sortBy=popularity.desc&country=KR" to "K-Drama",
        "withKeywordsTv|anime|genreKeywords=210024,&sortBy=popularity.desc" to "Anime",
        "withKeywordsMovie|animemovie|genreKeywords=210024,&sortBy=popularity.desc" to "Anime Movies",
    )

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        val (requestId, type, extra) = request.data.split("|", limit = 3)
        val params = linkedMapOf("requestID" to requestId)
        extra.split("&").filter { it.contains("=") }.forEach {
            params[it.substringBefore("=")] = it.substringAfter("=")
        }
        params["language"] = "en-US"
        params["page"] = page.toString()
        val json = api(params, keySource = null)
        val (mediaType, tvType) = when (type) {
            "anime" -> "tv" to TvType.Anime
            "animemovie" -> "movie" to TvType.AnimeMovie
            "tv" -> "tv" to null
            else -> "movie" to null
        }
        return newHomePageResponse(request.name, toResults(json["results"], mediaType, tvType), hasNextPage(json, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList<SearchResponse>() to false
        val json = api(
            linkedMapOf("requestID" to "searchMulti", "query" to q, "language" to "en-US", "page" to page.toString()),
            keySource = q,
        )
        return toResults(json["results"], null, null) to hasNextPage(json, page)
    }

    // Remembers episodes and adds backup links from our other sources (see shared/CsBackup.kt).
    override suspend fun load(url: String): LoadResponse =
        com.csasian.shared.CsMore.load(this, url) ?: loadPage(url).also { CsBackup.remember(this, it) }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = CsBackup.loadLinks(this, data, subtitleCallback, callback) {
        loadOwnLinks(data, isCasting, subtitleCallback, callback)
    }

    private suspend fun loadPage(url: String): LoadResponse {
        val q = queryOf(url)
        val type = q["type"]?.takeIf { it == "movie" || it == "tv" } ?: throw ErrorLoadingException("Invalid Rivestream URL: $url")
        val id = q["id"]?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: throw ErrorLoadingException("Invalid Rivestream URL: $url")

        val prefix = if (type == "movie") "movie" else "tv"
        fun req(requestId: String, vararg extra: Pair<String, String>) =
            linkedMapOf("id" to id, "requestID" to requestId, "language" to "en-US").apply { putAll(extra) }

        val parts = listOf(
            req("${prefix}Data"),
            req("${prefix}Casts"),
            req("${prefix}Videos"),
            req("${prefix}Related", "page" to "1"),
            req("${prefix}Similar", "page" to "1"),
        ).amap { p -> runCatching { api(p, keySource = id) }.getOrNull() }
        val d = parts[0] ?: throw ErrorLoadingException("Title not found")
        val title = d.text("title") ?: d.text("name") ?: throw ErrorLoadingException("Title not found")

        val poster = d.text("poster_path")?.let { "$IMG/w500$it" }
        val backdrop = d.text("backdrop_path")?.let { "$IMG/original$it" }
        val plotText = d.text("overview")
        val yearValue = (d.text("release_date") ?: d.text("first_air_date"))?.take(4)?.toIntOrNull()
        val scoreValue = d["vote_average"]?.asDouble()?.takeIf { it > 0 }?.let { Score.from10(it) }
        val genreIds = d["genres"]?.mapNotNull { it["id"]?.asInt() }.orEmpty()
        val tagList = d["genres"]?.mapNotNull { it.text("name") }.orEmpty()
        val actorList = parts[1]?.get("cast")?.take(20)?.mapNotNull { c ->
            val actorName = c.text("name") ?: return@mapNotNull null
            ActorData(Actor(actorName, c.text("profile_path")?.let { "$IMG/w185$it" }), roleString = c.text("character"))
        }.orEmpty()
        val trailer = parts[2]?.get("results")
            ?.filter { it.text("site") == "YouTube" && it.text("key") != null }
            ?.sortedByDescending { if (it.text("type") == "Trailer") 1 else 0 }
            ?.firstOrNull()?.text("key")?.let { "https://www.youtube.com/watch?v=$it" }
        val recs = (toResults(parts[3]?.get("results"), type, null) + toResults(parts[4]?.get("results"), type, null))
            .distinctBy { it.url }
        val imdb = d.text("imdb_id") ?: d["external_ids"]?.text("imdb_id")
        val isAnime = 16 in genreIds && d.text("original_language") == "ja"

        if (type == "movie") {
            return newMovieLoadResponse(
                title, url, if (isAnime) TvType.AnimeMovie else TvType.Movie, "$mainUrl/watch?type=movie&id=$id",
            ) {
                posterUrl = poster
                backgroundPosterUrl = backdrop
                plot = plotText
                year = yearValue
                score = scoreValue
                tags = tagList
                this.actors = actorList
                duration = d["runtime"]?.asInt()?.takeIf { it > 0 }
                recommendations = recs
                addTMDbId(id)
                addImdbId(imdb)
                addTrailer(trailer)
            }
        }

        val episodes = loadEpisodes(id, d)
        if (episodes.isEmpty()) throw ErrorLoadingException("No aired episodes")
        return newTvSeriesLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.TvSeries, episodes) {
            posterUrl = poster
            backgroundPosterUrl = backdrop
            plot = plotText
            year = yearValue
            score = scoreValue
            tags = tagList
            this.actors = actorList
            duration = d["episode_run_time"]?.firstOrNull()?.asInt()?.takeIf { it > 0 }
            recommendations = recs
            addTMDbId(id)
            addImdbId(imdb)
            addTrailer(trailer)
        }
    }

    /** Every regular season (season 0 "Specials" is skipped: the services do not serve it). */
    private suspend fun loadEpisodes(id: String, show: JsonNode): List<Episode> {
        val seasonNumbers = show["seasons"]?.mapNotNull { it["season_number"]?.asInt() }
            ?.filter { it > 0 }?.distinct()?.sorted().orEmpty()
        val now = today()
        val seasons = seasonNumbers.amap { s ->
            s to runCatching {
                api(
                    linkedMapOf("id" to id, "season" to s.toString(), "requestID" to "tvEpisodes", "language" to "en-US"),
                    keySource = id,
                )
            }.getOrNull()
        }
        return seasons.sortedBy { it.first }.flatMap { (seasonNumber, json) ->
            json?.get("episodes")?.mapNotNull { ep ->
                val number = ep["episode_number"]?.asInt()?.takeIf { it > 0 } ?: return@mapNotNull null
                val airDate = ep.text("air_date")
                // Unaired episodes have nothing to play.
                if (airDate == null || airDate > now) return@mapNotNull null
                newEpisode("$mainUrl/watch?type=tv&id=$id&season=$seasonNumber&episode=$number") {
                    name = ep.text("name")
                    season = seasonNumber
                    episode = number
                    description = ep.text("overview")
                    posterUrl = ep.text("still_path")?.let { "$IMG/w500$it" }
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
        // data is .../watch?type=movie&id={id} or .../watch?type=tv&id={id}&season={s}&episode={e}.
        val q = queryOf(data)
        val type = q["type"] ?: return false
        val id = q["id"]?.takeIf { it.isNotEmpty() } ?: return false
        val season = q["season"]
        val episode = q["episode"]
        if (type == "tv" && (season == null || episode == null)) return false

        val responses = services().amap { service ->
            val params = linkedMapOf<String, String>()
            if (type == "tv") {
                params["requestID"] = "tvVideoProvider"
                params["id"] = id
                params["season"] = season!!
                params["episode"] = episode!!
            } else {
                params["requestID"] = "movieVideoProvider"
                params["id"] = id
            }
            params["service"] = service
            if (service == "primevids" || service == "citadel") {
                params["cb"] = (System.currentTimeMillis() / 3_000_000L).toString()
            }
            service to withTimeoutOrNull(SERVICE_TIMEOUT) {
                runCatching { api(params, keySource = id, timeout = SERVICE_TIMEOUT / 1000) }.getOrNull()
            }?.get("data")?.takeIf { it.isObject }
        }

        var found = false
        val seen = HashSet<String>()
        responses.forEach { (service, d) ->
            d?.get("sources")?.forEach { s ->
                val wrapped = s.text("url") ?: return@forEach
                if (!seen.add(wrapped)) return@forEach
                val (url, headers) = unwrap(wrapped)
                // vidrift "orion" playlists answer 403/404 outside the site's own player.
                if (url.contains("/api/orion/")) return@forEach
                val label = s.text("quality") ?: ""
                val mp4 = s.text("format")?.lowercase() == "mp4" || url.substringBefore('?').endsWith(".mp4")
                val linkName = listOf(name, service, label).filter { it.isNotBlank() }.joinToString(" ")
                callback(
                    newExtractorLink("$name $service", linkName, url, if (mp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8) {
                        referer = headers["Referer"] ?: "$mainUrl/"
                        quality = qualityOf(label) ?: Qualities.Unknown.value
                        this.headers = headers.filterKeys { it != "Referer" }
                    },
                )
                found = true
            }
        }

        // Caption tracks (VTT) returned by some services; rawUrl is the unproxied file.
        responses.flatMap { (_, d) -> d?.get("captions")?.toList().orEmpty() }
            .mapNotNull { c ->
                val url = c.text("rawUrl") ?: c.text("file") ?: return@mapNotNull null
                url to (c.text("label") ?: c.text("language") ?: "Unknown")
            }
            .distinctBy { it.first }
            .take(MAX_SUBTITLES)
            .forEach { (url, label) -> subtitleCallback(newSubtitleFile(label, url)) }
        return found
    }

    /**
     * cors.corsflix.net/m3u8?url=<real>&headers=<json> -> the real URL plus those headers. Any
     * other URL is played directly with the site's own referer.
     */
    private fun unwrap(url: String): Pair<String, Map<String, String>> {
        val fallback = url to mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/", "Origin" to mainUrl)
        val host = runCatching { URI(url).host }.getOrNull() ?: return fallback
        if (!host.endsWith("corsflix.net")) return fallback
        val q = queryOf(url)
        val real = q["url"]?.takeIf { it.startsWith("http") } ?: return fallback
        val headers = runCatching {
            q["headers"]?.let { h -> mapper.readTree(h).fields().asSequence().associate { it.key to it.value.asText() } }
        }.getOrNull().orEmpty()
        return real to (mapOf("User-Agent" to userAgent) + headers)
    }

    private fun qualityOf(label: String?): Int? {
        val l = label?.lowercase() ?: return null
        return when {
            l.contains("4k") || l.contains("2160") -> Qualities.P2160.value
            else -> Regex("""(\d{3,4})p""").find(l)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    /** Service names the backend offers, cached for ten minutes. */
    private suspend fun services(): List<String> = serviceLock.withLock {
        val cached = serviceCache
        if (cached != null && System.currentTimeMillis() - cached.first < SERVICE_TTL) return@withLock cached.second
        val names = runCatching {
            api(linkedMapOf("requestID" to "VideoProviderServices"), keySource = null, timeout = 15L)["data"]
                ?.mapNotNull { it.asText()?.takeIf(String::isNotBlank) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (names != null) serviceCache = System.currentTimeMillis() to names
        names ?: cached?.second ?: FALLBACK_SERVICES
    }

    // ---- API ----

    /** [keySource] is what the site derives the secretKey from: the id, the search query, or nothing. */
    private suspend fun api(params: Map<String, String>, keySource: String?, timeout: Long = 20L): JsonNode {
        val query = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val url = "$mainUrl/api/backendfetch?$query&secretKey=${URLEncoder.encode(RiveKey.of(keySource), "UTF-8")}&proxyMode=noProxy"
        val response = app.get(url, headers = siteHeaders, timeout = timeout)
        if (!response.isSuccessful) throw ErrorLoadingException("Rivestream API error ${response.code}")
        return mapper.readTree(response.text)
    }

    private fun hasNextPage(json: JsonNode, page: Int): Boolean {
        val total = json["total_pages"]?.asInt() ?: 0
        return page < minOf(total, 500)
    }

    private fun toResults(results: JsonNode?, defaultType: String?, forcedType: TvType?): List<SearchResponse> {
        if (results == null || !results.isArray) return emptyList()
        return results.mapNotNull { item ->
            val type = item.text("media_type") ?: defaultType ?: return@mapNotNull null
            if (type != "movie" && type != "tv") return@mapNotNull null
            val id = item.text("id")?.takeIf { it.all(Char::isDigit) } ?: return@mapNotNull null
            val title = item.text("title") ?: item.text("name") ?: return@mapNotNull null
            val url = "$mainUrl/detail?type=$type&id=$id"
            val poster = item.text("poster_path")?.let { "$IMG/w342$it" }
            val yearValue = (item.text("release_date") ?: item.text("first_air_date"))?.take(4)?.toIntOrNull()
            val scoreValue = item["vote_average"]?.asDouble()?.takeIf { it > 0 }?.let { Score.from10(it) }
            if (type == "movie") {
                newMovieSearchResponse(title, url, forcedType ?: TvType.Movie) {
                    posterUrl = poster
                    year = yearValue
                    score = scoreValue
                }
            } else {
                newTvSeriesSearchResponse(title, url, forcedType ?: TvType.TvSeries) {
                    posterUrl = poster
                    year = yearValue
                    score = scoreValue
                }
            }
        }.distinctBy { it.url }
    }

    private fun queryOf(url: String): Map<String, String> =
        url.substringAfter('?', "").split("&").filter { it.contains("=") }.associate {
            it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
        }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun JsonNode.text(field: String): String? =
        this[field]?.takeIf { !it.isNull && !it.isContainerNode }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val IMG = "https://image.tmdb.org/t/p"
        private const val SERVICE_TTL = 10 * 60 * 1000L
        private const val SERVICE_TIMEOUT = 15_000L
        private const val MAX_SUBTITLES = 60

        // Used when VideoProviderServices cannot be read.
        private val FALLBACK_SERVICES = listOf(
            "heron", "hera", "astron", "triton", "tychon", "hadron", "theon", "eon", "oberon", "fortress", "kismet",
            "rift", "valiant", "sentinel", "mirage", "prometheus", "lumen", "pulse", "primevids", "citadel", "apollo",
            "asiacloud",
        )

        private val serviceLock = Mutex()
        @Volatile private var serviceCache: Pair<Long, List<String>>? = null
    }
}
