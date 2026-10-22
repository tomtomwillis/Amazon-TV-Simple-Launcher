package xyz.bodyparts.home

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * An Apple TV-style tile: a rounded card that lifts, grows and gets a coloured ring
 * when focused, optionally with its name fading in underneath.
 */
class TileView(
    context: Context,
    val key: String,
    val title: String,
    widthPx: Int,
    heightPx: Int,
    private val showCaption: Boolean = true,
) : LinearLayout(context) {

    private val card = FrameLayout(context)
    private val cardWidth = widthPx
    private val caption = TextView(context)
    private val radius = dp(14f)
    private var moving = false

    private val focusRing = GradientDrawable().apply {
        cornerRadius = radius
        setStroke(dp(3f).toInt(), FOCUS_COLOR)
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isFocusable = true
        isClickable = true
        // The grow animation is the focus indicator; Android 8+ would also draw a grey box
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false
        clipChildren = false
        clipToPadding = false

        card.background = rounded(Color.rgb(44, 44, 48))
        card.outlineProvider = ViewOutlineProvider.BACKGROUND
        card.clipToOutline = true
        card.elevation = dp(2f)
        addView(card, LayoutParams(widthPx, heightPx))

        if (showCaption) addCaption(widthPx)
    }

    private fun addCaption(widthPx: Int) {
        caption.text = title
        caption.setTextColor(Color.WHITE)
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        caption.maxLines = 1
        caption.ellipsize = TextUtils.TruncateAt.END
        caption.gravity = Gravity.CENTER
        caption.alpha = 0f
        addView(caption, LayoutParams(widthPx, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14f).toInt()
        })
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!moving) card.foreground = if (gainFocus) focusRing else null
        val scale = if (gainFocus) 1.12f else 1f
        card.animate()
            .scaleX(scale).scaleY(scale)
            .translationZ(if (gainFocus) dp(18f) else 0f)
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .start()
        if (!showCaption) return
        caption.animate()
            .alpha(if (gainFocus) 1f else 0f)
            .translationY(if (gainFocus) dp(6f) else 0f)
            .setDuration(160)
            .start()
    }

    private var wiggle: ObjectAnimator? = null

    /** Picked up to be moved: outlined and gently wiggling, like Apple TV. */
    fun setMoving(moving: Boolean) {
        this.moving = moving
        wiggle?.cancel()
        wiggle = null
        card.rotation = 0f
        card.foreground = if (isFocused) focusRing else null
        if (!moving) return
        card.foreground = GradientDrawable().apply {
            cornerRadius = radius
            setStroke(dp(3f).toInt(), Color.WHITE)
        }
        wiggle = ObjectAnimator.ofFloat(card, View.ROTATION, -1.5f, 1.5f).apply {
            duration = 140
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    override fun onDetachedFromWindow() {
        wiggle?.cancel()
        super.onDetachedFromWindow()
    }

    /** Full-bleed app banner (what most Fire TV apps provide). */
    fun setBanner(d: Drawable) {
        val iv = ImageView(context)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setImageDrawable(d)
        card.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** For apps with no banner: icon plus name on a dark card. */
    fun setIconAndName(icon: Drawable?, name: String) {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
        }
        if (icon != null) {
            val size = dp(52f).toInt()
            box.addView(ImageView(context).apply { setImageDrawable(icon) }, LayoutParams(size, size))
        }
        box.addView(TextView(context).apply {
            text = name
            setTextColor(Color.argb(220, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10f).toInt(), dp(8f).toInt(), dp(10f).toInt(), 0)
        }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(box, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** Text tile used for TV inputs, Fire TV Home and Settings. */
    fun setText(main: String, sub: String?, top: Int, bottom: Int) {
        card.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(top, bottom)
        ).apply { cornerRadius = radius }
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
        }
        box.addView(TextView(context).apply {
            text = main
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12f).toInt(), 0, dp(12f).toInt(), 0)
        })
        if (sub != null) {
            box.addView(TextView(context).apply {
                text = sub
                setTextColor(Color.argb(150, 255, 255, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding(0, dp(4f).toInt(), 0, 0)
            })
        }
        card.addView(box, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** How far through something is (0..1), as a bar along the bottom of the card. */
    fun setProgress(fraction: Float) {
        if (fraction <= 0f) return
        val inset = dp(12f).toInt()
        val height = dp(4f).toInt()
        val track = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = height / 2f
                setColor(Color.argb(110, 0, 0, 0))
            }
        }
        val bar = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = height / 2f
                setColor(Color.WHITE)
            }
        }
        val barWidth = ((cardWidth - 2 * inset) * fraction.coerceIn(0f, 1f)).toInt()
        track.addView(bar, FrameLayout.LayoutParams(barWidth, MATCH))
        card.addView(track, FrameLayout.LayoutParams(MATCH, height, Gravity.BOTTOM).apply {
            setMargins(inset, 0, inset, inset)
        })
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        /** Ring around the selected tile: a soft periwinkle to go with the background glows. */
        val FOCUS_COLOR = Color.rgb(140, 160, 255)
    }
}
