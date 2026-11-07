package com.openguide.tv.data

data class StreamInfo(
    val url: String,
    val quality: String?,
    val geoBlocked: Boolean,
    val notAlwaysOn: Boolean,
    val userAgent: String?,
    val referrer: String?,
) {
    /** Numeric height from labels like "720p", or 0 when iptv-org doesn't say. */
    val height: Int get() = quality?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
    val label: String get() = buildString {
        append(quality ?: "Auto")
        if (geoBlocked) append(" · geo-blocked")
        if (notAlwaysOn) append(" · not 24/7")
    }
}

class Channel(
    val id: String,
    val name: String,
    val country: String,
    val categories: List<String>,
    val logo: String?,
    val streams: List<StreamInfo>,
    /** ISO 639-3 codes, main language first. */
    val languages: List<String>,
) {
    val nameLower: String = name.lowercase()
    override fun equals(other: Any?) = other is Channel && other.id == id
    override fun hashCode() = id.hashCode()
}

data class Country(val code: String, val name: String, val flag: String)
data class Category(val id: String, val name: String)
data class Language(val code: String, val name: String)

class Catalog(
    val channels: List<Channel>,
    val countries: List<Country>,
    val categories: List<Category>,
    val languages: List<Language>,
    val fetchedAt: Long,
)

data class Programme(
    val start: Long,
    val stop: Long,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val category: String?,
    val episode: String?,
)

/** One cell in a guide row. [programme] is null where no listing covers that time. */
data class Block(val start: Long, val stop: Long, val programme: Programme?)

const val REGION_WORLD = "ALL"
const val CATEGORY_ALL = "all"
const val CATEGORY_FAVORITES = "favorites"
const val CATEGORY_CRT = "crt"
const val LANGUAGE_ALL = "all"

const val GUIDE_CHANNEL_ID = "openguide.guide-channel"
/** CRT mode's scrolling listings. It's added to lists by the UI and isn't in the catalog, having no stream. */
val GUIDE_CHANNEL = Channel(GUIDE_CHANNEL_ID, "Guide Channel", "", emptyList(), null, emptyList(), emptyList())
