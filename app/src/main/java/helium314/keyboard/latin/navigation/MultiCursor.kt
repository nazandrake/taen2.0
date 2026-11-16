// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.navigation

import helium314.keyboard.latin.common.lastGrapheme
import java.security.MessageDigest

/** A range of the field text, [start] inclusive, [end] exclusive; a caret when both are equal. */
data class TextRange(val start: Int, val end: Int)

/** What [MultiCursor] needs from the text field. */
interface FieldPort {
    /** The text the editor gives (ExtractedText), or null when it gives none. */
    fun extractedText(maxChars: Int): FieldText?
    fun beginBatchEdit()
    fun endBatchEdit()
    fun setSelection(start: Int, end: Int)
    fun commitText(text: CharSequence)
}

/** The extracted text of a field: [text] starts at [startOffset] of the whole field. */
class FieldText(val text: CharSequence, val startOffset: Int)

/** The outcome of a [MultiCursor] command, for the message the keyboard shows. */
sealed interface MultiCursorResult {
    /** [selected] ranges are held; [total] occurrences of the text exist in the field (0 when not counted). */
    data class Ranges(val selected: Int, val total: Int) : MultiCursorResult
    data class AllSelected(val count: Int) : MultiCursorResult
    data class Lines(val carets: Int) : MultiCursorResult
    enum class Refused : MultiCursorResult { NOT_ALLOWED, NO_TEXT, PARTIAL_TEXT, TOO_LONG, CHANGED, TOO_MANY, NOTHING_TO_MATCH, NO_MORE_LINES }
    data object Written : MultiCursorResult
    data object Undone : MultiCursorResult
}

/**
 * Several cursors in one field: "select next occurrence" (like Ctrl+G in an IDE) and carets at the same column on consecutive
 * lines. A field has one selection, so only the newest range is visible; the others are held here, in memory only, and dropped
 * when the field changes, the screen locks, or the user moves the cursor. An edit replaces the region from the first to the last
 * range with one commit inside one batch edit, after checking that the region still holds what it held when the ranges were
 * made. The field text is read only when [allowed] says so (never in password or incognito fields) and is never kept beyond the
 * region of the current ranges and the undo of the last edit, which ends after [UNDO_MILLIS]. The undo also keeps a SHA-256 digest of
 * the whole field as the edit left it (no text): any other change of the field, wherever it is, makes the undo unavailable.
 */
class MultiCursor(private val port: FieldPort, private val clock: () -> Long, private val allowed: () -> Boolean) {
    private var ranges: List<TextRange> = emptyList()
    private var needle: String? = null
    private var wholeWord = false
    private var regionText: String? = null
    private var columnOrigin = -1
    private var columnLines = 0
    private var undo: UndoRecord? = null
    private val ownSelections = HashSet<TextRange>()
    private var editedInSession = false

    private class UndoRecord(val start: Int, val original: String, val written: String, val time: Long, val fieldDigest: ByteArray) {
        /** True while [text] is the whole field exactly as the edit left it. */
        fun holds(text: String) = digestOf(text).contentEquals(fieldDigest)
    }

    /** True while more than one range is held: typed text goes to all of them. */
    val isActive get() = ranges.size > 1

    val heldRanges: List<TextRange> get() = ranges

    /** Adds the next occurrence of the selected text, or of the word at the caret (whole words only). */
    fun addNextOccurrence(selStart: Int, selEnd: Int): MultiCursorResult {
        if (columnOrigin >= 0) clear()
        val text = read().let { it as? String ?: return it as MultiCursorResult }
        if (ranges.isEmpty()) {
            val first = if (selStart == selEnd) wordAt(text, selStart) else TextRange(minOf(selStart, selEnd), maxOf(selStart, selEnd))
            if (first == null || first.start == first.end || first.end > text.length) return MultiCursorResult.Refused.NOTHING_TO_MATCH
            needle = text.substring(first.start, first.end)
            wholeWord = selStart == selEnd
            ownSelections.add(TextRange(minOf(selStart, selEnd), maxOf(selStart, selEnd))) // a late report of the selection before
            setRanges(text, listOf(first), first)
            return MultiCursorResult.Ranges(1, countAll(text))
        }
        val held = regionText
        if (held == null || text.regionOf(ranges) != held) {
            clear()
            return MultiCursorResult.Refused.CHANGED
        }
        if (ranges.size >= MAX_RANGES) return MultiCursorResult.Refused.TOO_MANY
        val next = findNext(text, ranges.maxBy { it.end }.end) ?: return MultiCursorResult.AllSelected(ranges.size)
        setRanges(text, ranges + next, next)
        return MultiCursorResult.Ranges(ranges.size, countAll(text))
    }

