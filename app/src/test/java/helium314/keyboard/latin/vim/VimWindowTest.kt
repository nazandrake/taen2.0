// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rule of [VimWindow]: the field text is whole and fits the window exactly when
 *  (1) the editor gave text with startOffset 0 that is at most MAX_CHARS long,
 *  (2) none of the three reads (before the selection, after it, the selected text) returned more than MAX_CHARS characters, and
 *  (3) the selected text is exactly the text of the selection in that text, and the two bounded reads are exactly the text before and after
 *      the selection in it, either as the platform editors answer for a reversed selection (up to the lower end, from the higher end) or as it
 *      is reported (up to the reported start, from the reported end).
 * Then the answer is Text(field, lower end, higher end); in every other case the read is refused. TooLong comes only from (1) or (2).
 * Why that is enough: the text has at most MAX_CHARS characters, a bounded read of MAX_CHARS + 1 returns a whole side whenever the side has at
 * most MAX_CHARS + 1 characters, so a side that the editor holds more of than the text shows can only be answered by a different string; the
 * selected text covers what lies between the two sides, so an editor that cut its text and clamped its selection into it is caught as well.
 *
 * The table below is generated: field lengths around the window, content with surrogate pairs at the ends, at the window edge and across
 * the cursor, every pair of selection points (collapsed, forward, reversed), both editor conventions and editors that cut, shift, offset or
 * shorten their answers. The expected outcome of a row comes from the rule above applied to what the editor said and to the true field.
 */
class VimWindowTest {
    private val max = VimEngine.MAX_CHARS
    private val pair = "😀"

    private enum class Convention { PLATFORM, REPORTED }
    /** CUT_CLAMPED: the text is cut to the window and the selection it reports is clamped into the cut text (the reads still know the real cursor). */
    private enum class Behaviour { WHOLE, HINT_HONORED, CUT_AT_WINDOW, CUT_CLAMPED, ONE_SHORT, SHIFTED, OFFSET_5, READS_ONE_SHORT, READS_SHIFTED_CURSOR, SELECTED_NULL }
    /** PERIODIC: one repeated character, so that a cut or shifted answer can equal the text it is cut from. */
    private enum class Content { PLAIN, PERIODIC, PAIR_AT_START, PAIR_AT_END, PAIR_AT_WINDOW_EDGE }

    private data class Row(val length: Int, val content: Content, val selStart: Int, val selEnd: Int, val convention: Convention, val behaviour: Behaviour)

    private fun field(length: Int, content: Content): String? {
        val sb = StringBuilder(length)
        for (i in 0 until length) sb.append('a' + i % 26)
        fun put(at: Int): Boolean { if (at < 0 || at + 2 > length) return false; sb.setCharAt(at, pair[0]); sb.setCharAt(at + 1, pair[1]); return true }
        return when (content) {
            Content.PLAIN -> sb.toString()
            Content.PERIODIC -> "x".repeat(length)
            Content.PAIR_AT_START -> if (put(0)) sb.toString() else null
            Content.PAIR_AT_END -> if (put(length - 2)) sb.toString() else null
            Content.PAIR_AT_WINDOW_EDGE -> if (length > max && put(max - 1)) sb.toString() else null // a cut at the window splits the pair
        }
    }

    private fun points(length: Int) = setOf(0, minOf(1, length), length / 2, maxOf(length - 1, 0), length).toList().sorted()

    private val lengths = listOf(0, 1, 2, 3, max - 1, max, max + 1, max + 2)

    private val rows: List<Row> = buildList {
        for (length in lengths) for (content in Content.entries) {
            if (field(length, content) == null) continue
            val p = points(length)
            for (s in p) for (e in p) for (c in Convention.entries) for (b in Behaviour.entries) add(Row(length, content, s, e, c, b))
        }
    }

    /** What the editor answers for a row: the extracted text, its start offset and selection, and the two bounded reads. */
    private class Answers(val extracted: String, val startOffset: Int, val selStart: Int, val selEnd: Int, val before: String, val after: String, val selected: String?)

