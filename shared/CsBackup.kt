package com.csasian.shared

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Backup links ("备用"), shared by every cloudstream-asian source (compiled into each plugin).
 *
 * When a source loads a title, [remember] notes each episode's title / season / number. When that
 * episode is played, [loadLinks] runs the source's own link lookup and, at the same time, looks for
 * the same title and episode on our other installed sources of the same language. Matches are
 * added as extra links named "备用 <source>", so a dead link has a working alternative.
 *
 * Every plugin has its own copy of this class (separate class loaders), so the "don't search for
 * backups of a backup" marker is a [CoroutineName], which all copies can see.
 */
object CsBackup {
    /** Our sources, by the name each one shows in CloudStream. */
    private val OURS = setOf(
        "欧乐影院 Olevod", "酷播 Kubb", "Movieffm", "冷映 Len", "华视影院 Huavod", "华人影院 Huarw", "CN影院 Csxyy", "泥巴影院 Nbyy", "唐人街 Trjvod", "优视频 UVOD", "华视界 Haotv", "随便看 Suikan", "爱瓜TV Aigua", "低端影视 ddys", "七味网 Qwfilm", "南瓜影视 Nangua", "网飞猫 Ncat", "看看鸭 Kankanya", "看片狂人 Kpkuang", "可可影视 Kkys", "母鸡资源 Mujizy", "磁力熊 Cilixiong", "奈飞工厂 Netflixgc", "共青春影院 Gqc", "黑夜影院 Darkvod", "233动漫 Dm233", "321Movies CS", "Cinejoy CS", "ZXCStream CS", "ReelFren Shorts", "ChartDrama Shorts", "DramaTip Shorts", "DramaFren",
        "独播库 Duboku", "小宝影院 Xiaobao", "Kissasian", "Crackle", "FlixVision", "frameXTV", "Rivestream", "Rivo", "Movy", "OneTouchTV Asia", "Kisskh Asia", "Donghuastream CS", "4KHDHub CS", "AniKoto CS", "AnimeSuge CS", "Animenosub CS", "Animepahe CS", "Dramanice",
    )
    /** Sources whose links only play through their own video interceptor (e.g. Haotv decrypts and
     *  cleans its segments): never offered as backups under another source. */
    private val NOT_AS_BACKUP = setOf("华视界 Haotv", "看看鸭 Kankanya")
    private const val MARKER = "csasian-backup"
    const val ENABLED_KEY = "csasian_backup_links"

    /** Total time spent on backups when the source's own links worked / failed. */
    private const val WAIT_WITH_OWN_MS = 9_000L
    private const val WAIT_WITHOUT_OWN_MS = 25_000L
    private const val PER_SOURCE_MS = 20_000L
    /** How long the check of the source's own links may take, and how many are checked. */
    private const val CHECK_MS = 5_000L
    private const val CHECK_LINKS = 3
    /** Without a working own link: how long to wait for more backups after the first one. */
    private const val AFTER_FIRST_MS = 3_000L
    /** Loading the details page of an episode that wasn't remembered. */
    private const val RECOVER_MS = 8_000L

    private class Ep(val title: String, val year: Int?, val movie: Boolean, val season: Int?, val episode: Int?)

