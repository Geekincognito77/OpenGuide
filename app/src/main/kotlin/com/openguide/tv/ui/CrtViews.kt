package com.openguide.tv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.openguide.tv.data.GuideRow
import kotlin.random.Random

/**
 * The guide channel: what's on now and next, scrolling slowly up the screen for as long as you leave
 * it on. Drawn on a canvas like the guide itself, because a stick can't scroll hundreds of nested views.
 */
class GuideChannelView(ctx: Context) : View(ctx) {
    private var rows: List<GuideRow> = emptyList()
    /** Each row's text once fitted to the screen, so it's only worked out the first time it's drawn. */
    private var fitted: Array<Array<String>?> = emptyArray()
    private var scroll = 0f
    private var lastFrame = 0L
    private var clockText = ""
    private var clockMinute = -1L

    private val headerH = ctx.dp(38)
    private val rowH = ctx.dp(78)
    private val pad = ctx.dpf(12f)
    private val speed = ctx.dpf(SCROLL_DP_PER_SECOND)
    private val bevel = ctx.dpf(1.5f)
    private val fill = Paint()
    private val namePaint = text(18f, Look.boldFace)
    private val linePaint = text(16.5f, Look.plainFace)
    private val headerPaint = text(19f, Look.boldFace)

    init {
        visibility = GONE
    }

    private fun text(sp: Float, face: Typeface) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp * resources.displayMetrics.scaledDensity
        typeface = face
        textScaleX = Look.textScaleX
        setShadowLayer(1f, context.dpf(1.5f), context.dpf(1.5f), Color.BLACK)
    }

    fun setRows(next: List<GuideRow>) {
        rows = next
        fitted = arrayOfNulls(next.size)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fitted = arrayOfNulls(rows.size)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        lastFrame = 0
        if (visibility == VISIBLE) invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Palette.BG)
        val listH = height - headerH
        val total = rows.size * rowH.toFloat()
        val moving = total > listH
        val now = SystemClock.uptimeMillis()
        if (moving) {
            if (lastFrame != 0L) scroll = (scroll + (now - lastFrame) * speed / 1000f) % total
            lastFrame = now
        } else {
            scroll = 0f
        }

        if (rows.isEmpty()) {
            linePaint.color = Palette.MUTED
            val message = "Listings are still loading."
            c.drawText(message, (width - linePaint.measureText(message)) / 2, height / 2f, linePaint)
        } else {
            val saved = c.save()
            c.clipRect(0, headerH, width, height)
            // Whole pixels, because a line that's blended across two scan lines shimmers on a tube.
            val top = scroll.toInt()
            var i = top / rowH
            var y = headerH - top % rowH
            while (y < height && (moving || i < rows.size)) {
                drawRow(c, i % rows.size, y.toFloat())
                i++
                y += rowH
            }
            c.restoreToCount(saved)
        }
        drawHeader(c)
        if (moving && visibility == VISIBLE) postInvalidateOnAnimation()
    }

    private fun drawHeader(c: Canvas) {
        fill.color = Palette.HEADER
        c.drawRect(0f, 0f, width.toFloat(), headerH.toFloat(), fill)
        val base = headerH * 0.7f
        headerPaint.color = Palette.ACCENT
        // Centred, as the channel number appears at the top left.
        val title = "GUIDE CHANNEL"
        c.drawText(title, (width - headerPaint.measureText(title)) / 2, base, headerPaint)
        val wall = System.currentTimeMillis()
        if (wall / 60_000 != clockMinute) {
            clockMinute = wall / 60_000
            clockText = timeOf(context, wall)
        }
        headerPaint.color = Palette.TEXT
        c.drawText(clockText, width - pad - headerPaint.measureText(clockText), base, headerPaint)
    }

    private fun drawRow(c: Canvas, index: Int, y: Float) {
        drawBevel(c, fill, 0f, y + 1, width.toFloat(), y + rowH - 1, if (index % 2 == 0) Palette.PANEL else Palette.CELL, bevel)
        val lines = fitted[index] ?: fit(rows[index]).also { fitted[index] = it }
        namePaint.color = Palette.ACCENT
        c.drawText(lines[0], pad, y + rowH * 0.31f, namePaint)
        linePaint.color = Palette.TEXT
        c.drawText(lines[1], pad, y + rowH * 0.62f, linePaint)
        if (lines[2].isNotEmpty()) {
            linePaint.color = Palette.MUTED
            c.drawText(lines[2], pad, y + rowH * 0.89f, linePaint)
        }
    }

    private fun fit(row: GuideRow): Array<String> {
        val avail = width - pad * 2
        fun cut(s: String, paint: TextPaint) = TextUtils.ellipsize(s, paint, avail, TextUtils.TruncateAt.END).toString()
        val next = row.next?.let { title -> "${row.nextAt?.let { timeOf(context, it) }.orEmpty()}  $title" }
        return arrayOf(
            cut("${row.number}  ${row.name}", namePaint),
            cut("Now  ${row.now}", linePaint),
            next?.let { cut(it, linePaint) }.orEmpty(),
        )
    }

    private companion object {
        const val SCROLL_DP_PER_SECOND = 28f
    }
}

