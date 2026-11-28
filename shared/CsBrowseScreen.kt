package com.csasian.shared

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.utils.AppContextUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * The category browser of [CsBrowse]: a full-screen page for one section of a source with section
 * tabs, a search box, the site's own filters (one chip row per filter, then 排序) and an endless
 * poster grid. Picking a title opens CloudStream's normal details page for it; when the user comes
 * back from there, the browser shows again where they left it.
 *
 * Built in code like shared/CsSwitcher.kt: dark theme, every control is D-pad focusable with a
 * white ring and a small zoom. SkyStream redraws plugin screens with its own plain widgets, so it
 * gets [SkyScreen]: one vertical list of headings, buttons and text.
 */
object CsBrowseScreen {
    private const val TAG = "CsBrowseScreen"
    private val main = Handler(Looper.getMainLooper())

    /** The browser, kept (hidden) while a details page is open, so it comes back as it was. */
    private var current: Screen? = null

    /** Opens the browser on [category] (or switches the open one to it). Safe from any thread. */
    fun open(browse: CsBrowse, category: CsBrowse.Section, delayMs: Long = 0) {
        if (CsLogos.onDesktop) {
            Log.w(TAG, "no screen to show the category browser on")
            return
        }
        main.postDelayed({
            val activity = CommonActivity.activity
            if (activity == null || activity.isFinishing) {
                Log.w(TAG, "no activity to show the category browser")
                return@postDelayed
            }
            try {
                if (CsLogos.onSkyStream) {
                    SkyScreen(activity, browse, category).show()
                    return@postDelayed
                }
                val screen = current?.takeIf { it.activity === activity && it.browse === browse && it.usable() }
                    ?: Screen(activity, browse).also {
                        current?.discard()
                        current = it
                    }
                screen.show(category)
            } catch (e: Throwable) {
                Log.e(TAG, "open failed", e)
                Toast.makeText(activity, "分类 Categories: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, delayMs)
    }

    /**
     * Opens CloudStream's details page for [card] with the app's own helper
     * (AppContextUtils.loadSearchResult, what the app's home and search use). If that is missing
     * in this app version, falls back to the app's search for the title. Returns false on failure.
     */
    internal fun openDetails(activity: Activity, card: SearchResponse): Boolean {
        try {
            with(AppContextUtils) { activity.loadSearchResult(card) }
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "loadSearchResult failed: $e")
        }
        if (CsLogos.onSkyStream) {
            // SkyStream has neither the helper nor CloudStream's search links; the page stays open.
            Toast.makeText(
                activity,
                "SkyStream 还不能从这里打开影片：请关闭本页，在下方列表或搜索里打开「${card.name}」。\n" +
                    "SkyStream can't open titles from this page yet: close it and open \"${card.name}\" from the list below or Search.",
                Toast.LENGTH_LONG,
            ).show()
            return false
        }
        return try {
            Toast.makeText(activity, "无法直接打开，改为搜索 · Opening search instead", Toast.LENGTH_SHORT).show()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("cloudstreamsearch://" + URLEncoder.encode(card.name, "UTF-8")))
                .setPackage(activity.packageName)
            activity.startActivity(intent)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "search fallback failed: $e")
            Toast.makeText(activity, "无法打开 · Can't open ${card.name}", Toast.LENGTH_LONG).show()
            false
        }
    }

    // ---- Which page is showing (androidx is not visible to plugins, so by reflection) ----

    private object Pages {
        /** The fragment the app's navigation currently shows, or null if this can't be found out. */
        fun top(activity: Activity): Any? = try {
            val fm = activity.javaClass.getMethod("getSupportFragmentManager").invoke(activity)
            val host = call(fm, "getPrimaryNavigationFragment")
                ?: (call(fm, "getFragments") as? List<*>)?.firstOrNull { it?.javaClass?.name?.contains("NavHost") == true }
            val child = host?.let { call(it, "getChildFragmentManager") }
            child?.let { call(it, "getPrimaryNavigationFragment") ?: (call(it, "getFragments") as? List<*>)?.lastOrNull() }
        } catch (_: Throwable) {
            null
        }

        fun resumed(fragment: Any): Boolean = (call(fragment, "isResumed") as? Boolean) == true

        fun url(fragment: Any): String? = try {
            (call(fragment, "getArguments") as? android.os.Bundle)?.getString("url")
        } catch (_: Throwable) {
            null
        }

        fun isHome(fragment: Any): Boolean = fragment.javaClass.name.contains("HomeFragment")

        private fun call(target: Any, name: String): Any? = try {
            target.javaClass.getMethod(name).invoke(target)
        } catch (_: Throwable) {
            null
        }
    }

    // ---- The browser (CloudStream on Android) ----

    private class Screen(val activity: Activity, val browse: CsBrowse) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val metrics = activity.resources.displayMetrics
        private val density = metrics.density
        private fun dp(v: Int) = (v * density).toInt()
        private fun dpf(v: Float) = v * density

        private val widthDp = metrics.widthPixels / density
        private val wide = widthDp >= 720
        private val sidePad = if (wide) 28 else 12
        private val columns = ((widthDp - 2 * sidePad - 20) / (if (wide) 138f else 112f)).toInt().coerceIn(3, 8)
        private val posterPx = ((metrics.widthPixels - dp(2 * sidePad + 20)) / columns).coerceAtLeast(dp(80))

        private var dialog: Dialog? = null
        private var discarded = false
        private var category: CsBrowse.Section = browse.sections.first()
        /** The chosen options by filter key, the sort under CsCategories.SORT; unset = 全部 (all). */
        private var chosen: Map<String, String> = browse.clean(category, emptyMap())
        private var keyword = ""

        // Views
        private lateinit var scroll: ScrollView
        private lateinit var title: TextView
        private lateinit var search: EditText
        private lateinit var clearSearch: TextView
        private lateinit var status: TextView
        private lateinit var grid: LinearLayout
        private lateinit var footer: TextView
        private val tabs = mutableListOf<Pair<CsBrowse.Section, View>>()
        private lateinit var filterRows: LinearLayout
        private val chipGroups = mutableMapOf<String, MutableList<Pair<String?, View>>>()

        // Grid state
        private class Cell(val view: View, val image: ImageView, val placeholder: View, val url: String?, val headers: Map<String, String>) {
            var bound = false
        }
        private val rows = mutableListOf<Pair<LinearLayout, List<Cell>>>()
        private val pending = mutableListOf<CsBrowse.Title>()
        private val seen = mutableSetOf<String>()
        private var page = 0
        private var total = -1
        private var loading = false
        private var ended = false
        private var failed = false
        private var generation = 0
        private var job: Job? = null
        private var firstFocusDone = false
        private var lastFocus: View? = null
        private var watching = false

        fun usable() = !discarded && !activity.isFinishing && !activity.isDestroyed

        fun show(target: CsBrowse.Section) {
            val d = dialog ?: build().also { dialog = it }
            watching = false
            if (target.id != category.id || page == 0 && !loading) selectCategory(target, force = page == 0 && !loading)
            if (!d.isShowing) d.show()
            bindVisible()
            lastFocus?.let { v -> v.post { if (v.isAttachedToWindow) v.requestFocus() } }
        }

        /** Hides the browser but keeps it as it is (results, filters, scroll, focus). */
        fun hide() {
            lastFocus = dialog?.currentFocus ?: lastFocus
            rows.forEach { (_, cells) -> cells.forEach { unbind(it) } }
            dialog?.hide()
        }

        fun discard() {
            if (discarded) return
            discarded = true
            watching = false
            scope.cancel()
            try {
                dialog?.dismiss()
            } catch (_: Throwable) {
            }
            if (current === this) current = null
        }

        // ---- Building ----

        private fun build(): Dialog {
            val d = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
            d.requestWindowFeature(Window.FEATURE_NO_TITLE)
            d.setContentView(buildRoot())
            d.setOnKeyListener { _, keyCode, event -> onKey(keyCode, event) }
            d.setOnCancelListener { hide() }
            return d
        }

        private fun buildRoot(): View {
            val root = FrameLayout(activity).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(BG_TOP, BG_BOTTOM))
            }
            val body = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                setPadding(dp(sidePad), dp(if (wide) 18 else 12), dp(sidePad), dp(24))
            }
            body.addView(buildHeader(), LinearLayout.LayoutParams(MATCH, WRAP))
            if (!wide) body.addView(buildSearchRow(), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            body.addView(buildTabs(), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

            // The section's filters and its sorts; rebuilt when the section changes.
            filterRows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            body.addView(filterRows, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

            status = text("", 13f, TEXT_2).apply { setPadding(dp(8), dp(12), dp(8), dp(4)) }
            body.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))

            grid = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            body.addView(grid, LinearLayout.LayoutParams(MATCH, WRAP))
            footer = text("", 13f, TEXT_2).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(14), dp(8), dp(14))
            }
            body.addView(footer, LinearLayout.LayoutParams(MATCH, WRAP))

            scroll = ScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                isFillViewport = true
                clipChildren = false
                addView(body)
                viewTreeObserver.addOnScrollChangedListener { onScrolled() }
            }
            root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
            return root
        }

        private fun buildHeader(): View {
            val bar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
            }
            val titles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            titles.addView(text("${browse.siteName} · 分类 Categories", 12.5f, TEXT_2))
            title = singleLine(text("", if (wide) 22f else 19f, TEXT, BOLD))
            titles.addView(title, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(3) })
            bar.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (wide) {
                bar.addView(buildSearchRow(), LinearLayout.LayoutParams(dp(420), WRAP))
            }
            bar.addView(pill("关闭 Close") { hide() }, pillParams())
            return bar
        }

        private fun buildSearchRow(): View {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
            }
            search = EditText(activity).apply {
                hint = "在本分类搜索 · Search this category"
                setHintTextColor(TEXT_3)
                setTextColor(TEXT)
                textSize = 14f
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                background = states(
                    normal = rounded(CARD, dpf(20f), dp(1) + 1, OUTLINE),
                    focused = rounded(CARD_FOCUS, dpf(20f), dp(3), FOCUS_RING),
                )
                setPadding(dp(16), 0, dp(16), 0)
                setOnEditorActionListener { _, actionId, event ->
                    val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                    if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                        applySearch()
                        true
                    } else {
                        false
                    }
                }
            }
            row.addView(search, LinearLayout.LayoutParams(0, dp(40), 1f))
            row.addView(pill("搜索 Search") { applySearch() }, pillParams())
            clearSearch = pill("清除 Clear") {
                search.setText("")
                applySearch()
            }.apply { visibility = View.GONE }
            row.addView(clearSearch, pillParams())
            return row
        }

        private fun buildTabs(): View {
            val strip = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }
            browse.sections.forEach { cat ->
                val tab = text("${cat.glyph} ${cat.name}", 15.5f, TEXT, MEDIUM).apply {
                    gravity = Gravity.CENTER
                    val r = dpf(22f)
                    background = states(
                        normal = rounded(CARD, r),
                        focused = rounded(CARD_FOCUS, r, dp(3), FOCUS_RING),
                        activated = rounded(ACCENT, r),
                        focusedActivated = rounded(ACCENT, r, dp(3), FOCUS_RING),
                    )
                    setPadding(dp(18), 0, dp(18), 0)
                    isFocusable = true
                    isClickable = true
                    setOnClickListener { selectCategory(cat) }
                    focusScale(this, 1.07f, dpf(4f))
                }
                strip.addView(tab, LinearLayout.LayoutParams(WRAP, dp(42)).apply { rightMargin = dp(10) })
                tabs += cat to tab
            }
            return horizontal(strip)
        }

        /** A labelled row of chips; [key] names the filter it sets. */
        private fun chipRow(label: String, key: String, options: List<Pair<String?, String>>): View {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
            }
            row.addView(
                text(label, 13.5f, TEXT_2, MEDIUM).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minWidth = dp(if (wide) 46 else 38)
                    if (label.length > 2) setPadding(0, 0, dp(6), 0)
                },
                LinearLayout.LayoutParams(WRAP, WRAP),
            )
            val strip = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                setPadding(dp(4), dp(5), dp(4), dp(5))
            }
            val group = mutableListOf<Pair<String?, View>>()
            options.forEach { (value, name) ->
                val chip = text(name, 13.5f, TEXT).apply {
                    gravity = Gravity.CENTER
                    val r = dpf(16f)
                    background = states(
                        normal = rounded(Color.TRANSPARENT, r, dp(1) + 1, OUTLINE),
                        focused = rounded(CARD_FOCUS, r, dp(3), FOCUS_RING),
                        activated = rounded(CHIP_ON, r),
                        focusedActivated = rounded(CHIP_ON, r, dp(3), FOCUS_RING),
                    )
                    setPadding(dp(13), 0, dp(13), 0)
                    isFocusable = true
                    isClickable = true
                    setOnClickListener { setFilter(key, value) }
                    focusScale(this, 1.08f, dpf(3f))
                }
                strip.addView(chip, LinearLayout.LayoutParams(WRAP, dp(32)).apply { rightMargin = dp(8) })
                group += value to chip
            }
            chipGroups[key] = group
            row.addView(horizontal(strip), LinearLayout.LayoutParams(0, WRAP, 1f))
            return row.also { it.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) } }
        }

        private fun horizontal(content: View) = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            isFillViewport = false
            addView(content)
        }

        // ---- Filters ----

        private fun selectCategory(target: CsBrowse.Section, force: Boolean = false) {
            if (!force && target.id == category.id && page > 0) return
            val previous = category
            category = target
            // A filter the new section offers with the same options keeps its choice (地区, 年份 ...);
            // the others (类型, whose options differ per section) start at 全部 again.
            chosen = browse.clean(
                target,
                chosen.filterKeys { key ->
                    key == CsCategories.SORT ||
                        previous.filters.firstOrNull { it.key == key }?.options == target.filters.firstOrNull { it.key == key }?.options
                },
            )
            title.text = "${target.glyph} ${target.label(" · ")}"
            tabs.forEach { (cat, view) -> view.isActivated = cat.id == target.id }
            chipGroups.clear()
            filterRows.removeAllViews()
            target.filters.forEach { f ->
                filterRows.addView(chipRow(f.name, f.key, listOf<Pair<String?, String>>(null to "全部") + f.options.map { it.value to it.label }))
            }
            if (target.sorts.size > 1) {
                filterRows.addView(chipRow("排序", CsCategories.SORT, target.sorts.map { it.value to it.label }))
            }
            refreshChips()
            reload()
        }

        private fun setFilter(key: String, value: String?) {
            chosen = browse.clean(category, if (value == null) chosen - key else chosen + (key to value))
            refreshChips()
            reload()
        }

        private fun refreshChips() {
            chipGroups.forEach { (key, group) -> group.forEach { (value, view) -> view.isActivated = value == chosen[key] } }
        }

        private fun applySearch() {
            val q = search.text?.toString()?.trim().orEmpty()
            try {
                (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(search.windowToken, 0)
            } catch (_: Throwable) {
            }
            clearSearch.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
            if (q == keyword) return
            keyword = q
            reload()
        }

        // ---- Loading ----

        private fun reload() {
            generation++
            job?.cancel()
            loading = false
            ended = false
            failed = false
            page = 0
            total = -1
            pending.clear()
            seen.clear()
            rows.forEach { (_, cells) -> cells.forEach { unbind(it) } }
            rows.clear()
            grid.removeAllViews()
            loadMore()
        }

        private fun loadMore() {
            if (loading || ended || discarded) return
            loading = true
            failed = false
            val gen = generation
            val next = page + 1
            val section = category
            val f = chosen
            val kw = keyword
            updateStatus()
            job = scope.launch {
                try {
                    val extra = if (next == 1 && kw.isNotBlank()) {
                        async(Dispatchers.IO) { runCatching { browse.translated(section, f, kw) }.getOrDefault(emptyList()) }
                    } else {
                        null
                    }
                    val result = withContext(Dispatchers.IO) { browse.fetch(section, f, kw, next) }
                    val more = extra?.await().orEmpty()
                    if (gen != generation) return@launch
                    page = next
                    ended = result.items.isEmpty() || !result.hasNext
                    append(result.items + more)
                    // Without a total from the site: the count once everything is shown.
                    total = result.total ?: if (ended) shown else -1
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "page $next failed: $e")
                    if (gen == generation) failed = true
                } finally {
                    if (gen == generation) {
                        loading = false
                        updateStatus()
                        // Keep going while the grid doesn't fill the screen yet.
                        scroll.post { if (gen == generation) onScrolled() }
                    }
                }
            }
        }

        private fun updateStatus() {
            val kw = keyword
            val what = if (kw.isNotBlank()) "「$kw」· " else ""
            status.text = when {
                total > 0 -> "${what}共 %,d 部 · %,d titles".format(total, total)
                total == 0 && !loading -> if (kw.isNotBlank()) "没有找到「$kw」· Nothing found" else "没有内容 · Nothing here"
                failed && page == 0 -> "加载失败 · Couldn't load"
                // The site doesn't say how many there are: how many are shown so far.
                shown > 0 -> "${what}已显示 %,d 部 · %,d shown".format(shown, shown)
                else -> "${what}加载中… Loading"
            }
            footer.text = when {
                failed -> "加载失败，按 OK 重试 · Couldn't load, press OK to retry"
                loading && page > 0 -> "加载更多… Loading more"
                ended && page > 0 && total > 0 -> "已经到底了 · That's everything"
                else -> ""
            }
            footer.isFocusable = failed
            footer.isClickable = failed
            footer.setOnClickListener(if (failed) View.OnClickListener { loadMore() } else null)
            footer.background = if (failed) states(rounded(CARD, dpf(16f)), rounded(CARD_FOCUS, dpf(16f), dp(3), FOCUS_RING)) else null
        }

        /** How many titles the grid holds (rows plus those waiting for a full row). */
        private val shown: Int get() = seen.size

        private fun append(items: List<CsBrowse.Title>) {
            val fresh = items.filter { seen.add(it.response.url) }
            // Fill the last row first, so rows stay full while pages arrive.
            pending += fresh
            val last = rows.lastOrNull()
            if (last != null && last.second.size < columns) {
                rows.removeAt(rows.size - 1)
                grid.removeView(last.first)
                last.second.forEach { unbind(it) }
                pending.addAll(0, last.second.mapNotNull { it.view.tag as? CsBrowse.Title })
            }
            val chunk = if (ended) pending.toList() else pending.take(pending.size - pending.size % columns)
            pending.removeAll(chunk.toSet())
            chunk.chunked(columns).forEach { line -> addRow(line) }
            bindVisible()
            if (!firstFocusDone && rows.isNotEmpty()) {
                firstFocusDone = true
                rows.first().second.first().view.requestFocus()
            }
        }

        private fun addRow(line: List<CsBrowse.Title>) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
            }
            val cells = line.map { cell(it) }
            cells.forEach { row.addView(it.view, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(5), dp(6), dp(5), dp(6)) }) }
            repeat(columns - line.size) { row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(5), 0, dp(5), 0) }) }
            grid.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            rows += row to cells
        }

        private fun cell(item: CsBrowse.Title): Cell {
            val r = dpf(12f)
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = states(
                    normal = rounded(Color.TRANSPARENT, r),
                    focused = rounded(CARD_FOCUS, r, dp(3), FOCUS_RING),
                )
                setPadding(dp(4), dp(4), dp(4), dp(6))
                isFocusable = true
                isClickable = true
                clipChildren = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                focusScale(this, 1.07f, dpf(6f))
                tag = item
            }
            val posterHeight = (posterPx * 1.4f).toInt()
            val poster = FrameLayout(activity).apply {
                background = rounded(CARD, dpf(9f))
                clipToOutline = true
            }
            val placeholder = text(category.glyph, 26f, TEXT_3).apply { gravity = Gravity.CENTER }
            poster.addView(placeholder, FrameLayout.LayoutParams(MATCH, MATCH))
            val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            poster.addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
            box.addView(poster, LinearLayout.LayoutParams(MATCH, posterHeight))
            box.addView(
                singleLine(text(item.name, 13.5f, TEXT, MEDIUM)),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6); leftMargin = dp(2); rightMargin = dp(2) },
            )
            if (item.subtitle.isNotBlank()) {
                box.addView(
                    singleLine(text(item.subtitle, 11f, TEXT_2)),
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(3); leftMargin = dp(2); rightMargin = dp(2) },
                )
            }
            box.setOnClickListener { openTitle(item) }
            return Cell(box, image, placeholder, item.image, item.response.posterHeaders.orEmpty())
        }

        // ---- Scrolling: endless pages, and images only near the screen ----

        private fun onScrolled() {
            if (discarded || dialog?.isShowing != true) return
            val content = scroll.getChildAt(0) ?: return
            val bottom = scroll.scrollY + scroll.height
            if (!loading && !ended && !failed && content.height - bottom < scroll.height * 1.5f) loadMore()
            bindVisible()
        }

        private fun bindVisible() {
            if (dialog?.isShowing != true || scroll.height == 0) return
            val top = scroll.scrollY - scroll.height
            val bottom = scroll.scrollY + scroll.height * 2
            rows.forEach { (row, cells) ->
                val y = grid.top + row.top
                val near = y + row.height >= top && y <= bottom || row.height == 0 && y <= bottom
                cells.forEach { if (near) bind(it) else unbind(it) }
            }
        }

        private fun bind(cell: Cell) {
            if (cell.bound) return
            cell.bound = true
            CsImages.bind(scope, cell.image, cell.url, posterPx, cell.headers) { cell.placeholder.visibility = View.GONE }
        }

        private fun unbind(cell: Cell) {
            if (!cell.bound) return
            cell.bound = false
            CsImages.clear(cell.image)
            cell.placeholder.visibility = View.VISIBLE
        }

        private fun onKey(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false
            if (event.action != KeyEvent.ACTION_UP) return true
            // Deep in the grid, Back first returns to the top (tabs and filters).
            val focus = dialog?.currentFocus
            if (scroll.scrollY > dp(120) && focus != null && isInGrid(focus)) {
                scroll.smoothScrollTo(0, 0)
                tabs.firstOrNull { it.first.id == category.id }?.second?.requestFocus()
                return true
            }
            hide()
            return true
        }

        private fun isInGrid(view: View): Boolean {
            var p: Any? = view
            while (p is View) {
                if (p === grid) return true
                p = p.parent
            }
            return false
        }

        // ---- Opening a title ----

        private fun openTitle(item: CsBrowse.Title) {
            val under = Pages.top(activity)
            lastFocus = dialog?.currentFocus
            hide()
            if (!openDetails(activity, item.response)) {
                show(category)
                return
            }
            if (under != null) watchForReturn(under) else Log.w(TAG, "can't see the app's pages; ▶ on the category page reopens the browser")
        }

        /**
         * Shows the browser again once the details page (and any player opened from it) is closed,
         * i.e. when the page that was under the browser is back on top. Gives up quietly if the
         * user goes elsewhere (Home); the category page's ▶ still reopens it.
         */
        private fun watchForReturn(under: Any) {
            watching = true
            val catUrl = browse.urlOf(category)
            val started = System.currentTimeMillis()
            var left = false
            val tick = object : Runnable {
                override fun run() {
                    if (!watching || discarded || !usable()) return
                    if (dialog?.isShowing == true) {
                        watching = false
                        return
                    }
                    val top = Pages.top(activity)
                    if (top == null) {
                        watching = false
                        return
                    }
                    val back = top === under || Pages.url(top) == catUrl
                    if (!left) {
                        if (!back) {
                            left = true
                        } else if (System.currentTimeMillis() - started > 8_000) {
                            // The details page never opened.
                            watching = false
                            show(category)
                            return
                        }
                    } else if (back && Pages.resumed(top)) {
                        watching = false
                        show(category)
                        return
                    } else if (Pages.isHome(top) && top !== under) {
                        watching = false
                        return
                    }
                    main.postDelayed(this, 500)
                }
            }
            main.postDelayed(tick, 500)
        }

        // ---- Small builders ----

        private fun pill(label: String, onClick: () -> Unit) = text(label, 14f, TEXT, MEDIUM).apply {
            gravity = Gravity.CENTER
            val r = dpf(20f)
            background = states(
                normal = rounded(Color.TRANSPARENT, r, dp(1) + 1, OUTLINE),
                focused = rounded(Color.parseColor("#26FFFFFF"), r, dp(3), FOCUS_RING),
            )
            setPadding(dp(16), 0, dp(16), 0)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            focusScale(this, 1.06f, dpf(4f))
        }

        private fun pillParams() = LinearLayout.LayoutParams(WRAP, dp(40)).apply { leftMargin = dp(10) }

        private fun text(value: String, sp: Float, color: Int, face: Typeface? = null) = TextView(activity).apply {
            text = value
            textSize = sp
            setTextColor(color)
            if (face != null) typeface = face
            includeFontPadding = false
        }
    }

    // ---- SkyStream: plain widgets in one vertical list ----

    /**
     * SkyStream draws headings, buttons, text fields and images itself and ignores colours and
     * focus rings, so this keeps to those: section buttons, a search field, the filters as buttons
     * (the chosen one marked ✓), and the titles as buttons with "Load more" at the end.
     */
    private class SkyScreen(private val activity: Activity, private val browse: CsBrowse, start: CsBrowse.Section) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private lateinit var dialog: Dialog
        private lateinit var body: LinearLayout
        private var category = start
        private var chosen: Map<String, String> = browse.clean(start, emptyMap())
        private val items = mutableListOf<CsBrowse.Title>()
        private var page = 0
        private var total = -1
        private var hasNext = false
        private var loading = false
        private var error: String? = null
        private var generation = 0
        private var query = ""

        fun show() {
            dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            dialog.setContentView(ScrollView(activity).apply { addView(body) })
            dialog.setOnDismissListener { scope.cancel() }
            reload()
            dialog.show()
        }

        private fun reload() {
            generation++
            items.clear()
            page = 0
            total = -1
            hasNext = false
            error = null
            loading = false
            loadMore()
        }

        private fun loadMore() {
            if (loading) return
            loading = true
            val gen = generation
            val section = category
            val f = chosen
            val q = query
            rebuild()
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { browse.fetch(section, f, q, page + 1) }
                    if (gen != generation) return@launch
                    page++
                    items += result.items.filter { n -> items.none { it.response.url == n.response.url } }
                    hasNext = result.hasNext && result.items.isNotEmpty()
                    total = result.total ?: if (!hasNext) items.size else -1
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (gen == generation) error = e.message ?: "error"
                } finally {
                    if (gen == generation) {
                        loading = false
                        rebuild()
                    }
                }
            }
        }

        private fun rebuild() {
            body.removeAllViews()
            body.addView(heading("${browse.siteName} · 分类：${category.label()}"))
            body.addView(row(*browse.sections.map { c ->
                button((if (c.id == category.id) "✓ " else "") + c.name) {
                    val previous = category
                    category = c
                    chosen = browse.clean(
                        c,
                        chosen.filterKeys { key ->
                            key == CsCategories.SORT ||
                                previous.filters.firstOrNull { it.key == key }?.options == c.filters.firstOrNull { it.key == key }?.options
                        },
                    )
                    reload()
                }
            }.toTypedArray()))

            val field = EditText(activity).apply {
                hint = "在本分类搜索 · Search this category"
                setText(query)
                isSingleLine = true
            }
            body.addView(row(field, button("搜索 Search") {
                query = field.text?.toString()?.trim().orEmpty()
                reload()
            }, button("关闭 Close") { dialog.dismiss() }))

            category.filters.forEach { f ->
                group(f.name, listOf<Pair<String?, String>>(null to "全部") + f.options.map { it.value to it.label }, chosen[f.key]) {
                    chosen = browse.clean(category, if (it == null) chosen - f.key else chosen + (f.key to it))
                }
            }
            if (category.sorts.size > 1) {
                group("排序 Sort", category.sorts.map { it.value to it.label }, chosen[CsCategories.SORT]) {
                    chosen = browse.clean(category, if (it == null) chosen else chosen + (CsCategories.SORT to it))
                }
            }

            body.addView(heading(
                when {
                    total > 0 -> "共 $total 部 · $total titles" + if (query.isNotBlank()) "（「$query」）" else ""
                    total == 0 -> "没有内容 · Nothing found"
                    error != null -> "加载失败 · Couldn't load: $error"
                    items.isNotEmpty() -> "已显示 ${items.size} 部 · ${items.size} shown"
                    else -> "加载中… Loading"
                },
            ))
            items.forEach { item ->
                val label = if (item.subtitle.isBlank()) item.name else "${item.name}  ·  ${item.subtitle}"
                body.addView(button(label) { openTitle(item) })
            }
            when {
                loading && page > 0 -> body.addView(text("加载更多… Loading more"))
                error != null -> body.addView(button("重试 Retry") { error = null; loadMore() })
                page > 0 && hasNext -> body.addView(button("加载更多 Load more") { loadMore() })
            }
        }

        private fun group(label: String, options: List<Pair<String?, String>>, selected: String?, apply: (String?) -> Unit) {
            body.addView(text(label))
            options.chunked(6).forEach { line ->
                body.addView(row(*line.map { (value, name) ->
                    button((if (value == selected) "✓ " else "") + name) {
                        apply(value)
                        reload()
                    }
                }.toTypedArray()))
            }
        }

        private fun openTitle(item: CsBrowse.Title) {
            if (openDetails(activity, item.response)) {
                try {
                    dialog.dismiss()
                } catch (_: Throwable) {
                }
            }
        }

        private fun heading(value: String) = TextView(activity).apply {
            text = value
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(16, 20, 16, 8)
        }

        private fun text(value: String) = TextView(activity).apply {
            text = value
            setPadding(16, 10, 16, 4)
        }

        private fun button(label: String, onClick: () -> Unit) = Button(activity).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        private fun row(vararg views: View) = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEach { addView(it) }
        }
    }

    // ---- Look (CsSwitcher's palette) ----

    private val BG_TOP = Color.parseColor("#0E0B24")
    private val BG_BOTTOM = Color.parseColor("#1A1546")
    private val CARD = Color.parseColor("#231E4A")
    private val CARD_FOCUS = Color.parseColor("#2F2963")
    private val CHIP_ON = Color.parseColor("#6C5CE7")
    private val ACCENT = Color.parseColor("#FF4D57")
    private val TEXT = Color.WHITE
    private val TEXT_2 = Color.parseColor("#B9B3E6")
    private val TEXT_3 = Color.parseColor("#7F79AE")
    private val OUTLINE = Color.parseColor("#4DB9B3E6")
    private val FOCUS_RING = Color.WHITE
    private const val ANIM_MS = 130L
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    private fun rounded(color: Int, radius: Float, strokePx: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

    /** Background that fades between states; focused wins over activated wins over normal. */
    private fun states(normal: Drawable, focused: Drawable, activated: Drawable? = null, focusedActivated: Drawable? = null) =
        StateListDrawable().apply {
            setEnterFadeDuration(ANIM_MS.toInt())
            setExitFadeDuration(ANIM_MS.toInt())
            if (focusedActivated != null) {
                addState(intArrayOf(android.R.attr.state_focused, android.R.attr.state_activated), focusedActivated)
            }
            addState(intArrayOf(android.R.attr.state_focused), focused)
            if (activated != null) addState(intArrayOf(android.R.attr.state_activated), activated)
            addState(intArrayOf(), normal)
        }

    /** Scales the view up a little while it has focus and keeps it in view inside scroll views. */
    private fun focusScale(view: View, scale: Float, lift: Float) {
        view.setOnFocusChangeListener { v, has ->
            v.animate()
                .scaleX(if (has) scale else 1f)
                .scaleY(if (has) scale else 1f)
                .translationZ(if (has) lift else 0f)
                .setDuration(ANIM_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
            if (has) {
                val pad = (v.height * 0.12f).toInt() + 8
                v.post { v.requestRectangleOnScreen(Rect(-pad, -pad, v.width + pad, v.height + pad), false) }
            }
        }
    }

    private fun singleLine(view: TextView) = view.apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
}
