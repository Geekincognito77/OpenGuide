package com.openguide.tv.data

import java.text.Normalizer

const val HALF_HOUR = 30 * 60_000L

/**
 * Rows to move for one key event. Holding Up or Down speeds up, so a long lineup is quick to cross
 * on remotes with no page buttons, while single presses still move exactly one row.
 */
fun rowStep(repeatCount: Int): Int = when {
    repeatCount < 6 -> 1
    repeatCount < 20 -> 3
    else -> 6
}

/** One iptv-org feed of a channel, as far as language is concerned. */
class FeedLanguages(val id: String, val isMain: Boolean, val languages: List<String>)

/** Feeds that actually have a stream come first, because a channel's other feeds aren't what plays. */
fun taggedLanguages(feeds: List<FeedLanguages>, usedFeeds: Set<String?>): List<String> {
    val used = feeds.filter { it.id in usedFeeds }.ifEmpty { feeds.filter { it.isMain } }.ifEmpty { feeds }
    return used.sortedByDescending { it.isMain }.flatMap { it.languages }.distinct()
}

private val SPANISH_NAME = Regex(
    "(?i)\\b(en espa[nñ]ol|espa[nñ]ol|latin[oa]|latin america|telenovelas?|novelas?|cine|pel[ií]culas?|" +
        "cr[ií]menes|deportes|noticias|adrenalina|pluto tv terror)(?![\\p{L}\\p{N}])"
)
private val PORTUGUESE_NAME = Regex("(?i)\\b(em portugu[eê]s|brasil|filmes)(?![\\p{L}\\p{N}])")

/**
 * iptv-org tags many Spanish and Portuguese channels as English ("Cine en espanol" is one), so a
 * channel's name overrules an English tag.
 */
fun correctLanguages(names: List<String>, tagged: List<String>): List<String> {
    if (tagged.firstOrNull() != "eng") return tagged
    return when {
        names.any { SPANISH_NAME.containsMatchIn(it) } -> listOf("spa")
        names.any { PORTUGUESE_NAME.containsMatchIn(it) } -> listOf("por")
        else -> tagged
    }
}

/** Strict on purpose: a channel counts only for its main language, not every audio track it has. */
fun matchesLanguage(languages: List<String>, selected: String): Boolean =
    selected == LANGUAGE_ALL || languages.firstOrNull() == selected

/**
 * Whether a response shows a stream is usable. Null means it can't be told, as with rate limiting.
 * @param head the first bytes of the body, which for HLS must be a playlist and not an error page.
 */
fun probeVerdict(code: Int, path: String, head: String): Boolean? {
    if (code == 429) return null
    if (code !in 200..299) return false
    val p = path.lowercase()
    if (p.endsWith(".mpd") || p.endsWith(".ts") || p.endsWith(".mp4") || p.endsWith(".mkv")) return true
    return head.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U")
}

/** How a CRT shows video that doesn't match its shape. */
enum class CrtStretch(val label: String) {
    FIT("Fit (black bars)"),
    FILL("Fill (crop edges)"),
    STRETCH("Stretch to fill"),
}

/**
 * The size to draw video at so it looks right on the tube, in the frame's own pixels.
 * @param tubeAspect the shape this frame has once it reaches the screen. A converter that squeezes
 *   a 16:9 signal onto a 4:3 tube makes this 4:3 even though the frame's pixels are 16:9.
 */
fun crtVideoSize(frameW: Int, frameH: Int, tubeAspect: Float, videoAspect: Float, stretch: CrtStretch): Pair<Int, Int> {
    if (stretch == CrtStretch.STRETCH) return frameW to frameH
    val wider = videoAspect > tubeAspect
    val matchWidth = if (stretch == CrtStretch.FIT) wider else !wider
    return if (matchWidth) frameW to (frameH * tubeAspect / videoAspect + 0.5f).toInt()
    else (frameW * videoAspect / tubeAspect + 0.5f).toInt() to frameH
}

/** The shape of a video once [cropX] and [cropY] percent are removed from each side and from top and bottom. */
fun croppedAspect(videoAspect: Float, cropX: Int, cropY: Int): Float =
    videoAspect * (100 - 2 * cropX) / (100 - 2 * cropY)

/** The size to draw a whole video at so that its cropped part comes out at [w] by [h]. */
fun uncroppedSize(w: Int, h: Int, cropX: Int, cropY: Int): Pair<Int, Int> =
    (w * 100f / (100 - 2 * cropX) + 0.5f).toInt() to (h * 100f / (100 - 2 * cropY) + 0.5f).toInt()

