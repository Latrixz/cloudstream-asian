package com.flixvision

import com.csasian.shared.CsBackup
import com.csasian.shared.CsQuality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * flixvision.st is a TMDB-keyed catalogue. Its own player is a list of ~30 third-party embed
 * hosts (`/api/go?t=..&slug=..&x=N` redirects to `<host>/embed/movie/<tmdbId>` etc.).
 * Most of them hide their streams behind encrypted API envelopes or browser checks; the ones
 * that serve plain HLS over ordinary HTTP are VixSrc and (best effort) VidRift, so those are
 * resolved here directly from the TMDB id.
 */
class FlixvisionProvider : MainAPI() {
    override var mainUrl = "https://flixvision.st"
    override var name = "FlixVision"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    private val siteHeaders
        get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
    private val jsonMapper = ObjectMapper()

    private val vixsrc = "https://vixsrc.to"
    private val vidrift = "https://embed.vidrift.net"

    // The site's own sections ("View All" targets of the home rails), then its genres.
    override val mainPage = listOf(
        "/trending" to "Trending",
        "/new-releases" to "New Releases",
        "/new-episodes" to "New Episodes",
        "/movie" to "Latest Movies",
        "/tv" to "Latest TV Shows",
        "/top-movies-2026" to "Top Movies 2026",
        "/top-tv-shows-2026" to "Top TV Shows 2026",
        "/top-imdb" to "Top IMDb",
        "/genre/action" to "Action",
        "/genre/comedy" to "Comedy",
        "/genre/drama" to "Drama",
        "/genre/horror" to "Horror",
        "/genre/thriller" to "Thriller",
        "/genre/science-fiction" to "Science Fiction",
        "/genre/animation" to "Animation",
        "/genre/crime" to "Crime",
        "/genre/romance" to "Romance",
        "/genre/documentary" to "Documentary",
    ).map { (path, label) -> MainPageData(label, path) }

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "$mainUrl${request.data}?page=$page" else "$mainUrl${request.data}"
        val document = fetchPage(url)
        val items = document.select("a.bf-card").mapNotNull { it.toCard() }.distinctBy { it.url }
        val hasNext = document.select(".pagination a[href]").any {
            it.attr("href").contains("page=${page + 1}")
        }
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    /**
     * The full results page (`/browser?keyword=`) sits behind the site's browser check, so search
     * uses the site's own autocomplete API. It returns the best 8 matches and has no paging.
     */
    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank() || page > 1) return emptyList<SearchResponse>() to false
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val text = app.get("$mainUrl/api/suggest?q=$encoded", headers = siteHeaders).text
        val root = runCatching { jsonMapper.readTree(text) }.getOrNull()
        if (root == null || !root.isArray) return emptyList<SearchResponse>() to false
        val results = root.mapNotNull { node ->
            val path = node.str("url") ?: return@mapNotNull null
            val title = node.str("title") ?: return@mapNotNull null
            val href = fixUrl(path)
            val poster = node.str("poster_path")?.let { "https://image.tmdb.org/t/p/w342$it" }
                ?: node.str("poster")?.replace("/w92/", "/w342/")
            val year = node.str("year")?.take(4)?.toIntOrNull()
            if (node.str("type") == "tv" || slugOf(href).startsWith("tv-")) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = poster
                    this.year = year
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    posterUrl = poster
                    this.year = year
                }
            }
        }
        return results.distinctBy { it.url } to false
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
        val pageUrl = url.substringBefore('?').substringBefore('#')
        val document = fetchDocument(pageUrl) ?: return loadWithoutPage(pageUrl)
        val ld = ldJson(document)
        val slug = slugOf(pageUrl)
        val isTv = slug.startsWith("tv-") || ld?.str("@type") == "TVSeries"

        val title = ld?.str("name")
            ?: document.selectFirst("h1.title")?.text()?.replace(Regex("""\s+-\s+Season\s+\d+$"""), "")?.trim()
            ?: throw ErrorLoadingException("Title not found")
        val posterImg: Element? = document.selectFirst(".dx-poster img")
        val poster = imageOf(posterImg)?.replace("/w342/", "/w500/")
            ?: document.selectFirst("#ml-meta")?.attr("data-p")?.takeIf { it.isNotBlank() }
        val background = document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: ld?.str("image")
        val plot = document.selectFirst(".description")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: ld?.str("description")
        val metaSpans = document.select(".metadata.set > span").map { it.text().trim() }
        val year = metaSpans.firstOrNull { it.matches(Regex("""\d{4}""")) }?.toIntOrNull()
            ?: ld?.str("datePublished")?.take(4)?.toIntOrNull()
        val rating = ld?.get("aggregateRating")?.str("ratingValue")?.toDoubleOrNull()
            ?: document.selectFirst(".metadata .IMDb")?.ownText()?.trim()?.toDoubleOrNull()
        val duration = metaSpans.firstNotNullOfOrNull { Regex("""^(\d+)\s*min""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: ld?.str("duration")?.let { Regex("""PT(?:(\d+)H)?(\d+)M""").find(it) }?.let {
                (it.groupValues[1].toIntOrNull() ?: 0) * 60 + (it.groupValues[2].toIntOrNull() ?: 0)
            }
        val infoRows = document.select(".mics li").associate { li ->
            li.selectFirst(".lbl")?.text()?.trim().orEmpty() to li
        }
        val tags = (infoRows["Genres"]?.select("a")?.map { it.text() }
            ?: ld?.get("genre")?.let { g -> if (g.isArray) g.map { it.asText() } else listOf(g.asText()) }
            ?: emptyList())
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val actorNames = (infoRows["Cast"]?.select("a")?.map { it.text() }
            ?: ld?.get("actor")?.mapNotNull { it.str("name") }
            ?: emptyList())
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val tmdbId = document.selectFirst("#cm-root")?.attr("data-id")?.takeIf { it.isNotBlank() }
        val recommendations = document.select("a.bf-card").mapNotNull { it.toCard() }
            .filter { it.url != pageUrl }.distinctBy { it.url }
        // The page lists cast names only; photos, roles and the trailer come from the TMDB mirror.
        val meta = tmdbId?.let { tmdbDetails(it, isTv) }
        val metaCast = meta?.get("cast")?.mapNotNull { c -> c.str("name")?.let { it to c } }?.toMap().orEmpty()
        val actors = actorNames.ifEmpty { metaCast.keys.take(15) }.map { actorName ->
            val c = metaCast[actorName]
            ActorData(Actor(actorName, c?.str("profile")), roleString = c?.str("character"))
        }
        val trailer = trailerOf(meta)
        val backdrop = background ?: meta?.str("backdrop")
        val score = (rating ?: meta?.str("rating")?.toDoubleOrNull())?.takeIf { it > 0 }?.let { Score.from10(it) }
        val runtime = duration ?: meta?.get("runtime")?.asInt()?.takeIf { it > 0 }

        if (!isTv) {
            val data = if (tmdbId != null) "$pageUrl?tmdb=$tmdbId" else pageUrl
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, data) {
                posterUrl = poster
                backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = runtime
                this.score = score
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }

        val episodes = buildEpisodes(document, pageUrl, tmdbId)
        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            posterUrl = poster
            backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
            this.tags = tags
            this.duration = runtime
            this.score = score
            this.actors = actors
            this.recommendations = recommendations
            addTrailer(trailer)
        }
    }

    private data class SiteEpisode(val number: Int, val name: String?, val runtime: Int?)
    private data class MetaEpisode(
        val number: Int,
        val name: String?,
        val runtime: Int?,
        val still: String?,
        val overview: String?,
    )

    private data class SeasonInfo(val counts: Map<Int, Int>, val episodes: List<MetaEpisode>)

    /**
     * The show page lists every season with its episode count, but only the first season's
     * episodes (per-season pages `?s=N&e=1` are behind the site's browser check). Episode titles,
     * stills and plots for each season come from VidRift's public TMDB episode API; if that
     * fails the episodes are still listed from the site's counts. Without a page (see
     * [loadWithoutPage]) the season list comes from that API too.
     */
    private suspend fun buildEpisodes(document: Document?, pageUrl: String, tmdbId: String?): List<Episode> {
        val currentSeason = document?.selectFirst(".we")?.attr("data-s")?.toIntOrNull() ?: 1
        val siteEpisodes = document?.select(".we-g a.we-c")?.mapNotNull { a ->
            val number = a.attr("data-n").toIntOrNull()
                ?: Regex("""[?&]e=(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            SiteEpisode(
                number,
                a.selectFirst(".we-ct")?.text()?.trim()?.takeIf { it.isNotBlank() },
                a.selectFirst(".we-cm")?.text()?.let { Regex("""(\d+)\s*min""").find(it) }?.groupValues?.get(1)?.toIntOrNull(),
            )
        }.orEmpty().distinctBy { it.number }

        val seasonCounts = document?.select("a.we-s")?.mapNotNull { a ->
            val season = Regex("""Season\s+(\d+)""").find(a.attr("title"))?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""[?&]s=(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            val count = a.selectFirst("small")?.text()?.let { Regex("""(\d+)""").find(it) }?.groupValues?.get(1)?.toIntOrNull() ?: 0
            season to count
        }.orEmpty().toMap().toMutableMap()

        val metadata = mutableMapOf<Int, List<MetaEpisode>>()
        if (tmdbId != null) {
            if (seasonCounts.isEmpty()) {
                // No season list on the page: ask for season 1 first, it also lists every season.
                seasonInfo(tmdbId, currentSeason)?.let { info ->
                    seasonCounts.putAll(info.counts)
                    metadata[currentSeason] = info.episodes
                }
            }
            seasonCounts.keys.filter { it !in metadata }.amap { season -> season to seasonInfo(tmdbId, season) }
                .forEach { (season, info) -> if (info != null) metadata[season] = info.episodes }
        }
        if (seasonCounts.isEmpty() && siteEpisodes.isNotEmpty()) {
            seasonCounts[currentSeason] = siteEpisodes.maxOf { it.number }
        }

        return seasonCounts.keys.sorted().flatMap { season ->
            val meta = metadata[season].orEmpty().associateBy { it.number }
            val site = if (season == currentSeason) siteEpisodes.associateBy { it.number } else emptyMap()
            val numbers = (1..(seasonCounts[season] ?: 0)).toSortedSet().apply {
                addAll(meta.keys)
                addAll(site.keys)
            }
            numbers.map { number ->
                val m = meta[number]
                val s = site[number]
                val data = buildString {
                    append("$pageUrl?s=$season&e=$number")
                    if (tmdbId != null) append("&tmdb=$tmdbId")
                }
                newEpisode(data) {
                    this.name = s?.name ?: m?.name ?: "Episode $number"
                    this.season = season
                    this.episode = number
                    this.posterUrl = m?.still
                    this.description = m?.overview
                    this.runTime = s?.runtime ?: m?.runtime
                }
            }
        }
    }

    private suspend fun seasonInfo(tmdbId: String, season: Int): SeasonInfo? = runCatching {
        val text = app.get(
            "$vidrift/api/episodes/$tmdbId/$season?v=2",
            headers = mapOf("User-Agent" to userAgent, "Referer" to "$vidrift/embed/tv/$tmdbId/$season/1"),
            timeout = 10L,
        ).text
        val root = jsonMapper.readTree(text)
        if (root.path("season").asInt(-1) != season) return@runCatching null
        val counts = root.path("seasons").mapNotNull { node ->
            val number = node.path("season").asInt(0).takeIf { it > 0 } ?: return@mapNotNull null
            number to node.path("episodes").asInt(0)
        }.toMap()
        val episodes = root.path("episodes").mapNotNull { node ->
            val number = node.path("episode").asInt(0).takeIf { it > 0 } ?: return@mapNotNull null
            MetaEpisode(
                number,
                node.str("name"),
                node.path("runtime").asInt(0).takeIf { it > 0 },
                node.str("still"),
                node.str("overview"),
            )
        }
        SeasonInfo(counts, episodes)
    }.getOrNull()

    /**
     * Detail pages that Cloudflare has not cached are answered with the site's "Quick check"
     * browser challenge. That challenge is not solved here. Instead the title is shown from the
     * site's unchallenged endpoints: the autocomplete API (title, year, poster) and the player's
     * own `/api/go` redirect (TMDB id), with seasons from the episode API. Plot, cast and rating
     * are only on the detail page, so they are filled from an open TMDB mirror instead.
     */
    private suspend fun loadWithoutPage(pageUrl: String): LoadResponse {
        val slug = slugOf(pageUrl)
        val isTv = slug.startsWith("tv-")
        val tmdbId = tmdbIdFromPlayer(slug, isTv)
            ?: throw ErrorLoadingException("FlixVision asked for a browser check; try again later")
        val words = slug.substringAfter('-').substringBeforeLast('-').replace('-', ' ')
        val hit = runCatching { searchPage(words, 1).first }.getOrDefault(emptyList())
            .firstOrNull { it.url == pageUrl }
        val title = hit?.name ?: words.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        val year = (hit as? MovieSearchResponse)?.year ?: (hit as? TvSeriesSearchResponse)?.year
        val poster = hit?.posterUrl?.replace("/w342/", "/w500/")
        // The detail page is blocked, so fill plot, cast, rating and genres from an open TMDB mirror.
        val meta = tmdbDetails(tmdbId, isTv)
        val name = meta?.str("title") ?: title
        val plot = meta?.str("overview")
        val score = meta?.get("rating")?.asDouble()?.takeIf { it > 0 }?.let { Score.from10(it) }
        val tags = meta?.get("genres")?.mapNotNull { it.asText().takeIf { g -> g.isNotBlank() } }.orEmpty()
        val cast = meta?.get("cast")?.mapNotNull { c ->
            val actorName = c.str("name") ?: return@mapNotNull null
            ActorData(Actor(actorName, c.str("profile")), roleString = c.str("character"))
        }.orEmpty().take(15)
        val metaYear = meta?.get("year")?.asInt()?.takeIf { it > 0 }
        val metaPoster = meta?.str("poster")
        val backdrop = meta?.str("backdrop")
        if (!isTv) {
            return newMovieLoadResponse(name, pageUrl, TvType.Movie, "$pageUrl?tmdb=$tmdbId") {
                posterUrl = poster ?: metaPoster
                backgroundPosterUrl = backdrop
                this.year = year ?: metaYear
                this.plot = plot
                this.score = score
                this.tags = tags
                this.actors = cast
                this.duration = meta?.get("runtime")?.asInt()?.takeIf { it > 0 }
                addTrailer(trailerOf(meta))
            }
        }
        return newTvSeriesLoadResponse(name, pageUrl, TvType.TvSeries, buildEpisodes(null, pageUrl, tmdbId)) {
            posterUrl = poster ?: metaPoster
            backgroundPosterUrl = backdrop
            this.year = year ?: metaYear
            this.plot = plot
            this.score = score
            this.tags = tags
            this.actors = cast
            addTrailer(trailerOf(meta))
        }
    }

    /** YouTube trailer from the TMDB mirror's `trailer` object or `trailers` list. */
    private fun trailerOf(meta: JsonNode?): String? {
        val key = meta?.get("trailer")?.str("key")
            ?: meta?.get("trailers")?.firstOrNull { it.str("site") == null || it.str("site") == "YouTube" }?.str("key")
        return key?.let { "https://www.youtube.com/watch?v=$it" }
    }

    /** TMDB details (title, overview, rating, genres, runtime, cast) from an open, key-less mirror. */
    private suspend fun tmdbDetails(tmdbId: String, isTv: Boolean): JsonNode? {
        val type = if (isTv) "tv" else "movie"
        for (host in listOf("https://api.framextv.cc", "https://api.framextv.xyz")) {
            val node = runCatching {
                val text = app.get(
                    "$host/api/details?id=$tmdbId&type=$type",
                    headers = mapOf("User-Agent" to userAgent, "Referer" to "https://framextv.cc/"),
                    timeout = 10L,
                ).text
                jsonMapper.readTree(text)
            }.getOrNull()
            if (node != null && node.str("title") != null) return node
        }
        return null
    }

    /** The play button's `/api/go` answers with a redirect to `<host>/.../<tmdbId>`; read the id from it. */
    private suspend fun tmdbIdFromPlayer(slug: String, isTv: Boolean): String? = runCatching {
        val type = if (isTv) "tv" else "movie"
        val url = "$mainUrl/api/go?t=$type&slug=$slug&x=0&st=0" + if (isTv) "&s=1&e=1" else ""
        val html = app.get(url, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/watch/$slug")).text
        val target = Regex("""url=([^"'>\s]+)""").find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
            ?: return@runCatching null
        Regex("""/(?:movie|tv)/(\d+)""").find(target)?.groupValues?.get(1)
            ?: Regex("""[?&](?:tmdb|video_id|id)=(\d+)""").find(target)?.groupValues?.get(1)
    }.getOrNull()

    private data class Candidate(
        val label: String,
        val url: String,
        val referer: String,
        val headers: Map<String, String>,
    )

    private suspend fun loadOwnLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data.substringBefore('?').substringBefore('#'))
        val query = data.substringAfter('?', "")
        fun param(key: String) = Regex("""(?:^|&)$key=([^&#]*)""").find(query)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

        val season = param("s")?.toIntOrNull()
        val episode = param("e")?.toIntOrNull()
        val isTv = slugOf(pageUrl).startsWith("tv-") || season != null
        val tmdbId = param("tmdb")
            ?: fetchDocument(pageUrl)?.selectFirst("#cm-root")?.attr("data-id")?.takeIf { it.isNotBlank() }
            ?: tmdbIdFromPlayer(slugOf(pageUrl), isTv)
            ?: return false
        val path = if (isTv) "tv/$tmdbId/${season ?: 1}/${episode ?: 1}" else "movie/$tmdbId"

        val resolvers: List<suspend () -> List<Candidate>> = listOf(
            { vixsrcCandidates(path) },
            { vidriftCandidates(path, subtitleCallback) },
        )
        val candidates = resolvers.amap { resolver -> runCatching { resolver() }.getOrDefault(emptyList()) }.flatten()
        if (candidates.isEmpty()) return false

        // Check every playlist in parallel; offer the live ones in order, or everything if none answered.
        val checked = candidates.amap { it to isPlayable(it) }
        val usable = checked.filter { it.second }.map { it.first }.ifEmpty { candidates }
        usable.forEach { c ->
            callback(
                newExtractorLink(name, "$name ${c.label}", c.url, ExtractorLinkType.M3U8) {
                    referer = c.referer
                    quality = Qualities.Unknown.value
                    headers = c.headers
                },
            )
        }
        return true
    }

    /** VixSrc: `/api/<path>` → embed page with a signed `window.masterPlaylist` → HLS master per server. */
    private suspend fun vixsrcCandidates(path: String): List<Candidate> {
        val pageHeaders = mapOf("User-Agent" to userAgent, "Referer" to "$vixsrc/$path")
        val api = jsonMapper.readTree(app.get("$vixsrc/api/$path", headers = pageHeaders, timeout = 15L).text)
        val src = api.str("src") ?: return emptyList()
        val embedUrl = vixsrc + src.replace("\\/", "/")
        val html = app.get(embedUrl, headers = pageHeaders, timeout = 15L).text
        val token = Regex("""['"]token['"]\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: return emptyList()
        val expires = Regex("""['"]expires['"]\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: return emptyList()
        val master = Regex("""url\s*:\s*['"](https?://[^'"]+/playlist/[^'"]+)['"]""").find(html)?.groupValues?.get(1)
            ?: return emptyList()
        // `h=1` (1080p) is only accepted when the page says canPlayFHD = true; otherwise it 403s.
        val fhd = Regex("""canPlayFHD\s*=\s*true""").containsMatchIn(html)
        val suffix = "token=$token&expires=$expires${if (fhd) "&h=1" else ""}&lang=en"

        val servers = Regex("""window\.streams\s*=\s*(\[.*?\]);""").find(html)?.groupValues?.get(1)
            ?.let { runCatching { jsonMapper.readTree(it) }.getOrNull() }
            ?.mapNotNull { node ->
                val url = node.str("url") ?: return@mapNotNull null
                (node.str("name") ?: "Server") to url
            }
            .orEmpty()
            .ifEmpty { listOf("Server1" to master) }

        val linkHeaders = mapOf("User-Agent" to userAgent)
        return servers.map { (label, url) ->
            val joined = url + (if (url.contains('?')) "&" else "?") + suffix
            Candidate("VixSrc ${label.replace("Server", "Server ")}", joined, "$vixsrc/", linkHeaders)
        }
    }

    /**
     * VidRift: the embed page carries `embedMeta` (playback token, and on a cache hit the Orion
     * stream list) plus `subtitleTracks`. Its stream API is fronted by Cloudflare and does not
     * answer every client, so this server is offered only when it actually returns a playlist.
     */
    private suspend fun vidriftCandidates(path: String, subtitleCallback: (SubtitleFile) -> Unit): List<Candidate> {
        val embedUrl = "$vidrift/embed/$path"
        val html = app.get(
            embedUrl,
            headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/", "Sec-Fetch-Dest" to "iframe"),
            timeout = 15L,
        ).text
        val meta = Regex("""var embedMeta\s*=\s*(\{.*?\});\s*</script>""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?.groupValues?.get(1)?.let { runCatching { jsonMapper.readTree(it) }.getOrNull() }
            ?: return emptyList()

        Regex("""var subtitleTracks\s*=\s*(\[.*?\]);""").find(html)?.groupValues?.get(1)
            ?.let { runCatching { jsonMapper.readTree(it) }.getOrNull() }
            ?.forEach { node ->
                val url = node.str("url") ?: return@forEach
                val label = node.str("label") ?: node.str("code") ?: return@forEach
                subtitleCallback(newSubtitleFile(label, if (url.startsWith("http")) url else vidrift + url))
            }

        var streams = meta.get("orionStreams")?.takeIf { it.isArray && it.size() > 0 }
        if (streams == null) {
            val token = meta.str("playbackToken") ?: return emptyList()
            val parent = meta.str("parent") ?: "flixvision.st"
            val sourceUrl = "$vidrift/api/source/$path?token=${URLEncoder.encode(token, "UTF-8")}" +
                "&provider=orion&parent=${URLEncoder.encode(parent, "UTF-8")}"
            val text = app.get(
                sourceUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to embedUrl,
                    "Accept" to "*/*",
                    "Sec-Fetch-Site" to "same-origin",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Dest" to "empty",
                ),
                timeout = 25L,
            ).text
            streams = runCatching { jsonMapper.readTree(text).get("streams") }.getOrNull()
                ?.takeIf { it.isArray && it.size() > 0 } ?: return emptyList()
        }
        val linkHeaders = mapOf("User-Agent" to userAgent)
        return streams.mapNotNull { node ->
            if (node.str("type")?.equals("hls", true) == false) return@mapNotNull null
            val url = node.str("url") ?: return@mapNotNull null
            val label = node.str("name")?.replace(" · ", " ") ?: "Orion"
            Candidate("VidRift $label", if (url.startsWith("http")) url else vidrift + url, "$vidrift/", linkHeaders)
        }
    }

    private suspend fun isPlayable(candidate: Candidate): Boolean = runCatching {
        app.get(
            candidate.url,
            headers = candidate.headers + ("Referer" to candidate.referer),
            timeout = 8L,
        ).text.trimStart().startsWith("#EXTM3U")
    }.getOrDefault(false)

    private suspend fun fetchPage(url: String): Document =
        fetchDocument(url) ?: throw ErrorLoadingException("FlixVision asked for a browser check; try again later")

    /** Returns null when the site answers with its "Quick check" browser challenge instead of the page. */
    private suspend fun fetchDocument(url: String): Document? {
        val document = app.get(url, headers = siteHeaders).document
        return document.takeUnless { it.title().trim().equals("Quick check", ignoreCase = true) }
    }

    private fun ldJson(document: Document): JsonNode? = document.select("script[type=application/ld+json]")
        .asSequence()
        .mapNotNull { runCatching { jsonMapper.readTree(it.data()) }.getOrNull() }
        .firstOrNull { it.str("@type") in setOf("Movie", "TVSeries") }

    private fun slugOf(url: String): String = url.substringAfter("/watch/", "").substringBefore('?').substringBefore('/')

    private fun imageOf(img: Element?): String? {
        if (img == null) return null
        return listOf(img.attr("data-lsrc"), img.attr("data-src"), img.attr("src"))
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { fixUrl(it) }
    }

    private fun Element.toCard(): SearchResponse? {
        val href = attr("href").takeIf { it.contains("/watch/") } ?: return null
        val url = fixUrl(href.substringBefore('?'))
        val rawTitle = (selectFirst(".bf-card__name") ?: selectFirst(".bf-card__title"))?.text()?.trim()
            ?.takeIf { it.isNotBlank() } ?: attr("title").trim()
        if (rawTitle.isBlank()) return null
        val year = Regex("""\((\d{4})\)\s*$""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val title = rawTitle
            .replace(Regex("""\s*\(\d{4}\)\s*$"""), "")
            .replace(Regex("""\s+-\s+Season\s+\d+$"""), "")
            .trim()
        val poster = imageOf(selectFirst("img"))
        val quality = CsQuality.from(selectFirst(".bf-card__qual")?.text())
        return if (slugOf(url).startsWith("tv-")) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
                this.year = year
                this.quality = quality
            }
        }
    }

    private fun JsonNode.str(key: String): String? =
        get(key)?.takeIf { !it.isNull }?.asText()?.trim()?.takeIf { it.isNotBlank() }
}
