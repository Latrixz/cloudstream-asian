package com.onetouchtv

import com.csasian.shared.CsSearch
import com.csasian.shared.CsCategories
import com.csasian.shared.CsBrowse
import com.csasian.shared.CsBackup

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * OneTouchTV (onetouchtv.xyz) is a Flutter web app backed by a JSON API at api3.devcorp.me:
 *  - lists:   vod/home (randomSlideShow + recents), vod/popular?page=N, vod/movie?page=N,
 *             vod/top (day/week/month), vod/filter?country=korean&genre=..&type=..&page=N
 *  - search:  vod/search?page=N&keyword=..
 *  - detail:  web/vod/{id}/detail (MyDramaList metadata, episodes[] with playId)
 *  - episode: web/vod/{id}/episode/{playId} -> sources[] (HLS) + track[] (WebVTT subtitles)
 * Every response body is AES-256-CBC encrypted base64 text with '+' written as '@' and '/' as '-_.'.
 * The HLS segments are named .png but are plain MPEG-TS, so the playlist plays as-is.
 *
 * Our URLs mirror the site's routes: /detail/{id} for titles and /detail/{id}/{playId} for episodes.
 */
class OneTouchTVProvider : MainAPI() {
    override var mainUrl = "https://onetouchtv.xyz"
    override var name = "OneTouchTV Asia"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    private val apiUrl = "https://api3.devcorp.me"
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
    private val apiHeaders
        get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/", "Origin" to mainUrl)
    private val mapper = ObjectMapper()

    private val countries = listOf(
        "korean" to "Korean",
        "chinese" to "Chinese",
        "thai" to "Thai",
        "japanese" to "Japanese",
        "taiwanese" to "Taiwanese",
        "filipino" to "Filipino",
    )

    /**
     * Category browsing (shared/CsBrowse.kt) with the API's own filter list (vod/filter): its three types
     * as sections, country, genre and year. It lists newest first and has no other sort. A search inside a
     * section is the site's search (it has no filters), kept to that section's type.
     */
    private val browse = CsBrowse(
        this, name,
        listOf(
            CsBrowse.Section("drama", "亚洲剧", ottFilters(), english = "Asian Dramas", glyph = "📺"),
            CsBrowse.Section("movie", "电影", ottFilters(), english = "Movies", glyph = "🎬"),
            CsBrowse.Section("tv show", "综艺", ottFilters(), english = "TV Shows", glyph = "🎤"),
        ),
    ) { id, filters, query, page ->
        if (query.isNotBlank()) {
            val found = searchPage(query, page)
            val type = typeOf(id)
            return@CsBrowse found.filter { it.type == type } to (found.size >= PAGE_SIZE)
        }
        val path = buildString {
            append("vod/filter?type=").append(URLEncoder.encode(id, "UTF-8").replace("+", "%20"))
            filters["country"]?.let { append("&country=").append(URLEncoder.encode(it, "UTF-8").replace("+", "%20")) }
            filters["genre"]?.let { append("&genre=").append(URLEncoder.encode(it, "UTF-8").replace("+", "%20")) }
            filters["year"]?.let { append("&year=").append(it) }
            append("&page=").append(page)
        }
        // An option with no titles answers 404.
        val items = runCatching { toResults(api(path).path("result")) }.getOrDefault(emptyList())
        items to (items.size >= PAGE_SIZE)
    }

