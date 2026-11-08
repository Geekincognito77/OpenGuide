package com.openguide.tv.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

class EpgDb(ctx: Context) : SQLiteOpenHelper(ctx, "epg.db", null, 1) {
    init { setWriteAheadLoggingEnabled(true) } // the guide keeps reading while listings update

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE programmes(channel TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, " +
                "title TEXT NOT NULL, sub TEXT, descr TEXT, cat TEXT, ep TEXT, source TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_ch_start ON programmes(channel, start)")
        db.execSQL("CREATE INDEX idx_source ON programmes(source)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {}

    fun forChannel(channel: String, from: Long, to: Long): List<Programme> {
        val out = ArrayList<Programme>()
        readableDatabase.rawQuery(
            "SELECT start,stop,title,sub,descr,cat,ep FROM programmes WHERE channel=? AND stop>? AND start<? ORDER BY start",
            arrayOf(channel, from.toString(), to.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out += Programme(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6))
            }
        }
        return out
    }

    fun airingNow(now: Long): Map<String, Programme> {
        val out = HashMap<String, Programme>()
        readableDatabase.rawQuery(
            "SELECT channel,start,stop,title FROM programmes WHERE start<=? AND stop>?",
            arrayOf(now.toString(), now.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out[c.getString(0)] = Programme(c.getLong(1), c.getLong(2), c.getString(3), null, null, null, null)
            }
        }
        return out
    }

    /** Each channel's next programme to start within [within] of now. */
    fun startingNext(now: Long, within: Long): Map<String, Programme> {
        val out = HashMap<String, Programme>()
        readableDatabase.rawQuery(
            "SELECT channel,start,stop,title FROM programmes WHERE start>? AND start<=? ORDER BY start DESC",
            arrayOf(now.toString(), (now + within).toString()),
        ).use { c ->
            // Latest first, so the earliest is what's left in the map.
            while (c.moveToNext()) {
                out[c.getString(0)] = Programme(c.getLong(1), c.getLong(2), c.getString(3), null, null, null, null)
            }
        }
        return out
    }

    fun channelsWithListings(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT DISTINCT channel FROM programmes", null).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    /** Channels with something on right now, which is what the guide's left edge shows. */
    fun channelsAiring(now: Long): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT channel FROM programmes WHERE start<=? AND stop>?",
            arrayOf(now.toString(), now.toString()),
        ).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    fun channelsFromOtherSources(source: String): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT DISTINCT channel FROM programmes WHERE source<>?", arrayOf(source)).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    fun programmeCount(): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM programmes", null).use { it.moveToFirst(); it.getLong(0) }

    fun clear() { writableDatabase.execSQL("DELETE FROM programmes") }

    fun prune(before: Long) {
        writableDatabase.execSQL("DELETE FROM programmes WHERE stop<?", arrayOf(before))
    }
}

/** @param interleaved the feed mixes channel entries in among programmes, so finding them all takes a full scan. */
data class EpgSource(val key: String, val name: String, val url: String, val interleaved: Boolean = false)

/**
 * iptv-org no longer publishes guide data, only mappings for building it yourself, so listings come
 * from public XMLTV feeds. Earlier sources win when two cover the same channel.
 */
object EpgSources {
    private val MJH = listOf("PlutoTV" to "Pluto TV", "SamsungTVPlus" to "Samsung TV Plus", "Plex" to "Plex", "Roku" to "Roku")

    private val EPGSHARE = ("AE1 AL1 AR1 AT1 AU1 BA1 BB1 BE2 BG1 BR1 BR2 CA2 CH1 CL1 CO1 CR1 CY1 CZ1 DE1 DK1 DO1 EC1 " +
        "EG1 ES1 FI1 FR1 GR1 HK1 HR1 HU1 ID1 IE1 IL1 IN1 IN2 IN4 IT1 JM1 JP1 JP2 KE1 KR1 KZ1 LT1 LU1 LV1 MN1 MT1 MX1 " +
        "MY1 NG1 NL1 NO1 NZ1 PA1 PE1 PH1 PH2 PK1 PL1 PT1 RO1 RO2 RS1 SA1 SA2 SE1 SG1 SK1 SV1 TH1 TR1 TR3 UA1 UK1 US2 " +
        "UY1 VN1 ZA1").split(' ')

    private val EPGPW = setOf("AU", "BR", "CA", "CN", "DE", "FR", "GB", "HK", "ID", "IN", "JP", "MY", "NZ", "PH", "RU", "SG", "TW", "US", "VN")

    fun forRegion(region: String): List<EpgSource> {
        val out = MJH.mapTo(ArrayList()) { (dir, name) -> EpgSource("mjh-$dir", name, "https://i.mjh.nz/$dir/all.xml.gz", interleaved = true) }
        if (region == REGION_WORLD) return out
        for (f in EPGSHARE) {
            if (f.startsWith(region)) {
                out += EpgSource("epgshare-$f", "EPGShare $f", "https://epgshare01.online/epgshare01/epg_ripper_$f.xml.gz")
            }
        }
        val iso = if (region == "UK") "GB" else region // iptv-org says UK where ISO says GB
        if (iso in EPGPW) out += EpgSource("epgpw-$iso", "EPG.PW $iso", "https://epg.pw/xmltv/epg_$iso.xml.gz")
        return out
    }
}

class EpgImporter(private val http: OkHttpClient, private val db: EpgDb, private val prefs: Prefs, private val tempDir: File) {

