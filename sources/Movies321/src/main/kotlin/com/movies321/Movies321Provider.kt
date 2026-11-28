package com.movies321

import com.csasian.shared.CsTmdbBrowse
import com.csasian.shared.CsSearch
import android.util.Log
import com.csasian.shared.CsBackup
import com.csasian.shared.CsLogos

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 321movies.xyz is a TMDB-keyed front end (`/movie/{id}`, `/tv/{id}`). Its catalogue comes straight
 * from TMDB; we read the same TMDB data through the keyless TMDB route our Movy source uses.
 *
 * Playback, two paths run side by side:
 *  A. The site's own player API, `/api/player/vixsrc-playlist`: a list of hls/mp4 links, each
 *     `enc:`-obfuscated with the player bundle's public codec key. Every link is probed (playlist,
 *     variant, first segment, total length vs. runtime) and only working ones are offered. This is
 *     the only path on SkyStream.
 *  B. Android only: the site's vidsrc.sh player in a hidden WebView:
 *  1. `vsembed.ru/vs_src.php` hands out a signed landing URL on a rotating player host.
 *  2. The landing page's `CFG.playerUrl` is the player page; its JS fetches the encrypted stream
 *     list, decrypts it with a WASM module that rotates every few minutes, gets an IP-bound token
 *     from the stream host's `/generate.php` and requests `/pl/<id>?token=...`.
 *  3. We let that JS run in CloudStream's hidden WebView and capture that first master request.
 *  4. `/pl/` playlists carry no tokens (the site's player stamps one on every request). The same
 *     id under `/pli/` (the iOS route) returns playlists with the token already in every variant and
 *     segment URL, so that is what we hand to the player. Segments need the player host as Referer.
 * Subtitles come from the site's own `/api/player/subtitles` (VTT files on cache.vdrk.site).
 */
class Movies321Provider : MainAPI() {
    override var mainUrl = "https://321movies.xyz"
    override var name = "321Movies CS"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.Anime)

    private val mapper = ObjectMapper()
    private val siteHeaders
        get() = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")

    // Category browsing (shared/CsTmdbBrowse.kt): TMDB discover through the same TMDB route as the rows,
    // shown as our own cards (so details and links still go through this source).
    private val browse = CsTmdbBrowse.create(this, name, get = { tmdb(it) }) { results, kind -> toResults(results, kind) }

    // TMDB lists behind the site's Trending / Popular / Top Rated rows, plus K-drama and anime.
    override val mainPage = browse.mainPage(mainPageOf(
        "trending/movie/week" to "Trending Movies",
        "trending/tv/week" to "Trending TV Shows",
        "movie/popular" to "Popular Movies",
        "tv/popular" to "Popular TV Shows",
        "movie/top_rated" to "Top Rated Movies",
        "tv/top_rated" to "Top Rated TV Shows",
        "discover/tv?with_original_language=ko&sort_by=popularity.desc&vote_count.gte=20" to "K-Drama",
        "discover/tv?with_genres=16&with_original_language=ja&sort_by=popularity.desc" to "Anime",
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
        val type = if (path.contains("tv")) "tv" else "movie"
        val sep = if (path.contains("?")) "&" else "?"
        val json = tmdb("$path${sep}page=$page")
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

    private suspend fun loadPage(url: String): LoadResponse {
        val m = TITLE_REGEX.find(url) ?: throw ErrorLoadingException("Invalid 321Movies URL: $url")
        val type = m.groupValues[1]
        val id = m.groupValues[2]
        val d = tmdb("$type/$id?append_to_response=credits,recommendations,similar,external_ids,videos")
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
        val recs = (toResults(d["recommendations"]?.get("results"), type) +
            toResults(d["similar"]?.get("results"), type)).distinctBy { it.url }
        val imdb = d.text("imdb_id") ?: d["external_ids"]?.text("imdb_id")
        val genreIds = d["genres"]?.mapNotNull { it["id"]?.asInt() }.orEmpty()
        val language = d.text("original_language")

        if (type == "movie") {
            val movieType = if (16 in genreIds && language == "ja") TvType.AnimeMovie else TvType.Movie
            return newMovieLoadResponse(title, url, movieType, "$mainUrl/watch/movie/$id") {
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

        val showType = when {
            16 in genreIds && language == "ja" -> TvType.Anime
            language == "ko" || language == "zh" || language == "th" -> TvType.AsianDrama
            else -> TvType.TvSeries
        }
        val episodes = loadEpisodes(id, d)
        if (episodes.isEmpty()) throw ErrorLoadingException("No aired episodes")
        return newTvSeriesLoadResponse(title, url, showType, episodes) {
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
                newEpisode("$mainUrl/watch/tv/$id?season=$seasonNumber&episode=$number") {
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
    ): Boolean = coroutineScope {
        // data is .../watch/movie/{id} or .../watch/tv/{id}?season={s}&episode={e}.
        val m = TITLE_REGEX.find(data) ?: return@coroutineScope false
        val type = m.groupValues[1]
        val id = m.groupValues[2]
        val season = Regex("""[?&]season=(\d+)""").find(data)?.groupValues?.get(1)
        val episode = Regex("""[?&]episode=(\d+)""").find(data)?.groupValues?.get(1)
        if (type == "tv" && (season == null || episode == null)) return@coroutineScope false
        val key = Title(type, id, season, episode)

        var sent = 0
        val send: (ExtractorLink) -> Unit = { link -> synchronized(this@Movies321Provider) { sent++; callback(link) } }

        val subs = async { runCatching { loadSubtitles(key, subtitleCallback) }.onFailure { Log.i(TAG, "subtitles $key failed: $it") } }
        // Android: the vidsrc WebView path runs alongside the site API. SkyStream's desktop browser
        // gets bounced by vidsrc's anti-devtools script, so there only the site API is used.
        val vidsrc = if (CsLogos.onDesktop) null else async { // desktop has no WebView; Android (CloudStream or SkyStream) does
            runCatching { resolveStream(key) }
                .onFailure { Log.i(TAG, "stream $key failed: $it") }
                .getOrNull()
                ?.let { stream ->
                    send(
                        newExtractorLink(name, "$name vidsrc", stream.url, ExtractorLinkType.M3U8) {
                            referer = stream.referer
                            quality = Qualities.Unknown.value
                            headers = mapOf("User-Agent" to USER_AGENT, "Origin" to stream.referer.trimEnd('/'))
                        },
                    )
                }
        }
        runCatching { loadApiLinks(key, send) }.onFailure { Log.i(TAG, "site api $key failed: $it") }
        vidsrc?.await()
        subs.await()
        sent > 0
    }

    // ---- The site's own player API (/api/player/vixsrc-playlist) ----

    private class ApiSource(val label: String, val url: String, val isMp4: Boolean, val language: String?, val height: Int?)

    private enum class Verdict { OK, MAYBE, DEAD }

    private class Probe(val verdict: Verdict, val height: Int? = null, val why: String = "")

    /**
     * Fetches the site's playlist, decodes every `enc:` link, probes them a few at a time with the
     * headers the site's player sends (its Referer/Origin; it adds nothing else), sends each one as
     * soon as it checks out, and finally the ambiguous ones (403/timeout) marked "(may not play)".
     * Dead links (404/451/5xx/DNS, HTML instead of a playlist, decoy clips) are dropped.
     */
    private suspend fun loadApiLinks(key: Title, send: (ExtractorLink) -> Unit) = coroutineScope {
        val runtime = async { runCatching { runtimeSeconds(key) }.getOrNull() }
        var query = "type=${key.type}&id=${key.id}"
        if (key.type == "tv") query += "&season=${key.season}&episode=${key.episode}"
        val started = System.currentTimeMillis()
        val response = app.get(
            "$mainUrl/api/player/vixsrc-playlist?$query",
            headers = siteHeaders + ("Accept" to "application/json"),
            timeout = API_TIMEOUT,
        )
        if (!response.isSuccessful) throw ErrorLoadingException("playlist api ${response.code}")
        val sources = parseApiSources(mapper.readTree(response.text))
        Log.i(TAG, "site api $key: ${sources.size} sources in ${System.currentTimeMillis() - started}ms")
        if (sources.isEmpty()) return@coroutineScope
        val runtimeSec = runtime.await()

        // Probe (and so send) in rank order: English, untagged, Hindi, other languages; best quality first.
        val ranked = sources.sortedWith(compareBy<ApiSource> { languageRank(it.language) }.thenByDescending { it.height ?: 0 })
        val permits = Semaphore(PROBE_PARALLEL)
        val maybes = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, ApiSource>>())
        ranked.mapIndexed { index, src ->
            async {
                val probe = permits.withPermit {
                    runCatching { probe(src, runtimeSec) }.getOrElse { Probe(Verdict.MAYBE, why = it.javaClass.simpleName) }
                }
                Log.i(TAG, "probe ${src.label}: ${probe.verdict} ${probe.why} ${src.url.take(90)}")
                when (probe.verdict) {
                    Verdict.OK -> send(apiLink(src, probe.height ?: src.height, verified = true))
                    Verdict.MAYBE -> maybes += index to src
                    Verdict.DEAD -> Unit
                }
            }
        }.awaitAll()
        maybes.sortedBy { it.first }.forEach { send(apiLink(it.second, it.second.height, verified = false)) }
    }

    private suspend fun apiLink(src: ApiSource, height: Int?, verified: Boolean): ExtractorLink {
        val title = "$name ${src.label}" + if (verified) "" else " (may not play)"
        val type = if (src.isMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
        return newExtractorLink(name, title, src.url, type) {
            referer = "$mainUrl/"
            quality = height ?: Qualities.Unknown.value
            headers = playerHeaders
        }
    }

    /** `playlist[].sources[]` -> decoded, de-duplicated hls/mp4 links. */
    private fun parseApiSources(json: JsonNode): List<ApiSource> {
        val out = LinkedHashMap<String, ApiSource>()
        json["playlist"]?.forEach { item ->
            item["sources"]?.forEach { s ->
                val kind = s.text("type")
                if (kind != "hls" && kind != "mp4") return@forEach
                val file = s.text("file")?.let { decodePlayerUrl(it) }?.takeIf { it.startsWith("http") } ?: return@forEach
                val url = withoutSpoofedIpHeaders(file)
                val rawLabel = s.text("label") ?: s.text("provider") ?: "Source"
                // "Cascade 720p | Hindi": the language stays in the label so it can still be picked.
                val language = rawLabel.substringAfter("|", "").trim().takeIf { it.isNotEmpty() }
                val label = rawLabel.replace(Regex("""\s*\|\s*"""), " · ").trim()
                if (url !in out) out[url] = ApiSource(label, url, kind == "mp4", language, labelHeight(rawLabel))
            }
        }
        return out.values.toList()
    }

    /**
     * The site's player codec: `enc:` + base64url(8-byte nonce + data), where
     * `plain[n] = data[n] ^ key[(n + nonce[n % 8]) % key.length]`. The key is a public constant in
     * the site's player bundle; the proxy links carry a shorter variant, kept as a fallback.
     */
    private fun decodePlayerUrl(value: String): String? {
        if (!value.startsWith("enc:")) return value
        val bytes = runCatching {
            java.util.Base64.getDecoder().decode(
                value.removePrefix("enc:").replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) },
            )
        }.getOrNull() ?: return null
        if (bytes.size <= 8) return null
        val nonce = bytes.copyOfRange(0, 8)
        val payload = bytes.copyOfRange(8, bytes.size)
        for (codecKey in CODEC_KEYS) {
            val k = codecKey.toByteArray()
            val plain = ByteArray(payload.size) { n ->
                (payload[n].toInt() xor k[(n + (nonce[n % 8].toInt() and 0xff)) % k.size].toInt()).toByte()
            }
            val text = String(plain, Charsets.UTF_8)
            if (text.startsWith("http")) return text
        }
        return null
    }

    /**
     * Some proxy links ask the site's proxy to send a made-up X-Forwarded-For upstream. We never
     * send spoofed client-IP headers, so those keys are taken out of the proxy's `headers=` JSON.
     */
    private fun withoutSpoofedIpHeaders(url: String): String {
        val m = Regex("""([?&]headers=)([^&]*)""").find(url) ?: return url
        val node = runCatching { mapper.readTree(URLDecoder.decode(m.groupValues[2], "UTF-8")) }.getOrNull()
            as? com.fasterxml.jackson.databind.node.ObjectNode ?: return url
        val spoofed = node.fieldNames().asSequence().filter { it.lowercase() in SPOOF_HEADERS }.toList()
        if (spoofed.isEmpty()) return url
        node.remove(spoofed)
        val encoded = URLEncoder.encode(mapper.writeValueAsString(node), "UTF-8")
        return url.replaceRange(m.groups[2]!!.range, encoded)
    }

    /** Checks one link the way a player would: playlist -> variant -> first segment (or MP4 head). */
    private suspend fun probe(src: ApiSource, runtimeSec: Int?): Probe {
        if (src.isMp4) {
            val r = probeGet(src.url, range = true) ?: return Probe(Verdict.MAYBE, why = "timeout")
            statusVerdict(r.code, r.body)?.let { return it }
            val head = r.bytes
            return if (head.size > 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp") Probe(Verdict.OK, why = "mp4")
            else Probe(Verdict.DEAD, why = "not mp4")
        }
        val master = probeGet(src.url) ?: return Probe(Verdict.MAYBE, why = "timeout")
        statusVerdict(master.code, master.body)?.let { return it }
        if (!master.body.trimStart().startsWith("#EXTM3U")) return Probe(Verdict.DEAD, why = "not a playlist")
        var height: Int? = null
        var media = master
        if (master.body.contains("#EXT-X-STREAM-INF")) {
            val variants = parseVariants(master.body, master.url)
            if (variants.isEmpty()) return Probe(Verdict.DEAD, why = "empty master")
            height = variants.maxOf { it.first }.takeIf { it > 0 }
            val best = variants.maxByOrNull { it.first }!!.second
            media = probeGet(best) ?: return Probe(Verdict.MAYBE, height, "variant timeout")
            statusVerdict(media.code, media.body)?.let { return Probe(it.verdict, height, "variant ${it.why}") }
            if (!media.body.trimStart().startsWith("#EXTM3U")) return Probe(Verdict.DEAD, why = "variant not a playlist")
        }
        val total = EXTINF_REGEX.findAll(media.body).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
        val minimum = runtimeSec?.let { it * MIN_RUNTIME_SHARE } ?: MIN_UNKNOWN_RUNTIME
        if (total < minimum) {
            return Probe(Verdict.DEAD, why = "decoy: ${total.toInt()}s of ${runtimeSec ?: "?"}s")
        }
        val segment = media.body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?.let { resolveUrl(media.url, it) } ?: return Probe(Verdict.DEAD, why = "no segments")
        val seg = probeGet(segment, range = true) ?: return Probe(Verdict.MAYBE, height, "segment timeout")
        statusVerdict(seg.code, seg.body)?.let { return Probe(it.verdict, height, "segment ${it.why}") }
        val b = seg.bytes
        val encrypted = media.body.contains("#EXT-X-KEY:METHOD=AES")
        return when {
            encrypted -> Probe(Verdict.OK, height, "aes, ${total.toInt()}s")
            b.isNotEmpty() && b[0] == 0x47.toByte() -> Probe(Verdict.OK, height, "ts, ${total.toInt()}s")
            b.size > 8 && String(b, 4, 4, Charsets.ISO_8859_1) in setOf("ftyp", "styp", "moof", "sidx") -> Probe(Verdict.OK, height, "fmp4")
            b.size > 3 && String(b, 0, 3, Charsets.ISO_8859_1) == "ID3" -> Probe(Verdict.OK, height, "id3")
            // Segments behind a fake image header need an interceptor; none of the site's real sources do.
            else -> Probe(Verdict.DEAD, why = "segment is not video (${b.take(4).joinToString("") { "%02x".format(it) }})")
        }
    }

    /** null = fine; 403/429 = ambiguous; 404/451/5xx and other failures = dead. */
    private fun statusVerdict(code: Int, body: String): Probe? = when {
        code in 200..299 -> if (body.trimStart().startsWith("<")) Probe(Verdict.DEAD, why = "html") else null
        // The site's proxies answer "fail" / "Forbidden: unauthorized access" when the upstream refused them,
        // whatever headers we send; a bare 403 may still depend on the player's own request.
        code == 403 && (body.trim() == "fail" || body.contains("unauthorized access")) -> Probe(Verdict.DEAD, why = "403 upstream refused")
        code == 403 && body.trimStart().startsWith("<") -> Probe(Verdict.DEAD, why = "403 html")
        code == 403 || code == 429 -> Probe(Verdict.MAYBE, why = "$code")
        else -> Probe(Verdict.DEAD, why = "$code")
    }

    private class Fetched(val code: Int, val url: String, val body: String, val bytes: ByteArray)

    /** One short GET with the player's headers; null on timeout (ambiguous), DEAD-coded on DNS/connect errors. */
    private suspend fun probeGet(url: String, range: Boolean = false): Fetched? = try {
        val headers = if (range) playerHeaders + ("Range" to "bytes=0-2047") else playerHeaders
        val r = app.get(url, headers = headers, timeout = PROBE_TIMEOUT)
        val bytes = if (range) r.body.byteStream().use { readUpTo(it, 2048) } else r.body.bytes()
        Fetched(r.code, r.url, if (range) String(bytes, Charsets.ISO_8859_1) else String(bytes, Charsets.UTF_8), bytes)
    } catch (e: java.net.SocketTimeoutException) {
        null
    } catch (e: java.io.InterruptedIOException) {
        null
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Fetched(-1, url, "", ByteArray(0)) // DNS, refused, TLS: dead
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

    /** (height, url) of every variant in a master playlist. */
    private fun parseVariants(master: String, base: String): List<Pair<Int, String>> {
        val lines = master.lines()
        return lines.mapIndexedNotNull { i, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
            val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: return@mapIndexedNotNull null
            val h = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            h to resolveUrl(base, uri)
        }
    }

    private fun resolveUrl(base: String, ref: String): String =
        runCatching { URI(base).resolve(ref.replace(" ", "%20")).toString() }.getOrDefault(ref)

    private suspend fun runtimeSeconds(key: Title): Int? {
        val minutes = if (key.type == "movie") tmdb("movie/${key.id}")["runtime"]?.asInt()
        else tmdb("tv/${key.id}/season/${key.season}/episode/${key.episode}")["runtime"]?.asInt()
        return minutes?.takeIf { it > 0 }?.times(60)
    }

    private fun languageRank(language: String?): Int = when {
        language == null -> 1
        language.startsWith("English", true) -> 0
        language.startsWith("Hindi", true) -> 2
        else -> 3
    }

    private fun labelHeight(label: String): Int? = when {
        Regex("""\b(4K|2160p?)\b""", RegexOption.IGNORE_CASE).containsMatchIn(label) -> 2160
        else -> Regex("""\b(\d{3,4})p\b""").find(label)?.groupValues?.get(1)?.toIntOrNull()
    }

    private val playerHeaders
        get() = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/", "Origin" to mainUrl)

    private data class Title(val type: String, val id: String, val season: String?, val episode: String?)

    private class Stream(val url: String, val referer: String)

    /**
     * Landing URL -> player page -> (hidden WebView) first tokenised master request -> its `/pli/`
     * twin. A stale signed landing URL is dropped and fetched once more; a WebView that captures
     * nothing is not retried (it already waited [WEBVIEW_TIMEOUT]).
     */
    private suspend fun resolveStream(key: Title): Stream {
        for (attempt in 0..1) {
            val src = embedSrc(key, fresh = attempt > 0)
            val origin = originOf(src) ?: throw ErrorLoadingException("Bad player URL")
            val landing = app.get(src, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$EMBED/"), timeout = 20L)
            val playerPath = CFG_REGEX.find(landing.text)?.groupValues?.get(1)
                ?.let { runCatching { mapper.readTree(it)["playerUrl"]?.asText() }.getOrNull() }
            if (!landing.isSuccessful || playerPath.isNullOrBlank()) {
                Log.i(TAG, "landing ${landing.code} without playerUrl (attempt $attempt)")
                forget(key)
                continue
            }
            val playerUrl = if (playerPath.startsWith("http")) playerPath else origin + playerPath

            val resolver = WebViewResolver(
                interceptUrl = MASTER_REGEX,
                userAgent = USER_AGENT,
                // Let the WebView do its own networking: its CORS requests need the real Origin.
                useOkhttp = false,
                timeout = WEBVIEW_TIMEOUT,
            )
            val started = System.currentTimeMillis()
            val captured = resolver.resolveUsingWebView(playerUrl, src, mapOf("User-Agent" to USER_AGENT), "GET") { false }.first
            val master = captured?.url?.toString()
            Log.i(TAG, "webview ${System.currentTimeMillis() - started}ms captured=${master?.take(90)}")
            if (master == null) {
                // The player never asked for a playlist (no stream, captcha gate, or no WebView).
                forget(key)
                throw ErrorLoadingException("No stream captured")
            }
            // The player sends Referer = its own origin; segments refuse any other.
            val referer = captured.header("Referer")?.let { originOf(it) }?.let { "$it/" } ?: "$origin/"
            return Stream(withTokenedPlaylists(master), referer)
        }
        throw ErrorLoadingException("No stream captured")
    }

    /** `/pl/<id>[/master.m3u8]?token=` -> `/pli/<id>...`: same token, but stamped into every URL. */
    private fun withTokenedPlaylists(master: String): String =
        STATIC_PL_REGEX.replace(master) { "/pli/${it.groupValues[1]}" }

    /**
     * One `vs_src.php` call per title, kept for a few minutes: the endpoint answers 429 after about
     * five quick calls. Calls are serialized; a 429 waits once and retries.
     */
    private suspend fun embedSrc(key: Title, fresh: Boolean): String = embedLock.withLock {
        val now = System.currentTimeMillis()
        if (!fresh) embedCache[key]?.takeIf { now - it.first < EMBED_TTL }?.let { return@withLock it.second }
        val query = if (key.type == "movie") "type=movie&id=${key.id}"
        else "type=tv&id=${key.id}&season=${key.season}&episode=${key.episode}"
        val referer = if (key.type == "movie") "$EMBED/embed/movie?tmdb=${key.id}"
        else "$EMBED/embed/tv?tmdb=${key.id}&season=${key.season}&episode=${key.episode}"
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer, "Accept" to "application/json")
        var response = app.get("$EMBED/vs_src.php?$query", headers = headers, timeout = 15L)
        if (response.code == 429) {
            Log.i(TAG, "vs_src 429, waiting")
            delay(RATE_LIMIT_WAIT)
            response = app.get("$EMBED/vs_src.php?$query", headers = headers, timeout = 15L)
        }
        if (!response.isSuccessful) throw ErrorLoadingException("vidsrc error ${response.code}")
        val src = mapper.readTree(response.text).text("src")?.takeIf { it.startsWith("http") }
            ?: throw ErrorLoadingException("vidsrc returned no player")
        synchronized(embedCache) {
            embedCache.entries.removeAll { now - it.value.first >= EMBED_TTL }
            embedCache[key] = now to src
        }
        Log.i(TAG, "vs_src $key -> ${originOf(src)}")
        src
    }

    private fun forget(key: Title) {
        synchronized(embedCache) { embedCache.remove(key) }
    }

    /** VTT tracks from the site's subtitle API, taken straight from cache.vdrk.site. */
    private suspend fun loadSubtitles(key: Title, subtitleCallback: (SubtitleFile) -> Unit) {
        var url = "$mainUrl/api/player/subtitles?id=${key.id}&type=${key.type}"
        if (key.type == "tv") url += "&season=${key.season}&episode=${key.episode}"
        val json = mapper.readTree(app.get(url, headers = siteHeaders, timeout = 15L).text)
        val tracks = json["tracks"]?.toList().orEmpty()
        tracks.mapNotNull { t ->
            val raw = t.text("url") ?: return@mapNotNull null
            val direct = Regex("""[?&]url=([^&]+)""").find(raw)?.groupValues?.get(1)
                ?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.startsWith("http") }
            val file = direct ?: if (raw.startsWith("http")) raw else mainUrl + raw
            file to subtitleLabel(t.text("label") ?: t.text("lang") ?: "Unknown")
        }.distinctBy { it.first }
            // Up to a few tracks per language (the list is alphabetical; a flat cap would drop whole languages).
            .groupBy { it.second.substringBefore(" (HI)").replace(Regex("""\s+\d+$"""), "") }
            .values.flatMap { group -> group.sortedBy { it.second.contains("(HI)") }.take(PER_LANGUAGE) }
            .take(MAX_SUBTITLES)
            .forEach { (file, label) -> subtitleCallback(newSubtitleFile(label, file)) }
        Log.i(TAG, "subtitles $key: ${tracks.size}")
    }

    /** "English2" -> "English 2", "English Hi3" -> "English (HI) 3". */
    private fun subtitleLabel(label: String): String {
        val hi = Regex("""^(.*?)\s+Hi(\d*)$""").find(label)
        if (hi != null) return "${hi.groupValues[1]} (HI) ${hi.groupValues[2]}".trim()
        return label.replace(Regex("""(\D)(\d+)$"""), "$1 $2")
    }

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

    private fun originOf(url: String): String? = runCatching {
        val u = URI(url)
        if (u.host == null) null else "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}"
    }.getOrNull()

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun JsonNode.text(field: String): String? =
        this[field]?.takeIf { !it.isNull && !it.isContainerNode }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val TAG = "Movies321"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val TMDB = "https://db.wecollege.net/3"
        private const val EMBED = "https://vsembed.ru"
        private const val EMBED_TTL = 4 * 60 * 1000L
        private const val RATE_LIMIT_WAIT = 4_000L
        private const val WEBVIEW_TIMEOUT = 30_000L
        private const val MAX_SUBTITLES = 150
        private const val PER_LANGUAGE = 3
        private const val API_TIMEOUT = 45L
        private const val PROBE_TIMEOUT = 8L
        private const val PROBE_PARALLEL = 3
        private const val MIN_RUNTIME_SHARE = 0.5
        private const val MIN_UNKNOWN_RUNTIME = 600.0

        /** The site player's link codec key (its bundle's playerUrlCodec) and the shorter proxy `k=` variant. */
        private val CODEC_KEYS = listOf(
            "j7wYkYhVgQn5x2L6k2M8hVQfD4zN3bP1aR7uT0cXyE6dZX4sWAd87JKMN8HHGG654GVCFRLMNBOPUY7LK",
            "j7wYkYhVgQn5x2L6k2M8hVQfD4zN3bP1aR7uT0cXyE6dZX4sW",
        )
        private val SPOOF_HEADERS = setOf("x-forwarded-for", "cf-connecting-ip", "x-real-ip", "true-client-ip", "forwarded")
        private val EXTINF_REGEX = Regex("""#EXTINF:\s*([\d.]+)""")

        private val TITLE_REGEX = Regex("""/(movie|tv)/(\d+)""")
        private val CFG_REGEX = Regex("""window\.CFG\s*=\s*(\{.*?\});""")

        /** The player's first master request: `/pl/<id>?...token=` on the stream host. */
        val MASTER_REGEX = Regex("""https?://[^/]+/pl[a-z]?/[A-Za-z0-9_-]{20,}[^?#]*\?(?:[^#]*&)?token=""")
        private val STATIC_PL_REGEX = Regex("""/pl/([A-Za-z0-9_-]{40,160})(?=[/?#]|$)""")

        private val embedLock = Mutex()
        private val embedCache = HashMap<Title, Pair<Long, String>>()
    }
}
