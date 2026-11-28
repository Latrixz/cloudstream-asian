package com.rivo

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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * rivo.st is a Next.js front end over its own TMDB wrapper (`/api/media/{type}/{list}`,
 * `/api/search`, `/api/details/{type}/{id}`, `/api/tv/{id}/season/{n}`). Its title pages sit behind
 * a Cloudflare challenge, so only the JSON API is used. Playback comes from the player backend
 * at f.rivo.st (`/api/source/...?server=N`), which returns HLS playlists already wrapped in the
 * site's own proxies plus OpenSubtitles SRTs.
 */
class RivoProvider : MainAPI() {
    override var mainUrl = "https://rivo.st"
    override var name = "Rivo"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper = ObjectMapper()
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    private val siteHeaders
        get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
    private val playerHeaders
        get() = mapOf("User-Agent" to userAgent, "Referer" to "$PLAYER/", "Origin" to PLAYER)

    // Category browsing (shared/CsTmdbBrowse.kt): TMDB discover through the keyless TMDB proxy (the site's
    // API only offers fixed lists), shown as our own cards, so details and links still go through the site.
    private val browse = CsTmdbBrowse.create(this, name) { results, kind -> toResults(results, kind) }

    // The lists behind the site's Movies / Shows tabs.
    override val mainPage = browse.mainPage(mainPageOf(
        "movie/trending" to "Trending Movies",
        "movie/popular" to "Popular Movies",
        "movie/now_playing" to "Now Playing",
        "movie/top_rated" to "Top Rated Movies",
        "tv/trending" to "Trending Shows",
        "tv/popular" to "Popular Shows",
        "tv/on_the_air" to "On The Air",
        "tv/top_rated" to "Top Rated Shows",
    ))

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        browse.rowPage(request) ?: com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    // Categories SkyStream Dev shows itself (shared/CsCategories.kt); the engine finds these by name.
    fun csCategories(): String = browse.categoriesJson()

