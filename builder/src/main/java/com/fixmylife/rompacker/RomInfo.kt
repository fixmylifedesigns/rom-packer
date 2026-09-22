package com.fixmylife.rompacker

enum class GameSystem(val ext: String, val label: String) {
    GB("gb", "Game Boy"),
    GBC("gbc", "Game Boy Color"),
    GBA("gba", "Game Boy Advance"),
}

data class RomInfo(
    val system: GameSystem,
    val headerTitle: String,
    val suggestedName: String,
) {
    companion object {
        private val NINTENDO_LOGO_START = byteArrayOf(0xCE.toByte(), 0xED.toByte(), 0x66, 0x66)

        /** Detects the system from the cartridge header, falling back to the file extension. */
        fun detect(rom: ByteArray, fileName: String?): RomInfo? {
            val ext = fileName?.substringAfterLast('.', "")?.lowercase()
            val system = when {
                isGba(rom) -> GameSystem.GBA
                isGb(rom) -> if (isCgb(rom)) GameSystem.GBC else GameSystem.GB
                ext == "gba" -> GameSystem.GBA
                ext == "gbc" -> GameSystem.GBC
                ext == "gb" -> GameSystem.GB
                else -> return null
            }
            val headerTitle = when (system) {
                GameSystem.GBA -> ascii(rom, 0xA0, 12)
                else -> ascii(rom, 0x134, if (isCgb(rom)) 11 else 16)
            }
            val fromFile = fileName?.substringBeforeLast('.')?.let(::cleanFileName).orEmpty()
            val name = fromFile.ifBlank { prettify(headerTitle) }.ifBlank { system.label }
            return RomInfo(system, headerTitle, name)
        }

        private fun isGba(rom: ByteArray) = rom.size >= 0xC0 && rom[0xB2] == 0x96.toByte()

        private fun isGb(rom: ByteArray) = rom.size >= 0x150 &&
            NINTENDO_LOGO_START.indices.all { rom[0x104 + it] == NINTENDO_LOGO_START[it] }

        private fun isCgb(rom: ByteArray): Boolean {
            val flag = rom[0x143].toInt() and 0xFF
            return flag == 0x80 || flag == 0xC0
        }

        private fun ascii(rom: ByteArray, start: Int, len: Int): String {
            if (rom.size < start + len) return ""
            return rom.copyOfRange(start, start + len)
                .takeWhile { it in 0x20..0x7E }
                .toByteArray()
                .toString(Charsets.US_ASCII)
                .trim()
        }

        /** "Pokemon - Crystal Version (USA, Europe) [!]" -> "Pokemon - Crystal Version" */
        fun cleanFileName(name: String): String =
            name.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "")
                .replace('_', ' ')
                .trim()

        private fun prettify(header: String): String =
            header.lowercase().split(' ').filter { it.isNotBlank() }
                .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }
}
