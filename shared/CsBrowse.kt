package com.csasian.shared

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Category browsing for any source: describe the sections and their filters (the model of
 * [CsCategories]) and how to fetch one page, and this gives, per app:
 *  - CloudStream: a "分类 Categories" home row first (generated tiles, no download), whose cards
 *    open our own category page (CsBrowseScreen: section tabs, search, filter chips, endless grid,
 *    newest first, D-pad focus; opening a title uses the app's details page and the page comes
 *    back after Back, filters and scroll kept);
 *  - SkyStream Dev (`cs3engine.categories`): no row; the app's own category chips through
 *    csCategories() / csCategoryPage();
 *  - original SkyStream: no row at all.
 *
 * In a provider (the engine finds the two contract methods by reflection, so they must be public
 * methods of the MainAPI subclass itself):
 *
 *     private val browse = CsBrowse(this, "酷播 Kubb", sections) { section, filters, query, page -> items to hasNext }
 *     override val mainPage = browse.mainPage(listOf(...the normal rows...))
 *     getMainPage:  browse.rowPage(request)?.let { return it }
 *     load:         browse.load(url)?.let { return it }      // first thing, before anything suspends
 *     loadLinks:    browse.reopen(data)                       // first thing
 *     fun csCategories(): String = browse.categoriesJson()
 *     fun csCategoryPage(categoryId: String, filtersJson: String, query: String, page: Int) =
 *         browse.categoryPage(categoryId, filtersJson, query, page)
 *
 * The fetch gets the section id, the chosen filters (filter key to option value, only options the
 * section offers; the sort is always there under [CsCategories.SORT], the first sort by default, so
 * list newest first), the query ("" for none) and the page (from 1). Searches in a category also
 * look up the title translated (shared/CsSearch.kt), after the source's own results.
 * Adult sections, filters and options are never shown.
 */
