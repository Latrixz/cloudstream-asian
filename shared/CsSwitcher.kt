package com.csasian.shared

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "切换片源 · Switch source": a full-screen grid of every installed source that has a home page,
 * ours first. Picking a card makes it CloudStream's home source and reloads Home. Opened from the
 * ⚙ button CloudStream shows next to the home source when our plugin sets openSettings. Built in
 * code like AsianHome's settings: every card is D-pad focusable with a white ring and small zoom.
 */
object CsSwitcher {
    /**
     * [extra] adds one more header button (label, action) — CS Asian 首页 uses it for
     * "Edit home rows", because CloudStream gives each source only one ⚙ button.
     */
    fun show(context: Context, extra: Pair<String, (Context) -> Unit>? = null) {
        try {
            Screen(context, extra).show()
        } catch (e: Throwable) {
            Toast.makeText(context, "切换片源: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Gives the ⚙ switcher to plugins from OTHER repos too, but only those with no settings screen
     * of their own (we never replace another plugin's settings) and that provide a Home page.
     * CloudStream keeps loaded plugins in a private map, so this uses reflection inside try/catch:
     * if a CloudStream update changes it, the extra buttons just don't appear. Runs at startup and
     * after every plugin (re)load, and only fills empty slots, so running it often is harmless.
     */
    fun injectEverywhere() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ inject() }, 8_000)
        if (System.getProperty(HOOK_KEY) == null) {
            System.setProperty(HOOK_KEY, "1")
            try {
                MainActivity.afterPluginsLoadedEvent += { _: Boolean ->
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ inject() }, 3_000)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private const val HOOK_KEY = "csasian.switcher.hooked"

    // Note: plugins built on CloudStream's newer BasePlugin class (e.g. phisher98, CNCVerse) have no
    // settings slot, so the Home ⚙ can't appear for them; only Plugin-based ones get it.
    private fun inject() {
        try {
            val field = com.lagradost.cloudstream3.plugins.PluginManager::class.java.getDeclaredField("plugins")
            field.isAccessible = true
            val loaded = (field.get(null) as? Map<*, *>)?.toMap() ?: return
            // Plugin files that provide at least one Home page.
            val withHome = APIHolder.allProviders.toList().filter { it.hasMainPage }
                .mapNotNull { it.sourcePlugin }.map { java.io.File(it).name }.toSet()
            loaded.forEach { (path, base) ->
                val plugin = base as? com.lagradost.cloudstream3.plugins.Plugin ?: return@forEach
                if (plugin.openSettings != null) return@forEach
                if (java.io.File(path.toString()).name !in withHome) return@forEach
                plugin.openSettings = { ctx -> show(ctx) }
            }
        } catch (e: Throwable) {
            android.util.Log.w("CsSwitcher", "inject failed: $e")
        }
    }

    /** Makes [api] the home source and asks the home screen to load it. */
    private fun switchTo(context: Context, api: MainAPI) {
        DataStoreHelper.currentHomePage = api.name
        try {
            // HomeViewModel listens to this and loads DataStoreHelper.currentHomePage.
            MainActivity.reloadHomeEvent.invoke(true)
        } catch (_: Throwable) {
            try {
                // Older builds: the plugins-loaded event also reloads the selected home source.
                MainActivity.afterPluginsLoadedEvent.invoke(true)
            } catch (_: Throwable) {
            }
        }
        Toast.makeText(context, "已切换到 ${api.name}", Toast.LENGTH_SHORT).show()
    }

    /** "华人影院 Huarw" shows "华人影院" big and "Huarw" small; names without a CJK first word stay whole. */
    private fun shortName(full: String): String {
        val first = full.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        return if (first.isNotEmpty() && first.any { isCjk(it) }) first else full.trim()
    }

    private fun isCjk(c: Char): Boolean =
        c in '一'..'鿿' || c in '㐀'..'䶿' || c in '぀'..'ヿ' || c in '가'..'힯'

    private class Screen(private val context: Context, private val extra: Pair<String, (Context) -> Unit>? = null) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private lateinit var dialog: Dialog

        private val metrics = context.resources.displayMetrics
        private val density = metrics.density
        private fun dp(v: Int) = (v * density).toInt()
        private fun dpf(v: Float) = v * density

        private val current: String? = try {
            DataStoreHelper.currentHomePage
        } catch (_: Throwable) {
            null
        }

        private val sources: List<MainAPI> = try {
            APIHolder.allProviders.toList().filter { it.hasMainPage }.distinctBy { it.name }
        } catch (_: Throwable) {
            emptyList()
        }

        private var currentCard: View? = null
        private var firstCard: View? = null

        fun show() {
            dialog = Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(buildRoot())
            dialog.setOnDismissListener { scope.cancel() }
            dialog.show()
        }

        private fun buildRoot(): View {
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(BG_TOP, BG_BOTTOM))
                // Generous side padding keeps everything clear of TV overscan.
                setPadding(dp(28), dp(18), dp(28), dp(14))
                clipChildren = false
            }
            root.addView(buildHeader(), LinearLayout.LayoutParams(MATCH, WRAP))

            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
            }
            // Our sources first (CS Asian 首页 leading), then everyone else's; each alphabetical.
            val (ours, others) = sources.partition { CsLogos.isOurs(it) }
            val oursSorted = ours.sortedWith(compareBy({ !it.name.startsWith("CS Asian") }, { it.name.lowercase() }))
            if (oursSorted.isNotEmpty()) addSection(body, "CS Asian 片源", oursSorted)
            if (others.isNotEmpty()) addSection(body, "其他片源 · Other sources", others.sortedBy { it.name.lowercase() })
            if (sources.isEmpty()) {
                body.addView(text("没有找到片源 · No sources installed", 14f, TEXT_2).apply { setPadding(dp(6), dp(12), dp(6), dp(12)) })
            }

            val scroll = ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                isFillViewport = true
                clipToPadding = false
                clipChildren = false
                // Room for the focus zoom so it is never clipped at the edges.
                setPadding(dp(10), dp(4), dp(10), dp(16))
                addView(body)
            }
            val panel = FrameLayout(context).apply {
                background = rounded(PANEL, dpf(18f))
                setPadding(dp(4), dp(6), dp(4), 0)
                clipChildren = false
                addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            root.addView(panel, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(14) })

