// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.media

/** The four picture formats of the media collection, told apart by magic bytes only, never by a file name or a type the provider claims. */
enum class MediaFormat(val ext: String, val mime: String) {
    GIF("gif", "image/gif"), PNG("png", "image/png"), JPEG("jpg", "image/jpeg"), WEBP("webp", "image/webp");

    companion object {
        /** The format of [bytes], or null for any other content (including too short). */
        fun sniff(bytes: ByteArray): MediaFormat? = when {
            bytes.startsWith("GIF87a") || bytes.startsWith("GIF89a") -> GIF
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes.startsWith("PNG", 1) && bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() && bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte() -> PNG
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> JPEG
            bytes.size >= 12 && bytes.startsWith("RIFF") && bytes.startsWith("WEBP", 8) -> WEBP
            else -> null
        }

        fun byExt(ext: String): MediaFormat? = entries.firstOrNull { it.ext == ext }

        private fun ByteArray.startsWith(text: String, offset: Int = 0): Boolean =
            size >= offset + text.length && text.indices.all { this[offset + it] == text[it].code.toByte() }
    }
}
