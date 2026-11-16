// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import java.io.ByteArrayOutputStream

/** Builds ustar archives for the tests, byte by byte from the format (POSIX.1-1988: 512-byte blocks, octal fields, checksum over the header). */
class TarBuilder {
    private val out = ByteArrayOutputStream()

    /** One entry ([gnu]: the old GNU magic). [declaredSize] overrides the size field of the header (the content is still written in full, padded to a block). */
    fun entry(name: String, content: ByteArray = ByteArray(0), type: Char = '0', linkName: String = "", declaredSize: Long? = null, prefix: String = "", gnu: Boolean = false): TarBuilder {
        val header = ByteArray(512)
        fun put(at: Int, text: String, width: Int) { text.toByteArray(Charsets.UTF_8).copyInto(header, at, 0, minOf(width, text.toByteArray(Charsets.UTF_8).size)) }
        fun octal(at: Int, value: Long, width: Int) { put(at, value.toString(8).padStart(width - 1, '0'), width - 1) }
        put(0, name, 100)
        octal(100, if (type == '5') 0b111_101_101 else 0b110_100_100, 8)
        octal(108, 0, 8); octal(116, 0, 8)
        octal(124, declaredSize ?: if (type == '0' || type == '\u0000') content.size.toLong() else 0L, 12)
        octal(136, 1_700_000_000L, 12)
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        header[156] = type.code.toByte()
        put(157, linkName, 100)
        if (gnu) { put(257, "ustar ", 6); put(263, " ", 1) } else { put(257, "ustar", 6); put(263, "00", 2) } // GNU magic "ustar  \0"
        put(345, prefix, 155) // in a GNU header these bytes are access and change times, not a path prefix
        val sum = header.sumOf { it.toInt() and 0xff }
        put(148, sum.toString(8).padStart(6, '0'), 6)
        header[154] = 0; header[155] = ' '.code.toByte()
        out.write(header)
        out.write(content)
        val pad = (512 - content.size % 512) % 512
        out.write(ByteArray(pad))
        return this
    }

    fun dir(name: String) = entry(name, type = '5')

    /** Two zero blocks (the end of the archive) and the record padding GNU tar adds. */
    fun build(end: Boolean = true): ByteArray {
        if (end) out.write(ByteArray(1024))
        return out.toByteArray()
    }
}
