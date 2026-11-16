// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.engine

/** The SentencePiece vocabulary of the model (tokens.txt: one `piece id` per line) and the conversion of token ids to text. */
class Tokens(lines: List<String>) {
    private val pieces: Array<String?>

    init {
        val parsed = HashMap<Int, String>()
        for (line in lines) {
            if (line.isBlank()) continue
            val split = line.lastIndexOf(' ')
            require(split > 0) { "bad vocabulary line" }
            val id = line.substring(split + 1).toIntOrNull() ?: throw IllegalArgumentException("bad vocabulary id")
            require(id >= 0 && parsed.put(id, line.substring(0, split)) == null) { "bad or repeated vocabulary id" }
        }
        val size = (parsed.keys.maxOrNull() ?: -1) + 1
        require(size == parsed.size) { "vocabulary ids are not contiguous" }
        pieces = Array(size) { parsed[it] }
    }

    val size: Int get() = pieces.size

    /** Text of [ids]: word pieces start with U+2581 (a space), byte pieces `<0xHH>` are joined to UTF-8, other `<...>` special tokens are dropped. */
    fun text(ids: IntArray): String {
        val out = StringBuilder()
        val bytes = ArrayList<Byte>()
        fun flushBytes() {
            if (bytes.isNotEmpty()) {
                out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                bytes.clear()
            }
        }
        for (id in ids) {
            require(id in pieces.indices) { "token id outside the vocabulary" }
            val piece = pieces[id]!!
            val byte = BYTE_PIECE.matchEntire(piece)
            if (byte != null) {
                bytes.add(byte.groupValues[1].toInt(16).toByte())
                continue
            }
            flushBytes()
            if (piece.length > 1 && piece.startsWith("<") && piece.endsWith(">")) continue
            out.append(piece.replace('▁', ' '))
        }
        flushBytes()
        return out.toString().trim()
    }

    private companion object {
        val BYTE_PIECE = Regex("""<0x([0-9A-Fa-f]{2})>""")
    }
}
