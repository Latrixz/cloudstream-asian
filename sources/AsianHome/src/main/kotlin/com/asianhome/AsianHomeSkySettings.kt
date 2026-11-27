package com.asianhome

import android.app.Dialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.lagradost.cloudstream3.MainAPI

/**
 * The row editor for SkyStream (desktop). SkyStream redraws plugin screens with its own widgets and
 * drops colours, card backgrounds and focus rings, so the TV editor ([AsianHomeSettings]) comes out
 * as loose text there. This version only uses what SkyStream draws natively: headings, plain
 * buttons and on/off switches, in one vertical list.
 *
 *  - Top: Save / Cancel / Clear.
 *  - "Selected rows": the home screen order, each with ↑ ↓ ✕.
 *  - Each source: tap its heading to open it, then switch rows on or off.
 */
class AsianHomeSkySettings(
    private val context: Context,
    private val store: AsianHomeStore,
    private val selfName: String,
    private val onSaved: () -> Unit,
) {
    private val selected = mutableListOf<PickedRow>()
    private var followingDefaults = store.saved() == null
    private val sources: List<MainAPI> = Sources.installed(selfName).sortedBy { it.name.lowercase() }
    private val open = mutableSetOf<String>()

    private lateinit var dialog: Dialog
    private lateinit var body: LinearLayout

    fun show() {
        selected += store.current(selfName)
        dialog = Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply { addView(body) }
        dialog.setContentView(scroll)
        rebuild()
        dialog.show()
    }

    private fun rebuild() {
        body.removeAllViews()
        body.addView(heading("CS Asian 首页 · 编辑首页栏目 Edit home rows"))
        body.addView(text("选择要在首页显示的栏目，并排好顺序。Pick the rows to show on Home and put them in order."))

        body.addView(row(
            button("💾 保存 Save") { save() },
            button("取消 Cancel") { dialog.dismiss() },
            button("清空 Clear all") {
                selected.clear()
                followingDefaults = false
                rebuild()
            },
        ))

        body.addView(heading("已选栏目 Selected rows (${selected.size})"))
        if (selected.isEmpty()) {
            body.addView(text("还没有栏目：在下面的片源里打开开关。No rows yet: switch some on in the sources below."))
        }
        selected.forEachIndexed { i, r ->
            body.addView(row(
                text("${i + 1}. ${shortName(r.provider)} · ${r.name}"),
                button("↑") { move(i, -1) },
                button("↓") { move(i, 1) },
                button("✕") { selected.removeAt(i); followingDefaults = false; rebuild() },
            ))
        }

        body.addView(heading("片源 Sources (${sources.size})"))
        if (sources.isEmpty()) {
            body.addView(text("没有找到其他片源 · No other sources found. Open a source once in SkyStream so it loads, then come back."))
        }
        sources.forEach { api ->
            val count = selected.count { it.provider == api.name }
            val isOpen = api.name in open
            body.addView(button("${if (isOpen) "▾" else "▸"} ${api.name}" + if (count > 0) "  ($count)" else "") {
                if (!open.remove(api.name)) open += api.name
                rebuild()
            })
            if (isOpen) {
                val rows = Sources.rows(api)
                if (rows.isEmpty()) body.addView(text("（这个片源没有首页栏目 · no home rows）"))
                rows.forEach { mp ->
                    val picked = PickedRow(api.name, mp.name, mp.data, mp.horizontalImages)
                    body.addView(Switch(context).apply {
                        this.text = mp.name
                        isChecked = selected.any { it.key == picked.key }
                        setOnCheckedChangeListener { _, on ->
                            followingDefaults = false
                            selected.removeAll { it.key == picked.key }
                            if (on) selected += picked
                            rebuild()
                        }
                    })
                }
            }
        }
    }

    private fun move(index: Int, delta: Int) {
        val to = index + delta
        if (to !in selected.indices) return
        followingDefaults = false
        selected.add(to, selected.removeAt(index))
        rebuild()
    }

    private fun save() {
        if (followingDefaults) store.clear() else store.save(selected.toList())
        dialog.dismiss()
        onSaved()
    }

    private fun shortName(full: String): String = full.trim().split(Regex("\\s+")).firstOrNull() ?: full

    private fun heading(value: String) = TextView(context).apply {
        text = value
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun text(value: String) = TextView(context).apply {
        text = value
        textSize = 14f
    }

    private fun button(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    }
}
