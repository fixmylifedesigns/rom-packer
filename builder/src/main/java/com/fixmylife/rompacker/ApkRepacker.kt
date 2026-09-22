package com.fixmylife.rompacker

import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * Takes the unsigned player template APK and produces an unsigned game APK:
 *  - AndroidManifest.xml: new package id + label
 *  - res/mipmap-* launcher PNGs: replaced with the game's icon
 *  - assets/: ROM + game.json added
 *
 * Entries keep their original compression method (resources.arsc must stay
 * STORED on Android 11+). Alignment is handled afterwards by apksig when signing.
 */
object ApkRepacker {

    enum class IconLayer(val fileName: String, val baseDp: Int) {
        LEGACY("ic_launcher", 48),
        FOREGROUND("ic_launcher_foreground", 108),
        BACKGROUND("ic_launcher_background", 108),
    }

    private val ICON_RE =
        Regex("^res/mipmap-([a-z]+)(?:-v\\d+)?/(ic_launcher(?:_foreground|_background)?)\\.png$")

    private val DENSITY = mapOf(
        "ldpi" to 0.75f, "mdpi" to 1f, "hdpi" to 1.5f,
        "xhdpi" to 2f, "xxhdpi" to 3f, "xxxhdpi" to 4f,
    )

    fun repack(
        template: File,
        output: File,
        newPackage: String,
        label: String,
        extraFiles: Map<String, ByteArray>,
        iconFor: (layer: IconLayer, sizePx: Int) -> ByteArray,
    ) {
        output.parentFile?.mkdirs()
        var iconsReplaced = 0
        var manifestPatched = false

        ZipFile(template).use { zin ->
            ZipOutputStream(BufferedOutputStream(output.outputStream(), 1 shl 16)).use { zout ->
                for (entry in zin.entries()) {
                    val name = entry.name
                    if (entry.isDirectory || isOldSignature(name) || name in extraFiles) continue
                    val stored = entry.method == ZipEntry.STORED

                    if (name == "AndroidManifest.xml") {
                        val original = zin.getInputStream(entry).use { it.readBytes() }
                        writeEntry(zout, name, ManifestRewriter.rewrite(original, newPackage, label), stored)
                        manifestPatched = true
                        continue
                    }

                    val icon = ICON_RE.matchEntire(name)
                    val density = icon?.let { DENSITY[it.groupValues[1]] }
                    if (icon != null && density != null) {
                        val layer = IconLayer.entries.first { it.fileName == icon.groupValues[2] }
                        val px = (layer.baseDp * density).roundToInt()
                        writeEntry(zout, name, iconFor(layer, px), stored)
                        iconsReplaced++
                        continue
                    }

                    copyEntry(zin, entry, zout)
                }
                extraFiles.forEach { (name, bytes) -> writeEntry(zout, name, bytes, stored = false) }
            }
        }

        check(manifestPatched) { "Template has no AndroidManifest.xml" }
        check(iconsReplaced > 0) { "No launcher icons found in template (res/mipmap-*/ic_launcher*.png)" }
    }

    private fun isOldSignature(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val upper = name.uppercase()
        return upper == "META-INF/MANIFEST.MF" ||
            upper.endsWith(".SF") || upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") || upper.endsWith(".EC")
    }

    private fun writeEntry(zout: ZipOutputStream, name: String, bytes: ByteArray, stored: Boolean) {
        val e = ZipEntry(name)
        if (stored) {
            e.method = ZipEntry.STORED
            e.size = bytes.size.toLong()
            e.compressedSize = bytes.size.toLong()
            e.crc = CRC32().apply { update(bytes) }.value
        } else {
            e.method = ZipEntry.DEFLATED
        }
        zout.putNextEntry(e)
        zout.write(bytes)
        zout.closeEntry()
    }

    private fun copyEntry(zin: ZipFile, src: ZipEntry, zout: ZipOutputStream) {
        val e = ZipEntry(src.name)
        if (src.method == ZipEntry.STORED) {
            e.method = ZipEntry.STORED
            e.size = src.size
            e.compressedSize = src.size
            e.crc = src.crc
        } else {
            e.method = ZipEntry.DEFLATED
        }
        zout.putNextEntry(e)
        zin.getInputStream(src).use { it.copyTo(zout, 1 shl 16) }
        zout.closeEntry()
    }
}
