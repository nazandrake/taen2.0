// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.navigation

/** A text field for the navigation tests: it applies and records every call. */
class FakeField(var text: String, var selStart: Int = 0, var selEnd: Int = selStart) : CursorPort, FieldPort {
    val calls = ArrayList<String>()
    var extractedCalls = 0
    var extracted: (String) -> FieldText? = { FieldText(it, 0) }
    private var batch = 0

    override val selectionStart get() = minOf(selStart, selEnd)
    override val selectionEnd get() = maxOf(selStart, selEnd)
    override fun textBefore(n: Int): CharSequence = text.substring(0, selectionStart).takeLast(n)
    override fun selectedText(): CharSequence? = if (selStart == selEnd) null else text.substring(selectionStart, selectionEnd)
    override fun textAfter(n: Int): CharSequence = text.substring(selectionEnd).take(n)
    override fun arrowKey(arrow: Arrow, select: Boolean) { calls.add("key $arrow${if (select) " shift" else ""}") }

    override fun setSelection(start: Int, end: Int) {
        calls.add("setSelection $start,$end")
        selStart = start
        selEnd = end
    }

    override fun extractedText(maxChars: Int): FieldText? {
        extractedCalls++
        return extracted(text)
    }

    override fun beginBatchEdit() { batch++; calls.add("begin") }
    override fun endBatchEdit() { batch--; calls.add("end") }

    override fun commitText(text: CharSequence) {
        calls.add("commit $text")
        this.text = this.text.substring(0, selectionStart) + text + this.text.substring(selectionEnd)
        selStart = selectionStart + text.length
        selEnd = selStart
    }

    val inBatch get() = batch > 0
}

/** A clock the test moves by hand. */
class FakeClock(var now: Long = 1_000_000L) : () -> Long {
    override fun invoke() = now
}