    private fun ottFilters() = listOf(
        CsCategories.Filter(
            "country", "地区 Country",
            listOf(
                "korean" to "Korea", "chinese" to "China", "japanese" to "Japan", "thai" to "Thailand",
                "taiwanese" to "Taiwan", "hong kong" to "Hong Kong", "filipino" to "Philippines",
            ).map { (value, label) -> CsCategories.Option(value, label) },
        ),
        CsCategories.Filter(
            "genre", "类型 Genre",
            listOf(
                "action", "adventure", "business", "comedy", "crime", "drama", "family", "fantasy", "food", "historical",
                "horror", "law", "life", "martial arts", "medical", "melodrama", "military", "music", "mystery", "political",
                "psychological", "romance", "sci-fi", "sitcom", "sports", "supernatural", "thriller", "tragedy", "war",
                "wuxia", "youth",
            ).map { g -> CsCategories.Option(g, g.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }) },
        ),
        CsCategories.Filter("year", "年份 Year", CsBrowse.years(20)),
    )

    // data: "home|<key>" and "top|<period>" are single pages; everything else is a paged API path.
    override val mainPage = browse.mainPage(mainPageOf(
        "home|randomSlideShow" to "Featured",
        "home|recents" to "Recently Updated",
        "vod/popular?" to "Popular",
        "vod/movie?" to "Newest Movies",
        "top|day" to "Top Today",
        "top|week" to "Top This Week",
    ) + countries.map { (key, label) -> MainPageData("$label Dramas & Movies", "vod/filter?country=$key&") })

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        browse.rowPage(request) ?: com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    // Categories SkyStream Dev shows itself (shared/CsCategories.kt); the engine finds these by name.
    fun csCategories(): String = browse.categoriesJson()

    fun csCategoryPage(categoryId: String, filtersJson: String, query: String, page: Int): SearchResponseList =
        browse.categoryPage(categoryId, filtersJson, query, page)

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        if (data.startsWith("home|") || data.startsWith("top|")) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val path = if (data.startsWith("home|")) "vod/home" else "vod/top"
            val result = api(path).path("result").path(data.substringAfter('|'))
            return newHomePageResponse(request.name, toResults(result), false)
        }
        val items = runCatching { toResults(api("${data}page=$page").path("result")) }.getOrDefault(emptyList())
        return newHomePageResponse(request.name, items, items.size >= PAGE_SIZE)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = searchWithExtra(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchWithExtra(query, page)
        return newSearchResponseList(results, hasNext)
    }

    // Page 1 also searches the English title when the query is in another language (shared/CsSearch.kt).
    private suspend fun searchWithExtra(query: String, page: Int): Pair<List<SearchResponse>, Boolean> =
        CsSearch.withExtra(query, page, lang) { q, p -> searchPage(q, p).let { it to (it.size >= PAGE_SIZE) } }

    private suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val keyword = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return runCatching { toResults(api("vod/search?page=$page&keyword=$keyword").path("result")) }
            .getOrDefault(emptyList())
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
        val id = idFromUrl(url) ?: throw ErrorLoadingException("Invalid OneTouchTV link")
        val detail = api("web/vod/${encodePath(id)}/detail").path("result")
        if (!detail.isObject) throw ErrorLoadingException("Title not found")
        val detailUrl = "$mainUrl/detail/$id"

        val (title, titleYear) = splitTitle(detail.text("title") ?: throw ErrorLoadingException("Title not found"))
        val year = detail.text("year")?.take(4)?.toIntOrNull() ?: titleYear
        val poster = detail.text("image")
        val background = detail.text("poster")
        val country = detail.text("country")
        val countryLabel = countries.firstOrNull { it.first == country }?.second
            ?: country?.replaceFirstChar { it.uppercase() }
        val genres = detail.path("genres").mapNotNull { it.asText("").trim().ifBlank { null } }
        val tags = (listOfNotNull(countryLabel) + genres.map { genre ->
            genre.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        }).distinct()
        val score = detail.path("rating").asDouble(0.0).takeIf { it > 0 }?.let { Score.from10(it) }
        val actors = (detail.path("actors").mapNotNull { actor ->
            val actorName = actor.text("name") ?: return@mapNotNull null
            ActorData(Actor(actorName, actor.text("image")))
        }.take(MAX_ACTORS) + detail.path("director").mapNotNull { director ->
            director.asText("").trim().ifBlank { null }?.let { ActorData(Actor(it), roleString = "Director") }
        }).ifEmpty { null }
        val network = detail.text("original_network")
        val otherTitles = detail.path("otherTitles").mapNotNull { it.asText("").trim().ifBlank { null } }
            .filter { it != title }.take(3)
        val plot = listOfNotNull(
            detail.text("description"),
            otherTitles.takeIf { it.isNotEmpty() }?.let { "Also known as: ${it.joinToString(", ")}" },
            network?.let { "Network: $it" },
        ).joinToString("\n\n").ifBlank { null }
        val runtime = detail.path("runtime").asInt(0).takeIf { it > 0 }

        val episodes = detail.path("episodes").mapNotNull { ep ->
            val playId = ep.text("playId") ?: return@mapNotNull null
            Triple(playId, ep.text("episode")?.toIntOrNull(), ep)
        }.sortedWith(compareBy({ it.second ?: Int.MAX_VALUE }, { it.first }))
        if (episodes.isEmpty()) throw ErrorLoadingException("No episodes available yet")

        val isMovie = detail.text("type") == "movie" && episodes.size == 1
        val type = typeOf(detail.text("type"))
        val recommended = recommendations(id, country, genres.firstOrNull())

        return if (isMovie) {
            newMovieLoadResponse(title, detailUrl, TvType.Movie, "$detailUrl/${episodes[0].first}") {
                posterUrl = poster
                backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                this.actors = actors
                this.duration = runtime
                this.recommendations = recommended
            }
        } else {
            val list = episodes.mapIndexed { index, (playId, number, ep) ->
                newEpisode("$detailUrl/$playId") {
                    this.episode = number ?: (index + 1)
                    this.name = "Episode ${number ?: playId}"
                    ep.text("released_at")?.let { addDate(it.take(10)) }
                }
            }
            newTvSeriesLoadResponse(title, detailUrl, if (type == TvType.Movie) TvType.AsianDrama else type, list) {
                posterUrl = poster
                backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                this.actors = actors
                this.duration = runtime
                this.showStatus = when (detail.text("status")) {
                    "completed" -> ShowStatus.Completed
                    "ongoing" -> ShowStatus.Ongoing
                    else -> null
                }
                this.recommendations = recommended
            }
        }
    }

    private suspend fun loadOwnLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data = https://onetouchtv.xyz/detail/{id}/{playId}
        val path = data.substringBefore('#').substringBefore('?').removePrefix("$mainUrl/detail/")
        val id = path.substringBefore('/').ifBlank { return false }
        val playId = path.substringAfter('/', "").ifBlank { return false }
        val result = try {
            api("web/vod/${encodePath(id)}/episode/${encodePath(playId)}").path("result")
        } catch (e: Throwable) {
            android.util.Log.w("OneTouchTV", "episode api failed id=$id play=$playId: $e")
            throw e
        }

        // English first, then the rest in the site's order.
        result.path("track").sortedBy { if (it.text("code") == "en") 0 else 1 }.forEach { track ->
            val file = track.text("file")?.takeIf { it.startsWith("http") } ?: return@forEach
            subtitleCallback(newSubtitleFile(track.text("name") ?: track.text("code") ?: "Subtitle", file))
        }

        var found = false
        result.path("sources").forEachIndexed { index, source ->
            val url = source.text("url")?.takeIf { it.startsWith("http") } ?: return@forEachIndexed
            val isHls = source.text("type") == "hls" || url.contains(".m3u8", true)
            val label = source.text("name")?.replaceFirstChar { it.uppercase() } ?: "Server ${index + 1}"
            callback(
                newExtractorLink(
                    name,
                    "$name $label",
                    url,
                    if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    referer = "$mainUrl/"
                    quality = qualityOf(source.text("quality"))
                    headers = mapOf("User-Agent" to userAgent, "Origin" to mainUrl)
                },
            )
            found = true
        }
        return found
    }

    /** Same country (and first genre when known), minus the current title. */
    private suspend fun recommendations(id: String, country: String?, genre: String?): List<SearchResponse> {
        if (country.isNullOrBlank()) return emptyList()
        return runCatching {
            val genreParam = genre?.let { "&genre=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
            toResults(api("vod/filter?country=${URLEncoder.encode(country, "UTF-8")}$genreParam&page=1").path("result"))
                .filter { idFromUrl(it.url) != id }
                .take(MAX_RECOMMENDATIONS)
        }.getOrDefault(emptyList())
    }

    /** GETs an API path and returns the decrypted JSON. */
    private suspend fun api(path: String): JsonNode {
        val body = app.get("$apiUrl/$path", headers = apiHeaders).text.trim()
        val json = if (body.startsWith("{")) body else decrypt(body)
        val root = mapper.readTree(json)
        if (root.path("success").isBoolean && !root.path("success").asBoolean()) {
            throw ErrorLoadingException("OneTouchTV: ${root.path("status").asText("error")}")
        }
        return root
    }

    private fun decrypt(text: String): String {
        val b64 = text.replace("\"", "").replace("\\s".toRegex(), "")
            .replace("@", "+").replace("-_.", "/")
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(AES_KEY.substring(0, 16).toByteArray(Charsets.UTF_8)),
        )
        return String(cipher.doFinal(Base64.getDecoder().decode(b64)), Charsets.UTF_8)
    }

    private fun toResults(list: JsonNode): List<SearchResponse> =
        list.mapNotNull { item ->
            val id = item.text("id") ?: item.text("_id") ?: return@mapNotNull null
            val (title, titleYear) = splitTitle(item.text("title") ?: return@mapNotNull null)
            val href = "$mainUrl/detail/$id"
            val poster = item.text("image")
            val year = item.text("year")?.take(4)?.toIntOrNull() ?: titleYear
            when (val type = typeOf(item.text("type"))) {
                TvType.Movie -> newMovieSearchResponse(title, href, type) {
                    posterUrl = poster
                    this.year = year
                }
                else -> newTvSeriesSearchResponse(title, href, type) {
                    posterUrl = poster
                    this.year = year
                }
            }
        }.distinctBy { it.url }

    private fun typeOf(type: String?): TvType = when (type?.lowercase()?.replace(" ", "")) {
        "movie" -> TvType.Movie
        "tvshow", "show" -> TvType.TvSeries   // the API also sends "tv show"
        else -> TvType.AsianDrama
    }

    /** "Filing for Love (2026)" -> ("Filing for Love", 2026). */
    private fun splitTitle(raw: String): Pair<String, Int?> {
        val match = TITLE_YEAR.find(raw.trim()) ?: return raw.trim() to null
        val title = raw.trim().substring(0, match.range.first).trim()
        return (title.ifBlank { raw.trim() }) to match.groupValues[1].toIntOrNull()
    }

    private fun idFromUrl(url: String): String? =
        url.substringBefore('#').substringBefore('?').substringAfter("/detail/", "")
            .substringBefore('/').ifBlank { null }

    private fun encodePath(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private fun qualityOf(text: String?): Int {
        val value = text.orEmpty()
        return when {
            value.contains("2160") || value.contains("4k", true) -> Qualities.P2160.value
            value.contains("1080") -> Qualities.P1080.value
            value.contains("720") -> Qualities.P720.value
            value.contains("480") -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    private fun JsonNode.text(field: String): String? =
        path(field).takeIf { it.isValueNode && !it.isNull }?.asText()?.trim()?.ifBlank { null }

    companion object {
        private const val AES_KEY = "im72charPasswordofdInitVectorStm"
        private const val PAGE_SIZE = 20
        private const val MAX_ACTORS = 20
        private const val MAX_RECOMMENDATIONS = 20
        private val TITLE_YEAR = Regex("""\s*\((\d{4})\)$""")
    }
}