    private fun answersFor(row: Row, truth: String): Answers {
        val lo = minOf(row.selStart, row.selEnd)
        val hi = maxOf(row.selStart, row.selEnd)
        val beforeEnd = if (row.convention == Convention.PLATFORM) lo else row.selStart
        val afterStart = if (row.convention == Convention.PLATFORM) hi else row.selEnd
        var before = truth.substring(0, beforeEnd).takeLast(max + 1)
        var after = truth.substring(afterStart).take(max + 1)
        var selected: String? = truth.substring(lo, hi)
        var extracted = truth
        var offset = 0
        var selStart = row.selStart
        var selEnd = row.selEnd
        when (row.behaviour) {
            Behaviour.WHOLE -> {}
            Behaviour.HINT_HONORED -> extracted = truth.take(max + 1)
            Behaviour.CUT_AT_WINDOW -> extracted = truth.take(max)
            Behaviour.CUT_CLAMPED -> { extracted = truth.take(max); selStart = minOf(selStart, extracted.length); selEnd = minOf(selEnd, extracted.length) }
            Behaviour.ONE_SHORT -> extracted = truth.dropLast(1)
            Behaviour.SHIFTED -> extracted = truth.drop(1)
            Behaviour.OFFSET_5 -> offset = 5
            Behaviour.READS_ONE_SHORT -> { before = before.drop(1); after = after.dropLast(1); selected = selected?.dropLast(1) }
            Behaviour.SELECTED_NULL -> selected = null
            Behaviour.READS_SHIFTED_CURSOR -> { // the reads are for a cursor one place to the right (or left at the end)
                val shift = if (row.selStart < truth.length && row.selEnd < truth.length) 1 else -1
                if (row.selStart + shift >= 0 && row.selEnd + shift >= 0 && row.selStart + shift <= truth.length && row.selEnd + shift <= truth.length) {
                    val s2 = row.selStart + shift
                    val e2 = row.selEnd + shift
                    before = truth.substring(0, if (row.convention == Convention.PLATFORM) minOf(s2, e2) else s2).takeLast(max + 1)
                    after = truth.substring(if (row.convention == Convention.PLATFORM) maxOf(s2, e2) else e2).take(max + 1)
                    selected = truth.substring(minOf(s2, e2), maxOf(s2, e2))
                }
            }
        }
        return Answers(extracted, offset, selStart, selEnd, before, after, selected)
    }

    private fun editorFor(a: Answers) = object : VimEditorReads {
        override fun extracted(hintMaxChars: Int) = VimExtracted(a.extracted, a.startOffset, a.selStart, a.selEnd)
        override fun before(n: Int): CharSequence = a.before.takeLast(n)
        override fun after(n: Int): CharSequence = a.after.take(n)
        override fun selected(): CharSequence? = a.selected
    }

    /** The rule, written out on its own: null when the row is whole and fits, otherwise what kind of refusal the rule names (TOO_LONG, PARTIAL). */
    private fun expectedRefusal(row: Row, truth: String, a: Answers): String? {
        val lo = minOf(a.selStart, a.selEnd)
        val hi = maxOf(a.selStart, a.selEnd)
        if (a.startOffset != 0) return "PARTIAL"
        if (a.extracted.length > max) return "TOO_LONG"
        if (lo < 0 || hi > a.extracted.length) return "PARTIAL"
        val selected = a.selected ?: "" // no selected text is the same as an empty one
        if (a.before.length > max || a.after.length > max || selected.length > max) return "TOO_LONG"
        if (selected != a.extracted.substring(lo, hi)) return "PARTIAL"
        val platform = a.before == a.extracted.substring(0, lo) && a.after == a.extracted.substring(hi)
        val reported = a.before == a.extracted.substring(0, a.selStart) && a.after == a.extracted.substring(a.selEnd)
        if (!platform && !reported) return "PARTIAL"
        // the reads match the extracted text: it must be the field itself (the table's editors only differ by cutting or shifting)
        check(a.extracted == truth) { "a row whose reads match a text that is not the field: $row" }
        return null
    }

    private fun checkRow(row: Row): String? {
        val truth = field(row.length, row.content)!!
        val a = answersFor(row, truth)
        val read = VimWindow.read(editorFor(a), max)
        val refusal = expectedRefusal(row, truth, a)
        val lo = minOf(row.selStart, row.selEnd)
        val hi = maxOf(row.selStart, row.selEnd)
        if (refusal == null) {
            // positive: the exact edit window, and the text fits (a field of up to MAX_CHARS is never refused)
            check(truth.length <= max) { "the rule accepts a field longer than the window: $row" }
            return if (read is VimRead.Text && read.text == truth && read.selStart == lo && read.selEnd == hi) null else "expected Text($lo, $hi) got ${describe(read)}: $row"
        }
        val kind = when (read) {
            is VimRead.Text -> return "expected refusal $refusal got Text: $row"
            VimRead.TooLong -> "TOO_LONG"
            VimRead.Partial -> "PARTIAL"
            VimRead.Unreadable -> return "expected refusal $refusal got Unreadable: $row"
        }
        return if (kind == refusal) null else "expected $refusal got $kind: $row"
    }

    private fun describe(r: VimRead) = when (r) { is VimRead.Text -> "Text(${r.selStart}, ${r.selEnd})"; else -> r.toString() }

