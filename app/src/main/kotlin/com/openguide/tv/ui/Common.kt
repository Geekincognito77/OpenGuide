package com.openguide.tv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.TextView
import coil.ImageLoader
import coil.request.ImageRequest
import coil.size.Precision
import okhttp3.OkHttpClient
import java.util.Date

object Palette {
    var BG = 0; private set
    var PANEL = 0; private set
    var CELL = 0; private set
    var CELL_NOW = 0; private set
    var CELL_EMPTY = 0; private set
    var HEADER = 0; private set
    var ACCENT = 0; private set
    var ON_ACCENT = 0; private set
    var TEXT = 0; private set
    var MUTED = 0; private set
    var FAINT = 0; private set
    var BAD = 0; private set
    var BANNER = 0; private set

    init { use(crt = false) }

    /**
     * The CRT colours are flat and a little short of full strength, because saturated reds and thin
     * lines smear and crawl over composite video.
     */
    fun use(crt: Boolean) {
        fun c(modern: String, tube: String) = Color.parseColor(if (crt) tube else modern)
        BG = c("#0A1024", "#00002C")
        PANEL = c("#141C3A", "#14288C")
        CELL = c("#1C2750", "#1A3098")
        CELL_NOW = c("#2A3A73", "#2848BC")
        CELL_EMPTY = c("#121A36", "#101C68")
        HEADER = c("#0F1730", "#000020")
        ACCENT = c("#FFB300", "#F5D800")
        ON_ACCENT = c("#141006", "#00002C")
        TEXT = c("#F4F6FF", "#F0F0F0")
        MUTED = c("#A4AECF", "#C4D0F4")
        FAINT = c("#66729A", "#8494D4")
        BAD = c("#FF8A80", "#FFA888")
        BANNER = c("#E60A1024", "#F000003C")
    }
}

/** What CRT mode changes about how everything is drawn. Set before any view is built. */
object Look {
    var crt = false; private set
    /**
     * A converter squeezing a 16:9 picture onto a 4:3 tube makes everything narrower, so text is
     * drawn wider by the same amount and comes out the right shape.
     */
    var textScaleX = 1f; private set
    val fontScale get() = if (crt) 1.12f else 1f
    val boldFace: Typeface get() = Typeface.DEFAULT_BOLD
    /** Thin strokes shimmer on a tube, so CRT mode has no light weight. */
    val plainFace: Typeface get() = if (crt) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

