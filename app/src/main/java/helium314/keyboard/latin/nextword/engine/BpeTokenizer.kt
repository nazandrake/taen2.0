// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword.engine

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.text.Normalizer

/** How the text is cut into pieces before the merges run: the pre-tokenizer of the model's own tokenizer, written out as a scanner. */
enum class PreSplit {
    /** SmolLM2: every digit alone, then the byte-level BPE split pattern (case-sensitive contractions, an optional space before a run of one class). */
    SMOLLM2,
    /** Qwen2/Qwen3: NFC first, then the Qwen pattern (contractions in any case, one leading non-letter before letters, digits one by one). */
    QWEN2,
}

/**
 * The byte-level BPE tokenizer of SmolLM2 and Qwen3, from their vocab.json and merges.txt (shipped verbatim in the APK). [encode] gives the same ids as
 * the models' own tokenizer without special tokens (BpeTokenizerTest compares 100 strings per model); special-token text such as `<|endoftext|>` is
 * plain text here, so typed text can never become a control token. The pre-tokenizer patterns are scanned by hand instead of with a regular expression,
 * because `\s` and `\p{N}` differ between the regex engines of the JVM and Android.
 */
class BpeTokenizer private constructor(
    private val ids: Map<String, Int>,
    private val tokens: Array<String?>,
    private val ranks: Map<String, Int>,
    private val split: PreSplit,
) {
    val vocabularySize: Int get() = tokens.size

    fun encode(text: String): IntArray {
        val input = if (split == PreSplit.QWEN2) Normalizer.normalize(text, Normalizer.Form.NFC) else text
        val out = ArrayList<Int>()
        for (piece in pieces(input)) {
            val bytes = piece.toByteArray(Charsets.UTF_8)
            val symbols = ArrayList<String>(bytes.size)
            for (b in bytes) symbols.add(BYTE_CHARS[b.toInt() and 0xFF])
            for (symbol in merge(symbols)) out.add(ids[symbol] ?: continue)
        }
        return out.toIntArray()
    }

    /** The text of token [id], or null when it is no token or its bytes are not whole UTF-8 characters (a part of a longer character). */
    fun piece(id: Int): String? {
        val token = tokens.getOrNull(id) ?: return null
        val bytes = ByteArray(token.length)
        for (i in token.indices) bytes[i] = (CHAR_BYTES[token[i]] ?: return null).toByte()
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun merge(symbols: ArrayList<String>): List<String> {
        while (symbols.size > 1) {
            var best = -1
            var bestRank = Int.MAX_VALUE
            for (i in 0 until symbols.size - 1) {
                val rank = ranks[symbols[i] + " " + symbols[i + 1]] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    best = i
                }
            }
            if (best < 0) break
            val first = symbols[best]
            val second = symbols[best + 1]
            // every occurrence of the pair, left to right, like the reference implementation
            var i = 0
            val merged = ArrayList<String>(symbols.size)
            while (i < symbols.size) {
                if (i < symbols.size - 1 && symbols[i] == first && symbols[i + 1] == second) {
                    merged.add(first + second)
                    i += 2
                } else {
                    merged.add(symbols[i])
                    i++
                }
            }
            symbols.clear()
            symbols.addAll(merged)
        }
        return symbols
    }

    private fun pieces(text: String): List<String> {
        val out = ArrayList<String>()
        if (split == PreSplit.SMOLLM2) {
            // the digits pre-tokenizer: each numeric character is a piece of its own; the pattern then runs inside every other piece
            var start = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val n = Character.charCount(cp)
                if (isNumber(cp)) {
                    if (start < i) scanByteLevel(text.substring(start, i), out)
                    out.add(text.substring(i, i + n))
                    start = i + n
                }
                i += n
            }
            if (start < text.length) scanByteLevel(text.substring(start), out)
        } else {
            scanQwen(text, out)
        }
        return out
    }

    /** `'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+` */
    private fun scanByteLevel(s: String, out: MutableList<String>) {
        var i = 0
        while (i < s.length) {
            val end = contraction(s, i, ignoreCase = false)
                ?: optionalSpaceRun(s, i, ::isLetter)
                ?: optionalSpaceRun(s, i, ::isNumber)
                ?: optionalSpaceRun(s, i, ::isOther)
                ?: whitespace(s, i)
            out.add(s.substring(i, end))
            i = end
        }
    }

    /** `(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}| ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+` */
    private fun scanQwen(s: String, out: MutableList<String>) {
        var i = 0
        while (i < s.length) {
            val end = contraction(s, i, ignoreCase = true)
                ?: qwenLetters(s, i)
                ?: (if (isNumber(s.codePointAt(i))) i + Character.charCount(s.codePointAt(i)) else null)
                ?: optionalSpaceRun(s, i, ::isOther)?.let { e -> run(s, e) { it == '\r'.code || it == '\n'.code } }
                ?: lineBreaks(s, i)
                ?: whitespace(s, i)
            out.add(s.substring(i, end))
            i = end
        }
    }

    private fun contraction(s: String, i: Int, ignoreCase: Boolean): Int? {
        if (s[i] != '\'') return null
        for (c in CONTRACTIONS) if (s.regionMatches(i + 1, c, 0, c.length, ignoreCase)) return i + 1 + c.length
        return null
    }

    /** ` ?X+`: an optional ASCII space, then at least one code point of class X. */
    private fun optionalSpaceRun(s: String, i: Int, cls: (Int) -> Boolean): Int? {
        if (s[i] == ' ' && i + 1 < s.length && cls(s.codePointAt(i + 1))) return run(s, i + 1, cls)
        return if (cls(s.codePointAt(i))) run(s, i, cls) else null
    }

    /** `[^\r\n\p{L}\p{N}]?\p{L}+` */
    private fun qwenLetters(s: String, i: Int): Int? {
        val cp = s.codePointAt(i)
        if (isLetter(cp)) return run(s, i, ::isLetter)
        val next = i + Character.charCount(cp)
        if (cp != '\r'.code && cp != '\n'.code && !isNumber(cp) && next < s.length && isLetter(s.codePointAt(next))) return run(s, next, ::isLetter)
        return null
    }

    /** `\s*[\r\n]+`: the whitespace run up to and including its last line break. */
    private fun lineBreaks(s: String, i: Int): Int? {
        val end = run(s, i, ::isSpace)
        var last = -1
        for (k in i until end) if (s[k] == '\r' || s[k] == '\n') last = k
        return if (last < 0) null else last + 1
    }

    /** `\s+(?!\S)|\s+`: a whitespace run; when a non-space follows, its last space is left for the next piece (unless the run is one space long). */
    private fun whitespace(s: String, i: Int): Int {
        val end = run(s, i, ::isSpace)
        if (end == i) return i + Character.charCount(s.codePointAt(i)) // not reached: every code point is in one of the classes
        if (end < s.length && end - i > 1) return end - Character.charCount(s.codePointBefore(end))
        return end
    }

    private fun run(s: String, from: Int, cls: (Int) -> Boolean): Int {
        var i = from
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!cls(cp)) break
            i += Character.charCount(cp)
        }
        return i
    }

    companion object {
        private val CONTRACTIONS = listOf("s", "t", "re", "ve", "m", "ll", "d")

        /** `\p{L}` */
        private fun isLetter(cp: Int) = Character.isLetter(cp)

        /** `\p{N}` (decimal digits, letter numbers, other numbers) */
        private fun isNumber(cp: Int) = when (Character.getType(cp)) {
            Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
            else -> false
        }

        /** `\s`: the Unicode White_Space property (space separators, line and paragraph separators, tab, line feed, vertical tab, form feed, return, next line). */
        private fun isSpace(cp: Int) = Character.isSpaceChar(cp) || cp in 0x09..0x0D || cp == 0x85

        /** `[^\s\p{L}\p{N}]` */
        private fun isOther(cp: Int) = !isSpace(cp) && !isLetter(cp) && !isNumber(cp)

        /** The byte-to-character table of byte-level BPE: printable bytes stand for themselves, the others are moved to U+0100 and up. */
        private val BYTE_CHARS: Array<String> = run {
            val printable = (0x21..0x7E) + (0xA1..0xAC) + (0xAE..0xFF)
            var next = 256
            Array(256) { b -> if (b in printable) b.toChar().toString() else (next++).toChar().toString() }
        }
        private val CHAR_BYTES: Map<Char, Int> = BYTE_CHARS.withIndex().associate { (b, c) -> c[0] to b }

        /** Builds the tokenizer from the text of vocab.json (one flat JSON object of token to id) and merges.txt (a `#version` line, then one "left right" pair per line, by rank). */
        fun read(vocabJson: String, merges: String, split: PreSplit): BpeTokenizer {
            val ids = VocabJson.parse(vocabJson)
            val tokens = arrayOfNulls<String>((ids.values.maxOrNull() ?: -1) + 1)
            for ((t, id) in ids) tokens[id] = t
            val ranks = HashMap<String, Int>()
            for (line in merges.lineSequence()) {
                if (line.isEmpty() || line.startsWith("#version")) continue
                ranks.putIfAbsent(line, ranks.size)
            }
            return BpeTokenizer(ids, tokens, ranks, split)
        }
    }
}

