package com.openguide.tv.data

import android.content.Context
import android.os.Build
import android.util.JsonReader
import android.util.JsonToken
import com.openguide.tv.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.security.MessageDigest

data class Release(val version: String, val apkUrl: String, val size: Long, val sha256: String?)

/** Finds and downloads new versions from the project's GitHub releases. */
class Updater(private val ctx: Context, private val http: OkHttpClient) {

    /** Before Android 7 the installer can only read a file directly, so it has to be on shared storage. */
    private val dir: File
        get() {
            val base = if (Build.VERSION.SDK_INT < 24) ctx.getExternalFilesDir(null) ?: ctx.cacheDir else ctx.cacheDir
            return File(base, "updates").apply { mkdirs() }
        }

    /** @return the newest release if it's newer than this app, otherwise null. */
    fun latestIfNewer(): Release? {
        val req = Request.Builder().url(BuildConfig.UPDATE_API).header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { res ->
            if (res.code == 404) return null // nothing has been released yet
            if (!res.isSuccessful) throw IOException("GitHub returned HTTP ${res.code}")
            val release = parse(JsonReader(InputStreamReader(res.body!!.byteStream(), Charsets.UTF_8)))
                ?: throw IOException("The latest release has no $ASSET")
            return release.takeIf { isNewerVersion(it.version, BuildConfig.VERSION_NAME) }
        }
    }

    private fun parse(reader: JsonReader): Release? {
        var tag: String? = null
        var url: String? = null
        var size = 0L
        var sha: String? = null
        reader.use { r ->
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "tag_name" -> tag = r.nextString()
                    "assets" -> {
                        r.beginArray()
                        while (r.hasNext()) {
                            var name: String? = null
                            var link: String? = null
                            var bytes = 0L
                            var digest: String? = null
                            r.beginObject()
                            while (r.hasNext()) {
                                val k = r.nextName()
                                when {
                                    r.peek() == JsonToken.NULL -> r.skipValue()
                                    k == "name" -> name = r.nextString()
                                    k == "browser_download_url" -> link = r.nextString()
                                    k == "size" -> bytes = r.nextLong()
                                    k == "digest" -> digest = r.nextString()
                                    else -> r.skipValue()
                                }
                            }
                            r.endObject()
                            if (name == ASSET) {
                                url = link
                                size = bytes
                                sha = digest?.removePrefix("sha256:")
                            }
                        }
                        r.endArray()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }
        return Release(tag ?: return null, url ?: return null, size, sha)
    }

    /** Downloads and checks the APK. Android also refuses it unless it has this app's signature. */
    fun download(release: Release, progress: (Int) -> Unit): File {
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, ASSET)
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Download failed: HTTP ${res.code}")
            val total = res.body!!.contentLength().takeIf { it > 0 } ?: release.size
            var done = 0L
            var shown = -1
            file.outputStream().use { out ->
                val input = res.body!!.byteStream()
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (pct != shown) {
                        shown = pct
                        progress(pct)
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256 != null && !release.sha256.equals(actual, ignoreCase = true)) {
            file.delete()
            throw IOException("The download was corrupted")
        }
        val info = ctx.packageManager.getPackageArchiveInfo(file.path, 0)
        if (info?.packageName != ctx.packageName) {
            file.delete()
            throw IOException("The download isn't a valid update")
        }
        file.setReadable(true, false) // the installer reads it directly on old Android
        return file
    }

    private companion object {
        const val ASSET = "OpenGuide.apk"
    }
}
