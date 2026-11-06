package com.openguide.tv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.openguide.tv.BuildConfig
import com.openguide.tv.data.CATEGORY_ALL
import com.openguide.tv.data.CATEGORY_CRT
import com.openguide.tv.data.CATEGORY_FAVORITES
import com.openguide.tv.data.Channel
import com.openguide.tv.data.CrtStretch
import com.openguide.tv.data.LANGUAGE_ALL
import com.openguide.tv.data.Programme
import com.openguide.tv.data.REGION_WORLD
import com.openguide.tv.data.Repo

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

data class PickerItem(val id: String, val label: String)

fun regionLabel(repo: Repo): String {
    val r = repo.region
    return "Region: " + if (r == REGION_WORLD) "Worldwide" else repo.catalog.value?.countries?.firstOrNull { it.code == r }?.name ?: r
}

fun languageLabel(repo: Repo): String {
    val l = repo.language
    return "Language: " + if (l == LANGUAGE_ALL) "All" else repo.catalog.value?.languages?.firstOrNull { it.code == l }?.name ?: l
}

/** A frame to line up with the edge of the picture, so the UI can stay inside what the tube shows. */
class OverscanOverlay(ctx: Context) : View(ctx) {
    private val line = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = ctx.dpf(4f)
    }
    private val text = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f * resources.displayMetrics.scaledDensity
        typeface = Look.boldFace
        textScaleX = Look.textScaleX
        textAlign = Paint.Align.CENTER
    }
    private var insetX = 0
    private var insetY = 0

    init {
        setBackgroundColor(Color.BLACK)
        visibility = GONE
    }

    val isShowing get() = visibility == VISIBLE

    fun show(xPercent: Int, yPercent: Int) {
        visibility = VISIBLE
        update(xPercent, yPercent)
    }

    fun update(xPercent: Int, yPercent: Int) {
        insetX = xPercent
        insetY = yPercent
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val x = width * insetX / 100f
        val y = height * insetY / 100f
        val half = line.strokeWidth / 2
        c.drawRect(x + half, y + half, width - x - half, height - y - half, line)
        val mid = width / 2f
        var ty = height * 0.40f
        val step = text.textSize * 1.5f
        listOf(
            "Fit the white frame to your screen",
            "Left / Right: narrower / wider",
            "Up / Down: taller / shorter",
            "Width $insetX%   Height $insetY%",
            "OK or Back: done",
        ).forEach {
            c.drawText(it, mid, ty, text)
            ty += step
        }
    }
}

/** Full-screen scrim with a centred list; every choice in the app (region, category, source) uses it. */
class PickerOverlay(ctx: Context) : FrameLayout(ctx) {
    private val title = ctx.label(size = 18f, bold = true)
    private val list = RecyclerView(ctx).apply {
        layoutManager = LinearLayoutManager(ctx)
        itemAnimator = null
    }
    private var pickListener: (PickerItem) -> Unit = {}
    private var returnFocus: View? = null
    private lateinit var panel: View

    init {
        setBackgroundColor(Color.parseColor("#B3050814"))
        visibility = GONE
        isClickable = true
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(Palette.PANEL, ctx.dpf(10f))
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
        }
        panel.addView(title, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = ctx.dp(10) })
        panel.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        this.panel = panel
        addView(panel, LayoutParams(ctx.dp(420), ctx.dp(420), Gravity.CENTER))
    }

    val isShowing get() = visibility == VISIBLE

    fun show(heading: String, items: List<PickerItem>, selected: String?, onPick: (PickerItem) -> Unit) {
        title.text = heading
        pickListener = onPick
        returnFocus = rootView.findFocus()
        // Fits short lists; long ones (like regions) scroll inside the maximum height.
        panel.layoutParams = panel.layoutParams.apply { height = minOf(context.dp(420), context.dp(72 + 38 * items.size)) }
        val start = items.indexOfFirst { it.id == selected }.coerceAtLeast(0)
        list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = items.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = object : RecyclerView.ViewHolder(
                context.focusButton("", 15f) {}.apply {
                    layoutParams = RecyclerView.LayoutParams(MATCH, context.dp(38))
                }
            ) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val item = items[i]
                (h.itemView as TextView).apply {
                    text = (if (item.id == selected) "✓  " else "") + item.label
                    setOnClickListener {
                        hide()
                        pickListener(item)
                    }
                }
            }
        }
        visibility = VISIBLE
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(start, context.dp(120))
        list.post {
            list.findViewHolderForAdapterPosition(start)?.itemView?.requestFocus() ?: list.requestFocus()
        }
    }

    /** Hands focus back, because a hidden view's focus otherwise jumps to the first tab. */
    fun hide() {
        visibility = GONE
        returnFocus?.takeIf { it.isShown }?.requestFocus()
        returnFocus = null
    }
}

