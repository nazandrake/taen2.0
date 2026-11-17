// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** The bytes of a PIN or the trigger word for the hash: made from the characters without a String, and zeroed after use. */
object SecretBytes {
    /**
     * Runs [block] with the UTF-8 form of [chars] (the same bytes as `String(chars).toByteArray(Charsets.UTF_8)`, a malformed surrogate
     * becomes '?'). The array and the scratch space it was made in are zeroed when [block] ends, so [block] must not keep the array.
     * The caller clears [chars]. [scratch] is the space the encoding is made in (at least three bytes per character; a test passes its own to see it cleared).
     */
    fun <T> withUtf8(chars: CharArray, scratch: ByteArray = ByteArray(chars.size * MAX_BYTES_PER_CHAR), block: (ByteArray) -> T): T {
        require(scratch.size >= chars.size * MAX_BYTES_PER_CHAR) { "scratch space too small" }
        val bytes: ByteArray
        try {
            val out = ByteBuffer.wrap(scratch)
            val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            encoder.encode(CharBuffer.wrap(chars), out, true)
            encoder.flush(out)
            bytes = scratch.copyOf(out.position())
        } finally {
            scratch.fill(0)
        }
        try {
            return block(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /** UTF-8 needs at most three bytes for a UTF-16 unit (a surrogate pair: four bytes for two units). */
    private const val MAX_BYTES_PER_CHAR = 3
}
