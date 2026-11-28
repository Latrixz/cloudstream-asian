package com.zxcstream

import android.util.Log
import com.csasian.shared.CsBackup
import com.csasian.shared.CsSearch
import com.csasian.shared.CsTmdbBrowse
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * zxcstream.com: a TMDB front end. Its catalogue is a same-origin, keyless TMDB proxy under
 * `/database/...` (trending, discover, search, details, season), which we use as is.
 *
 * The site's own player embeds third-party players; we call the ones that work without a browser
 * (no WebView anywhere, so the same code plays in CloudStream and in SkyStream on the desktop):
 *  - vidstuck.cc ("Main Server", see [Vidstuck]):
 *      Berkas    rousav HLS 1080/720/480, multi-audio, subtitles inside the playlist.
 *      Orion     MovieBox MP4s (360p/480p in reality) plus one MP4 per dubbed language.
 *      Andromeda MovieBox DASH (HEVC).
 *      plus vidstuck's own subtitle list (MovieBox SRTs).
 *  - cinezo ("Jennifer"): `punkflix.net/{movie|tv}/...json`, 4K H.264 HLS on many movies, 1080p episodes,
 *    VTT subtitles. Needs the cinezo player as Referer.
 *  - vidbolt.xyz: Luna (AES-128 HLS) and Vega (TS) backups; its Orion (rousav, the same files as
 *    Berkas) only when Berkas had nothing. Needs a browser UA and the vidbolt Referer.
 * Not used: vaplayer / vidfast (captcha and devtools gates), vidstuck Atlas/Milkyway/Ursa (their
 * workers fail from outside), zxcstream.cc (same upstreams), vidbolt Callisto and cinezo Zendaya
 * (the same MovieBox files as Orion/Andromeda).
 *
 * Every server's segments are real TS/fMP4 (punkflix ".jpg" and vidbolt ".png"/"image/jpeg" files
 * carry no fake image header), so no video interceptor is needed.
 *
 * Vivamax (TMDB company 149142) titles are kept out: discover rows exclude the company, and other
 * lists check Filipino-language titles against their production companies.
 */
