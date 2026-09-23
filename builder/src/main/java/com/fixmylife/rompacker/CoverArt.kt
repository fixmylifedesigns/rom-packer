package com.fixmylife.rompacker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.CRC32

/**
 * Finds box art without any API key.
 *
 * Three open sources:
 *  - libretro-database's serial dats, which map a full cartridge serial
 *    (CGB-BXTJ-JPN) to the canonical game name for all three systems.
 *  - the No-Intro dats in the same repo, which add CRC32 for every entry.
 *  - libretro-thumbnails, whose Named_Boxarts folders are keyed by that name.
 *
 * Lookups are tried in order: full serial, then the 4-character code inside it,
 * then the loaded ROM's own checksum, then a title search.
 */
object CoverArt {

    data class Match(val name: String, val serial: String?, val crc: String?)

    private const val DAT_BASE =
        "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/no-intro"
    private const val SERIAL_BASE =
        "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/serial"
    private const val THUMB_BASE = "https://raw.githubusercontent.com/libretro-thumbnails"
    private const val CACHE_DAYS = 14L

    private val SYSTEM_PREFIXES = setOf("DMG", "CGB", "AGB")

    private fun datName(system: GameSystem) = when (system) {
        GameSystem.GB -> "Nintendo - Game Boy"
        GameSystem.GBC -> "Nintendo - Game Boy Color"
        GameSystem.GBA -> "Nintendo - Game Boy Advance"
    }

    private fun thumbRepo(system: GameSystem) = datName(system).replace(' ', '_')

    /** Accepts "CGB-BXTJ-JPN", "AGB-BPRE", or a bare "BPRE". */
    fun parseGameCode(input: String): String? {
        val parts = input.trim().uppercase().split('-', ' ').filter { it.isNotBlank() }
        return parts.firstOrNull { part ->
            part.length == 4 && part.all { it.isLetterOrDigit() } && part !in SYSTEM_PREFIXES
        }
    }

    /** "CGB-BXTJ-JPN" -> "BXTJ". A bare code is returned unchanged. */
    private fun codeOf(serial: String?): String? {
        val parts = serial?.uppercase()?.split('-') ?: return null
        return parts.firstOrNull { it.length == 4 && it !in SYSTEM_PREFIXES }
    }

    /** The game code stored in the cartridge header, if the header has one. */
    fun headerCode(rom: ByteArray, system: GameSystem): String? {
        val start = if (system == GameSystem.GBA) 0xAC else 0x13F
        if (rom.size < start + 4) return null
        val code = String(rom.copyOfRange(start, start + 4), Charsets.US_ASCII)
        return code.takeIf { it.all { c -> c in 'A'..'Z' || c in '0'..'9' } }
    }

    /** Full cartridge serial, e.g. CGB-BXTJ-JPN, rebuilt from the header. */
    fun headerSerial(rom: ByteArray, system: GameSystem): String? {
        val code = headerCode(rom, system) ?: return null
        val prefix = when (system) {
            GameSystem.GB -> "DMG"
            GameSystem.GBC -> "CGB"
            GameSystem.GBA -> "AGB"
        }
        val region = when (code.last()) {
            'J' -> "JPN"
            'E' -> "USA"
            'P', 'X', 'Y', 'D', 'S', 'F', 'I' -> "EUR"
            else -> null
        }
        return listOfNotNull(prefix, code, region).joinToString("-")
    }

    fun crc32(rom: ByteArray): String =
        CRC32().apply { update(rom) }.value.toString(16).uppercase().padStart(8, '0')

    /** Exact match on the ROM's own checksum. The most reliable route. */
    fun byChecksum(context: Context, system: GameSystem, rom: ByteArray): Match? {
        val crc = crc32(rom)
        return entries(context, system).firstOrNull { it.crc == crc }
    }