/** Channel browser: categories and search on the left, a grid of channels on the right. */
class BrowserScreen(
    ctx: Context,
    private val repo: Repo,
    private val logos: Logos,
    private val onTune: (Channel, List<Channel>) -> Unit,
    private val onToggleFavorite: (Channel) -> Unit,
    private val onOptions: (Channel, List<Channel>) -> Unit,
    private val onPickRegion: () -> Unit,
    private val onPickLanguage: () -> Unit,
) : LinearLayout(ctx) {

    var category = CATEGORY_ALL
        private set
    private var query = ""
    private var airing: Map<String, Programme> = emptyMap()
    private var channels: List<Channel> = emptyList()
    private val countLabel = ctx.label(size = 13f, color = Palette.MUTED)
    private val regionButton = ctx.focusButton("", 13f) { onPickRegion() }
    private val languageButton = ctx.focusButton("", 13f) { onPickLanguage() }
    private val search = EditText(ctx).apply {
        hint = "Search channels"
        setHintTextColor(Palette.FAINT)
        setTextColor(Palette.TEXT)
        textSize = 14f
        typeface = Look.plainFace
        textScaleX = Look.textScaleX
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(6))
        background = roundRect(Palette.CELL, ctx.dpf(6f))
        setOnFocusChangeListener { v, has ->
            v.background = roundRect(Palette.CELL, ctx.dpf(6f), Palette.ACCENT, if (has) ctx.dp(2) else 0)
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                reload()
            }
        })
    }
    private val categoryList = RecyclerView(ctx).apply {
        layoutManager = LinearLayoutManager(ctx)
        itemAnimator = null
    }
    private val grid = RecyclerView(ctx).apply {
        layoutManager = GridLayoutManager(ctx, if (Look.crt) 2 else 3)
        itemAnimator = null
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        clipToPadding = false
        setPadding(0, 0, 0, ctx.dp(24))
    }
    private val gridAdapter = ChannelAdapter()
    private var categoryRows: List<PickerItem> = emptyList()
    private val categoryAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = categoryRows.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = object : RecyclerView.ViewHolder(
            context.focusButton("", 13.5f) {}.apply { layoutParams = RecyclerView.LayoutParams(MATCH, context.dp(30)) }
        ) {}
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val item = categoryRows[i]
            (h.itemView as TextView).apply {
                text = item.label
                val color = if (item.id == category) Palette.ACCENT else Palette.TEXT
                tag = color
                setTextColor(if (hasFocus()) Palette.ON_ACCENT else color)
                setOnClickListener {
                    category = item.id
                    refresh()
                }
            }
        }
    }

    init {
        orientation = HORIZONTAL
        val left = LinearLayout(ctx).apply { orientation = VERTICAL }
        left.addView(search, LayoutParams(MATCH, ctx.dp(34)).apply { bottomMargin = ctx.dp(6) })
        left.addView(regionButton, LayoutParams(MATCH, ctx.dp(30)))
        left.addView(languageButton, LayoutParams(MATCH, ctx.dp(30)))
        left.addView(categoryList, LayoutParams(MATCH, 0, 1f).apply { bottomMargin = ctx.dp(120) })
        addView(left, LayoutParams(ctx.dp(200), MATCH).apply { rightMargin = ctx.dp(12) })

        val right = LinearLayout(ctx).apply { orientation = VERTICAL }
        right.addView(countLabel, LayoutParams(MATCH, ctx.dp(22)))
        right.addView(grid, LayoutParams(MATCH, 0, 1f))
        addView(right, LayoutParams(0, MATCH, 1f))
        grid.adapter = gridAdapter
        categoryList.adapter = categoryAdapter
    }

    fun refresh() {
        val c = repo.catalog.value ?: return
        regionButton.text = regionLabel(repo)
        languageButton.text = languageLabel(repo)
        val lineup = repo.filtered(CATEGORY_ALL) // what each row will actually show
        val counts = HashMap<String, Int>()
        lineup.forEach { ch -> ch.categories.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        val rows = buildList {
            add(PickerItem(CATEGORY_ALL, "All channels (${lineup.size})"))
            add(PickerItem(CATEGORY_FAVORITES, "★ Favorites (${repo.favorites.value.size})"))
            add(PickerItem(CATEGORY_CRT, "CRT list (${repo.crtList.value.size})"))
            c.categories.filter { (counts[it.id] ?: 0) > 0 }
                .forEach { add(PickerItem(it.id, "${it.name} (${counts[it.id]})")) }
        }
        val changed = rows != categoryRows
        categoryRows = rows
        // Rebinding in place keeps focus on the row; replacing the rows would drop it.
        if (changed) categoryAdapter.notifyDataSetChanged() else categoryAdapter.notifyItemRangeChanged(0, rows.size)
        reload()
    }

    fun setAiring(map: Map<String, Programme>) {
        airing = map
        // In place, not notifyDataSetChanged: this runs on a timer and must not steal focus.
        gridAdapter.notifyItemRangeChanged(0, channels.size)
    }

    private fun reload() {
        val next = repo.filtered(category, query)
        if (next == channels) {
            gridAdapter.notifyItemRangeChanged(0, channels.size)
            return
        }
        val hadFocus = grid.hasFocus()
        channels = next
        countLabel.text = "${channels.size} channels"
        gridAdapter.notifyDataSetChanged()
        grid.scrollToPosition(0)
        if (hadFocus) focusFirst()
    }

    /** The grid may not have laid out yet right after being shown, so wait for its first card. */
    fun focusFirst(triesLeft: Int = 10) {
        grid.post {
            val first = grid.findViewHolderForAdapterPosition(0)?.itemView
            when {
                first != null -> first.requestFocus()
                triesLeft > 0 && channels.isNotEmpty() -> focusFirst(triesLeft - 1)
                else -> regionButton.requestFocus()
            }
        }
    }

    private inner class ChannelAdapter : RecyclerView.Adapter<ChannelAdapter.Holder>() {
        inner class Holder(val root: FrameLayout) : RecyclerView.ViewHolder(root) {
            val logo = ImageView(root.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val name = root.context.label(size = 14f, bold = true)
            val now = root.context.label(size = 12f, color = Palette.MUTED)
            val progress = ProgressBar(root.context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
            init {
                val c = root.context
                val col = LinearLayout(c).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(c.dp(8), c.dp(6), c.dp(8), c.dp(6))
                }
                col.addView(logo, LayoutParams(c.dp(56), c.dp(40)).apply { rightMargin = c.dp(8) })
                val text = LinearLayout(c).apply { orientation = VERTICAL }
                text.addView(name, LayoutParams(MATCH, WRAP))
                text.addView(now, LayoutParams(MATCH, WRAP).apply { topMargin = c.dp(3) })
                text.addView(progress, LayoutParams(MATCH, c.dp(3)).apply { topMargin = c.dp(4) })
                col.addView(text, LayoutParams(0, WRAP, 1f))
                root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
            }
        }

        override fun getItemCount() = channels.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val c = parent.context
            val root = FrameLayout(c).apply {
                isFocusable = true
                isClickable = true
                layoutParams = RecyclerView.LayoutParams(MATCH, c.dp(68)).apply {
                    setMargins(c.dp(3), c.dp(3), c.dp(3), c.dp(3))
                }
                background = roundRect(Palette.CELL, c.dpf(6f))
            }
            val h = Holder(root)
            root.setOnFocusChangeListener { v, has ->
                v.background = if (has) roundRect(Palette.CELL_NOW, c.dpf(6f), Palette.ACCENT, c.dp(2))
                else roundRect(Palette.CELL, c.dpf(6f))
            }
            return h
        }

        override fun onBindViewHolder(h: Holder, i: Int) {
            val ch = channels[i]
            val fav = ch.id in repo.favorites.value
            h.name.text = (if (fav) "★ " else "") + "${repo.numbers[ch.id] ?: ""}  ${ch.name}"
            h.name.setTextColor(if (ch.id in repo.deadChannels) Palette.FAINT else Palette.TEXT)
            val p = airing[ch.id]
            val now = System.currentTimeMillis()
            h.now.text = p?.title ?: if (ch.id in repo.deadChannels) "Unavailable right now" else "No listings"
            if (p != null) {
                h.progress.visibility = View.VISIBLE
                h.progress.progress = (1000 * (now - p.start) / (p.stop - p.start).coerceAtLeast(1)).toInt()
            } else {
                h.progress.visibility = View.INVISIBLE
            }
            bindLogo(h, ch)
            h.root.setOnClickListener { onTune(ch, channels) }
            h.root.setOnLongClickListener {
                onOptions(ch, channels)
                true
            }
            h.root.setOnKeyListener { _, code, ev ->
                if (code == KeyEvent.KEYCODE_MENU && ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                    onToggleFavorite(ch)
                    val pos = h.bindingAdapterPosition
                    if (pos >= 0) notifyItemChanged(pos)
                    true
                } else false
            }
        }

        private fun bindLogo(h: Holder, ch: Channel) {
            h.logo.tag = ch.id
            val bmp = logos.get(ch.logo) {
                if (h.logo.tag == ch.id) logos.get(ch.logo) {}?.let(h.logo::setImageBitmap)
            }
            h.logo.setImageBitmap(bmp)
        }
    }
}

