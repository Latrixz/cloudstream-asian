package com.fourkhdhub

import com.csasian.shared.CsBackup

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * 4KHDHub (4khdhub.one) is a download-link site (sister of HDHub4u):
 *  - lists:   / and /category/{slug}/ (paged /page/{n}/), 18 `a.movie-card` per page
 *  - search:  /?s={q} (paged /page/{n}/?s={q})
 *  - detail:  movies list files as `.download-item`; series list single episodes as
 *             `.season-item` (one per season + quality variant) holding `.episode-download-item`s
 *  - every file has "Download HubCloud" / "Download HubDrive" buttons going through a shortener
 *    (greenmotors.{club,cc}) whose page hides the target in s('o','…'):
 *    base64 -> base64 -> rot13 -> base64 -> JSON {"o": base64(target)}
 *  - hubcloud.{tld}/drive/{id} -> #download (gamerxyt.com/hubcloud.php?…) -> "FSL Server" = signed R2 MKV
 *    (plus "10Gbps" and pixeldrain mirrors). hubdrive.{tld}/file/{id} links to the same hubcloud page.
 *
 * Many files are 2160p Dolby Vision / remux / TrueHD files most Android TVs can't play, so links are
 * ranked: plain 1080p/720p first (smaller first), 4K/DV/remux/huge last with quality P144.
 */
