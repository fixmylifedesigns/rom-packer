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
 * Two open sources:
 *  - No-Intro dat files mirrored in libretro-database, which map a ROM's CRC32
 *    and (for GBA) its 4-character game code to the canonical game name.
 *  - libretro-thumbnails, whose Named_Boxarts folders are keyed by that name.
 *
 * Caveat worth knowing: the GBA dat carries game codes for 3218 of its 3692
 * entries, but the Game Boy and Game Boy Color dats carry almost none (6 and
 * 80), so a code like CGB-BXRJ-JPN usually cannot be resolved for those.
 * Matching the ROM's own CRC32 works for all three systems and is tried first.
 */
object CoverArt {

    data class Match(val name: String, val serial: String?, val crc: String?)

    private const val DAT_BASE =
        "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/no-intro"
    private const val THUMB_BASE = "https://raw.githubusercontent.com/libretro-thumbnails"
    private const val CACHE_DAYS = 14L

    private val SYSTEM_PREFIXES = setOf("DMG", "CGB", "AGB")

    private fun datName(system: GameSystem) = when (system) {
        GameSystem.GB -> "Nintendo - Game Boy"
        GameSystem.GBC -> "Nintendo - Game Boy Color"
        GameSystem.GBA -> "Nintendo - Game Boy Advance"
    }

    private fun thumbRepo(system: GameSystem) = datName(system).replace(' ', '_')

    /** Accepts "CGB-BXRJ-JPN", "AGB-BPRE", or a bare "BPRE". */
    fun parseGameCode(input: String): String? {
        val parts = input.trim().uppercase().split('-', ' ').filter { it.isNotBlank() }
        return parts.firstOrNull { part ->
            part.length == 4 && part.all { it.isLetterOrDigit() } && part !in SYSTEM_PREFIXES
        }
    }

    /** The game code stored in the cartridge header, if the header has one. */
    fun headerCode(rom: ByteArray, system: GameSystem): String? {
        val start = if (system == GameSystem.GBA) 0xAC else 0x13F
        if (rom.size < start + 4) return null
        val code = String(rom.copyOfRange(start, start + 4), Charsets.US_ASCII)
        return code.takeIf { it.all { c -> c in 'A'..'Z' || c in '0'..'9' } }
    }

    /** Full cartridge serial, e.g. CGB-BXRJ-JPN, rebuilt from the header. */
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
     * Looks up a game code, then falls back to treating the input as a title.
     * Results are ordered best-first.
     */
    fun search(context: Context, system: GameSystem, query: String): List<Match> {
        val all = entries(context, system)
        val code = parseGameCode(query)
        if (code != null) {
            val bySerial = all.filter { it.serial.equals(code, ignoreCase = true) }
            if (bySerial.isNotEmpty()) return bySerial
        }
        val terms = query.lowercase()
            .replace(Regex("\\b(dmg|cgb|agb|jpn|usa|eur)\\b"), " ")
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 }
        if (terms.isEmpty()) return emptyList()
        return all.filter { m -> terms.all { m.name.lowercase().contains(it) } }
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
    private fun entries(context: Context, system: GameSystem): List<Match> =
        cache.getOrPut(system) { parse(datText(context, system)) }

    private fun datText(context: Context, system: GameSystem): String {
        val file = File(context.cacheDir, "nointro-${system.ext}.dat")
        val fresh = file.isFile && file.length() > 0 &&
            System.currentTimeMillis() - file.lastModified() < CACHE_DAYS * 24 * 3600 * 1000
        if (!fresh) {
            val bytes = download("$DAT_BASE/${encode(datName(system))}.dat")
            if (bytes != null) file.writeBytes(bytes) else if (!file.isFile) return ""
        }
        return file.readText(Charsets.UTF_8)
    }

    private val NAME_RE = Regex("""\bname\s+"([^"]+)"""")
    private val SERIAL_RE = Regex("""\bserial\s+"([^"]+)"""")
    private val CRC_RE = Regex("""\bcrc\s+([0-9A-Fa-f]{8})""")

    private fun parse(text: String): List<Match> {
        if (text.isBlank()) return emptyList()
        return text.split("\ngame (").drop(1).mapNotNull { block ->
            val name = NAME_RE.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
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
