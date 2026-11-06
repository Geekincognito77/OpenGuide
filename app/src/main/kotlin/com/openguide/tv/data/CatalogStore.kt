package com.openguide.tv.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

/** Downloads the iptv-org API, keeps channels that have a stream, and caches them on disk. */
class CatalogStore(private val ctx: Context, private val http: OkHttpClient) {

    private val catalogFile get() = File(ctx.filesDir, "catalog.bin")
    private val epgIndexFile get() = File(ctx.filesDir, "epgindex.bin")

    private inline fun fetch(name: String, read: (JsonReader) -> Unit) {
        val req = Request.Builder().url("$API/$name.json").build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("$name.json returned HTTP ${res.code}")
            JsonReader(InputStreamReader(res.body!!.byteStream(), Charsets.UTF_8)).use(read)
        }
    }

    private inline fun JsonReader.objects(row: (String) -> Unit, end: () -> Unit) {
        beginArray()
        while (hasNext()) {
            beginObject()
            while (hasNext()) row(nextName())
            endObject()
            end()
        }
        endArray()
    }

    private fun JsonReader.str(): String? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()

    private fun JsonReader.strings(): List<String> {
        if (peek() == JsonToken.NULL) { nextNull(); return emptyList() }
        val out = ArrayList<String>(2)
        beginArray()
        while (hasNext()) str()?.let { out += it }
        endArray()
        return out
    }

    fun download(progress: (String) -> Unit): Catalog {
        progress("Fetching stream list…")
        val streams = HashMap<String, MutableList<StreamInfo>>(16384)
        val direct = HashMap<String, MutableSet<String>>()
        val usedFeeds = HashMap<String, MutableSet<String?>>(16384)
        val streamTitles = HashMap<String, MutableSet<String>>(16384)
        fetch("streams") { r ->
            var ch: String? = null; var url: String? = null; var q: String? = null
            var feed: String? = null; var title: String? = null
            var labels = emptyList<String>(); var ua: String? = null; var ref: String? = null
            r.objects({ k ->
                when (k) {
                    "channel" -> ch = r.str()
                    "feed" -> feed = r.str()
                    "title" -> title = r.str()
                    "url" -> url = r.str()
                    "quality" -> q = r.str()
                    "labels" -> labels = r.strings()
                    "user_agent" -> ua = r.str()
                    "referrer" -> ref = r.str()
                    else -> r.skipValue()
                }
            }, {
                val c = ch; val u = url
                if (c != null && u != null) {
                    streams.getOrPut(c) { ArrayList(2) } +=
                        StreamInfo(u, q, "Geo-blocked" in labels, "Not 24/7" in labels, ua, ref)
                    usedFeeds.getOrPut(c) { HashSet(2) } += feed
                    title?.let { streamTitles.getOrPut(c) { HashSet(2) } += it }
                    // Pluto stream URLs embed the same ID the Pluto guide feed uses.
                    PLUTO_ID.find(u)?.let { direct.getOrPut(c) { HashSet() } += it.groupValues[1] }
                }
                ch = null; url = null; q = null; labels = emptyList(); ua = null; ref = null
                feed = null; title = null
            })
        }

        progress("Fetching blocklist…")
        val blocked = HashSet<String>()
        fetch("blocklist") { r ->
            var ch: String? = null
            r.objects({ k -> if (k == "channel") ch = r.str() else r.skipValue() },
                { ch?.let { blocked += it }; ch = null })
        }

        progress("Fetching channels…")
        class Raw(val name: String, val alt: List<String>, val country: String, val cats: List<String>)
        val raw = HashMap<String, Raw>(streams.size)
        fetch("channels") { r ->
            var id: String? = null; var name: String? = null; var alt = emptyList<String>()
            var country: String? = null; var cats = emptyList<String>(); var nsfw = false
            r.objects({ k ->
                when (k) {
                    "id" -> id = r.str()
                    "name" -> name = r.str()
                    "alt_names" -> alt = r.strings()
                    "country" -> country = r.str()
                    "categories" -> cats = r.strings()
                    "is_nsfw" -> nsfw = r.nextBoolean()
                    else -> r.skipValue()
                }
            }, {
                val i = id; val n = name; val c = country
                if (i != null && n != null && c != null && !nsfw && i !in blocked && i in streams) {
                    raw[i] = Raw(n, alt, c, cats)
                }
                id = null; name = null; alt = emptyList(); country = null; cats = emptyList(); nsfw = false
            })
        }

        progress("Fetching languages…")
        val feeds = HashMap<String, MutableList<FeedLanguages>>(raw.size)
        fetch("feeds") { r ->
            var ch: String? = null; var id: String? = null; var main = false; var langs = emptyList<String>()
            r.objects({ k ->
                when (k) {
                    "channel" -> ch = r.str()
                    "id" -> id = r.str()
                    "is_main" -> main = r.nextBoolean()
                    "languages" -> langs = r.strings()
                    else -> r.skipValue()
                }
            }, {
                val c = ch; val i = id
                if (c != null && i != null && c in raw) feeds.getOrPut(c) { ArrayList(1) } += FeedLanguages(i, main, langs)
                ch = null; id = null; main = false; langs = emptyList()
            })
        }
        val languageNames = HashMap<String, String>()
        fetch("languages") { r ->
            var code: String? = null; var name: String? = null
            r.objects({ k ->
                when (k) { "code" -> code = r.str(); "name" -> name = r.str(); else -> r.skipValue() }
            }, {
                val c = code; val n = name
                if (c != null && n != null) languageNames[c] = n
                code = null; name = null
            })
        }

        progress("Fetching logos…")
        val logos = HashMap<String, Pair<Int, String>>(raw.size)
        fetch("logos") { r ->
            var ch: String? = null; var feed: String? = null; var fmt: String? = null; var url: String? = null
            r.objects({ k ->
                when (k) {
                    "channel" -> ch = r.str()
                    "feed" -> feed = r.str()
                    "format" -> fmt = r.str()
                    "url" -> url = r.str()
                    else -> r.skipValue()
                }
            }, {
                val c = ch; val u = url
                // Android can't decode SVG, and many iptv-org logos offer a PNG alongside one.
                if (c != null && u != null && c in raw && !fmt.equals("SVG", true) && !u.endsWith(".svg", true)) {
                    val rank = if (feed == null) 0 else 1
                    val cur = logos[c]
                    if (cur == null || rank < cur.first) logos[c] = rank to u
                }
                ch = null; feed = null; fmt = null; url = null
            })
        }

        progress("Fetching guide mappings…")
        val aliases = HashMap<String, MutableSet<String>>(raw.size)
        fetch("guides") { r ->
            var ch: String? = null; var site: String? = null; var sid: String? = null; var sname: String? = null
            r.objects({ k ->
                when (k) {
                    "channel" -> ch = r.str()
                    "site" -> site = r.str()
                    "site_id" -> sid = r.str()
                    "site_name" -> sname = r.str()
                    else -> r.skipValue()
                }
            }, {
                val c = ch
                if (c != null && c in raw) {
                    sname?.let { aliases.getOrPut(c) { HashSet() } += it }
                    val s = sid
                    if (s != null && site in DIRECT_ID_SITES) {
                        direct.getOrPut(c) { HashSet() } += s.substringAfterLast('#')
                    }
                }
                ch = null; site = null; sid = null; sname = null
            })
        }

        progress("Fetching categories…")
        val categories = ArrayList<Category>()
        fetch("categories") { r ->
            var id: String? = null; var name: String? = null
            r.objects({ k ->
                when (k) { "id" -> id = r.str(); "name" -> name = r.str(); else -> r.skipValue() }
            }, {
                val i = id; val n = name
                if (i != null && n != null) categories += Category(i, n)
                id = null; name = null
            })
        }

        progress("Fetching countries…")
        val countries = ArrayList<Country>()
        fetch("countries") { r ->
            var code: String? = null; var name: String? = null; var flag: String? = null
            r.objects({ k ->
                when (k) {
                    "code" -> code = r.str(); "name" -> name = r.str(); "flag" -> flag = r.str()
                    else -> r.skipValue()
                }
            }, {
                val c = code; val n = name
                if (c != null && n != null) countries += Country(c, n, flag.orEmpty())
                code = null; name = null; flag = null
            })
        }

        val channels = raw.map { (id, c) ->
            val tagged = taggedLanguages(feeds[id].orEmpty(), usedFeeds[id].orEmpty())
            val names = listOf(c.name) + streamTitles[id].orEmpty()
            Channel(id, c.name, c.country, c.cats, logos[id]?.second, streams[id].orEmpty(), correctLanguages(names, tagged))
        }.sortedBy { it.nameLower }
        if (channels.isEmpty()) throw IOException("iptv-org returned no playable channels")

        val catalog = Catalog(
            channels,
            countries.sortedBy { it.name },
            categories.sortedBy { it.name },
            channels.flatMap { it.languages }.distinct()
                .mapNotNull { code -> languageNames[code]?.let { Language(code, it) } }
                .sortedBy { it.name },
            System.currentTimeMillis(),
        )
        progress("Saving…")
        saveCatalog(catalog)
        saveEpgIndex(channels.map { ch ->
            val names = aliases[ch.id].orEmpty() + raw.getValue(ch.id).alt + ch.name
            Triple(ch.id, names.map(::normalizeName).filter { it.length >= 2 }.toSet(), direct[ch.id].orEmpty())
        })
        return catalog
    }

    private fun saveCatalog(c: Catalog) {
        val tmp = File(catalogFile.path + ".tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { o ->
            o.writeInt(FORMAT)
            o.writeLong(c.fetchedAt)
            o.writeInt(c.countries.size)
            c.countries.forEach { o.writeUTF(it.code); o.writeUTF(it.name); o.writeUTF(it.flag) }
            o.writeInt(c.categories.size)
            c.categories.forEach { o.writeUTF(it.id); o.writeUTF(it.name) }
            o.writeInt(c.languages.size)
            c.languages.forEach { o.writeUTF(it.code); o.writeUTF(it.name) }
            o.writeInt(c.channels.size)
            for (ch in c.channels) {
                o.writeUTF(ch.id); o.writeUTF(ch.name); o.writeUTF(ch.country)
                o.writeUTF(ch.categories.joinToString(","))
                o.writeUTF(ch.logo.orEmpty())
                o.writeUTF(ch.languages.joinToString(","))
                o.writeInt(ch.streams.size)
                for (s in ch.streams) {
                    o.writeUTF(s.url); o.writeUTF(s.quality.orEmpty())
                    o.writeBoolean(s.geoBlocked); o.writeBoolean(s.notAlwaysOn)
                    o.writeUTF(s.userAgent.orEmpty()); o.writeUTF(s.referrer.orEmpty())
                }
            }
        }
        tmp.renameTo(catalogFile)
    }

    /** Null when there is no cache or it was written by an older version of the format. */
    fun loadCached(): Catalog? = try {
        if (!catalogFile.exists() || !epgIndexFile.exists()) null
        else DataInputStream(BufferedInputStream(catalogFile.inputStream(), 1 shl 16)).use { i ->
            if (i.readInt() != FORMAT) return null
            val at = i.readLong()
            val countries = List(i.readInt()) { Country(i.readUTF(), i.readUTF(), i.readUTF()) }
            val cats = List(i.readInt()) { Category(i.readUTF(), i.readUTF()) }
            val languages = List(i.readInt()) { Language(i.readUTF(), i.readUTF()) }
            val channels = List(i.readInt()) {
                val id = i.readUTF(); val name = i.readUTF(); val country = i.readUTF()
                val cc = i.readUTF().split(',').filter { it.isNotEmpty() }
                val logo = i.readUTF().ifEmpty { null }
                val langs = i.readUTF().split(',').filter { it.isNotEmpty() }
                val streams = List(i.readInt()) {
                    StreamInfo(
                        i.readUTF(), i.readUTF().ifEmpty { null }, i.readBoolean(), i.readBoolean(),
                        i.readUTF().ifEmpty { null }, i.readUTF().ifEmpty { null },
                    )
                }
                Channel(id, name, country, cc, logo, streams, langs)
            }
            Catalog(channels, countries, cats, languages, at)
        }
    } catch (_: IOException) { null }

    private fun saveEpgIndex(rows: List<Triple<String, Set<String>, Set<String>>>) {
        val tmp = File(epgIndexFile.path + ".tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { o ->
            o.writeInt(rows.size)
            for ((id, names, ids) in rows) {
                o.writeUTF(id)
                o.writeInt(names.size); names.forEach(o::writeUTF)
                o.writeInt(ids.size); ids.forEach(o::writeUTF)
            }
        }
        tmp.renameTo(epgIndexFile)
    }

    /** Loaded only while listings update, because it is too large to keep in memory on a stick. */
    fun loadEpgIndex(wanted: Set<String>): EpgIndex {
        val aliases = HashMap<String, MutableList<String>>()
        val direct = HashMap<String, MutableList<String>>()
        DataInputStream(BufferedInputStream(epgIndexFile.inputStream(), 1 shl 16)).use { i ->
            repeat(i.readInt()) {
                val id = i.readUTF()
                val keep = id in wanted
                repeat(i.readInt()) {
                    val n = i.readUTF()
                    if (keep) aliases.getOrPut(n) { ArrayList(1) } += id
                }
                repeat(i.readInt()) {
                    val d = i.readUTF()
                    if (keep) direct.getOrPut(d) { ArrayList(1) } += id
                }
            }
        }
        return EpgIndex(aliases, direct)
    }

    private companion object {
        const val API = "https://iptv-org.github.io/api"
        const val FORMAT = 2
        val PLUTO_ID = Regex("jmp2\\.uk/plu-([0-9a-f]+)")
        // Only sites whose IDs match the feeds we download. Others (e.g. tvpassport) use IDs that
        // could collide with unrelated numeric IDs in other feeds.
        val DIRECT_ID_SITES = setOf("i.mjh.nz", "pluto.tv", "plex.tv")
    }
}

class EpgIndex(val aliases: Map<String, List<String>>, val direct: Map<String, List<String>>)