/** Shows the crop amounts over the playing video, leaving the picture visible while they're adjusted. */
class CropOverlay(ctx: Context) : FrameLayout(ctx) {
    private val text = ctx.label(size = 15f, lines = 3).apply {
        gravity = Gravity.CENTER
        background = roundRect(Palette.PANEL, ctx.dpf(6f))
        setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
    }

    init {
        visibility = GONE
        addView(text, LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ctx.dp(12) })
    }

    val isShowing get() = visibility == VISIBLE

    fun show(x: Int, y: Int) {
        visibility = VISIBLE
        update(x, y)
    }

    fun update(x: Int, y: Int) {
        text.text = "Crop black bars: top and bottom $y%, sides $x%\n" +
            "Up/Down: top and bottom   Left/Right: sides\nOK or Back: done"
    }
}

/** Everything the settings screen asks the activity to do. */
interface SettingsActions {
    fun pickRegion()
    fun pickLanguage()
    fun listsChanged()
    fun crtLookChanged()
    fun crtPictureChanged()
    fun calibrateOverscan()
    fun adjustCrop()
    fun checkForUpdate()
}

/** Region, display options, refresh, and where the data comes from. Scrolls, as it outgrew a TV screen. */
class SettingsScreen(
    ctx: Context,
    private val repo: Repo,
    private val actions: SettingsActions,
) : LinearLayout(ctx) {
    private val prefs get() = repo.prefs
    private fun row(onClick: () -> Unit) = context.focusButton("", 16f, onClick)

    private val region = row { actions.pickRegion() }
    private val language = row { actions.pickLanguage() }
    private val onlyListed = row {
        prefs.onlyWithListings = !prefs.onlyWithListings
        refresh()
        actions.listsChanged()
    }
    private val hideDead = row {
        prefs.hideDead = !prefs.hideDead
        refresh()
        actions.listsChanged()
    }
    private val dpadSurf = row {
        prefs.dpadChangesChannel = !prefs.dpadChangesChannel
        refresh()
    }
    private val refreshNow = row { repo.refreshAll() }.apply { text = "Refresh channels and listings now" }
    private val update = row { actions.checkForUpdate() }

    private val crtMode = row {
        prefs.crtMode = !prefs.crtMode
        actions.crtLookChanged()
    }
    private val crtStretch = row {
        prefs.crtStretch = CrtStretch.entries[(prefs.crtStretch.ordinal + 1) % CrtStretch.entries.size]
        refresh()
        actions.crtPictureChanged()
    }
    private val crtSignal = row {
        prefs.crtSqueezed = !prefs.crtSqueezed
        actions.crtLookChanged()
    }
    private val crtOverscan = row { actions.calibrateOverscan() }
    private val crtCrop = row { actions.adjustCrop() }
    private val crtOverscanVideo = row {
        prefs.crtOverscanVideo = !prefs.crtOverscanVideo
        refresh()
        actions.crtPictureChanged()
    }
    private val crtRows = listOf(crtStretch, crtCrop, crtSignal, crtOverscan, crtOverscanVideo)

    private val stats = ctx.label(size = 13.5f, color = Palette.MUTED, lines = 8)
    private val credits = ctx.label(
        "Channels and streams come from the iptv-org project. Listings come from i.mjh.nz, EPGShare and EPG.PW. " +
            "OpenGuide hosts no video and only links to publicly listed streams; some may be offline or blocked in your area.\n\n" +
            "In the guide: OK watches, and holding OK opens options (preview, favorite, CRT list). Hold Up or Down to scroll faster. " +
            "Channel Up and Down page through the guide. Back returns to the current time, then to the tabs.\n" +
            "While watching: Channel Up and Down change channel, OK shows info, Left returns to the last channel, " +
            "Right or holding OK opens options.\n" +
            "Remotes with Menu, Play/Pause, Rewind or Fast Forward buttons can use those as shortcuts.",
        size = 12.5f, color = Palette.FAINT, lines = 14,
    )

    init {
        orientation = VERTICAL
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        val width = if (Look.crt) MATCH else ctx.dp(620)
        listOf(region, language, onlyListed, hideDead, dpadSurf, crtMode, crtStretch, crtCrop, crtSignal, crtOverscan, crtOverscanVideo, refreshNow, update).forEach {
            col.addView(it, LayoutParams(width, ctx.dp(40)).apply { bottomMargin = ctx.dp(4) })
        }
        col.addView(stats, LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dp(14); bottomMargin = ctx.dp(14) })
        col.addView(credits, LayoutParams(MATCH, WRAP).apply { bottomMargin = ctx.dp(16) })
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        addView(scroll, LayoutParams(MATCH, MATCH))
    }

    private fun onOff(v: Boolean) = if (v) "On" else "Off"

    fun refresh() {
        repo.catalog.value ?: return
        region.text = regionLabel(repo)
        language.text = languageLabel(repo)
        onlyListed.text = "Only channels with listings: " + onOff(prefs.onlyWithListings)
        hideDead.text = "Hide channels with no working source: " + onOff(prefs.hideDead)
        dpadSurf.text = "D-pad Up/Down change channel: " + onOff(prefs.dpadChangesChannel)
        crtMode.text = "CRT TV mode: " + onOff(prefs.crtMode)
        crtStretch.text = "Picture: " + prefs.crtStretch.label
        crtCrop.text = "Crop black bars (${prefs.crtCropY}% top and bottom, ${prefs.crtCropX}% sides)"
        crtSignal.text = "Signal: " + if (prefs.crtSqueezed) "Squeezed to 4:3 (most adapters)" else "Shape kept"
        crtOverscan.text = "Adjust overscan (${prefs.crtOverscanX}% wide, ${prefs.crtOverscanY}% high)"
        crtOverscanVideo.text = "Keep video inside overscan: " + onOff(prefs.crtOverscanVideo)
        crtRows.forEach { it.visibility = if (prefs.crtMode) VISIBLE else GONE }
        if (update.text.isNullOrEmpty()) setUpdateText(null)
    }

    /** @param text a status to show on the update row, or null for its normal label. */
    fun setUpdateText(text: String?) {
        update.text = text ?: "Check for updates (this is version ${BuildConfig.VERSION_NAME})"
    }

    fun setStats(text: String) { stats.text = text }
    fun focusFirst() { region.requestFocus() }
    fun focusCrt() { crtMode.requestFocus() }
}
