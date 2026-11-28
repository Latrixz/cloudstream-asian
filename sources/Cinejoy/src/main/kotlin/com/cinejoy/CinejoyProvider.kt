package com.cinejoy

import com.csasian.shared.CsTmdbBrowse
import com.csasian.shared.CsSearch
import android.util.Log
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
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * cinejoy.pk is a SvelteKit front end over TMDB, with curated lists from `lists2.wing.st/joy`.
 * We read TMDB through the keyless proxy our Movy source uses (the site's own TMDB key stays put).
 *
 * Streams come from `POST api2.wing.st/g`, one call per server, with the request and its answer
 * sealed by the site's crush.wasm (see [CinejoyGate]). The answer is an HLS master:
 *  - Light (lightspeed.st): up to 4K HDR10 HEVC plus 1080/720/360 H.264, fMP4, separate audio.
 *    Playlists and segments answer 404 without `Origin: https://cinejoy.pk`.
 *  - Nebula (nebula.bright67.online): 1080p/720p fMP4, no headers.
 *  - Polaris (TS behind a fake PNG header) is left out; Sirius answers 500 for everything.
 * Subtitles come from `subs2.wing.st/subtitles` (SRT, many languages) plus Light's own VTT.
 */
class CinejoyProvider : MainAPI() {
    override var mainUrl = "https://cinejoy.pk"
    override var name = "Cinejoy CS"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper = ObjectMapper()
    private val siteHeaders
        get() = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/", "Origin" to mainUrl)

    // Category browsing (shared/CsTmdbBrowse.kt): TMDB discover through the same TMDB route as the rows,
    // shown as our own cards (so details and links still go through this source).
    private val browse = CsTmdbBrowse.create(this, name, get = { tmdb(it) }) { results, kind -> toResults(results, kind) }

    override val mainPage = browse.mainPage(mainPageOf(
        "trending/movie/week" to "Trending Movies",
        "trending/tv/week" to "Trending TV Shows",
        "movie/popular" to "Popular Movies",
        "tv/popular" to "Popular TV Shows",
        "movie/top_rated" to "Top Rated Movies",
        "tv/top_rated" to "Top Rated TV Shows",
        "${LIST_PREFIX}mindfuck-movies" to "Mindfuck Movies",
        "${LIST_PREFIX}oscar-nominees-best-picture" to "Oscar Nominees for Best Picture",
        "${LIST_PREFIX}based-on-a-true-story" to "Based on a True Story",
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
        if (path.startsWith(LIST_PREFIX)) return listPage(path.removePrefix(LIST_PREFIX), page, request.name)
        val type = if (path.contains("tv")) "tv" else "movie"
        val json = tmdb("$path?page=$page")
        val items = toResults(json["results"], type)
        Log.i(TAG, "row '${request.name}' page $page: ${items.size} titles")
        return newHomePageResponse(request.name, items, hasNextPage(json, page))
    }

    /** A curated `lists2.wing.st/joy/<slug>` list, 25 per page. */
    private suspend fun listPage(slug: String, page: Int, rowName: String): HomePageResponse {
        val offset = (page - 1) * LIST_PAGE
        val json = mapper.readTree(
            app.get("$LISTS/$slug?limit=$LIST_PAGE&offset=$offset", headers = siteHeaders, timeout = 20L).text,
        )
        val items = json["items"]?.mapNotNull { item ->
            val type = when (item.text("type")) {
                "movie" -> "movie"
                "show", "tv", "series" -> "tv"
                else -> return@mapNotNull null
            }
            val id = item["ids"]?.text("tmdb")?.takeIf { it.all(Char::isDigit) } ?: return@mapNotNull null
            val title = item.text("title") ?: return@mapNotNull null
            val poster = item.text("poster")?.replace("/t/p/w200/", "/t/p/w342/")
            val yearValue = item["year"]?.asInt()?.takeIf { it > 0 }
            if (type == "movie") {
                newMovieSearchResponse(title, "$mainUrl/movie/$id", TvType.Movie) { posterUrl = poster; year = yearValue }
            } else {
                newTvSeriesSearchResponse(title, "$mainUrl/tv/$id", TvType.TvSeries) { posterUrl = poster; year = yearValue }
            }
        }.orEmpty().distinctBy { it.url }
        val total = json["pagination"]?.get("total")?.asInt() ?: 0
        Log.i(TAG, "list '$slug' page $page: ${items.size} titles of $total")
        return newHomePageResponse(rowName, items, offset + LIST_PAGE < total)
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
        return CsBackup.loadLinks(this, data, subtitleCallback, callback) { callback ->
            loadOwnLinks(data, subtitleCallback, callback)
        }
    }

    private suspend fun loadPage(url: String): LoadResponse {
        val m = TITLE_REGEX.find(url) ?: throw ErrorLoadingException("Invalid Cinejoy URL: $url")
        val type = m.groupValues[1]
        val id = m.groupValues[2]
        val d = tmdb("$type/$id?append_to_response=credits,recommendations,external_ids,videos")
        if (d["id"] == null) throw ErrorLoadingException("Title not found")
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
            ?.filter { it.text("site") == "YouTube" && it.text("key") != null }
            ?.sortedByDescending { if (it.text("type") == "Trailer") 1 else 0 }
            ?.firstOrNull()?.text("key")?.let { "https://www.youtube.com/watch?v=$it" }
        val recs = toResults(d["recommendations"]?.get("results"), type)
        val imdb = d.text("imdb_id") ?: d["external_ids"]?.text("imdb_id")

        if (type == "movie") {
            return newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/watch/movie/$id") {
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

    /** Every regular season, aired episodes only (season 0 "Specials" is skipped). */
    private suspend fun loadEpisodes(id: String, show: JsonNode): List<Episode> {
        val seasonNumbers = show["seasons"]?.mapNotNull { it["season_number"]?.asInt() }
            ?.filter { it > 0 }?.distinct()?.sorted().orEmpty()
        val now = today()
        val seasons = seasonNumbers.amap { s -> s to runCatching { tmdb("tv/$id/season/$s") }.getOrNull() }
        return seasons.sortedBy { it.first }.flatMap { (seasonNumber, json) ->
            json?.get("episodes")?.mapNotNull { ep ->
                val number = ep["episode_number"]?.asInt()?.takeIf { it > 0 } ?: return@mapNotNull null
                val airDate = ep.text("air_date")
                if (airDate == null || airDate > now) return@mapNotNull null
                newEpisode("$mainUrl/watch/tv/$id/$seasonNumber/$number") {
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

    private data class Title(
        val type: String, val id: String, val season: String?, val episode: String?,
        val imdb: String, val year: String, val title: String,
    )

    private suspend fun loadOwnLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = coroutineScope {
        // data is .../watch/movie/{id} or .../watch/tv/{id}/{season}/{episode} (the site's own routes).
        val m = WATCH_REGEX.find(data) ?: return@coroutineScope false
        val type = m.groupValues[1]
        val id = m.groupValues[2]
        val season = m.groupValues[3].takeIf { it.isNotEmpty() }
        val episode = m.groupValues[4].takeIf { it.isNotEmpty() }
        if (type == "tv" && (season == null || episode == null)) return@coroutineScope false

        val subs = async {
            runCatching { loadSubtitles(type, id, season, episode, subtitleCallback) }
                .onFailure { Log.i(TAG, "subtitles $type/$id failed: $it") }
        }
        // The gate wants the title, year and IMDb id alongside the TMDB id, as the site sends them.
        val d = runCatching { tmdb("$type/$id?append_to_response=external_ids") }.getOrNull()
        if (d?.get("id") == null) {
            Log.i(TAG, "TMDB $type/$id unavailable")
            subs.await()
            return@coroutineScope false
        }
        val key = Title(
            type, id, season, episode,
            imdb = d.text("imdb_id") ?: d["external_ids"]?.text("imdb_id") ?: "",
            year = (d.text("release_date") ?: d.text("first_air_date"))?.take(4) ?: "",
            title = d.text("title") ?: d.text("name") ?: "",
        )

        val links = SERVERS.map { server ->
            async {
                runCatching { serverLink(server, key, subtitleCallback) }
                    .onFailure { Log.i(TAG, "${server.name} $type/$id failed: $it") }
                    .getOrNull()
            }
        }.awaitAll().filterNotNull()
        subs.await()
        links.forEach(callback)
        Log.i(TAG, "links $type/$id s=$season e=$episode: ${links.map { it.name }}")
        links.isNotEmpty()
    }

    private class Server(val name: String, val headers: Map<String, String>)

    /** One server: `/g` -> HLS master, checked and labelled by its best variant. */
    private suspend fun serverLink(server: Server, key: Title, subtitleCallback: (SubtitleFile) -> Unit): ExtractorLink? {
        val answer = gate(server.name, key) ?: return null
        val stream = answer["data"]?.get("stream")?.firstOrNull { it.text("playlist") != null }
        if (stream == null) {
            Log.i(TAG, "${server.name} ${key.type}/${key.id}: no stream (${answer["data"]?.text("error") ?: answer.text("status")})")
            return null
        }
        val playlist = stream.text("playlist")!!
        val master = app.get(playlist, headers = server.headers + ("User-Agent" to USER_AGENT), timeout = 15L)
        if (!master.isSuccessful || !master.text.trimStart().startsWith("#EXTM3U")) {
            Log.i(TAG, "${server.name} master ${master.code}: $playlist")
            return null
        }
        val heights = RESOLUTION_REGEX.findAll(master.text).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        val best = heights.maxOrNull()
        val hdr = master.text.contains("VIDEO-RANGE=PQ") || master.text.contains("VIDEO-RANGE=HLG")
        stream["captions"]?.forEach { c ->
            val url = c.text("url") ?: return@forEach
            val language = c.text("language") ?: c.text("id") ?: "en"
            subtitleCallback(newSubtitleFile("${languageName(language)} (${server.name})", url) { headers = server.headers })
        }
        val label = buildString {
            append("$name ${server.name}")
            if (best != null) append(if (best >= 2160) " 4K" else " ${best}p")
            if (hdr) append(" HDR")
        }
        return newExtractorLink(name, label, playlist, ExtractorLinkType.M3U8) {
            referer = "$mainUrl/"
            quality = best ?: Qualities.Unknown.value
            headers = server.headers + ("User-Agent" to USER_AGENT)
        }
    }

    /** Calls the gate for one server; null (with a log line saying why) if it can't be opened. */
    private suspend fun gate(server: String, key: Title): JsonNode? {
        val payload = linkedMapOf<String, String>("tmdb" to key.id)
        if (key.type == "tv") {
            payload["season"] = key.season!!
            payload["episode"] = key.episode!!
        }
        payload["imdb"] = key.imdb
        payload["year"] = key.year
        payload["title"] = key.title
        val path = if (key.type == "tv") "/$server/series" else "/$server/movie"
        val plaintext = mapper.writeValueAsString(mapOf("path" to path, "payload" to payload))
        val sealed = CinejoyGate.seal(plaintext)
        val response = app.post(
            GATE,
            headers = siteHeaders,
            requestBody = sealed.body.toRequestBody("text/plain;charset=UTF-8".toMediaType()),
            timeout = 20L,
        )
        val bytes = response.body.bytes()
        if (!response.isSuccessful) {
            Log.w(TAG, "/g $path HTTP ${response.code}: ${String(bytes).take(200)}")
            return null
        }
        val text = runCatching { sealed.open(bytes) }.getOrElse {
            // Most likely crush.wasm rotated its key or labels: see CinejoyGate.
            Log.w(TAG, "/g $path: can't open the answer (${bytes.size} bytes, $it). Gate key or labels changed? " +
                "Answer starts: ${String(bytes.copyOf(minOf(bytes.size, 120)))}")
            return null
        }
        return mapper.readTree(text)
    }

    /** SRT tracks from subs2.wing.st, a few per language, English first. */
    private suspend fun loadSubtitles(
        type: String, id: String, season: String?, episode: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        var url = "$SUBS/subtitles?type=$type&tmdb=$id"
        if (type == "tv") url += "&season=$season&episode=$episode"
        val json = mapper.readTree(app.get(url, headers = siteHeaders, timeout = 15L).text)
        val tracks = json["subtitles"]?.toList().orEmpty()
        tracks.mapNotNull { t ->
            val file = t.text("url")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            file to (t.text("language") ?: "und")
        }.distinctBy { it.first }
            .groupBy { it.second }
            .entries.sortedBy { if (it.key == "en") 0 else 1 }
            .flatMap { (lang, group) ->
                val language = languageName(lang)
                group.take(PER_LANGUAGE).mapIndexed { i, (file, _) -> file to if (i == 0) language else "$language ${i + 1}" }
            }
            .take(MAX_SUBTITLES)
            .forEach { (file, label) -> subtitleCallback(newSubtitleFile(label, file)) }
        Log.i(TAG, "subtitles $type/$id: ${tracks.size}")
    }

    private fun languageName(code: String): String =
        runCatching { SubtitleHelper.fromTagToEnglishLanguageName(code) }.getOrNull() ?: code

    // ---- TMDB ----

    private suspend fun tmdb(pathAndQuery: String): JsonNode {
        val response = app.get("$TMDB/$pathAndQuery", headers = siteHeaders, timeout = 20L)
        if (!response.isSuccessful && response.code != 404) throw ErrorLoadingException("TMDB error ${response.code}")
        return mapper.readTree(response.text)
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

    private fun hasNextPage(json: JsonNode, page: Int): Boolean {
        val total = json["total_pages"]?.asInt() ?: 0
        return page < minOf(total, 500)
    }

    private fun image(path: String?, size: String): String? =
        path?.let { if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/$size$it" }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun JsonNode.text(field: String): String? =
        this[field]?.takeIf { !it.isNull && !it.isContainerNode }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val TAG = "Cinejoy"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val TMDB = "https://db.wecollege.net/3"
        private const val GATE = "https://api2.wing.st/g"
        private const val SUBS = "https://subs2.wing.st"
        private const val LISTS = "https://lists2.wing.st/joy"
        private const val LIST_PREFIX = "list:"
        private const val LIST_PAGE = 25
        private const val MAX_SUBTITLES = 120
        private const val PER_LANGUAGE = 3

        /** Servers in our ranking order (links are emitted in this order); Light needs the site's Origin. */
        private val SERVERS = listOf(
            Server("Light", mapOf("Origin" to "https://cinejoy.pk", "Referer" to "https://cinejoy.pk/")),
            Server("Nebula", emptyMap()),
        )

        private val TITLE_REGEX = Regex("""/(movie|tv)/(\d+)""")
        private val WATCH_REGEX = Regex("""/watch/(movie|tv)/(\d+)(?:/(\d+)/(\d+))?""")
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=\d+x(\d+)""")
    }
}
