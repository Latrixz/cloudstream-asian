package com.movy

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
 * movy.sx is a Next.js front end over a keyless TMDB proxy (db.wecollege.net/3/...) for the
 * catalogue and its own stream API (api.wecollege.net). Each stream "provider" is a city-named
 * route (`/{provider}/sources`) whose answer is encrypted with a short-lived seed (`/seed`);
 * see [MovyCipher]. The decrypted JSON lists HLS URLs that need the site as Referer.
 */
class MovyProvider : MainAPI() {
    override var mainUrl = "https://www.movy.sx"
    override var name = "Movy"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper = ObjectMapper()
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val siteHeaders
        get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/", "Origin" to mainUrl)

    // Category browsing (shared/CsTmdbBrowse.kt): TMDB discover through the same TMDB route as the rows,
    // shown as our own cards (so details and links still go through this source).
    private val browse = CsTmdbBrowse.create(this, name, get = { tmdb(it) }) { results, kind -> toResults(results, kind) }

    // The rows of the site's home page (same TMDB queries, so every row pages).
    override val mainPage = browse.mainPage(mainPageOf(
        "trending/all/week" to "Trending",
        "movie/popular" to "Popular Movies",
        "tv/popular" to "Popular TV Shows",
        "movie/top_rated" to "Top Rated Movies",
        "tv/top_rated" to "Top Rated TV Shows",
        "discover/tv?with_networks=213" to "Netflix",
        "discover/tv?with_networks=1024" to "Prime Video",
        "discover/tv?with_networks=49" to "HBO Max",
        "discover/tv?with_networks=2739" to "Disney+",
        "discover/tv?with_networks=2552" to "Apple TV+",
        "discover/tv?with_networks=4330" to "Paramount+",
        "discover/tv?with_networks=453" to "Hulu",
    ))

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        browse.rowPage(request) ?: com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    // Categories SkyStream Dev shows itself (shared/CsCategories.kt); the engine finds these by name.
    fun csCategories(): String = browse.categoriesJson()

    fun csCategoryPage(categoryId: String, filtersJson: String, query: String, page: Int): SearchResponseList =
        browse.categoryPage(categoryId, filtersJson, query, page)

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val defaultType = when {
            path.startsWith("movie") -> "movie"
            path.startsWith("tv") || path.startsWith("discover/tv") -> "tv"
            else -> null
        }
        val sep = if (path.contains("?")) "&" else "?"
        val json = tmdb("$path${sep}page=$page")
        return newHomePageResponse(request.name, toResults(json["results"], defaultType), hasNextPage(json, page))
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
        val json = tmdb("search/multi?query=${enc(query.trim())}&page=$page&include_adult=false")
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

    private suspend fun details(type: String, id: String): JsonNode {
        val d = tmdb("$type/$id?append_to_response=credits,recommendations,similar,external_ids,videos")
        if (d["id"] == null) throw ErrorLoadingException("Title not found")
        return d
    }

    private suspend fun loadPage(url: String): LoadResponse {
        val m = TITLE_REGEX.find(url) ?: throw ErrorLoadingException("Invalid Movy URL: $url")
        val type = m.groupValues[1]
        val id = m.groupValues[2]
        val d = details(type, id)
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
        val trailer = d["videos"]?.get("results")
            ?.firstOrNull { it.text("site") == "YouTube" && it.text("type") == "Trailer" }
            ?.text("key")?.let { "https://www.youtube.com/watch?v=$it" }
        val recs = (toResults(d["recommendations"]?.get("results"), type) +
            toResults(d["similar"]?.get("results"), type)).distinctBy { it.url }
        val imdb = d.text("imdb_id") ?: d["external_ids"]?.text("imdb_id")

        if (type == "movie") {
            return newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/movie/$id") {
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
                addImdbId(imdb)
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
            duration = d["episode_run_time"]?.firstOrNull()?.asInt()?.takeIf { it > 0 }
            recommendations = recs
            addTMDbId(id)
            addImdbId(imdb)
            addTrailer(trailer)
        }
    }

