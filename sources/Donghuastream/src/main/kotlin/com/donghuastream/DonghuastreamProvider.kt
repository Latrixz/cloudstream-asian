package com.donghuastream

import com.csasian.shared.CsSearch
import com.csasian.shared.CsCategories
import com.csasian.shared.CsBrowse
import com.csasian.shared.CsBackup

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Donghuastream (donghuastream.org) is a WordPress site on an anime theme:
 *  - lists:   /anime/?order={update|popular|latest|title}&status=..&type=..&page=N  (article.bs cards)
 *  - search:  /?s={query}&paged=N
 *  - detail:  /anime/{slug}/  (info in .infox, episodes newest-first in .eplister li a -> episode pages)
 *  - episode: /{slug}-episode-N-.../  servers are base64-encoded <iframe> tags in `select.mirror option`
 * Players seen: Rumble (embed page carries the HLS playlist up to 4K plus VTT subtitle tracks),
 * ok.ru, Dailymotion and streamplay. Rumble is ours; the rest go through CloudStream's extractors.
 */
class DonghuastreamProvider : MainAPI() {
    override var mainUrl = "https://donghuastream.org"
    override var name = "Donghuastream CS"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
    private val pageHeaders get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
    private val jsonMapper = ObjectMapper()

    /**
     * Category browsing (shared/CsBrowse.kt) with the site's own /anime/ filters: genre, status and year
     * (its "season"), latest added first, then last updated and popular. One section: the site is all
     * donghua, and its type and sub/dub filters are near-empty (almost everything is ONA, sub). A search
     * inside it is the site's search (it has no filters).
     */
    private val browse = CsBrowse(
        this, name,
        listOf(
            CsBrowse.Section(
                "donghua", "国漫",
                listOf(
                    CsCategories.Filter(
                        "genre", "类型 Genre",
                        listOf(
                            "action" to "Action", "adventure" to "Adventure", "another-world" to "Another World",
                            "assassin" to "Assassin", "beast" to "Beast", "drama" to "Drama", "fantasy" to "Fantasy",
                            "funny" to "Funny", "game" to "Game", "martial-arts" to "Martial Arts", "monsters" to "Monsters",
                            "mystery" to "Mystery", "reincarnated" to "Reincarnated", "romance" to "Romance", "sci-fi" to "Sci-Fi",
                            "slice-of-life" to "Slice of Life", "supernatural" to "Supernatural", "swords-fight" to "Swords Fight",
                            "thriller" to "Thriller", "vengeance" to "Vengeance",
                        ).map { (value, label) -> CsCategories.Option(value, label) },
                    ),
                    CsCategories.Filter(
                        "status", "状态 Status",
                        listOf("ongoing" to "Ongoing", "completed" to "Completed", "upcoming" to "Upcoming", "hiatus" to "Hiatus")
                            .map { (value, label) -> CsCategories.Option(value, label) },
                    ),
                    CsCategories.Filter("year", "年份 Year", CsBrowse.years(10)),
                ),
                listOf(
                    CsCategories.Option("latest", "最新 Newest"),
                    CsCategories.Option("update", "最近更新 Last updated"),
                    CsCategories.Option("popular", "热门 Popular"),
                ),
                english = "Donghua", glyph = "🐉",
            ),
        ),
        popularSort = "popular",
    ) { _, filters, query, page ->
        if (query.isNotBlank()) return@CsBrowse searchOwn(query, page)
        val params = buildString {
            filters["genre"]?.let { append("genre%5B%5D=").append(it).append('&') }
            filters["status"]?.let { append("status=").append(it).append('&') }
            filters["year"]?.let { append("season%5B%5D=").append(it).append('&') }
            append("order=").append(filters[CsCategories.SORT] ?: "latest")
        }
        val document = getPage("$mainUrl/anime/?$params&page=$page")
        val items = cards(document.selectFirst(".listupd"))
        items to (items.isNotEmpty() && document.selectFirst(".hpage a.r, .pagination a.next, a.r[href*=page=]") != null)
    }

