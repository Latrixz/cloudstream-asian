package com.csasian.shared

import android.app.Activity
import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity

/**
 * "支持 CS Asian · Support" popup, shared by every cloudstream-asian plugin (compiled into each).
 * All copies share one schedule in the app's key store, so a user sees one popup however many
 * of our sources they have: on first use, then at most once a week. There is no "I've supported"
 * switch: the app can't tell who paid, so everyone sees it weekly. It is only checked when the app starts (Plugin.load), so
 * switching between sources never brings it up. The QR code (Buy Me a Coffee) is embedded, because
 * most TVs have no browser to open a link.
 */
object CsSupport {
    /** Test builds: show on every app start. Must be false when publishing. */
    private const val FORCE = false

    private const val KEY_NEXT = "csasian_support_next"
    private const val DAY = 24L * 60 * 60 * 1000
    private const val LATER = 7 * DAY
    private const val LINK = "buymeacoffee.com/latrixz"
    /** This month's goal (Cloudflare Worker fed by Buy Me a Coffee webhooks, see support-worker/). */
    private const val STATS_URL = "https://csasian-support.airroom.workers.dev/stats"

    private class Goal(val month: String, val goal: Double, val raised: Double, val percent: Int, val supporters: Int)

    /** Reads the goal; null when it can't be reached (the popup then shows without the bar). */
    private fun fetchGoal(): Goal? = try {
        val c = java.net.URL(STATS_URL).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 3000
        val text = c.inputStream.bufferedReader().use { it.readText() }
        val o = org.json.JSONObject(text)
        Goal(o.optString("month"), o.optDouble("goal", 100.0), o.optDouble("raised", 0.0),
            o.optInt("percent", 0), o.optInt("supporters", 0))
    } catch (_: Throwable) {
        null
    }
    private const val QR_PNG = "iVBORw0KGgoAAAANSUhEUgAAAQgAAAEIAQMAAACZOPi8AAAABlBMVEUAAAD///+l2Z/dAAAAAnRSTlP//8i138cAAAAJcEhZcwAACxIAAAsSAdLdfvwAAAEGSURBVGiB7ZbBCsMwDEP1/z/tsUSy08Lofa8pzVzl5SJkM9XT0kuwCe1V61lFtu8vkLAxhvbWKpHYfkUOYRVLZG8OTmSg3FQgkfC0ZafKI86JO8+vmfz3RNYWygcj0wiHJpFxc+WAR+R7xu5+FY5GpJ9ays0oRGKPW4co0+XiGIjIKw1ow4BESnuk00ogYT3ztUcMl7BL7ViHSAUknJ5OTWD3FpGQ+0pxyxiSaHO2YepzX6QRzk/YGTBUwrnxjF3E/EcBEl5yrb6YOLGIuJNWSqA0w4ZF2KdVjlyaKzBCma5z4tYqMHEEysXdMRihujZQUoUjTiHF9RtFzMS1bxaKSTyslyATH+THJARPwbSzAAAAAElFTkSuQmCC"

    @Volatile private var shownThisRun = false

    /** Call from Plugin.load. Shows the popup a few seconds later if it's due. */
    fun maybeShow() {
        Handler(Looper.getMainLooper()).postDelayed({ checkAndShow() }, 6_000)
    }

    private fun checkAndShow() {
        try {
            // Main thread: every plugin's copy runs here one after the other, so the key decides once.
            val now = System.currentTimeMillis()
            val next = CloudStreamApp.getKeyClass(KEY_NEXT, java.lang.Long::class.java)?.toLong()
            // First use (never shown, or a test build's marker): show now.
            // Test builds: once per app run (the run is marked with the process id).
            val runMark = -android.os.Process.myPid().toLong()
            if (FORCE) { if (shownThisRun || next == runMark) return }
            else if (shownThisRun || (next != null && next > 0 && now < next)) return
            // Claim it before showing, so the other plugins' copies stay quiet.
            CloudStreamApp.setKeyClass(KEY_NEXT, if (FORCE) runMark else now + LATER)
            shownThisRun = true
            // Fetch the goal off the main thread, then show.
            Thread {
                val goal = fetchGoal()
                Handler(Looper.getMainLooper()).post {
                    val activity = CommonActivity.activity ?: return@post
                    if (!activity.isFinishing) show(activity, goal)
                }
            }.start()
        } catch (_: Throwable) {
        }
    }

    private fun money(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v)

