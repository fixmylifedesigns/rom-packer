package com.fixmylife.rompacker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * A packaged game is self-describing: its APK holds assets/game.json, the ROM,
 * and the artwork it was built from. That is everything needed to rebuild it on
 * a newer player, keeping the same package id and signing key, so Android treats
 * the result as an update and the save files in its private storage survive.
 */
object InstalledGames {

    data class Game(
        val packageId: String,
        val title: String,
        val playerBuild: Int,
        val apkPath: String,
    )

    /** Every installed app that was built by ROM Packer. */
    fun list(context: Context): List<Game> {
        val pm = context.packageManager
        val packages = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())

        return packages.mapNotNull { info ->
            if (info.packageName == context.packageName) return@mapNotNull null
            val apk = info.applicationInfo?.sourceDir ?: return@mapNotNull null
            val config = readGameJson(apk) ?: return@mapNotNull null
            val build = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
            Game(
                packageId = info.packageName,
                title = config.optString("title").ifBlank { info.packageName },
                playerBuild = build,
                apkPath = apk,
            )
        }.sortedBy { it.title.lowercase() }
    }

    private fun readGameJson(apkPath: String): JSONObject? = runCatching {
        ZipFile(File(apkPath)).use { zip ->
            val entry = zip.getEntry("assets/game.json") ?: return null
            JSONObject(zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8))
        }
    }.getOrNull()

    /**
     * Repacks one installed game onto the bundled player template.
     * Returns the signed APK, ready to hand to the installer.
     */
    fun rebuild(context: Context, game: Game, template: File, work: File): File {
        var config: JSONObject? = null
        var rom: ByteArray? = null
        var romName = ""
        var art: Bitmap? = null

        ZipFile(File(game.apkPath)).use { zip ->
            val configEntry = zip.getEntry("assets/game.json")
                ?: error("${game.title} isn't a ROM Packer game")
            val parsed = JSONObject(
                zip.getInputStream(configEntry).use { it.readBytes() }.toString(Charsets.UTF_8)
            )
            romName = parsed.optString("rom")
            if (romName.isBlank()) error("No ROM recorded in ${game.title}")
            val romEntry = zip.getEntry("assets/$romName") ?: error("${game.title} has no ROM inside it")
            config = parsed
            rom = zip.getInputStream(romEntry).use { it.readBytes() }
            art = readArtwork(zip)
        }

        val settings = config ?: error("Couldn't read ${game.title}")
        val romBytes = rom ?: error("Couldn't read the ROM in ${game.title}")
        val label = settings.optString("title").ifBlank { game.title }
        val artwork = art ?: IconMaker.placeholder(label, systemOf(romName))
        val unsigned = File(work, "rebuild-unsigned.apk")

        ApkRepacker.repack(
            template = template,
            output = unsigned,
            newPackage = game.packageId,
            label = label,
            extraFiles = mapOf(
                "assets/game.json" to settings.toString().toByteArray(),
                "assets/$romName" to romBytes,
                "assets/icon.png" to encodePng(artwork),
            ),
        ) { layer, px -> IconMaker.render(artwork, layer, px) }

        val signed = File(work, "${game.packageId.substringAfterLast('.')}.apk")
        Signer(context).sign(unsigned, signed)
        unsigned.delete()
        return signed
    }

    /**
     * Prefers the artwork stored at build time. Games built before that was added
     * fall back to the biggest launcher PNG they shipped.
     */
    private fun readArtwork(zip: ZipFile): Bitmap? {
        zip.getEntry("assets/icon.png")?.let { entry ->
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
        }
        val fallback = listOf("xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "mdpi")
            .firstNotNullOfOrNull { density ->
                zip.getEntry("res/mipmap-$density-v4/ic_launcher.png")
                    ?: zip.getEntry("res/mipmap-$density/ic_launcher.png")
            } ?: return null
        val bytes = zip.getInputStream(fallback).use { it.readBytes() }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun systemOf(romName: String) = when (romName.substringAfterLast('.')) {
        "gba" -> GameSystem.GBA
        "gbc" -> GameSystem.GBC
        else -> GameSystem.GB
    }

    fun encodePng(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
}