class CsBrowse private constructor(
    val api: MainAPI,
    /** The source's name in the page header and on the tiles, e.g. "酷播 Kubb". */
    val siteName: String,
    sections: List<Section>,
    /** The sort for the "popular" list on a category's own details page; null = the default sort. */
    private val popularSort: String?,
    /** Where pre-made tiles are hosted ("<base>/<slug>.png"), used only if drawing one fails. */
    private val hostedTiles: String?,
    private val fetcher: suspend (Query) -> Page,
) {
    /**
     * [fetch] returns one page of [sectionId] with [filters] (and the sort under CsCategories.SORT),
     * searched for [query] when it isn't empty: the titles and whether another page follows.
     */
    constructor(
        api: MainAPI,
        siteName: String,
        sections: List<Section>,
        popularSort: String? = null,
        fetch: suspend (sectionId: String, filters: Map<String, String>, query: String, page: Int) -> Pair<List<SearchResponse>, Boolean>,
    ) : this(api, siteName, sections, popularSort, null, { q ->
        val (items, hasNext) = fetch(q.section.id, q.filters, q.query, q.page)
        Page(items.map { Title(it) }, hasNext)
    })

    /** One section (a category) and the filters and sorts the site offers for it. */
    data class Section(
        val id: String,
        val name: String,
        val filters: List<CsCategories.Filter> = emptyList(),
        /** Newest first: the first one is the default. */
        val sorts: List<CsCategories.Option> = emptyList(),
        val english: String = englishFor(name),
        val glyph: String = glyphFor(name),
        /** The tile's gradient (ARGB); null picks one by the section's place. */
        val colors: Pair<Int, Int>? = null,
        /** For the tile's file name; letters, digits and dashes. */
        val slug: String = id,
    ) {
        /** True when the English name is just the name again (app sections such as "ReelShort"). */
        val sameName: Boolean get() = english.trim().equals(name.trim(), ignoreCase = true)

        /** "电影 Movies", or the name once when both are the same ("ReelShort"). */
        fun label(separator: String = " "): String = if (sameName) name else "$name$separator$english"
    }

    /** What a page fetch is asked for. [filters] holds the sort too, under CsCategories.SORT. */
    data class Query(val section: Section, val filters: Map<String, String>, val query: String, val page: Int) {
        val sort: String? get() = filters[CsCategories.SORT]
    }

    /** A title in the grid; [subtitle] is the small line under its name. */
    data class Title(val response: SearchResponse, val subtitle: String = subtitleOf(response)) {
        val name: String get() = response.name
        val image: String? get() = response.posterUrl
    }

    /** One page: the titles, whether another follows, and the total when the site tells it. */
    data class Page(val items: List<Title>, val hasNext: Boolean, val total: Int? = null)

    /** Every section, without adult ones (and adult filters and options). */
    val sections: List<Section> = sections.filterNot { CsCategories.isAdult(it.name) }.mapIndexed { i, s ->
        s.copy(
            filters = s.filters.filterNot { CsCategories.isAdult(it.name) }
                .map { f -> f.copy(options = f.options.filterNot { CsCategories.isAdult(it.label) }) }
                .filter { it.options.isNotEmpty() },
            sorts = s.sorts.filterNot { CsCategories.isAdult(it.label) },
            colors = s.colors ?: familyColors(s.name) ?: PALETTE[i % PALETTE.size],
            slug = s.slug.replace(Regex("[^A-Za-z0-9_-]"), "_"),
        )
    }

    fun section(id: String): Section? = sections.firstOrNull { it.id == id }

    // ---- The "分类 Categories" home row ----

    /** [content] with the "分类 Categories" row first, only where we show categories ourselves (CloudStream). */
    fun mainPage(content: List<MainPageData>): List<MainPageData> =
        if (!showsRow) content else listOf(MainPageData(ROW_NAME, ROW_DATA, true)) + content

    /** The row's cards for getMainPage, or null when [request] is another row. */
    fun rowPage(request: MainPageRequest): HomePageResponse? {
        if (request.data != ROW_DATA) return null
        val cards = if (showsRow) cards() else emptyList()
        return newHomePageResponse(listOf(HomePageList(request.name, cards, true)), false)
    }

    private val showsRow: Boolean get() = !CsCategories.hostShowsCategories && !CsLogos.onSkyStream

    fun urlOf(section: Section) = "${api.mainUrl.trimEnd('/')}$PATH${URLEncoder.encode(section.id, "UTF-8")}"

    /** The section a category card's URL (or play data) stands for, or null for any other URL. */
    fun fromUrl(url: String): Section? {
        if (!url.contains(PATH)) return null
        val id = url.substringAfter(PATH).substringBefore('?').substringBefore('#').substringBefore('/').trim()
        return section(runCatching { URLDecoder.decode(id, "UTF-8") }.getOrDefault(id))
    }

    fun cards(): List<SearchResponse> = sections.map { section ->
        // Not TvType.Others: CloudStream's home hides types outside the user's type filters.
        api.newMovieSearchResponse(section.label(), urlOf(section), TvType.Movie) {
            posterUrl = tileUrl(section)
        }
    }

    // ---- Opening a card ----

    private enum class Caller { PREVIEW, DETAILS, OTHER }

    /** Who called load(), from the class names on the stack (no suspension has happened yet). */
    private fun caller(): Caller {
        val names = Thread.currentThread().stackTrace.map { it.className }
        val result = when {
            names.any { it.contains("HomeViewModel") || it.contains("HomeParentItemAdapterPreview") } -> Caller.PREVIEW
            names.any { it.contains("ResultViewModel") } -> Caller.DETAILS
            else -> Caller.OTHER
        }
        if (result == Caller.OTHER) {
            Log.d(TAG, "load() from: " + names.filter { it.startsWith("com.lagradost") || it.startsWith("dev.") }.distinct().take(8))
        }
        return result
    }

    /**
     * load() for a category card: null for any other URL. Call it first in load(), before anything
     * suspends, since it tells by the call stack who is asking:
     *  - the home preview banner, which loads random home cards in the background: fails on purpose,
     *    so category cards never show up there (and nothing is cached);
     *  - the details page (or the phone's quick-info popup) after the user picked the card: the
     *    category page opens on top, and this returns a small page for the category behind it;
     *  - anything else: only that small page; its ▶ opens the category page (see [reopen]).
     * CloudStream keeps loaded pages for 10 minutes, which would skip load() the next time the same
     * card is picked, so the small page is dropped from that cache again right afterwards.
     */
    suspend fun load(url: String): LoadResponse? {
        val section = fromUrl(url) ?: return null
        when (caller()) {
            Caller.PREVIEW -> throw ErrorLoadingException("分类卡片没有预览 · No preview for category cards")
            Caller.DETAILS -> CsBrowseScreen.open(this, section)
            // SkyStream has no preview banner, and picking a card is the only way load() is called there.
            Caller.OTHER -> if (CsLogos.onSkyStream) CsBrowseScreen.open(this, section)
        }
        forgetCachedPage(url)
        val sort = popularSort?.takeIf { s -> section.sorts.any { it.value == s } }
        val top = runCatching {
            fetch(section, sort?.let { mapOf(CsCategories.SORT to it) }.orEmpty(), "", 1).items.map { it.response }
        }.getOrDefault(emptyList())
        val what = if (sort != null) "热播 · Popular" else "最新 · Newest"
        return api.newMovieLoadResponse(if (section.sameName) "${section.name} · 分类 Category" else "${section.name} · 分类 ${section.english}", url, TvType.Others, url) {
            posterUrl = tileUrl(section)
            backgroundPosterUrl = tileUrl(section)
            plot = "按 ▶ 或 OK 重新打开「${section.name}」分类：筛选、搜索、浏览全部。\n" +
                "Press ▶ / OK to reopen the ${section.english} browser: filters, search and every title.\n\n" +
                "下面是${section.name}的$what ${section.english} below."
            tags = listOf("分类 Category", section.name)
            recommendations = top
        }
    }

    /** First thing in loadLinks: ▶ on a category's own page opens the category page again. */
    fun reopen(data: String) {
        val section = fromUrl(data) ?: return
        CsBrowseScreen.open(this, section, delayMs = 700)
        throw ErrorLoadingException("已打开分类 · Category browser opened")
    }

    /**
     * Drops the category page from CloudStream's 10-minute page cache (a private list, so this uses
     * reflection inside try/catch). If that ever fails, picking the card again shows the category
     * page and its ▶ opens the browser.
     */
    private fun forgetCachedPage(url: String) {
        if (CsLogos.onDesktop) return
        try {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val field = com.lagradost.cloudstream3.ui.APIRepository::class.java.getDeclaredField("cache")
                    field.isAccessible = true
                    val cache = field.get(null) as? MutableList<*> ?: return@postDelayed
                    val ours = cache.toList().filter { entry ->
                        (entry as? com.lagradost.cloudstream3.ui.APIRepository.Companion.SavedLoadResponse)?.response?.url == url
                    }
                    if (ours.isNotEmpty()) cache.removeAll(ours.toSet())
                } catch (e: Throwable) {
                    Log.w(TAG, "page cache not cleared: $e")
                }
            }, 2_500)
        } catch (e: Throwable) {
            Log.w(TAG, "page cache not cleared: $e")
        }
    }

    // ---- Fetching ----

    /** [chosen] without options [section] doesn't offer, and with a valid sort (the first by default). */
    fun clean(section: Section, chosen: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        section.filters.forEach { f ->
            chosen[f.key]?.takeIf { v -> f.options.any { it.value == v } }?.let { out[f.key] = it }
        }
        val sort = chosen[CsCategories.SORT]?.takeIf { s -> section.sorts.any { it.value == s } } ?: section.sorts.firstOrNull()?.value
        if (sort != null) out[CsCategories.SORT] = sort
        return out
    }

    /** One page of [section]; [chosen] is cleaned first. */
    suspend fun fetch(section: Section, chosen: Map<String, String>, query: String, page: Int): Page =
        fetcher(Query(section, clean(section, chosen), query.trim(), page.coerceAtLeast(1))).let { page ->
            // The shared adult filter, by title only. hasNext stays the site's own.
            page.copy(items = page.items.filterNot { CsCategories.isAdultTitle(it.response.name) })
        }

    /** Page 1 of an in-category search also tries the title translated to the source's language. */
    suspend fun translated(section: Section, chosen: Map<String, String>, query: String): List<Title> {
        if (query.isBlank()) return emptyList()
        return CsSearch.extra(query.trim(), api.lang) { q -> fetch(section, chosen, q, 1).items.map { it.response } }
            .map { Title(it) }
    }

    /** A page plus, on page 1 of a search, the translated titles (fetched at the same time), without duplicates. */
    suspend fun fetchWithTranslation(section: Section, chosen: Map<String, String>, query: String, page: Int): Page = coroutineScope {
        val extra = if (page == 1 && query.isNotBlank()) {
            async { runCatching { translated(section, chosen, query) }.getOrDefault(emptyList()) }
        } else {
            null
        }
        val own = fetch(section, chosen, query, page)
        val more = extra?.await().orEmpty()
        if (more.isEmpty()) own else own.copy(items = (own.items + more).distinctBy { it.response.url })
    }

    // ---- SkyStream Dev's own category screen (shared/CsCategories.kt) ----

    /** For `fun csCategories(): String`. */
    fun categoriesJson(): String {
        // English sources show English names in SkyStream's chips ("Movies", "Genre"), not "电影 / 类型 Genre".
        if (!api.lang.startsWith("en")) {
            return CsCategories.json(sections.map { CsCategories.Category(it.id, it.name, it.filters, it.sorts) })
        }
        return CsCategories.json(
            sections.map { s ->
                CsCategories.Category(
                    s.id,
                    englishOnly(s.english).ifEmpty { s.name },
                    s.filters.map { f ->
                        f.copy(
                            name = englishOnly(f.name).ifEmpty { f.name },
                            options = f.options.map { o -> o.copy(label = englishOnly(o.label).ifEmpty { o.label }) },
                        )
                    },
                    s.sorts.map { o -> o.copy(label = englishOnly(o.label).ifEmpty { o.label }) },
                )
            },
        )
    }

    /** "类型 Genre" → "Genre"; a label with no Latin letters comes back empty (the caller keeps the original). */
    private fun englishOnly(label: String): String =
        if (label.none { it in 'A'..'Z' || it in 'a'..'z' }) ""
        else label.replace(Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}·・]+"), " ").replace(Regex("\\s+"), " ").trim()

    /** For `fun csCategoryPage(categoryId, filtersJson, query, page): SearchResponseList`. */
    fun categoryPage(categoryId: String, filtersJson: String, query: String, page: Int): SearchResponseList = runBlocking {
        val section = section(categoryId) ?: throw ErrorLoadingException("没有这个分类 · No category $categoryId")
        val result = fetchWithTranslation(section, CsCategories.chosen(filtersJson), query, page)
        newSearchResponseList(result.items.map { it.response }.distinctBy { it.url }, result.hasNext && result.items.isNotEmpty())
    }

    // ---- Card images ----

    private val tileFiles = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The card image, drawn here once and saved in the app's cache folder, so the cards need no
     * download. If that fails: the hosted tile when there is one, otherwise the repo logo.
     */
    fun tileUrl(section: Section): String {
        val fallback = hostedTiles?.let { "${it.trimEnd('/')}/${section.slug}.png" } ?: CsLogos.REPO_LOGO
        if (CsLogos.onDesktop) return fallback
        tileFiles[section.id]?.let { return it }
        return try {
            val context = com.lagradost.cloudstream3.CloudStreamApp.context ?: return fallback
            val dir = File(context.externalCacheDir ?: context.cacheDir, "csasian-categories").apply { mkdirs() }
            val owner = api.javaClass.simpleName.replace(Regex("[^A-Za-z0-9]"), "")
            val file = File(dir, "$owner-v$TILE_VERSION-${section.slug}.png")
            if (!file.exists() || file.length() == 0L) {
                val tmp = File(dir, "${file.name}.tmp")
                FileOutputStream(tmp).use { drawTile(section).compress(Bitmap.CompressFormat.PNG, 100, it) }
                tmp.renameTo(file)
            }
            ("file://" + file.absolutePath).also { tileFiles[section.id] = it }
        } catch (e: Throwable) {
            Log.w(TAG, "tile not drawn: $e")
            fallback
        }
    }

    /**
     * The card image, sized like CloudStream's horizontal home cards: they are 180 x 114 dp (its
     * HomeChildItemAdapter, scaled by the poster-size setting on TV and phone alike) and crop to fill,
     * so the tile is 30:19, 960 x 608. Everything that matters stays inside the middle 70% x 60%.
     * A rich diagonal gradient in the section's colour family, its icon as a large white watermark on
     * the right, the English name big and bold in the middle and the Chinese name under it.
     */
    fun drawTile(section: Section, width: Int = TILE_W, height: Int = TILE_H): Bitmap {
        val (from, to) = section.colors ?: PALETTE.first()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val w = width.toFloat()
        val h = height.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Diagonal gradient: light top-left, the family's colours, deep bottom-right.
        paint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(mix(from, Color.WHITE, 0.12f), from, to, mix(to, Color.BLACK, 0.35f)),
            floatArrayOf(0f, 0.3f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w, h, paint)
        // A soft glow top-left, then a vignette that darkens the edges.
        paint.shader = RadialGradient(w * 0.22f, h * 0.18f, w * 0.65f, Color.argb(60, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = RadialGradient(
            w / 2, h / 2, w * 0.62f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(115, 0, 0, 0)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // The icon: a large white watermark, right side, partly off the edge.
        val iconSize = h * 0.72f
        c.saveLayerAlpha(0f, 0f, w, h, 46)
        drawIcon(c, iconFor(section), w - iconSize * 0.30f, h * 0.52f, iconSize)
        c.restore()

        // A thin inner outline with round corners, for a card-like feel inside the crop.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.006f
        paint.color = Color.argb(40, 255, 255, 255)
        val inset = h * 0.05f
        c.drawRoundRect(RectF(inset, inset, w - inset, h - inset), h * 0.08f, h * 0.08f, paint)
        paint.style = Paint.Style.FILL

        // Text, centred in the safe area: the English name as big as fits on one line, the Chinese under it.
        // The text fits a little inside the safe width, so the longest names don't touch its edge.
        val safeW = w * 0.70f * 0.94f
        val safeH = h * 0.60f
        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.typeface = bold
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE
        paint.setShadowLayer(h * 0.022f, 0f, h * 0.008f, Color.argb(130, 0, 0, 0))
        var big = h * 0.25f
        paint.textSize = big
        val englishWidth = paint.measureText(section.english)
        if (englishWidth > safeW) big *= safeW / englishWidth
        val small = (big * 0.55f).coerceIn(h * 0.10f, h * 0.15f)
        paint.textSize = small
        val chineseWidth = paint.measureText(section.name)
        val smallSize = if (chineseWidth > safeW) small * safeW / chineseWidth else small
        val gap = h * 0.045f
        // When the English name is the name again ("ReelShort"), it is drawn once.
        val block = (if (section.sameName) big * 0.74f else big * 0.74f + gap + smallSize * 0.88f).coerceAtMost(safeH)
        val top = (h - block) / 2
        paint.textSize = big
        c.drawText(section.english, w / 2, top + big * 0.74f, paint)
        if (section.sameName) return bmp
        paint.textSize = smallSize
        paint.color = Color.argb(230, 255, 255, 255)
        c.drawText(section.name, w / 2, top + big * 0.74f + gap + smallSize * 0.88f, paint)
        return bmp
    }

    private enum class Icon { FILM, TV, MIC, STAR, GLOBE, PHONE, PLAY }

    private fun iconFor(section: Section): Icon {
        val n = section.name + " " + section.english
        return when {
            listOf("短剧", "微短剧", "爽文", "Short").any { n.contains(it) } -> Icon.PHONE
            listOf("纪录", "Documentary").any { n.contains(it) } -> Icon.GLOBE
            listOf("综艺", "真人秀", "音乐", "Variety", "Music").any { n.contains(it) } -> Icon.MIC
            listOf("动漫", "动画", "番", "漫", "Anime", "Animation", "特摄").any { n.contains(it) } -> Icon.STAR
            listOf("剧", "电视", "Series", "Drama").any { n.contains(it) } -> Icon.TV
            listOf("电影", "影片", "片", "Movie").any { n.contains(it) } -> Icon.FILM
            else -> Icon.PLAY
        }
    }

    /** A simple white vector icon centred at ([cx], [cy]), [s] tall. */
    private fun drawIcon(c: Canvas, icon: Icon, cx: Float, cy: Float, s: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val path = android.graphics.Path()
        when (icon) {
            Icon.FILM -> {
                // A film reel: a disc with five holes and a hub.
                path.fillType = android.graphics.Path.FillType.EVEN_ODD
                val r = s * 0.5f
                path.addCircle(cx, cy, r, android.graphics.Path.Direction.CW)
                for (i in 0 until 5) {
                    val a = Math.toRadians(-90.0 + i * 72.0)
                    path.addCircle(cx + (r * 0.55f * Math.cos(a)).toFloat(), cy + (r * 0.55f * Math.sin(a)).toFloat(), r * 0.2f, android.graphics.Path.Direction.CW)
                }
                path.addCircle(cx, cy, r * 0.08f, android.graphics.Path.Direction.CW)
                c.drawPath(path, p)
            }
            Icon.TV -> {
                // A screen with antennae and a stand.
                val sw = s * 1.15f
                val sh = s * 0.72f
                val left = cx - sw / 2
                val top = cy - sh / 2 + s * 0.08f
                path.fillType = android.graphics.Path.FillType.EVEN_ODD
                path.addRoundRect(RectF(left, top, left + sw, top + sh), s * 0.08f, s * 0.08f, android.graphics.Path.Direction.CW)
                val b = s * 0.08f
                path.addRoundRect(RectF(left + b, top + b, left + sw - b, top + sh - b), s * 0.04f, s * 0.04f, android.graphics.Path.Direction.CW)
                c.drawPath(path, p)
                p.strokeWidth = s * 0.06f
                p.strokeCap = Paint.Cap.ROUND
                c.drawLine(cx, top, cx - s * 0.22f, top - s * 0.26f, p)
                c.drawLine(cx, top, cx + s * 0.22f, top - s * 0.26f, p)
                c.drawRoundRect(RectF(cx - s * 0.28f, top + sh + s * 0.05f, cx + s * 0.28f, top + sh + s * 0.11f), s * 0.03f, s * 0.03f, p)
            }
            Icon.MIC -> {
                // A microphone: capsule, holder, stem and base.
                val cw = s * 0.34f
                c.drawRoundRect(RectF(cx - cw / 2, cy - s * 0.5f, cx + cw / 2, cy + s * 0.08f), cw / 2, cw / 2, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = s * 0.06f
                p.strokeCap = Paint.Cap.ROUND
                c.drawArc(RectF(cx - s * 0.3f, cy - s * 0.3f, cx + s * 0.3f, cy + s * 0.22f), 0f, 180f, false, p)
                c.drawLine(cx, cy + s * 0.22f, cx, cy + s * 0.4f, p)
                c.drawLine(cx - s * 0.18f, cy + s * 0.42f, cx + s * 0.18f, cy + s * 0.42f, p)
            }
            Icon.STAR -> {
                // A five-pointed star.
                val outer = s * 0.52f
                val inner = outer * 0.45f
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) outer else inner
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    val x = cx + (r * Math.cos(a)).toFloat()
                    val y = cy + (r * Math.sin(a)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                p.strokeJoin = Paint.Join.ROUND
                c.drawPath(path, p)
            }
            Icon.GLOBE -> {
                // A globe: outline, equator, latitudes and meridians.
                val r = s * 0.48f
                p.style = Paint.Style.STROKE
                p.strokeWidth = s * 0.05f
                c.drawCircle(cx, cy, r, p)
                c.drawOval(RectF(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r), p)
                c.drawLine(cx, cy - r, cx, cy + r, p)
                c.drawLine(cx - r, cy, cx + r, cy, p)
                val lat = r * 0.5f
                val half = Math.sqrt((r * r - lat * lat).toDouble()).toFloat()
                c.drawLine(cx - half, cy - lat, cx + half, cy - lat, p)
                c.drawLine(cx - half, cy + lat, cx + half, cy + lat, p)
            }
            Icon.PHONE -> {
                // A phone with a play button on its screen.
                val pw = s * 0.56f
                val ph = s
                val left = cx - pw / 2
                val top = cy - ph / 2
                path.fillType = android.graphics.Path.FillType.EVEN_ODD
                path.addRoundRect(RectF(left, top, left + pw, top + ph), s * 0.09f, s * 0.09f, android.graphics.Path.Direction.CW)
                val b = s * 0.05f
                path.addRoundRect(RectF(left + b, top + s * 0.11f, left + pw - b, top + ph - s * 0.11f), s * 0.03f, s * 0.03f, android.graphics.Path.Direction.CW)
                c.drawPath(path, p)
                val t = android.graphics.Path()
                t.moveTo(cx - s * 0.08f, cy - s * 0.12f)
                t.lineTo(cx + s * 0.13f, cy)
                t.lineTo(cx - s * 0.08f, cy + s * 0.12f)
                t.close()
                c.drawPath(t, p)
            }
            Icon.PLAY -> {
                path.fillType = android.graphics.Path.FillType.EVEN_ODD
                path.addCircle(cx, cy, s * 0.5f, android.graphics.Path.Direction.CW)
                path.moveTo(cx - s * 0.14f, cy - s * 0.22f)
                path.lineTo(cx + s * 0.24f, cy)
                path.lineTo(cx - s * 0.14f, cy + s * 0.22f)
                path.close()
                c.drawPath(path, p)
            }
        }
    }

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    companion object {
        const val ROW_NAME = "分类 Categories"
        const val ROW_DATA = "csasian-categories"
        private const val PATH = "/csasian-category/"
        private const val TAG = "CsBrowse"
        // v2: 30:19 tiles with a safe area (v1 was 16:9, and CloudStream's crop cut its text).
        private const val TILE_VERSION = 3
        private const val TILE_W = 960
        private const val TILE_H = 608

        /**
         * Like the main constructor, but [fetch] gets the whole [Query] and returns a [Page] (with a
         * total and per-title subtitles); [hostedTiles] is a fallback for the drawn tiles.
         */
        fun detailed(
            api: MainAPI,
            siteName: String,
            sections: List<Section>,
            popularSort: String? = null,
            hostedTiles: String? = null,
            fetch: suspend (Query) -> Page,
        ) = CsBrowse(api, siteName, sections, popularSort, hostedTiles, fetch)

        /** Tile gradients, picked by a section's place when it names none. */
        private val PALETTE = listOf(
            0xFFE53950.toInt() to 0xFF6A1B9A.toInt(),
            0xFF1E88E5.toInt() to 0xFF283593.toInt(),
            0xFFFB8C00.toInt() to 0xFFC2185B.toInt(),
            0xFF8E24AA.toInt() to 0xFF3949AB.toInt(),
            0xFF00897B.toInt() to 0xFF1B5E20.toInt(),
            0xFFF06292.toInt() to 0xFF7B1FA2.toInt(),
            0xFF43A047.toInt() to 0xFF00695C.toInt(),
            0xFF5C6BC0.toInt() to 0xFF1A237E.toInt(),
        )

        private val NAMES = listOf(
            listOf("短剧", "微短剧", "爽文") to ("Short Dramas" to "📱"),
            listOf("纪录") to ("Documentary" to "🌏"),
            listOf("综艺", "真人秀") to ("Variety" to "🎤"),
            listOf("国漫") to ("Chinese Anime" to "🐉"),
            listOf("日漫") to ("Japanese Anime" to "🐉"),
            listOf("美漫") to ("Western Animation" to "🦸"),
            listOf("动漫", "动画", "番剧", "新番") to ("Anime" to "🐉"),
            listOf("少儿", "儿童", "亲子") to ("Kids" to "🧸"),
            listOf("剧场") to ("Anime Movies" to "🎬"),
            listOf("特摄") to ("Tokusatsu" to "🦖"),
            listOf("体育", "赛事") to ("Sports" to "⚽"),
            listOf("音乐", "MV") to ("Music" to "🎵"),
            listOf("国产剧", "大陆剧", "陆剧") to ("Chinese Dramas" to "📺"),
            listOf("港剧", "香港剧") to ("Hong Kong Dramas" to "📺"),
            listOf("台剧", "台湾剧") to ("Taiwanese Dramas" to "📺"),
            listOf("韩剧") to ("Korean Dramas" to "📺"),
            listOf("日剧") to ("Japanese Dramas" to "📺"),
            listOf("泰剧") to ("Thai Dramas" to "📺"),
            listOf("美剧", "欧美剧") to ("Western Series" to "📺"),
            listOf("电视剧", "剧集", "连续剧", "电视") to ("TV Series" to "📺"),
            listOf("电影", "影片") to ("Movies" to "🎬"),
        )

        /** A colour family per kind of section, so 电影 / 电视剧 / 综艺 / 动漫 / 纪录片 / 短剧 look the same in every source. */
        private fun familyColors(name: String): Pair<Int, Int>? = when {
            listOf("短剧", "微短剧", "爽文").any { name.contains(it) } -> 0xFFF06292.toInt() to 0xFF7B1FA2.toInt()
            name.contains("纪录") -> 0xFF00897B.toInt() to 0xFF1B5E20.toInt()
            listOf("综艺", "真人秀").any { name.contains(it) } -> 0xFFFB8C00.toInt() to 0xFFC2185B.toInt()
            listOf("动漫", "动画", "番", "漫", "特摄").any { name.contains(it) } -> 0xFF8E24AA.toInt() to 0xFF3949AB.toInt()
            listOf("剧", "电视").any { name.contains(it) } -> 0xFF1E88E5.toInt() to 0xFF283593.toInt()
            listOf("电影", "影片", "片").any { name.contains(it) } -> 0xFFE53950.toInt() to 0xFF6A1B9A.toInt()
            else -> null
        }

        private fun match(name: String) = NAMES.firstOrNull { (words, _) -> words.any { name.contains(it) } }?.second

        /** "电影" → "Movies", "短剧" → "Short Dramas", ...; the name itself when unknown. */
        fun englishFor(name: String): String = match(name)?.first ?: name

        fun glyphFor(name: String): String = match(name)?.second ?: "🎞"

        /** The small line under a title: its year and score, when the card has them. */
        fun subtitleOf(item: SearchResponse): String {
            val year = when (item) {
                is MovieSearchResponse -> item.year
                is TvSeriesSearchResponse -> item.year
                is AnimeSearchResponse -> item.year
                else -> null
            }
            val score = runCatching { item.score?.toDouble(10) }.getOrNull()?.takeIf { it > 0 }
            return listOfNotNull(
                year?.takeIf { it > 0 }?.toString(),
                score?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) },
            ).joinToString(" · ")
        }

        /** Options from plain labels (value = label). */
        fun options(vararg labels: String): List<CsCategories.Option> = labels.map { CsCategories.Option(it) }

        /** Year options: the last [count] years, this year first. */
        fun years(count: Int = 12): List<CsCategories.Option> {
            val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            return (now downTo now - count + 1).map { CsCategories.Option(it.toString()) }
        }
    }
}
