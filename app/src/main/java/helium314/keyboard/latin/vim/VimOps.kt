// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

/**
 * Pure text transformations of the Vim commands that do more than cut and paste: join, case change, shift, increment and decrement. Each
 * works on strings and returns the new text; the engine turns that into one text change. Ported from Vim's do_join, op_tilde, shift_line
 * and do_addsub; the golden table compares them with the real vim.
 */
internal object VimOps {
    /** The width of a tab when the indent of a line is measured and written (Vim's 'tabstop'; the shift width is 4). */
    const val TAB = 4
    const val SHIFT_WIDTH = 4

    // ---- join -------------------------------------------------------------------------------------------------------------

    /** The result of a join: the new line and the cursor column in it. */
    class Joined(val line: String, val cursor: Int)

    /** Vim's J ([insertSpace]) and gJ over [lines] (the first is the current line). 'joinspaces' is off. */
    fun join(lines: List<String>, insertSpace: Boolean): Joined {
        val sb = StringBuilder(lines[0])
        var endcurr1 = if (insertSpace && lines[0].isNotEmpty()) lines[0].last() else '\u0000'
        var cursor = 0
        for (t in 1 until lines.size) {
            var curr = lines[t]
            var spaces = 0
            if (insertSpace) {
                curr = curr.trimStart(' ', '\t')
                if (curr.isNotEmpty() && curr[0] != ')' && sb.isNotEmpty() && endcurr1 != '\t' && endcurr1 != ' ') spaces = 1
            }
            cursor = sb.length
            for (k in 0 until spaces) sb.append(' ')
            sb.append(curr)
            endcurr1 = if (insertSpace && curr.isNotEmpty()) curr.last() else '\u0000'
        }
        return Joined(sb.toString(), cursor)
    }

    // ---- case ---------------------------------------------------------------------------------------------------------------

