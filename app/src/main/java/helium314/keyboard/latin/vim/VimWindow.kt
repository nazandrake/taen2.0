// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

/** What an editor answers to a request for the whole text: the text, where it starts in the field and the selection in the field's offsets. */
class VimExtracted(val text: CharSequence?, val startOffset: Int, val selectionStart: Int, val selectionEnd: Int)

/** The reads the layer makes of an editor; the adapter (keyboard/VimInput.kt) answers them from the input connection. Null: no answer. */
interface VimEditorReads {
    fun extracted(hintMaxChars: Int): VimExtracted?
    /** At most [n] characters before the cursor. */
    fun before(n: Int): CharSequence?
    /** At most [n] characters after the cursor. */
    fun after(n: Int): CharSequence?
    /** The selected text (null or empty: nothing is selected). It has no bound: the editor gives all of it. */
    fun selected(): CharSequence?
}

/**
 * The one owner of "the field text is whole and fits the window" (VimWindowTest states the rule and checks it on a generated table).
 * The text is the whole field when the editor gave it from offset 0, it has at most [max] characters, and the three reads (the text before
 * the selection and after it, each bounded to max + 1 characters, and the selected text) are exactly what the extracted text has there.
 * Before, selection and after cover the field, and a bounded read returns a whole side when the side has at most max + 1 characters, so
 * a side with more text in the editor than in the extracted text answers with a different string: a cut-short or shifted answer never
 * matches, whatever selection the editor reports for its cut text. Editors differ for a reversed selection (the platform editors read up to
 * its lower end and from its higher end, others up to the reported start and from the reported end): either one matches. The lengths of
 * the reads are never added (with the second convention they overlap); TooLong is only an extracted text, or one read, longer than the window.
 */
object VimWindow {
    fun read(editor: VimEditorReads, max: Int): VimRead {
        val extracted = editor.extracted(max + 1) ?: return VimRead.Unreadable
        val text = extracted.text ?: return VimRead.Unreadable
        if (extracted.startOffset != 0) return VimRead.Partial
        if (text.length > max) return VimRead.TooLong
        val rawStart = extracted.selectionStart
        val rawEnd = extracted.selectionEnd
        val lo = minOf(rawStart, rawEnd)
        val hi = maxOf(rawStart, rawEnd)
        if (lo < 0 || hi > text.length) return VimRead.Partial
        val before = editor.before(max + 1) ?: return VimRead.Unreadable
        val after = editor.after(max + 1) ?: return VimRead.Unreadable
        val selected = editor.selected() ?: ""
        if (before.length > max || after.length > max || selected.length > max) return VimRead.TooLong
        val whole = text.toString()
        if (selected.toString() != whole.substring(lo, hi)) return VimRead.Partial
        val b = before.toString()
        val a = after.toString()
        val platform = b == whole.substring(0, lo) && a == whole.substring(hi)
        val reported = b == whole.substring(0, rawStart) && a == whole.substring(rawEnd)
        return if (platform || reported) VimRead.Text(whole, lo, hi) else VimRead.Partial
    }
}
