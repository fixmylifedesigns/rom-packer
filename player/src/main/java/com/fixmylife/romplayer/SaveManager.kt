package com.fixmylife.romplayer

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-disk layout for one packaged game:
 *
 *   files/battery.sav          the cartridge battery save (portable)
 *   files/states/slot-N.state  numbered save states (core-specific)
 *   files/states/slot-N.png    thumbnail for that slot
 *   files/states/auto.state    written on exit, offered on next launch
 *
 * Battery saves are plain SRAM dumps, byte-identical to what mGBA, VBA-M and
 * RetroArch write, so a .sav from another emulator imports cleanly. Save states
 * are mGBA's own format and only load back into this player.
 */
class SaveManager(context: Context) {

    private val files = context.filesDir
    private val statesDir = File(files, "states").apply { mkdirs() }

    val batteryFile = File(files, "battery.sav")
    val autoStateFile = File(statesDir, "auto.state")

    /** Legacy path from the first builds; migrated on first run. */
    private val legacySram = File(files, "game.srm")

    init {
        if (legacySram.isFile && !batteryFile.isFile) legacySram.renameTo(batteryFile)
    }

    data class Slot(val index: Int, val file: File, val thumb: File) {
        val exists: Boolean get() = file.isFile && file.length() > 0
        val savedAt: Long get() = if (exists) file.lastModified() else 0L
    }

    fun slots(): List<Slot> = (1..SLOT_COUNT).map { i ->
        Slot(i, File(statesDir, "slot-$i.state"), File(statesDir, "slot-$i.png"))
    }

    fun describe(slot: Slot): String = when {
        !slot.exists -> "Empty"
        else -> {
            val stamp = DATE_FORMAT.format(Date(slot.savedAt))
            val kb = slot.file.length() / 1024
            "$stamp  \u00b7  $kb KB"
        }
    }

    fun readBattery(): ByteArray? =
        batteryFile.takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }

    /** Writes via a temp file so a crash mid-write can't corrupt an existing save. */
    fun writeAtomic(target: File, bytes: ByteArray): Boolean = runCatching {
        if (bytes.isEmpty()) return false
        val tmp = File(target.parentFile, "${target.name}.part")
        tmp.writeBytes(bytes)
        target.delete()
        tmp.renameTo(target)
    }.getOrDefault(false)

    fun delete(slot: Slot) {
        slot.file.delete()
        slot.thumb.delete()
    }

    /** A sensible export file name, e.g. "Pokemon Crystal.sav". */
    fun exportName(title: String, extension: String): String {
        val clean = title.filter { it.isLetterOrDigit() || it in " -_" }.trim().ifBlank { "game" }
        return "$clean.$extension"
    }

    companion object {
        const val SLOT_COUNT = 8
        private val DATE_FORMAT = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    }
}