    /** Every regular season (specials are skipped). */
    private suspend fun loadEpisodes(id: String, show: JsonNode): List<Episode> {
        val seasonNumbers = show["seasons"]?.mapNotNull { it["season_number"]?.asInt() }
            ?.filter { it > 0 }?.distinct()?.sorted().orEmpty()
        val now = today()
        val seasons = seasonNumbers.amap { s ->
            s to runCatching { tmdb("tv/$id/season/$s") }.getOrNull()
        }
        return seasons.sortedBy { it.first }.flatMap { (seasonNumber, json) ->
            json?.get("episodes")?.mapNotNull { ep ->
                val number = ep["episode_number"]?.asInt()?.takeIf { it > 0 } ?: return@mapNotNull null
                val airDate = ep.text("air_date")
                // Unaired episodes have nothing to play.
                if (airDate == null || airDate > now) return@mapNotNull null
                newEpisode("$mainUrl/tv/$id?s=$seasonNumber&e=$number") {
                    name = ep.text("name")
                    season = seasonNumber
                    episode = number
                    description = ep.text("overview")
                    posterUrl = image(ep.text("still_path"), "w500")
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
        // data is .../movie/{id} or .../tv/{id}?s={season}&e={episode}.
        val m = TITLE_REGEX.find(data) ?: return false
        val type = m.groupValues[1]
        val id = m.groupValues[2]
        val season = Regex("""[?&]s=(\d+)""").find(data)?.groupValues?.get(1) ?: "1"
        val episode = Regex("""[?&]e=(\d+)""").find(data)?.groupValues?.get(1) ?: "1"
        val d = runCatching { tmdb("$type/$id?append_to_response=external_ids") }.getOrNull()
            ?: return false
        val title = d.text("title") ?: d.text("name") ?: return false
        val params = linkedMapOf(
            // The site URL-encodes the title itself before the query string encodes it again.
            "title" to enc(title),
            "mediaType" to type,
            "year" to ((d.text("release_date") ?: d.text("first_air_date"))?.take(4) ?: ""),
            "totalSeasons" to if (type == "tv") (d["number_of_seasons"]?.asInt() ?: 1).toString() else "0",
            "episodeId" to if (type == "tv") episode else "1",
            "seasonId" to if (type == "tv") season else "1",
            "tmdbId" to id,
            "imdbId" to (d.text("imdb_id") ?: d["external_ids"]?.text("imdb_id") ?: ""),
            "enc" to "2",
        )

        val session = SeedSession(id)
        val results = PROVIDERS.amap { provider ->
            provider to runCatching { fetchSources(provider, params, session) }.getOrNull()
        }

        val sources = results.flatMap { (provider, json) ->
            json?.get("sources")?.mapNotNull { s ->
                val url = s.text("url") ?: s.text("file") ?: return@mapNotNull null
                if (!url.startsWith("http")) return@mapNotNull null
                Triple(provider, s.text("quality") ?: "Auto", url)
            }.orEmpty()
        }.distinctBy { it.third }

        // Most hosts want the site as Referer, a few (e.g. vimeos) refuse it: check which works.
        val checked = sources.amap { src -> src to probe(src.third) }
        checked.forEach { (src, result) ->
            val (provider, label, url) = src
            val (useSite, mp4) = result
            callback(
                newExtractorLink(
                    name,
                    "$name $provider $label",
                    url,
                    if (mp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8,
                ) {
                    referer = if (useSite) "$mainUrl/" else ""
                    quality = qualityOf(label) ?: Qualities.Unknown.value
                    headers = if (useSite) mapOf("User-Agent" to userAgent, "Origin" to mainUrl)
                    else mapOf("User-Agent" to userAgent)
                },
            )
        }

        results.flatMap { (_, json) -> json?.get("subtitles")?.toList().orEmpty() }
            .mapNotNull { s ->
                val url = s.text("url") ?: s.text("file") ?: return@mapNotNull null
                url to (s.text("label") ?: s.text("lang") ?: s.text("language") ?: "Unknown")
            }
            .distinctBy { it.first }
            .take(MAX_SUBTITLES)
            .forEach { (url, label) ->
                subtitleCallback(newSubtitleFile(label, url) { headers = siteHeaders })
            }
        return checked.isNotEmpty()
    }

    /**
     * One seed per media id shared by all providers (as the site does); refreshed once when the
     * API answers 401. The seed endpoint is rate limited, so it is not fetched per provider.
     */
    private inner class SeedSession(val mediaId: String) {
        private val lock = Mutex()
        private var seed: String? = null
        private var fetchedAt = 0L

        suspend fun get(stale: String? = null): String? = lock.withLock {
            val current = seed
            val fresh = current != null && current != stale &&
                System.currentTimeMillis() - fetchedAt < SEED_TTL
            if (fresh) return@withLock current
            val r = runCatching {
                mapper.readTree(app.get("$STREAM_API/seed?mediaId=$mediaId", headers = siteHeaders, timeout = 15L).text)
            }.getOrNull()
            val s = r?.text("seed")
            if (s != null) {
                seed = s
                fetchedAt = System.currentTimeMillis()
            }
            s ?: current
        }
    }

    private suspend fun fetchSources(provider: String, params: Map<String, String>, session: SeedSession): JsonNode? {
        var seed = session.get() ?: return null
        repeat(2) { attempt ->
            val query = (params + ("seed" to seed)).entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
            val r = app.get("$STREAM_API/$provider/sources?$query", headers = siteHeaders, timeout = 15L)
            if (r.code == 401 && attempt == 0) {
                seed = session.get(stale = seed) ?: return null
                return@repeat
            }
            if (!r.isSuccessful) return null
            var body = r.text.trim()
            if (body.startsWith("\"")) body = mapper.readTree(body).asText()
            val plain = MovyCipher.decrypt(body, seed, params["tmdbId"]!!.toLong())
            return mapper.readTree(plain)
        }
        return null
    }

    /**
     * Returns (send the site headers, is a plain video file). The site headers are kept unless
     * the URL works only without them; the type comes from the first bytes of the answer.
     */
    private suspend fun probe(url: String): Pair<Boolean, Boolean> {
        val guessMp4 = url.substringBefore('?').let { it.endsWith(".mp4") || it.contains("/mp4/") }
        suspend fun head(headers: Map<String, String>): String? = runCatching {
            val r = app.get(url, headers = headers + ("Range" to "bytes=0-511"), timeout = 10L)
            if (r.code !in 200..299) return@runCatching null
            r.body.byteStream().use { String(readHead(it, 16), Charsets.ISO_8859_1) }
        }.getOrNull()
        fun isMp4(start: String?) = if (start == null) guessMp4 else !start.trimStart().startsWith("#EXTM3U")
        val withSite = head(siteHeaders)
        if (withSite != null) return true to isMp4(withSite)
        val bare = head(mapOf("User-Agent" to userAgent)) ?: return true to guessMp4
        return false to isMp4(bare)
    }

    /** Reads up to [size] bytes. */
    private fun readHead(stream: java.io.InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = stream.read(buffer, read, size - read)
            if (n < 0) break
            read += n
        }
        return buffer.copyOf(read)
    }

    private fun qualityOf(label: String?): Int? {
        val l = label?.lowercase() ?: return null
        return when {
            l.contains("4k") || l.contains("2160") -> Qualities.P2160.value
            else -> Regex("""(\d{3,4})p""").find(l)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    // ---- TMDB proxy ----

    private suspend fun tmdb(pathAndQuery: String): JsonNode {
        val response = app.get("$TMDB/$pathAndQuery", headers = siteHeaders, timeout = 20L)
        if (!response.isSuccessful && response.code != 404) throw ErrorLoadingException("Movy API error ${response.code}")
        return mapper.readTree(response.text)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun image(path: String?, size: String): String? =
        path?.let { if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/$size$it" }

    private fun hasNextPage(json: JsonNode, page: Int): Boolean {
        val total = json["total_pages"]?.asInt() ?: 0
        return page < minOf(total, 500)
    }

    private fun toResults(results: JsonNode?, defaultType: String?): List<SearchResponse> {
        if (results == null || !results.isArray) return emptyList()
        return results.mapNotNull { item ->
            val type = item.text("media_type") ?: defaultType ?: return@mapNotNull null
            if (type != "movie" && type != "tv") return@mapNotNull null
            val id = item.text("id")?.takeIf { it.all(Char::isDigit) } ?: return@mapNotNull null
            val title = item.text("title") ?: item.text("name") ?: return@mapNotNull null
            val url = "$mainUrl/$type/$id"
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

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun JsonNode.text(field: String): String? =
        this[field]?.takeIf { !it.isNull && !it.isContainerNode }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val TMDB = "https://db.wecollege.net/3"
        private const val STREAM_API = "https://api.wecollege.net"
        private const val SEED_TTL = 25_000L
        private const val MAX_SUBTITLES = 60

        // Stream routes from the site's bundle (dallas currently answers 404 and is left out).
        private val PROVIDERS = listOf(
            "miami", "seattle", "phoenix", "chicago", "reno", "nashville", "atlanta", "vegas", "boise",
            "austin", "berlin", "cancun", "delhi", "munich", "oakland", "paris", "portland",
        )

        private val TITLE_REGEX = Regex("""/(movie|tv)/(\d+)""")
    }
}
