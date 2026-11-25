package com.asianhome

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.csasian.shared.CsBackup
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * "片源状态 Source status": opens the first home row of every installed source and shows a light
 * per source (green = works, yellow = slow, red = down, with a short reason). Also holds the
 * on/off switch for backup links. Selecting a card checks that source again.
 */
class AsianHomeStatus(private val context: Context, selfName: String) {
    private enum class State { CHECKING, OK, SLOW, DOWN, NO_HOME }

    private class Result(val state: State, val note: String)

    private val sources: List<MainAPI> = Sources.installed(selfName).sortedBy { it.name.lowercase() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val limit = Semaphore(4)
    private val results = mutableMapOf<String, Result>()
    private val dots = mutableMapOf<String, View>()
    private val notes = mutableMapOf<String, TextView>()
    private val jobs = mutableMapOf<String, Job>()

    private lateinit var dialog: Dialog
    private lateinit var summary: TextView

    private val metrics = context.resources.displayMetrics
    private val density = metrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Float) = v * density

    fun show() {
        dialog = Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(buildRoot())
        dialog.setOnDismissListener { scope.cancel() }
        dialog.show()
        checkAll()
    }

    private fun buildRoot(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.BG_TOP, Ui.BG_BOTTOM))
            setPadding(dp(28), dp(18), dp(28), dp(14))
            clipChildren = false
        }

        // Header: title + summary, then the backup switch, Check again and Back.
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(Ui.text(context, "片源状态 Source status", 20f, Ui.TEXT, Ui.BOLD))
        summary = Ui.singleLine(Ui.text(context, "检查中… Checking…", 12.5f, Ui.TEXT_2))
        titles.addView(summary, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(4) })
        bar.addView(titles, LinearLayout.LayoutParams(0, wrap, 1f))

        lateinit var backup: TextView
        backup = pill(backupLabel(), primary = CsBackup.enabled()) {
            CsBackup.setEnabled(!CsBackup.enabled())
            backup.text = backupLabel()
            stylePill(backup, CsBackup.enabled())
        }
        bar.addView(backup)
        bar.addView(pill("重新检查 Check again", primary = false) { checkAll() })
        bar.addView(pill("返回 Back", primary = false) { dialog.dismiss() })
        root.addView(bar, LinearLayout.LayoutParams(match, wrap))

        root.addView(
            Ui.text(
                context,
                "备用链接：播放我们的片源时，同时在其他片源找同一集，出错时可换着播。 " +
                    "Backup links: when you play one of our sources, the same episode is also looked up on our other sources.",
                12f, Ui.TEXT_2,
            ),
            LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) },
        )

        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        val columns = (metrics.widthPixels / density - 56f).div(200f).toInt().coerceIn(2, 5)
        if (sources.isEmpty()) {
            grid.addView(Ui.text(context, "没有找到其他片源 · No other sources installed", 14f, Ui.TEXT_2))
        }
        sources.chunked(columns).forEach { line ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
            }
            line.forEach { api -> row.addView(card(api), cardParams()) }
            repeat(columns - line.size) { row.addView(View(context), cardParams()) }
            grid.addView(row, LinearLayout.LayoutParams(match, wrap))
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            setPadding(dp(4), dp(8), dp(4), dp(14))
            addView(grid)
        }
        root.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f).apply { topMargin = dp(10) })
        root.post { backup.requestFocus() }
        return root
    }

    private fun cardParams() = LinearLayout.LayoutParams(0, dp(76), 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) }

    private fun card(api: MainAPI): View {
        val r = dpf(14f)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.states(
                normal = Ui.rounded(Ui.CARD, r),
                focused = Ui.rounded(Ui.CARD_FOCUS, r, dp(3), Ui.FOCUS_RING),
            )
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isFocusable = true
            isClickable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            Ui.focusScale(this, 1.04f, dpf(5f))
            setOnClickListener { check(api) }
        }
        val logoBox = FrameLayout(context).apply { clipChildren = false }
        val logo = LogoView(context, api.name, dp(40), dpf(10f))
        bindLogo(logo, api)
        logoBox.addView(logo, FrameLayout.LayoutParams(dp(40), dp(40)))
        // Status light on the logo's corner.
        val dot = View(context).apply { background = dotDrawable(State.CHECKING) }
        logoBox.addView(dot, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, -dp(3), -dp(3))
        })
        card.addView(logoBox, LinearLayout.LayoutParams(dp(44), dp(44)))

        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.singleLine(Ui.text(context, api.name, 14f, Ui.TEXT, Ui.MEDIUM)))
        val note = Ui.singleLine(Ui.text(context, "检查中… Checking…", 11.5f, Ui.TEXT_2))
        texts.addView(note, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(4) })
        card.addView(texts, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = dp(10) })

        dots[api.name] = dot
        notes[api.name] = note
        return card
    }

    private fun checkAll() = sources.forEach { check(it) }

    private fun check(api: MainAPI) {
        jobs[api.name]?.cancel()
        show(api.name, Result(State.CHECKING, "检查中… Checking…"))
        jobs[api.name] = scope.launch {
            val result = limit.withPermit { withContext(Dispatchers.IO) { probe(api) } }
            show(api.name, result)
        }
    }

    private suspend fun probe(api: MainAPI): Result {
        val row = Sources.rows(api).firstOrNull() ?: return Result(State.NO_HOME, "没有首页 · No home page")
        val started = System.currentTimeMillis()
        return try {
            val response = withTimeoutOrNull(TIMEOUT_MS) {
                api.getMainPage(1, MainPageRequest(row.name, row.data, row.horizontalImages))
            } ?: return Result(State.DOWN, "没有响应 · Not responding")
            val seconds = (System.currentTimeMillis() - started) / 1000.0
            val time = String.format("%.1f s", seconds)
            val items = response.items.sumOf { it.list.size }
            when {
                items == 0 -> Result(State.DOWN, "没有内容 · No titles")
                seconds > SLOW_S -> Result(State.SLOW, "较慢 · Slow · $time")
                else -> Result(State.OK, "正常 · OK · $time")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result(State.DOWN, reason(e))
        }
    }

    private fun reason(e: Throwable): String {
        val all = generateSequence(e) { it.cause }.take(5).toList()
        val text = all.joinToString(" ") { "${it.javaClass.simpleName} ${it.message.orEmpty()}" }.lowercase()
        return when {
            all.any { it is UnknownHostException || it is ConnectException } -> "连不上网站 · Can't reach the site"
            all.any { it is SocketTimeoutException } -> "没有响应 · Not responding"
            "cloudflare" in text || "captcha" in text || "403" in text -> "被拦截 · Blocked (Cloudflare)"
            "503" in text || "502" in text || "500" in text -> "网站出错 · Site error"
            else -> "出错 · Error: " + (e.message ?: e.javaClass.simpleName).take(60)
        }
    }

    private fun show(name: String, result: Result) {
        results[name] = result
        dots[name]?.background = dotDrawable(result.state)
        notes[name]?.apply {
            text = result.note
            setTextColor(if (result.state == State.DOWN) Color.parseColor("#FF8A90") else Ui.TEXT_2)
        }
        updateSummary()
    }

    private fun updateSummary() {
        val all = results.values
        val checking = all.count { it.state == State.CHECKING }
        val ok = all.count { it.state == State.OK }
        val slow = all.count { it.state == State.SLOW }
        val down = all.count { it.state == State.DOWN }
        val parts = mutableListOf("🟢 $ok 正常 OK")
        if (slow > 0) parts += "🟡 $slow 较慢 slow"
        if (down > 0) parts += "🔴 $down 不可用 down"
        if (checking > 0) parts += "⏳ $checking 检查中 checking"
        summary.text = parts.joinToString("   ")
    }

    private fun dotDrawable(state: State) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(
            when (state) {
                State.CHECKING -> Color.parseColor("#8A84B8")
                State.OK -> Color.parseColor("#34D07F")
                State.SLOW -> Color.parseColor("#F5C542")
                State.DOWN -> Color.parseColor("#FF4D57")
                State.NO_HOME -> Color.parseColor("#5C5786")
            }
        )
        setStroke(dp(2), Ui.BG_BOTTOM)
    }

    private fun backupLabel() = if (CsBackup.enabled()) "备用链接 Backup links: 开 On" else "备用链接 Backup links: 关 Off"

    private fun pill(label: String, primary: Boolean, onClick: () -> Unit) =
        Ui.text(context, label, 14f, Ui.TEXT, Ui.MEDIUM).apply {
            gravity = Gravity.CENTER
            stylePill(this, primary)
            setPadding(dp(18), 0, dp(18), 0)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            Ui.focusScale(this, 1.06f, dpf(4f))
            layoutParams = LinearLayout.LayoutParams(wrap, dp(40)).apply { leftMargin = dp(10) }
        }

    private fun stylePill(view: TextView, primary: Boolean) {
        val r = dpf(22f)
        view.background = if (primary) {
            Ui.states(normal = Ui.rounded(Ui.ACCENT, r), focused = Ui.rounded(Ui.ACCENT_FOCUS, r, dp(3), Ui.FOCUS_RING))
        } else {
            Ui.states(
                normal = Ui.rounded(Color.TRANSPARENT, r, dp(1) + 1, Ui.OUTLINE),
                focused = Ui.rounded(Color.parseColor("#26FFFFFF"), r, dp(3), Ui.FOCUS_RING),
            )
        }
    }

    private fun bindLogo(view: LogoView, api: MainAPI) {
        scope.launch {
            val urls = AsianHomeLogos.candidates(api)
            urls.firstNotNullOfOrNull { AsianHomeLogos.cached(it) }?.let {
                view.show(it, animate = false)
                return@launch
            }
            AsianHomeLogos.firstBitmap(urls)?.let { view.show(it, animate = true) }
        }
    }

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    companion object {
        private const val TIMEOUT_MS = 25_000L
        private const val SLOW_S = 6.0
    }
}
