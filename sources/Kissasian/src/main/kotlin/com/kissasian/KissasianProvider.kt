package com.kissasian

import com.csasian.shared.CsBackup

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.MixDrop
import com.lagradost.cloudstream3.extractors.Vidmoly
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The app's Vidmoly extractor only knows vidmoly.net/.me/.to/.biz; the site embeds vidmoly.org. */
class VidmolyOrg : Vidmoly() {
    override val mainUrl = "https://vidmoly.org"
}

/** zumivids links mixdrop.top (redirects to mxdrop.click), which no built-in MixDrop mirror covers. */
class MixDropTop : MixDrop() {
    override var mainUrl = "https://mixdrop.top"
}

class KissasianProvider : MainAPI() {
    override var mainUrl = "https://kissasian.ng"
    override var name = "Kissasian"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie, TvType.TvSeries)

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val siteHeaders = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")

    private val zumivids = "https://zumivids.com"

    // data = "home|<section>" for the homepage sections, otherwise the /series/ filter query.
    override val mainPage = mainPageOf(
        "home|latest" to "Latest Episodes",
        "home|hot" to "Hot Series Update",
        "order=update" to "Recently Updated Dramas",
        "order=popular" to "Popular Dramas",
        "order=latest" to "Newly Added Dramas",
        // The site marks nearly every title "Ongoing" and its "completed" filter is empty.
        "status=ongoing&order=popular" to "Ongoing Dramas",
        "type=movie&order=update" to "Movies",
        "country%5B%5D=south-korea&order=update" to "Korean Dramas",
        "country%5B%5D=china&order=update" to "Chinese Dramas",
        "country%5B%5D=japan&order=update" to "Japanese Dramas",
        "country%5B%5D=thailand&order=update" to "Thai Dramas",
        "country%5B%5D=taiwan&order=update" to "Taiwanese Dramas",
        "country%5B%5D=philippines&order=update" to "Filipino",
        "order=rating" to "Top Rated",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.startsWith("home|")) {
            val section = request.data.substringAfter('|')
            if (section == "hot" && page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val url = if (page > 1) "$mainUrl/page/$page/" else "$mainUrl/"
            val document = app.get(url, headers = siteHeaders).document
            val heading = if (section == "hot") "Hot Series" else "Latest Release"
            val box = document.select("div.bixbox").firstOrNull { box ->
                box.selectFirst(".releases h2, .releases h3")?.text()?.contains(heading, true) == true
            }
            val items = box?.select("article.bs")?.mapNotNull { it.toCard() }.orEmpty()
            val hasNext = section == "latest" && document.selectFirst(".hpage a.r, a.next.page-numbers") != null
            return newHomePageResponse(request.name, items, hasNext)
        }
        val document = app.get("$mainUrl/series/?${request.data}&page=$page", headers = siteHeaders).document
        val items = document.select(".listupd article.bs").mapNotNull { it.toCard() }
        val hasNext = document.selectFirst(".hpage a.r") != null
        return newHomePageResponse(request.name, items, hasNext)
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
        val url = if (page > 1) "$mainUrl/page/$page/?s=$q" else "$mainUrl/?s=$q"
        val document = app.get(url, headers = siteHeaders).document
        val results = document.select(".listupd article.bs").mapNotNull { it.toCard() }.distinctBy { it.url }
        return results to (document.selectFirst("a.next.page-numbers") != null)
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
        var document = app.get(url, headers = siteHeaders).document
        var showUrl = url
        if (!url.contains("/series/")) {
            // Episode (or movie) page: jump to its drama page, which has the full episode list.
            val seriesLink = document.selectFirst(".naveps .nvsc a[href*='/series/'], .headlist .thumb a[href*='/series/']")
                ?.attr("abs:href")
            if (!seriesLink.isNullOrBlank()) {
                document = app.get(seriesLink, headers = siteHeaders).document
                showUrl = seriesLink
            } else {
                return loadFromEpisodePage(url, document)
            }
        }

        val info = document.selectFirst(".bigcontent .infox") ?: throw ErrorLoadingException("Drama info not found")
        val title = info.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
            .ifBlank { throw ErrorLoadingException("Drama title not found") }
        val fields = info.select(".spe span").associate { span ->
            span.selectFirst("b")?.text()?.trim()?.removeSuffix(":")?.lowercase().orEmpty() to span
        }
        fun field(key: String) = fields[key]?.text()?.substringAfter(':')?.trim()?.takeIf { it.isNotBlank() }

        val poster = imageOf(document.selectFirst(".bigcontent .thumb img"))
        val typeText = field("type").orEmpty()
        val isMovie = typeText.equals("movie", true)
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(field("released").orEmpty())?.value?.toIntOrNull()
        val status = when (field("status")?.lowercase()) {
            "ongoing", "upcoming" -> ShowStatus.Ongoing
            "completed" -> ShowStatus.Completed
            else -> null
        }
        val rating = document.selectFirst("meta[itemprop=ratingValue]")?.attr("content")?.toDoubleOrNull()
            ?: Regex("""Rating\s*([\d.]+)""").find(document.select(".rating strong").text())?.groupValues?.get(1)?.toDoubleOrNull()
        val plot = document.select(".synp .entry-content p").map { it.text().trim() }
            .filter { it.isNotBlank() && !it.startsWith("(Source:") }
            .joinToString("\n\n").ifBlank { null }
        val tags = (
            info.select(".genxed a").map { it.text() } +
                (fields["country"]?.select("a")?.map { it.text() } ?: emptyList())
            ).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val actors = (fields["casts"]?.select("a")?.map { it.text().trim() } ?: emptyList())
            .filter { it.isNotBlank() }.distinct()
            .map { ActorData(Actor(it)) }
        val duration = field("duration")?.let { parseDuration(it) }
        val recommendations = document.select("article.bs").mapNotNull { it.toCard() }.filter { it.url != showUrl }

        val episodes = document.select(".eplister li a").mapIndexedNotNull { position, a ->
            val href = a.attr("abs:href").ifBlank { return@mapIndexedNotNull null }
            val num = a.selectFirst(".epl-num")?.text()?.trim().orEmpty()
            val epTitle = a.selectFirst(".epl-title")?.text()?.trim().orEmpty()
            EpisodeInfo(href, epTitle, episodeNumber(num, epTitle, href), position, a.selectFirst(".epl-date")?.text()?.trim())
        }.distinctBy { it.url }

        if (isMovie) {
            val movieUrl = episodes.firstOrNull()?.url ?: throw ErrorLoadingException("No video for this movie")
            return newMovieLoadResponse(title, showUrl, TvType.Movie, movieUrl) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = rating?.let { Score.from10(it) }
                this.actors = actors
                this.duration = duration
                this.recommendations = recommendations
            }
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("No episodes yet")

        return newTvSeriesLoadResponse(title, showUrl, TvType.AsianDrama, toEpisodes(episodes)) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.showStatus = status
            this.score = rating?.let { Score.from10(it) }
            this.actors = actors
            this.duration = duration
            this.recommendations = recommendations
        }
    }

    /** Fallback when an episode page has no drama link: use its sidebar episode list. */
    private suspend fun loadFromEpisodePage(url: String, document: Document): LoadResponse {
        val heading = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val title = heading.replace(Regex("""\s+(Episode\s+\d+.*|Full\s+Movie)$""", RegexOption.IGNORE_CASE), "")
            .ifBlank { heading }
        val poster = imageOf(document.selectFirst(".headlist .thumb img, .episodelist img"))
        if (heading.contains("Full Movie", true)) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) { posterUrl = poster }
        }
        val list = document.select(".episodelist li a").mapIndexed { position, a ->
            val href = a.attr("abs:href")
            val epTitle = a.selectFirst("h3")?.text()?.trim().orEmpty()
            val num = Regex("""Eps\s*(\d+)""").find(a.selectFirst("span")?.text().orEmpty())?.groupValues?.get(1).orEmpty()
            EpisodeInfo(href, epTitle, episodeNumber(num, epTitle, href), position, null)
        }.filter { it.url.isNotBlank() }.distinctBy { it.url }
            .ifEmpty { listOf(EpisodeInfo(url, heading, episodeNumber("", heading, url), 0, null)) }
        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, toEpisodes(list)) { posterUrl = poster }
    }

    private data class EpisodeInfo(val url: String, val title: String, val number: Int?, val position: Int, val date: String?)

    /** The site lists episodes newest first and not always strictly ordered; sort ascending. */
    private fun toEpisodes(list: List<EpisodeInfo>): List<Episode> =
        list.sortedWith(compareBy<EpisodeInfo>({ it.number ?: Int.MAX_VALUE }, { -it.position }))
            .map { info ->
                newEpisode(info.url) {
                    name = info.title.ifBlank { info.number?.let { "Episode $it" } }
                    episode = info.number
                    info.date?.let { addDate(it, "MMMM d, yyyy") }
                }
            }

    private fun episodeNumber(num: String, title: String, url: String): Int? =
        Regex("""^\d+""").find(num.trim())?.value?.toIntOrNull()
            ?: Regex("""Episode\s+(\d+)""", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""-episode-(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()

    private suspend fun loadOwnLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data, headers = siteHeaders).document

        // Every <option> is a base64-encoded <iframe>; the default player iframe is also on the page.
        val optionEmbeds: List<String> = document.select("option[value]").mapNotNull { decodeIframe(it.attr("value")) }
        val playerIframe: Element? = document.selectFirst("#pembed iframe")
        val playerEmbed: String = playerIframe?.attr("src").orEmpty().ifBlank { playerIframe?.attr("data-src").orEmpty() }
        val embeds = (optionEmbeds + playerEmbed)
            .filter { it.isNotBlank() }.map { fixUrl(it) }.filter { it.startsWith("http") }.distinct()

        val hosts = mutableListOf<String>()
        var hasZumivids = false
        for (embed in embeds) {
            when {
                embed.contains("zumivids.") -> {
                    hasZumivids = true
                    hosts += zumividsHosts(embed)
                }
                // dramavideo.se hides its player in an encoded script blob and AES-encrypts the
                // video id: deliberately obfuscated against third-party players, so it is skipped.
                embed.contains("dramavideo.") -> Unit
                else -> hosts += embed
            }
        }
        // Episodes whose only server is dramavideo.se are usually mirrored on zumivids under
        // "{drama-slug}-ep-{n}". Try that mirror when the page didn't link zumivids itself.
        if (!hasZumivids) {
            guessZumividsUrl(document, data)?.let { hosts += zumividsHosts(it) }
        }

        val found = AtomicBoolean(false)
        hosts.distinct().amap { host ->
            if (resolveHost(host, subtitleCallback, callback)) found.set(true)
        }
        return found.get()
    }

    private fun decodeIframe(value: String): String? {
        if (value.length < 16) return null
        val html = runCatching {
            String(Base64.getDecoder().decode(value.trim().padEnd((value.trim().length + 3) / 4 * 4, '=')))
        }.getOrNull() ?: return null
        val iframe = Jsoup.parse(html).selectFirst("iframe") ?: return null
        return iframe.attr("src").ifBlank { iframe.attr("data-src") }.takeIf { it.isNotBlank() }
    }

    private suspend fun zumividsHosts(url: String): List<String> {
        val response = runCatching { app.get(url, headers = siteHeaders) }.getOrNull() ?: return emptyList()
        if (response.code != 200) return emptyList()
        val doc = response.document
        return (doc.select("[data-video]").map { it.attr("data-video") } +
            doc.select("iframe").map { it.attr("src").ifBlank { it.attr("data-src") } })
            .map { it.trim() }
            .filter { it.startsWith("http") && !it.contains("zumivids.") }
            .distinct()
    }

    private fun guessZumividsUrl(document: Document, pageUrl: String): String? {
        val seriesUrl = document.selectFirst(".naveps .nvsc a[href*='/series/'], .headlist .thumb a[href*='/series/']")
            ?.attr("abs:href") ?: return null
        val slug = Regex("""/series/([^/]+)""").find(seriesUrl)?.groupValues?.get(1) ?: return null
        val heading = document.selectFirst("h1.entry-title")?.text().orEmpty()
        val number = episodeNumber("", heading, pageUrl) ?: return null
        return "$zumivids/embed/$slug-ep-$number.html"
    }

    /** Built-in extractor first; host-specific fallbacks only if it produced nothing. */
    private suspend fun resolveHost(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val referer = "$zumivids/"
        val links = AtomicInteger(0)
        val counting: (ExtractorLink) -> Unit = { links.incrementAndGet(); callback(it) }
        runCatching { loadExtractor(url, referer, subtitleCallback, counting) }
        if (links.get() > 0) return true

        when {
            url.contains("vidmoly.") -> {
                runCatching { VidmolyOrg().getUrl(url, referer, subtitleCallback, counting) }
                if (links.get() == 0) runCatching { vidmolyFallback(url, referer, subtitleCallback, counting) }
            }
            url.contains("mixdrop.") || url.contains("mxdrop.") ->
                runCatching { MixDropTop().getUrl(url, referer, subtitleCallback, counting) }
            url.contains("streamtape.") ->
                runCatching { streamtapeFallback(url, referer, counting) }
        }
        return links.get() > 0
    }

    private suspend fun vidmolyFallback(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val embed = url.replace("/w/", "/embed-")
        val origin = Regex("""^https?://[^/]+""").find(embed)?.value ?: "https://vidmoly.org"
        val html = app.get(
            embed,
            headers = mapOf("User-Agent" to userAgent, "Referer" to referer, "Sec-Fetch-Dest" to "iframe"),
        ).text
        val sources = Regex("""sources\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1).orEmpty()
        Regex("""file\s*:\s*['"]([^'"]+)['"]""").findAll(sources).map { it.groupValues[1] }.distinct().forEach { file ->
            callback(
                newExtractorLink("Vidmoly", "Vidmoly", file, if (file.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = "$origin/"
                    quality = Qualities.Unknown.value
                },
            )
        }
        Regex("""\{\s*file\s*:\s*['"]([^'"]+)['"]\s*,\s*label\s*:\s*['"]([^'"]*)['"]\s*,\s*kind\s*:\s*['"](?:captions|subtitles)['"]""")
            .findAll(html).forEach { m ->
                subtitleCallback(newSubtitleFile(m.groupValues[2].ifBlank { "English" }, fixUrlFrom(origin, m.groupValues[1])))
            }
    }

    /** Streamtape builds the link as  '//st' + ('xcdreamtape.com/get_video?...').substring(a).substring(b). */
    private suspend fun streamtapeFallback(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, headers = mapOf("User-Agent" to userAgent, "Referer" to referer)).text
        val line = Regex("""botlink'\)\.innerHTML\s*=\s*([^\n;]+)""").findAll(html).lastOrNull()?.groupValues?.get(1)
            ?: return
        val head = Regex("""^\s*'([^']*)'""").find(line)?.groupValues?.get(1) ?: return
        var tail = Regex("""\(\s*'([^']*)'\s*\)""").find(line)?.groupValues?.get(1) ?: return
        Regex("""\.substring\((\d+)\)""").findAll(line).forEach { tail = tail.drop(it.groupValues[1].toInt()) }
        val video = "https:$head$tail&stream=1"
        callback(
            newExtractorLink("StreamTape", "StreamTape", video, ExtractorLinkType.VIDEO) {
                this.referer = "https://streamtape.com/"
                quality = Qualities.Unknown.value
            },
        )
    }

    private fun fixUrlFrom(origin: String, url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        else -> "$origin/${url.trimStart('/')}"
    }

    private fun parseDuration(text: String): Int? {
        val hours = Regex("""(\d+)\s*hr""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("""(\d+)\s*min""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return (hours * 60 + minutes).takeIf { it > 0 }
    }

    private fun imageOf(img: Element?): String? {
        if (img == null) return null
        return listOf(img.attr("data-src"), img.attr("data-lazy-src"), img.attr("src"))
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { fixUrl(it) }
    }

    private fun Element.toCard(): SearchResponse? {
        val link = selectFirst(".bsx > a, a[href]") ?: return null
        val href = link.attr("abs:href").ifBlank { return null }
        if (!href.startsWith(mainUrl) || href.contains("/random") || href.contains("list-mode")) return null
        val title = (link.attr("title").ifBlank { selectFirst("h2")?.text().orEmpty() }).trim()
        if (title.isBlank()) return null
        val poster = imageOf(selectFirst("img"))
        val isMovie = selectFirst(".typez")?.text()?.trim()?.equals("Movie", true) == true
        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { posterUrl = poster }
        }
    }
}
