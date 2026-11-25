package com.asianhome

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Settings screen, built in code (no layout resources). Left: a grid of source cards; selecting a
 * card opens that source's rows as toggle chips just below its line of cards. Right: the chosen
 * rows in home-screen order, with round Up / Down / Remove buttons. Every control is focusable and
 * gets a white ring plus a small zoom when focused, so a TV remote can always see where it is.
 */
class AsianHomeSettings(
    private val context: Context,
    private val store: AsianHomeStore,
    private val selfName: String,
    private val onSaved: () -> Unit,
) {
    private val selected = mutableListOf<PickedRow>()

    /** True while the list is the untouched defaults, so Save keeps following the defaults. */
    private var followingDefaults = store.saved() == null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var dialog: Dialog
    private lateinit var selectedList: LinearLayout
    private lateinit var selectedTitle: TextView

    private val sources: List<MainAPI> = Sources.installed(selfName).sortedBy { it.name.lowercase() }
    private val sourcesByName = sources.associateBy { it.name }

    // Left panel state
    private val cards = mutableMapOf<String, View>()
    private val badges = mutableMapOf<String, TextView>()
    private val chips = mutableMapOf<String, TextView>()
    private var openSource: String? = null
    private var openExpansion: LinearLayout? = null

    private val metrics = context.resources.displayMetrics
    private val density = metrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Float) = v * density
    private val wide = metrics.widthPixels > metrics.heightPixels

    fun show() {
        selected += store.current(selfName)
        dialog = Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(buildRoot())
        dialog.setOnDismissListener { scope.cancel() }
        dialog.show()
    }

    private fun buildRoot(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.BG_TOP, Ui.BG_BOTTOM))
            // Generous side padding keeps everything clear of TV overscan.
            setPadding(dp(28), dp(18), dp(28), dp(14))
            clipChildren = false
        }
        root.addView(buildHeader(), LinearLayout.LayoutParams(match, wrap))

        val columns = LinearLayout(context).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            clipChildren = false
        }
        val left = buildSourcesPanel()
        val right = buildSelectedPanel()
        columns.addView(left, if (wide) LinearLayout.LayoutParams(0, match, 58f) else LinearLayout.LayoutParams(match, 0, 1f))
        columns.addView(
            right,
            if (wide) LinearLayout.LayoutParams(0, match, 42f).apply { leftMargin = dp(16) }
            else LinearLayout.LayoutParams(match, 0, 1f).apply { topMargin = dp(12) },
        )
        root.addView(columns, LinearLayout.LayoutParams(match, 0, 1f).apply { topMargin = dp(14) })

        rebuildSelected()
        val first = sources.firstOrNull()?.let { cards[it.name] }
        root.post { (first ?: root.findFocus())?.requestFocus() }
        return root
    }

    // ---- Header ----

    private fun buildHeader(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        val logo = LogoView(context, "CS", dp(40), dpf(10f))
        bindLogo(logo, listOf(AsianHomeLogos.REPO_LOGO))
        bar.addView(logo)

        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(Ui.text(context, "CS Asian 首页", 20f, Ui.TEXT, Ui.BOLD))
        titles.addView(
            Ui.singleLine(Ui.text(context, "选择并排序首页栏目 · Pick and order your home rows", 12.5f, Ui.TEXT_2)),
            LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(4) },
        )
        bar.addView(titles, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = dp(12) })

        // Clear all asks first: the first press arms it ("Confirm?"), a second press within 3 s clears.
        lateinit var clearButton: android.widget.TextView
        val clearLabel = "清空 Clear all"
        var armed = false
        val disarm = Runnable {
            armed = false
            clearButton.text = clearLabel
            clearButton.setTextColor(Ui.TEXT)
        }
        clearButton = pillButton(clearLabel, primary = false) {
            if (!armed) {
                armed = true
                clearButton.text = "确认清空？ Confirm?"
                clearButton.setTextColor(Ui.ACCENT)
                clearButton.postDelayed(disarm, 3000)
            } else {
                clearButton.removeCallbacks(disarm)
                disarm.run()
                clearAll()
            }
        }
        bar.addView(pillButton("片源状态 Status", primary = false) { AsianHomeStatus(context, selfName).show() })
        bar.addView(clearButton)
        bar.addView(pillButton("取消 Cancel", primary = false) { dialog.dismiss() })
        bar.addView(pillButton("保存 Save", primary = true) { save() })
        return bar
    }

    private fun pillButton(label: String, primary: Boolean, onClick: () -> Unit) =
        Ui.text(context, label, 14f, Ui.TEXT, Ui.MEDIUM).apply {
            gravity = Gravity.CENTER
            val r = dpf(22f)
            background = if (primary) {
                Ui.states(
                    normal = Ui.rounded(Ui.ACCENT, r),
                    focused = Ui.rounded(Ui.ACCENT_FOCUS, r, dp(3), Ui.FOCUS_RING),
                )
            } else {
                Ui.states(
                    normal = Ui.rounded(Color.TRANSPARENT, r, dp(1) + 1, Ui.OUTLINE),
                    focused = Ui.rounded(Color.parseColor("#26FFFFFF"), r, dp(3), Ui.FOCUS_RING),
                )
            }
            setPadding(dp(if (primary) 22 else 18), 0, dp(if (primary) 22 else 18), 0)
            minWidth = dp(if (primary) 104 else 0)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            Ui.focusScale(this, 1.06f, dpf(4f))
            layoutParams = LinearLayout.LayoutParams(wrap, dp(40)).apply { leftMargin = dp(10) }
        }

    // ---- Left panel: source grid with inline row chips ----

    private fun panel(title: View, content: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(Ui.PANEL, dpf(18f))
        setPadding(dp(4), dp(14), dp(4), 0)
        clipChildren = false
        addView(title, LinearLayout.LayoutParams(match, wrap).apply { leftMargin = dp(14); rightMargin = dp(14) })
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            clipToPadding = false
            clipChildren = false
            // Room for the focus zoom so it is never clipped at the panel edges.
            setPadding(dp(10), dp(8), dp(10), dp(14))
            addView(content)
        }
        addView(scroll, LinearLayout.LayoutParams(match, 0, 1f).apply { topMargin = dp(4) })
    }

    private fun panelTitle(title: String, hint: String?): Pair<LinearLayout, TextView> {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val main = Ui.text(context, title, 16f, Ui.TEXT, Ui.BOLD)
        box.addView(main)
        if (hint != null) {
            box.addView(
                Ui.singleLine(Ui.text(context, hint, 12f, Ui.TEXT_2)),
                LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = dp(10) },
            )
        }
        return box to main
    }

    private fun buildSourcesPanel(): View {
        val (title, _) = panelTitle("片源 Sources", "确认键展开 · Select a source to pick its rows")
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        if (sources.isEmpty()) {
            grid.addView(
                Ui.text(context, "没有找到其他片源 · No other sources installed", 14f, Ui.TEXT_2)
                    .apply { setPadding(dp(6), dp(12), dp(6), dp(12)) },
            )
            return panel(title, grid)
        }

        val columns = gridColumns()
        sources.chunked(columns).forEach { line ->
            val group = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
            }
            val cardsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
            }
            val expansion = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                clipChildren = false
                clipToPadding = false
            }
            line.forEach { api ->
                cardsRow.addView(
                    sourceCard(api, group, expansion),
                    LinearLayout.LayoutParams(0, dp(104), 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) },
                )
            }
            repeat(columns - line.size) {
                cardsRow.addView(View(context), LinearLayout.LayoutParams(0, dp(104), 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            }
            group.addView(cardsRow, LinearLayout.LayoutParams(match, wrap))
            group.addView(expansion, LinearLayout.LayoutParams(match, wrap))
            grid.addView(group, LinearLayout.LayoutParams(match, wrap))
        }
        return panel(title, grid)
    }

    /** 3–4 cards per line on a TV, fewer on narrow screens. */
    private fun gridColumns(): Int {
        val screenDp = metrics.widthPixels / density
        val panelDp = if (wide) (screenDp - 56 - 16) * 0.58f else screenDp - 56
        return (panelDp / 128f).toInt().coerceIn(2, 4)
    }

    private fun sourceCard(api: MainAPI, group: LinearLayout, expansion: LinearLayout): View {
        val r = dpf(16f)
        val card = FrameLayout(context).apply {
            background = Ui.states(
                normal = Ui.rounded(Ui.CARD, r),
                focused = Ui.rounded(Ui.CARD_FOCUS, r, dp(3), Ui.FOCUS_RING),
                activated = Ui.rounded(Ui.CARD, r, dp(2), Ui.ACCENT),
                focusedActivated = Ui.rounded(Ui.CARD_FOCUS, r, dp(3), Ui.FOCUS_RING),
            )
            isFocusable = true
            isClickable = true
            clipChildren = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            Ui.focusScale(this, 1.05f, dpf(6f))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(8))
        }
        val logo = LogoView(context, api.name, dp(40), dpf(11f))
        bindSourceLogo(logo, api)
        content.addView(logo)
        val short = AsianHomeProvider.shortName(api.name)
        val rest = api.name.trim().removePrefix(short).trim()
        content.addView(
            Ui.singleLine(Ui.text(context, short, 13.5f, Ui.TEXT, Ui.MEDIUM)).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) },
        )
        if (rest.isNotEmpty()) {
            content.addView(
                Ui.singleLine(Ui.text(context, rest, 11f, Ui.TEXT_2)).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(3) },
            )
        }
        card.addView(content, FrameLayout.LayoutParams(match, match))

        val badge = Ui.text(context, "", 10.5f, Color.WHITE, Ui.BOLD).apply {
            background = Ui.rounded(Ui.ACCENT, dpf(10f))
            setPadding(dp(7), dp(3), dp(7), dp(3))
            visibility = View.GONE
        }
        card.addView(badge, FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
        badges[api.name] = badge
        cards[api.name] = card
        updateBadge(api.name)

        card.setOnClickListener { toggleSource(api, group, expansion) }
        return card
    }

    private fun toggleSource(api: MainAPI, group: LinearLayout, expansion: LinearLayout) {
        val wasOpen = openSource == api.name
        openSource?.let { cards[it]?.isActivated = false }
        openExpansion?.let {
            it.removeAllViews()
            it.visibility = View.GONE
        }
        chips.clear()
        openSource = null
        openExpansion = null
        if (wasOpen) return

        openSource = api.name
        openExpansion = expansion
        cards[api.name]?.isActivated = true
        fillExpansion(expansion, api)
        expansion.visibility = View.VISIBLE
        expansion.alpha = 0f
        expansion.translationY = -dpf(8f)
        expansion.animate().alpha(1f).translationY(0f).setDuration(150).start()
        // Bring the opened line and its chips into view.
        group.post { group.requestRectangleOnScreen(Rect(0, 0, group.width, group.height), false) }
    }

    private fun fillExpansion(box: LinearLayout, api: MainAPI) {
        val rows = Sources.rows(api).distinctBy { it.name }
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.EXPAND, dpf(14f), dp(1), Color.parseColor("#4DFF4D57"))
            setPadding(dp(14), dp(12), dp(14), dp(14))
            clipChildren = false
            clipToPadding = false
        }
        inner.addView(
            Ui.singleLine(Ui.text(context, "${api.name} · ${rows.size} 个栏目 rows", 12.5f, Ui.TEXT_2, Ui.MEDIUM)),
            LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(10) },
        )
        if (rows.isEmpty()) {
            inner.addView(Ui.text(context, "这个片源没有首页栏目 · This source has no home rows", 13f, Ui.TEXT_2))
        } else {
            val flow = FlowLayout(context, dp(8), dp(10)).apply { clipChildren = false }
            rows.forEach { row -> flow.addView(chip(api.name, row)) }
            inner.addView(flow, LinearLayout.LayoutParams(match, wrap))
        }
        box.addView(inner, LinearLayout.LayoutParams(match, wrap).apply { setMargins(dp(6), dp(2), dp(6), dp(8)) })
    }

    private fun chip(provider: String, row: MainPageData): TextView {
        val picked = PickedRow(provider, row.name, row.data, row.horizontalImages)
        val r = dpf(18f)
        return Ui.text(context, row.name, 13.5f, Ui.TEXT, Ui.MEDIUM).apply {
            gravity = Gravity.CENTER
            setTextColor(Ui.textStates(Color.parseColor("#E6E3FF"), Color.WHITE))
            background = Ui.states(
                normal = Ui.rounded(Color.TRANSPARENT, r, dp(1) + 1, Ui.OUTLINE),
                focused = Ui.rounded(Color.parseColor("#26FFFFFF"), r, dp(3), Ui.FOCUS_RING),
                activated = Ui.rounded(Ui.ACCENT, r),
                focusedActivated = Ui.rounded(Ui.ACCENT_FOCUS, r, dp(3), Ui.FOCUS_RING),
            )
            setPadding(dp(16), 0, dp(16), 0)
            height = dp(36)
            maxLines = 1
            isFocusable = true
            isClickable = true
            isActivated = selected.any { it.key == picked.key }
            Ui.focusScale(this, 1.06f, dpf(4f))
            setOnClickListener {
                followingDefaults = false
                if (selected.any { it.key == picked.key }) {
                    selected.removeAll { it.key == picked.key }
                } else {
                    selected += picked
                }
                isActivated = selected.any { it.key == picked.key }
                updateBadge(provider)
                rebuildSelected()
            }
            chips[picked.key] = this
        }
    }

    private fun updateBadge(provider: String) {
        val badge = badges[provider] ?: return
        val count = selected.count { it.provider == provider }
        badge.text = "$count 已选"
        badge.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    // ---- Right panel: chosen rows in order ----

    private fun buildSelectedPanel(): View {
        val (title, main) = panelTitle("已选栏目 Selected rows", null)
        selectedTitle = main
        selectedList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        return panel(title, selectedList)
    }

    /**
     * Rebuilds the right list. [focusIndex] / [focusAction] (0 up, 1 down, 2 remove) say which button
     * gets focus afterwards, so focus follows a row that was just moved.
     */
    private fun rebuildSelected(focusIndex: Int = -1, focusAction: Int = 0) {
        selectedList.removeAllViews()
        selectedTitle.text = "已选栏目 Selected rows (${selected.size})"
        if (selected.isEmpty()) {
            selectedList.addView(emptyState(), LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(40) })
            return
        }
        selected.forEachIndexed { index, row ->
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.rounded(Ui.CARD, dpf(14f))
                setPadding(dp(12), dp(8), dp(10), dp(8))
                clipChildren = false
            }
            line.addView(
                Ui.text(context, "${index + 1}", 12f, Ui.TEXT_2, Ui.BOLD).apply {
                    gravity = Gravity.CENTER
                    background = Ui.rounded(Color.parseColor("#14FFFFFF"), dpf(12f))
                },
                LinearLayout.LayoutParams(dp(24), dp(24)),
            )
            val logo = LogoView(context, row.provider, dp(28), dpf(8f))
            sourcesByName[row.provider]?.let { bindSourceLogo(logo, it) }
            line.addView(logo, LinearLayout.LayoutParams(dp(28), dp(28)).apply { leftMargin = dp(10) })

            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(Ui.singleLine(Ui.text(context, row.name, 14.5f, Ui.TEXT, Ui.BOLD)))
            texts.addView(
                Ui.singleLine(Ui.text(context, row.provider, 11.5f, Ui.TEXT_2)),
                LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(3) },
            )
            line.addView(texts, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = dp(10); rightMargin = dp(6) })

            val up = iconButton("▲", enabled = index > 0) { move(index, -1) }
            val down = iconButton("▼", enabled = index < selected.size - 1) { move(index, 1) }
            val remove = iconButton("✕", enabled = true, danger = true) { remove(index) }
            listOf(up, down, remove).forEach { line.addView(it) }
            selectedList.addView(line, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(8) })

            if (index == focusIndex) {
                val buttons = listOf(up, down, remove)
                val target = buttons[focusAction].takeIf { it.isEnabled } ?: buttons.first { it.isEnabled }
                target.requestFocus()
                target.post { target.requestRectangleOnScreen(Rect(0, -dp(12), target.width, target.height + dp(12)), false) }
            }
        }
    }

    private fun iconButton(glyph: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) =
        Ui.text(context, glyph, if (glyph == "✕") 14f else 12f, Ui.TEXT, Ui.BOLD).apply {
            gravity = Gravity.CENTER
            val r = dpf(18f)
            background = Ui.states(
                normal = Ui.rounded(Color.parseColor("#1AFFFFFF"), r),
                focused = Ui.rounded(if (danger) Ui.ACCENT else Color.parseColor("#40FFFFFF"), r, dp(2) + 1, Ui.FOCUS_RING),
            )
            isEnabled = enabled
            // Disabled buttons are dimmed and skipped by the D-pad.
            isFocusable = enabled
            isClickable = enabled
            alpha = if (enabled) 1f else 0.28f
            Ui.focusScale(this, 1.12f, dpf(4f))
            setOnClickListener { if (isEnabled) onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { leftMargin = dp(6) }
        }

    private fun emptyState(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(Ui.text(context, "🏠", 44f).apply { gravity = Gravity.CENTER })
        addView(
            Ui.text(context, "还没有栏目", 16f, Ui.TEXT, Ui.BOLD).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(14) },
        )
        addView(
            Ui.text(context, "Pick rows from the sources on the left", 13f, Ui.TEXT_2).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(6) },
        )
    }

    private fun move(index: Int, delta: Int) {
        val to = index + delta
        if (to !in selected.indices) return
        followingDefaults = false
        val row = selected.removeAt(index)
        selected.add(to, row)
        rebuildSelected(to, if (delta < 0) 0 else 1)
    }

    private fun remove(index: Int) {
        if (index !in selected.indices) return
        followingDefaults = false
        val row = selected.removeAt(index)
        chips[row.key]?.isActivated = false
        updateBadge(row.provider)
        if (selected.isEmpty()) {
            rebuildSelected()
            (cards[row.provider] ?: sources.firstOrNull()?.let { cards[it.name] })?.requestFocus()
        } else {
            rebuildSelected(minOf(index, selected.size - 1), 2)
        }
    }

    private fun clearAll() {
        selected.clear()
        selected += store.defaults(selfName)
        followingDefaults = true
        chips.forEach { (key, chip) -> chip.isActivated = selected.any { it.key == key } }
        badges.keys.forEach { updateBadge(it) }
        rebuildSelected()
    }

    private fun save() {
        // Untouched defaults are stored as "no choice", so newly installed default sources still show up.
        if (followingDefaults) store.clear() else store.save(selected.toList())
        dialog.dismiss()
        onSaved()
    }

    // ---- Logos ----

    private fun bindSourceLogo(view: LogoView, api: MainAPI) {
        // Once candidates are known, a cached logo shows at once, without the fade.
        scope.launch {
            val urls = AsianHomeLogos.candidates(api)
            urls.firstNotNullOfOrNull { AsianHomeLogos.cached(it) }?.let {
                view.show(it, animate = false)
                return@launch
            }
            AsianHomeLogos.firstBitmap(urls)?.let { view.show(it, animate = true) }
        }
    }

    private fun bindLogo(view: LogoView, urls: List<String>) {
        urls.firstNotNullOfOrNull { AsianHomeLogos.cached(it) }?.let {
            view.show(it, animate = false)
            return
        }
        scope.launch { AsianHomeLogos.firstBitmap(urls)?.let { view.show(it, animate = true) } }
    }

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
}