/** Compares dotted versions numerically, so 1.10 is newer than 1.9. A leading "v" is ignored. */
fun isNewerVersion(remote: String, current: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(remote)
    val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/** Rounds down to the half hour, the guide's column boundary. */
fun floorHalfHour(t: Long): Long = t - Math.floorMod(t, HALF_HOUR)

private val NAME_NOISE = Regex("\\b(hd|fhd|uhd|sd|4k|tv|east|west|channel|the)\\b")
private val NON_NAME = Regex("[^\\p{L}\\p{N}]")
private val MARKS = Regex("\\p{M}+")

/**
 * Reduces a channel name to a key for matching across data sets, which spell the same channel
 * differently ("Fox Weather HD" and "FOX Weather" both become "foxweather").
 */
fun normalizeName(name: String?): String {
    if (name.isNullOrBlank()) return ""
    val folded = MARKS.replace(Normalizer.normalize(name, Normalizer.Form.NFKD), "").lowercase()
    return NON_NAME.replace(NAME_NOISE.replace(folded, " "), "")
}

/** Parses XMLTV times such as "20261106155700 +0000". Returns null if malformed. */
fun parseXmltvTime(s: String?): Long? {
    if (s == null || s.length < 12) return null
    fun part(a: Int, b: Int) = s.substring(a, b).toIntOrNull()
    val year = part(0, 4) ?: return null
    val month = part(4, 6) ?: return null
    val day = part(6, 8) ?: return null
    val hour = part(8, 10) ?: return null
    val minute = part(10, 12) ?: return null
    val second = if (s.length >= 14) part(12, 14) ?: 0 else 0

    // Days from civil date (Howard Hinnant), far cheaper than a Calendar per programme.
    val y = if (month <= 2) year - 1 else year
    val era = Math.floorDiv(y, 400)
    val yoe = y - era * 400
    val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146097L + doe - 719468L
    var ms = (days * 86400L + hour * 3600L + minute * 60L + second) * 1000L

    val tz = s.substring(minOf(14, s.length)).trim()
    if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-')) {
        val h = tz.substring(1, 3).toIntOrNull() ?: 0
        val m = tz.substring(3, 5).toIntOrNull() ?: 0
        val offset = (h * 60 + m) * 60_000L
        ms += if (tz[0] == '+') -offset else offset
    }
    return ms
}

/**
 * Lays programmes out across [from, to): trims overlaps (feeds do overlap) and fills gaps with
 * empty blocks, so every moment in a row belongs to exactly one block.
 */
fun buildBlocks(programmes: List<Programme>, from: Long, to: Long): List<Block> {
    val out = ArrayList<Block>()
    var cursor = from
    for (p in programmes) {
        if (p.stop <= cursor) continue
        if (p.start >= to) break
        if (p.start > cursor) out += Block(cursor, p.start, null)
        val start = maxOf(p.start, cursor)
        val stop = minOf(p.stop, to)
        out += Block(start, stop, p)
        cursor = stop
    }
    if (cursor < to) out += Block(cursor, to, null)
    return out
}

/**
 * Decides which app channel each XMLTV channel feeds. An exact ID wins over a name match, and a
 * channel takes listings from only one XMLTV channel, because a name like "ABC" can match hundreds
 * of local affiliates and merging them would produce nonsense.
 */
fun resolveXmlChannels(
    xml: Map<String, List<String>>,
    direct: Map<String, List<String>>,
    aliases: Map<String, List<String>>,
    alreadyClaimed: Set<String>,
): Map<String, List<String>> {
    val taken = HashSet(alreadyClaimed)
    val out = HashMap<String, MutableList<String>>()
    for (xmlId in xml.keys) {
        for (ch in direct[xmlId].orEmpty()) {
            if (taken.add(ch)) out.getOrPut(xmlId) { ArrayList(1) } += ch
        }
    }
    for ((xmlId, names) in xml) {
        for (name in names) {
            val key = normalizeName(name)
            if (key.length < 2) continue
            for (ch in aliases[key].orEmpty()) {
                if (taken.add(ch)) out.getOrPut(xmlId) { ArrayList(1) } += ch
            }
        }
    }
    return out
}

/** Best stream first: remembered good one, then not geo-blocked, always-on, highest quality. */
fun orderStreams(streams: List<StreamInfo>, lastGoodUrl: String?): List<StreamInfo> =
    streams.sortedWith(
        compareByDescending<StreamInfo> { it.url == lastGoodUrl }
            .thenBy { it.geoBlocked }
            .thenBy { it.notAlwaysOn }
            .thenByDescending { minOf(it.height, 1080) }
    )