    /**
     * Adds a caret on the next line at the column of the first caret (the characters after the previous line break). A line
     * shorter than the column gets no caret: lines are never padded.
     */
    fun addCaretOnNextLine(selStart: Int, selEnd: Int): MultiCursorResult {
        if (needle != null || (columnOrigin < 0 && ranges.isNotEmpty())) clear() // carets of an ended column start again from the caret
        val text = read().let { it as? String ?: return it as MultiCursorResult }
        if (columnOrigin < 0) {
            if (selStart != selEnd) return MultiCursorResult.Refused.NOTHING_TO_MATCH
            columnOrigin = selStart
            columnLines = 1
            ownSelections.add(TextRange(selStart, selStart)) // a late report of the caret before
        } else {
            val held = regionText
            if (held == null || text.regionOf(ranges) != held) {
                clear()
                return MultiCursorResult.Refused.CHANGED
            }
        }
        val lineStarts = lineStarts(text, columnOrigin, columnLines + 1)
        if (lineStarts.size <= columnLines) {
            if (ranges.isEmpty()) clear() // a first caret with no line below is no column
            return MultiCursorResult.Refused.NO_MORE_LINES
        }
        if (columnLines >= MAX_RANGES) return MultiCursorResult.Refused.TOO_MANY
        columnLines++
        val carets = columnCarets(text, columnOrigin, columnLines).map { TextRange(it, it) }
        setRanges(text, carets, carets.last())
        return MultiCursorResult.Lines(carets.size)
    }

    /** Replaces every held range with [insert]; afterwards each range is a caret after its insertion. */
    fun type(insert: String): MultiCursorResult = edit { text, r -> Edit(r.start, r.end, insert) }

    /** Deletes every non-empty range, or the whole grapheme before every caret (an emoji sequence, a flag or a letter with its accents is one). */
    fun deleteBackward(): MultiCursorResult = edit { text, r ->
        if (r.start != r.end) Edit(r.start, r.end, "")
        else if (r.start == 0) Edit(0, 0, "")
        else Edit(r.start - text.substring(maxOf(0, r.start - GRAPHEME_WINDOW), r.start).lastGrapheme.length, r.start, "")
    }

    /** Restores the region of the last edit, only while the field still holds exactly what the edit wrote. */
    fun undoLastEdit(): MultiCursorResult? {
        val record = undo ?: return null
        undo = null
        if (clock() - record.time > UNDO_MILLIS || !allowed()) return null
        val text = read() as? String ?: return null
        val end = record.start + record.written.length
        if (!record.holds(text) || end > text.length) return null
        write(record.start, end, record.original, record.start + record.original.length)
        clear()
        return MultiCursorResult.Undone
    }

    /** The field reports its selection: one these ranges did not set means the user moved the cursor, so the ranges are dropped. */
    fun onSelectionChanged(start: Int, end: Int) {
        if (ranges.isNotEmpty() && TextRange(minOf(start, end), maxOf(start, end)) !in ownSelections) clear()
    }

    /** Drops the ranges (a cursor move by the user, a refused edit); the undo of the last edit stays. */
    fun clear() {
        ownSelections.clear()
        editedInSession = false
        ranges = emptyList()
        needle = null
        wholeWord = false
        regionText = null
        columnOrigin = -1
        columnLines = 0
    }

    /** Drops everything, the undo included (field change, screen lock). */
    fun forgetAll() {
        clear()
        undo = null
    }

    /** True while the last edit can still be undone by time; [undoLastEdit] also checks the field. */
    fun hasUndo() = undo?.let { clock() - it.time <= UNDO_MILLIS } == true

    private class Edit(val start: Int, val end: Int, val insert: String)

    private fun edit(change: (String, TextRange) -> Edit): MultiCursorResult {
        if (!isActive) return MultiCursorResult.Refused.NOTHING_TO_MATCH
        val text = read().let { it as? String ?: return it.also { clear() } as MultiCursorResult }
        val held = regionText
        if (held == null || text.regionOf(ranges) != held) {
            clear()
            return MultiCursorResult.Refused.CHANGED
        }
        val edits = ranges.map { change(text, it) }
        val regionStart = ranges.first().start
        val regionEnd = ranges.last().end
        val start = minOf(regionStart, edits.first().start)
        val end = maxOf(regionEnd, edits.last().end)
        val out = StringBuilder()
        val carets = ArrayList<TextRange>()
        var pos = start
        for (e in edits) {
            if (e.start < pos) continue // two deletions that would overlap: keep the first
            out.append(text, pos, e.start)
            out.append(e.insert)
            val caret = start + out.length
            carets.add(TextRange(caret, caret))
            pos = e.end
        }
        out.append(text, pos, end)
        val written = out.toString()
        write(start, end, written, carets.last().start)
        undo = undoRecordFor(text, start, end, written)
        editedInSession = true
        if (columnOrigin >= 0) {
            // the column the carets now sit at; a change of the line structure ends the column mode (the carets stay for typing)
            if (text.substring(start, end).count { it == '\n' } == written.count { it == '\n' }) columnOrigin = carets.first().start
            else { columnOrigin = -1; columnLines = 0 }
        }
        ranges = carets
        regionText = written.substring(carets.first().start - start, carets.last().end - start)
        return MultiCursorResult.Written
    }