class FourKHDHubProvider : MainAPI() {
    override var mainUrl = SITE_URL
    override var name = "4KHDHub CS"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.Anime)

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val pageHeaders get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
    private val jsonMapper = ObjectMapper()

    override val mainPage = mainPageOf(
        "" to "Latest Releases",
        "category/movies" to "Movies",
        "category/series" to "Series",
        "category/hindi-movies" to "Hindi Movies",
        "category/english-movies" to "English Movies",
        "category/hindi-series" to "Hindi Series",
        "category/english-series" to "English Series",
        "category/korean-series" to "Korean Series",
        "category/anime" to "Anime",
        "category/netflix" to "Netflix",
        "category/amazon_prime_video" to "Prime Video",
    )

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.let { if (it.isEmpty()) "" else "$it/" }
        val url = if (page <= 1) "$mainUrl/$path" else "$mainUrl/${path}page/$page/"
        val document = app.get(url, headers = pageHeaders).document
        val hint = when {
            request.data.contains("anime") -> TvType.Anime
            request.data.contains("korean") -> TvType.AsianDrama
            else -> null
        }
        val items = cards(document, hint)
        return newHomePageResponse(request.name, items, items.isNotEmpty() && hasNextPage(document, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$q" else "$mainUrl/page/$page/?s=$q"
        val document = app.get(url, headers = pageHeaders).document
        val results = cards(document, null)
        return results to (results.isNotEmpty() && hasNextPage(document, page))
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
        val detailUrl = url.substringBefore('#')
        val document = app.get(detailUrl, headers = pageHeaders).document
        val rawTitle = document.selectFirst("h1.page-title")?.text()?.trim().orEmpty()
            .ifBlank { throw ErrorLoadingException("Title not found") }
        val title = rawTitle.replace(Regex("""\s*\((19|20)\d{2}\)\s*$"""), "").trim().ifBlank { rawTitle }
        val html = document.html()
        val year = Regex(""""(?:datePublished|startDate)"\s*:\s*"(\d{4})""").find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\((\d{4})\)\s*$""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: metadata(document, "Release")?.take(4)?.toIntOrNull()
        val poster = document.selectFirst(".poster-image img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val tagline = document.selectFirst(".movie-tagline")?.text()?.trim()?.ifBlank { null }
        val overview = document.select(".content-section p.mt-4").firstOrNull()?.text()?.trim()?.ifBlank { null }
        val audios = metadata(document, "Audios")?.replace(Regex("""\s*\|\s*"""), ", ")
        val plot = listOfNotNull(overview, tagline?.takeIf { overview == null }, audios?.let { "Audio: $it" })
            .joinToString("\n\n").ifBlank { null }
        val badges = document.select("span.badge-outline a").map { it.text().trim() }.filter { it.isNotBlank() }
        val tags = badges.filterNot { TECH_TAG.matches(it) }.distinct()
        val score = document.selectFirst(".imdb-score")?.text()?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }
        val actors = metadata(document, "Stars")?.let { stars ->
            Regex("""([^,(]+?)\s*(?:\(([^)]*)\))?\s*(?:,|$)""").findAll(stars).mapNotNull { m ->
                val actor = m.groupValues[1].trim().ifBlank { return@mapNotNull null }
                ActorData(Actor(actor), roleString = m.groupValues[2].trim().ifBlank { null })
            }.take(15).toList()
        }.orEmpty() + metadata(document, "Director")?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }
            ?.map { ActorData(Actor(it), roleString = "Director") }.orEmpty()
        val trailer = document.selectFirst("#trailer-btn")?.attr("data-trailer-url")
            ?.let { Regex("""/embed/([A-Za-z0-9_-]{6,})""").find(it)?.groupValues?.get(1) }
            ?.let { "https://www.youtube.com/watch?v=$it" }
        val recommended = document.select(".card-grid-small a.movie-card").mapNotNull { it.toResult(null) }
            .filter { it.url != detailUrl }.distinctBy { it.url }

        val isSeries = detailUrl.contains("-series-") || document.selectFirst(".episode-download-item") != null
        if (!isSeries) {
            if (movieFiles(document).isEmpty()) throw ErrorLoadingException("No download files on this page")
            return newMovieLoadResponse(title, detailUrl, TvType.Movie, detailUrl) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score?.let { Score.from10(it) }
                this.actors = actors.ifEmpty { null }
                this.recommendations = recommended
                addTrailer(trailer)
            }
        }

        val type = when {
            badges.any { it.equals("Anime", true) } -> TvType.Anime
            badges.any { it.equals("Korean", true) } -> TvType.AsianDrama
            else -> TvType.TvSeries
        }
        val numbers = episodeFiles(document).map { it.season to it.episode }.distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))
        if (numbers.isEmpty()) throw ErrorLoadingException("Only zip/pack downloads on this page")
        val episodes = numbers.map { (season, episode) ->
            newEpisode("$detailUrl#s=$season&e=$episode") {
                this.name = "Episode $episode"
                this.season = season
                this.episode = episode
            }
        }
        return newTvSeriesLoadResponse(title, detailUrl, type, episodes) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = score?.let { Score.from10(it) }
            this.actors = actors.ifEmpty { null }
            this.recommendations = recommended
            addTrailer(trailer)
        }
    }

    private suspend fun loadOwnLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data = detail URL (movie) or detail URL#s={season}&e={episode}
        val detailUrl = data.substringBefore('#')
        val params = data.substringAfter('#', "").split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val season = params["s"]?.toIntOrNull()
        val episode = params["e"]?.toIntOrNull()
        val document = app.get(detailUrl, headers = pageHeaders).document
        val files = if (season != null && episode != null) {
            episodeFiles(document).filter { it.season == season && it.episode == episode }.map { it.file }
        } else {
            movieFiles(document)
        }
        if (files.isEmpty()) return false

        val resolved = coroutineScope {
            files.map { file ->
                async { withTimeoutOrNull(FILE_TIMEOUT_MS) { runCatching { resolveFile(file) }.getOrNull() } }
            }.awaitAll()
        }
        val ranked = files.zip(resolved).filter { !it.second.isNullOrEmpty() }
            .sortedWith(compareBy({ rankOf(it.first).first }, { rankOf(it.first).second }))
        val seen = HashSet<String>()
        ranked.forEach { (file, streams) ->
            streams!!.filter { seen.add(it.url) }.forEach { stream ->
                callback(
                    newExtractorLink(name, linkName(file, stream.server), stream.url, ExtractorLinkType.VIDEO) {
                        quality = qualityOf(file)
                        headers = mapOf("User-Agent" to userAgent)
                    },
                )
            }
        }
        return ranked.isNotEmpty()
    }

    // ---------------------------------------------------------------- page parsing

    /** One downloadable file: its description text (resolution/codec/audio), size and audio languages. */
    private data class FileInfo(val spec: String, val size: String?, val languages: String?, val links: List<String>)
    private data class EpisodeFile(val season: Int, val episode: Int, val file: FileInfo)

    private fun movieFiles(document: Document): List<FileInfo> =
        document.select(".download-item").mapNotNull { item ->
            val header = item.selectFirst(".download-header")
            val heading = header?.selectFirst(".flex-1")?.ownText()?.trim().orEmpty()
            val fileTitle = item.selectFirst(".file-title")?.text()?.trim().orEmpty()
            if (fileTitle.endsWith(".zip", true)) return@mapNotNull null
            val headerBadges = header?.select(".badge")?.map { it.text().trim() }.orEmpty()
            val size = headerBadges.firstOrNull { SIZE.containsMatchIn(it) }
            val languages = headerBadges.firstOrNull { it.isLanguageList() }
            val links = buttonLinks(item)
            if (links.isEmpty()) null else FileInfo("$heading $fileTitle", size, languages, links)
        }

    private fun episodeFiles(document: Document): List<EpisodeFile> =
        document.select(".season-item").flatMap { group ->
            val header = group.selectFirst(".episode-header")
            val variant = header?.selectFirst(".episode-title")?.text()?.trim().orEmpty()
            val groupSeason = Regex("""S(\d{1,3})""", RegexOption.IGNORE_CASE)
                .find(header?.selectFirst(".episode-number")?.text() ?: variant)?.groupValues?.get(1)?.toIntOrNull()
            val languages = header?.select(".episode-meta .badge")?.map { it.text().trim() }
                ?.firstOrNull { it.isLanguageList() }
            group.select(".episode-download-item").mapNotNull { item ->
                val fileTitle = item.selectFirst(".episode-file-title")?.text()?.trim().orEmpty()
                val sxe = Regex("""S(\d{1,3})\s*E(\d{1,4})""", RegexOption.IGNORE_CASE).find(fileTitle)
                val episode = item.selectFirst(".badge-psa")?.text()
                    ?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                    ?: sxe?.groupValues?.get(2)?.toIntOrNull()
                    ?: return@mapNotNull null
                val season = groupSeason ?: sxe?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val size = item.selectFirst(".badge-size")?.text()?.trim()
                val links = buttonLinks(item)
                if (links.isEmpty()) null
                else EpisodeFile(season, episode, FileInfo("$variant $fileTitle", size, languages, links))
            }
        }

    /** HubCloud button first, HubDrive (same file, one more hop) as fallback. */
    private fun buttonLinks(item: Element): List<String> {
        val anchors = item.select("a[href]").filter { it.text().contains("Download", true) }
        val cloud = anchors.filter { it.text().contains("HubCloud", true) }
        val drive = anchors.filter { it.text().contains("HubDrive", true) }
        return (cloud + drive).map { it.attr("href").trim() }.filter { it.startsWith("http") }.distinct()
    }

    private fun metadata(document: Document, label: String): String? =
        document.select(".metadata-item").firstOrNull {
            it.selectFirst(".metadata-label")?.text()?.trim()?.removeSuffix(":").equals(label, true)
        }?.selectFirst(".metadata-value")?.text()?.trim()?.ifBlank { null }

    private fun String.isLanguageList(): Boolean =
        !SIZE.containsMatchIn(this) && LANGUAGE.containsMatchIn(this)

    private fun cards(document: Document, hint: TvType?): List<SearchResponse> =
        document.select(".card-grid a.movie-card").ifEmpty { document.select("a.movie-card") }
            .mapNotNull { it.toResult(hint) }.distinctBy { it.url }

    /** The poster badge, from the card's own quality badges (4K/DV/HDR) and format tags (2160p/1080p). */
    private fun cardQuality(card: Element): SearchQuality? {
        val text = (card.select(".quality-badge").map { it.text() } + card.select(".movie-card-format").map { it.text() })
            .joinToString(" ").lowercase()
        return when {
            "4k" in text || "2160" in text -> SearchQuality.FourK
            "hdr" in text || "dv" in text.split(' ') -> SearchQuality.HDR
            "1080" in text || "720" in text -> SearchQuality.HD
            else -> null
        }
    }

    private fun Element.toResult(hint: TvType?): SearchResponse? {
        val href = attr("href").trim().ifBlank { return null }
        val url = fixUrl(href)
        val title = selectFirst(".movie-card-title")?.text()?.trim()?.ifBlank { null }
            ?: attr("aria-label").removeSuffix(" details").trim().ifBlank { return null }
        val poster = selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
        val year = selectFirst(".movie-card-meta")?.text()?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
        val badge = cardQuality(this)
        return if (url.contains("-series-")) {
            newTvSeriesSearchResponse(title, url, hint ?: TvType.TvSeries) { posterUrl = poster; this.year = year; quality = badge }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster; this.year = year; quality = badge }
        }
    }

    private fun hasNextPage(document: Document, page: Int): Boolean =
        document.select("a[href]").any { it.attr("href").contains("/page/${page + 1}/") }

    // ---------------------------------------------------------------- link resolving

    private data class Stream(val server: String, val url: String)

    /** Shortener -> HubCloud (or HubDrive -> HubCloud) -> gamerxyt -> direct file URLs. */
    private suspend fun resolveFile(file: FileInfo): List<Stream>? {
        for (link in file.links) {
            val target = runCatching { unshorten(link) }.getOrNull() ?: continue
            val streams = runCatching { hubCloudStreams(target) }.getOrNull()
            if (!streams.isNullOrEmpty()) return streams
        }
        return null
    }

    /** Returns the HubCloud/HubDrive URL behind a shortener link (or the link itself if it already is one). */
    private suspend fun unshorten(url: String): String? {
        if (isHost(url, HUBCLOUD_PREFIX) || isHost(url, HUBDRIVE_PREFIX)) return url
        val response = app.get(url, headers = mapOf("User-Agent" to userAgent))
        val finalUrl = response.url
        if (isHost(finalUrl, HUBCLOUD_PREFIX) || isHost(finalUrl, HUBDRIVE_PREFIX)) return finalUrl
        val packed = Regex("""s\(\s*'o'\s*,\s*'([A-Za-z0-9+/=_-]+)'""").find(response.text)?.groupValues?.get(1)
            ?: return null
        val json = b64(rot13(b64(b64(packed))))
        val target = jsonMapper.readTree(json).path("o").asText("").ifBlank { return null }
        return b64(target).trim().takeIf { it.startsWith("http") }
    }

    private suspend fun hubCloudStreams(url: String): List<Stream> {
        var page = url
        if (isHost(page, HUBDRIVE_PREFIX)) {
            val doc = app.get(page, headers = mapOf("User-Agent" to userAgent)).document
            page = doc.select("a[href]").map { it.attr("href") }.firstOrNull { isHost(it, HUBCLOUD_PREFIX) }
                ?: return emptyList()
        }
        // hubcloud.{tld}/drive/{id} -> "Generate Direct Download Link" (gamerxyt.com/hubcloud.php?...)
        val drive = app.get(page, headers = mapOf("User-Agent" to userAgent))
        val generator = drive.document.selectFirst("a#download")?.attr("href")?.takeIf { it.startsWith("http") }
            ?: Regex("""var\s+url\s*=\s*'([^']+)'""").find(drive.text)?.groupValues?.get(1)
            ?: return emptyList()
        val origin = URI(drive.url).let { "${it.scheme}://${it.host}/" }
        val links = app.get(generator, headers = mapOf("User-Agent" to userAgent, "Referer" to origin)).document
            .select("a[href]")
        val fsl = links.filter { it.text().contains("FSL", true) }.map { Stream("", it.attr("href")) }
        val fast = links.filter { it.text().contains("10Gbps", true) }.map { Stream("10Gbps", it.attr("href")) }
        val pixel = links.mapNotNull { a ->
            val id = Regex("""pixeldrain\.[a-z]+/(?:u|file|api/file)/([A-Za-z0-9]+)""").find(a.attr("href"))
                ?.groupValues?.get(1) ?: return@mapNotNull null
            Stream("Pixel", "https://pixeldrain.dev/api/file/$id?download")
        }
        // The R2 "FSL" link is a real signed MKV URL; the others are fallbacks for when it is missing.
        return listOf(fsl, fast, pixel).firstOrNull { it.isNotEmpty() }
            ?.filter { it.url.startsWith("http") }?.distinctBy { it.url }.orEmpty()
    }

    private fun isHost(url: String, prefix: String): Boolean =
        runCatching { URI(url).host?.lowercase()?.removePrefix("www.")?.startsWith(prefix) == true }.getOrDefault(false)

    private fun b64(text: String): String =
        String(android.util.Base64.decode(text.trim(), android.util.Base64.DEFAULT), Charsets.UTF_8)

    private fun rot13(text: String): String = buildString(text.length) {
        for (c in text) append(
            when (c) {
                in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                else -> c
            },
        )
    }

    // ---------------------------------------------------------------- ranking / naming

    private fun resolution(spec: String): Int? =
        Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(spec)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (Regex("""\b(4K|UHD)\b""", RegexOption.IGNORE_CASE).containsMatchIn(spec)) 2160 else null

    private fun sizeGb(size: String?): Double? {
        val m = Regex("""([\d.]+)\s*([GMT])B""", RegexOption.IGNORE_CASE).find(size ?: return null) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2].uppercase()) {
            "T" -> value * 1024
            "M" -> value / 1024
            else -> value
        }
    }

    private fun isDolbyVision(spec: String) = Regex("""\b(DV|DoVi|Dolby[ .]?Vision)\b""", RegexOption.IGNORE_CASE).containsMatchIn(spec)
    private fun isHdr(spec: String) = Regex("""\bHDR(10\+?)?\b""", RegexOption.IGNORE_CASE).containsMatchIn(spec)
    private fun isRemux(spec: String) = spec.contains("REMUX", true)
    private fun isHevc(spec: String) = Regex("""HEVC|[xh]\.?265""", RegexOption.IGNORE_CASE).containsMatchIn(spec)
    private fun isAvc(spec: String) = Regex("""\bAVC\b|[xh]\.?264""", RegexOption.IGNORE_CASE).containsMatchIn(spec)
    private fun isAv1(spec: String) = Regex("""\bAV1\b""", RegexOption.IGNORE_CASE).containsMatchIn(spec)
    private fun isTrueHd(spec: String) = Regex("""TrueHD|DTS[- .]?(HD|X)""", RegexOption.IGNORE_CASE).containsMatchIn(spec)

    /** 4K, Dolby Vision, remux or > 20 GB: most TVs/players can't cope, so these go last. */
    private fun isHeavy(file: FileInfo): Boolean {
        val res = resolution(file.spec) ?: 0
        return res >= 1440 || isDolbyVision(file.spec) || isRemux(file.spec) || (sizeGb(file.size) ?: 0.0) > HEAVY_GB
    }

    private fun qualityOf(file: FileInfo): Int =
        if (isHeavy(file)) Qualities.P144.value
        else when (resolution(file.spec)) {
            1080 -> Qualities.P1080.value
            720 -> Qualities.P720.value
            480 -> Qualities.P480.value
            360 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }

    /**
     * Sort key (lower = earlier): tier, then a tie-breaker.
     * 0: light H.264/HEVC 1080p/720p (<= 10 GB), 1: same but 10-20 GB, 2: AV1, 3: TrueHD/DTS-HD audio,
     * 4: heavy (4K/DV/remux/huge). Within a tier: 1080p before 720p, H.264 before HEVC, smaller first.
     */
    private fun rankOf(file: FileInfo): Pair<Int, Double> {
        val size = sizeGb(file.size) ?: 50.0
        if (isHeavy(file)) return 4 to size
        val tier = when {
            isTrueHd(file.spec) -> 3
            isAv1(file.spec) -> 2
            size > LIGHT_GB -> 1
            else -> 0
        }
        val resPenalty = when (resolution(file.spec)) {
            1080 -> 0.0
            720 -> 1000.0
            null -> 3000.0
            else -> 2000.0
        }
        val codecPenalty = if (isAvc(file.spec)) 0.0 else 100.0
        return tier to resPenalty + codecPenalty + size
    }

    /** e.g. "4KHDHub CS 1080p x264 · 2.3 GB · Hindi+English". */
    private fun linkName(file: FileInfo, server: String): String {
        val spec = file.spec
        val hints = listOfNotNull(
            resolution(spec)?.let { if (it >= 2160) "4K" else "${it}p" },
            "REMUX".takeIf { isRemux(spec) },
            "DV".takeIf { isDolbyVision(spec) },
            "HDR".takeIf { isHdr(spec) && !isDolbyVision(spec) },
            when {
                isAv1(spec) -> "AV1"
                isHevc(spec) -> "HEVC"
                isAvc(spec) -> "x264"
                else -> null
            },
            "TrueHD".takeIf { isTrueHd(spec) },
        ).joinToString(" ")
        val languages = file.languages?.split(',', '|', '/')?.map { it.trim() }?.filter { it.isNotBlank() }
            ?.joinToString("+")
        return listOf("$name $hints".trim(), file.size?.trim(), languages, server.ifBlank { null })
            .filterNot { it.isNullOrBlank() }.joinToString(" · ")
    }

    companion object {
        // Domains rotate; keep them here.
        private const val SITE_URL = "https://4khdhub.one"
        private const val HUBCLOUD_PREFIX = "hubcloud."
        private const val HUBDRIVE_PREFIX = "hubdrive."

        private const val FILE_TIMEOUT_MS = 25_000L
        private const val HEAVY_GB = 20.0
        private const val LIGHT_GB = 10.0

        private val SIZE = Regex("""\d\s*[GMT]B\b""", RegexOption.IGNORE_CASE)
        private val LANGUAGE = Regex(
            """Hindi|English|Tamil|Telugu|Korean|Japanese|Chinese|Spanish|French|Malayalam|Kannada|Bengali|Marathi|Punjabi|Urdu|Thai|Multi""",
            RegexOption.IGNORE_CASE,
        )
        /** Badge links that describe the release rather than the title. */
        private val TECH_TAG = Regex(
            """\d{3,4}p|4K|10Bit|DV.*|HDR.*|SDR|HEVC|H\.?26[45]|x26[45]|AV1|REMUX|BluRay|WEB-?DL|WEBRip|HDRip|HDTV|Dual Language|Multi Audio|Movies|Series""",
            RegexOption.IGNORE_CASE,
        )
    }
}
