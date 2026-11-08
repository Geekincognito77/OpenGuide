package com.openguide.tv.ui

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.openguide.tv.data.Channel
import com.openguide.tv.data.GUIDE_CHANNEL_ID
import com.openguide.tv.data.Repo
import com.openguide.tv.data.StreamInfo
import com.openguide.tv.data.isBreakFiller
import com.openguide.tv.data.orderStreams
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.TimeUnit

sealed class TuneState {
    object Idle : TuneState()
    data class Connecting(val channel: Channel, val attempt: Int, val total: Int) : TuneState()
    data class Playing(val channel: Channel, val stream: StreamInfo) : TuneState()
    data class Failed(val channel: Channel) : TuneState()
}

/**
 * Wraps ExoPlayer with what public IPTV needs: many streams are dead or stall, so each channel's
 * streams are tried in turn until one plays, without the viewer having to do anything.
 *
 * A stream that has been playing and then stalls is restarted rather than abandoned. Stitched streams
 * like Pluto's keep only about 25 seconds of live window and hand out per-session URLs, so a short
 * hiccup can leave the player stuck on segments that no longer exist, while reopening the stream
 * starts a fresh session. Moving on would also fail a channel outright if it has only one source.
 */
@OptIn(UnstableApi::class)
class TvPlayer(
    ctx: Context,
    private val repo: Repo,
    private val onState: (TuneState) -> Unit,
    private val onBreakChanged: () -> Unit,
) {

    val exo: ExoPlayer = ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
        .setLoadControl(
            DefaultLoadControl.Builder()
                // Short start and restart thresholds suit channel changing and a short live window.
                .setBufferDurationsMs(15_000, 30_000, 1_500, 3_000)
                .build()
        )
        .build().apply {
        playWhenReady = true
        setHandleAudioBecomingNoisy(true)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var queue: List<StreamInfo> = emptyList()
    private var attempt = 0
    /** This channel has played since it was tuned, so a failure now doesn't mean it's dead. */
    private var everPlayed = false
    /** The current stream has played, so a stall is worth restarting it for. */
    private var streamPlayed = false
    private var restarts = 0
    private var startedAt = 0L
    private var lastFrames = -1
    private var lastPosition = -1L
    private var lastProgressAt = 0L
    private var subtitlesOn = false
    private var triedSubtitleTrack = false
    private val segmentIsFiller = TreeMap<Long, Boolean>()
    /** Hangs fail in seconds, so ExoPlayer's own retries happen before the watchdog steps in. */
    private val streamHttp: OkHttpClient = repo.http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    var channel: Channel? = null
        private set
    var state: TuneState = TuneState.Idle
        private set(v) { field = v; onState(v) }

    /** The picture is Pluto's logo clip, which can run for minutes and looks like a stuck stream. */
    var onBreak = false
        private set(v) { if (field != v) { field = v; onBreakChanged() } }

    private val watchdog = Runnable {
        Log.i(TAG, "Timed out on ${channel?.id}, attempt $attempt, played=$streamPlayed")
        restartOrNext()
    }

    /** A channel that was playing is probably fine and the connection isn't, so keep trying. */
    private val retryAfterFailure = Runnable {
        Log.i(TAG, "Trying ${channel?.id} again")
        attempt = -1
        next()
    }

    /** Catches a player that claims to be playing while its picture has stopped. */
    private val freezeCheck = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (exo.playbackState == Player.STATE_READY && exo.playWhenReady) {
                onBreak = showingBreakFiller()
                val frames = exo.videoDecoderCounters?.let {
                    it.ensureUpdated()
                    it.renderedOutputBufferCount
                } ?: 0
                val position = exo.currentPosition
                val moving = if (exo.videoFormat != null) frames != lastFrames else position != lastPosition
                lastFrames = frames
                lastPosition = position
                when {
                    moving -> {
                        // Only a long healthy run clears the count, or a flapping stream would never fail over.
                        if (now - startedAt > HEALTHY_AFTER) restarts = 0
                        lastProgressAt = now
                    }
                    now - lastProgressAt > FROZEN_TIMEOUT -> {
                        Log.i(TAG, "Picture frozen on ${channel?.id}")
                        lastProgressAt = now
                        restartOrNext()
                    }
                }
            } else {
                lastProgressAt = now
            }
            handler.postDelayed(this, FREEZE_POLL)
        }
    }

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                val ch = channel ?: return
                when (s) {
                    Player.STATE_READY -> {
                        handler.removeCallbacks(watchdog)
                        everPlayed = true
                        streamPlayed = true
                        val stream = queue.getOrNull(attempt) ?: return
                        repo.prefs.setGoodStream(ch.id, stream.url)
                        repo.setDead(ch.id, false)
                        state = TuneState.Playing(ch, stream)
                    }
                    Player.STATE_BUFFERING -> arm(if (streamPlayed) REBUFFER_TIMEOUT else STALL_TIMEOUT)
                    Player.STATE_ENDED -> next() // a live stream ending means the source dropped
                    else -> {}
                }
            }

            override fun onTracksChanged(tracks: Tracks) = chooseSubtitleTrack(tracks)

            override fun onPlayerError(error: PlaybackException) {
                Log.i(TAG, "Stream error on ${channel?.id}: ${error.errorCodeName}")
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exo.seekToDefaultPosition()
                    exo.prepare()
                } else {
                    restartOrNext()
                }
            }
        })
        handler.postDelayed(freezeCheck, FREEZE_POLL)
    }

    /** Whether the current stream offers any subtitles. Only known once it has started. */
    val hasSubtitles: Boolean
        get() = exo.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSupported }

    /** Saves the choice for this channel and applies it. */
    fun setSubtitles(on: Boolean) {
        val ch = channel ?: return
        repo.prefs.setSubtitles(ch.id, on)
        applySubtitles()
        chooseSubtitleTrack(exo.currentTracks)
    }

    private fun applySubtitles() {
        subtitlesOn = channel?.let { repo.prefs.subtitles(it.id) } ?: false
        triedSubtitleTrack = false
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesOn)
            .setPreferredTextLanguage(Locale.getDefault().language)
            .build()
    }

    /**
     * The player only picks subtitles on its own if their language is the device's, and many streams
     * label theirs vaguely or not at all, so when none got picked this takes what there is.
     */
    private fun chooseSubtitleTrack(tracks: Tracks) {
        if (!subtitlesOn || triedSubtitleTrack) return
        val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
        if (text.isEmpty() || text.any { it.isSelected }) return
        triedSubtitleTrack = true
        // A named track is real subtitles; an unnamed one may be built-in captions that are often empty.
        val own = Locale.getDefault().language
        val group = text.firstOrNull { it.getTrackFormat(0).language?.startsWith(own) == true }
            ?: text.firstOrNull { it.getTrackFormat(0).label != null }
            ?: text.first()
        val track = (0 until group.length).first { group.isTrackSupported(it) }
        Log.i(TAG, "Subtitles on ${channel?.id}: choosing ${group.getTrackFormat(track).label}")
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
            .build()
    }

    /**
     * Stitched playlists list only about 25 seconds and keep sliding, so what's on screen may already
     * have dropped off the list. Segments are remembered by absolute time so they can still be looked up.
     */
    private fun showingBreakFiller(): Boolean {
        val playlist = (exo.currentManifest as? HlsManifest)?.mediaPlaylist ?: return false
        for (segment in playlist.segments) {
            segmentIsFiller[playlist.startTimeUs + segment.relativeStartTimeUs] = isBreakFiller(segment.url)
        }
        val position = playlist.startTimeUs + exo.currentPosition * 1000
        segmentIsFiller.headMap(position - SEGMENT_MEMORY_US).clear()
        return segmentIsFiller.floorEntry(position)?.value ?: false
    }

    private fun restartOrNext() {
        val ch = channel ?: return
        val s = queue.getOrNull(attempt)
        if (s != null && streamPlayed && restarts < MAX_RESTARTS) {
            restarts++
            Log.i(TAG, "Restarting ${ch.id}, try $restarts of $MAX_RESTARTS")
            state = TuneState.Connecting(ch, attempt + 1, queue.size)
            play(s)
            arm(CONNECT_TIMEOUT)
        } else {
            next()
        }
    }

    private fun arm(ms: Long) {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, ms)
    }

    /** @param stream a specific stream to start from, when the viewer chose one. */
    fun tune(ch: Channel, stream: StreamInfo? = null) {
        handler.removeCallbacks(retryAfterFailure)
        channel = ch
        if (ch.id == GUIDE_CHANNEL_ID) {
            playNothing(ch)
            return
        }
        val ordered = orderStreams(ch.streams, repo.prefs.goodStream(ch.id))
        queue = if (stream != null) listOf(stream) + (ordered - stream) else ordered
        attempt = -1
        everPlayed = false
        repo.prefs.lastChannel = ch.id
        next()
    }

    /** The guide channel has no stream, so it's "on" with the player idle. Not a failure, so never marked dead. */
    private fun playNothing(ch: Channel) {
        handler.removeCallbacks(watchdog)
        onBreak = false
        queue = emptyList()
        attempt = -1
        repo.prefs.lastChannel = ch.id
        exo.stop()
        exo.clearMediaItems()
        state = TuneState.Idle
    }

    private fun next() {
        val ch = channel ?: return
        onBreak = false
        attempt++
        streamPlayed = false
        restarts = 0
        val s = queue.getOrNull(attempt)
        if (s == null) {
            handler.removeCallbacks(watchdog)
            exo.stop()
            exo.clearMediaItems()
            if (everPlayed) handler.postDelayed(retryAfterFailure, RETRY_DELAY) else repo.setDead(ch.id, true)
            state = TuneState.Failed(ch)
            return
        }
        state = TuneState.Connecting(ch, attempt + 1, queue.size)
        play(s)
        arm(CONNECT_TIMEOUT)
    }

    private fun play(s: StreamInfo) {
        onBreak = false
        segmentIsFiller.clear()
        startedAt = SystemClock.uptimeMillis()
        lastProgressAt = startedAt
        applySubtitles()
        val http = OkHttpDataSource.Factory(streamHttp)
        s.userAgent?.let { http.setUserAgent(it) }
        s.referrer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it, "Origin" to originOf(it))) }
        val item = MediaItem.Builder().setUri(s.url)
        mimeFor(s.url)?.let { item.setMimeType(it) }
        exo.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(item.build()), C.TIME_UNSET)
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun originOf(url: String): String =
        Uri.parse(url).let { "${it.scheme}://${it.authority}" }

    private fun mimeFor(url: String): String? {
        val path = Uri.parse(url).path.orEmpty().lowercase()
        return when {
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".ts") || path.endsWith(".mp4") || path.endsWith(".mkv") -> null
            // iptv-org also lists HLS at URLs like /index.php that ExoPlayer can't identify on its own.
            else -> MimeTypes.APPLICATION_M3U8
        }
    }

    fun stop() {
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(retryAfterFailure)
        channel = null
        onBreak = false
        exo.stop()
        exo.clearMediaItems()
        state = TuneState.Idle
    }

    fun release() {
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(freezeCheck)
        handler.removeCallbacks(retryAfterFailure)
        channel = null
        exo.release()
    }

    private companion object {
        const val TAG = "TvPlayer"
        const val CONNECT_TIMEOUT = 15_000L
        const val STALL_TIMEOUT = 25_000L
        const val REBUFFER_TIMEOUT = 10_000L
        const val FROZEN_TIMEOUT = 10_000L
        const val FREEZE_POLL = 2_000L
        const val HEALTHY_AFTER = 60_000L
        const val MAX_RESTARTS = 3
        const val RETRY_DELAY = 15_000L
        const val SEGMENT_MEMORY_US = 120_000_000L
    }
}
