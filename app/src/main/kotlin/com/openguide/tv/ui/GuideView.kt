package com.openguide.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import com.openguide.tv.data.Block
import com.openguide.tv.data.Channel
import com.openguide.tv.data.GUIDE_CHANNEL_ID
import com.openguide.tv.data.HALF_HOUR
import com.openguide.tv.data.Listings
import com.openguide.tv.data.Repo
import com.openguide.tv.data.buildBlocks
import com.openguide.tv.data.floorHalfHour
import com.openguide.tv.data.rowStep
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The programme grid. Drawn directly on a canvas instead of nesting a view per cell, because a
 * Fire TV stick can't scroll thousands of nested views smoothly.
 *
 * Focus works like a cable box: moving up and down keeps your place in time ([anchor]), and a
 * programme running past the edge of the screen scrolls time before focus leaves it.
 */
class GuideView(
    ctx: Context,
    private val repo: Repo,
    private val logos: Logos,
    private val listings: Listings,
) : View(ctx) {

    private val crt = Look.crt

    var onFocusChanged: (Channel?, Block?) -> Unit = { _, _ -> }
    var onSelect: (Channel, Block?) -> Unit = { _, _ -> }
    var onPreview: (Channel) -> Unit = {}
    var onToggleFavorite: (Channel) -> Unit = {}
    var onOptions: (Channel) -> Unit = {}

    private var channels: List<Channel> = emptyList()
    private var row = 0
    private var topRow = 0
    private var minStart = floorHalfHour(System.currentTimeMillis())
    private var viewStart = minStart
    private var anchor = System.currentTimeMillis()
    /** OK was pressed here and hasn't yet become a hold, so releasing it means "watch". */
    private var okArmed = false
    var tunedChannelId: String? = null
        set(v) { field = v; invalidate() }

    /** Colour-codes programme types, which old guides used so you could scan for a film or a game. */
    private fun crtTint(category: String?): Int? {
        val c = category?.lowercase() ?: return null
        return when {
            "movie" in c || "film" in c -> TINT_MOVIE
            "sport" in c -> TINT_SPORT
            "news" in c -> TINT_NEWS
            "kid" in c || "child" in c || "anim" in c -> TINT_KIDS
            else -> null
        }
    }

    private val headerH = ctx.dp(if (crt) 28 else 24)
    private val rowH = ctx.dp(if (crt) 48 else 50)
    private val channelW = ctx.dp(if (crt) 200 else 190)
    private val gap = ctx.dpf(if (crt) 3f else 2f)
    private val radius = if (crt) 0f else ctx.dpf(4f)
    private val pad = ctx.dpf(8f)
    /** A CRT can't resolve as much text, so it shows fewer half hours in bigger cells. */
    private val span = (if (crt) 3 else 4) * HALF_HOUR
    private val titleSize = if (crt) 16.5f else 14.5f
    private val cellNameSize = if (crt) 15f else 13f
    private val bevel = ctx.dpf(1.5f)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val titlePaint = text(titleSize, Look.boldFace)
    private val subPaint = text(11.5f, Look.plainFace)
    private val numberPaint = text(if (crt) 18f else 12f, Look.boldFace)
    private val headerPaint = text(if (crt) 16f else 12.5f, Look.boldFace)
    private val rect = RectF()
    private val src = Rect()
    private val dst = RectF()
    private val arrow = Path()
    private val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private fun text(sp: Float, tf: Typeface) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp * resources.displayMetrics.scaledDensity
        typeface = tf
        textScaleX = Look.textScaleX
        shade(true)
    }

    private fun TextPaint.shade(on: Boolean) {
        if (!crt) return
        if (on) setShadowLayer(1f, context.dpf(1.5f), context.dpf(1.5f), android.graphics.Color.BLACK) else clearShadowLayer()
    }

    private val visibleRows get() = ((height - headerH) / rowH).coerceAtLeast(1)
    private val gridW get() = (width - channelW).coerceAtLeast(1)
    private val pxPerMs get() = gridW.toFloat() / span
    private val horizon get() = minStart + HORIZON

    val currentChannel: Channel? get() = channels.getOrNull(row)

    fun setChannels(list: List<Channel>) {
        val keep = currentChannel?.id
        channels = list
        // If this channel just left the list, stay at the same height rather than jumping to the top.
        val at = list.indexOfFirst { it.id == keep }
        row = if (at >= 0) at else row.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        clampScroll()
        invalidate()
        notifyFocus()
    }

    fun selectChannel(id: String?) {
        val i = channels.indexOfFirst { it.id == id }
        if (i >= 0) {
            row = i
            clampScroll()
            invalidate()
            notifyFocus()
        }
    }

    /** Called every so often: time moves on, so the guide's left edge must too. */
    fun tick() {
        val now = System.currentTimeMillis()
        minStart = floorHalfHour(now)
        if (viewStart < minStart) viewStart = minStart
        if (anchor < now) anchor = now
        invalidate()
        notifyFocus()
    }

    fun isAtNow(): Boolean = viewStart == minStart

    fun jumpToNow() {
        viewStart = minStart
        anchor = System.currentTimeMillis()
        invalidate()
        notifyFocus()
    }

    private val tunedRow get() = channels.indexOfFirst { it.id == tunedChannelId }

    /** On the channel being watched, at the current time. A channel not in this list can't count against it. */
    fun isHome(): Boolean = isAtNow() && (tunedRow < 0 || tunedRow == row)

    fun goHome() {
        tunedRow.takeIf { it >= 0 }?.let {
            row = it
            clampScroll()
        }
        jumpToNow()
    }

    fun refreshFocus() {
        invalidate()
        notifyFocus()
    }

    private fun clampScroll() {
        if (row < topRow) topRow = row
        if (row >= topRow + visibleRows) topRow = row - visibleRows + 1
        topRow = topRow.coerceIn(0, (channels.size - visibleRows).coerceAtLeast(0))
    }

    private fun blocks(index: Int): List<Block> {
        val ch = channels.getOrNull(index) ?: return emptyList()
        return buildBlocks(listings.get(ch.id).orEmpty(), minStart, horizon)
    }

    private fun focusedBlock(): Block? {
        val list = blocks(row)
        return list.firstOrNull { anchor >= it.start && anchor < it.stop } ?: list.lastOrNull()
    }

    private fun notifyFocus() = onFocusChanged(currentChannel, focusedBlock())

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clampScroll()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val moved = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> moveRows(rowStep(event.repeatCount))
            KeyEvent.KEYCODE_DPAD_UP -> {
                // A fresh press moves up to the tabs; a held key stops at the top instead of overshooting.
                if (row == 0) return event.repeatCount > 0
                moveRows(-rowStep(event.repeatCount))
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> moveRows(visibleRows)
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> moveRows(-visibleRows)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveRight()
            KeyEvent.KEYCODE_DPAD_LEFT -> moveLeft()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.repeatCount == 0) {
                    okArmed = true
                } else if (okArmed) {
                    okArmed = false
                    currentChannel?.let(onOptions)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (event.repeatCount == 0) currentChannel?.let(onPreview)
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (event.repeatCount == 0) currentChannel?.let(onToggleFavorite)
                return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        if (moved) {
            invalidate()
            notifyFocus()
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (okArmed) {
                okArmed = false
                currentChannel?.let { onSelect(it, focusedBlock()) }
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        okArmed = false
        invalidate()
    }

    private fun moveRows(delta: Int): Boolean {
        val next = (row + delta).coerceIn(0, (channels.size - 1).coerceAtLeast(0))
        if (next == row) return false
        row = next
        clampScroll()
        return true
    }

    private fun moveRight(): Boolean {
        val list = blocks(row)
        val i = list.indexOfFirst { anchor >= it.start && anchor < it.stop }
        if (i < 0) return false
        val b = list[i]
        val viewEnd = viewStart + span
        if (b.stop > viewEnd) {
            if (viewEnd >= horizon) return false
            viewStart += HALF_HOUR
            anchor = maxOf(anchor, viewStart)
            return true
        }
        val next = list.getOrNull(i + 1) ?: return false
        anchor = next.start
        while (next.start >= viewStart + span - HALF_HOUR / 2) viewStart += HALF_HOUR
        return true
    }

    private fun moveLeft(): Boolean {
        val list = blocks(row)
        val i = list.indexOfFirst { anchor >= it.start && anchor < it.stop }
        if (i < 0) return false
        val b = list[i]
        if (b.start < viewStart && viewStart > minStart) {
            viewStart -= HALF_HOUR
            anchor = maxOf(b.start, viewStart)
            return true
        }
        val prev = list.getOrNull(i - 1) ?: return false
        anchor = maxOf(prev.start, minStart)
        if (anchor < viewStart) viewStart = maxOf(floorHalfHour(anchor), minStart)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (!crt) canvas.drawColor(Palette.BG) // the CRT look's background shows through
        if (channels.isEmpty()) {
            titlePaint.color = Palette.MUTED
            canvas.drawText("No channels here.", pad, headerH + rowH * 0.6f, titlePaint)
            return
        }
        drawHeader(canvas)
        val end = minOf(topRow + visibleRows, channels.size)
        for (i in topRow until end) drawRow(canvas, i, headerH + (i - topRow) * rowH.toFloat())

        // Pre-load just off screen so scrolling doesn't reveal empty rows.
        for (i in end until minOf(end + 6, channels.size)) listings.get(channels[i].id)
        for (i in maxOf(topRow - 6, 0) until topRow) listings.get(channels[i].id)

        val now = System.currentTimeMillis()
        if (now in viewStart..(viewStart + span)) {
            val x = channelW + (now - viewStart) * pxPerMs
            // Bright in the time header, faint over the grid so it doesn't cut through titles.
            fill.color = Palette.ACCENT
            if (crt) {
                // A notch under the times, since a line would cut through the big titles.
                val s = context.dpf(6f)
                arrow.reset()
                arrow.moveTo(x - s, headerH - s)
                arrow.lineTo(x + s, headerH - s)
                arrow.lineTo(x, headerH.toFloat())
                arrow.close()
                canvas.drawPath(arrow, fill)
                return
            }
            canvas.drawRect(x - gap / 2, 0f, x + gap / 2, headerH.toFloat(), fill)
            fill.alpha = 90
            val bottom = headerH + minOf(visibleRows, channels.size) * rowH.toFloat()
            canvas.drawRect(x - gap / 2, headerH.toFloat(), x + gap / 2, bottom, fill)
            fill.alpha = 255
        }
    }

    private fun drawHeader(c: Canvas) {
        fill.color = Palette.HEADER
        c.drawRect(0f, 0f, width.toFloat(), headerH.toFloat(), fill)
        val base = headerH * 0.68f
        headerPaint.color = Palette.ACCENT
        c.drawText(dayLabel(viewStart), pad, base, headerPaint)
        headerPaint.color = if (crt) Palette.TEXT else Palette.MUTED
        var t = viewStart
        while (t < viewStart + span) {
            c.drawText(timeOf(context, t), channelW + (t - viewStart) * pxPerMs + pad / 2, base, headerPaint)
            t += HALF_HOUR
        }
    }

    private fun dayLabel(t: Long): String {
        val a = Calendar.getInstance()
        val b = Calendar.getInstance().apply { timeInMillis = t }
        if (a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)) return "Today"
        a.add(Calendar.DAY_OF_YEAR, 1)
        if (a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)) return "Tomorrow"
        return dayFormat.format(Date(t))
    }

    private fun drawRow(c: Canvas, index: Int, y: Float) {
        val ch = channels[index]
        drawChannelCell(c, ch, index == row, y)

        val saved = c.save()
        c.clipRect(channelW.toFloat(), y, width.toFloat(), y + rowH)
        val viewEnd = viewStart + span
        val focused = focusedIfRow(index)
        val now = System.currentTimeMillis()
        for (b in blocks(index)) {
            if (b.stop <= viewStart) continue
            if (b.start >= viewEnd) break
            drawBlock(c, ch, b, y, b == focused, now)
        }
        c.restoreToCount(saved)
    }

    private fun focusedIfRow(index: Int): Block? = if (index == row) focusedBlock() else null

    private fun drawChannelCell(c: Canvas, ch: Channel, isRow: Boolean, y: Float) {
        rect.set(0f, y + gap / 2, channelW - gap, y + rowH - gap / 2)
        val cellColor = if (isRow) Palette.CELL_NOW else Palette.PANEL
        if (crt) {
            drawBevel(c, fill, rect.left, rect.top, rect.right, rect.bottom, cellColor, bevel)
        } else {
            fill.color = cellColor
            c.drawRoundRect(rect, radius, radius, fill)
        }

        val dead = ch.id in repo.deadChannels
        numberPaint.color = if (dead) Palette.FAINT else if (crt) Palette.ACCENT else Palette.MUTED
        val base = y + rowH / 2f + numberPaint.textSize * 0.36f
        c.drawText((repo.numbers[ch.id] ?: 0).toString(), pad * 0.8f, base, numberPaint)

        // Logo, fitted inside a fixed box with its proportions kept. A CRT can't show one that small.
        val boxL = context.dpf(if (crt) 54f else 38f)
        val boxW = if (crt) 0f else context.dpf(44f)
        val boxH = rowH - context.dpf(14f)
        if (!crt) logos.get(ch.logo) { invalidate() }?.let { bmp ->
            val scale = minOf(boxW / bmp.width, boxH / bmp.height)
            val w = bmp.width * scale
            val h = bmp.height * scale
            src.set(0, 0, bmp.width, bmp.height)
            dst.set(boxL + (boxW - w) / 2, y + (rowH - h) / 2, boxL + (boxW + w) / 2, y + (rowH + h) / 2)
            bitmapPaint.alpha = if (dead) 110 else 255
            c.drawBitmap(bmp, src, dst, bitmapPaint)
        }

        val nameX = boxL + boxW + pad * 0.8f
        var nameW = channelW - nameX - pad * 0.6f
        if (ch.id == tunedChannelId) nameW -= context.dpf(10f)
        val favorite = ch.id in repo.favorites.value
        val name = (if (favorite) "★ " else "") + ch.name
        titlePaint.textSize = cellNameSize * resources.displayMetrics.scaledDensity
        titlePaint.color = if (dead) Palette.FAINT else Palette.TEXT
        if (crt) {
            drawWrapped(c, name, nameX, y, nameW)
        } else {
            val shown = TextUtils.ellipsize(name, titlePaint, nameW, TextUtils.TruncateAt.END).toString()
            c.drawText(shown, nameX, y + rowH / 2f + titlePaint.textSize * 0.36f, titlePaint)
        }
        titlePaint.textSize = titleSize * resources.displayMetrics.scaledDensity

        if (ch.id == tunedChannelId) {
            fill.color = Palette.ACCENT
            c.drawCircle(channelW - context.dpf(9f), y + rowH / 2f, context.dpf(3.5f), fill)
        }
    }

    private fun drawBlock(c: Canvas, ch: Channel, b: Block, y: Float, isFocus: Boolean, now: Long) {
        val left = channelW + (maxOf(b.start, viewStart) - viewStart) * pxPerMs
        val right = channelW + (minOf(b.stop, viewStart + span) - viewStart) * pxPerMs
        rect.set(left + gap / 2, y + gap / 2, right - gap / 2, y + rowH - gap / 2)

        val airing = now >= b.start && now < b.stop
        val active = isFocus && hasFocus()
        val tint = if (crt) crtTint(b.programme?.category) else null
        val cellColor = when {
            active -> Palette.ACCENT
            isFocus -> Palette.FAINT
            b.programme == null -> Palette.CELL_EMPTY
            tint != null -> tint
            airing -> Palette.CELL_NOW
            else -> Palette.CELL
        }
        if (crt) {
            drawBevel(c, fill, rect.left, rect.top, rect.right, rect.bottom, cellColor, bevel)
        } else {
            fill.color = cellColor
            c.drawRoundRect(rect, radius, radius, fill)
        }

        val p = b.programme
        val textLeft = rect.left + pad
        val avail = rect.width() - pad * 2
        val continued = b.start < viewStart
        val titleColor = when {
            active -> Palette.ON_ACCENT
            p == null -> Palette.FAINT
            else -> Palette.TEXT
        }
        val subColor = if (active) Palette.ON_ACCENT else Palette.MUTED

        var tx = textLeft
        if (continued) {
            arrow.reset()
            val cy = y + rowH / 2f
            val s = context.dpf(4f)
            arrow.moveTo(rect.left + s, cy - s)
            arrow.lineTo(rect.left, cy)
            arrow.lineTo(rect.left + s, cy + s)
            arrow.close()
            fill.color = titleColor
            c.drawPath(arrow, fill)
        }
        if (avail <= pad) return
        titlePaint.color = titleColor
        val title = when {
            p != null -> p.title
            ch.id == GUIDE_CHANNEL_ID -> "What's on, scrolling"
            listings.get(ch.id) == null -> "…"
            else -> "No listings"
        }
        if (crt) {
            titlePaint.shade(!active)
            drawWrapped(c, title, tx, y, avail)
            titlePaint.shade(true)
            return
        }
        val line = TextUtils.ellipsize(title, titlePaint, tx + avail - textLeft, TextUtils.TruncateAt.END).toString()
        val sub = when {
            p == null -> null
            !p.subtitle.isNullOrEmpty() -> p.subtitle
            else -> "${timeOf(context, p.start)} – ${timeOf(context, p.stop)}"
        }
        if (sub == null || rect.height() < titlePaint.textSize * 2.2f) {
            c.drawText(line, tx, y + rowH / 2f + titlePaint.textSize * 0.36f, titlePaint)
        } else {
            c.drawText(line, tx, y + rowH * 0.45f, titlePaint)
            subPaint.color = subColor
            val subLine = TextUtils.ellipsize(sub, subPaint, avail, TextUtils.TruncateAt.END).toString()
            c.drawText(subLine, tx, y + rowH * 0.80f, subPaint)
        }
    }

    /** Old guides wrap a title onto a second line rather than showing a subtitle. */
    private fun drawWrapped(c: Canvas, title: String, x: Float, y: Float, avail: Float) {
        val fit = titlePaint.breakText(title, true, avail, null)
        if (fit >= title.length) {
            c.drawText(title, x, y + rowH / 2f + titlePaint.textSize * 0.36f, titlePaint)
            return
        }
        val space = title.lastIndexOf(' ', fit)
        val cut = if (space > 0) space else fit
        c.drawText(title.substring(0, cut), x, y + rowH * 0.43f, titlePaint)
        val rest = TextUtils.ellipsize(title.substring(cut).trim(), titlePaint, avail, TextUtils.TruncateAt.END)
        c.drawText(rest.toString(), x, y + rowH * 0.84f, titlePaint)
    }

    private companion object {
        val TINT_MOVIE = android.graphics.Color.parseColor("#4A2A9C")
        val TINT_SPORT = android.graphics.Color.parseColor("#14684C")
        val TINT_NEWS = android.graphics.Color.parseColor("#14607C")
        val TINT_KIDS = android.graphics.Color.parseColor("#7C4A1C")
        const val HORIZON = 48 * 2 * HALF_HOUR
    }
}