    /** @return true if the source's rows changed. */
    fun import(source: EpgSource, index: EpgIndex, now: Long): Boolean {
        val builder = Request.Builder().url(source.url)
        prefs.etag(source.key)?.let { builder.header("If-None-Match", it) }
        http.newCall(builder.build()).execute().use { res ->
            if (res.code == 304) return false
            if (!res.isSuccessful) {
                Log.w(TAG, "${source.key}: HTTP ${res.code}")
                return false
            }
            // Spooled to disk because matching needs two passes: channels first, then programmes.
            val spool = File(tempDir, "epg-${source.key}.tmp")
            try {
                spool.outputStream().use { res.body!!.byteStream().copyTo(it, 1 shl 16) }
                val xml = readChannels(source, spool)
                val claimed = db.channelsFromOtherSources(source.key)
                val targets = resolveXmlChannels(xml, index.direct, index.aliases, claimed)
                Log.i(TAG, "${source.key}: ${targets.values.sumOf { it.size }} channels matched")
                val rows = open(spool).use { parse(source, it, targets, now - 3_600_000L, now + WINDOW_MS) }
                Log.i(TAG, "${source.key}: stored $rows programmes")
            } finally {
                spool.delete()
            }
            prefs.setEtag(source.key, res.header("ETag"))
            return true
        }
    }

    private fun open(file: File): InputStream = unzip(BufferedInputStream(file.inputStream(), 1 shl 16))

    private fun newParser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
        // Feeds regularly contain stray entities that strict parsing would reject outright.
        try { setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) } catch (_: Exception) {}
        setInput(input, "UTF-8")
    }

    private fun readChannels(source: EpgSource, file: File): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        try {
            open(file).use { input ->
                val p = newParser(input)
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        if (p.name == "channel") {
                            val id = p.getAttributeValue(null, "id")
                            val names = readChannelNames(p)
                            if (id != null && id !in out) out[id] = names
                        } else if (p.name == "programme" && !source.interleaved) {
                            break
                        }
                    }
                    ev = p.next()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "${source.key}: channel scan stopped early: $e")
        }
        return out
    }

    private fun unzip(input: BufferedInputStream): InputStream {
        input.mark(2)
        val isGzip = input.read() == 0x1f && input.read() == 0x8b
        input.reset()
        return if (isGzip) GZIPInputStream(input, 1 shl 16) else input
    }

    private fun parse(source: EpgSource, input: InputStream, targets: Map<String, List<String>>, from: Long, to: Long): Int {
        val p = newParser(input)

        val w = db.writableDatabase
        val insert = w.compileStatement(
            "INSERT INTO programmes(channel,start,stop,title,sub,descr,cat,ep,source) VALUES(?,?,?,?,?,?,?,?,?)"
        )
        var stored = 0

        w.beginTransactionNonExclusive()
        try {
            w.execSQL("DELETE FROM programmes WHERE source=?", arrayOf(source.key))
            try {
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        if (p.name == "programme") {
                            val dest = targets[p.getAttributeValue(null, "channel")]
                            val start = parseXmltvTime(p.getAttributeValue(null, "start"))
                            val stop = parseXmltvTime(p.getAttributeValue(null, "stop"))
                            if (dest != null && start != null && stop != null && stop > from && start < to && stop > start) {
                                val prog = readProgramme(p)
                                if (prog != null) {
                                    for (ch in dest) {
                                        insert.clearBindings()
                                        insert.bindString(1, ch)
                                        insert.bindLong(2, start)
                                        insert.bindLong(3, stop)
                                        insert.bindString(4, prog.title)
                                        prog.subtitle?.let { insert.bindString(5, it) }
                                        prog.description?.let { insert.bindString(6, it) }
                                        prog.category?.let { insert.bindString(7, it) }
                                        prog.episode?.let { insert.bindString(8, it) }
                                        insert.bindString(9, source.key)
                                        insert.executeInsert()
                                        stored++
                                    }
                                }
                            }
                        }
                    }
                    ev = p.next()
                }
            } catch (e: Exception) {
                // A truncated download still yields usable rows, so keep what parsed.
                Log.w(TAG, "${source.key}: stopped early after $stored rows: $e")
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
            insert.close()
        }
        return stored
    }

    private fun readChannelNames(p: XmlPullParser): List<String> {
        val names = ArrayList<String>(1)
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && p.depth == depth)) break
            if (ev == XmlPullParser.START_TAG && p.name == "display-name") names += readText(p)
        }
        return names
    }

    private fun readProgramme(p: XmlPullParser): Programme? {
        var title: String? = null; var sub: String? = null; var desc: String? = null
        var cat: String? = null; var ep: String? = null
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && p.depth == depth)) break
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "title" -> if (title == null) title = readText(p)
                    "sub-title" -> if (sub == null) sub = readText(p)
                    "desc" -> if (desc == null) desc = readText(p).take(600)
                    "category" -> if (cat == null) cat = readText(p)
                    "episode-num" -> if (ep == null && p.getAttributeValue(null, "system") == "onscreen") ep = readText(p)
                }
            }
        }
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return null
        return Programme(0, 0, t, sub?.trim()?.ifEmpty { null }, desc?.trim()?.ifEmpty { null }, cat?.trim()?.ifEmpty { null }, ep?.trim()?.ifEmpty { null })
    }

    private fun readText(p: XmlPullParser): String {
        val depth = p.depth
        val sb = StringBuilder()
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && p.depth == depth)) break
            if (ev == XmlPullParser.TEXT) sb.append(p.text)
        }
        return sb.toString()
    }

    private companion object {
        const val TAG = "EpgImporter"
        const val WINDOW_MS = 48 * 3_600_000L
    }
}