    fun use(crt: Boolean, squeezed: Boolean, frameAspect: Float) {
        this.crt = crt
        textScaleX = if (crt && squeezed) (frameAspect / (4f / 3f)).coerceAtLeast(1f) else 1f
        Palette.use(crt)
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun roundRect(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0): Drawable {
    if (Look.crt && color != Color.TRANSPARENT) return Bevel(color, radius, strokeColor, strokeWidth)
    return GradientDrawable().apply {
        setColor(color)
        cornerRadius = if (Look.crt) 0f else radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }
}

/** The raised look of an old on-screen display: lit along the top and left, shaded along the bottom and right. */
private class Bevel(private val color: Int, radius: Float, private val stroke: Int, private val strokeW: Int) : Drawable() {
    private val paint = Paint()
    /** Callers pass a corner radius in device pixels, which scales with screen density, as this should. */
    private val edge = (radius / 2f).coerceIn(2f, 6f)

    override fun draw(c: Canvas) = drawBevel(c, paint, bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), color, edge).also {
        if (strokeW > 0) {
            paint.color = stroke
            val b = bounds
            val w = strokeW.toFloat()
            c.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + w, paint)
            c.drawRect(b.left.toFloat(), b.bottom - w, b.right.toFloat(), b.bottom.toFloat(), paint)
            c.drawRect(b.left.toFloat(), b.top.toFloat(), b.left + w, b.bottom.toFloat(), paint)
            c.drawRect(b.right - w, b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
        }
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

fun drawBevel(c: Canvas, paint: Paint, l: Float, t: Float, r: Float, b: Float, color: Int, edge: Float) {
    paint.color = color
    c.drawRect(l, t, r, b, paint)
    paint.color = Color.argb(90, 255, 255, 255)
    c.drawRect(l, t, r, t + edge, paint)
    c.drawRect(l, t + edge, l + edge, b, paint)
    paint.color = Color.argb(130, 0, 0, 0)
    c.drawRect(l + edge, b - edge, r, b, paint)
    c.drawRect(r - edge, t + edge, r, b - edge, paint)
}

/** Hard-edged drop shadow, which keeps text readable over any colour on a blurry picture. */
fun TextView.setCrtShadow(on: Boolean) {
    if (!Look.crt) return
    if (on) setShadowLayer(1f, context.dpf(1.5f), context.dpf(1.5f), Color.BLACK) else setShadowLayer(0f, 0f, 0f, 0)
}

fun Context.label(
    text: String = "",
    size: Float = 14f,
    color: Int = Palette.TEXT,
    bold: Boolean = false,
    lines: Int = 1,
): TextView = TextView(this).apply {
    this.text = text
    textSize = size * Look.fontScale
    setTextColor(color)
    includeFontPadding = false
    typeface = if (bold) Look.boldFace else Look.plainFace
    textScaleX = Look.textScaleX
    setCrtShadow(true)
    maxLines = lines
    ellipsize = TextUtils.TruncateAt.END
    gravity = Gravity.START or Gravity.CENTER_VERTICAL
}

/** A text button that shows D-pad focus in the accent colour, the only cue a remote user gets. */
fun Context.focusButton(text: String, size: Float = 14f, onClick: () -> Unit): TextView = label(text, size).apply {
    isFocusable = true
    isClickable = true
    setPadding(dp(12), dp(6), dp(12), dp(6))
    val radius = dpf(6f)
    background = roundRect(Color.TRANSPARENT, radius)
    onFocusChangeListener = View.OnFocusChangeListener { v, has ->
        v.background = roundRect(if (has) Palette.ACCENT else Color.TRANSPARENT, radius)
        (v as TextView).setTextColor(if (has) Palette.ON_ACCENT else (v.tag as? Int ?: Palette.TEXT))
        v.setCrtShadow(!has)
    }
    setOnClickListener { onClick() }
}

fun timeOf(ctx: Context, t: Long): String = DateFormat.getTimeFormat(ctx).format(Date(t))

fun durationLeft(ms: Long): String {
    val mins = ((ms + 59_999) / 60_000).toInt()
    return if (mins >= 60) "${mins / 60}h ${mins % 60}m left" else "$mins min left"
}

/** Keeps decoded logos in memory so the guide can draw them directly on its canvas. */
class Logos(private val ctx: Context, http: OkHttpClient) {
    private val loader = ImageLoader.Builder(ctx)
        .okHttpClient(http)
        .allowHardware(false)
        .build()
    private val cache = LruCache<String, Bitmap>(240)
    private val inFlight = HashSet<String>()
    private val failed = HashSet<String>()

    /** Returns the logo if ready; otherwise starts loading it and calls [onReady] when it arrives. */
    fun get(url: String?, onReady: () -> Unit): Bitmap? {
        if (url == null || url in failed) return null
        cache.get(url)?.let { return it }
        if (inFlight.add(url)) {
            loader.enqueue(
                ImageRequest.Builder(ctx).data(url).size(160, 100).precision(Precision.INEXACT)
                    .target(
                        onSuccess = { d ->
                            inFlight.remove(url)
                            (d as? BitmapDrawable)?.bitmap?.let { cache.put(url, it) }
                            onReady()
                        },
                        onError = {
                            inFlight.remove(url)
                            failed += url
                        },
                    ).build()
            )
        }
        return null
    }
}
