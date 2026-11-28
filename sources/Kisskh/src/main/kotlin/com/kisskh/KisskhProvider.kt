package com.kisskh

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
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URLEncoder

/**
 * kisskh.co is an Angular app on a JSON API (https://kisskh.co/api/):
 *  - lists:   DramaList/List?page&type&sub&country&status&order&pageSize  -> {totalCount, data:[{id,title,thumbnail,episodesCount}]}
 *             type 1 TV series, 2 movie, 3 anime, 4 Hollywood; country 1 China, 2 Korea, 3 Japan,
 *             4 Hong Kong, 5 Thailand, 6 US, 7 Taiwan, 8 Philippines; order 1 popular, 2 last update, 3 release
 *  - search:  DramaList/Search?q=..&type=0  -> [{id,title,thumbnail,episodesCount}]
 *  - detail:  DramaList/Drama/{id}?isq=false  -> description, releaseDate, country, status, type, episodes[{id,number,sub}]
 *  - video:   DramaList/Episode/{epId}.png?err=false&ts=null&time=null&kkey=..  -> {"Video": m3u8}
 *  - subs:    Sub/{epId}?kkey=..  -> [{src,label,land}]; .txt1/.txt files have AES-encrypted cue text,
 *             (also .txt2) decrypted by [getVideoInterceptor] (CloudStream fetches subtitles through it too).
 * kkey comes from [KisskhCrypto].
 *
 * Our URLs follow the site's own pages: /Drama/{slug}?id={dramaId} and
 * /Drama/{slug}/Episode-{n}?id={dramaId}&ep={episodeId}.
 */
