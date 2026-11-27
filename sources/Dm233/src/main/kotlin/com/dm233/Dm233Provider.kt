package com.dm233

import android.util.Log
import com.csasian.shared.CsAdStrip
import com.csasian.shared.CsBackup
import com.csasian.shared.CsQuality
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 233动漫 (www.233dm.com): MacCMS v10 with the stui "the4" template (obfuscated class names, so the
 * parsing below keys on hrefs and attributes rather than CSS classes).
 *  - lists:   /type/{id}-{page}.html (36 per page, open). Types: 1 日漫, 2 国漫, 24 剧场, 3 美漫, 4 特摄,
 *             5 动画 (mostly 动态漫 / AI漫剧 filler, left out).
 *  - latest:  /index.php/ajax/data?mid=1&page=N (JSON, 10 per page, all types, newest first).
 *  - hot:     /dayhot (open), /show/{id}--hits------{page}---.html (behind the click-through gate).
 *  - search:  /search/{kw}----------{page}---.html (24 per page, behind the gate).
 *  - detail:  /anime/{24 hex}.html. Source tabs a[href=#playlistN] -> div#playlistN; the episode links
 *             /anime/{slug}/play/{sid}/{ep}.html carry the real sid (tab order is not the sid).
 *  - player:  the play page's `player_aaaa` ("encrypt":1 = JS escape()) gives "from" and "url".
 *             from=mp4 (精品, the site's own server): url is a token for the v2player iframe, whose page
 *             holds playData(b64, ivHex), AES-128-CBC with [V2_KEY] -> a signed 1080p MP4 (~21 h).
 *             Everything else is a plain supply-network m3u8 (暴风, 量子, 优质, ...).
 * The /show/ and /search/ pages answer a "系统安全验证" page with a 继续访问 button now and then: a bot
 * check that passes by posting base64(Date.now() XOR a 16-byte key from that page) and keeping PHPSESSID.
 */
class Dm233Provider : MainAPI() {
    override var mainUrl = "https://www.233dm.com"
    override var name = "233动漫 Dm233"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
    private val pageHeaders get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
    private val jsonMapper = ObjectMapper()

    private data class Category(val id: Int, val label: String) {
        val type get() = if (id == MOVIE_TYPE) TvType.AnimeMovie else TvType.Anime
    }

    private val categories = listOf(
        Category(1, "日漫"),
        Category(2, "国漫"),
        Category(MOVIE_TYPE, "剧场"),
        Category(3, "美漫"),
        Category(4, "特摄"),
    )
    private val hotCategories = listOf(1, 2, MOVIE_TYPE)

    override val mainPage = listOf(
        MainPageData("最新更新", LATEST_ROW),
        MainPageData("今日热播", DAYHOT_ROW),
    ) + categories.map { MainPageData(it.label, "type:${it.id}") } +
        hotCategories.map { id -> MainPageData("${categories.first { it.id == id }.label}人气榜", "hits:$id") }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        return when {
            data == LATEST_ROW -> latestRow(request.name, page)
            data == DAYHOT_ROW -> {
                if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
                val items = getPage("$mainUrl/dayhot").cards { _, badges ->
                    // Mixed list: the first badge is the category name.
                    if (badges.firstOrNull() == "剧场") TvType.AnimeMovie else TvType.Anime
                }.filterNot { it.second.firstOrNull() == FILLER_LABEL }.map { it.first }
                newHomePageResponse(request.name, items, false)
            }
            else -> {
                val gated = data.startsWith("hits:")
                val id = data.substringAfter(':').toInt()
                val type = if (id == MOVIE_TYPE) TvType.AnimeMovie else TvType.Anime
                val url = when {
                    !gated -> "$mainUrl/type/$id-$page.html"
                    page == 1 -> "$mainUrl/show/$id--hits---------.html"
                    else -> "$mainUrl/show/$id--hits------$page---.html"
                }
                val document = if (gated) getGated(url) else getPage(url)
                val items = document.cards { _, _ -> type }.map { it.first }
                val pager = if (gated) Regex("""/show/$id--hits------(\d+)---\.html""") else Regex("""/type/$id-(\d+)\.html""")
                newHomePageResponse(request.name, items, items.isNotEmpty() && page < lastPage(document, pager, page))
            }
        }
    }

    /** Newest first from the ajax list API (10 per call, all categories): 3 calls per row page. */
    private suspend fun latestRow(rowName: String, page: Int): HomePageResponse {
        val pages = ((page - 1) * 3 + 1)..(page * 3)
        val results = coroutineScope {
            pages.map { p ->
                async {
                    runCatching {
                        jsonMapper.readTree(app.get("$mainUrl/index.php/ajax/data?mid=1&page=$p", headers = pageHeaders).text)
                    }.getOrNull()
                }
            }.awaitAll()
        }
        val pageCount = results.firstNotNullOfOrNull { it?.path("pagecount")?.asInt(0)?.takeIf { n -> n > 0 } } ?: 0
        val items = results.filterNotNull().flatMap { it.path("list").toList() }.mapNotNull { vod ->
            val typeId = vod.path("type_id").asInt(0)
            if (typeId == FILLER_TYPE || typeId !in categories.map { it.id }) return@mapNotNull null
            val title = vod.path("vod_name").asText("").trim().ifBlank { return@mapNotNull null }
            val link = vod.path("detail_link").asText("").takeIf { ANIME_PATH.containsMatchIn(it) } ?: return@mapNotNull null
            if (isAdult(title) || isAdult(vod.path("vod_class").asText(""))) return@mapNotNull null
            val type = if (typeId == MOVIE_TYPE) TvType.AnimeMovie else TvType.Anime
            newAnimeSearchResponse(title, fixUrl(link), type) {
                posterUrl = cleanImage(vod.path("vod_pic").asText(""))
                year = vod.path("vod_year").asText("").take(4).toIntOrNull()
                quality = CsQuality.from(vod.path("vod_remarks").asText(""))
            }
        }.distinctBy { it.url }
        return newHomePageResponse(rowName, items, items.isNotEmpty() && pages.last < pageCount)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        val keyword = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val url = if (page == 1) "$mainUrl/search/$keyword-------------.html"
        else "$mainUrl/search/$keyword----------$page---.html"
        val document = getGated(url)
        val results = document.cards { title, _ ->
            if (title.contains("剧场版") || title.contains("电影")) TvType.AnimeMovie else TvType.Anime
        }.map { it.first }
        val pager = Regex("""/search/[^"/]*----------(\d+)---\.html""")
        return results to (results.isNotEmpty() && page < lastPage(document, pager, page))
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

    /** Strips the ad clips the supply networks splice into their playlists (暴风 /adjump/ etc.). */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? =
        if (extractorLink.type == ExtractorLinkType.M3U8) CsAdStrip.interceptor else null

    private suspend fun loadPage(url: String): LoadResponse {
        val detailUrl = url.substringBefore('#')
        val document = getPage(detailUrl)
        val title = document.selectFirst("h1.title")?.text()?.trim().orEmpty()
            .ifBlank { document.title().substringBefore("免费在线观看").trim() }
            .ifBlank { throw ErrorLoadingException("找不到动漫标题") }

        val info = infoLines(document)
        val genres = info["类型"]?.split(',', '，', '/')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        if (isAdult(title) || genres.any { isAdult(it) }) throw ErrorLoadingException("此内容不可用")
        val tags = (genres + listOfNotNull(info["地区"], info["语言"])).filter { it.isNotBlank() }.distinct()
        val year = info["年份"]?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
        val status = info["状态"]?.takeIf { it.isNotBlank() }
        val people = { label: String, limit: Int ->
            document.select("p.data").firstOrNull { it.ownText().trim().startsWith(label) }
                ?.select("a")?.map { it.text().trim() }?.filter { it.isNotBlank() && it != "内详" && it != "未知" }
                ?.distinct()?.take(limit).orEmpty()
        }
        val actors = (people("声优", 20).map { ActorData(Actor(it)) } +
            people("导演", 3).map { ActorData(Actor(it), roleString = "导演") }).ifEmpty { null }
        val plot = (document.selectFirst(".detail-content")?.text()?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.substringAfter("剧情:")?.trim())
            ?.ifBlank { null }
        val plotText = listOfNotNull(status?.let { "（$it）" }, plot).joinToString("\n").ifBlank { null }
        val poster = document.select("img[data-original]").firstOrNull()?.attr("data-original")?.let { cleanImage(it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val typeId = document.selectFirst("li.active > a[href^=/type/]")?.attr("href")
            ?.let { Regex("""/type/(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val sources = parseSources(document)
        if (sources.isEmpty()) throw ErrorLoadingException("此动漫暂无播放来源")
        val canonical = canonicalEpisodes(sources)
        val singleIsMovie = canonical.size == 1 &&
            (typeId == MOVIE_TYPE || normalizeEpisode(canonical[0].name).any { !it.isDigit() })
        val recommended = document.cards { _, _ -> if (typeId == MOVIE_TYPE) TvType.AnimeMovie else TvType.Anime }
            .map { it.first }.filter { it.url != detailUrl }.distinctBy { it.url }.take(MAX_RECOMMENDATIONS)

        return if (singleIsMovie) {
            newMovieLoadResponse(title, detailUrl, TvType.AnimeMovie, episodeData(detailUrl, canonical[0], movie = true)) {
                posterUrl = poster
                this.plot = plotText
                this.year = year
                this.tags = tags
                this.actors = actors
                this.recommendations = recommended
            }
        } else {
            val episodes = canonical.mapIndexed { position, entry ->
                newEpisode(episodeData(detailUrl, entry, movie = false)) {
                    name = episodeName(entry.name, position)
                    episode = position + 1
                }
            }
            val dubbed = info["语言"]?.let { it.contains("国语") || it.contains("粤语") } == true ||
                title.endsWith("国语") || title.endsWith("粤语")
            newAnimeLoadResponse(title, detailUrl, if (typeId == MOVIE_TYPE) TvType.AnimeMovie else TvType.Anime) {
                posterUrl = poster
                this.plot = plotText
                this.year = year
                this.tags = tags
                this.actors = actors
                this.recommendations = recommended
                addEpisodes(if (dubbed) DubStatus.Dubbed else DubStatus.Subbed, episodes)
            }
        }
    }

    private suspend fun loadOwnLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data = https://www.233dm.com/anime/{slug}.html#ep={normalized name}&n={ep}  (ep empty for movies)
        val detailUrl = data.substringBefore('#')
        val params = data.substringAfter('#', "").split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val wanted = URLDecoder.decode(params["ep"].orEmpty(), "UTF-8")
        val wantedNumber = params["n"]?.toIntOrNull()

        val sources = parseSources(getPage(detailUrl))
        // One episode per source, matched by name (falling back to the same episode number). A single
        // "全集" file never stands in for one episode.
        val targets = sources.mapNotNull { source ->
            val entry = if (wanted.isNotBlank()) {
                source.episodes.firstOrNull { normalizeEpisode(it.name) == wanted }
                    ?: source.episodes.firstOrNull { it.ep == wantedNumber && !it.name.contains("全集") }
            } else {
                source.episodes.firstOrNull()
            }
            entry?.let { source to it }
        }.sortedBy { (source, _) -> serverRank(LABEL_TO_FROM[source.label] ?: "") }
        if (targets.isEmpty()) return false

        // Started in rank order, 3 at a time; each link goes out as soon as it resolves.
        val found = AtomicInteger(0)
        val callbackLock = Any()
        val counts = mutableMapOf<String, Int>()
        val limit = Semaphore(3)
        coroutineScope {
            targets.forEach { (source, entry) ->
                launch {
                    val link = limit.withPermit {
                        withTimeoutOrNull(LINK_TIMEOUT_MS) { resolveLink(source, entry) }
                    } ?: return@launch
                    val label = synchronized(counts) {
                        val count = (counts[link.label] ?: 0) + 1
                        counts[link.label] = count
                        if (count > 1) "${link.label} $count" else link.label
                    }
                    val extractorLink = newExtractorLink(name, "$name $label", link.url, link.type) {
                        quality = link.quality
                        if (link.type == ExtractorLinkType.M3U8) headers = mapOf("User-Agent" to userAgent)
                    }
                    synchronized(callbackLock) {
                        callback(extractorLink)
                        found.incrementAndGet()
                    }
                }
            }
        }
        if (found.get() == 0) Log.w(TAG, "no links for $data")
        return found.get() > 0
    }

    private data class Link(val label: String, val url: String, val type: ExtractorLinkType, val quality: Int)

    /** Fetches the play page's player_aaaa; 精品 (mp4) goes through the v2player decrypt. */
    private suspend fun resolveLink(source: Source, entry: EpisodeEntry): Link? = runCatching {
        val playUrl = "$mainUrl/anime/${entry.slug}/play/${entry.sid}/${entry.ep}.html"
        val html = getText(playUrl)
        val start = html.indexOf("player_aaaa")
        if (start < 0) return null
        val config: JsonNode = extractJsonObject(html, html.indexOf('=', start) + 1)
            ?.let { jsonMapper.readTree(it) } ?: return null
        val raw = config.path("url").asText("").trim()
        if (raw.isBlank()) return null
        val value = when (config.path("encrypt").asInt(0)) {
            1 -> jsUnescape(raw)
            2 -> jsUnescape(base64Decode(raw))
            else -> raw
        }.trim()
        val from = config.path("from").asText("").lowercase()
        val label = FROM_TO_LABEL[from] ?: source.label
        if (from == JINGPIN_FROM) {
            val name = config.path("vod_data").path("vod_name").asText("").ifBlank { "1" }
            val mp4 = resolveJingpin(value, name) ?: return null
            Link(label, mp4, ExtractorLinkType.VIDEO, Qualities.P1080.value)
        } else {
            if (!value.startsWith("http")) return null
            val url = encodeNonAscii(value)
            val type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            Link(label, url, type, Qualities.Unknown.value)
        }
    }.onFailure { Log.w(TAG, "link failed ${source.label} ${entry.slug}/${entry.sid}/${entry.ep}: ${it.message}") }
        .getOrNull()

    /** 精品: v2player page -> playData('<b64>','<iv hex>') -> AES-128-CBC with [V2_KEY] -> MP4 URL. */
    private suspend fun resolveJingpin(token: String, title: String): String? {
        if (token.isBlank()) return null
        val url = "$V2_PLAYER?url=${URLEncoder.encode(token, "UTF-8")}&name=${URLEncoder.encode(title, "UTF-8")}"
        // The player only renders playData for requests coming from the site.
        val html = app.get(url, headers = pageHeaders).text
        val match = Regex("""playData\(\s*'([^']+)'\s*,\s*'([0-9a-fA-F]+)'""").find(html)
        if (match == null) {
            Log.w(TAG, "精品: no playData in v2player page (${html.length} bytes) for $token")
            return null
        }
        val (cipherText, ivHex) = match.destructured
        val plain = try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(V2_KEY.toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec(hexBytes(ivHex)),
            )
            String(cipher.doFinal(base64DecodeArray(cipherText)), Charsets.UTF_8).trim()
        } catch (e: Exception) {
            Log.e(TAG, "精品: AES decrypt failed (key $V2_KEY may have changed, see v2player common.js): ${e.message}")
            return null
        }
        if (!plain.startsWith("http")) {
            Log.e(TAG, "精品: decrypted text is not a URL (key $V2_KEY may have changed): ${plain.take(60)}")
            return null
        }
        return plain
    }

    // ---- HTTP ----

    @Volatile private var sessionId: String? = null
    private val gateLock = Mutex()
    @Volatile private var gatePasses = 0

    private val cookies get() = sessionId?.let { mapOf("PHPSESSID" to it) } ?: emptyMap()

    /** GET with the PHPSESSID session; retries on 429/5xx. */
    private suspend fun getText(url: String): String {
        var last: com.lagradost.nicehttp.NiceResponse? = null
        repeat(3) { attempt ->
            val response = app.get(url, headers = pageHeaders, cookies = cookies)
            response.cookies["PHPSESSID"]?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            if (response.code != 429 && response.code < 500) return response.text
            last = response
            delay(1200L * (attempt + 1))
        }
        return last?.text.orEmpty()
    }

    private suspend fun getPage(url: String): Document = Jsoup.parse(getText(url), url)

    /** For /show/ and /search/: passes the "系统安全验证" click-through when it shows up. */
    private suspend fun getGated(url: String): Document {
        var html = getText(url)
        repeat(2) { attempt ->
            if (!html.contains(GATE_MARK)) return Jsoup.parse(html, url)
            val seen = gatePasses
            gateLock.withLock {
                // Another row may have passed it while we waited for the lock.
                if (gatePasses == seen || attempt > 0) passGate(html)
            }
            html = getText(url)
        }
        if (html.contains(GATE_MARK)) {
            Log.w(TAG, "security check still shown after passing it: $url")
            throw ErrorLoadingException("网站安全验证未通过，请稍后再试")
        }
        return Jsoup.parse(html, url)
    }

    /** Same as the page's 继续访问 button: i = base64(Date.now() XOR key), key read from the page. */
    private suspend fun passGate(gateHtml: String) {
        val key = Regex("""new\s+Uint8Array\(\s*\[([^\]]+)]""").find(gateHtml)?.groupValues?.get(1)
            ?.split(',')?.mapNotNull { parseIntLiteral(it.trim()) }?.takeIf { it.isNotEmpty() }
        if (key == null) {
            Log.w(TAG, "security check: key not found in page")
            return
        }
        val type = Regex("""verify_check\?type=(\w+)""").find(gateHtml)?.groupValues?.get(1) ?: "search"
        // Posting within milliseconds of the page that started the session sometimes gets
        // "invalid_token"; a person takes far longer to click 继续访问.
        delay(GATE_PAUSE_MS)
        val stamp = System.currentTimeMillis().toString().toByteArray(Charsets.UTF_8)
        val token = base64Encode(ByteArray(stamp.size) { (stamp[it].toInt() xor key[it % key.size]).toByte() })
        val response = app.post(
            "$mainUrl/index.php/ajax/verify_check?type=$type",
            headers = pageHeaders + ("X-Requested-With" to "XMLHttpRequest"),
            cookies = cookies,
            data = mapOf("i" to token),
        )
        response.cookies["PHPSESSID"]?.takeIf { it.isNotBlank() }?.let { sessionId = it }
        val ok = Regex(""""code"\s*:\s*1\b""").containsMatchIn(response.text)
        Log.i(TAG, "security check ($type): ${if (ok) "passed" else "failed: ${response.text.take(100)}"}")
        gatePasses++
    }

    // ---- parsing ----

    private data class EpisodeEntry(val name: String, val slug: String, val sid: Int, val ep: Int)
    private data class Source(val label: String, val episodes: List<EpisodeEntry>)

    /** Tab a[href=#playlistN] gives the label, div#playlistN the episodes (with the real sid in each href). */
    private fun parseSources(document: Document): List<Source> =
        document.select("a[href^=#playlist]").mapIndexedNotNull { index, tab ->
            val box = document.getElementById(tab.attr("href").removePrefix("#")) ?: return@mapIndexedNotNull null
            val episodes = box.select("a[href*=/play/]").mapNotNull { link ->
                val (slug, sid, ep) = PLAY_PATH.find(link.attr("href"))?.destructured ?: return@mapNotNull null
                val label = link.text().trim().ifBlank { link.attr("title").trim() }
                EpisodeEntry(label, slug, sid.toInt(), ep.toInt())
            }.distinctBy { it.ep }
            if (episodes.isEmpty()) return@mapIndexedNotNull null
            Source(tab.ownText().trim().ifBlank { "线路 ${index + 1}" }, episodes)
        }

    /** The episode list most sources agree on (ties: the longer one); lone "全集" lists only as a last resort. */
    private fun canonicalEpisodes(sources: List<Source>): List<EpisodeEntry> {
        val candidates = sources.filterNot { it.episodes.size == 1 && it.episodes[0].name.contains("全集") }
            .ifEmpty { sources }
        val size = candidates.groupingBy { it.episodes.size }.eachCount()
            .maxWithOrNull(compareBy<Map.Entry<Int, Int>>({ it.value }, { it.key }))!!.key
        return candidates.first { it.episodes.size == size }.episodes
    }

    /** "类型：动作,科幻 / 地区：韩国 / 年份：2016", "状态：已完结", "语言：日语" -> label to value. */
    private fun infoLines(document: Document): Map<String, String> {
        val out = mutableMapOf<String, String>()
        document.select("p.data").forEach { line ->
            line.text().replace(' ', ' ').split(" / ").forEach { part ->
                val label = part.substringBefore('：', "").trim()
                val value = part.substringAfter('：', "").trim().trimEnd(',')
                if (label.isNotEmpty() && value.isNotEmpty() && label !in out) out[label] = value
            }
        }
        return out
    }

    /** Poster cards: a.lazyload[href=/anime/...][title][data-original] with <b> badges (lang/year, remark). */
    private fun Document.cards(typeOf: (title: String, badges: List<String>) -> TvType): List<Pair<SearchResponse, List<String>>> =
        select("a[href*=/anime/][title][data-original]").mapNotNull { card ->
            val href = card.attr("href").takeIf { ANIME_PATH.containsMatchIn(it) && !it.contains("/play/") }
                ?: return@mapNotNull null
            val title = card.attr("title").trim().ifBlank { return@mapNotNull null }
            if (isAdult(title)) return@mapNotNull null
            val badges = card.select("b").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
            val remark = badges.lastOrNull()
            val year = badges.firstNotNullOfOrNull { Regex("""(?:^|/)((?:19|20)\d{2})$""").find(it)?.groupValues?.get(1) }
            newAnimeSearchResponse(title, fixUrl(href), typeOf(title, badges)) {
                posterUrl = cleanImage(card.attr("data-original"))
                this.year = year?.toIntOrNull()
                quality = CsQuality.from(remark)
            } to badges
        }.distinctBy { it.first.url }

    private fun lastPage(document: Document, pager: Regex, page: Int): Int =
        document.select("a[href]").mapNotNull { pager.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: page

    private fun isAdult(text: String): Boolean {
        val t = text.trim()
        return ADULT_WORDS.any { t.contains(it) } || t == "H" || t.startsWith("H漫") || t.startsWith("H动") ||
            t.contains("H动漫") || t.contains("H番")
    }

    /** "https://as.cfhls.top//upload/x.jpg" -> single slash; blank or bare host -> null. */
    private fun cleanImage(url: String): String? {
        val trimmed = url.trim().takeIf { it.isNotBlank() && !it.startsWith("data:") } ?: return null
        val fixed = fixUrl(trimmed).replace(Regex("""(?<!:)//+"""), "/")
        return fixed.takeIf { Regex("""^https?://[^/]+/.+""").matches(it) }
    }

    private fun episodeData(detailUrl: String, entry: EpisodeEntry, movie: Boolean): String {
        // Movie sources name their single file differently (HD中字 / 正片), so movies match by position.
        val ep = if (movie) "" else URLEncoder.encode(normalizeEpisode(entry.name), "UTF-8")
        return "$detailUrl#ep=$ep&n=${entry.ep}"
    }

    private fun normalizeEpisode(name: String): String {
        val trimmed = name.trim()
        return Regex("""^第?\s*0*(\d+)\s*[集话話期]?$""").find(trimmed)?.groupValues?.get(1) ?: trimmed
    }

    private fun episodeName(raw: String, position: Int): String {
        val normalized = normalizeEpisode(raw)
        return when {
            normalized.isBlank() -> "第 ${position + 1} 集"
            normalized.all { it.isDigit() } -> "第 $normalized 集"
            else -> raw.trim()
        }
    }

    /** 精品 first, then 优质 (1080zyk), 量子, 暴风, other networks. */
    private fun serverRank(from: String): Int = when (from) {
        JINGPIN_FROM -> 0
        "1080zyk" -> 1
        "lzm3u8" -> 2
        "bfzym3u8" -> 3
        "" -> 5
        else -> 4
    }

    /** JavaScript's unescape(): %XX and %uXXXX sequences. */
    private fun jsUnescape(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                if (i + 5 < text.length && text[i + 1] == 'u') {
                    text.substring(i + 2, i + 6).toIntOrNull(16)?.let {
                        out.append(it.toChar()); i += 6; continue
                    }
                }
                if (i + 2 < text.length) {
                    text.substring(i + 1, i + 3).toIntOrNull(16)?.let {
                        out.append(it.toChar()); i += 3; continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** Percent-encodes non-ASCII characters and spaces (暴风 paths like /第01集/index.m3u8). */
    private fun encodeNonAscii(url: String): String {
        val out = StringBuilder(url.length)
        url.forEach { c ->
            if (c.code < 0x80 && c != ' ') out.append(c)
            else c.toString().toByteArray(Charsets.UTF_8).forEach { out.append("%%%02X".format(it.toInt() and 0xff)) }
        }
        return out.toString()
    }

    private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) {
        hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private fun parseIntLiteral(text: String): Int? =
        if (text.startsWith("0x", true)) text.substring(2).toIntOrNull(16) else text.toIntOrNull()

    /** Returns the JSON object that starts at [from], respecting strings and escapes. */
    private fun extractJsonObject(text: String, from: Int): String? {
        val begin = text.indexOf('{', from).takeIf { it >= 0 && it - from < 5 } ?: return null
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

    companion object {
        private const val TAG = "Dm233"
        private const val LATEST_ROW = "latest"
        private const val DAYHOT_ROW = "dayhot"
        private const val MOVIE_TYPE = 24
        private const val FILLER_TYPE = 5
        private const val FILLER_LABEL = "动画"
        private const val MAX_RECOMMENDATIONS = 16
        private const val LINK_TIMEOUT_MS = 15_000L
        private const val GATE_MARK = "系统安全验证"
        private const val GATE_PAUSE_MS = 600L

        /** 精品 server: the v2player iframe and its AES-128 key (UTF-8), from the player's common.js. */
        private const val JINGPIN_FROM = "mp4"
        private const val V2_PLAYER = "https://art.v2player.top:8989/player/"
        private const val V2_KEY = "ABABEF777999CCCD"

        private val ANIME_PATH = Regex("""/anime/[0-9a-f]{16,}\.html""")
        private val PLAY_PATH = Regex("""/anime/([0-9a-f]+)/play/(\d+)/(\d+)\.html""")
        private val ADULT_WORDS = listOf("里番", "裏番", "伦理", "倫理", "福利")

        /** The site's player_list names (static/js/playerconfig.js). */
        private val FROM_TO_LABEL = mapOf(
            "mp4" to "精品",
            "bfzym3u8" to "暴风",
            "lzm3u8" to "量子",
            "ffm3u8" to "非凡",
            "1080zyk" to "优质",
            "rym3u8" to "如意",
            "dyttm3u8" to "天堂",
        )
        private val LABEL_TO_FROM = FROM_TO_LABEL.entries.associate { (from, label) -> label to from }
    }
}
