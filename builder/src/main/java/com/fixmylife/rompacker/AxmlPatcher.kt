package com.fixmylife.rompacker

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rewrites strings inside a compiled (binary) AndroidManifest.xml.
 *
 * Every attribute value, tag name and namespace in binary XML points into one
 * string pool by index. We rebuild that pool with the replaced strings in the
 * same order, so all indices stay valid and nothing else in the file has to move
 * except the chunk sizes.
 */
object AxmlPatcher {

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val SORTED_FLAG = 0x1
    private const val UTF8_FLAG = 0x100

    class StringPool(
        val strings: List<String>,
        val utf8: Boolean,
        val flags: Int,
        val headerSize: Int,
        val styleOffsets: IntArray,
        val styleData: ByteArray,
    )

    fun patch(axml: ByteArray, transform: (String) -> String): ByteArray {
        val buf = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN)
        require(u16(buf, 0) == RES_XML_TYPE) { "Not a binary XML file" }
        val xmlHeaderSize = u16(buf, 2)
        val poolStart = xmlHeaderSize
        require(u16(buf, poolStart) == RES_STRING_POOL_TYPE) { "String pool not found" }
        val poolSize = buf.getInt(poolStart + 4)

        val pool = readPool(buf, poolStart)
        val newStrings = pool.strings.map(transform)
        if (newStrings == pool.strings) return axml

        val newPool = writePool(pool, newStrings)
        val rest = axml.copyOfRange(poolStart + poolSize, axml.size)

        val out = ByteArrayOutputStream(axml.size + 256)
        val header = axml.copyOfRange(0, xmlHeaderSize)
        val total = xmlHeaderSize + newPool.size + rest.size
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(4, total)
        out.write(header)
        out.write(newPool)
        out.write(rest)
        return out.toByteArray()
    }

    fun readStrings(axml: ByteArray): List<String> {
        val buf = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN)
        return readPool(buf, u16(buf, 2)).strings
    }

    internal fun readPool(buf: ByteBuffer, start: Int): StringPool {
        val headerSize = u16(buf, start + 2)
        val chunkSize = buf.getInt(start + 4)
        val stringCount = buf.getInt(start + 8)
        val styleCount = buf.getInt(start + 12)
        val flags = buf.getInt(start + 16)
        val stringsStart = buf.getInt(start + 20)
        val stylesStart = buf.getInt(start + 24)
        val utf8 = flags and UTF8_FLAG != 0

        val offsetsBase = start + headerSize
        val strings = List(stringCount) { i ->
            val off = start + stringsStart + buf.getInt(offsetsBase + i * 4)
            if (utf8) readUtf8(buf, off) else readUtf16(buf, off)
        }
        val styleOffsets = IntArray(styleCount) { i -> buf.getInt(offsetsBase + stringCount * 4 + i * 4) }
        val styleData = if (styleCount > 0) {
            ByteArray(chunkSize - stylesStart).also { dst ->
                buf.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply { position(start + stylesStart) }.get(dst)
            }
        } else ByteArray(0)
        return StringPool(strings, utf8, flags, headerSize, styleOffsets, styleData)
    }

    internal fun writePool(pool: StringPool, strings: List<String>, utf8: Boolean = pool.utf8): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        strings.forEachIndexed { i, s ->
            offsets[i] = data.size()
            if (utf8) writeUtf8(data, s) else writeUtf16(data, s)
        }
        while (data.size() % 4 != 0) data.write(0)

        val styleCount = pool.styleOffsets.size
        val stringsStart = pool.headerSize + strings.size * 4 + styleCount * 4
        val stylesStart = if (styleCount > 0) stringsStart + data.size() else 0
        val total = stringsStart + data.size() + pool.styleData.size

        var flags = pool.flags and SORTED_FLAG.inv()
        flags = if (utf8) flags or UTF8_FLAG else flags and UTF8_FLAG.inv()

        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(RES_STRING_POOL_TYPE.toShort())
        out.putShort(pool.headerSize.toShort())
        out.putInt(total)
        out.putInt(strings.size)
        out.putInt(styleCount)
        out.putInt(flags)
        out.putInt(stringsStart)
        out.putInt(stylesStart)
        while (out.position() < pool.headerSize) out.put(0)
        offsets.forEach { out.putInt(it) }
        pool.styleOffsets.forEach { out.putInt(it) }
        out.put(data.toByteArray())
        out.put(pool.styleData)
        return out.array()
    }

    // ---- string encodings ----

    private fun readUtf16(buf: ByteBuffer, pos: Int): String {
        var p = pos
        var len = u16(buf, p); p += 2
        if (len and 0x8000 != 0) {
            len = ((len and 0x7FFF) shl 16) or u16(buf, p); p += 2
        }
        val chars = CharArray(len) { i -> buf.getChar(p + i * 2) }
        return String(chars)
    }

    private fun readUtf8(buf: ByteBuffer, pos: Int): String {
        var p = pos
        // UTF-16 length (unused), then UTF-8 byte length
        p += if (u8(buf, p) and 0x80 != 0) 2 else 1
        var len = u8(buf, p); p++
        if (len and 0x80 != 0) {
            len = ((len and 0x7F) shl 8) or u8(buf, p); p++
        }
        val bytes = ByteArray(len) { i -> buf.get(p + i) }
        return String(bytes, Charsets.UTF_8)
    }

    private fun writeUtf16(out: ByteArrayOutputStream, s: String) {
        val len = s.length
        if (len > 0x7FFF) {
            le16(out, 0x8000 or (len ushr 16)); le16(out, len and 0xFFFF)
        } else le16(out, len)
        s.forEach { le16(out, it.code) }
        le16(out, 0)
    }

    private fun writeUtf8(out: ByteArrayOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeLen8(out, s.length)
        writeLen8(out, bytes.size)
        out.write(bytes)
        out.write(0)
    }

    private fun writeLen8(out: ByteArrayOutputStream, len: Int) {
        require(len <= 0x7FFF) { "String too long for UTF-8 pool" }
        if (len > 0x7F) {
            out.write(0x80 or (len ushr 8)); out.write(len and 0xFF)
        } else out.write(len)
    }

    private fun le16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
    }

    private fun u16(buf: ByteBuffer, pos: Int) = buf.getShort(pos).toInt() and 0xFFFF
    private fun u8(buf: ByteBuffer, pos: Int) = buf.get(pos).toInt() and 0xFF
}
