package xyz.bodyparts.home

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Neko, the desktop cat, pottering along the bottom of the home screen: it walks
 * somewhere, sits, now and then yawns and naps or has a scratch, then wanders off.
 *
 * Sprites and timings come from oneko.js (MIT, see third_party/oneko-LICENSE.txt):
 * a 256x128 sheet of 32x32 frames, advanced every 100 ms. It only ticks while the
 * home screen is on show.
 */
class CatView(context: Context) : View(context) {

    private val sheet = BitmapFactory.decodeResource(
        resources, R.drawable.oneko, BitmapFactory.Options().apply { inScaled = false }
    )
    private val paint = Paint().apply { isFilterBitmap = false } // keep the pixels crisp
    private val src = Rect()
    private val dst = Rect()

    /** Whole-number scale so the pixel art stays sharp: about 48dp tall. */
    private val scale = (48 * resources.displayMetrics.density / SPRITE).roundToInt().coerceAtLeast(1)
    private val size = SPRITE * scale
    private val speed = 8 * scale

    private var x = -1
    private var targetX = 0
    private var frameCount = 0
    private var idleTime = 0
    private var idleAnimation: String? = null
    private var idleAnimationFrame = 0
    private var sprite = SPRITES.getValue("idle")[0]

    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            frame()
            invalidate()
            postDelayed(this, FRAME_MS)
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(0, widthMeasureSpec), size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (x < 0 && w > size) {
            x = Random.nextInt(w - size)
            targetX = x
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        setRunning(visibility == VISIBLE && isShown)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        setRunning(isShown && windowVisibility == VISIBLE)
    }

    override fun onDetachedFromWindow() {
        setRunning(false)
        super.onDetachedFromWindow()
    }

    private fun setRunning(on: Boolean) {
        if (on == running) return
        running = on
        removeCallbacks(tick)
        if (on) postDelayed(tick, FRAME_MS)
    }

    // ---------- Behaviour (after oneko.js's frame() and idle()) ----------

    private fun frame() {
        frameCount += 1
        if (width <= size) return

        if (abs(targetX - x) < speed) {
            idle()
            return
        }

        idleAnimation = null
        idleAnimationFrame = 0

        // Looks up for a moment before setting off
        if (idleTime > 1) {
            setSprite("alert", 0)
            idleTime = minOf(idleTime, 7) - 1
            return
        }

        val dir = if (targetX > x) 1 else -1
        setSprite(if (dir > 0) "E" else "W", frameCount)
        x = (x + dir * speed).coerceIn(0, width - size)
    }

    private fun idle() {
        idleTime += 1

        if (idleTime > 10 && idleAnimation == null) {
            when {
                // Every few seconds: yawn, nap, or a scratch (against the wall if it's at one)
                Random.nextInt(IDLE_ANIMATION_CHANCE) == 0 -> {
                    val options = mutableListOf("yawn", "yawn", "sleeping", "scratchSelf")
                    if (x <= speed) options += "scratchWallW"
                    if (x >= width - size - speed) options += "scratchWallE"
                    idleAnimation = options.random()
                }
                // Otherwise, now and then, wander off somewhere new
                idleTime > 30 && Random.nextInt(WANDER_CHANCE) == 0 -> {
                    targetX = Random.nextInt(width - size)
                    return
                }
            }
        }

        when (idleAnimation) {
            "yawn" -> {
                setSprite("tired", 0)
                if (idleAnimationFrame > 12) resetIdleAnimation()
            }
            "sleeping" -> {
                if (idleAnimationFrame < 8) {
                    setSprite("tired", 0)
                } else {
                    setSprite("sleeping", idleAnimationFrame / 4)
                    if (idleAnimationFrame > 192) resetIdleAnimation()
                }
            }
            "scratchSelf", "scratchWallW", "scratchWallE" -> {
                setSprite(idleAnimation!!, idleAnimationFrame)
                if (idleAnimationFrame > 9) resetIdleAnimation()
            }
            else -> {
                setSprite("idle", 0)
                return
            }
        }
        idleAnimationFrame += 1
    }

    private fun resetIdleAnimation() {
        idleAnimation = null
        idleAnimationFrame = 0
    }

    private fun setSprite(name: String, frame: Int) {
        val frames = SPRITES.getValue(name)
        sprite = frames[frame % frames.size]
    }

    override fun onDraw(canvas: Canvas) {
        if (x < 0) return
        // oneko.js gives background offsets: column = -x, row = -y
        val col = -sprite[0]
        val row = -sprite[1]
        src.set(col * SPRITE, row * SPRITE, (col + 1) * SPRITE, (row + 1) * SPRITE)
        dst.set(x, height - size, x + size, height)
        canvas.drawBitmap(sheet, src, dst, paint)
    }

    private companion object {
        const val SPRITE = 32
        const val FRAME_MS = 100L
        const val IDLE_ANIMATION_CHANCE = 80
        const val WANDER_CHANCE = 50

        // From oneko.js's spriteSets
        val SPRITES = mapOf(
            "idle" to listOf(intArrayOf(-3, -3)),
            "alert" to listOf(intArrayOf(-7, -3)),
            "scratchSelf" to listOf(intArrayOf(-5, 0), intArrayOf(-6, 0), intArrayOf(-7, 0)),
            "scratchWallE" to listOf(intArrayOf(-2, -2), intArrayOf(-2, -3)),
            "scratchWallW" to listOf(intArrayOf(-4, 0), intArrayOf(-4, -1)),
            "tired" to listOf(intArrayOf(-3, -2)),
            "sleeping" to listOf(intArrayOf(-2, 0), intArrayOf(-2, -1)),
            "E" to listOf(intArrayOf(-3, 0), intArrayOf(-3, -1)),
            "W" to listOf(intArrayOf(-4, -2), intArrayOf(-4, -3)),
        )
    }
}