    /** Changes the case of [text]: '~' toggles, 'u' lower, 'U' upper. One code point stays one code point. */
    fun changeCase(text: String, kind: Char): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val out = when (kind) {
                'u' -> Character.toLowerCase(cp)
                'U' -> Character.toUpperCase(cp)
                else -> if (Character.isLowerCase(cp)) Character.toUpperCase(cp) else if (Character.isUpperCase(cp)) Character.toLowerCase(cp) else cp
            }
            sb.appendCodePoint(out)
        }
        return sb.toString()
    }

    // ---- shift --------------------------------------------------------------------------------------------------------------

    /** The width of the indent of [line]: blanks and tabs, a tab going to the next multiple of [TAB]. */
    fun indentWidth(line: String): Int {
        var w = 0
        for (ch in line) {
            if (ch == ' ') w++ else if (ch == '\t') w += TAB - w % TAB else break
        }
        return w
    }

    /** The length of the indent (the blanks and tabs at the start) of [line]. */
    fun indentLength(line: String): Int {
        var n = 0
        while (n < line.length && (line[n] == ' ' || line[n] == '\t')) n++
        return n
    }

    /** [line] shifted right ([left] false) or left by [amount] shift widths; [tabs] writes the indent with tabs (the setting), else with spaces. */
    fun shiftLine(line: String, left: Boolean, amount: Int, tabs: Boolean): String {
        if (line.isEmpty()) return line
        val width = indentWidth(line)
        val target = maxOf(0, width + (if (left) -1 else 1) * SHIFT_WIDTH * amount)
        val indent = if (tabs) "\t".repeat(target / TAB) + " ".repeat(target % TAB) else " ".repeat(target)
        return indent + line.substring(indentLength(line))
    }

    // ---- increment ----------------------------------------------------------------------------------------------------------

    /** The result of a ctrl-a or ctrl-x: the new line and the column of the last character of the number. */
    class Added(val line: String, val cursor: Int)

    private fun isDigit(c: Char) = c in '0'..'9'
    private fun isBin(c: Char) = c == '0' || c == '1'
    private fun isHex(c: Char) = isDigit(c) || c in 'a'..'f' || c in 'A'..'F'

    /**
     * Vim's do_addsub with 'nrformats' bin,hex: the number at or after column [col0] of [line] gets [delta] added. In Visual mode ([visual]) it is
     * the first number that starts inside [lo, hi): Vim does not look back for the start of a number there (so "1f" of "0x1f", selected from the
     * 1, is the decimal number 1), and a minus sign counts only inside the selection. Null when there is no number (Vim beeps) or it is too long.
     */
    fun addSub(line: String, col0: Int, delta: Long, visual: Boolean, lo: Int = 0, hi: Int = line.length): Added? {
        var col = col0
        val n = line.length
        fun at(i: Int): Char = if (i in 0 until n) line[i] else '\u0000'
        if (visual) {
            col = lo
            while (col < hi && col < n && !isDigit(at(col))) col++
            if (col >= hi || col >= n) return null
        } else {
            // on a hexadecimal or binary number: move to its start
            while (col > 0 && isBin(at(col))) col--
            while (col > 0 && isHex(at(col))) col--
            val hexAt = col > 0 && (at(col) == 'x' || at(col) == 'X') && at(col - 1) == '0' && isHex(at(col + 1))
            if (!hexAt) {
                col = col0
                while (col > 0 && isDigit(at(col))) col--
            }
            val hexNow = col > 0 && (at(col) == 'x' || at(col) == 'X') && at(col - 1) == '0' && isHex(at(col + 1))
            val binNow = col > 0 && (at(col) == 'b' || at(col) == 'B') && at(col - 1) == '0' && isBin(at(col + 1))
            if (hexNow || binNow) col--
            else {
                col = col0
                while (col < n && !isDigit(at(col))) col++
                while (col > 0 && isDigit(at(col - 1))) col--
            }
            if (!isDigit(at(col))) return null
        }
        val limit = if (visual) minOf(hi, n) else n
        var pre = '\u0000'
        var base = 10
        var digitsStart = col
        if (at(col) == '0' && col + 2 < limit + 1 && (at(col + 1) == 'x' || at(col + 1) == 'X') && isHex(at(col + 2)) && col + 2 < limit) { base = 16; pre = at(col + 1); digitsStart = col + 2 }
        else if (at(col) == '0' && (at(col + 1) == 'b' || at(col + 1) == 'B') && isBin(at(col + 2)) && col + 2 < limit) { base = 2; pre = at(col + 1); digitsStart = col + 2 }
        var end = digitsStart
        while (end < limit && (if (base == 16) isHex(at(end)) else if (base == 2) isBin(at(end)) else isDigit(at(end)))) end++
        if (end <= digitsStart || (base == 10 && end - digitsStart > 18) || (base == 16 && end - digitsStart > 16) || (base == 2 && end - digitsStart > 64)) return null
        var negative = false
        var start = col
        if (base == 10 && col > 0 && at(col - 1) == '-' && (!visual || col - 1 >= lo)) {
            negative = true
            start = col - 1
        }
        val text = line.substring(digitsStart, end)
        var value = 0L
        for (ch in text) value = value * base + (if (ch <= '9') ch - '0' else (ch.lowercaseChar() - 'a') + 10)
        val firstDigit = at(col)
        val totalLength = end - col // the length of the number with its prefix, without the sign
        var neg = negative
        val result: Long
        if (base == 10) {
            var signed = if (neg) -value else value
            signed += delta
            neg = signed < 0
            result = if (neg) -signed else signed
        } else {
            result = value + delta // unsigned and 64 bits wide: below zero it wraps around, as in Vim
        }
        val sb = StringBuilder()
        if (neg) sb.append('-')
        var length = totalLength
        if (base != 10) { sb.append('0').append(pre); length -= 2 }
        val digits: String = when (base) {
            16 -> {
                val upper = text.any { it in 'A'..'F' }
                val h = hex(result)
                if (upper) h.uppercase() else h
            }
            2 -> bin(result)
            else -> result.toString()
        }
        length -= digits.length
        if (firstDigit == '0') while (length-- > 0) sb.append('0')
        sb.append(digits)
        val newLine = line.substring(0, start) + sb + line.substring(end)
        return Added(newLine, start + sb.length - 1)
    }

    private fun hex(v: Long): String {
        if (v == 0L) return "0"
        val sb = StringBuilder()
        var x = v
        while (x != 0L) { sb.append("0123456789abcdef"[(x and 15).toInt()]); x = x ushr 4 }
        return sb.reverse().toString()
    }

    private fun bin(v: Long): String {
        if (v == 0L) return "0"
        val sb = StringBuilder()
        var x = v
        while (x != 0L) { sb.append(if (x and 1L == 1L) '1' else '0'); x = x ushr 1 }
        return sb.reverse().toString()
    }
}