    @Test fun theTableHasTheRowsItClaims() {
        val honest = rows.filter { it.behaviour == Behaviour.WHOLE }
        assertTrue(rows.size > 5000, "rows: ${rows.size}")
        for (length in lengths) assertTrue(honest.any { it.length == length }, "no row of length $length")
        assertTrue(rows.any { it.selStart > it.selEnd }, "reversed rows")
        assertTrue(rows.any { it.selStart == it.selEnd }, "collapsed rows")
        assertTrue(rows.any { it.selStart < it.selEnd }, "forward rows")
        assertTrue(rows.any { it.length == max + 2 && it.content == Content.PAIR_AT_WINDOW_EDGE }, "a pair across the window edge")
        println("VimWindowTest rows: ${rows.size}")
    }

    @Test fun everyRowOfTheTableFollowsTheRule() {
        val failures = rows.mapNotNull { checkRow(it) }
        assertEquals(emptyList(), failures.take(10), "${failures.size} of ${rows.size} rows fail")
    }

    /** The row of a field of exactly the window with a reversed selection (1, 0), answered as reported: it is one row of the table, and it is edited. */
    @Test fun aFieldOfExactlyTheWindowWithAReversedSelectionIsRead() {
        val row = Row(max, Content.PLAIN, 1, 0, Convention.REPORTED, Behaviour.WHOLE)
        assertTrue(row in rows, "the row is in the table")
        assertEquals(null, checkRow(row))
        val truth = field(max, Content.PLAIN)!!
        val read = VimWindow.read(editorFor(answersFor(row, truth)), max)
        assertTrue(read is VimRead.Text, "got $read")
        assertEquals(truth, read.text)
        assertEquals(0 to 1, read.selStart to read.selEnd)
    }

    /**
     * An editor that cuts its text to the window and clamps its selection into the cut text, with the whole field selected: the text before and
     * after the selection are both empty and match, so only the selected text shows that a character is missing. One row of the table.
     */
    @Test fun aCutTextWithAClampedSelectionOverTheWholeFieldIsRefused() {
        val row = Row(max + 1, Content.PERIODIC, 0, max + 1, Convention.PLATFORM, Behaviour.CUT_CLAMPED)
        assertTrue(row in rows, "the row is in the table")
        assertEquals(null, checkRow(row))
        val truth = field(max + 1, Content.PERIODIC)!!
        assertEquals(VimRead.TooLong, VimWindow.read(editorFor(answersFor(row, truth)), max))
        // positive control: the same editor with a field that fits is read whole, the whole field selected
        val fits = Row(max, Content.PERIODIC, 0, max, Convention.PLATFORM, Behaviour.CUT_CLAMPED)
        val read = VimWindow.read(editorFor(answersFor(fits, field(max, Content.PERIODIC)!!)), max)
        assertTrue(read is VimRead.Text, "got $read")
        assertEquals(0 to max, read.selStart to read.selEnd)
    }

    /** Positive control of the refusals: the same field one character longer is refused as too long, and an editor with no text gives none. */
    @Test fun aFieldOneLongerIsTooLongAndNoTextIsUnreadable() {
        val row = Row(max + 1, Content.PLAIN, 0, 0, Convention.PLATFORM, Behaviour.WHOLE)
        val truth = field(max + 1, Content.PLAIN)!!
        assertEquals(VimRead.TooLong, VimWindow.read(editorFor(answersFor(row, truth)), max))
        val none = object : VimEditorReads {
            override fun extracted(hintMaxChars: Int) = VimExtracted(null, 0, 0, 0)
            override fun before(n: Int): CharSequence = ""
            override fun after(n: Int): CharSequence = ""
            override fun selected(): CharSequence? = null
        }
        assertEquals(VimRead.Unreadable, VimWindow.read(none, max))
        val noAnswer = object : VimEditorReads {
            override fun extracted(hintMaxChars: Int): VimExtracted? = null
            override fun before(n: Int): CharSequence = ""
            override fun after(n: Int): CharSequence = ""
            override fun selected(): CharSequence? = null
        }
        assertEquals(VimRead.Unreadable, VimWindow.read(noAnswer, max))
        val noReads = object : VimEditorReads {
            override fun extracted(hintMaxChars: Int) = VimExtracted("abc", 0, 1, 1)
            override fun before(n: Int): CharSequence? = null
            override fun after(n: Int): CharSequence = "bc"
            override fun selected(): CharSequence? = null
        }
        assertEquals(VimRead.Unreadable, VimWindow.read(noReads, max))
        // positive control of the three Unreadable answers: the same text with every read present is read
        val all = object : VimEditorReads {
            override fun extracted(hintMaxChars: Int) = VimExtracted("abc", 0, 1, 1)
            override fun before(n: Int): CharSequence = "a"
            override fun after(n: Int): CharSequence = "bc"
            override fun selected(): CharSequence? = null
        }
        val read = VimWindow.read(all, max)
        assertTrue(read is VimRead.Text, "got $read")
        assertEquals(Triple("abc", 1, 1), Triple(read.text, read.selStart, read.selEnd))
    }
}
