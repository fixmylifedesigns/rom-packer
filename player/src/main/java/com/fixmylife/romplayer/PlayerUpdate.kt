package com.fixmylife.romplayer

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A packaged game can't replace itself: it has no copy of itself to download,
 * and only ROM Packer holds the signing key and the ROM-repacking code. What it
 * can do is notice that the player it was built from is out of date and send the
 * user to ROM Packer, which rebuilds it in place (same package id, same key, so
 * saves survive).
 *
 * The version to compare is this APK's own versionCode: ROM Packer copies the
 * player template wholesale, so an installed game carries the template's build
 * number.
 */
object PlayerUpdate {

    const val PACKER_PACKAGE = "com.fixmylife.rompacker"

    private const val LATEST_URL =
        "https://api.github.com/repos/fixmylifedesigns/rom-packer/releases/latest"

    fun installedVersion(context: Context): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }.getOrDefault(0)

    /** Newest published build number, or null if GitHub can't be reached. */
    fun latestBuild(): Int? = runCatching {
        val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "rom-packer-player")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            JSONObject(body).optString("tag_name").substringAfterLast('-').toIntOrNull()
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    fun isPackerInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKER_PACKAGE, 0)
        true
    }.getOrDefault(false)
}