class ZxcstreamProvider : MainAPI() {
    override var mainUrl = "https://zxcstream.com"
    override var name = "ZXCStream CS"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.Anime)

    private val mapper = ObjectMapper()
    private val vidstuck by lazy { Vidstuck(mapper, USER_AGENT) }

    private val siteHeaders
        get() = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/", "Accept" to "application/json")

    // Category browsing (shared/CsTmdbBrowse.kt): TMDB discover through the site's own proxy, our own cards;
    // the Vivamax company is excluded there too.
    private val browse = CsTmdbBrowse.create(this, name, extraParams = "without_companies=$VIVAMAX", get = { db(it) }) { results, kind ->
        toResults(results, kind)
    }

    // The site's home rows (its /swipers.json, plus its network/studio rows). The Vivamax studio row is
    // left out on purpose, and every discover row excludes that company. On CloudStream the
    // "分类 Categories" row comes first.
    override val mainPage = browse.mainPage(mainPageOf(
        "trending/movie/week" to "Trending Movies",
        "trending/tv/week" to "Trending TV Shows",
        "discover/movie?sort_by=popularity.desc" to "Popular This Week",
        "discover/tv?sort_by=popularity.desc" to "Popular TV",
        "discover/movie?sort_by=primary_release_date.desc&vote_count.gte=50" to "Recent Movies",
        "discover/tv?sort_by=first_air_date.desc&vote_count.gte=20" to "Recent TV",
        "discover/movie?sort_by=vote_average.desc&vote_count.gte=500" to "Top Rated Movies",
        "discover/movie?sort_by=vote_average.desc&vote_count.gte=1000" to "Highly Rated",
        "discover/movie?with_genres=28&sort_by=popularity.desc" to "Action",
        "discover/movie?with_genres=35&sort_by=popularity.desc" to "Comedy",
        "discover/movie?with_genres=27&sort_by=popularity.desc" to "Horror",
        "discover/movie?with_genres=10749&sort_by=popularity.desc" to "Romance",
        "discover/movie?with_genres=878&sort_by=popularity.desc" to "Sci-Fi",
        "discover/movie?with_genres=53&sort_by=popularity.desc" to "Thriller",
        "discover/movie?with_genres=16&sort_by=popularity.desc" to "Animation",
        "discover/movie?with_genres=99&sort_by=popularity.desc" to "Documentary",
        "discover/tv?with_genres=10759&sort_by=popularity.desc" to "TV Action",
        "discover/tv?with_genres=35&sort_by=popularity.desc" to "TV Comedy",
        "discover/tv?with_genres=18&sort_by=popularity.desc" to "TV Drama",
        "discover/tv?with_networks=213&sort_by=popularity.desc" to "Netflix",
        "discover/tv?with_networks=49&sort_by=popularity.desc" to "HBO",
        "discover/tv?with_networks=2739&sort_by=popularity.desc" to "Disney+",
        "discover/tv?with_networks=2552&sort_by=popularity.desc" to "Apple TV+",
        "discover/movie?with_companies=420&sort_by=popularity.desc" to "Marvel Studios",
        "discover/movie?with_companies=41077&sort_by=popularity.desc" to "A24",
        "discover/movie?with_companies=210099&sort_by=popularity.desc" to "Amazon MGM Studios",
        "discover/movie?with_companies=3172&sort_by=popularity.desc" to "Blumhouse",
        "discover/movie?with_companies=142877&sort_by=popularity.desc" to "Shudder",
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
        val type = if (path.contains("/tv")) "tv" else "movie"
        var query = "$path${if (path.contains("?")) "&" else "?"}page=$page"
        if (path.startsWith("discover/")) query += "&include_adult=false&without_companies=$VIVAMAX"
        val json = db(query)
        val items = toResults(json["results"], type)
        Log.i(TAG, "row '${request.name}' page $page: ${items.size} titles")
        return newHomePageResponse(request.name, items, hasNextPage(json, page))
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
        val json = db("search/multi?query=${enc(query.trim())}&page=$page")
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
            loadOwnLinks(data, isCasting, subtitleCallback, callback)
        }
    }

    // ---- catalogue ----

    private suspend fun loadPage(url: String): LoadResponse {
        val m = TITLE_REGEX.find(url) ?: throw ErrorLoadingException("Invalid ZXCStream URL: $url")
        val type = m.groupValues[1]
        val id = m.groupValues[2]
        val d = db("details/$type/$id?image=1&recommendation=1&cast=1&language=en")
        if (d["id"] == null) throw ErrorLoadingException("Title not found")
        if (isBlocked(d)) throw ErrorLoadingException("Title not available")
        details[DetailsKey(type, id)] = d
        val title = d.text("title") ?: d.text("name") ?: throw ErrorLoadingException("Title not found")
        val poster = image(d.text("poster_path"), "w500")
        val backdrop = image(d.text("backdrop_path"), "w1280")
        val logo = d["images"]?.get("logos")
            ?.sortedByDescending { if (it.text("iso_639_1") == "en") 1 else 0 }
            ?.firstOrNull()?.text("file_path")?.let { image(it, "w500") }
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
        val showType = typeOf(type, d)

        if (type == "movie") {
            return newMovieLoadResponse(title, url, showType, "$mainUrl/watch/movie/$id") {
                posterUrl = poster
                backgroundPosterUrl = backdrop
                logoUrl = logo
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
        return newTvSeriesLoadResponse(title, url, showType, episodes) {
            posterUrl = poster
            backgroundPosterUrl = backdrop
            logoUrl = logo
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
        val seasons = seasonNumbers.amap { s -> s to runCatching { db("season/$id/$s?language=en") }.getOrNull() }
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

    /** Anime: Japanese animation. Asian drama: Korean / Chinese / Thai / Japanese live-action series. */
    private fun typeOf(type: String, item: JsonNode): TvType {
        val genres = (item["genre_ids"]?.mapNotNull { it.asInt() } ?: item["genres"]?.mapNotNull { it["id"]?.asInt() }).orEmpty()
        val language = item.text("original_language")
        val countries = item["origin_country"]?.mapNotNull { it.asText() }.orEmpty()
        val japanese = language == "ja" || "JP" in countries
        if (type == "movie") return if (16 in genres && japanese) TvType.AnimeMovie else TvType.Movie
        return when {
            16 in genres && japanese -> TvType.Anime
            language in ASIAN_LANGUAGES || countries.any { it in ASIAN_COUNTRIES } -> TvType.AsianDrama
            else -> TvType.TvSeries
        }
    }

    private suspend fun toResults(results: JsonNode?, defaultType: String?): List<SearchResponse> {
        if (results == null || !results.isArray) return emptyList()
        val items = results.mapNotNull { item ->
            val type = item.text("media_type") ?: defaultType ?: return@mapNotNull null
            if (type != "movie" && type != "tv") return@mapNotNull null
            if (item["adult"]?.asBoolean() == true || item["softcore"]?.asBoolean() == true) return@mapNotNull null
            if (looksAdult(item)) return@mapNotNull null
            item.text("id")?.takeIf { it.all(Char::isDigit) } ?: return@mapNotNull null
            type to item
        }
        // List items don't carry their studios; Vivamax titles are Filipino, so only those get a details check.
        val safe = items.amap { (type, item) ->
            val suspect = item.text("original_language") == "tl"
            if (suspect && runCatching { isBlocked(detailsOf(type, item.text("id")!!)) }.getOrDefault(false)) null else type to item
        }.filterNotNull()
        return safe.mapNotNull { (type, item) ->
            val id = item.text("id")!!
            val title = item.text("title") ?: item.text("name") ?: return@mapNotNull null
            val url = "$mainUrl/$type/$id"
            val poster = image(item.text("poster_path"), "w342")
            val yearValue = (item.text("release_date") ?: item.text("first_air_date"))?.take(4)?.toIntOrNull()
            val scoreValue = item["vote_average"]?.asDouble()?.takeIf { it > 0 }?.let { Score.from10(it) }
            val tvType = typeOf(type, item)
            if (type == "movie") {
                newMovieSearchResponse(title, url, tvType) {
                    posterUrl = poster
                    year = yearValue
                    score = scoreValue
                }
            } else {
                newTvSeriesSearchResponse(title, url, tvType) {
                    posterUrl = poster
                    year = yearValue
                    score = scoreValue
                }
            }
        }.distinctBy { it.url }
    }

    /** Adult titles and anything from Vivamax (its `softcore` flag is not reliable, so the studio decides). */
    /** TMDB's adult flag misses some titles (e.g. Japanese AV listed as normal films): check the words too. */
    private fun looksAdult(d: JsonNode): Boolean {
        val words = listOfNotNull(d.text("title"), d.text("name"), d.text("original_title"), d.text("original_name"), d.text("overview"))
            .joinToString(" ")
        return ADULT_WORDS.containsMatchIn(words)
    }

    private fun isBlocked(d: JsonNode): Boolean =
        looksAdult(d) || d["adult"]?.asBoolean() == true ||
            d["production_companies"]?.any { it["id"]?.asInt() == VIVAMAX } == true

    private data class DetailsKey(val type: String, val id: String)

    private suspend fun detailsOf(type: String, id: String): JsonNode {
        val key = DetailsKey(type, id)
        details[key]?.let { return it }
        val d = db("details/$type/$id?language=en")
        if (d["id"] != null) details[key] = d
        return d
    }

    // ---- links ----

    private data class Title(val type: String, val id: String, val season: Int?, val episode: Int?)

    private suspend fun loadOwnLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = coroutineScope {
        // data is .../watch/movie/{id} or .../watch/tv/{id}/{season}/{episode}.
        val m = WATCH_REGEX.find(data) ?: return@coroutineScope false
        val key = Title(m.groupValues[1], m.groupValues[2], m.groupValues[3].toIntOrNull(), m.groupValues[4].toIntOrNull())
        if (key.type == "tv" && (key.season == null || key.episode == null)) return@coroutineScope false
        val d = detailsOf(key.type, key.id)
        if (isBlocked(d)) return@coroutineScope false
        val date = d.text("release_date") ?: d.text("first_air_date") ?: ""
        val meta = Vidstuck.Meta(
            type = key.type,
            id = key.id,
            season = key.season,
            episode = key.episode,
            title = d.text("title") ?: d.text("name") ?: "",
            year = date.take(4),
            date = date,
            latestDate = d.text("last_air_date"),
            imdbId = d.text("imdb_id"),
        )

        val started = System.currentTimeMillis()
        var sent = 0
        val send: (ExtractorLink) -> Unit = { link ->
            synchronized(this@ZxcstreamProvider) {
                sent++
                callback(link)
            }
            Log.i(TAG, "link ${link.name} ${link.quality}p after ${System.currentTimeMillis() - started}ms")
        }
        val subUrls = ConcurrentHashMap.newKeySet<String>()
        val sub: suspend (String, String, Map<String, String>) -> Unit = { label, url, headers ->
            if (subUrls.add(url)) subtitleCallback(newSubtitleFile(label, url) { if (headers.isNotEmpty()) this.headers = headers })
        }

        // Three servers at a time, each with its own time limit; links go out as soon as they check out.
        val permits = Semaphore(PARALLEL)
        suspend fun task(label: String, timeout: Long = SERVER_TIMEOUT, block: suspend () -> Unit) {
            permits.withPermit {
                val t = System.currentTimeMillis()
                val done = withTimeoutOrNull(timeout) {
                    runCatching { block() }.onFailure { Log.i(TAG, "$label ${key.id}: failed: $it") }
                }
                if (done == null) Log.i(TAG, "$label ${key.id}: timed out")
                else Log.i(TAG, "$label ${key.id}: ${System.currentTimeMillis() - t}ms")
            }
        }

        val berkasOk = CompletableDeferred<Boolean>()
        val jobs = listOf(
            async {
                var ok = false
                try {
                    task("berkas") { ok = berkas(meta, send) }
                } finally {
                    berkasOk.complete(ok)
                }
            },
            async { task("jennifer") { cinezo(key, send, sub, berkasOk) } },
            async {
                var dubs: List<Dub> = emptyList()
                task("orion") { dubs = orion(meta, null, send) }
                // One more vidstuck call per dubbed language, English first.
                dubs.map { dub -> async { task("orion ${dub.code}", DUB_TIMEOUT) { orion(meta, dub, send) } } }.awaitAll()
            },
            async { task("andromeda") { andromeda(meta, send, berkasOk) } },
            async { task("vidbolt luna") { vidbolt(key, "luna", send) } },
            async { task("vidbolt vega") { vidbolt(key, "vega", send) } },
            async {
                // vidbolt's Orion is the same rousav origin as Berkas: only worth it when Berkas had nothing.
                if (!berkasOk.await()) task("vidbolt orion") { vidbolt(key, "orion", send) }
            },
            async {
                withTimeoutOrNull(SERVER_TIMEOUT) {
                    runCatching {
                        vidstuck.subtitles(meta).take(MAX_SUBTITLES).forEach { (label, url) -> sub(label, url, emptyMap()) }
                    }.onFailure { Log.i(TAG, "vidstuck subtitles ${key.id}: $it") }
                }
            },
        )
        jobs.awaitAll()
        Log.i(TAG, "links ${key}: $sent in ${System.currentTimeMillis() - started}ms")
        sent > 0
    }

    private val vidstuckHeaders
        get() = mapOf("User-Agent" to USER_AGENT, "Referer" to "${Vidstuck.BASE}/", "Origin" to Vidstuck.BASE)

    /** vidstuck Berkas: one rousav HLS master (multi-audio, subtitles inside). */
    private suspend fun berkas(meta: Vidstuck.Meta, send: (ExtractorLink) -> Unit): Boolean {
        val json = vidstuck.server(meta, "berkas") ?: return false
        var ok = false
        json["links"]?.forEach { l ->
            val link = l.text("link")?.takeIf { it.isNotEmpty() } ?: return@forEach
            if (l.text("type") != "hls") return@forEach
            val url = absolute(Vidstuck.BASE, link)
            val quality = hlsQuality(url, vidstuckHeaders) ?: return@forEach
            send(
                newExtractorLink(name, "$name Berkas", url, ExtractorLinkType.M3U8) {
                    referer = "${Vidstuck.BASE}/"
                    this.quality = quality
                    headers = vidstuckHeaders
                },
            )
            ok = true
        }
        return ok
    }

    private class Dub(val code: String, val type: String, val label: String)

    /**
     * vidstuck Orion: MovieBox MP4s. The listed resolutions are inflated (the same file appears as
     * 480/720/1080/2160), so every distinct file is labelled by its real frame size. Without [dub]
     * it also returns the dubbed languages on offer (audio dubs only; "sub" entries are hard-subbed copies).
     */
    private suspend fun orion(meta: Vidstuck.Meta, dub: Dub?, send: (ExtractorLink) -> Unit): List<Dub> {
        val json = vidstuck.server(meta, "orion", dub?.code, dub?.type) ?: return emptyList()
        val dubs = json["dubs"]?.toList().orEmpty()
        val original = dubs.firstOrNull { it["original"]?.asBoolean() == true }
        val label = dub?.label ?: original?.text("lanCode")?.let { "Original audio (${languageName(it)})" } ?: "Original audio"
        val files = json["links"]?.mapNotNull { l ->
            val url = l.text("link")?.takeIf { it.startsWith("http") && l.text("type") == "mp4" } ?: return@mapNotNull null
            url to (l["resolution"]?.asInt() ?: 0)
        }.orEmpty()
            .groupBy({ it.first }, { it.second })
            .map { (url, claimed) -> url to claimed.filter { it > 0 }.minOrNull() }
        // The best file only for dubs; both sizes for the original.
        val probed = files.amap { (url, claimed) -> Triple(url, mp4Quality(url) ?: claimed ?: Qualities.Unknown.value, claimed) }
            .sortedByDescending { it.second }
        (if (dub == null) probed else probed.take(1)).forEach { (url, quality, _) ->
            send(
                newExtractorLink(name, "$name Orion · $label", url, ExtractorLinkType.VIDEO) {
                    this.quality = quality
                    headers = mapOf("User-Agent" to USER_AGENT)
                },
            )
        }
        if (dub != null) return emptyList()
        return dubs.filter { it["original"]?.asBoolean() != true && it.text("type") == "0" }
            .mapNotNull { d ->
                val code = d.text("lanCode") ?: return@mapNotNull null
                Dub(code, "0", "${languageName(code)} dub")
            }
            .distinctBy { it.code }
            .sortedWith(compareBy<Dub> { if (it.code == "en") 0 else 1 }.thenBy { it.label })
    }

    /** vidstuck Andromeda: MovieBox DASH (HEVC), labelled by the MPD's largest video representation. */
    private suspend fun andromeda(meta: Vidstuck.Meta, send: (ExtractorLink) -> Unit, berkasDone: CompletableDeferred<Boolean>) {
        val json = vidstuck.server(meta, "andromeda") ?: return
        json["links"]?.forEach { l ->
            val link = l.text("link")?.takeIf { it.isNotEmpty() && l.text("type") == "dash" } ?: return@forEach
            val url = absolute(Vidstuck.BASE, link)
            val mpd = app.get(url, headers = vidstuckHeaders, timeout = PROBE_TIMEOUT)
            if (!mpd.isSuccessful || !mpd.text.contains("<MPD")) return@forEach
            val heights = Regex("""<Representation[^>]*mimeType="video[^>]*>""").findAll(mpd.text).mapNotNull { r ->
                // `\s` so that bandwidth="..." isn't read as the width.
                val w = Regex("""\swidth="(\d+)"""").find(r.value)?.groupValues?.get(1)?.toIntOrNull()
                val h = Regex("""\sheight="(\d+)"""").find(r.value)?.groupValues?.get(1)?.toIntOrNull()
                qualityOf(w, h).takeIf { it > 0 }
            }.toList()
            val hevc = Regex("""codecs="(hev1|hvc1)""").containsMatchIn(mpd.text)
            // Links of equal quality keep their arrival order, and Berkas is the better 1080p.
            berkasDone.await()
            send(
                newExtractorLink(name, "$name Andromeda${if (hevc) " · HEVC" else ""}", url, ExtractorLinkType.DASH) {
                    referer = "${Vidstuck.BASE}/"
                    quality = heights.maxOrNull() ?: Qualities.Unknown.value
                    headers = vidstuckHeaders
                },
            )
        }
    }

    private val cinezoHeaders
        get() = mapOf("User-Agent" to USER_AGENT, "Referer" to "$CINEZO/", "Origin" to CINEZO)

    /** cinezo "Jennifer": punkflix HLS (4K on many movies) and its VTT subtitles. */
    private suspend fun cinezo(
        key: Title,
        send: (ExtractorLink) -> Unit,
        sub: suspend (String, String, Map<String, String>) -> Unit,
        berkasDone: CompletableDeferred<Boolean>,
    ) {
        val api = if (key.type == "movie") "$PUNKFLIX/movie/${key.id}.json" else "$PUNKFLIX/tv/${key.id}/${key.season}/${key.episode}.json"
        val response = app.get(api, headers = cinezoHeaders + ("Accept" to "application/json"), timeout = 15L)
        if (!response.isSuccessful) throw IllegalStateException("punkflix ${response.code}")
        val json = mapper.readTree(response.text)
        val url = json["stream"]?.text("hls") ?: json["stream"]?.text("original") ?: return
        val quality = hlsQuality(url, cinezoHeaders) ?: return
        // 4K goes out at once (it ranks first anyway); 1080p waits so that Berkas stays ahead of it.
        if (quality < 2160) berkasDone.await()
        send(
            newExtractorLink(name, "$name Jennifer", url, ExtractorLinkType.M3U8) {
                referer = "$CINEZO/"
                this.quality = quality
                headers = cinezoHeaders
            },
        )
        json["subtitles"]?.forEach { s ->
            val file = s.text("url") ?: return@forEach
            val label = languageName(s.text("language") ?: "und") + (s.text("title")?.let { " ($it)" } ?: "")
            sub(label, file, cinezoHeaders)
        }
    }

    private val vidboltHeaders
        get() = mapOf("User-Agent" to USER_AGENT, "Referer" to "$VIDBOLT/", "Origin" to VIDBOLT)

    /** vidbolt.xyz `/api/resolve?prefer=<server>`; it falls back to another server when that one has nothing. */
    private suspend fun vidbolt(key: Title, server: String, send: (ExtractorLink) -> Unit) {
        var query = "type=${key.type}&id=${key.id}&prefer=$server"
        if (key.type == "tv") query += "&season=${key.season}&episode=${key.episode}"
        val response = app.get("$VIDBOLT/api/resolve?$query", headers = vidboltHeaders + ("Accept" to "application/json"), timeout = 15L)
        if (!response.isSuccessful) throw IllegalStateException("resolve ${response.code}")
        val stream = mapper.readTree(response.text)["stream"] ?: return
        val got = stream.text("server")
        if (got != server) {
            Log.i(TAG, "vidbolt asked $server, got $got: skipped")
            return
        }
        val label = server.replaceFirstChar { it.uppercase() }
        stream["sources"]?.forEach { s ->
            val url = s.text("url")?.takeIf { it.startsWith("http") && s.text("kind") == "hls" } ?: return@forEach
            val claimed = s.text("quality")?.let { Regex("""(\d{3,4})p""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            val playlist = runCatching { hlsQuality(url, vidboltHeaders, allowMedia = true) }
                .onFailure { Log.i(TAG, "vidbolt $server playlist: $it") }.getOrNull() ?: return@forEach
            val quality = playlist.takeIf { it > 0 } ?: claimed ?: Qualities.Unknown.value
            val language = s.text("language")?.let { " · $it" } ?: ""
            send(
                newExtractorLink(name, "$name vidbolt $label$language", url, ExtractorLinkType.M3U8) {
                    referer = "$VIDBOLT/"
                    this.quality = quality
                    headers = vidboltHeaders
                },
            )
        }
    }

    // ---- probing ----

    /**
     * Fetches an HLS playlist with the link's own headers. Returns the best variant's quality class
     * from its RESOLUTION, 0 for a media playlist when [allowMedia], or null when it isn't a playlist.
     */
    private suspend fun hlsQuality(url: String, headers: Map<String, String>, allowMedia: Boolean = false): Int? {
        val r = app.get(url, headers = headers, timeout = PROBE_TIMEOUT)
        val body = r.text
        if (!r.isSuccessful || !body.trimStart().startsWith("#EXTM3U")) {
            Log.i(TAG, "playlist ${r.code} not usable: ${url.take(90)}")
            return null
        }
        val variants = RESOLUTION_REGEX.findAll(body).map { qualityOf(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.toList()
        if (variants.isEmpty()) return if (allowMedia || body.contains("#EXTINF")) 0 else null
        return variants.max()
    }

    /** Real frame size of an MP4 (moov comes first on MovieBox files): the first video `tkhd` box. */
    private suspend fun mp4Quality(url: String): Int? = runCatching {
        val r = app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Range" to "bytes=0-131071"), timeout = PROBE_TIMEOUT)
        if (r.code !in 200..299) return@runCatching null
        val b = r.body.byteStream().use { readUpTo(it, 131072) }
        var i = 0
        while (true) {
            i = indexOf(b, TKHD, i)
            if (i < 0) return@runCatching null
            val o = i + 4
            val version = b.getOrNull(o)?.toInt() ?: return@runCatching null
            val at = o + if (version == 1) 88 else 76
            if (at + 8 > b.size) return@runCatching null
            val w = u32(b, at) ushr 16
            val h = u32(b, at + 4) ushr 16
            if (w > 0 && h > 0) return@runCatching qualityOf(w.toInt(), h.toInt())
            i = o
        }
        null
    }.getOrNull()

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xff) shl 24) or ((b[at + 1].toLong() and 0xff) shl 16) or
            ((b[at + 2].toLong() and 0xff) shl 8) or (b[at + 3].toLong() and 0xff)

    private fun indexOf(b: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..b.size - needle.size) {
            for (j in needle.indices) if (b[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun readUpTo(input: java.io.InputStream, max: Int): ByteArray {
        val buf = ByteArray(max)
        var n = 0
        while (n < max) {
            val read = input.read(buf, n, max - n)
            if (read < 0) break
            n += read
        }
        return buf.copyOf(n)
    }

    /** Quality class from a frame size; wide (cropped) films go by width, e.g. 1920x800 -> 1080, 3840x1600 -> 2160. */
    private fun qualityOf(w: Int?, h: Int?): Int {
        val byWidth = when {
            w == null -> 0
            w >= 3400 -> 2160
            w >= 2400 -> 1440
            w >= 1800 -> 1080
            w >= 1200 -> 720
            w >= 800 -> 480
            w >= 600 -> 360
            else -> 0
        }
        return maxOf(byWidth, h ?: 0)
    }

    private fun languageName(code: String): String = when (code.lowercase()) {
        "esla" -> "Latin American Spanish"
        "ptbr" -> "Brazilian Portuguese"
        "ukr" -> "Ukrainian"
        "und" -> "Unknown"
        else -> Locale(code).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() && !it.equals(code, true) } ?: code
    }

    // ---- TMDB proxy ----

    private suspend fun db(pathAndQuery: String): JsonNode {
        val response = app.get("$mainUrl/database/$pathAndQuery", headers = siteHeaders, timeout = 20L)
        if (!response.isSuccessful && response.code != 404) throw ErrorLoadingException("Catalogue error ${response.code}")
        return mapper.readTree(response.text)
    }

    private fun hasNextPage(json: JsonNode, page: Int): Boolean {
        val total = json["total_pages"]?.asInt() ?: 0
        return page < minOf(total, 500)
    }

    private fun image(path: String?, size: String): String? =
        path?.let { if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/$size$it" }

    private fun absolute(base: String, link: String): String =
        if (link.startsWith("http")) link else runCatching { URI("$base/").resolve(link).toString() }.getOrDefault(base + link)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    companion object {
        private const val TAG = "Zxcstream"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val VIVAMAX = 149142
        private val ADULT_WORDS = Regex(
            "(?i)\\b(creampie|porn|porno|hentai|jav|av idol|gravure|nude|nudity|erotic|erotica|softcore|hardcore sex|sex tape|milf|orgy|bukkake|gangbang|fetish|bdsm)\\b|" +
                "中出|無修正|无码|成人|情色|三级|AV女優|アダルト|エロ",
        )
        private const val PUNKFLIX = "https://punkflix.net"
        private const val CINEZO = "https://player.cinezo.live"
        private const val VIDBOLT = "https://vidbolt.xyz"
        private const val PARALLEL = 3
        private const val SERVER_TIMEOUT = 20_000L
        private const val DUB_TIMEOUT = 25_000L
        private const val PROBE_TIMEOUT = 10L
        private const val MAX_SUBTITLES = 40

        private val TITLE_REGEX = Regex("""/(movie|tv)/(\d+)""")
        private val WATCH_REGEX = Regex("""/watch/(movie|tv)/(\d+)(?:/(\d+)/(\d+))?""")
        private val RESOLUTION_REGEX = Regex("""#EXT-X-STREAM-INF:[^\n]*RESOLUTION=(\d+)x(\d+)""")
        private val TKHD = "tkhd".toByteArray(Charsets.ISO_8859_1)
        private val ASIAN_LANGUAGES = setOf("ko", "zh", "cn", "th", "ja")
        private val ASIAN_COUNTRIES = setOf("KR", "CN", "TW", "HK", "TH", "JP")

        /** Title details, shared by the Vivamax check, load() and loadLinks(). */
        private val details = object : LinkedHashMap<DetailsKey, JsonNode>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DetailsKey, JsonNode>?) = size > 300
        }.let { java.util.Collections.synchronizedMap(it) }
    }
}
