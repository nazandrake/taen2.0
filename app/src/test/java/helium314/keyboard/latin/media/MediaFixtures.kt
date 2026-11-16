// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.media

import helium314.keyboard.latin.privacy.SecureStore
import helium314.keyboard.latin.privacy.StoreFiles
import helium314.keyboard.latin.privacy.DefaultStoreFiles
import helium314.keyboard.latin.privacy.tempDir
import helium314.keyboard.latin.privacy.testStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

/** Picture files made by hand, byte by byte from the format specifications, with metadata that the strip must remove (canary texts). */
object MediaFixtures {
    const val EXIF = "GPSCANARY-48.8584N-2.2945E"
    const val XMP = "XMPCANARY-creator"
    const val ICC = "ICCCANARY-profile"
    const val TEXT = "TEXTCANARY-comment"
    const val TRAIL = "TRAILCANARY-after-the-end"

    fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    fun concat(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> parts.forEach { o.write(it) } }.toByteArray()

    // ---- JPEG ---------------------------------------------------------------------------------------------------

    private fun jseg(marker: Int, data: ByteArray) = concat(bytes(0xFF, marker, (data.size + 2) shr 8, (data.size + 2) and 0xFF), data)
    val jpegApp0 = jseg(0xE0, ascii("JFIF\u0000") + bytes(1, 1, 0, 0, 1, 0, 1, 0, 0))
    val jpegDqt = jseg(0xDB, bytes(0) + ByteArray(64) { (it + 1).toByte() })
    val jpegSof = jseg(0xC0, bytes(8, 0, 8, 0, 8, 1, 1, 0x11, 0))
    val jpegDht = jseg(0xC4, bytes(0x00) + ByteArray(16) { if (it == 0) 1 else 0 } + bytes(0))
    val jpegSos = jseg(0xDA, bytes(1, 1, 0, 0, 63, 0))
    /** Entropy-coded data with a stuffed 0xFF00 and a restart marker, which must not end the scan. */
    val jpegScan = bytes(0x12, 0xFF, 0x00, 0x34, 0xFF, 0xD0, 0x56, 0x78)
    val jpegEoi = bytes(0xFF, 0xD9)
    val jpegSoi = bytes(0xFF, 0xD8)

    /** The picture as it must come out: no segment that carries metadata, nothing after the end. */
    val jpegClean: ByteArray = concat(jpegSoi, jpegApp0, jpegDqt, jpegSof, jpegDht, jpegSos, jpegScan, jpegEoi)

    val jpegDirty: ByteArray = concat(
        jpegSoi, jpegApp0,
        jseg(0xE1, ascii("Exif\u0000\u0000$EXIF")), jseg(0xE1, ascii("http://ns.adobe.com/xap/1.0/\u0000$XMP")),
        jseg(0xE2, ascii("ICC_PROFILE\u0000$ICC")), jseg(0xED, ascii("Photoshop 3.0\u0000$TEXT")), jseg(0xFE, ascii(TEXT)),
        jpegDqt, jpegSof, jpegDht, jpegSos, jpegScan, jpegEoi, ascii(TRAIL),
    )

    // ---- PNG ----------------------------------------------------------------------------------------------------

    private val pngSignature = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun pchunk(type: String, data: ByteArray = ByteArray(0), badCrc: Boolean = false): ByteArray {
        val crc = CRC32().apply { update(ascii(type)); update(data) }.value.toInt() xor (if (badCrc) 1 else 0)
        return concat(bytes(data.size ushr 24, data.size ushr 16, data.size ushr 8, data.size), ascii(type), data, bytes(crc ushr 24, crc ushr 16, crc ushr 8, crc))
    }

    val pngIhdr = pchunk("IHDR", bytes(0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0))
    val pngIdat = pchunk("IDAT", bytes(0x78, 0x9C, 0x63, 0x60, 0x60, 0x60, 0x00, 0x00, 0x00, 0x04, 0x00, 0x01))
    val pngIend = pchunk("IEND")
    val pngClean: ByteArray = concat(pngSignature, pngIhdr, pngIdat, pngIend)
    val pngDirty: ByteArray = concat(
        pngSignature, pngIhdr, pchunk("tEXt", ascii("Comment\u0000$TEXT")), pchunk("eXIf", ascii("MM\u0000*$EXIF")), pchunk("iCCP", ascii("icc\u0000\u0000$ICC")),
        pchunk("tIME", bytes(7, 0xE9, 11, 16, 9, 0, 0)), pchunk("zTXt", ascii("k\u0000\u0000$XMP")), pchunk("iTXt", ascii("XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000$XMP")),
        pngIdat, pngIend, ascii(TRAIL),
    )

