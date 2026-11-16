// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.navigation

import java.text.BreakIterator
import java.util.Locale

/**
 * Grapheme-cluster arithmetic for the cursor features: a caret is placed only between clusters (never inside a surrogate pair,
 * a joined emoji, a flag, a letter with its combining marks), and a column is a count of clusters, not of UTF-16 units.
 */
internal object Graphemes {
    /** The longest cluster looked at per step when text is read around a caret, in chars. */
    const val STEP_WINDOW = 64

    private fun iteratorOver(text: String) = BreakIterator.getCharacterInstance(Locale.ROOT).also { it.setText(text) }

    /** True when [i] lies between two clusters of [text] (the ends included). */
    fun isBoundary(text: String, i: Int) = i in 0..text.length && iteratorOver(text).isBoundary(i)

    /** A test for many offsets of one text. */
    fun boundaryTest(text: String): (Int) -> Boolean {
        val it = iteratorOver(text)
        return { i -> i in 0..text.length && it.isBoundary(i) }
    }

    /** [i] moved left to the start of the cluster that contains it. */
    fun snapDown(text: String, i: Int): Int {
        val at = i.coerceIn(0, text.length)
        val it = iteratorOver(text)
        return if (it.isBoundary(at)) at else it.preceding(at)
    }

    /** [i] moved right to the end of the cluster that contains it. */
    fun snapUp(text: String, i: Int): Int {
        val at = i.coerceIn(0, text.length)
        val it = iteratorOver(text)
        return if (it.isBoundary(at)) at else it.following(at)
    }

    /** The number of clusters of [line] that end at or before offset [caret] of it (a caret inside a cluster counts the clusters before it). */
    fun columnOf(line: String, caret: Int): Int {
        val it = iteratorOver(line)
        var column = 0
        while (true) {
            val end = it.next()
            if (end == BreakIterator.DONE || end > caret) return column
            column++
        }
    }

    /** The offset in [line] after [column] clusters, or null when the line has fewer. */
    fun offsetOfColumn(line: String, column: Int): Int? {
        val clusters = iteratorOver(line)
        var offset = clusters.first()
        repeat(column) {
            offset = clusters.next()
            if (offset == BreakIterator.DONE) return null
        }
        return offset
    }

    /**
     * True when [read], asked for with [requested] chars, may end inside a cluster of the field: it is as long as asked for, or
     * shorter while the editor still has more ([readMore] asks for one char more than was returned). A read that stops where the
     * field ends is not cut. An editor that returns less than asked for whatever it holds cannot be told from the end of the
     * field, and its last cluster is taken for whole.
     */
    fun mayBeCut(read: CharSequence, requested: Int, readMore: (Int) -> CharSequence?): Boolean =
        read.length >= requested || (readMore(read.length + 1)?.length ?: 0) > read.length

    /**
     * Chars of the first [steps] clusters of [text]. [maybeCut] is true when [text] may end before the field text does: a last
     * cluster that reaches the end of such a text may be a part of a longer one, so it is not counted.
     */
    fun forward(text: CharSequence, steps: Int, maybeCut: Boolean): Int {
        val s = text.toString()
        val it = iteratorOver(s)
        var offset = it.first()
        var taken = 0
        while (taken < steps) {
            val next = it.next()
            if (next == BreakIterator.DONE) break
            if (maybeCut && next == s.length) break
            offset = next
            taken++
        }
        return offset
    }

    /** Chars of the last [steps] clusters of [text]; [maybeCut] as in [forward], for a first cluster that starts the text. */
    fun backward(text: CharSequence, steps: Int, maybeCut: Boolean): Int {
        val s = text.toString()
        val it = iteratorOver(s)
        var offset = it.last()
        var taken = 0
        while (taken < steps) {
            val previous = it.previous()
            if (previous == BreakIterator.DONE) break
            if (maybeCut && previous == 0) break
            offset = previous
            taken++
        }
        return s.length - offset
    }
}
