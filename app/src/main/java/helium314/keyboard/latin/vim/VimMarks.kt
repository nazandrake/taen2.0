// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

/**
 * The marks of one field (a to z, the visual marks < and >, the change marks [ and ], the last insert ^, and the context mark ' that
 * `` and '' jump to), in memory only, as (line, column) like Vim's. A mark is valid for the text it was set in ([base]): an edit made through
 * the layer moves the marks of the lines after it ([remap]); when the text is not the one the marks were set in (it was changed by the
 * keyboard or the app), every mark is forgotten ([forgetIfChanged]). Nothing here reads the field or keeps text other than [base].
 */
internal class VimMarks {
    private val marks = HashMap<Char, IntArray>()

    /** The text the marks belong to; null when there are none. */
    var base: String? = null
        private set

    fun isEmpty() = marks.isEmpty()

    fun clear() {
        marks.clear()
        base = null
    }

    /** Forgets all marks when [text] is not the text they were set in. True when something was forgotten. */
    fun forgetIfChanged(text: String): Boolean {
        val b = base ?: return false
        if (b == text) return false
        clear()
        return true
    }

    fun set(name: Char, text: String, offset: Int) {
        if (base != null && base != text) clear()
        base = text
        val line = lineOf(text, offset)
        marks[name] = intArrayOf(line, offset - lineStartOf(text, line))
    }

    /** The offset of mark [name] in [text], or -1 when it is not set (or the text is not the one it belongs to). The column is cut to the line. */
    fun get(name: Char, text: String): Int {
        val m = marks[name] ?: return -1
        if (base != text) return -1
        if (m[0] >= lineCount(text)) return -1
        val ls = lineStartOf(text, m[0])
        val le = text.indexOf('\n', ls).let { if (it < 0) text.length else it }
        return minOf(ls + m[1], le)
    }

    fun isSet(name: Char) = marks.containsKey(name)

    fun names(): List<Char> = marks.keys.sorted()

    /**
     * An edit turned [old] into [new]: the marks follow their lines (a line that is only changed keeps its marks, a deleted line loses them).
     * The two texts are compared line by line; lines that are equal at both ends of the text map to each other, and in the changed middle
     * the lines map by position as far as both sides have lines.
     */
    fun remap(old: String, new: String) {
        if (marks.isEmpty()) { base = null; return }
        if (base != old) { clear(); return }
        if (old == new) return
        val map = lineMap(old.split('\n'), new.split('\n'))
        val it = marks.entries.iterator()
        val moved = HashMap<Char, IntArray>()
        while (it.hasNext()) {
            val e = it.next()
            val target = if (e.value[0] < map.size) map[e.value[0]] else -1
            if (target >= 0) moved[e.key] = intArrayOf(target, e.value[1])
        }
        marks.clear()
        marks.putAll(moved)
        base = new
    }

    /**
     * The edit that replaced [start, end) of [old] with [replacement] is known, so the marks follow it exactly: a line wholly before it stays, a
     * line that starts at or after its end moves by the lines the edit added or removed, a line wholly inside it is gone (its marks with it), and
     * a line it only touches stays (a line it joins into the first one goes into that one).
     */
    fun remapEdit(old: String, start: Int, end: Int, replacement: String) {
        if (marks.isEmpty()) { base = null; return }
        if (base != old) { clear(); return }
        val removed = old.substring(start, end)
        val addedNl = replacement.count { it == '\n' }
        val removedNl = removed.count { it == '\n' }
        val delta = addedNl - removedNl
        val firstLine = lineOf(old, start)
        val moved = HashMap<Char, IntArray>()
        for ((name, m) in marks) {
            val ls = lineStartOf(old, m[0])
            val le = old.indexOf('\n', ls).let { if (it < 0) old.length else it }
            val covered = (start <= ls && end > le) || (le == old.length && start < ls && end >= le) || (le == old.length && ls == 0 && start == 0 && end >= le)
            val target = when {
                le < start -> m[0]
                ls >= end -> m[0] + delta
                addedNl >= removedNl -> m[0] // no line is lost: the lines are changed in place
                covered -> -1
                m[0] == firstLine -> m[0]
                else -> firstLine
            }
            if (target >= 0) moved[name] = intArrayOf(target, m[1])
        }
        marks.clear()
        marks.putAll(moved)
        base = old.substring(0, start) + replacement + old.substring(end)
    }

    companion object {
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
}