class KisskhProvider : MainAPI() {
    override var mainUrl = "https://kisskh.co"
    override var name = "Kisskh Asia"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie,
        TvType.Anime,
    )

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val apiHeaders get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/", "Accept" to "application/json")
    private val api get() = "$mainUrl/api"
    private val jsonMapper = ObjectMapper()

    /** request.data = "type|country|order" for DramaList/List. */
    /**
     * Category browsing (shared/CsBrowse.kt) with the site's own list filters (DramaList/List): its four
     * types as sections, country and status, and its orders (release date first, then last update and
     * popularity). A search inside a section is the site's search for that type (one page).
     */
    private val browse = CsBrowse(
        this, name,
        listOf(
            CsBrowse.Section(
                "1", "亚洲剧", listOf(countryFilter(), statusFilter()), sorts(),
                english = "Asian Dramas", glyph = "📺",
            ),
            CsBrowse.Section("2", "电影", listOf(countryFilter()), sorts(), english = "Movies", glyph = "🎬"),
            CsBrowse.Section("3", "动漫", listOf(statusFilter()), sorts(), english = "Anime", glyph = "🐉"),
            CsBrowse.Section("4", "美剧", listOf(statusFilter()), sorts(), english = "Hollywood", glyph = "📺"),
        ),
        popularSort = "1",
    ) { id, filters, query, page ->
        val type = id.toIntOrNull() ?: 0
        if (query.isNotBlank()) {
            if (page > 1) return@CsBrowse emptyList<SearchResponse>() to false
            return@CsBrowse searchOwn(query, type) to false
        }
        val country = filters["country"]?.toIntOrNull() ?: 0
        val status = filters["status"]?.toIntOrNull() ?: 0
        val order = filters[CsCategories.SORT]?.toIntOrNull() ?: 3
        val root = getJson(
            "$api/DramaList/List?page=$page&type=$type&sub=0&country=$country&status=$status&order=$order&pageSize=$PAGE_SIZE",
        )
        val items = root.path("data").mapNotNull { it.toSearchResult(typeHint(type, country)) }
        items to (items.isNotEmpty() && page * PAGE_SIZE < root.path("totalCount").asInt(0))
    }

    private fun countryFilter() = CsCategories.Filter(
        "country", "地区 Country",
        listOf(2 to "Korea", 1 to "China", 3 to "Japan", 4 to "Hong Kong", 5 to "Thailand", 7 to "Taiwan", 8 to "Philippines", 6 to "United States")
            .map { (id, label) -> CsCategories.Option(id.toString(), label) },
    )

    private fun statusFilter() = CsCategories.Filter(
        "status", "状态 Status",
        listOf(CsCategories.Option("1", "Ongoing"), CsCategories.Option("2", "Completed")),
    )

    private fun sorts() = listOf(
        CsCategories.Option("3", "最新 Newest"),
        CsCategories.Option("2", "最近更新 Last updated"),
        CsCategories.Option("1", "热门 Popular"),
    )

    override val mainPage = browse.mainPage(mainPageOf(
        "0|0|2" to "Latest Updates",
        "0|0|1" to "Popular",
        "1|2|2" to "Korean Dramas",
        "1|1|2" to "Chinese Dramas",
        "1|5|2" to "Thai Dramas",
        "1|3|2" to "Japanese Dramas",
        "1|7|2" to "Taiwanese Dramas",
        "1|8|2" to "Philippine Dramas",
        "2|0|2" to "Movies",
        "2|2|1" to "Popular Korean Movies",
        "3|0|2" to "Anime",
        "4|0|2" to "Hollywood",
    ))

    // SkyStream never asks for page 2; there a row with more pages ends with a "More ›" card.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        browse.rowPage(request) ?: com.csasian.shared.CsMore.wrap(this, request, page, mainPageOwn(page, request))

    // Categories SkyStream Dev shows itself (shared/CsCategories.kt); the engine finds these by name.
    fun csCategories(): String = browse.categoriesJson()

    fun csCategoryPage(categoryId: String, filtersJson: String, query: String, page: Int): SearchResponseList =
        browse.categoryPage(categoryId, filtersJson, query, page)

    private suspend fun mainPageOwn(page: Int, request: MainPageRequest): HomePageResponse {
        val (type, country, order) = request.data.split('|').map { it.toIntOrNull() ?: 0 }
        val root = getJson(listUrl(page, type, country, order, PAGE_SIZE))
        val items = root.path("data").mapNotNull { it.toSearchResult(typeHint(type, country)) }
        val total = root.path("totalCount").asInt(0)
        return newHomePageResponse(request.name, items, items.isNotEmpty() && page * PAGE_SIZE < total)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // Also searches the English title when the query is in another language (shared/CsSearch.kt).
    override suspend fun search(query: String): List<SearchResponse> =
        CsSearch.withExtra(query, 1, lang) { q, _ -> searchOwn(q, 0) to false }.first

    /** The site's search (one page); [type] 0 is every type. */
    private suspend fun searchOwn(query: String, type: Int): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return getJson("$api/DramaList/Search?q=$encoded&type=$type")
            .mapNotNull { it.toSearchResult(if (type == 0) null else typeHint(type, 0)) }
            .distinctBy { it.url }
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
        val dramaId = queryParam(url, "id")?.toLongOrNull()
            ?: Regex("""/(\d+)(?:$|[/?#])""").find(url)?.groupValues?.get(1)?.toLongOrNull()
            ?: throw ErrorLoadingException("Invalid Kisskh link")
        val detail = getJson("$api/DramaList/Drama/$dramaId?isq=false")
        val title = detail.text("title") ?: throw ErrorLoadingException("Title not found")
        val slug = slugOf(title)
        val detailUrl = "$mainUrl/Drama/$slug?id=$dramaId"
        val poster = detail.text("thumbnail")
        val country = detail.text("country")
        val status = detail.text("status")
        val siteType = detail.text("type")
        val year = Regex("""\((\d{4})\)\s*$""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: detail.text("releaseDate")?.take(4)?.toIntOrNull()
        val plot = detail.text("description")?.replace("\r\n", "\n")?.replace(Regex("""\n\s*\n\s*\n+"""), "\n\n")?.trim()
        val tags = listOfNotNull(country, siteType?.let { if (it == "TVSeries") "TV Series" else it }, status)
            .filter { it.isNotBlank() }.distinct()

        val episodes = detail.path("episodes").mapNotNull { ep ->
            val id = ep.path("id").asLong(0).takeIf { it > 0 } ?: return@mapNotNull null
            id to ep.path("number").asDouble(0.0)
        }.sortedBy { it.second }
        if (episodes.isEmpty()) throw ErrorLoadingException("No episodes yet")

        val type = when {
            siteType.equals("Movie", true) -> TvType.Movie
            siteType.equals("Anime", true) -> TvType.Anime
            siteType.equals("Hollywood", true) -> TvType.TvSeries
            else -> typeOfCountry(country)
        }
        val recommended = recommendations(dramaId, siteType, country, type)

        return if (type == TvType.Movie && episodes.size == 1) {
            newMovieLoadResponse(title, detailUrl, type, episodeUrl(slug, dramaId, episodes[0].first, episodes[0].second)) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommended
            }
        } else {
            val list = episodes.mapIndexed { index, (id, number) ->
                newEpisode(episodeUrl(slug, dramaId, id, number)) {
                    name = "Episode ${numberText(number)}"
                    episode = if (number > 0 && number % 1.0 == 0.0) number.toInt() else index + 1
                }
            }
            newTvSeriesLoadResponse(title, detailUrl, if (type == TvType.Movie) TvType.TvSeries else type, list) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommended
                showStatus = when {
                    status.equals("Completed", true) -> ShowStatus.Completed
                    status.equals("Ongoing", true) -> ShowStatus.Ongoing
                    else -> null
                }
            }
        }
    }

    private suspend fun loadOwnLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data = https://kisskh.co/Drama/{slug}/Episode-{n}?id={dramaId}&ep={episodeId}
        val episodeId = queryParam(data, "ep")?.toLongOrNull() ?: return false
        // Under another source (a backup) our interceptor isn't used, so encrypted subtitles
        // would show as gibberish there: only offer the plain ones.
        val asBackup = currentCoroutineContext()[CoroutineName]?.name == BACKUP_MARKER

        return coroutineScope {
            val subs = async {
                runCatching {
                    getJson("$api/Sub/$episodeId?kkey=${KisskhCrypto.subtitleKey(episodeId)}").forEach { sub ->
                        val src = sub.text("src") ?: return@forEach
                        if (asBackup && KisskhCrypto.isEncryptedSubtitle(src)) return@forEach
                        val label = sub.text("label") ?: sub.text("land") ?: "Unknown"
                        subtitleCallback(newSubtitleFile(label, src))
                    }
                }
            }
            val video = getJson(
                "$api/DramaList/Episode/$episodeId.png?err=false&ts=null&time=null&kkey=${KisskhCrypto.videoKey(episodeId)}",
            )
            subs.await()
            val stream = video.text("Video")?.let { if (it.startsWith("//")) "https:$it" else it }
                ?.takeIf { it.startsWith("http") } ?: return@coroutineScope false
            val isHls = stream.substringBefore('?').endsWith(".m3u8", true)
            callback(
                newExtractorLink(
                    name,
                    name,
                    stream,
                    if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    referer = "$mainUrl/"
                    quality = Qualities.Unknown.value
                    headers = mapOf("User-Agent" to userAgent, "Origin" to mainUrl)
                },
            )
            true
        }
    }

    /**
     * CloudStream loads a link's subtitles through the same interceptor as its video, so this
     * turns kisskh's encrypted .txt/.txt1/.txt2 subtitle files into plain SRT. Everything else passes.
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = subtitleDecrypter

    private val subtitleDecrypter = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (!KisskhCrypto.isEncryptedSubtitle(request.url.toString()) || !response.isSuccessful) {
            return@Interceptor response
        }
        try {
            val text = response.body.string()
            val plain = KisskhCrypto.decryptSubtitle(text).toByteArray(Charsets.UTF_8)
            response.newBuilder()
                .removeHeader("Content-Length")
                .removeHeader("Content-Encoding")
                .header("Content-Type", "application/x-subrip; charset=utf-8")
                .body(plain.toResponseBody("application/x-subrip; charset=utf-8".toMediaTypeOrNull()))
                .build()
        } catch (e: Exception) {
            response.close()
            throw java.io.IOException("Kisskh subtitle decrypt failed", e)
        }
    }

    /** Popular titles of the same kind (type + country), without the current one. */
    private suspend fun recommendations(dramaId: Long, siteType: String?, country: String?, type: TvType): List<SearchResponse> =
        runCatching {
            val typeCode = when {
                siteType.equals("Movie", true) -> 2
                siteType.equals("Anime", true) -> 3
                siteType.equals("Hollywood", true) -> 4
                siteType.equals("TVSeries", true) -> 1
                else -> 0
            }
            val countryCode = COUNTRIES.entries.firstOrNull { it.value.equals(country, true) }?.key ?: 0
            getJson(listUrl(1, typeCode, countryCode, 1, MAX_RECOMMENDATIONS + 1)).path("data")
                .filter { it.path("id").asLong(0) != dramaId }
                .mapNotNull { it.toSearchResult(type) }
                .take(MAX_RECOMMENDATIONS)
        }.getOrDefault(emptyList())

    private fun listUrl(page: Int, type: Int, country: Int, order: Int, size: Int) =
        "$api/DramaList/List?page=$page&type=$type&sub=0&country=$country&status=0&order=$order&pageSize=$size"

    private suspend fun getJson(url: String): JsonNode {
        val response = app.get(url, headers = apiHeaders)
        if (!response.isSuccessful) throw ErrorLoadingException("Kisskh answered HTTP ${response.code}")
        return jsonMapper.readTree(response.text)
    }

    private fun JsonNode.text(field: String): String? =
        path(field).takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotBlank() }

    /** [type] null = guess from the episode count (search results carry no type). */
    private fun JsonNode.toSearchResult(type: TvType?): SearchResponse? {
        val id = path("id").asLong(0).takeIf { it > 0 } ?: return null
        val title = text("title") ?: return null
        // Title only (never the synopsis): drops e.g. "(UNCENSORED)" anime from search, rows and categories.
        if (CsCategories.isAdultTitle(title)) return null
        val url = "$mainUrl/Drama/${slugOf(title)}?id=$id"
        val poster = text("thumbnail")
        val count = path("episodesCount").asInt(0)
        val resolved = type ?: TvType.AsianDrama
        return if (resolved == TvType.Movie) {
            newMovieSearchResponse(title, url, resolved) { posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, url, resolved) {
                posterUrl = poster
                if (count > 0) episodes = count
            }
        }
    }

    private fun typeHint(type: Int, country: Int): TvType = when (type) {
        2 -> TvType.Movie
        3 -> TvType.Anime
        4 -> TvType.TvSeries
        else -> if (country == 6) TvType.TvSeries else TvType.AsianDrama
    }

    private fun typeOfCountry(country: String?): TvType =
        if (country.equals("United States", true)) TvType.TvSeries else TvType.AsianDrama

    private fun episodeUrl(slug: String, dramaId: Long, episodeId: Long, number: Double) =
        "$mainUrl/Drama/$slug/Episode-${numberText(number)}?id=$dramaId&ep=$episodeId"

    private fun numberText(number: Double): String =
        if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()

    /** Same slugs as the site: every run of non-alphanumerics becomes '-'. */
    private fun slugOf(title: String): String =
        title.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifBlank { "drama" }

    private fun queryParam(url: String, key: String): String? =
        url.substringAfter('?', "").substringBefore('#').split('&')
            .firstOrNull { it.substringBefore('=') == key }?.substringAfter('=', "")?.takeIf { it.isNotBlank() }

    companion object {
        private const val PAGE_SIZE = 40
        private const val MAX_RECOMMENDATIONS = 20
        /** CsBackup's CoroutineName while it collects backup links (see shared/CsBackup.kt). */
        private const val BACKUP_MARKER = "csasian-backup"
        private val COUNTRIES = mapOf(
            1 to "China", 2 to "South Korea", 3 to "Japan", 4 to "Hong Kong",
            5 to "Thailand", 6 to "United States", 7 to "Taiwan", 8 to "Philippines",
        )
    }
}
