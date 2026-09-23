package com.fixmylife.rompacker

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update straight from GitHub releases.
 *
 * CI tags every build "build-N" where N is the run number, and passes that same
 * number in as the APK's versionCode, so comparing the two is just an integer
 * compare. Every release is signed with the same key, so Android treats the
 * download as an in-place upgrade and nothing is lost.
 */
object Updater {

    private const val LATEST_URL =
        "https://api.github.com/repos/fixmylifedesigns/rom-packer/releases/latest"
    private const val ASSET = "RomPacker.apk"
    private const val PREFS = "updates"
    private const val LAST_CHECK = "lastCheck"
    private const val CHECK_INTERVAL_MS = 12 * 3600 * 1000L

    data class Release(val versionCode: Int, val tag: String, val url: String, val size: Long)

    fun installedVersion(context: Context): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            info.versionCode
        }
    }.getOrDefault(0)

    /** True when enough time has passed to check again without being a nuisance. */
    fun shouldAutoCheck(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return System.currentTimeMillis() - prefs.getLong(LAST_CHECK, 0) > CHECK_INTERVAL_MS
    }

    fun markChecked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply()
    }

    /** Fetches the newest release, or null if the network call or parsing fails. */
    fun latest(): Release? = runCatching {
        val body = get(LATEST_URL)?.toString(Charsets.UTF_8) ?: return null
        val json = JSONObject(body)
        val tag = json.optString("tag_name").ifBlank { return null }
        val version = tag.substringAfterLast('-').toIntOrNull() ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name") == ASSET) {
                return Release(
                    versionCode = version,
                    tag = tag,
                    url = asset.optString("browser_download_url"),
                    size = asset.optLong("size"),
                )
            }
        }
        null
    }.getOrNull()

    /** Downloads the release APK into the cache and returns the file. */
    fun download(context: Context, release: Release, onProgress: (Int) -> Unit = {}): File? {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "RomPacker-${release.tag}.apk")
        val conn = runCatching {
            (URL(release.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "rom-packer")
            }
        }.getOrNull() ?: return null
        return try {
            if (conn.responseCode != 200) return null
            val total = if (release.size > 0) release.size else conn.contentLength.toLong()
            var read = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                out.outputStream().use { sink ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        sink.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            // Only report whole percent changes, or this fires ~120 times.
                            val percent = ((read * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            out.takeIf { it.length() > 0 }
        } catch (_: Exception) {
            out.delete()
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String): ByteArray? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "rom-packer")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode != 200) return null
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
