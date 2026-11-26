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
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
        "欧乐影院 Olevod", "酷播 Kubb", "Movieffm", "冷映 Len", "华视影院 Huavod", "华人影院 Huarw", "泥巴影院 Nbyy", "华视界 Haotv", "随便看 Suikan", "爱瓜TV Aigua", "低端影视 ddys",
        "独播库 Duboku", "小宝影院 Xiaobao", "Kissasian", "Crackle", "FlixVision", "frameXTV", "Rivestream", "Rivo", "Movy",
    )
    /** Sources whose links only play through their own video interceptor (e.g. Haotv decrypts and
     *  cleans its segments): never offered as backups under another source. */
    private val NOT_AS_BACKUP = setOf("华视界 Haotv")
    private const val MARKER = "csasian-backup"
    const val ENABLED_KEY = "csasian_backup_links"

    /** Total time spent on backups when the source's own links worked / failed. */
    private const val WAIT_WITH_OWN_MS = 9_000L
    private const val WAIT_WITHOUT_OWN_MS = 25_000L
    private const val PER_SOURCE_MS = 20_000L

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
        own: suspend () -> Boolean,
    ): Boolean {
        val isBackupCall = currentCoroutineContext()[CoroutineName]?.name == MARKER
        val ep = if (isBackupCall || !enabled()) null else lookup(data)
        if (ep == null) return own()

        return coroutineScope {
            val started = System.currentTimeMillis()
            val backup = async { findBackups(api, ep, subtitleCallback, callback) }
            var ownError: Throwable? = null
            val ownOk = try {
                own()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ownError = e
                false
            }
            val budget = (if (ownOk) WAIT_WITH_OWN_MS else WAIT_WITHOUT_OWN_MS) - (System.currentTimeMillis() - started)
            val found = withTimeoutOrNull(budget.coerceAtLeast(1L)) { backup.await() } ?: false
            backup.cancel()
            if (!ownOk && !found && ownError != null) throw ownError
            ownOk || found
        }
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
        val want = norm(ep.title)
        if (want.isEmpty()) return false
        val hits = search(api, ep.title).filter { norm(it.name) == want }.take(3)
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
                        // highest quality first); they're for when those fail.
                        quality = com.lagradost.cloudstream3.utils.Qualities.P144.value
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
        if (q in 144..4320 && q != com.lagradost.cloudstream3.utils.Qualities.Unknown.value) " ${q}p" else ""

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

    /** Lowercase, without spaces, punctuation or a trailing year: "The Glory (2022)" -> "theglory". */
    private fun norm(title: String): String =
        title.lowercase()
            .replace(Regex("[(（\\[【]\\s*\\d{4}\\s*[)）\\]】]"), "")
            .replace(Regex("[\\s\\p{Punct}·・：:，,。！!？?“”\"'‘’《》〈〉「」『』（）()\\[\\]【】—\\-]+"), "")
}
