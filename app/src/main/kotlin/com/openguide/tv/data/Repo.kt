package com.openguide.tv.data

import android.content.Context
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("openguide", Context.MODE_PRIVATE)

    var region: String?
        get() = p.getString("region", null)
        set(v) = p.edit().putString("region", v).apply()
    var favorites: Set<String>
        get() = p.getStringSet("favorites", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("favorites", v).apply()
    var lastChannel: String?
        get() = p.getString("last_channel", null)
        set(v) = p.edit().putString("last_channel", v).apply()
    var epgFetchedAt: Long
        get() = p.getLong("epg_at", 0)
        set(v) = p.edit().putLong("epg_at", v).apply()
    var onlyWithListings: Boolean
        get() = p.getBoolean("only_listings", false)
        set(v) = p.edit().putBoolean("only_listings", v).apply()
    var language: String?
        get() = p.getString("language", null)
        set(v) = p.edit().putString("language", v).apply()
    var hideDead: Boolean
        get() = p.getBoolean("hide_dead", true)
        set(v) = p.edit().putBoolean("hide_dead", v).apply()
    var deadChannels: Set<String>
        get() = p.getStringSet("dead_channels", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("dead_channels", v).apply()
    /** What the last source check covered and when, so it only runs again when that's out of date. */
    var sourceCheckKey: String?
        get() = p.getString("source_check_key", null)
        set(v) = p.edit().putString("source_check_key", v).apply()
    var sourceCheckAt: Long
        get() = p.getLong("source_check_at", 0)
        set(v) = p.edit().putLong("source_check_at", v).apply()
    /** For remotes with no channel buttons. */
    var dpadChangesChannel: Boolean
        get() = p.getBoolean("dpad_surf", false)
        set(v) = p.edit().putBoolean("dpad_surf", v).apply()

    var crtMode: Boolean
        get() = p.getBoolean("crt_mode", false)
        set(v) = p.edit().putBoolean("crt_mode", v).apply()
    var crtList: Set<String>
        get() = p.getStringSet("crt_list", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("crt_list", v).apply()
    var crtStretch: CrtStretch
        get() = CrtStretch.entries.firstOrNull { it.name == p.getString("crt_stretch", null) } ?: CrtStretch.FIT
        set(v) = p.edit().putString("crt_stretch", v.name).apply()
    /** The converter squeezes the app's 16:9 picture onto a 4:3 tube, as most cheap HDMI adapters do. */
    var crtSqueezed: Boolean
        get() = p.getBoolean("crt_squeezed", true)
        set(v) = p.edit().putBoolean("crt_squeezed", v).apply()
    var crtOverscanX: Int
        get() = p.getInt("crt_overscan_x", 5)
        set(v) = p.edit().putInt("crt_overscan_x", v).apply()
    var crtOverscanY: Int
        get() = p.getInt("crt_overscan_y", 5)
        set(v) = p.edit().putInt("crt_overscan_y", v).apply()
    var crtOverscanVideo: Boolean
        get() = p.getBoolean("crt_overscan_video", false)
        set(v) = p.edit().putBoolean("crt_overscan_video", v).apply()

    /** A channel uses the default picture mode until it's given its own. */
    fun channelStretch(channel: String): CrtStretch =
        CrtStretch.entries.firstOrNull { it.name == p.getString("picture_$channel", null) } ?: crtStretch
    fun setChannelStretch(channel: String, v: CrtStretch) = p.edit().putString("picture_$channel", v.name).apply()
    /** Percent cut from the sides and from top and bottom, since black bars vary from stream to stream. */
    fun channelCrop(channel: String): Pair<Int, Int> = parseCrop(p.getString("crop_$channel", null))
    fun setChannelCrop(channel: String, x: Int, y: Int) = p.edit().putString("crop_$channel", "$x,$y").apply()
    fun subtitles(channel: String): Boolean = p.getBoolean("subtitles_$channel", false)
    fun setSubtitles(channel: String, on: Boolean) = p.edit().putBoolean("subtitles_$channel", on).apply()

    fun goodStream(channel: String): String? = p.getString("good_$channel", null)
    fun setGoodStream(channel: String, url: String) = p.edit().putString("good_$channel", url).apply()
    fun etag(key: String): String? = p.getString("etag_$key", null)
    fun setEtag(key: String, v: String?) = p.edit().putString("etag_$key", v).apply()
    fun clearEtags() {
        val e = p.edit()
        p.all.keys.filter { it.startsWith("etag_") }.forEach(e::remove)
        e.apply()
    }
}

/** App-wide data: the catalog, the current region's lineup, and listings. */
class Repo(ctx: Context) {
    val prefs = Prefs(ctx)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .build()
    val db = EpgDb(ctx)

    private val store = CatalogStore(ctx, http)
    private val importer = EpgImporter(http, db, prefs, ctx.cacheDir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val epgLock = Mutex()

    private val _catalog = MutableStateFlow<Catalog?>(null)
    val catalog: StateFlow<Catalog?> = _catalog

    /** Text shown while listings update; null when idle. */
    private val _epgStatus = MutableStateFlow<String?>(null)
    val epgStatus: StateFlow<String?> = _epgStatus

    /** Bumped whenever stored listings change, so screens can redraw. */
    private val _epgVersion = MutableStateFlow(0)
    val epgVersion: StateFlow<Int> = _epgVersion

    private val _lineup = MutableStateFlow<List<Channel>>(emptyList())
    /** Every channel in the region: those with listings first, each group A to Z. */
    val lineup: StateFlow<List<Channel>> = _lineup

    private val _favorites = MutableStateFlow(prefs.favorites)
    val favorites: StateFlow<Set<String>> = _favorites

    private val _crtList = MutableStateFlow(prefs.crtList)
    val crtList: StateFlow<Set<String>> = _crtList

    /** Bumped when a channel starts or stops being hidden, without the lineup itself changing. */
    private val _visibility = MutableStateFlow(0)
    val visibility: StateFlow<Int> = _visibility

    @Volatile var withListings: Set<String> = emptySet()
        private set
    /** Channels with something on now. A channel with only later listings still reads "No listings". */
    @Volatile var airingNow: Set<String> = emptySet()
        private set
    @Volatile var numbers: Map<String, Int> = emptyMap()
        private set
    /** Channels none of whose sources work. Kept between launches so they stay hidden from the start. */
    @Volatile var deadChannels: Set<String> = prefs.deadChannels
        private set
    private val probeHttp: OkHttpClient = http.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
    private var sourceCheck: Job? = null

    val region: String get() = prefs.region ?: REGION_WORLD
    val language: String get() = prefs.language ?: LANGUAGE_ALL

    /** Loads the cache, or downloads if there is none. Throws if neither works. */
    suspend fun start(progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        progress("Loading channels…")
        val cached = store.loadCached()
        publish(cached ?: store.download(progress))
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt > CATALOG_MAX_AGE) {
            scope.launch {
                try {
                    publish(store.download { })
                } catch (e: Exception) {
                    Log.w(TAG, "Catalog refresh failed; keeping cached copy", e)
                }
            }
        }
        refreshListings(force = false)
        checkSources(force = false)
    }

    private fun publish(c: Catalog) {
        if (prefs.region == null) prefs.region = defaultRegion(c)
        if (prefs.language == null) prefs.language = defaultLanguage(c)
        withListings = db.channelsWithListings()
        airingNow = db.channelsAiring(System.currentTimeMillis())
        _catalog.value = c
        rebuildLineup()
    }

    private fun defaultLanguage(c: Catalog): String {
        val own = try { Locale.getDefault().isO3Language } catch (_: Exception) { "" }
        return if (c.languages.any { it.code == own }) own else LANGUAGE_ALL
    }

    private fun inRegion(c: Catalog, region: String) =
        c.channels.asSequence().filter { region == REGION_WORLD || it.country == region }

    /** How many of the region's channels each language would show. */
    fun languageCounts(): Map<String, Int> {
        val c = _catalog.value ?: return emptyMap()
        val out = HashMap<String, Int>()
        inRegion(c, region).forEach { ch -> ch.languages.firstOrNull()?.let { out[it] = (out[it] ?: 0) + 1 } }
        return out
    }

    fun setLanguage(code: String) {
        if (code == prefs.language) return
        prefs.language = code
        scope.launch {
            rebuildLineup()
            checkSources(force = false)
        }
    }

    private fun defaultRegion(c: Catalog): String {
        val code = Locale.getDefault().country.uppercase().let { if (it == "GB") "UK" else it }
        return if (c.channels.any { it.country == code }) code else "US"
    }

    /** One at a time, or a rebuild that started before a language change could finish after it and undo it. */
    @Synchronized
    private fun rebuildLineup() {
        val c = _catalog.value ?: return
        val lang = language
        val has = withListings
        val list = inRegion(c, region)
            .filter { matchesLanguage(it.languages, lang) }
            .sortedWith(compareBy<Channel> { it.id !in has }.thenBy { it.nameLower })
            .toList()
        numbers = list.withIndex().associate { (i, ch) -> ch.id to i + 1 }
        _lineup.value = list
    }

    fun filtered(category: String, query: String = ""): List<Channel> {
        val favs = _favorites.value
        val crt = _crtList.value
        // Lists someone built by hand keep what they chose; they can remove it if they want it gone.
        val picked = category == CATEGORY_FAVORITES || category == CATEGORY_CRT
        val only = prefs.onlyWithListings && !picked
        val hideDead = prefs.hideDead && !picked
        val airing = airingNow
        val dead = deadChannels
        val q = query.trim().lowercase()
        return _lineup.value.filter { ch ->
            (category == CATEGORY_ALL || (category == CATEGORY_FAVORITES && ch.id in favs) ||
                (category == CATEGORY_CRT && ch.id in crt) || category in ch.categories) &&
                (q.isEmpty() || q in ch.nameLower) &&
                (!only || ch.id in airing) &&
                (!hideDead || ch.id !in dead)
        }
    }

    /** Re-reads what's on now, since channels start and stop having listings as time passes. */
    fun refreshAiring() {
        scope.launch {
            val next = db.channelsAiring(System.currentTimeMillis())
            if (next != airingNow) {
                airingNow = next
                if (prefs.onlyWithListings) _visibility.value++
            }
        }
    }

    fun setDead(id: String, dead: Boolean) {
        if ((id in deadChannels) == dead) return
        deadChannels = if (dead) deadChannels + id else deadChannels - id
        prefs.deadChannels = deadChannels
        if (prefs.hideDead) _visibility.value++
    }

    /**
     * Tries each channel's streams in the background, because a dead channel is otherwise only found
     * by tuning to it. Results that could be down to this device being offline are thrown away.
     */
    fun checkSources(force: Boolean) {
        val key = "$region|$language"
        val age = System.currentTimeMillis() - prefs.sourceCheckAt
        if (!force && key == prefs.sourceCheckKey && age < SOURCE_CHECK_MAX_AGE) return
        sourceCheck?.cancel()
        sourceCheck = scope.launch {
            val channels = _lineup.value
            val gate = Semaphore(PROBE_CONCURRENCY)
            for (batch in channels.chunked(PROBE_BATCH)) {
                if (!isActive) return@launch
                val results = java.util.Collections.synchronizedMap(HashMap<String, Boolean>())
                batch.map { ch ->
                    launch { gate.withPermit { channelWorks(ch)?.let { results[ch.id] = it } } }
                }.joinAll()
                if (!online()) {
                    Log.i(TAG, "Went offline during the source check; stopping it")
                    return@launch
                }
                val dead = (deadChannels - results.filterValues { it }.keys) + results.filterValues { !it }.keys
                if (dead != deadChannels) {
                    deadChannels = dead
                    prefs.deadChannels = dead
                    if (prefs.hideDead) _visibility.value++
                }
            }
            prefs.sourceCheckKey = key
            prefs.sourceCheckAt = System.currentTimeMillis()
            Log.i(TAG, "Source check done: ${channels.count { it.id in deadChannels }} of ${channels.size} channels have no working source")
        }
    }

    /** True once a stream works, false if none do, null if that can't be told right now. */
    private fun channelWorks(ch: Channel): Boolean? {
        var unknown = false
        for (s in ch.streams) {
            when (probe(s)) {
                true -> return true
                null -> unknown = true
                false -> {}
            }
        }
        return if (unknown) null else false
    }

    private fun probe(s: StreamInfo): Boolean? = try {
        val req = Request.Builder().url(s.url)
        s.userAgent?.let { req.header("User-Agent", it) }
        s.referrer?.let { req.header("Referer", it) }
        probeHttp.newCall(req.build()).execute().use { res ->
            val head = if (res.isSuccessful) res.body!!.source().let { src ->
                src.request(16)
                src.buffer.copy().readUtf8(minOf(src.buffer.size, 16))
            } else ""
            probeVerdict(res.code, res.request.url.encodedPath, head)
        }
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {
        false // a malformed URL in the data
    }

    private fun online(): Boolean = try {
        probeHttp.newCall(Request.Builder().url("https://iptv-org.github.io/api/categories.json").head().build())
            .execute().use { it.isSuccessful }
    } catch (_: IOException) {
        false
    }

    fun channel(id: String?): Channel? = id?.let { i -> _catalog.value?.channels?.firstOrNull { it.id == i } }

    fun toggleFavorite(id: String): Boolean {
        val cur = _favorites.value
        val next = if (id in cur) cur - id else cur + id
        prefs.favorites = next
        _favorites.value = next
        return id in next
    }

    fun toggleCrt(id: String): Boolean {
        val cur = _crtList.value
        val next = if (id in cur) cur - id else cur + id
        prefs.crtList = next
        _crtList.value = next
        return id in next
    }

    fun setRegion(code: String) {
        if (code == prefs.region) return
        prefs.region = code
        prefs.epgFetchedAt = 0
        // Keeps the language unless this region has nothing in it.
        if (language != LANGUAGE_ALL && (languageCounts()[language] ?: 0) == 0) prefs.language = LANGUAGE_ALL
        scope.launch {
            epgLock.withLock {
                db.clear() // listings were matched for the old region's channels
                prefs.clearEtags()
                withListings = emptySet()
                airingNow = emptySet()
                _epgVersion.value++
            }
            rebuildLineup()
            refreshListings(force = true)
            checkSources(force = false)
        }
    }

    fun refreshAll() {
        scope.launch {
            try {
                _epgStatus.value = "Updating channels…"
                publish(store.download { _epgStatus.value = it })
            } catch (e: Exception) {
                Log.w(TAG, "Catalog refresh failed", e)
            }
            _epgStatus.value = null
            refreshListings(force = true)
            checkSources(force = true)
        }
    }

    fun refreshListings(force: Boolean) {
        val age = System.currentTimeMillis() - prefs.epgFetchedAt
        if (!force && age < EPG_MAX_AGE) return
        scope.launch {
            if (!epgLock.tryLock()) return@launch
            try { updateListings() } finally { epgLock.unlock() }
        }
    }

    private fun updateListings() {
        val now = System.currentTimeMillis()
        val r = region
        // The whole region, not the lineup, so switching language doesn't need new listings.
        val wanted = _catalog.value?.let { c -> inRegion(c, r).mapTo(HashSet()) { it.id } } ?: return
        val index = try {
            store.loadEpgIndex(wanted)
        } catch (e: Exception) {
            Log.w(TAG, "No EPG index", e)
            return
        }
        val sources = EpgSources.forRegion(r)
        var anySucceeded = false
        for ((i, s) in sources.withIndex()) {
            _epgStatus.value = "Updating listings ${i + 1}/${sources.size}: ${s.name}"
            try {
                importer.import(s, index, now)
                anySucceeded = true
                withListings = db.channelsWithListings()
                airingNow = db.channelsAiring(System.currentTimeMillis())
                rebuildLineup()
                _epgVersion.value++
                if (prefs.onlyWithListings) _visibility.value++
            } catch (e: Exception) {
                Log.w(TAG, "${s.key} failed", e) // other feeds can still fill the guide
            }
        }
        db.prune(now - 3_600_000L)
        withListings = db.channelsWithListings()
        if (anySucceeded) prefs.epgFetchedAt = now
        _epgStatus.value = null
        _epgVersion.value++
    }

    private companion object {
        const val TAG = "Repo"
        const val CATALOG_MAX_AGE = 24 * 3_600_000L
        const val EPG_MAX_AGE = 6 * 3_600_000L
        const val SOURCE_CHECK_MAX_AGE = 12 * 3_600_000L
        const val PROBE_CONCURRENCY = 6
        const val PROBE_BATCH = 60
    }
}

/** Per-channel listings in memory for the rows near the screen, loaded from SQLite off the main thread. */
class Listings(private val repo: Repo, private val scope: CoroutineScope, private val onLoaded: () -> Unit) {
    private val cache = LruCache<String, List<Programme>>(96)
    private val pending = HashSet<String>()

    fun get(channel: String): List<Programme>? {
        cache.get(channel)?.let { return it }
        if (pending.add(channel)) {
            scope.launch {
                val rows = withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    repo.db.forChannel(channel, now - 3_600_000L, now + 72 * 3_600_000L)
                }
                cache.put(channel, rows)
                pending.remove(channel)
                onLoaded()
            }
        }
        return null
    }

    fun clear() { cache.evictAll() }
}
