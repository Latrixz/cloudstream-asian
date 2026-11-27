package com.csasian.anime

import com.csasian.shared.CsBackup
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Shared backend of AniKoto (anikototv.bz) and AnimeSuge (animesuge.cz). Both sites run the same
 * anime CMS with different themes, so selectors below cover both markups.
 *
 *  - Lists/search: /filter?keyword=&sort=...&term_type[]=...&page=N
 *  - Detail:       /watch/{slug} (AniKoto) or /anime/{slug} (AnimeSuge); element [data-id][data-url]
 *                  gives the anime id
 *  - Episodes:     /ajax/episode/list/{id}  -> a[data-ids] (data-slug, data-sub, data-dub)
 *  - Servers:      /ajax/server/list?servers={data-ids} -> [data-link-id] grouped by [data-type=sub|dub]
 *  - Embed:        /ajax/server?get={data-link-id} -> MegaPlay URL (see [MegaPlay])
 *
 * Episode data is the full episode URL plus a fragment: {seriesUrl}/ep-{slug}#lang={sub|dub}&id={animeId}
 */
abstract class AnimeSiteProvider : MainAPI() {
    /** Site logo; used as the poster when a title has none. */
    abstract val siteLogo: String

    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    protected open val userAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val pageHeaders get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
    private val ajaxHeaders get() = pageHeaders + ("X-Requested-With" to "XMLHttpRequest")
    private val mapper = ObjectMapper()