    // ---- GIF ----------------------------------------------------------------------------------------------------

    private fun sub(data: ByteArray) = concat(bytes(data.size), data)
    private val gifHeader = ascii("GIF89a")
    private val gifScreen = bytes(1, 0, 1, 0, 0x80, 0, 0) // 1x1, a 2-colour global table
    private val gifTable = bytes(0, 0, 0, 0xFF, 0xFF, 0xFF)
    private val gifGce = bytes(0x21, 0xF9, 4, 0, 10, 0, 0, 0)
    private val gifLoop = concat(bytes(0x21, 0xFF), sub(ascii("NETSCAPE2.0")), sub(bytes(1, 0, 0)), bytes(0))
    private val gifImage = concat(bytes(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0), bytes(2), sub(bytes(0x02, 0x4C, 0x01)), bytes(0))
    val gifClean: ByteArray = concat(gifHeader, gifScreen, gifTable, gifLoop, gifGce, gifImage, bytes(0x3B))
    val gifDirty: ByteArray = concat(
        gifHeader, gifScreen, gifTable, gifLoop,
        bytes(0x21, 0xFE), sub(ascii(TEXT)), bytes(0),
        bytes(0x21, 0xFF), sub(ascii("XMP DataXMP")), sub(ascii(XMP)), bytes(0),
        bytes(0x21, 0x01), sub(bytes(12, 0, 0, 0, 0, 1, 0, 1, 0, 1, 1, 0, 0)), sub(ascii(TEXT)), bytes(0),
        gifGce, gifImage, bytes(0x3B), ascii(TRAIL),
    )

    /** A valid GIF of exactly [total] bytes: the bulk is image data sub-blocks (the strip copies them without looking into them). */
    fun gifOfSize(total: Int): ByteArray {
        val head = concat(gifHeader, gifScreen, gifTable, bytes(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0), bytes(2))
        val tail = bytes(0, 0x3B)
        var room = total - head.size - tail.size
        val out = ByteArrayOutputStream(total)
        out.write(head)
        while (room > 0) {
            val n = minOf(255, room - 1) // one byte for the length
            if (n < 1) { // too little room for a block: the rest cannot be filled
                throw IllegalArgumentException("size $total cannot be built")
            }
            out.write(n); out.write(ByteArray(n) { 0x5A }); room -= n + 1
        }
        out.write(tail)
        return out.toByteArray().also { check(it.size == total) }
    }

    // ---- WebP ---------------------------------------------------------------------------------------------------

    private fun le32(n: Int) = bytes(n, n shr 8, n shr 16, n shr 24)
    private fun wchunk(type: String, data: ByteArray) = concat(ascii(type), le32(data.size), data, if (data.size % 2 == 1) bytes(0) else ByteArray(0))
    private fun riff(chunks: ByteArray) = concat(ascii("RIFF"), le32(4 + chunks.size), ascii("WEBP"), chunks)
    private val vp8 = wchunk("VP8 ", ByteArray(20) { (it * 3).toByte() })
    private fun vp8x(flags: Int) = wchunk("VP8X", bytes(flags, 0, 0, 0, 0, 0, 0, 0, 0, 0))
    val webpClean: ByteArray = riff(concat(vp8x(0x10), vp8))
    val webpDirty: ByteArray = riff(concat(vp8x(0x10 or 0x20 or 0x08 or 0x04), wchunk("ICCP", ascii(ICC)), vp8, wchunk("EXIF", ascii("Exif\u0000\u0000$EXIF")), wchunk("XMP ", ascii(XMP)), wchunk("JUNK", ascii(TEXT))))

    // ---- stores -------------------------------------------------------------------------------------------------

    /** Encrypted stores in a temporary directory; [locked] makes the tier unavailable (nothing opens, no names), like a locked screen. */
    class TestStorage(val dir: File = tempDir(), private val failing: Set<String> = emptySet()) : MediaStorage {
        @Volatile var locked = false
        override fun open(name: String): SecureStore? {
            if (locked) return null
            val files: StoreFiles = if (name in failing) object : StoreFiles by DefaultStoreFiles { override fun replace(from: File, to: File) = false } else DefaultStoreFiles
            return testStore(dir, name, files = files)
        }
        override fun names(): List<String>? = if (locked) null else dir.listFiles { f -> f.name.endsWith(".enc") }?.map { it.name.removeSuffix(".enc") }.orEmpty()
        fun files(): List<String> = dir.list()?.sorted().orEmpty()
    }
}