/** A reader for the one JSON shape of vocab.json: an object whose values are non-negative integers. Anything else throws IllegalArgumentException. */
internal object VocabJson {
    fun parse(text: String): Map<String, Int> {
        val out = HashMap<String, Int>(text.length / 12)
        var i = skip(text, 0)
        require(i < text.length && text[i] == '{') { "vocab.json is not an object" }
        i = skip(text, i + 1)
        if (i < text.length && text[i] == '}') return out
        while (true) {
            require(i < text.length && text[i] == '"') { "a key is expected" }
            val key = StringBuilder()
            i++
            while (true) {
                require(i < text.length) { "unterminated string" }
                val c = text[i++]
                if (c == '"') break
                if (c != '\\') { key.append(c); continue }
                require(i < text.length) { "unterminated escape" }
                when (val e = text[i++]) {
                    '"', '\\', '/' -> key.append(e)
                    'b' -> key.append('\b')
                    'f' -> key.append('\u000C')
                    'n' -> key.append('\n')
                    'r' -> key.append('\r')
                    't' -> key.append('\t')
                    'u' -> {
                        require(i + 4 <= text.length) { "short unicode escape" }
                        key.append(text.substring(i, i + 4).toInt(16).toChar())
                        i += 4
                    }
                    else -> throw IllegalArgumentException("unknown escape")
                }
            }
            i = skip(text, i)
            require(i < text.length && text[i] == ':') { "a colon is expected" }
            i = skip(text, i + 1)
            val start = i
            while (i < text.length && text[i].isDigit()) i++
            require(i > start) { "an id is expected" }
            out[key.toString()] = text.substring(start, i).toInt()
            i = skip(text, i)
            require(i < text.length) { "unterminated object" }
            if (text[i] == '}') return out
            require(text[i] == ',') { "a comma is expected" }
            i = skip(text, i + 1)
        }
    }

    private fun skip(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }
}
