package xyz.bodyparts.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * A black background with a few soft, faint glows drifting slowly around it.
 *
 * Each glow's gradient is built once and only moved, and it redraws at 30 fps only
 * while the home screen is on show, so it stays cheap on the TV's GPU.
 */
class AmbientBackgroundView(context: Context) : View(context) {

    /** A glow drifting along its own slow Lissajous path (periods in seconds). */
    private class Glow(
        val color: Int,
        val radiusFraction: Float,
        val periodX: Float,
        val periodY: Float,
        val phaseX: Float,
        val phaseY: Float,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var radius = 0f
    }

    private val glows = listOf(
        Glow(Color.argb(70, 70, 100, 210), 0.55f, 71f, 53f, 0.0f, 1.3f),  // blue
        Glow(Color.argb(55, 140, 80, 200), 0.45f, 89f, 61f, 2.1f, 0.4f),  // violet
        Glow(Color.argb(45, 40, 160, 170), 0.40f, 103f, 79f, 4.0f, 2.7f), // teal
        Glow(Color.argb(35, 200, 120, 170), 0.35f, 127f, 97f, 1.0f, 5.1f), // rose
    )

    private var running = false
    private val startedAt = SystemClock.uptimeMillis()

    init {
        setBackgroundColor(Color.BLACK)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val size = max(w, h).toFloat()
        for (g in glows) {
            g.radius = size * g.radiusFraction
            // Centred on the origin; onDraw moves it into place
            g.paint.shader = RadialGradient(
                0f, 0f, g.radius,
                intArrayOf(g.color, Color.argb(Color.alpha(g.color) / 3, Color.red(g.color), Color.green(g.color), Color.blue(g.color)), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - startedAt) / 1000f
        for (g in glows) {
            val x = width * (0.5f + 0.42f * sin(2 * PI.toFloat() * t / g.periodX + g.phaseX))
            val y = height * (0.5f + 0.42f * sin(2 * PI.toFloat() * t / g.periodY + g.phaseY))
            canvas.save()
            canvas.translate(x, y)
            canvas.drawCircle(0f, 0f, g.radius, g.paint)
            canvas.restore()
        }
        if (running) postInvalidateDelayed(FRAME_MS)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        running = visibility == VISIBLE
        if (running) invalidate()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    private companion object {
        const val FRAME_MS = 33L
    }
}