    /**
     * Full serial, then game code, then the loaded ROM's checksum, then title.
     * Passing the ROM lets a code that matches this cartridge resolve even when
     * the database has no entry for it.
     */
    fun search(context: Context, system: GameSystem, query: String, rom: ByteArray? = null): List<Match> {
        val all = entries(context, system)
        val trimmed = query.trim()
        val wanted = trimmed.uppercase()

        // 1. exact serial, e.g. CGB-BXTJ-JPN
        all.filter { it.serial?.uppercase() == wanted }.let { if (it.isNotEmpty()) return it }

        // 2. the 4-character code inside it, e.g. BXTJ
        val code = parseGameCode(trimmed)
        if (code != null) {
            all.filter { codeOf(it.serial) == code }.let { if (it.isNotEmpty()) return it }

            // 3. the code belongs to the ROM that's loaded, so identify it by checksum
            if (rom != null && headerCode(rom, system) == code) {
                byChecksum(context, system, rom)?.let { return listOf(it) }
            }
        }

        // 4. title words
        val terms = trimmed.lowercase()
            .replace(Regex("\\b(dmg|cgb|agb|jpn|usa|eur)\\b"), " ")
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 }
        if (terms.isEmpty()) return emptyList()
        return all.filter { m -> terms.all { m.name.lowercase().contains(it) } }
            .distinctBy { it.name }
            .sortedBy { it.name.length }
            .take(25)
    }

    fun boxartUrl(system: GameSystem, name: String): String {
        // libretro replaces these characters with underscores in thumbnail file names.
        val safe = name.map { if (it in "&*/:`<>?\\|\"") '_' else it }.joinToString("")
        return "$THUMB_BASE/${thumbRepo(system)}/master/Named_Boxarts/${encode(safe)}.png"
    }

    fun fetchBoxart(system: GameSystem, name: String): Bitmap? {
        val bytes = download(boxartUrl(system, name)) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    // ---- dat handling ----

    private val cache = HashMap<GameSystem, List<Match>>()

    @Synchronized
    private fun entries(context: Context, system: GameSystem): List<Match> = cache.getOrPut(system) {
        // The serial dat carries cartridge serials; the No-Intro dat carries CRC32
        // for everything. Both name games identically, so they merge cleanly.
        parseSerialDat(datText(context, system, SERIAL_BASE, "serial")) +
            parse(datText(context, system, DAT_BASE, "nointro"))
    }

    private fun datText(context: Context, system: GameSystem, base: String, tag: String): String {
        val file = File(context.cacheDir, "$tag-${system.ext}.dat")
        val fresh = file.isFile && file.length() > 0 &&
            System.currentTimeMillis() - file.lastModified() < CACHE_DAYS * 24 * 3600 * 1000
        if (!fresh) {
            val bytes = download("$base/${encode(datName(system))}.dat")
            if (bytes != null) file.writeBytes(bytes) else if (!file.isFile) return ""
        }
        return file.readText(Charsets.UTF_8)
    }

    private val NAME_RE = Regex("""\bname\s+"([^"]+)"""")
    private val COMMENT_RE = Regex("""\bcomment\s+"([^"]+)"""")
    private val SERIAL_RE = Regex("""\bserial\s+"([^"]+)"""")
    private val CRC_RE = Regex("""\bcrc\s+([0-9A-Fa-f]{8})""")

    /** Serial dat entries: comment "Name" / serial "CGB-BXTJ-JPN" / rom ( crc XXXXXXXX ) */
    private fun parseSerialDat(text: String): List<Match> = parseBlocks(text, COMMENT_RE)

    /** No-Intro dat entries: name "Name" / rom ( ... crc XXXXXXXX ... ) */
    private fun parse(text: String): List<Match> = parseBlocks(text, NAME_RE)

    private fun parseBlocks(text: String, titleRegex: Regex): List<Match> {
        if (text.isBlank()) return emptyList()
        return text.split("\ngame (").drop(1).mapNotNull { block ->
            val name = titleRegex.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val serial = SERIAL_RE.find(block)?.groupValues?.get(1)?.split(',')?.firstOrNull()?.trim()
            val crc = CRC_RE.find(block)?.groupValues?.get(1)?.uppercase()
            Match(name, serial, crc)
        }
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun download(url: String): ByteArray? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "rom-packer")
        }
        try {
            if (conn.responseCode != 200) return null
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
