package com.asianhome

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

/**
 * A home screen made of rows borrowed from other installed sources. Each row is fetched from its
 * own source, and the items are passed through untouched, so their apiName still points at that
 * source: CloudStream opens them there, never here.
 */
class AsianHomeProvider(
    private val store: AsianHomeStore,
    /** Opens the settings screen (set by the plugin). */
    private val openSetup: (android.content.Context) -> Unit = {},
) : MainAPI() {
    override var name = "CS Asian 首页"
    override var mainUrl = "https://github.com/Latrixz/cloudstream-asian"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = TvType.values().toSet()

    private val mapper = ObjectMapper()

    // A getter, so the app sees a new list every time it reloads the home screen after Save.
    override val mainPage: List<MainPageData>
        get() {
            val usedLabels = mutableSetOf<String>()
            val rows = resolvedRows().map { (api, row) ->
                var label = "${row.name} · ${shortName(api.name)}"
                var n = 2
                while (!usedLabels.add(label)) label = "${row.name} · ${shortName(api.name)} ($n)".also { n++ }
                val data = mapper.createObjectNode()
                    .put("provider", api.name)
                    .put("name", row.name)
                    .put("data", row.data)
                    .put("horizontal", row.horizontal)
                MainPageData(label, mapper.writeValueAsString(data), row.horizontal)
            }
            return rows.ifEmpty { listOf(MainPageData(EMPTY_LABEL, "", false)) }
        }

    /** Saved rows whose source is installed, refreshed with that source's current row data. */
    private fun resolvedRows(): List<Pair<MainAPI, PickedRow>> {
        val sources = Sources.installed(name).associateBy { it.name }
        return store.current(name).mapNotNull { picked ->
            val api = sources[picked.provider] ?: return@mapNotNull null
            val live = Sources.rows(api)
            val match = live.firstOrNull { it.name == picked.name } ?: live.firstOrNull { it.data == picked.data }
            api to (match?.let { PickedRow(api.name, it.name, it.data, it.horizontalImages) } ?: picked)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (request.data.isBlank()) return welcomePage()
        // Each row is caught on its own: one failing source must not blank the whole home screen.
        return try {
            val node = mapper.readTree(request.data)
            val providerName = node.path("provider").asText("")
            val api = Sources.find(providerName, name) ?: return null
            val original = MainPageRequest(
                node.path("name").asText(""),
                node.path("data").asText(""),
                node.path("horizontal").asBoolean(false),
            )
            val response = withTimeoutOrNull(ROW_TIMEOUT_MS) { api.getMainPage(page, original) } ?: return null
            val lists = response.items
            val renamed = if (lists.size == 1) {
                listOf(HomePageList(request.name, lists[0].list, lists[0].isHorizontalImages))
            } else {
                lists.map { HomePageList("${it.name} · ${shortName(api.name)}", it.list, it.isHorizontalImages) }
            }
            newHomePageResponse(renamed, response.hasNext)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    /** Shown instead of an error while no rows are chosen: a welcome card plus the installed sources. */
    private suspend fun welcomePage(): HomePageResponse {
        val setup = newMovieSearchResponse("⚙ 选择你的首页栏目 · Pick your home rows", "$mainUrl/#setup", TvType.Others) {
            posterUrl = AsianHomeLogos.REPO_LOGO
        }
        val sources = Sources.installed(name).sortedBy { it.name.lowercase() }.map { api ->
            val logo = try { AsianHomeLogos.candidates(api).firstOrNull() } catch (_: Throwable) { null }
            newMovieSearchResponse(api.name, "$mainUrl/#setup", TvType.Others) { posterUrl = logo }
        }
        val rows = mutableListOf(HomePageList("👋 欢迎使用 CS Asian 首页 · Welcome: pick the rows you want to see", listOf(setup), false))
        if (sources.isNotEmpty()) rows += HomePageList("你的片源 · Your sources (select one to start)", sources, false)
        return newHomePageResponse(rows, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val apis = resolvedRows().map { it.first }.distinctBy { it.name }
        val perSource = apis.amap { api ->
            try {
                val results = withTimeoutOrNull(SEARCH_TIMEOUT_MS) { searchOne(api, query) }.orEmpty()
                results.take(PER_SOURCE).map { labelled(it, shortName(api.name)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
        }
        // Round-robin, so the first source doesn't fill the top of the results.
        val merged = mutableListOf<SearchResponse>()
        val longest = perSource.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) perSource.forEach { list -> list.getOrNull(i)?.let { merged += it } }
        return merged
    }

    private suspend fun searchOne(api: MainAPI, query: String): List<SearchResponse> = try {
        api.search(query).orEmpty()
    } catch (e: NotImplementedError) {
        // Some sources only implement the paged search.
        api.search(query, 1)?.items.orEmpty()
    }

    /**
     * Prefixes the title with the source's short name. Only the title changes: url and apiName are
     * kept, so the card still opens in its own source. If this app build's copy() has a different
     * signature, the item is returned as it was.
     */
    private fun labelled(item: SearchResponse, source: String): SearchResponse = try {
        val title = "[$source] ${item.name}"
        when (item) {
            is MovieSearchResponse -> item.copy(name = title)
            is TvSeriesSearchResponse -> item.copy(name = title)
            is AnimeSearchResponse -> item.copy(name = title)
            is LiveSearchResponse -> item.copy(name = title)
            else -> item
        }
    } catch (_: Throwable) {
        item
    }

    // Items keep their original apiName, so the app never calls these. They are only a fallback,
    // for a URL that belongs to one of the installed sources.
    override suspend fun load(url: String): LoadResponse? {
        if (url.endsWith("#setup")) {
            // Only a page here: TVs call load() just from highlighting a card (for the preview), so
            // the settings screen opens from the ▶ button instead (see loadLinks).
            return newMovieLoadResponse("选择你的首页栏目 · Pick your home rows", url, TvType.Others, url) {
                posterUrl = AsianHomeLogos.REPO_LOGO
                plot = "按 ▶ 打开设置：选择片源，再选它的栏目，最后按「保存」。\n\n" +
                    "Press ▶ to open the settings: pick a source, choose its rows, then press Save."
            }
        }
        val api = sourceForUrl(url) ?: throw ErrorLoadingException(NOT_HERE)
        return api.load(url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.endsWith("#setup")) {
            openSetupScreen()
            throw ErrorLoadingException("已打开设置 · Settings opened")
        }
        val api = sourceForUrl(data) ?: throw ErrorLoadingException(NOT_HERE)
        return api.loadLinks(data, isCasting, subtitleCallback, callback)
    }

    /** Opens the settings screen over whatever is showing (the player's loading screen). */
    private fun openSetupScreen() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                var c: android.content.Context? = com.lagradost.cloudstream3.CloudStreamApp.context
                while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
                val act = com.lagradost.cloudstream3.CommonActivity.activity ?: (c as? android.app.Activity)
                act?.let { openSetup(it) }
            } catch (_: Throwable) {
            }
        }
    }

    private fun sourceForUrl(url: String): MainAPI? {
        val host = hostOf(url) ?: return null
        return Sources.installed(name).firstOrNull { api -> hostOf(api.mainUrl) == host }
    }

    private fun hostOf(url: String): String? = try {
        URI(url.trim()).host?.lowercase()?.removePrefix("www.")
    } catch (_: Throwable) {
        null
    }

    companion object {
        const val EMPTY_LABEL = "CS Asian 首页"
        private const val ROW_TIMEOUT_MS = 30_000L
        private const val SEARCH_TIMEOUT_MS = 12_000L
        private const val PER_SOURCE = 10
        private const val NOT_HERE =
            "CS Asian 首页 只是聚合首页，请从原来的片源打开。 This item belongs to another source; open it from there."

        /** "欧乐影院 Olevod" -> "欧乐影院"; names without a CJK first word stay whole ("frameXTV"). */
        fun shortName(full: String): String {
            val first = full.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            return if (first.isNotEmpty() && first.any { isCjk(it) }) first else full.trim()
        }

        private fun isCjk(c: Char): Boolean =
            c in '一'..'鿿' || c in '㐀'..'䶿' || c in '぀'..'ヿ' || c in '가'..'힯'
    }
}
