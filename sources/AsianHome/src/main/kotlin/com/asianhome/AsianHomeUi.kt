package com.asianhome

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/** Colours, shapes and small widgets for the settings screen (all built in code, no resources). */
object Ui {
    val BG_TOP = Color.parseColor("#0E0B24")
    val BG_BOTTOM = Color.parseColor("#1A1546")
    val PANEL = Color.parseColor("#0AFFFFFF")
    val CARD = Color.parseColor("#231E4A")
    val CARD_FOCUS = Color.parseColor("#2F2963")
    val EXPAND = Color.parseColor("#1B1740")
    val ACCENT = Color.parseColor("#FF4D57")
    val ACCENT_FOCUS = Color.parseColor("#FF6A72")
    val TEXT = Color.WHITE
    val TEXT_2 = Color.parseColor("#B9B3E6")
    val OUTLINE = Color.parseColor("#4DB9B3E6")
    val FOCUS_RING = Color.WHITE

    const val ANIM_MS = 130L

    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    private val LETTER_COLOURS = intArrayOf(
        Color.parseColor("#6C5CE7"), Color.parseColor("#00B894"), Color.parseColor("#0984E3"),
        Color.parseColor("#E17055"), Color.parseColor("#D63083"), Color.parseColor("#00A8B5"),
        Color.parseColor("#E1A100"), Color.parseColor("#8E44AD"),
    )

    fun letterColour(name: String) = LETTER_COLOURS[(name.hashCode() and 0x7fffffff) % LETTER_COLOURS.size]

    fun rounded(color: Int, radius: Float, strokePx: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

    /** Background that fades between states; [focused] wins over [activated] wins over [normal]. */
    fun states(
        normal: Drawable,
        focused: Drawable,
        activated: Drawable? = null,
        focusedActivated: Drawable? = null,
    ) = StateListDrawable().apply {
        setEnterFadeDuration(ANIM_MS.toInt())
        setExitFadeDuration(ANIM_MS.toInt())
        if (focusedActivated != null) {
            addState(intArrayOf(android.R.attr.state_focused, android.R.attr.state_activated), focusedActivated)
        }
        addState(intArrayOf(android.R.attr.state_focused), focused)
        if (activated != null) addState(intArrayOf(android.R.attr.state_activated), activated)
        addState(intArrayOf(), normal)
    }

    fun textStates(normal: Int, activated: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_activated), intArrayOf()),
        intArrayOf(activated, normal),
    )

    /** Scales the view up a little while it has focus, with a short ease-out. */
    fun focusScale(view: View, scale: Float, lift: Float) {
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

    fun text(context: Context, value: String, sp: Float, color: Int = TEXT, face: Typeface? = null) =
        TextView(context).apply {
            text = value
            textSize = sp
            setTextColor(color)
            if (face != null) typeface = face
            includeFontPadding = false
        }

    fun singleLine(view: TextView) = view.apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
}

/**
 * A rounded logo tile: shows a coloured letter until the real image arrives, and keeps the letter
 * if the image never loads.
 */
class LogoView(context: Context, private val label: String, sizePx: Int, private val radius: Float) :
    FrameLayout(context) {
    private val letter = Ui.text(context, firstLetter(label), sizePx / context.resources.displayMetrics.scaledDensity * 0.46f, Color.WHITE, Ui.BOLD)
        .apply { gravity = Gravity.CENTER }
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
        alpha = 0f
    }

    init {
        background = Ui.rounded(Ui.letterColour(label), radius)
        clipToOutline = true
        addView(letter, LayoutParams(sizePx, sizePx))
        // The image must fit inside the tile: full size minus the inset on both sides, centred.
        // (A full-size image with margins overflowed the tile and got its right/bottom edge clipped.)
        val inset = (sizePx * 0.12f).toInt()
        val inner = sizePx - 2 * inset
        addView(image, LayoutParams(inner, inner, Gravity.CENTER))
        layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
    }

    fun show(bitmap: Bitmap, animate: Boolean) {
        background = Ui.rounded(Color.parseColor("#F4F2FF"), radius)
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

/** Lays children out left to right and wraps to a new line when the width runs out. */
class FlowLayout(context: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += lineHeight + vGap
                lineHeight = 0
            }
            x += child.measuredWidth + hGap
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(y + lineHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += lineHeight + vGap
                lineHeight = 0
            }
            child.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + child.measuredWidth, paddingTop + y + child.measuredHeight)
            x += child.measuredWidth + hGap
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
    }
}