    fun csCategoryPage(categoryId: String, filtersJson: String, query: String, page: Int): SearchResponseList =
        browse.categoryPage(categoryId, filtersJson, query, page)

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        val type = request.data.substringBefore("/")
        val json = api("media/${request.data}", mapOf("page" to page.toString()))
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
        val params = mapOf("q" to query.trim(), "type" to "multi", "page" to page.toString())
        // The search backend sometimes answers 502; one retry usually gets through.
        val json = runCatching { api("search", params) }.getOrNull() ?: api("search", params)
        return toResults(json["media"] ?: json["results"], null) to hasNextPage(json, page)
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
        return CsBackup.loadLinks(this, data, subtitleCallback, callback) { callback ->
            loadOwnLinks(data, isCasting, subtitleCallback, callback)
        }
    }

    private suspend fun loadPage(url: String): LoadResponse {
        val m = TITLE_REGEX.find(url) ?: throw ErrorLoadingException("Invalid Rivo URL: $url")
        val type = m.groupValues[1]
        val id = m.groupValues[2]
        val d = api("details/$type/$id", emptyMap())
        val title = d.text("title") ?: d.text("name") ?: throw ErrorLoadingException("Title not found")
        val poster = image(d.text("poster"), "w500")
        val backdrop = image(d.text("backdrop"), "w1280")
        val plotText = d.text("overview")
        val yearValue = d.text("date")?.take(4)?.toIntOrNull()
        val scoreValue = d["rating"]?.asDouble()?.takeIf { it > 0 }?.let { Score.from10(it) }
        val tagList = d["genres"]?.mapNotNull { g -> if (g.isTextual) g.asText() else g.text("name") }.orEmpty()
        val actorList = d["cast"]?.take(20)?.mapNotNull { c ->
            val actorName = c.text("name") ?: return@mapNotNull null
            ActorData(Actor(actorName, image(c.text("profile"), "w185")), roleString = c.text("role"))
        }.orEmpty()
        val trailer = d["trailer"]?.takeIf { it.text("site") == null || it.text("site") == "YouTube" }
            ?.text("key")?.let { "https://www.youtube.com/watch?v=$it" }
        val recs = (toResults(d["recommendations"], type) + toResults(d["similar"], type)).distinctBy { it.url }
        val imdb = d.text("imdbId") ?: d["externalIds"]?.text("imdb_id")
        val runtime = d["runtime"]?.asInt()?.takeIf { it > 0 }

        if (type == "movie") {
            return newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/watch/movie/$id") {
                posterUrl = poster
                backgroundPosterUrl = backdrop
                plot = plotText
                year = yearValue
                score = scoreValue
                tags = tagList
                actors = actorList
                duration = runtime
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
            duration = runtime
            recommendations = recs
            addTMDbId(id)
            addImdbId(imdb)
            addTrailer(trailer)
        }
    }

    /** Every regular season (specials are skipped: the player servers do not serve season 0). */
    private suspend fun loadEpisodes(id: String, show: JsonNode): List<Episode> {
        val seasonNumbers = show["seasons"]?.mapNotNull { it["season_number"]?.asInt() }
            ?.filter { it > 0 }?.distinct()?.sorted().orEmpty()
        val now = today()
        val seasons = seasonNumbers.amap { s ->
            s to runCatching { api("tv/$id/season/$s", emptyMap()) }.getOrNull()
        }
        return seasons.sortedBy { it.first }.flatMap { (seasonNumber, json) ->
            json?.get("episodes")?.mapNotNull { ep ->
                // Season JSON gives every number as a string.
                val number = ep.text("episode_number")?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                val airDate = ep.text("air_date")
                // Unaired episodes have nothing to play.
                if (airDate != null && airDate > now) return@mapNotNull null
                newEpisode("$mainUrl/watch/tv/$id/$seasonNumber/$number") {
                    name = ep.text("name")
                    season = seasonNumber
                    episode = number
                    description = ep.text("overview")
                    posterUrl = image(ep.text("still_path"), "w300")
                    runTime = ep.text("runtime")?.toIntOrNull()?.takeIf { it > 0 }
                    score = ep.text("vote_average")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { Score.from10(it) }
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
        // data is .../watch/movie/{id} or .../watch/tv/{id}/{season}/{episode}.
        val match = PLAY_REGEX.find(data) ?: return false
        val (type, id, season, episode) = match.destructured
        val base = if (type == "tv") "$PLAYER/api/source/tv/$id?s=$season&e=$episode" else "$PLAYER/api/source/movie/$id"

        val responses = servers().amap { (serverId, label) ->
            runCatching {
                val r = app.get("$base${if ('?' in base) "&" else "?"}server=$serverId", headers = playerHeaders, timeout = 15L)
                if (r.isSuccessful) label to mapper.readTree(r.text) else null
            }.getOrNull()
        }.filterNotNull()

        var found = false
        responses.forEach { (label, json) ->
            val server = json.text("server") ?: label
            json["sources"]?.forEach { s ->
                val url = s.text("url") ?: return@forEach
                val mp4 = s.text("kind")?.lowercase() == "mp4" || url.substringBefore('?').endsWith(".mp4")
                found = true
                callback(
                    newExtractorLink(name, "$name $server", url, if (mp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8) {
                        referer = "$PLAYER/"
                        quality = qualityOf(s.text("quality")) ?: Qualities.Unknown.value
                        headers = playerHeaders
                    },
                )
            }
        }

        // Every server returns the same OpenSubtitles list (SRT).
        responses.flatMap { (_, json) -> json["subtitles"]?.toList().orEmpty() }
            .mapNotNull { s -> s.text("url")?.let { it to (s.text("label") ?: s.text("lang") ?: "Unknown") } }
            .distinctBy { it.first }
            .take(MAX_SUBTITLES)
            .forEach { (url, label) -> subtitleCallback(newSubtitleFile(label, url)) }
        return found
    }

    /** Server ids and labels from the player, minus the ones that cannot be played. */
    private suspend fun servers(): List<Pair<String, String>> {
        val list = runCatching {
            mapper.readTree(app.get("$PLAYER/api/servers?v=7", headers = playerHeaders, timeout = 8L).text)["servers"]
                ?.mapNotNull { s -> s.text("id")?.let { it to (s.text("label") ?: it) } }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: FALLBACK_SERVERS
        // Oracle's segments are MPEG-TS hidden behind a fake PNG header, which the player rejects.
        return list.filter { (id, label) -> id != "1" && !label.equals("Oracle", ignoreCase = true) }
    }

    private fun qualityOf(label: String?): Int? {
        val l = label?.lowercase() ?: return null
        return when {
            l.contains("4k") || l.contains("2160") -> Qualities.P2160.value
            else -> Regex("""(\d{3,4})p""").find(l)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    // ---- API ----

    private suspend fun api(path: String, params: Map<String, String>, timeout: Long = 20L): JsonNode {
        val query = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val response = app.get("$mainUrl/api/$path${if (query.isEmpty()) "" else "?$query"}", headers = siteHeaders, timeout = timeout)
        if (!response.isSuccessful) throw ErrorLoadingException("Rivo API error ${response.code}")
        return mapper.readTree(response.text)
    }

    private fun hasNextPage(json: JsonNode, page: Int): Boolean {
        val total = json["total_pages"]?.asInt() ?: json["totalPages"]?.asInt() ?: 0
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
            // The site's own lists use poster/date/rating; plain TMDB results (categories) poster_path etc.
            val poster = image(item.text("poster") ?: item.text("poster_path"), "w342")
            val yearValue = (item.text("date") ?: item.text("release_date") ?: item.text("first_air_date"))?.take(4)?.toIntOrNull()
            val scoreValue = (item["rating"] ?: item["vote_average"])?.asDouble()?.takeIf { it > 0 }?.let { Score.from10(it) }
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

    /** The API gives bare TMDB image paths ("/abc.jpg"). */
    private fun image(path: String?, size: String): String? = when {
        path.isNullOrBlank() -> null
        path.startsWith("http") -> path
        else -> "https://image.tmdb.org/t/p/$size${if (path.startsWith("/")) path else "/$path"}"
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun JsonNode.text(field: String): String? =
        this[field]?.takeIf { !it.isNull && !it.isContainerNode }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val PLAYER = "https://f.rivo.st"
        private const val MAX_SUBTITLES = 60

        private val FALLBACK_SERVERS = listOf("2" to "Phoenix", "3" to "Kraken", "4" to "Titan")

        private val TITLE_REGEX = Regex("""/(movie|tv)/(\d+)""")
        private val PLAY_REGEX = Regex("""/watch/(movie|tv)/(\d+)(?:/(\d+)/(\d+))?""")
    }
}