    private fun show(activity: Activity, goal: Goal?) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun rounded(color: Int, r: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
            setColor(color); cornerRadius = r * d; if (stroke > 0) setStroke(stroke, strokeColor)
        }
        fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
            text = s; textSize = sp; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        val accent = Color.parseColor("#FF4D57")
        val dim = Color.parseColor("#B9B3E6")

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Phones (portrait) stack everything; TVs and landscape put the QR on the right.
        val wide = activity.resources.displayMetrics.let { it.widthPixels > it.heightPixels }
        // A TV (or anything without a touchscreen) shows the QR code to scan with a phone; on a
        // phone or tablet the "Open support page" button is the way in, so no QR.
        val isTv = try {
            val ui = activity.getSystemService(android.content.Context.UI_MODE_SERVICE) as android.app.UiModeManager
            ui.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
                !activity.packageManager.hasSystemFeature("android.hardware.touchscreen")
        } catch (_: Throwable) { true }
        val textWidth = if (wide) dp(380) else ViewGroup.LayoutParams.MATCH_PARENT
        val card = LinearLayout(activity).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#1A1546"), Color.parseColor("#0E0B24"))).apply {
                cornerRadius = 22 * d; setStroke(dp(1), Color.parseColor("#4DFF4D57"))
            }
            setPadding(dp(26), dp(22), dp(26), dp(22))
        }

        val left = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text("💗  支持 CS ASIAN · SUPPORT THE REPO", 11f, Color.parseColor("#FFB3B8"), true).apply {
            background = rounded(Color.parseColor("#33FF4D57"), 14f, dp(1), Color.parseColor("#66FF4D57"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        left.addView(text("让 CS Asian 继续免费", 22f, Color.WHITE, true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        left.addView(text("Keep CS Asian free ☕", 16f, Color.WHITE, true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        left.addView(text("没有广告，没有付费墙。你的支持让每个片源保持更新。\nNo ads, no paywalls. Your support keeps every source working and up to date.", 12.5f, dim),
            LinearLayout.LayoutParams(textWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        val tiles = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("🎬" to "17 片源 Sources", "🚫" to "零广告 No ads", "🆓" to "100% 免费 Free").forEachIndexed { i, (icon, label) ->
            val t = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = rounded(Color.parseColor("#14FFFFFF"), 12f, dp(1), Color.parseColor("#26FFFFFF"))
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }
            t.addView(text(icon, 18f, Color.WHITE).apply { gravity = Gravity.CENTER })
            t.addView(text(label, 11.5f, Color.WHITE, true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            tiles.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        left.addView(tiles, LinearLayout.LayoutParams(textWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        if (goal != null) {
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Color.parseColor("#14FFFFFF"), 14f, dp(1), Color.parseColor("#26FFFFFF"))
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(text("${goal.month} 目标 Goal", 13f, Color.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(text("$${money(goal.raised)} / $${money(goal.goal)}", 13f, Color.WHITE, true))
            box.addView(head)
            val track = android.widget.FrameLayout(activity).apply { background = rounded(Color.parseColor("#26FFFFFF"), 6f) }
            val fill = View(activity).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.parseColor("#FF4D57"), Color.parseColor("#FF8A65"))).apply { cornerRadius = 6 * d }
            }
            track.addView(fill, android.widget.FrameLayout.LayoutParams(0, dp(10)))
            box.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(10) })
            track.post {
                val pct = goal.percent.coerceIn(0, 100)
                fill.layoutParams = fill.layoutParams.apply { width = if (pct == 0) 0 else maxOf(dp(10), track.width * pct / 100) }
                fill.requestLayout()
            }
            val foot = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val who = if (goal.supporters == 0) "成为本月第一位支持者 · Be this month's first supporter"
                else "👥 ${goal.supporters} 位支持者 supporters"
            foot.addView(text(who, 11.5f, dim), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            foot.addView(text("${goal.percent}%", 11.5f, Color.WHITE, true))
            box.addView(foot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            left.addView(box, LinearLayout.LayoutParams(textWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        }
        left.addView(text("✈️  Telegram: t.me/csasian", 12f, dim),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        // Buttons
        fun button(label: String, primary: Boolean, onClick: () -> Unit) = text(label, 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused),
                    rounded(if (primary) Color.parseColor("#FF6A72") else Color.parseColor("#33FFFFFF"), 22f, dp(3), Color.WHITE))
                addState(intArrayOf(), if (primary) rounded(accent, 22f) else rounded(Color.TRANSPARENT, 22f, dp(1) + 1, Color.parseColor("#66B9B3E6")))
            }
            setPadding(dp(20), 0, dp(20), 0)
            isFocusable = true; isClickable = true
            setOnClickListener { onClick() }
        }
        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        // Phones (and TVs with a browser) can open the page directly; others scan the QR code.
        val open = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://$LINK"))
        val canOpen = try { open.resolveActivity(activity.packageManager) != null } catch (_: Throwable) { false }
        if (canOpen) {
            buttons.addView(button("☕ 打开支持页面 Open support page", true) {
                try { activity.startActivity(open) } catch (_: Throwable) { }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { rightMargin = dp(10) })
        }
        val later = button("稍后 Later", false) {
            if (!FORCE) CloudStreamApp.setKeyClass(KEY_NEXT, System.currentTimeMillis() + LATER)
            dialog.dismiss()
        }
        buttons.addView(later, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        left.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        card.addView(left)

        // QR code
        val right = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val qr = ImageView(activity).apply {
            val bytes = Base64.decode(QR_PNG, Base64.DEFAULT)
            setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            background = rounded(Color.WHITE, 14f)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            scaleType = ImageView.ScaleType.FIT_CENTER
            // Pixel-sharp QR when scaled up.
            (drawable as? android.graphics.drawable.BitmapDrawable)?.paint?.isFilterBitmap = false
        }
        val qrSize = if (wide) dp(170) else dp(140)
        right.addView(qr, LinearLayout.LayoutParams(qrSize, qrSize))
        right.addView(text("用手机扫码 Scan with your phone", 12f, Color.WHITE, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        right.addView(text("☕ $LINK", 11.5f, dim).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        if (isTv || !canOpen) card.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (wide) { leftMargin = dp(28); gravity = Gravity.CENTER_VERTICAL } else { topMargin = dp(18); gravity = Gravity.CENTER_HORIZONTAL }
        })

        // Phones: keep it inside the screen; the long texts wrap.
        if (!wide) card.setPadding(dp(20), dp(20), dp(20), dp(20))
        dialog.setContentView(card)
        dialog.show()
        later.requestFocus()
    }
}
