// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

/** Line arithmetic on a text that has its lines joined by "\n": pure functions of their arguments, nothing is kept. */
internal object VimLines {
    fun lineOf(text: String, offset: Int): Int {
        var n = 0
        var i = text.indexOf('\n')
        while (i in 0 until offset) { n++; i = text.indexOf('\n', i + 1) }
        return n
    }

    fun lineStartOf(text: String, line: Int): Int {
        var start = 0
        repeat(line) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) return text.length
            start = nl + 1
        }
        return start
    }

    fun lineCount(text: String): Int = text.count { it == '\n' } + 1

    /** For each line of [old] the index of the line it became in [new], or -1 when the line is gone. */
    fun lineMap(old: List<String>, new: List<String>): IntArray {
        val map = IntArray(old.size) { -1 }
        var head = 0
        while (head < old.size && head < new.size && old[head] == new[head]) { map[head] = head; head++ }
        var tail = 0
        while (tail < old.size - head && tail < new.size - head && old[old.size - 1 - tail] == new[new.size - 1 - tail]) {
            map[old.size - 1 - tail] = new.size - 1 - tail
            tail++
        }
        val oldMid = old.size - head - tail
        val newMid = new.size - head - tail
        if (oldMid <= 0 || newMid <= 0) return map
        if (oldMid.toLong() * newMid <= 40_000L) { // the longest common subsequence of the changed middle; lines between its pairs map by position
            val lcs = Array(oldMid + 1) { IntArray(newMid + 1) }
            for (i in oldMid - 1 downTo 0) for (j in newMid - 1 downTo 0)
                lcs[i][j] = if (old[head + i] == new[head + j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            var i = 0
            var j = 0
            var gapOld = 0
            var gapNew = 0
            fun flush() {
                for (k in 0 until minOf(gapOld, gapNew)) map[head + i - gapOld + k] = head + j - gapNew + k
                gapOld = 0
                gapNew = 0
            }
            while (i < oldMid && j < newMid) {
                if (old[head + i] == new[head + j]) { flush(); map[head + i] = head + j; i++; j++ }
                else if (lcs[i + 1][j] >= lcs[i][j + 1]) { i++; gapOld++ }
                else { j++; gapNew++ }
            }
            gapOld += oldMid - i
            gapNew += newMid - j
            i = oldMid
            j = newMid
            flush()
        } else {
            for (k in 0 until minOf(oldMid, newMid)) map[head + k] = head + k // too many lines to compare: by position
        }
        return map
    }
}
