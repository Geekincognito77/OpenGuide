package com.openguide.tv.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.openguide.tv.OpenGuideApp
import com.openguide.tv.data.Block
import com.openguide.tv.data.CATEGORY_ALL
import com.openguide.tv.data.CATEGORY_CRT
import com.openguide.tv.data.CATEGORY_FAVORITES
import com.openguide.tv.data.Channel
import com.openguide.tv.data.CrtStretch
import com.openguide.tv.data.LANGUAGE_ALL
import com.openguide.tv.data.MAX_CROP
import com.openguide.tv.data.Listings
import com.openguide.tv.data.Programme
import com.openguide.tv.data.REGION_WORLD
import com.openguide.tv.data.Release
import com.openguide.tv.data.Repo
import com.openguide.tv.data.Updater
import com.openguide.tv.data.croppedAspect
import com.openguide.tv.data.crtVideoSize
import com.openguide.tv.data.uncroppedSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Date

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private enum class Screen { LOADING, GUIDE, BROWSER, SETTINGS, FULLSCREEN }

/**
 * One activity holds every screen, so a single video surface can move between full screen and the
 * small window without restarting the stream.
 */
@OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private val repo: Repo by lazy { (application as OpenGuideApp).repo }
    private val scope: CoroutineScope = MainScope()
    private val logos by lazy { Logos(this, repo.http) }
    private val listings by lazy { Listings(repo, scope) { onListingsLoaded() } }
    private val updater by lazy { Updater(this, repo.http) }
    private val prefs get() = repo.prefs

    private lateinit var root: FrameLayout
    private lateinit var shell: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var video: PlayerView
    private lateinit var videoStatus: TextView
    private lateinit var picker: PickerOverlay
    private lateinit var calibrator: OverscanOverlay
    private lateinit var cropper: CropOverlay
    private lateinit var pipFrame: View
    private lateinit var crtNumber: TextView
    private lateinit var breakLabel: TextView
    private lateinit var subtitles: SubtitleView
    private lateinit var hero: LinearLayout
    private lateinit var guide: GuideView
    private lateinit var guideScreen: LinearLayout
    private lateinit var browser: BrowserScreen
    private lateinit var settings: SettingsScreen
    private lateinit var clock: TextView
    private lateinit var status: TextView
    private lateinit var categoryChip: TextView
    private lateinit var tabGuide: TextView
    private lateinit var tabBrowser: TextView
    private lateinit var tabSettings: TextView

    private lateinit var heroChannel: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroTime: TextView
    private lateinit var heroProgress: ProgressBar
    private lateinit var heroDesc: TextView

    private lateinit var loadingView: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var retryButton: TextView

    private lateinit var banner: LinearLayout
    private lateinit var bannerLogo: ImageView
    private lateinit var bannerChannel: TextView
    private lateinit var bannerTitle: TextView
    private lateinit var bannerTime: TextView
    private lateinit var bannerNext: TextView
    private lateinit var bannerState: TextView
    private lateinit var bannerProgress: ProgressBar
    private lateinit var bannerHint: TextView

    private var player: TvPlayer? = null
    private var screen = Screen.LOADING
    private var guideCategory = CATEGORY_ALL
    /** The list Up/Down surf through while watching: whichever list the channel was picked from. */
    private var surfList: List<Channel> = emptyList()
    private var previousChannel: Channel? = null
    private var lastExitPress = 0L
    private var uiReady = false
    private var swallowKeyUp: Int? = null
    /** OK was pressed while watching and hasn't yet become a hold. */
    private var okArmed = false
    private var reopenSettings = false
    private var updateBusy = false
    /** An update waiting for permission to install apps, which is granted on another screen. */
    private var pendingApk: File? = null

    private val hideBanner = Runnable {
        banner.visibility = View.GONE
        crtNumber.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        reopenSettings = savedInstanceState?.getBoolean(KEY_SETTINGS) == true
        Look.use(prefs.crtMode, prefs.crtSqueezed, frameW.toFloat() / frameH)
        buildUi()
        setContentView(root)
        startLoading()
    }

    private fun buildUi() {
        root = FrameLayout(this)
        setUiBackground(fullscreen = false)

        shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (Look.crt) setPadding(dp(6), dp(6), dp(6), dp(4)) else setPadding(dp(27), dp(14), dp(27), dp(10))
            visibility = View.GONE
        }
        guide = GuideView(this, repo, logos, listings).apply {
            onFocusChanged = { ch, block ->
                updateHero(ch, block)
                updateStatusText()
            }
            onSelect = { ch, _ -> tuneFullscreen(ch, repo.filtered(guideCategory)) }
            onPreview = { ch ->
                surfList = repo.filtered(guideCategory)
                tune(ch)
            }
            onToggleFavorite = ::toggleFavorite
            onOptions = { ch -> showChannelOptions(ch, repo.filtered(guideCategory)) }
        }
        shell.addView(buildTopBar(), LinearLayout.LayoutParams(MATCH, dp(32)).apply { bottomMargin = dp(6) })
        content = FrameLayout(this)
        shell.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(shell, FrameLayout.LayoutParams(MATCH, MATCH))
        guideScreen = buildGuideScreen()
        browser = BrowserScreen(this, repo, logos, ::tuneFullscreen, ::toggleFavorite, ::showChannelOptions, ::pickRegion, ::pickLanguage)
        settings = SettingsScreen(this, repo, settingsActions)
        listOf(guideScreen, browser, settings).forEach {
            it.visibility = View.GONE
            content.addView(it, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        // The raised frame around the small video window in CRT mode.
        pipFrame = View(this).apply {
            background = roundRect(Palette.PANEL, dpf(6f))
            visibility = View.GONE
        }
        root.addView(pipFrame, FrameLayout.LayoutParams(0, 0))

        video = PlayerView(this).apply {
            useController = false
            // In CRT mode placeVideo() works out the shape itself, because the view can't know the tube's.
            resizeMode = if (Look.crt) AspectRatioFrameLayout.RESIZE_MODE_FILL else AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            setBackgroundColor(Color.BLACK)
            isFocusable = false
            visibility = View.GONE
        }
        root.addView(video, FrameLayout.LayoutParams(0, 0))
        videoStatus = label(size = 13f, color = Palette.MUTED, lines = 3).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            visibility = View.GONE
        }
        root.addView(videoStatus, FrameLayout.LayoutParams(0, 0))

        // Separate from the video view, which can be larger than the screen when cropping.
        video.subtitleView?.visibility = View.GONE
        subtitles = SubtitleView(this).apply {
            setFractionalTextSize(if (Look.crt) 0.07f else SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
            visibility = View.GONE
        }
        root.addView(subtitles, FrameLayout.LayoutParams(MATCH, MATCH))

        banner = buildBanner()
        root.addView(banner, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        // All CRT mode shows while watching: the number in the corner, like an old TV.
        crtNumber = label("", 34f, Color.parseColor("#6CFF6C"), bold = true).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }
        root.addView(crtNumber, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))

        // Says a long logo screen is Pluto's ad break, so it doesn't look like a stuck channel.
        breakLabel = label(
            "Pluto ad break · show will resume",
            if (Look.crt) 18f else 14f,
            if (Look.crt) Color.parseColor("#6CFF6C") else Palette.TEXT,
            bold = Look.crt,
        ).apply {
            background = roundRect(if (Look.crt) Color.BLACK else Palette.PANEL, dpf(6f))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            visibility = View.GONE
        }
        root.addView(breakLabel, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END))

        loadingView = buildLoading()
        root.addView(loadingView, FrameLayout.LayoutParams(MATCH, MATCH))

        picker = PickerOverlay(this)
        root.addView(picker, FrameLayout.LayoutParams(MATCH, MATCH))

        cropper = CropOverlay(this)
        root.addView(cropper, FrameLayout.LayoutParams(MATCH, MATCH))

        calibrator = OverscanOverlay(this)
        root.addView(calibrator, FrameLayout.LayoutParams(MATCH, MATCH))
        applyOverscan()
    }

    private val settingsActions = object : SettingsActions {
        override fun pickRegion() = this@MainActivity.pickRegion()
        override fun pickLanguage() = this@MainActivity.pickLanguage()
        override fun listsChanged() {
            surfList = emptyList()
            reloadGuide()
            updateStats()
        }
        override fun crtLookChanged() {
            // Colours, fonts and layout are all fixed when views are built, so build them again.
            reopenSettings = true
            recreate()
        }
        override fun crtPictureChanged() = placeVideo()
        override fun calibrateOverscan() {
            calibrator.show(prefs.crtOverscanX, prefs.crtOverscanY)
        }
        override fun checkForUpdate() = this@MainActivity.checkForUpdate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SETTINGS, reopenSettings || screen == Screen.SETTINGS)
    }

    private fun setUiBackground(fullscreen: Boolean) {
        when {
            // Bars around full-screen video have to be black, not the UI's colour.
            fullscreen -> root.setBackgroundColor(Color.BLACK)
            Look.crt -> root.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#0C2496"), Palette.BG),
            )
            else -> root.setBackgroundColor(Palette.BG)
        }
    }

    // ---- CRT geometry ----

    private val frameW get() = resources.displayMetrics.widthPixels
    private val frameH get() = resources.displayMetrics.heightPixels
    private val insetX get() = if (Look.crt) frameW * prefs.crtOverscanX / 100 else 0
    private val insetY get() = if (Look.crt) frameH * prefs.crtOverscanY / 100 else 0

    /** Keeps everything but full-screen video inside the part of the picture the tube shows. */
    private fun applyOverscan() {
        // Not the picker: it's centred anyway, and its dimming should reach the screen's edges.
        listOf<View>(shell, banner, loadingView, cropper, crtNumber, breakLabel, subtitles).forEach { v ->
            v.layoutParams = (v.layoutParams as FrameLayout.LayoutParams).apply {
                setMargins(insetX, insetY, insetX, insetY)
            }
        }
        if (Look.crt) {
            val (w, h) = crtGuideWindow()
            hero.background = roundRect(Palette.PANEL, dpf(6f))
            hero.setPadding(dp(10), dp(6), dp(10), dp(4))
            hero.layoutParams = (hero.layoutParams as LinearLayout.LayoutParams).apply {
                height = h
                rightMargin = w + dp(12)
                bottomMargin = dp(8)
            }
        }
    }

    /** The guide's video window, in the shape that comes out 4:3 on the tube. */
    private fun crtGuideWindow(): Pair<Int, Int> {
        val w = (frameW - 2 * insetX) * 30 / 100
        return w to if (prefs.crtSqueezed) w * frameH / frameW else w * 3 / 4
    }

    /** Where video goes on this screen, as left, top, width, height, or null if it isn't shown. */
    private fun crtFrame(): IntArray? = when (screen) {
        Screen.FULLSCREEN ->
            if (prefs.crtOverscanVideo) intArrayOf(insetX, insetY, frameW - 2 * insetX, frameH - 2 * insetY)
            else intArrayOf(0, 0, frameW, frameH)
        Screen.GUIDE -> crtGuideWindow().let { (w, h) ->
            intArrayOf(frameW - insetX - dp(10) - w, insetY + dp(6) + dp(32) + dp(6) + dp(3), w, h - dp(6))
        }
        Screen.BROWSER -> {
            val w = dp(180)
            val h = if (prefs.crtSqueezed) w * frameH / frameW else w * 3 / 4
            intArrayOf(insetX + dp(6), frameH - insetY - dp(4) - h, w, h)
        }
        else -> null
    }

    private fun placeVideoCrt() {
        val f = crtFrame()
        if (f == null) {
            video.visibility = View.GONE
            videoStatus.visibility = View.GONE
            pipFrame.visibility = View.GONE
            return
        }
        val (left, top, fw) = f
        val fh = f[3]
        val size = player?.exo?.videoSize
        val aspect = if (size != null && size.width > 0 && size.height > 0) {
            size.width * size.pixelWidthHeightRatio / size.height
        } else {
            16f / 9f
        }
        // What this frame's shape becomes on the tube, once a squeezing converter has had its way.
        val squeeze = if (prefs.crtSqueezed) (4f / 3f) / (frameW.toFloat() / frameH) else 1f
        val id = player?.channel?.id
        // A small window can't clip its video, so it never crops.
        val stretch = (id?.let(prefs::channelStretch) ?: prefs.crtStretch)
            .let { if (it == CrtStretch.FILL && screen != Screen.FULLSCREEN) CrtStretch.FIT else it }
        // Cropping is full screen only: a small window can't clip what would overflow it.
        val full = screen == Screen.FULLSCREEN
        val (cropX, cropY) = if (full && id != null) prefs.channelCrop(id) else 0 to 0
        val fitted = crtVideoSize(fw, fh, fw.toFloat() / fh * squeeze, croppedAspect(aspect, cropX, cropY), stretch)
        val (w, h) = uncroppedSize(fitted.first, fitted.second, cropX, cropY)
        video.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.START).apply {
            leftMargin = left + (fw - w) / 2
            topMargin = top + (fh - h) / 2
        }
        video.visibility = View.VISIBLE
        pipFrame.visibility = if (full) View.GONE else View.VISIBLE
        if (!full) {
            val border = dp(3)
            pipFrame.layoutParams = FrameLayout.LayoutParams(fw + 2 * border, fh + 2 * border, Gravity.TOP or Gravity.START).apply {
                leftMargin = left - border
                topMargin = top - border
            }
            video.setBackgroundColor(Color.BLACK)
        }
        videoStatus.layoutParams = FrameLayout.LayoutParams(fw, fh, Gravity.TOP or Gravity.START).apply {
            leftMargin = left
            topMargin = top
        }
        applyVideoStatus()
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (!Look.crt) bar.addView(label("OpenGuide", 16f, Palette.ACCENT, bold = true), LinearLayout.LayoutParams(WRAP, MATCH).apply { rightMargin = dp(14) })
        tabGuide = focusButton("Guide") { showGuide(focus = true) }.apply { id = View.generateViewId() }
        guide.nextFocusUpId = tabGuide.id
        tabBrowser = focusButton("Channels") { showBrowser(focus = true) }
        tabSettings = focusButton("Settings") { showSettings(focus = true) }
        categoryChip = focusButton("", 13f) { pickGuideCategory() }
        listOf(tabGuide, tabBrowser, tabSettings).forEach { bar.addView(it, LinearLayout.LayoutParams(WRAP, MATCH)) }
        bar.addView(categoryChip, LinearLayout.LayoutParams(WRAP, MATCH).apply { leftMargin = dp(14) })
        status = label(size = 12f, color = Palette.MUTED).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        bar.addView(status, LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = dp(10); rightMargin = dp(14) })
        clock = label(size = if (Look.crt) 16f else 14f, color = if (Look.crt) Palette.ACCENT else Palette.TEXT, bold = true)
        bar.addView(clock, LinearLayout.LayoutParams(WRAP, MATCH))
        return bar
    }

    private fun buildGuideScreen(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Leaves room for the video window, which floats above this at the right.
            setPadding(dp(2), dp(2), dp(272), 0)
        }
        heroChannel = label(size = 13f, color = Palette.ACCENT, bold = true)
        heroTitle = label(size = 24f, bold = true)
        heroTime = label(size = 13.5f, color = Palette.MUTED)
        heroProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        heroDesc = label(size = 13.5f, color = Palette.TEXT, lines = if (Look.crt) 2 else 3)
        hero.addView(heroChannel, LinearLayout.LayoutParams(MATCH, WRAP))
        hero.addView(heroTitle, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(5) })
        hero.addView(heroTime, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(5) })
        hero.addView(heroProgress, LinearLayout.LayoutParams(dp(220), dp(3)).apply { topMargin = dp(5) })
        hero.addView(heroDesc, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(7) })
        col.addView(hero, LinearLayout.LayoutParams(MATCH, dp(148)))
        col.addView(guide, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return col
    }

    private fun buildBanner(): LinearLayout {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Palette.BANNER)
            setPadding(dp(32), dp(14), dp(32), dp(20))
            visibility = View.GONE
        }
        bannerLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        b.addView(bannerLogo, LinearLayout.LayoutParams(dp(96), dp(64)).apply { rightMargin = dp(18) })
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bannerChannel = label(size = 14f, color = Palette.ACCENT, bold = true)
        bannerTitle = label(size = 22f, bold = true)
        bannerTime = label(size = 13.5f, color = Palette.MUTED)
        bannerProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        bannerNext = label(size = 13.5f, color = Palette.MUTED)
        bannerState = label(size = 13f, color = Palette.ACCENT)
        bannerHint = label("", 12f, Palette.FAINT)
        text.addView(bannerChannel, LinearLayout.LayoutParams(MATCH, WRAP))
        text.addView(bannerTitle, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        text.addView(bannerTime, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        text.addView(bannerProgress, LinearLayout.LayoutParams(dp(320), dp(3)).apply { topMargin = dp(4) })
        text.addView(bannerNext, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(5) })
        text.addView(bannerState, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        text.addView(bannerHint, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        b.addView(text, LinearLayout.LayoutParams(0, WRAP, 1f))
        return b
    }

    private fun buildLoading(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Palette.BG)
        addView(label("OpenGuide", 40f, Palette.ACCENT, bold = true).apply { gravity = Gravity.CENTER })
        loadingText = label(size = 16f, color = Palette.MUTED, lines = 4).apply { gravity = Gravity.CENTER }
        addView(loadingText, LinearLayout.LayoutParams(dp(640), WRAP).apply { topMargin = dp(16) })
        retryButton = focusButton("Try again", 16f) { startLoading() }.apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        addView(retryButton, LinearLayout.LayoutParams(dp(160), dp(40)).apply { topMargin = dp(18) })
    }

    private fun startLoading() {
        screen = Screen.LOADING
        loadingView.visibility = View.VISIBLE
        retryButton.visibility = View.GONE
        scope.launch {
            try {
                repo.start { msg -> scope.launch { loadingText.text = msg } }
                onDataReady()
            } catch (e: Exception) {
                loadingText.text = "Couldn't reach iptv-org. Check this device's internet connection.\n(${e.message})"
                retryButton.visibility = View.VISIBLE
                retryButton.requestFocus()
            }
        }
    }

    private fun onDataReady() {
        uiReady = true
        loadingView.visibility = View.GONE
        shell.visibility = View.VISIBLE
        reloadGuide()
        browser.refresh()
        settings.refresh()
        if (Look.crt && repo.crtList.value.isNotEmpty()) {
            guideCategory = CATEGORY_CRT
            reloadGuide()
        }
        showGuide(focus = true)
        guide.selectChannel(repo.prefs.lastChannel)
        if (reopenSettings) {
            reopenSettings = false
            showSettings(focus = false)
            settings.focusCrt()
        } else {
            repo.channel(repo.prefs.lastChannel)?.let { surfList = repo.filtered(guideCategory); tune(it) }
        }

        scope.launch {
            repo.lineup.drop(1).collect {
                reloadGuide()
                if (screen == Screen.BROWSER) browser.refresh()
            }
        }
        scope.launch {
            repo.visibility.drop(1).collect {
                reloadGuide()
                // Not the browser: it would lose your place, and it refreshes whenever it's opened.
                updateStats()
            }
        }
        scope.launch { repo.epgVersion.collect { onListingsChanged() } }
        scope.launch {
            repo.epgStatus.collect {
                updateStatusText()
                updateStats()
            }
        }
        scope.launch {
            while (true) {
                tick()
                delay(30_000)
            }
        }
    }

    private fun updateStatusText() {
        status.text = repo.epgStatus.value ?: when {
            screen == Screen.SETTINGS -> ""
            screen == Screen.GUIDE && !guide.isHome() -> "Back: your channel"
            else -> "Hold OK for options"
        }
    }

    private fun tick() {
        val now = Date()
        val day = if (Look.crt) "" else DateFormat.format("EEE MMM d  ", now).toString()
        clock.text = day + DateFormat.getTimeFormat(this).format(now)
        guide.tick()
        repo.refreshAiring()
        repo.refreshListings(force = false)
        repo.checkSources(force = false)
        if (screen == Screen.BROWSER) refreshBrowserAiring()
    }

    private fun onListingsChanged() {
        listings.clear()
        guide.refreshFocus()
        if (screen == Screen.BROWSER) refreshBrowserAiring()
        updateStats()
    }

    private fun onListingsLoaded() {
        guide.refreshFocus()
        if (banner.visibility == View.VISIBLE) player?.channel?.let(::updateBannerGuide)
    }

    private fun refreshBrowserAiring() {
        scope.launch {
            val map = withContext(Dispatchers.IO) { repo.db.airingNow(System.currentTimeMillis()) }
            browser.setAiring(map)
        }
    }

    private fun updateStats() {
        scope.launch {
            val count = withContext(Dispatchers.IO) { repo.db.programmeCount() }
            val c = repo.catalog.value ?: return@launch
            val lineup = repo.lineup.value
            val listed = lineup.count { it.id in repo.withListings }
            val dead = lineup.count { it.id in repo.deadChannels }
            val at = repo.prefs.epgFetchedAt
            settings.setStats(
                "${c.channels.size} channels worldwide, ${lineup.size} in this region. " +
                    "$listed of those have listings ($count programmes stored).\n" +
                    "$dead have no working source right now" + (if (prefs.hideDead) " and are hidden.\n" else ".\n") +
                    "Channels updated ${DateFormat.format("MMM d, h:mm a", c.fetchedAt)}. " +
                    "Listings updated " + if (at > 0) DateFormat.format("MMM d, h:mm a", at) else "never yet"
            )
        }
    }

    private fun reloadGuide() {
        val list = repo.filtered(guideCategory)
        guide.setChannels(list)
        categoryChip.text = (if (Look.crt) "" else "Showing: ") + categoryName(guideCategory) + "  ▾"
    }

    private fun categoryName(id: String): String = when (id) {
        CATEGORY_ALL -> "All channels"
        CATEGORY_FAVORITES -> "Favorites"
        CATEGORY_CRT -> "CRT list"
        else -> repo.catalog.value?.categories?.firstOrNull { it.id == id }?.name ?: id
    }

    private fun updateHero(ch: Channel?, block: Block?) {
        if (ch == null) {
            heroChannel.text = ""
            heroTitle.text = "No channels here yet"
            heroTime.text = when (guideCategory) {
                CATEGORY_FAVORITES -> "Hold OK on any channel to add it to your favorites."
                CATEGORY_CRT -> "Hold OK on any channel to add it to your CRT list."
                else -> ""
            }
            heroDesc.text = ""
            heroProgress.visibility = View.INVISIBLE
            return
        }
        heroChannel.text = "${repo.numbers[ch.id] ?: ""}  ${ch.name}"
        val p = block?.programme
        if (p == null) {
            heroTitle.text = ch.name
            heroTime.text = "No listings for this time"
            heroDesc.text = if (ch.id in repo.deadChannels) "This channel didn't respond when last tried." else "Press OK to watch."
            heroProgress.visibility = View.INVISIBLE
            return
        }
        val now = System.currentTimeMillis()
        heroTitle.text = p.title
        val parts = ArrayList<String>(3)
        parts += "${timeOf(this, p.start)} – ${timeOf(this, p.stop)}"
        if (now >= p.start && now < p.stop) parts += durationLeft(p.stop - now)
        p.category?.let { parts += it }
        p.episode?.let { parts += it }
        heroTime.text = parts.joinToString("  ·  ")
        heroDesc.text = listOfNotNull(p.subtitle, p.description).joinToString(" — ")
        if (now >= p.start && now < p.stop) {
            heroProgress.visibility = View.VISIBLE
            heroProgress.progress = (1000 * (now - p.start) / (p.stop - p.start)).toInt()
        } else {
            heroProgress.visibility = View.INVISIBLE
        }
    }

    // ---- Screens ----

    private fun show(target: Screen) {
        screen = target
        setUiBackground(target == Screen.FULLSCREEN)
        shell.visibility = if (target == Screen.FULLSCREEN) View.GONE else View.VISIBLE
        guideScreen.visibility = if (target == Screen.GUIDE) View.VISIBLE else View.GONE
        browser.visibility = if (target == Screen.BROWSER) View.VISIBLE else View.GONE
        settings.visibility = if (target == Screen.SETTINGS) View.VISIBLE else View.GONE
        categoryChip.visibility = if (target == Screen.GUIDE) View.VISIBLE else View.GONE
        updateStatusText()
        if (target != Screen.FULLSCREEN) {
            banner.removeCallbacks(hideBanner)
            hideBanner.run()
        }
        subtitles.visibility = if (target == Screen.FULLSCREEN) View.VISIBLE else View.GONE
        updateBreakLabel()
        placeVideo()
    }

    private fun placeVideo() {
        if (Look.crt) {
            placeVideoCrt()
            return
        }
        val params: FrameLayout.LayoutParams? = when (screen) {
            Screen.FULLSCREEN -> FrameLayout.LayoutParams(MATCH, MATCH)
            Screen.GUIDE -> FrameLayout.LayoutParams(dp(256), dp(144), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(52); rightMargin = dp(27)
            }
            Screen.BROWSER -> FrameLayout.LayoutParams(dp(200), dp(112), Gravity.BOTTOM or Gravity.START).apply {
                bottomMargin = dp(10); leftMargin = dp(27)
            }
            else -> null
        }
        if (params == null) {
            video.visibility = View.GONE
            videoStatus.visibility = View.GONE
            return
        }
        video.layoutParams = params
        video.visibility = View.VISIBLE
        videoStatus.layoutParams = FrameLayout.LayoutParams(params.width, params.height, params.gravity).apply {
            topMargin = params.topMargin; rightMargin = params.rightMargin
            bottomMargin = params.bottomMargin; leftMargin = params.leftMargin
        }
        applyVideoStatus()
    }

    private fun showGuide(focus: Boolean) {
        if (screen == Screen.SETTINGS) resumeIfStopped()
        show(Screen.GUIDE)
        if (focus) guide.requestFocus()
    }

    private fun showBrowser(focus: Boolean) {
        if (screen == Screen.SETTINGS) resumeIfStopped()
        show(Screen.BROWSER)
        browser.refresh()
        refreshBrowserAiring()
        if (focus) browser.focusFirst()
    }

    private fun showSettings(focus: Boolean) {
        show(Screen.SETTINGS)
        // No video window on this screen, so don't leave audio playing with nothing on show.
        player?.channel?.let { repo.prefs.lastChannel = it.id }
        player?.stop()
        settings.refresh()
        updateStats()
        if (focus) settings.focusFirst()
    }

    private fun resumeIfStopped() {
        if (player?.channel == null) repo.channel(repo.prefs.lastChannel)?.let(::tune)
    }

    private fun pickGuideCategory() {
        val c = repo.catalog.value ?: return
        val lineup = repo.filtered(CATEGORY_ALL) // what each row will actually show
        val counts = HashMap<String, Int>()
        lineup.forEach { ch -> ch.categories.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        val items = buildList {
            add(PickerItem(CATEGORY_ALL, "All channels"))
            add(PickerItem(CATEGORY_FAVORITES, "★ Favorites"))
            add(PickerItem(CATEGORY_CRT, "CRT list"))
            c.categories.filter { (counts[it.id] ?: 0) > 0 }.forEach { add(PickerItem(it.id, "${it.name} (${counts[it.id]})")) }
        }
        picker.show("Show in guide", items, guideCategory) {
            guideCategory = it.id
            reloadGuide()
            guide.requestFocus()
        }
    }

    private fun pickRegion() {
        val c = repo.catalog.value ?: return
        val counts = HashMap<String, Int>()
        c.channels.forEach { counts[it.country] = (counts[it.country] ?: 0) + 1 }
        val items = buildList {
            add(PickerItem(REGION_WORLD, "Worldwide (${c.channels.size}, fewer listings)"))
            c.countries.filter { (counts[it.code] ?: 0) > 0 }
                .forEach { add(PickerItem(it.code, "${it.flag} ${it.name} (${counts[it.code]})")) }
        }
        picker.show("Region", items, repo.region) {
            repo.setRegion(it.id)
            settings.refresh()
            browser.refresh()
        }
    }

    private fun pickLanguage() {
        val c = repo.catalog.value ?: return
        val counts = repo.languageCounts()
        val items = buildList {
            add(PickerItem(LANGUAGE_ALL, "All languages (${counts.values.sum()})"))
            c.languages.filter { (counts[it.code] ?: 0) > 0 }
                .sortedByDescending { counts[it.code] }
                .forEach { add(PickerItem(it.code, "${it.name} (${counts[it.code]})")) }
        }
        picker.show("Language", items, repo.language) {
            repo.setLanguage(it.id)
            surfList = emptyList()
            settings.refresh()
            browser.refresh()
        }
    }

    // ---- Updates ----

    private fun checkForUpdate() {
        if (updateBusy) return
        updateBusy = true
        settings.setUpdateText("Checking for updates…")
        scope.launch {
            try {
                val release = withContext(Dispatchers.IO) { updater.latestIfNewer() }
                if (release == null) {
                    settings.setUpdateText("Up to date (version ${com.openguide.tv.BuildConfig.VERSION_NAME}). Press to check again")
                } else {
                    settings.setUpdateText(null)
                    val items = listOf(PickerItem("yes", "Download and install"), PickerItem("no", "Not now"))
                    picker.show("Update to ${release.version}?", items, null) {
                        if (it.id == "yes") downloadUpdate(release)
                    }
                }
            } catch (e: IOException) {
                settings.setUpdateText("Couldn't check for updates. Press to try again")
                android.util.Log.w("Update", "Check failed", e)
            } finally {
                updateBusy = false
            }
        }
    }

    private fun downloadUpdate(release: Release) {
        if (updateBusy) return
        updateBusy = true
        settings.setUpdateText("Downloading ${release.version}…")
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    updater.download(release) { pct ->
                        scope.launch { settings.setUpdateText("Downloading ${release.version}: $pct%") }
                    }
                }
                settings.setUpdateText(null)
                installUpdate(file)
            } catch (e: IOException) {
                settings.setUpdateText("The download failed. Press to try again")
                android.util.Log.w("Update", "Download failed", e)
            } finally {
                updateBusy = false
            }
        }
    }

    private fun installUpdate(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingApk = file
            Toast.makeText(this, "Allow OpenGuide to install apps, then press Back", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) {
                pendingApk = null
                settings.setUpdateText("Allow OpenGuide to install unknown apps in system settings, then try again")
            }
            return
        }
        val uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(this, "$packageName.updates", file) else Uri.fromFile(file)
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            settings.setUpdateText("This device has no app installer available")
        }
    }

    // ---- Playback ----

    private fun ensurePlayer(): TvPlayer = player ?: TvPlayer(this, repo, ::onTuneState, ::updateBreakLabel).also {
        player = it
        video.player = it.exo
        it.exo.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (Look.crt) placeVideo()
            }

            override fun onCues(cueGroup: CueGroup) {
                subtitles.setCues(cueGroup.cues)
            }
        })
    }

    private fun tune(ch: Channel, stream: com.openguide.tv.data.StreamInfo? = null) {
        val p = ensurePlayer()
        val cur = p.channel
        if (cur != null && cur != ch) previousChannel = cur
        subtitles.setCues(emptyList())
        p.tune(ch, stream)
        guide.tunedChannelId = ch.id
        updateStatusText()
        // Each channel has its own picture settings.
        if (Look.crt) placeVideo()
    }

    private fun tuneFullscreen(ch: Channel, list: List<Channel>) {
        surfList = list
        val already = player?.channel == ch && player?.state !is TuneState.Failed
        show(Screen.FULLSCREEN)
        if (!already) tune(ch)
        showBanner()
    }

    private fun onTuneState(s: TuneState) {
        applyVideoStatus()
        if (screen == Screen.FULLSCREEN) {
            updateBannerState()
            if (s is TuneState.Connecting || s is TuneState.Failed) showBanner(persist = s is TuneState.Failed)
        }
        if (s is TuneState.Failed) guide.refreshFocus()
    }

    private fun updateBreakLabel() {
        breakLabel.visibility = if (screen == Screen.FULLSCREEN && player?.onBreak == true) View.VISIBLE else View.GONE
    }

    private fun applyVideoStatus() {
        val text = when (val s = player?.state) {
            is TuneState.Connecting -> if (s.total > 1) "Connecting…\nSource ${s.attempt} of ${s.total}" else "Connecting…"
            is TuneState.Failed -> "${s.channel.name} isn't responding right now."
            null, TuneState.Idle -> when {
                screen != Screen.GUIDE -> null
                Look.crt -> "OK: watch\nHold OK: options"
                else -> "Press OK on a channel to watch.\nHold OK to preview it here."
            }
            else -> null
        }
        videoStatus.text = text.orEmpty()
        val visible = text != null && video.visibility == View.VISIBLE
        videoStatus.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun showBanner(persist: Boolean = false) {
        val ch = player?.channel ?: return
        banner.removeCallbacks(hideBanner)
        if (Look.crt) {
            val failed = player?.state is TuneState.Failed
            crtNumber.text = (repo.numbers[ch.id]?.toString() ?: "--") + if (failed) "  NO SIGNAL" else ""
            crtNumber.visibility = View.VISIBLE
            if (!persist) banner.postDelayed(hideBanner, 4000)
            return
        }
        banner.visibility = View.VISIBLE
        bannerChannel.text = "${repo.numbers[ch.id] ?: ""}  ${ch.name}" + if (ch.id in repo.favorites.value) "  ★" else ""
        val channelKeys = if (prefs.dpadChangesChannel) "Up/Down" else "Channel +/-"
        bannerHint.text = "$channelKeys: change channel   ·   Left: last channel   ·   Right or hold OK: options   ·   Back: guide"
        bannerLogo.tag = ch.id
        bannerLogo.setImageBitmap(logos.get(ch.logo) {
            if (bannerLogo.tag == ch.id) bannerLogo.setImageBitmap(logos.get(ch.logo) {})
        })
        updateBannerGuide(ch)
        updateBannerState()
        if (!persist) banner.postDelayed(hideBanner, 6000)
    }

    private fun updateBannerGuide(ch: Channel) {
        val rows = listings.get(ch.id).orEmpty()
        val now = System.currentTimeMillis()
        val cur = rows.firstOrNull { now >= it.start && now < it.stop }
        val next = rows.firstOrNull { it.start >= now }
        if (cur != null) {
            bannerTitle.text = cur.title
            bannerTime.text = "${timeOf(this, cur.start)} – ${timeOf(this, cur.stop)}  ·  ${durationLeft(cur.stop - now)}"
            bannerProgress.visibility = View.VISIBLE
            bannerProgress.progress = (1000 * (now - cur.start) / (cur.stop - cur.start)).toInt()
        } else {
            bannerTitle.text = ch.name
            bannerTime.text = "No listings for this channel"
            bannerProgress.visibility = View.INVISIBLE
        }
        bannerNext.text = next?.let { "Next at ${timeOf(this, it.start)}: ${it.title}" }.orEmpty()
        bannerNext.visibility = if (next != null) View.VISIBLE else View.GONE
    }

    private fun updateBannerState() {
        val text = when (val s = player?.state) {
            is TuneState.Connecting -> if (s.total > 1) "Connecting… source ${s.attempt} of ${s.total}" else "Connecting…"
            is TuneState.Failed -> "This channel isn't responding right now. Change channel, or press Back for the guide." +
                if (s.channel.streams.any { it.geoBlocked }) " It may be blocked in your area." else ""
            is TuneState.Playing -> s.stream.quality.orEmpty()
            else -> ""
        }
        bannerState.text = text
        bannerState.setTextColor(if (player?.state is TuneState.Failed) Palette.BAD else Palette.ACCENT)
        bannerState.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun surf(delta: Int) {
        val list = surfList.ifEmpty { repo.filtered(guideCategory) }
        if (list.isEmpty()) return
        val i = list.indexOf(player?.channel)
        var at = if (i < 0) 0 else Math.floorMod(i + delta, list.size)
        // Skip channels known to be dead, unless that's all there is.
        if (prefs.hideDead) {
            var left = list.size
            while (left-- > 0 && list[at].id in repo.deadChannels) at = Math.floorMod(at + delta, list.size)
        }
        val next = list[at]
        tune(next)
        guide.selectChannel(next.id)
        showBanner()
    }

    private fun toggleFavorite(ch: Channel) {
        val added = repo.toggleFavorite(ch.id)
        Toast.makeText(this, if (added) "Added ${ch.name} to favorites" else "Removed ${ch.name} from favorites", Toast.LENGTH_SHORT).show()
        if (guideCategory == CATEGORY_FAVORITES) reloadGuide() else guide.refreshFocus()
    }

    private fun toggleCrtList(ch: Channel) {
        val added = repo.toggleCrt(ch.id)
        Toast.makeText(this, if (added) "Added ${ch.name} to the CRT list" else "Removed ${ch.name} from the CRT list", Toast.LENGTH_SHORT).show()
        if (guideCategory == CATEGORY_CRT) reloadGuide()
    }

    private fun crtListLabel(ch: Channel) = if (ch.id in repo.crtList.value) "Remove from CRT list" else "Add to CRT list"

    /** Everything a remote with only a D-pad, OK and Back can't reach through a dedicated button. */
    private fun showChannelOptions(ch: Channel, list: List<Channel>) {
        val isFav = ch.id in repo.favorites.value
        val items = listOf(
            PickerItem("watch", "Watch"),
            PickerItem("preview", "Preview in small window"),
            PickerItem("fav", if (isFav) "Remove from favorites" else "Add to favorites"),
            PickerItem("crt", crtListLabel(ch)),
        )
        picker.show(ch.name, items, null) { item ->
            when (item.id) {
                "watch" -> tuneFullscreen(ch, list)
                "preview" -> {
                    surfList = list
                    tune(ch)
                }
                "fav" -> {
                    toggleFavorite(ch)
                    if (screen == Screen.BROWSER) browser.refresh()
                }
                "crt" -> {
                    toggleCrtList(ch)
                    if (screen == Screen.BROWSER) browser.refresh()
                }
            }
        }
    }

    private fun showPlayerOptions() {
        val ch = player?.channel ?: return
        val isFav = ch.id in repo.favorites.value
        val previous = previousChannel
        val items = buildList {
            add(PickerItem("fav", if (isFav) "Remove from favorites" else "Add to favorites"))
            add(PickerItem("crt", crtListLabel(ch)))
            add(PickerItem("subs", "Subtitles: " + if (prefs.subtitles(ch.id)) "On" else "Off"))
            if (Look.crt) {
                add(PickerItem("picture", "Picture: " + prefs.channelStretch(ch.id).label))
                add(PickerItem("crop", "Crop black bars"))
            }
            if (previous != null) add(PickerItem("prev", "Last channel: ${previous.name}"))
            ch.streams.forEachIndexed { i, s -> add(PickerItem("src$i", "Source ${i + 1}: ${s.label}")) }
        }
        val playing = (player?.state as? TuneState.Playing)?.stream
        val current = playing?.let { "src${ch.streams.indexOf(it)}" }
        picker.show(ch.name, items, current, focus = items.first().id) { item ->
            when (item.id) {
                "fav" -> toggleFavorite(ch)
                "crt" -> toggleCrtList(ch)
                "subs" -> toggleSubtitles(ch)
                "picture" -> pickPicture(ch)
                "crop" -> prefs.channelCrop(ch.id).let { (x, y) -> cropper.show(x, y) }
                "prev" -> goToPreviousChannel()
                current -> {} // already playing this source, so don't restart it
                else -> tune(ch, ch.streams[item.id.removePrefix("src").toInt()])
            }
        }
    }

    private fun pickPicture(ch: Channel) {
        val items = CrtStretch.entries.map { PickerItem(it.name, it.label) }
        picker.show("Picture for ${ch.name}", items, prefs.channelStretch(ch.id).name) {
            prefs.setChannelStretch(ch.id, CrtStretch.valueOf(it.id))
            placeVideo()
        }
    }

    private fun toggleSubtitles(ch: Channel) {
        val p = player ?: return
        val on = !prefs.subtitles(ch.id)
        p.setSubtitles(on)
        val text = when {
            !on -> "Subtitles off for ${ch.name}"
            p.hasSubtitles -> "Subtitles on for ${ch.name}"
            else -> "Subtitles on, but this stream isn't offering any"
        }
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // ---- Remote ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // A press that opened or closed the picker must not act again when the key is released.
        if (event.action == KeyEvent.ACTION_UP && code == swallowKeyUp) {
            swallowKeyUp = null
            return true
        }
        if (calibrator.isShowing) {
            handleCalibrationKey(event)
            return true
        }
        if (cropper.isShowing) {
            handleCropKey(event)
            return true
        }
        if (picker.isShowing) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    swallowKeyUp = code
                    picker.hide()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (screen == Screen.FULLSCREEN && handleFullscreenKey(event)) return true
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) handleBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleCropKey(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        val id = player?.channel?.id
        var (x, y) = id?.let(prefs::channelCrop) ?: (0 to 0)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> x--
            KeyEvent.KEYCODE_DPAD_RIGHT -> x++
            KeyEvent.KEYCODE_DPAD_UP -> y++
            KeyEvent.KEYCODE_DPAD_DOWN -> y--
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                swallowKeyUp = event.keyCode
                cropper.visibility = View.GONE
                settings.refresh()
                return
            }
            else -> return
        }
        x = x.coerceIn(0, MAX_CROP)
        y = y.coerceIn(0, MAX_CROP)
        if (id != null) prefs.setChannelCrop(id, x, y)
        cropper.update(x, y)
        placeVideo()
    }

    private fun handleCalibrationKey(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        var x = prefs.crtOverscanX
        var y = prefs.crtOverscanY
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> x++
            KeyEvent.KEYCODE_DPAD_RIGHT -> x--
            KeyEvent.KEYCODE_DPAD_UP -> y--
            KeyEvent.KEYCODE_DPAD_DOWN -> y++
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                swallowKeyUp = event.keyCode
                calibrator.visibility = View.GONE
                applyOverscan()
                settings.refresh()
                return
            }
            else -> return
        }
        prefs.crtOverscanX = x.coerceIn(0, MAX_OVERSCAN)
        prefs.crtOverscanY = y.coerceIn(0, MAX_OVERSCAN)
        calibrator.update(prefs.crtOverscanX, prefs.crtOverscanY)
    }

    private fun goToPreviousChannel() {
        val previous = previousChannel
        if (previous != null) tune(previous)
        showBanner()
    }

    private fun handleFullscreenKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> if (first) surf(1)
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> if (first) surf(-1)
            KeyEvent.KEYCODE_DPAD_UP -> if (first) { if (prefs.dpadChangesChannel) surf(1) else showBanner() }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (first) { if (prefs.dpadChangesChannel) surf(-1) else showBanner() }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> when {
                first -> okArmed = true
                down && okArmed -> {
                    okArmed = false
                    showPlayerOptions()
                }
                event.action == KeyEvent.ACTION_UP && okArmed -> {
                    okArmed = false
                    if (banner.visibility == View.VISIBLE || crtNumber.visibility == View.VISIBLE) hideBanner.run() else showBanner()
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (first) goToPreviousChannel()
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (first) showPlayerOptions()
            KeyEvent.KEYCODE_MENU -> if (first) {
                swallowKeyUp = event.keyCode
                showPlayerOptions()
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (first) goToPreviousChannel()
            KeyEvent.KEYCODE_BACK -> if (event.action == KeyEvent.ACTION_UP) {
                guide.selectChannel(player?.channel?.id)
                showGuide(focus = true)
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (first) player?.exo?.let { it.playWhenReady = !it.playWhenReady }
            else -> return false
        }
        return true
    }

    private fun handleBack() {
        when {
            screen == Screen.LOADING -> finish()
            screen != Screen.GUIDE -> showGuide(focus = true)
            guide.hasFocus() && !guide.isHome() -> guide.goHome()
            guide.hasFocus() -> {
                tabGuide.requestFocus()
                confirmExit()
            }
            else -> confirmExit()
        }
    }

    private fun confirmExit() {
        val now = System.currentTimeMillis()
        if (now - lastExitPress < 2500) {
            finish()
        } else {
            lastExitPress = now
            Toast.makeText(this, "Press Back again to exit", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Lifecycle ----

    override fun onStart() {
        super.onStart()
        // The Fire TV takes the decoder back when we leave, so playback restarts on return.
        if (uiReady && player == null && screen != Screen.SETTINGS) {
            repo.channel(repo.prefs.lastChannel)?.let(::tune)
        }
    }

    override fun onResume() {
        super.onResume()
        // onStart wouldn't do: the TV's permission screen is a side panel, so this activity only pauses.
        pendingApk?.let { apk ->
            if (Build.VERSION.SDK_INT >= 26 && packageManager.canRequestPackageInstalls()) {
                pendingApk = null
                installUpdate(apk)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
        video.player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private companion object {
        const val KEY_SETTINGS = "settings"
        const val MAX_OVERSCAN = 20
    }
}
