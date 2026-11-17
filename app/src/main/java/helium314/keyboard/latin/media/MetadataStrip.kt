// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.media

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** The picture could not be parsed as its format (broken, cut off or inconsistent). The import refuses it. */
class MediaParseException(message: String) : Exception(message)

/**
 * Removes metadata from a picture without decoding or re-encoding it: every segment that carries the image data and what is needed to show it is copied
 * byte for byte, and everything else is left out. A file that does not parse as its format is rejected ([MediaParseException]), never passed through.
 *  - JPEG: only the JFIF header (APP0 "JFIF"), the Adobe colour marker (APP14) and the coding segments stay; EXIF and XMP (APP1), ICC profiles (APP2),
 *    IPTC (APP13), other application segments and comments are dropped, and so is everything after the end-of-image marker.
 *  - PNG: an allow-list of chunks (the image, palette, transparency, colour description and animation chunks); text chunks (tEXt, zTXt, iTXt), eXIf, iCCP, tIME
 *    and every unknown chunk are dropped, every kept chunk has its CRC checked, and everything after IEND is dropped.
 *  - GIF: comment, plain-text and unknown extensions are dropped, application extensions stay only for NETSCAPE2.0 and ANIMEXTS1.0 (the loop count).
 *  - WebP: the image chunks stay (VP8, VP8L, VP8X, ALPH, ANIM, ANMF); EXIF, XMP, ICCP and unknown chunks are dropped and the VP8X flags and the RIFF size are rewritten.
 */
object MetadataStrip {
    fun strip(bytes: ByteArray, format: MediaFormat): ByteArray = when (format) {
        MediaFormat.JPEG -> jpeg(bytes)
        MediaFormat.PNG -> png(bytes)
        MediaFormat.GIF -> gif(bytes)
        MediaFormat.WEBP -> webp(bytes)
    }

    private fun fail(): Nothing = throw MediaParseException("picture does not parse")
    private fun u8(b: ByteArray, i: Int): Int { if (i < 0 || i >= b.size) fail(); return b[i].toInt() and 0xFF }
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun be32(b: ByteArray, i: Int): Long = (be16(b, i).toLong() shl 16) or be16(b, i + 2).toLong()
    private fun le32(b: ByteArray, i: Int): Long = u8(b, i).toLong() or (u8(b, i + 1).toLong() shl 8) or (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)
    private fun ByteArrayOutputStream.range(b: ByteArray, from: Int, to: Int) { if (from < 0 || to > b.size || from > to) fail(); write(b, from, to - from) }
    private fun ascii(b: ByteArray, i: Int, n: Int): String { if (i < 0 || i + n > b.size) fail(); return String(b, i, n, Charsets.ISO_8859_1) }

    // ---- JPEG -------------------------------------------------------------------------------------------------------

    private fun jpeg(b: ByteArray): ByteArray {
        if (b.size < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) fail()
        val out = ByteArrayOutputStream(b.size)
        out.write(0xFF); out.write(0xD8)
        var i = 2
        while (true) {
            while (i < b.size && u8(b, i) == 0xFF && i + 1 < b.size && u8(b, i + 1) == 0xFF) i++ // fill bytes
            if (u8(b, i) != 0xFF) fail()
            val marker = u8(b, i + 1)
            if (marker == 0xD9) { out.write(0xFF); out.write(0xD9); return out.toByteArray() } // EOI: whatever follows is dropped
            if (marker == 0xD8 || marker == 0x00) fail()
            if (marker == 0x01 || marker in 0xD0..0xD7) { out.range(b, i, i + 2); i += 2; continue }
            val length = be16(b, i + 2)
            if (length < 2 || i + 2 + length > b.size) fail()
            val end = i + 2 + length
            if (keepJpegSegment(b, marker, i + 4, length - 2)) out.range(b, i, end)
            i = end
            if (marker == 0xDA) { // scan data: up to the next marker that is not a stuffed byte or a restart marker
                var j = i
                while (true) {
                    if (j >= b.size) fail()
                    if (u8(b, j) != 0xFF) { j++; continue }
                    if (j + 1 >= b.size) fail()
                    val next = u8(b, j + 1)
                    if (next == 0x00 || next in 0xD0..0xD7) { j += 2; continue }
                    if (next == 0xFF) { j++; continue }
                    break
                }
                out.range(b, i, j)
                i = j
            }
        }
    }

    private fun keepJpegSegment(b: ByteArray, marker: Int, dataStart: Int, dataLength: Int): Boolean = when (marker) {
        0xE0 -> dataLength >= 5 && ascii(b, dataStart, 5) == "JFIF\u0000"
        0xEE -> true // Adobe: the colour transform is needed to show some files
        in 0xE1..0xEF -> false // EXIF, XMP, ICC, IPTC, MPF and every other application segment
        0xFE -> false // comment
        else -> true // frame header, quantisation and Huffman tables, scan header, restart interval
    }

