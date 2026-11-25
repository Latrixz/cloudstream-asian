package com.asianhome

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Settings dialog, built in code (no layout resources). Left: every installed source, each one
 * expanding to its rows with checkboxes. Right: the chosen rows in home-screen order, with
 * Up / Down / Remove. Every control is a focusable Button or CheckBox, so a TV remote can reach it.
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

    private val checkBoxes = mutableMapOf<String, CheckBox>()
    private val headers = mutableMapOf<String, Button>()
    private var updatingChecks = false

    private lateinit var dialog: Dialog
    private lateinit var selectedList: LinearLayout
    private lateinit var selectedTitle: TextView

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    fun show() {
        selected += store.current(selfName)
        dialog = Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(buildRoot())
        dialog.show()
    }

    private fun buildRoot(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 18, 18))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(text("CS Asian 首页 · 设置 Settings", 20f, bold = true), LinearLayout.LayoutParams(0, wrap, 1f))
        bar.addView(button("清空 Clear all") { resetToDefault() })
        bar.addView(button("取消 Cancel") { dialog.dismiss() })
        val save = button("保存 Save") { save() }
        bar.addView(save)
        root.addView(bar, LinearLayout.LayoutParams(match, wrap))

        val metrics = context.resources.displayMetrics
        val wide = metrics.widthPixels > metrics.heightPixels
        val columns = LinearLayout(context).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val sourcesColumn = column("所有片源 All sources", buildSources())
        selectedList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val selectedColumn = column(null, selectedList)
        selectedTitle = (selectedColumn.getChildAt(0) as TextView)
        val params = { LinearLayout.LayoutParams(if (wide) 0 else match, if (wide) match else 0, 1f) }
        columns.addView(sourcesColumn, params().apply { if (wide) rightMargin = dp(12) else bottomMargin = dp(12) })
        columns.addView(selectedColumn, params())
        root.addView(columns, LinearLayout.LayoutParams(match, 0, 1f).apply { topMargin = dp(8) })

        rebuildSelected()
        save.post { save.requestFocus() }
        return root
    }

    private fun column(title: String?, content: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title ?: "", 16f, bold = true).apply { setPadding(0, dp(4), 0, dp(4)) })
        addView(ScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(match, 0, 1f))
    }

    // ---- Left column: sources and their rows ----

    private fun buildSources(): View {
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val sources = Sources.installed(selfName)
        if (sources.isEmpty()) {
            list.addView(text("没有找到其他片源。 No other sources installed.", 14f))
            return list
        }
        sources.sortedBy { it.name.lowercase() }.forEach { api ->
            val rowsBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(dp(20), 0, 0, dp(4))
            }
            val header = button("") {
                if (rowsBox.childCount == 0) fillRows(rowsBox, api.name, Sources.rows(api))
                rowsBox.visibility = if (rowsBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                updateHeader(api.name, rowsBox.visibility == View.VISIBLE)
            }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
            headers[api.name] = header
            updateHeader(api.name, false)
            list.addView(header, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(4) })
            list.addView(rowsBox, LinearLayout.LayoutParams(match, wrap))
        }
        return list
    }

    private fun fillRows(box: LinearLayout, provider: String, rows: List<com.lagradost.cloudstream3.MainPageData>) {
        if (rows.isEmpty()) {
            box.addView(text("（这个片源没有首页栏目 no rows）", 14f))
            return
        }
        rows.distinctBy { it.name }.forEach { row ->
            val picked = PickedRow(provider, row.name, row.data, row.horizontalImages)
            val check = CheckBox(context).apply {
                text = row.name
                textSize = 15f
                setTextColor(Color.WHITE)
                buttonTintList = ColorStateList.valueOf(Color.WHITE)
                background = focusBackground()
                isFocusable = true
                setPadding(dp(6), dp(6), dp(6), dp(6))
                isChecked = selected.any { it.key == picked.key }
                setOnCheckedChangeListener { _, checked ->
                    if (updatingChecks) return@setOnCheckedChangeListener
                    followingDefaults = false
                    if (checked) {
                        if (selected.none { it.key == picked.key }) selected += picked
                    } else {
                        selected.removeAll { it.key == picked.key }
                    }
                    rebuildSelected()
                    updateHeader(provider, true)
                }
            }
            checkBoxes[picked.key] = check
            box.addView(check, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(2) })
        }
    }

    private fun updateHeader(provider: String, open: Boolean) {
        val count = selected.count { it.provider == provider }
        val arrow = if (open) "▾" else "▸"
        headers[provider]?.text = if (count > 0) "$arrow  $provider  ($count)" else "$arrow  $provider"
    }

    // ---- Right column: chosen rows in order ----

    private fun rebuildSelected(focusIndex: Int = -1, focusAction: Int = 0) {
        selectedList.removeAllViews()
        selectedTitle.text = "已选栏目 Selected rows (${selected.size})"
        if (selected.isEmpty()) {
            selectedList.addView(text("还没有选择。展开左边的片源勾选栏目。\nNothing selected. Expand a source on the left and tick its rows.", 14f))
            return
        }
        selected.forEachIndexed { index, row ->
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(
                text("${index + 1}. ${row.name} · ${AsianHomeProvider.shortName(row.provider)}", 15f),
                LinearLayout.LayoutParams(0, wrap, 1f),
            )
            val up = button("↑ 上移") { move(index, -1) }.apply { isEnabled = index > 0 }
            val down = button("↓ 下移") { move(index, 1) }.apply { isEnabled = index < selected.size - 1 }
            val remove = button("✕ 移除") { remove(index) }
            listOf(up, down, remove).forEach { line.addView(it) }
            selectedList.addView(line, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(2) })
            if (index == focusIndex) {
                val target = listOf(up, down, remove)[focusAction].takeIf { it.isEnabled }
                    ?: listOf(up, down, remove).first { it.isEnabled }
                target.post { target.requestFocus() }
            }
        }
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
        updatingChecks = true
        checkBoxes[row.key]?.isChecked = false
        updatingChecks = false
        updateHeader(row.provider, checkBoxes[row.key]?.let { (it.parent as? View)?.visibility == View.VISIBLE } ?: false)
        rebuildSelected(minOf(index, selected.size - 1), 2)
    }

    private fun resetToDefault() {
        selected.clear()
        selected += store.defaults(selfName)
        followingDefaults = true
        syncChecks()
        rebuildSelected()
    }

    private fun syncChecks() {
        updatingChecks = true
        checkBoxes.forEach { (key, box) -> box.isChecked = selected.any { it.key == key } }
        updatingChecks = false
        headers.keys.forEach { provider ->
            val open = checkBoxes.entries.firstOrNull { it.key.startsWith("$provider\u0000") }
                ?.let { (it.value.parent as? View)?.visibility == View.VISIBLE } ?: false
            updateHeader(provider, open)
        }
    }

    private fun save() {
        // Untouched defaults are stored as "no choice", so newly installed default sources still show up.
        if (followingDefaults) store.clear() else store.save(selected.toList())
        dialog.dismiss()
        onSaved()
    }

    // ---- Small view helpers ----

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(Color.GRAY, Color.WHITE),
            ),
        )
        background = focusBackground()
        isFocusable = true
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(12), dp(8), dp(12), dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = dp(6) }
        setOnClickListener { onClick() }
    }

    /** A clear highlight for the focused control: on a TV the D-pad focus has to be obvious. */
    private fun focusBackground() = StateListDrawable().apply {
        fun shape(color: Int, stroke: Int = 0) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(6).toFloat()
            if (stroke != 0) setStroke(dp(2), stroke)
        }
        addState(intArrayOf(android.R.attr.state_focused), shape(Color.rgb(47, 111, 235), Color.WHITE))
        addState(intArrayOf(android.R.attr.state_pressed), shape(Color.rgb(47, 111, 235)))
        addState(intArrayOf(), shape(Color.argb(40, 255, 255, 255)))
    }
}