/**
 * Analog snow for a channel change, which also hides the wait for the new stream. A few coarse frames
 * of noise are made once and flicked between, so it costs a stick next to nothing to draw.
 */
class StaticView(ctx: Context) : View(ctx) {
    private val frames: List<Bitmap> by lazy { List(FRAME_COUNT) { noiseFrame() } }
    private val paint = Paint() // no filtering: the noise should stay coarse
    private val target = Rect()
    private var frame = 0
    private var earliestEnd = 0L

    private val nextFrame = object : Runnable {
        override fun run() {
            frame = (frame + 1 + Random.nextInt(FRAME_COUNT - 1)) % FRAME_COUNT
            invalidate()
            postDelayed(this, FRAME_MS)
        }
    }
    private val stop = Runnable { cancel() }

    init {
        visibility = GONE
    }

    private fun noiseFrame(): Bitmap {
        val pixels = IntArray(NOISE_W * NOISE_H)
        for (y in 0 until NOISE_H) {
            // Each line is a little brighter or darker than the last, as snow is on a real set.
            val band = Random.nextInt(-24, 25)
            for (x in 0 until NOISE_W) {
                val v = (Random.nextInt(256) + band).coerceIn(0, 255)
                pixels[y * NOISE_W + x] = Color.rgb(v, v, v)
            }
        }
        return Bitmap.createBitmap(pixels, NOISE_W, NOISE_H, Bitmap.Config.RGB_565)
    }

    /** Runs until [finish], but gives up after a while so a slow stream isn't hidden by snow forever. */
    fun start() {
        removeCallbacks(stop)
        removeCallbacks(nextFrame)
        earliestEnd = SystemClock.uptimeMillis() + SHORTEST_MS
        visibility = VISIBLE
        post(nextFrame)
        postDelayed(stop, LONGEST_MS)
    }

    /** The picture is ready. Still runs for long enough to read as a burst rather than a glitch. */
    fun finish() {
        if (visibility != VISIBLE) return
        removeCallbacks(stop)
        postDelayed(stop, (earliestEnd - SystemClock.uptimeMillis()).coerceAtLeast(0))
    }

    fun cancel() {
        removeCallbacks(stop)
        removeCallbacks(nextFrame)
        visibility = GONE
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancel()
    }

    override fun onDraw(c: Canvas) {
        target.set(0, 0, width, height)
        c.drawBitmap(frames[frame], null, target, paint)
    }

    private companion object {
        const val NOISE_W = 192
        const val NOISE_H = 144
        const val FRAME_COUNT = 4
        const val FRAME_MS = 50L
        const val SHORTEST_MS = 350L
        const val LONGEST_MS = 5_000L
    }
}