    // ---- PNG --------------------------------------------------------------------------------------------------------

    private val pngKeep = setOf("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "sBIT", "bKGD", "hIST", "pHYs", "acTL", "fcTL", "fdAT", "cICP")

    private fun png(b: ByteArray): ByteArray {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (b.size < 8 || !(0 until 8).all { b[it] == signature[it] }) fail()
        val out = ByteArrayOutputStream(b.size)
        out.write(signature)
        var i = 8
        var first = true
        while (true) {
            val length = be32(b, i)
            if (length > Int.MAX_VALUE - 12 || i + 12 + length > b.size) fail()
            val type = ascii(b, i + 4, 4)
            if (first && type != "IHDR") fail()
            first = false
            val crc = CRC32().apply { update(b, i + 4, 4 + length.toInt()) }.value
            if (crc != be32(b, i + 8 + length.toInt())) fail()
            val end = i + 12 + length.toInt()
            if (type in pngKeep) out.range(b, i, end)
            if (type == "IEND") return out.toByteArray() // whatever follows is dropped
            i = end
        }
    }

    // ---- GIF --------------------------------------------------------------------------------------------------------

    private fun gif(b: ByteArray): ByteArray {
        val head = ascii(b, 0, 6)
        if (head != "GIF87a" && head != "GIF89a") fail()
        val out = ByteArrayOutputStream(b.size)
        var i = 13
        val packed = u8(b, 10)
        if (packed and 0x80 != 0) i += 3 * (1 shl ((packed and 7) + 1))
        out.range(b, 0, i)
        while (true) {
            when (u8(b, i)) {
                0x3B -> { out.write(0x3B); return out.toByteArray() } // trailer: whatever follows is dropped
                0x2C -> {
                    val flags = u8(b, i + 9)
                    var j = i + 10
                    if (flags and 0x80 != 0) j += 3 * (1 shl ((flags and 7) + 1))
                    j += 1 // LZW minimum code size
                    j = skipSubBlocks(b, j)
                    out.range(b, i, j)
                    i = j
                }
                0x21 -> {
                    val label = u8(b, i + 1)
                    var j = skipSubBlocks(b, i + 2)
                    val keep = when (label) {
                        0xF9 -> true // graphic control: frame delay, transparency, disposal
                        0xFF -> u8(b, i + 2) == 11 && ascii(b, i + 3, 11).let { it == "NETSCAPE2.0" || it == "ANIMEXTS1.0" }
                        else -> false // comment, plain text, unknown
                    }
                    if (keep) out.range(b, i, j)
                    i = j
                }
                else -> fail()
            }
        }
    }

    /** The index after the sub-blocks that start at [start] (each is a length byte and that many bytes, ended by a zero length). */
    private fun skipSubBlocks(b: ByteArray, start: Int): Int {
        var j = start
        while (true) {
            val size = u8(b, j)
            j += 1 + size
            if (size == 0) return j
            if (j > b.size) fail()
        }
    }

    // ---- WebP -------------------------------------------------------------------------------------------------------

    private val webpKeep = setOf("VP8 ", "VP8L", "VP8X", "ALPH", "ANIM", "ANMF")

    private fun webp(b: ByteArray): ByteArray {
        if (ascii(b, 0, 4) != "RIFF" || ascii(b, 8, 4) != "WEBP") fail()
        val declared = le32(b, 4)
        if (declared < 4 || 8 + declared > b.size) fail()
        val limit = (8 + declared).toInt()
        val body = ByteArrayOutputStream(b.size)
        var i = 12
        var image = false
        while (i < limit) {
            if (i + 8 > limit) fail()
            val type = ascii(b, i, 4)
            val size = le32(b, i + 4)
            val padded = size + (size and 1L)
            if (i + 8 + padded > limit) fail()
            val end = (i + 8 + padded).toInt()
            if (type in webpKeep) {
                if (type == "VP8 " || type == "VP8L" || type == "ANMF") image = true
                if (type == "VP8X") {
                    if (size < 10) fail()
                    body.range(b, i, i + 8)
                    body.write(u8(b, i + 8) and (0x20 or 0x08 or 0x04).inv()) // no ICC, EXIF or XMP flag any more
                    body.range(b, i + 9, end)
                } else body.range(b, i, end)
            }
            i = end
        }
        if (!image) fail()
        val chunks = body.toByteArray()
        val out = ByteArrayOutputStream(chunks.size + 12)
        val total = 4 + chunks.size
        out.write("RIFF".toByteArray(Charsets.ISO_8859_1))
        out.write(total and 0xFF); out.write((total shr 8) and 0xFF); out.write((total shr 16) and 0xFF); out.write((total shr 24) and 0xFF)
        out.write("WEBP".toByteArray(Charsets.ISO_8859_1))
        out.write(chunks)
        return out.toByteArray()
    }
}