    override val mainPage = browse.mainPage(mainPageOf(
        "order=update" to "Latest Update",
        "order=popular" to "Popular",
        "order=latest" to "New Series",
        "status=ongoing&order=popular" to "Ongoing",
        "status=completed&order=popular" to "Completed",
        "type=movie&order=update" to "Movies",
    ))

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        browse.rowPage(request) ?: com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    // Categories SkyStream Dev shows itself (shared/CsCategories.kt); the engine finds these by name.
    fun csCategories(): String = browse.categoriesJson()

    fun csCategoryPage(categoryId: String, filtersJson: String, query: String, page: Int): SearchResponseList =
        browse.categoryPage(categoryId, filtersJson, query, page)

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        val document = getPage("$mainUrl/anime/?${request.data}&page=$page")
        val items = cards(document.selectFirst(".listupd"))
        val hasNext = document.selectFirst(".hpage a.r, .pagination a.next, a.r[href*=page=]") != null
        return newHomePageResponse(request.name, items, items.isNotEmpty() && hasNext)
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
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = getPage("$mainUrl/?s=$encoded&paged=$page")
        val results = cards(document.selectFirst(".listupd"))
        val hasNext = document.selectFirst(".pagination a.next, .hpage a.r") != null
        return results to (results.isNotEmpty() && hasNext)
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
        val document = getPage(url)
        val detailUrl = document.selectFirst("link[rel=canonical]")?.attr("href")?.takeIf { it.contains("/anime/") }
            ?: url
        val title = document.selectFirst(".infox h1.entry-title, h1.entry-title")?.text()?.trim()
            ?.ifBlank { null } ?: throw ErrorLoadingException("Title not found")
        val poster = imageOf(document.selectFirst(".thumb img"))
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val info = { label: String ->
            document.select(".info-content .spe span").firstOrNull {
                it.selectFirst("b")?.text()?.trim()?.removeSuffix(":")?.equals(label, true) == true
            }?.let { span -> span.text().substringAfter(':').trim().ifBlank { null } }
        }
        val kind = info("Type")
        val status = info("Status")
        val year = info("Released")?.let { YEAR.find(it)?.value?.toIntOrNull() }
            ?: info("Season")?.let { YEAR.find(it)?.value?.toIntOrNull() }
        val duration = info("Duration")?.let { Regex("""(\d+)\s*min""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val studio = info("Studio")
        val tags = document.select(".genxed a").map { it.text().trim() }
            .filter { it.isNotBlank() && !it.equals("MY FAVOURITE", true) && !it.equals("Popular", true) }
            .distinct()
        val score = document.selectFirst(".rating meta[itemprop=ratingValue]")?.attr("content")
            ?.toDoubleOrNull()?.takeIf { it > 0 }
        val altNames = document.selectFirst(".infox .alter")?.text()?.trim()?.trimEnd(',')?.trim()?.ifBlank { null }
        // The first paragraph usually repeats the alternative names; keep the story paragraphs.
        val story = document.select(".entry-content[itemprop=description] p").map { it.text().trim() }
            .filter { it.isNotBlank() && it != altNames && !it.equals(altNames?.let { n -> "$n ," }, true) }
            .joinToString("\n\n").ifBlank { null }
        val plot = listOfNotNull(
            story,
            altNames?.let { "Also known as: $it" },
            studio?.let { "Studio: $it" },
        ).joinToString("\n\n").ifBlank { null }

        val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH)
        val isoDate = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        // The list is newest first; play order is oldest first.
        val entries = document.select(".eplister li a[href]").reversed().mapNotNull { link ->
            val href = fixUrl(link.attr("href")).ifBlank { return@mapNotNull null }
            val numText = link.selectFirst(".epl-num")?.text()?.trim().orEmpty()
            val epTitle = link.selectFirst(".epl-title")?.text()?.trim()
                ?.replace(Regex("""\s*Multiple Subtitles\s*$""", RegexOption.IGNORE_CASE), "")?.ifBlank { null }
            val date = link.selectFirst(".epl-date")?.text()?.trim()
                ?.let { runCatching { dateFormat.parse(it) }.getOrNull() }
                ?.let { isoDate.format(it) }
            Entry(href, Regex("""\d+""").find(numText)?.value?.toIntOrNull(), numText, epTitle, date)
        }.distinctBy { it.url }
        if (entries.isEmpty()) throw ErrorLoadingException("No episodes available yet")

        val isMovieType = kind?.contains("Movie", true) == true
        val recommended = cards(
            document.select(".bixbox").firstOrNull { it.selectFirst(".releases h3")?.text()?.contains("Recommended", true) == true }
                ?.selectFirst(".listupd")
        ).filter { it.url != detailUrl }

        return if (isMovieType && entries.size == 1) {
            newMovieLoadResponse(title, detailUrl, TvType.AnimeMovie, entries[0].url) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = duration
                this.score = score?.let { Score.from10(it) }
                this.recommendations = recommended
            }
        } else {
            val episodes = entries.mapIndexed { index, entry ->
                newEpisode(entry.url) {
                    episode = if (isMovieType) index + 1 else entry.number ?: (index + 1)
                    name = entry.title ?: entry.label.ifBlank { null } ?: "Episode ${index + 1}"
                    addDate(entry.date)
                }
            }
            newAnimeLoadResponse(title, detailUrl, if (isMovieType) TvType.AnimeMovie else TvType.Anime) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = duration
                this.score = score?.let { Score.from10(it) }
                this.recommendations = recommended
                showStatus = when {
                    status?.contains("Completed", true) == true -> ShowStatus.Completed
                    status?.contains("Ongoing", true) == true -> ShowStatus.Ongoing
                    else -> null
                }
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    private suspend fun loadOwnLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data = full episode page URL, e.g. https://donghuastream.org/{slug}-episode-12-multiple-subtitles/
        val document = getPage(data.substringBefore('#'))
        val servers = document.select("select.mirror option[value]").mapNotNull { option ->
            val raw = option.attr("value").trim().ifBlank { return@mapNotNull null }
            val html = runCatching {
                String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull() ?: return@mapNotNull null
            val src = Jsoup.parse(html).selectFirst("iframe[src]")?.attr("src")?.trim()?.ifBlank { null }
                ?: return@mapNotNull null
            normalizeEmbed(src)
        }.toMutableList()
        // The page's default player (no <option> needed) as a fallback.
        val defaultFrame = document.selectFirst("#pembed iframe")
        if (defaultFrame != null) {
            val src: String = defaultFrame.attr("data-litespeed-src").ifBlank { defaultFrame.attr("src") }
            if (src.isNotBlank() && !src.startsWith("about:")) servers.add(normalizeEmbed(src))
        }
        val ordered = servers.distinct()
            .filterNot { it.contains("seatv", true) } // the old SeaTV domain is parked
            .sortedBy { rank(it) }
        if (ordered.isEmpty()) return false

        var found = false
        for (embed in ordered) {
            val ok = runCatching {
                if (embed.contains("rumble.com/embed/")) {
                    rumble(embed, subtitleCallback, callback)
                } else {
                    loadExtractor(embed, data, subtitleCallback) { callback(it); found = true }
                }
            }.getOrDefault(false)
            if (ok) found = true
        }
        return found
    }

    /**
     * Rumble's embed page inlines the player config: `"hls":{"url":".../hls-vod/{id}/playlist.m3u8"}` (a
     * master playlist up to 4K with plain MPEG-TS) and `"cc":{"vi":{"language":..,"path":..vtt},...}`.
     */
    private suspend fun rumble(
        embed: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = app.get(embed, headers = pageHeaders).text
        val playlist = (RUMBLE_HLS.find(html) ?: RUMBLE_ANY_M3U8.find(html))?.value
            ?.replace("\\/", "/") ?: return false

        val ccStart = html.indexOf("\"cc\":{")
        if (ccStart >= 0) {
            extractJsonObject(html, ccStart + 5)?.let { json ->
                runCatching { jsonMapper.readTree(json) }.getOrNull()?.let { cc ->
                    val tracks = cc.fields().asSequence().mapNotNull { (code, track) ->
                        val path = track.path("path").asText("").takeIf { it.startsWith("http") }
                            ?: return@mapNotNull null
                        Triple(code, track.path("language").asText("").ifBlank { code }, path)
                    }.sortedBy { if (it.first == "en") 0 else 1 }
                    tracks.forEach { (_, language, path) -> subtitleCallback(newSubtitleFile(language, path)) }
                }
            }
        }

        val width = Regex("""p\.f\["[^"]+"]=\{"fps":\d+,"w":(\d+),"h":(\d+)""").find(html)
        val quality = width?.let { qualityOf(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            ?: Qualities.Unknown.value
        callback(
            newExtractorLink(name, "$name Rumble", playlist, ExtractorLinkType.M3U8) {
                referer = "https://rumble.com/"
                this.quality = quality
                headers = mapOf("User-Agent" to userAgent)
            },
        )
        return true
    }

    /** Protocol-relative and player-page URLs into what the extractors expect. */
    private fun normalizeEmbed(src: String): String {
        val url = if (src.startsWith("//")) "https:$src" else src
        // geo.dailymotion.com/player/{player}.html?video={id} -> the classic embed the extractor knows.
        if (url.contains("dailymotion.com/player", true)) {
            Regex("""[?&]video=([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)?.let {
                return "https://www.dailymotion.com/embed/video/$it"
            }
        }
        return url
    }

    /** Rumble first, then ok.ru, then everything else (Dailymotion, streamplay, ...). */
    private fun rank(url: String): Int = when {
        url.contains("rumble.com", true) -> 0
        url.contains("ok.ru", true) || url.contains("odnoklassniki", true) -> 1
        url.contains("dailymotion", true) -> 2
        else -> 3
    }

    private fun qualityOf(width: Int, height: Int): Int = when {
        width >= 3200 || height >= 2000 -> Qualities.P2160.value
        width >= 2400 || height >= 1400 -> Qualities.P1440.value
        width >= 1800 || height >= 1000 -> Qualities.P1080.value
        width >= 1200 || height >= 700 -> Qualities.P720.value
        else -> Qualities.P480.value
    }

    /** Returns the JSON object that starts at [from], respecting strings and escapes. */
    private fun extractJsonObject(text: String, from: Int): String? {
        val begin = text.indexOf('{', from).takeIf { it >= 0 && it - from < 3 } ?: return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in begin until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return text.substring(begin, i + 1)
                }
            }
        }
        return null
    }

    private suspend fun getPage(url: String): Document = app.get(url, headers = pageHeaders).document

    private data class Entry(
        val url: String,
        val number: Int?,
        val label: String,
        val title: String?,
        val date: String?,
    )

    private fun imageOf(img: Element?): String? {
        if (img == null) return null
        return listOf(img.attr("data-src"), img.attr("data-lazy-src"), img.attr("src"))
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { fixUrl(it) }
    }

    /** `article.bs` cards of a list, search or recommendation block. */
    private fun cards(container: Element?): List<SearchResponse> =
        container?.select("article.bs .bsx > a[href]")?.mapNotNull { link ->
            val href = fixUrl(link.attr("href"))
            if (!href.contains("/anime/") || href.contains("/anime/list-mode")) return@mapNotNull null
            val title = link.attr("title").trim()
                .ifBlank { link.selectFirst(".tt h2")?.text()?.trim().orEmpty() }
                .ifBlank { return@mapNotNull null }
            val poster = imageOf(link.selectFirst("img"))
            val isMovie = link.selectFirst(".typez")?.text()?.contains("Movie", true) == true
            newAnimeSearchResponse(title, href, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
                posterUrl = poster
            }
        }?.distinctBy { it.url }.orEmpty()

    companion object {
        private val YEAR = Regex("""(19|20)\d{2}""")
        private val RUMBLE_HLS = Regex("""https:\\?/\\?/rumble\.com\\?/hls-vod\\?/[^"]+?playlist\.m3u8[^"]*""")
        private val RUMBLE_ANY_M3U8 = Regex("""https:\\?/\\?/[^"]+?\.m3u8[^"]*""")
    }
}