    private val episodes = object : LinkedHashMap<String, Ep>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Ep>?) = size > 3000
    }

    fun enabled(): Boolean = try {
        CloudStreamApp.getKeyClass(ENABLED_KEY, java.lang.Boolean::class.java)?.booleanValue() ?: true
    } catch (_: Throwable) {
        true
    }

    fun setEnabled(on: Boolean) {
        try {
            CloudStreamApp.setKeyClass(ENABLED_KEY, on)
        } catch (_: Throwable) {
        }
    }

    /** Call with every LoadResponse a source returns. */
    fun remember(api: MainAPI, response: LoadResponse?) {
        response ?: return
        try {
            val title = response.name
            val year = response.year
            val list: List<Pair<String, Ep>> = when (response) {
                is MovieLoadResponse -> listOf(response.dataUrl to Ep(title, year, true, null, null))
                is TvSeriesLoadResponse -> numbered(response.episodes.map { it.data to (it.season to it.episode) }, title, year)
                is AnimeLoadResponse -> response.episodes.values.flatMap { eps ->
                    numbered(eps.map { it.data to (it.season to it.episode) }, title, year)
                }
                else -> emptyList()
            }
            synchronized(episodes) {
                list.forEach { (data, ep) ->
                    episodes[data] = ep
                    // The app may turn relative episode data into "<mainUrl>/<data>" before playing.
                    if (!data.startsWith("http")) episodes["${api.mainUrl.trimEnd('/')}/${data.trimStart('/')}"] = ep
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun numbered(items: List<Pair<String, Pair<Int?, Int?>>>, title: String, year: Int?): List<Pair<String, Ep>> =
        items.mapIndexed { i, (data, se) -> data to Ep(title, year, false, se.first, se.second ?: (i + 1)) }

    private fun lookup(data: String): Ep? = synchronized(episodes) {
        episodes[data] ?: episodes.entries.firstOrNull { (k, _) -> !k.startsWith("http") && data.endsWith("/$k") }?.value
    }

    /**
     * Wraps a source's own loadLinks: runs it and the backup search side by side. Throws the
     * source's own error only when no backup link was found either.
     */
    suspend fun loadLinks(
        api: MainAPI,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        /** The source's own lookup; it sends its links to the callback it is given. */
        own: suspend (callback: (ExtractorLink) -> Unit) -> Boolean,
    ): Boolean {
        val isBackupCall = currentCoroutineContext()[CoroutineName]?.name == MARKER
        if (isBackupCall || !enabled()) return own(callback)
        val known = lookup(data)

        return coroutineScope {
            var started = System.currentTimeMillis()
            // Set as soon as any backup link is handed over, so a slow source still searching when
            // time runs out doesn't make the ones already found count as nothing.
            val gotBackup = AtomicBoolean(false)
            val firstBackupAt = java.util.concurrent.atomic.AtomicLong(0L)
            fun startBackups(ep: Ep) = async {
                findBackups(api, ep, subtitleCallback) { link ->
                    if (gotBackup.compareAndSet(false, true)) firstBackupAt.set(System.currentTimeMillis())
                    callback(link)
                }
            }
            // Known episode: search our other sources alongside the source's own lookup.
            var backup = known?.let { startBackups(it) }
            var ownError: Throwable? = null
            val ownLinks = ConcurrentLinkedQueue<ExtractorLink>()
            val ownOk = try {
                own { link ->
                    ownLinks.add(link)
                    callback(link)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ownError = e
                false
            }
            // A link that doesn't load on this device (a certificate it doesn't trust, a dead
            // host, a 404) is no link: then the backups get the full time, not the short one.
            val ownWorks = ownOk && anyLoads(ownLinks.toList())
            if (backup == null) {
                // The episode wasn't seen being loaded in this session (an app that reopens a saved
                // details page, or plays from history). Only when the source has nothing that works,
                // load its details page now to learn the title, then search the others.
                if (ownWorks) return@coroutineScope true
                val ep = withTimeoutOrNull(RECOVER_MS) { recover(api, data) }
                if (ep == null) {
                    if (!ownOk && ownError != null) throw ownError!!
                    return@coroutineScope ownOk
                }
                started = System.currentTimeMillis()
                backup = startBackups(ep)
            }
            val search = backup!!
            val budget = (if (ownWorks) WAIT_WITH_OWN_MS else WAIT_WITHOUT_OWN_MS) - (System.currentTimeMillis() - started)
            val found = (withTimeoutOrNull(budget.coerceAtLeast(1L)) {
                if (ownWorks) return@withTimeoutOrNull search.await()
                // Without a working link of its own, play as soon as backups are here: a few
                // seconds after the first one, for others to join, not after the slowest source.
                while (search.isActive && (firstBackupAt.get() == 0L || System.currentTimeMillis() - firstBackupAt.get() < AFTER_FIRST_MS)) {
                    kotlinx.coroutines.delay(200)
                }
                if (search.isActive) true else search.await()
            } ?: false) || gotBackup.get()
            search.cancel()
            if (!ownOk && !found && ownError != null) throw ownError!!
            ownOk || found
        }
    }

    /**
     * The episode behind [data] when it wasn't remembered: loads the details page it most likely
     * belongs to (the data itself, then without its last part: ".../title/1" → ".../title"), which
     * remembers its episodes, and looks [data] up again.
     */
    private suspend fun recover(api: MainAPI, data: String): Ep? {
        if (!data.startsWith("http")) return null
        val base = data.substringBefore('#').substringBefore('?').trimEnd('/')
        val candidates = listOf(base, base.substringBeforeLast('/')).distinct()
            .filter { it.count { c -> c == '/' } >= 3 }
        for (url in candidates) {
            val response = try {
                api.load(url)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            } ?: continue
            remember(api, response)
            lookup(data)?.let { return it }
            // A movie page whose play data isn't [data] itself is still the title.
            if (response is MovieLoadResponse) return Ep(response.name, response.year, true, null, null)
        }
        return null
    }

    /** Whether any of the first few [links] loads on this device (see [loads]); true for none to check. */
    private suspend fun anyLoads(links: List<ExtractorLink>): Boolean {
        val checkable = links.filter { it.url.startsWith("http") }.take(CHECK_LINKS)
        if (checkable.isEmpty()) return links.isNotEmpty()
        return withTimeoutOrNull(CHECK_MS) {
            coroutineScope {
                checkable.map { link -> async(Dispatchers.IO) { loads(link) } }.awaitAll().any { it }
            }
        } ?: true // Too slow to tell: don't count a slow host as dead.
    }

    /**
     * Opens [link] the way a player would, with the device's own certificate checks (the app's
     * HTTP client skips them, the player doesn't). Only clear failures count: a certificate,
     * connection or DNS error, a 4xx or 5xx, or a playlist that isn't one. A 401/403 may just
     * want headers the player adds, and a 429 is a busy host, so those count as loading.
     */
    private fun loads(link: ExtractorLink): Boolean {
        val conn = try {
            URL(link.url).openConnection() as HttpURLConnection
        } catch (_: Throwable) {
            return true
        }
        return try {
            conn.connectTimeout = 4_000
            conn.readTimeout = 4_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (link.referer.isNotBlank()) conn.setRequestProperty("Referer", link.referer)
            link.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            when {
                code == 401 || code == 403 || code == 429 -> true
                code !in 200..399 -> false
                link.type == ExtractorLinkType.M3U8 ->
                    conn.inputStream.use { String(it.readNBytesCompat(2048)) }.trimStart('\uFEFF', ' ', '\n', '\r').startsWith("#EXTM3U")
                else -> true
            }
        } catch (_: java.net.SocketTimeoutException) {
            true // Slow is not dead.
        } catch (_: Throwable) {
            false // Certificate, connection refused, unknown host...
        } finally {
            conn.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private suspend fun findBackups(
        self: MainAPI,
        ep: Ep,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val others = try {
            APIHolder.allProviders.toList()
                .filter { it.name in OURS && it.name !in NOT_AS_BACKUP && it.name != self.name && it.lang == self.lang }
                .distinctBy { it.name }
        } catch (_: Throwable) {
            emptyList()
        }
        if (others.isEmpty()) return false
        val results = withContext(CoroutineName(MARKER)) {
            others.amap { api ->
                try {
                    withTimeoutOrNull(PER_SOURCE_MS) { linksFrom(api, ep, subtitleCallback, callback) } ?: false
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    false
                }
            }
        }
        return results.any { it }
    }

    private suspend fun linksFrom(
        api: MainAPI,
        ep: Ep,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val want = titleKey(ep.title)
        if (want.first.isEmpty()) return false
        // The title as is, then the show's plain name: sites write a season many ways ("Season 2",
        // "2", "S2", "第二季"), and a search for one spelling may not find another.
        val queries = listOf(ep.title, baseTitle(ep.title)).filter { it.isNotBlank() }.distinctBy { norm(it) }
        val hits = queries.flatMap { search(api, it) }.distinctBy { it.url }.filter { titleKey(it.name) == want }.take(3)
        for (hit in hits) {
            val loaded = api.load(hit.url) ?: continue
            if (!sameYear(ep.year, loaded.year)) continue
            val data = episodeData(loaded, ep) ?: continue
            val links = mutableListOf<ExtractorLink>()
            api.loadLinks(data, false, subtitleCallback) { synchronized(links) { links += it } }
            if (links.isEmpty()) continue
            val label = "备用 ${api.name}"
            synchronized(links) { links.toList() }.forEach { l ->
                callback(
                    newExtractorLink(label, "$label · ${l.name.removePrefix(api.name).trim().ifEmpty { l.name }}" + qualityTag(l.quality), l.url, l.type) {
                        referer = l.referer
                        // Backups never outrank the source's own links (CloudStream plays the
                        // highest quality first); they're for when those fail. SkyStream shows
                        // the number ("1080p · 144p"), so there they keep their real quality.
                        quality = if (CsLogos.onSkyStream) realQuality(l.quality)
                            else com.lagradost.cloudstream3.utils.Qualities.P144.value
                        headers = l.headers
                        extractorData = l.extractorData
                    }
                )
            }
            return true
        }
        return false
    }

    /** " 1080p" for a known quality (the link itself is ranked last, see linksFrom). */
    private fun qualityTag(q: Int): String =
        if (realQuality(q) != com.lagradost.cloudstream3.utils.Qualities.Unknown.value) " ${q}p" else ""

    /** [q], or Unknown for none and for 144, which our sources use to rank a link last, not as a size. */
    private fun realQuality(q: Int): Int =
        if (q in 240..4320 && q != com.lagradost.cloudstream3.utils.Qualities.Unknown.value) q
        else com.lagradost.cloudstream3.utils.Qualities.Unknown.value

    private suspend fun search(api: MainAPI, query: String): List<SearchResponse> = try {
        api.search(query).orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (_: NotImplementedError) {
        try {
            api.search(query, 1)?.items.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun episodeData(loaded: LoadResponse, ep: Ep): String? = when (loaded) {
        is MovieLoadResponse -> if (ep.movie) loaded.dataUrl else null
        is TvSeriesLoadResponse -> if (ep.movie) null else pick(loaded.episodes.map { Triple(it.data, it.season, it.episode) }, ep)
        is AnimeLoadResponse -> if (ep.movie) null else loaded.episodes.values.firstNotNullOfOrNull { list ->
            pick(list.map { Triple(it.data, it.season, it.episode) }, ep)
        }
        else -> null
    }

    private fun pick(list: List<Triple<String, Int?, Int?>>, ep: Ep): String? {
        val numbered = list.mapIndexed { i, (data, season, number) -> Triple(data, season, number ?: (i + 1)) }
        // Same season when both sides know it; otherwise the episode number alone.
        return numbered.firstOrNull { (_, s, n) -> n == ep.episode && (s == null || ep.season == null || s == ep.season) }?.first
    }

    private fun sameYear(a: Int?, b: Int?) = a == null || b == null || kotlin.math.abs(a - b) <= 1

    private val SEASON_MARKS = listOf(
        Regex("(?i)\\s*[-:：,，]?\\s*\\bseason\\s*(\\d{1,2})\\b"),
        Regex("(?i)\\s*[-:：,，]?\\s*\\b(\\d{1,2})(?:st|nd|rd|th)\\s+season\\b"),
        Regex("(?i)\\s*\\bS(\\d{1,2})\\s*$"),
        Regex("\\s*第\\s*([0-9一二三四五六七八九十]+)\\s*[季部]"),
        Regex("\\s+(\\d{1,2})\\s*$"),
    )

    /** The season a title names ("… Season 2", "… 2", "… S2", "…第二季"), and the title without it. */
    private fun splitSeason(title: String): Pair<String, Int> {
        val t = title.replace(Regex("[(（\\[【]\\s*\\d{4}\\s*[)）\\]】]"), "").trim()
        for (mark in SEASON_MARKS) {
            val m = mark.find(t) ?: continue
            val n = seasonNumber(m.groupValues[1]) ?: continue
            if (n in 1..30) return t.removeRange(m.range).trim() to n
        }
        return t to 1
    }

    private fun seasonNumber(s: String): Int? = s.toIntOrNull() ?: run {
        val digits = "一二三四五六七八九"
        when {
            s == "十" -> 10
            s.length == 1 -> digits.indexOf(s[0]).takeIf { it >= 0 }?.plus(1)
            s.startsWith("十") -> digits.indexOf(s[1]).takeIf { it >= 0 }?.plus(11)
            else -> null
        }
    }

    /** What makes two titles the same show and season: "The Glory Fades Season 2" == "The Glory Fades 2". */
    private fun titleKey(title: String): Pair<String, Int> = splitSeason(title).let { (base, n) -> norm(base) to n }

    /** The title without its season, for searching ("The Glory Fades Season 2" → "The Glory Fades"). */
    private fun baseTitle(title: String): String = splitSeason(title).first

    /** Lowercase, without spaces, punctuation or a trailing year: "The Glory (2022)" -> "theglory". */
    private fun norm(title: String): String =
        title.lowercase()
            .replace(Regex("[(（\\[【]\\s*\\d{4}\\s*[)）\\]】]"), "")
            .replace(Regex("[\\s\\p{Punct}·・：:，,。！!？?“”\"'‘’《》〈〉「」『』（）()\\[\\]【】—\\-]+"), "")
}
