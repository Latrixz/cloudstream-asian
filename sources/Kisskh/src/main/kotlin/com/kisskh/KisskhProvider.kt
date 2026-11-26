package com.kisskh

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
    override val mainPage = mainPageOf(
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
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (type, country, order) = request.data.split('|').map { it.toIntOrNull() ?: 0 }
        val root = getJson(listUrl(page, type, country, order, PAGE_SIZE))
        val items = root.path("data").mapNotNull { it.toSearchResult(typeHint(type, country)) }
        val total = root.path("totalCount").asInt(0)
        return newHomePageResponse(request.name, items, items.isNotEmpty() && page * PAGE_SIZE < total)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return getJson("$api/DramaList/Search?q=$encoded&type=0")
            .mapNotNull { it.toSearchResult(null) }
            .distinctBy { it.url }
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