            // Start on the current home source so OK on it just closes, and arrows move from there.
            root.post {
                val target = currentCard ?: firstCard ?: return@post
                target.requestFocus()
                target.post { target.requestRectangleOnScreen(Rect(0, -dp(40), target.width, target.height + dp(16)), true) }
            }
            return root
        }

        // ---- Header ----

        private fun buildHeader(): View {
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
            }
            val logo = LogoTile(context, "CS", dp(40), dpf(10f))
            bindLogo(logo, listOf(CsLogos.REPO_LOGO))
            bar.addView(logo)

            val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            titles.addView(text("切换片源 · Switch source", 20f, TEXT, BOLD))
            titles.addView(
                singleLine(text("选择首页显示的片源 · Pick what Home shows", 12.5f, TEXT_2)),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) },
            )
            bar.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) })
            extra?.let { (label, action) ->
                bar.addView(pillButton(label) { dialog.dismiss(); action(context) })
            }
            bar.addView(pillButton("关闭 Close") { dialog.dismiss() })
            return bar
        }

        private fun pillButton(label: String, onClick: () -> Unit) = text(label, 14f, TEXT, MEDIUM).apply {
            gravity = Gravity.CENTER
            val r = dpf(22f)
            background = states(
                normal = rounded(Color.TRANSPARENT, r, dp(1) + 1, OUTLINE),
                focused = rounded(Color.parseColor("#26FFFFFF"), r, dp(3), FOCUS_RING),
            )
            setPadding(dp(18), 0, dp(18), 0)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            focusScale(this, 1.06f, dpf(4f))
            layoutParams = LinearLayout.LayoutParams(WRAP, dp(40)).apply { leftMargin = dp(10) }
        }

        // ---- Grid ----

        /** 4–6 cards per line on a TV, 2–3 on a phone. */
        private fun columns(): Int {
            val widthDp = metrics.widthPixels / density - 56 - 28
            return (widthDp / 150f).toInt().coerceIn(2, 6)
        }

        private fun addSection(body: LinearLayout, title: String, list: List<MainAPI>) {
            body.addView(
                text(title, 16f, TEXT, BOLD),
                LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    leftMargin = dp(8)
                    topMargin = dp(if (body.childCount == 0) 8 else 18)
                    bottomMargin = dp(4)
                },
            )
            val columns = columns()
            list.chunked(columns).forEach { line ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                }
                line.forEach { api -> row.addView(card(api), cardParams()) }
                // Keep the last line's cards the same width as the others.
                repeat(columns - line.size) { row.addView(View(context), cardParams()) }
                body.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }

        private fun cardParams() = LinearLayout.LayoutParams(0, dp(116), 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) }

        private fun card(api: MainAPI): View {
            val r = dpf(16f)
            val isCurrent = api.name == current
            val card = FrameLayout(context).apply {
                background = states(
                    normal = rounded(CARD, r),
                    focused = rounded(CARD_FOCUS, r, dp(3), FOCUS_RING),
                    activated = rounded(CARD, r, dp(2), ACCENT),
                    focusedActivated = rounded(CARD_FOCUS, r, dp(3), FOCUS_RING),
                )
                isFocusable = true
                isClickable = true
                isActivated = isCurrent
                clipChildren = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                focusScale(this, 1.06f, dpf(6f))
            }
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(8))
            }
            val logo = LogoTile(context, api.name, dp(46), dpf(12f))
            bindSourceLogo(logo, api)
            content.addView(logo)
            val short = shortName(api.name)
            val rest = api.name.trim().removePrefix(short).trim()
            content.addView(
                singleLine(text(short, 14f, TEXT, MEDIUM)).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) },
            )
            if (rest.isNotEmpty()) {
                content.addView(
                    singleLine(text(rest, 11f, TEXT_2)).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) },
                )
            }
            card.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

            if (isCurrent) {
                val badge = text("当前 Current", 10.5f, Color.WHITE, BOLD).apply {
                    background = rounded(ACCENT, dpf(10f))
                    setPadding(dp(7), dp(3), dp(7), dp(3))
                }
                card.addView(badge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
                currentCard = card
            }
            if (firstCard == null) firstCard = card

            card.setOnClickListener {
                dialog.dismiss()
                switchTo(context, api)
            }
            return card
        }

        // ---- Logos ----

        private fun bindSourceLogo(view: LogoTile, api: MainAPI) {
            // Once candidates are known, a cached logo shows at once, without the fade.
            scope.launch {
                val urls = CsLogos.candidates(api)
                urls.firstNotNullOfOrNull { CsLogos.cached(it) }?.let {
                    view.show(it, animate = false)
                    return@launch
                }
                CsLogos.firstBitmap(urls)?.let { view.show(it, animate = true) }
            }
        }

        private fun bindLogo(view: LogoTile, urls: List<String>) {
            urls.firstNotNullOfOrNull { CsLogos.cached(it) }?.let {
                view.show(it, animate = false)
                return
            }
            scope.launch { CsLogos.firstBitmap(urls)?.let { view.show(it, animate = true) } }
        }

        private fun text(value: String, sp: Float, color: Int, face: Typeface? = null) = CsSwitcher.text(context, value, sp, color, face)
    }

    /**
     * A rounded logo tile: shows a coloured letter until the real image arrives, and keeps the
     * letter if the image never loads.
     */
    private class LogoTile(context: Context, label: String, sizePx: Int, private val radius: Float) : FrameLayout(context) {
        private val letter = text(context, firstLetter(label), sizePx / context.resources.displayMetrics.scaledDensity * 0.46f, Color.WHITE, BOLD)
            .apply { gravity = Gravity.CENTER }
        private val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            alpha = 0f
        }

        init {
            background = rounded(letterColour(label), radius)
            clipToOutline = true
            addView(letter, LayoutParams(sizePx, sizePx))
            // The image sits inside the tile with a small inset on every side, centred.
            val inset = (sizePx * 0.12f).toInt()
            val inner = sizePx - 2 * inset
            addView(image, LayoutParams(inner, inner, Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
        }

        fun show(bitmap: Bitmap, animate: Boolean) {
            background = rounded(Color.parseColor("#F4F2FF"), radius)
            letter.visibility = View.GONE
            image.setImageBitmap(bitmap)
            image.visibility = View.VISIBLE
            if (animate) image.animate().alpha(1f).setDuration(180).start() else image.alpha = 1f
        }

        private fun firstLetter(name: String): String {
            val c = name.trim().firstOrNull { it.isLetterOrDigit() } ?: '?'
            return c.uppercaseChar().toString()
        }
    }

    // ---- Look (same palette as AsianHome's settings) ----

    private val BG_TOP = Color.parseColor("#0E0B24")
    private val BG_BOTTOM = Color.parseColor("#1A1546")
    private val PANEL = Color.parseColor("#0AFFFFFF")
    private val CARD = Color.parseColor("#231E4A")
    private val CARD_FOCUS = Color.parseColor("#2F2963")
    private val ACCENT = Color.parseColor("#FF4D57")
    private val TEXT = Color.WHITE
    private val TEXT_2 = Color.parseColor("#B9B3E6")
    private val OUTLINE = Color.parseColor("#4DB9B3E6")
    private val FOCUS_RING = Color.WHITE
    private const val ANIM_MS = 130L
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    private val LETTER_COLOURS = intArrayOf(
        Color.parseColor("#6C5CE7"), Color.parseColor("#00B894"), Color.parseColor("#0984E3"),
        Color.parseColor("#E17055"), Color.parseColor("#D63083"), Color.parseColor("#00A8B5"),
        Color.parseColor("#E1A100"), Color.parseColor("#8E44AD"),
    )

    private fun letterColour(name: String) = LETTER_COLOURS[(name.hashCode() and 0x7fffffff) % LETTER_COLOURS.size]

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

    /** Scales the view up a little while it has focus, with a short ease-out. */
    private fun focusScale(view: View, scale: Float, lift: Float) {
        view.setOnFocusChangeListener { v, has ->
            v.animate()
                .scaleX(if (has) scale else 1f)
                .scaleY(if (has) scale else 1f)
                .translationZ(if (has) lift else 0f)
                .setDuration(ANIM_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun text(context: Context, value: String, sp: Float, color: Int, face: Typeface? = null) = TextView(context).apply {
        text = value
        textSize = sp
        setTextColor(color)
        if (face != null) typeface = face
        includeFontPadding = false
    }

    private fun singleLine(view: TextView) = view.apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
}