    /**
     * The undo record after replacing [start, end) of [text] with [written]. Consecutive edits of one set of cursors (typing a word
     * letter by letter) are one record, so an undo restores the region as it was before the first of them.
     */
    private fun undoRecordFor(text: String, start: Int, end: Int, written: String): UndoRecord {
        val prev = undo
        val after = text.substring(0, start) + written + text.substring(end)
        if (prev == null || !editedInSession || !prev.holds(text))
            return UndoRecord(start, text.substring(start, end), written, clock(), digestOf(after))
        val prevEnd = prev.start + prev.written.length
        val from = minOf(start, prev.start)
        val to = maxOf(end, prevEnd)
        val original = text.substring(from, prev.start) + prev.original + text.substring(prevEnd, to)
        val region = text.substring(from, start) + written + text.substring(end, to)
        return UndoRecord(from, original, region, clock(), digestOf(after))
    }

    private fun write(start: Int, end: Int, replacement: String, caret: Int) {
        ownSelections.add(TextRange(start, end))
        ownSelections.add(TextRange(start + replacement.length, start + replacement.length))
        ownSelections.add(TextRange(caret, caret))
        port.beginBatchEdit()
        try {
            port.setSelection(start, end)
            port.commitText(replacement)
            port.setSelection(caret, caret)
        } finally {
            port.endBatchEdit()
        }
    }

    /** The whole field text as a String, or the refusal. */
    private fun read(): Any {
        if (!allowed()) return MultiCursorResult.Refused.NOT_ALLOWED
        val field = port.extractedText(MAX_CHARS + 1) ?: return MultiCursorResult.Refused.NO_TEXT
        if (field.startOffset != 0) return MultiCursorResult.Refused.PARTIAL_TEXT
        if (field.text.length > MAX_CHARS) return MultiCursorResult.Refused.TOO_LONG
        return field.text.toString()
    }

    private fun setRanges(text: String, newRanges: List<TextRange>, visible: TextRange) {
        editedInSession = false
        ranges = newRanges.sortedBy { it.start }
        regionText = text.regionOf(ranges)
        ownSelections.add(visible)
        port.setSelection(visible.start, visible.end)
    }

    private fun findNext(text: String, from: Int): TextRange? {
        val n = needle ?: return null
        for (start in occurrences(text, n) .let { all -> all.filter { it >= from } + all.filter { it < from } }) {
            val r = TextRange(start, start + n.length)
            if (ranges.none { it.start < r.end && r.start < it.end }) return r
        }
        return null
    }

    private fun countAll(text: String): Int = needle?.let { occurrences(text, it).size } ?: 0

    private fun occurrences(text: String, n: String): List<Int> {
        val out = ArrayList<Int>()
        var i = text.indexOf(n)
        while (i >= 0) {
            if (!wholeWord || (!isWordBefore(text, i) && !isWordAt(text, i + n.length))) out.add(i)
            i = text.indexOf(n, i + 1)
        }
        return out
    }

    companion object {
        const val MAX_CHARS = 20_000
        const val MAX_RANGES = 100
        const val UNDO_MILLIS = 120_000L
        private const val GRAPHEME_WINDOW = 64 // longest emoji sequence looked at before a caret, in chars

        private fun digestOf(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

        private fun String.regionOf(ranges: List<TextRange>): String? {
            if (ranges.isEmpty()) return null
            val start = ranges.first().start
            val end = ranges.last().end
            return if (start < 0 || end > length || start > end) null else substring(start, end)
        }

        private fun isWordChar(cp: Int) = Character.isLetterOrDigit(cp) || cp == '_'.code || Character.getType(cp) == Character.NON_SPACING_MARK.toInt()
        private fun isWordBefore(text: String, i: Int) = i > 0 && isWordChar(Character.codePointBefore(text, i))
        private fun isWordAt(text: String, i: Int) = i < text.length && isWordChar(Character.codePointAt(text, i))

        /** The word around [caret], or null when the caret touches no word character. */
        fun wordAt(text: String, caret: Int): TextRange? {
            if (caret < 0 || caret > text.length) return null
            var start = caret
            while (isWordBefore(text, start)) start -= Character.charCount(Character.codePointBefore(text, start))
            var end = caret
            while (isWordAt(text, end)) end += Character.charCount(Character.codePointAt(text, end))
            return if (start == end) null else TextRange(start, end)
        }

        /** The starts of [count] lines from the line of [caret], fewer when the text ends first. */
        fun lineStarts(text: String, caret: Int, count: Int): List<Int> {
            val out = ArrayList<Int>()
            var start = text.lastIndexOf('\n', caret - 1) + 1
            while (out.size < count) {
                out.add(start)
                val nl = text.indexOf('\n', start)
                if (nl < 0) break
                start = nl + 1
            }
            return out
        }

        /**
         * Carets at the column of [caret] (characters after the previous line break) on [lineCount] lines from the line of
         * [caret]. A line shorter than the column gets none.
         */
        fun columnCarets(text: String, caret: Int, lineCount: Int): List<Int> {
            val starts = lineStarts(text, caret, lineCount)
            val column = caret - starts.first()
            return starts.mapNotNull { start ->
                val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                if (end - start >= column) start + column else null
            }
        }
    }
}