    override val mainPage = listOf(
        MainPageData("Recently Updated", "sort=recently_updated"),
        MainPageData("Top Airing", "status[]=currently-airing&sort=most-viewed"),
        MainPageData("Most Viewed", "sort=most-viewed"),
        MainPageData("New Releases", "sort=release-date"),
        MainPageData("Top Rated", "sort=score"),
        MainPageData("Dubbed", "language[]=dub&sort=recently_updated"),
        MainPageData("Movies", "term_type[]=Movie&sort=recently_updated"),
        MainPageData("TV Series", "term_type[]=TV&sort=recently_updated"),
    )

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        val (items, hasNext) = filterPage(request.data, page)
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (items, hasNext) = searchPage(query, page)
        return newSearchResponseList(items, hasNext)
    }

    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        return filterPage("keyword=${URLEncoder.encode(query.trim(), "UTF-8")}", page)
    }

    private suspend fun filterPage(params: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val query = params.replace("[]", "%5B%5D")
        val document = app.get("$mainUrl/filter?$query&page=$page", headers = pageHeaders).document
        val items = document.select("#list-items > .item, .main-card > .item")
            .mapNotNull { it.toListResult() }
            .distinctBy { it.url }
        val hasNext = document.select("a[href*=page=${page + 1}]").any {
            Regex("""[?&]page=${page + 1}(?:&|$)""").containsMatchIn(it.attr("href"))
        }
        return items to (items.isNotEmpty() && hasNext)
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
        val document = app.get(seriesUrl(url.substringBefore('#')), headers = pageHeaders).document
        val holder = document.selectFirst("[data-id][data-url]")
            ?: throw ErrorLoadingException("Anime not found")
        val animeId = holder.attr("data-id")
        val seriesUrl = seriesUrl(holder.attr("data-url").ifBlank { url.substringBefore('#') })

        val heading = document.selectFirst("h1[itemprop=name], h1.title")
        val title = heading?.text()?.trim().orEmpty().ifBlank {
            document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        }.ifBlank { throw ErrorLoadingException("Anime title not found") }
        val japName = heading?.attr("data-jp")?.trim()?.takeIf { it.isNotBlank() && it != title }
        val poster: String = imageOf(document.selectFirst("img[itemprop=image]"))
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: siteLogo
        val plot = document.select(".synopsis .content, .description .full, .description .short")
            .map { it.text().trim() }.maxByOrNull { it.length }?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val info = infoRows(document)
        val typeText = info["type"].orEmpty()
        val type = tvTypeOf(typeText)
        val year = listOfNotNull(info["premiered"], info["aired"], info["date aired"])
            .firstNotNullOfOrNull { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }
        val score = (info["mal"] ?: info["score"] ?: document.selectFirst("[data-score]")?.attr("data-score"))
            ?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }
        val status = info["status"]?.lowercase()?.let {
            when {
                it.contains("airing") && !it.contains("finished") && !it.contains("not yet") -> ShowStatus.Ongoing
                it.contains("finished") -> ShowStatus.Completed
                else -> null
            }
        }
        val duration = info["duration"]?.let { Regex("""(\d+)\s*min""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val tags = document.select("a[href*=/genre/]")
            .filter { link -> link.parents().any { it.hasClass("meta") || it.hasClass("bmeta") } }
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val synonyms = document.selectFirst(".names")?.text()?.split(';')
            ?.map { it.trim() }?.filter { it.isNotBlank() && it != title }.orEmpty()

        val episodes = fetchEpisodes(animeId)
        val malId = episodes.firstNotNullOfOrNull { it.malId }
        fun episodeList(lang: String) = episodes.filter { if (lang == "dub") it.dub else it.sub }.map { ep ->
            newEpisode("$seriesUrl/ep-${ep.slug}#lang=$lang&id=$animeId") {
                name = ep.title?.takeUnless { Regex("""^(Episode|EP)\s*\d+$""", RegexOption.IGNORE_CASE).matches(it) }
                episode = ep.number
            }
        }
        val subbed = episodeList("sub")
        val dubbed = episodeList("dub")
        val recommendations = recommendations(document, seriesUrl)

        return newAnimeLoadResponse(title, seriesUrl, type) {
            posterUrl = poster
            this.japName = japName
            this.plot = plot
            this.year = year
            this.tags = tags
            this.synonyms = synonyms
            this.score = score?.let { Score.from10(it) }
            this.showStatus = status
            this.duration = duration
            this.recommendations = recommendations
            if (subbed.isNotEmpty()) addEpisodes(DubStatus.Subbed, subbed)
            if (dubbed.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubbed)
            addMalId(malId)
        }
    }

    private suspend fun loadOwnLinks(
        data: String,
        @Suppress("UNUSED_PARAMETER") isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val episodeUrl = data.substringBefore('#')
        val params = data.substringAfter('#', "").split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val lang = params["lang"]?.takeIf { it == "dub" } ?: "sub"
        val slug = episodeUrl.substringAfterLast("/ep-", "").ifBlank { "1" }
        val animeId = params["id"]?.takeIf { it.isNotBlank() }
            ?: app.get(episodeUrl, headers = pageHeaders).document
                .selectFirst("[data-id][data-url]")?.attr("data-id")
            ?: return false

        val episodes = fetchEpisodes(animeId)
        val episode = episodes.firstOrNull { it.slug == slug }
            ?: slug.toIntOrNull()?.let { n -> episodes.firstOrNull { it.number == n } }
            ?: return false

        val serverHtml = ajaxResult("$mainUrl/ajax/server/list?servers=${episode.serverIds}") ?: return false
        val servers = Jsoup.parse(serverHtml).select("[data-link-id]").map { el ->
            val type = el.parents().firstOrNull { it.hasAttr("data-type") }?.attr("data-type").orEmpty()
            Triple(type, el.text().trim().ifBlank { "Server" }, el.attr("data-link-id"))
        }.filter { it.third.isNotBlank() }
        val wanted = servers.filter { it.first == lang }.ifEmpty { servers }
        val langLabel = if (lang == "dub") "Dub" else "Sub"

        val seenSubs = mutableSetOf<String>()
        val subtitleOnce: (SubtitleFile) -> Unit = { sub ->
            val fresh = synchronized(seenSubs) { seenSubs.add(sub.url) }
            if (fresh) subtitleCallback(sub)
        }

        val results = coroutineScope {
            wanted.map { (_, label, linkId) ->
                async {
                    runCatching {
                        val embed = embedUrl(linkId) ?: return@runCatching false
                        if (!MegaPlay.handles(embed)) return@runCatching false
                        MegaPlay.extract(
                            embed, name, "$name $label ($langLabel)", "$mainUrl/", userAgent,
                            subtitleOnce, callback,
                        )
                    }.getOrDefault(false)
                }
            }.awaitAll()
        }
        return results.any { it }
    }

    private suspend fun embedUrl(linkId: String): String? {
        val text = app.get("$mainUrl/ajax/server?get=$linkId", headers = ajaxHeaders).text
        return mapper.readTree(text).path("result").path("url").asText("").takeIf { it.startsWith("http") }
    }

    private suspend fun ajaxResult(url: String): String? {
        val text = app.get(url, headers = ajaxHeaders).text
        return mapper.readTree(text).path("result").asText("").takeIf { it.isNotBlank() }
    }

    private data class EpisodeEntry(
        val slug: String,
        val number: Int?,
        val title: String?,
        val sub: Boolean,
        val dub: Boolean,
        val serverIds: String,
        val malId: Int?,
    )

    private suspend fun fetchEpisodes(animeId: String): List<EpisodeEntry> {
        val html = ajaxResult("$mainUrl/ajax/episode/list/$animeId") ?: return emptyList()
        return Jsoup.parse(html).select("a[data-ids]").mapNotNull { a ->
            val slug = a.attr("data-slug").trim().ifBlank { return@mapNotNull null }
            val number = slug.toIntOrNull()
                ?: Regex("""\d+""").find(a.attr("data-num"))?.value?.toIntOrNull()
            val title = a.selectFirst(".d-title")?.text()?.trim()?.ifBlank { null }
                ?: a.attr("title").trim().ifBlank { null }
                ?: a.parent()?.attr("title")?.trim()?.ifBlank { null }
            val sub = a.attr("data-sub") == "1"
            val dub = a.attr("data-dub") == "1"
            EpisodeEntry(
                slug = slug,
                number = number,
                title = title,
                // Some entries carry neither flag; treat those as subbed.
                sub = sub || !dub,
                dub = dub,
                serverIds = a.attr("data-ids"),
                malId = a.attr("data-mal").toIntOrNull()?.takeIf { it > 0 },
            )
        }.distinctBy { it.slug }
    }

    /** "Type: TV", "Premiered: FALL 2026", ... from the detail block (label lower-cased). */
    private fun infoRows(document: Document): Map<String, String> =
        document.select(".meta > div").mapNotNull { row ->
            val text = row.text().trim()
            val colon = text.indexOf(':')
            if (colon <= 0 || colon > 20) return@mapNotNull null
            text.substring(0, colon).trim().lowercase() to text.substring(colon + 1).trim()
        }.distinctBy { it.first }.toMap()

    /** The "Recommended" block on the detail page. */
    private fun recommendations(document: Document, seriesUrl: String): List<SearchResponse> = runCatching {
        val heading = document.getAllElements().firstOrNull {
            it.ownText().trim().equals("Recommended", ignoreCase = true)
        } ?: return@runCatching emptyList()
        val section = heading.parents().firstOrNull { it.select("a.item").isNotEmpty() }
            ?: return@runCatching emptyList()
        section.select("a.item").mapNotNull { it.toListResult() }
            .filter { it.url != seriesUrl }
            .distinctBy { it.url }
    }.getOrDefault(emptyList())

    /** List item (filter grid) or recommendation card. */
    private fun Element.toListResult(): SearchResponse? {
        val link = (if (tagName() == "a") this else null)
            ?: selectFirst("a.name, .name a, a.poster, .poster a, a[href]")
            ?: return null
        val href = link.attr("abs:href").ifBlank { link.attr("href") }
        if (!href.contains("/watch/") && !href.contains("/anime/")) return null
        val url = seriesUrl(fixUrl(href))
        val img = selectFirst("img")
        val title = (selectFirst(".name")?.text()?.trim()
            ?: img?.attr("alt")?.trim()).orEmpty().ifBlank { return null }
        val typeText = selectFirst(".right, .item-status .type, .meta .type")?.text()?.trim()
            ?: select(".meta .dot").map { it.text().trim() }.firstOrNull { tvTypeOrNull(it) != null }
            .orEmpty()
        val subCount = countOf(selectFirst(".ep-status.sub, .dub-sub-total .sub"))
        val dubCount = countOf(selectFirst(".ep-status.dub, .dub-sub-total .dub"))
        return newAnimeSearchResponse(title, url, tvTypeOf(typeText)) {
            posterUrl = imageOf(img)
            if (subCount != null || dubCount != null) {
                addDubStatus(dubCount != null, subCount != null, dubCount, subCount)
            }
        }
    }

    private fun countOf(el: Element?): Int? = el?.text()?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

    /** Drops the "/ep-N" suffix of watch links. */
    private fun seriesUrl(url: String): String = url.replace(Regex("""/ep-[^/?#]*$"""), "").trimEnd('/')

    private fun imageOf(img: Element?): String? {
        img ?: return null
        return listOf(img.attr("data-src"), img.attr("src"))
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { fixUrl(it) }
    }

    private fun tvTypeOrNull(text: String): TvType? = when (text.trim().lowercase()) {
        "movie" -> TvType.AnimeMovie
        "ova", "special", "tv special" -> TvType.OVA
        "tv", "ona", "tv_short", "tv short" -> TvType.Anime
        else -> null
    }

    private fun tvTypeOf(text: String): TvType = tvTypeOrNull(text) ?: TvType.Anime
}
