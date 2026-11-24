// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

/** The modes of the Vim layer. [INSERT] is the ordinary typing keyboard: the layer does not see its keys. */
enum class VimMode { INSERT, NORMAL, VISUAL, VISUAL_LINE }

/** The text of the field as the editor gave it, whole and from offset 0; the selection is in the same offsets. */
sealed interface VimRead {
    class Text(val text: String, val selStart: Int, val selEnd: Int) : VimRead
    /** The editor gives no text. */
    data object Unreadable : VimRead
    /** The editor gave a part of the field (it does not start at 0). */
    data object Partial : VimRead
    /** The field is longer than [VimEngine.MAX_CHARS]. */
    data object TooLong : VimRead
}

/** Cursor keys the layer falls back to when the field text cannot be read (a pure cursor motion only). */
enum class VimNav { LEFT, RIGHT, UP, DOWN, WORD_LEFT, WORD_RIGHT, LINE_START, LINE_END, TEXT_START, TEXT_END }

/** What the engine needs from the text field. The adapter owns the input connection; nothing here is Android. */
interface VimPort {
    /** The field text and selection now. Asked for once per command, and again just before an edit. */
    fun read(): VimRead
    /** Moves the selection ([start] == [end] is a caret). */
    fun select(start: Int, end: Int)
    /** Replaces [start, end) of the field with [text] in one batch edit and puts the caret at [caret]. Not typing: nothing is learned. */
    fun replace(start: Int, end: Int, text: String, caret: Int)
    /** Presses a cursor key [times] times. */
    fun navigate(nav: VimNav, times: Int)
    /** The keyboard's undo and redo. */
    fun history(redo: Boolean)
}

/** What the surrounding keyboard decides for the current field. */
interface VimRules {
    /** Vim mode may be used in this field (the privacy decision; never in a password field). */
    fun available(): Boolean
    /** The privacy decision allows a copy of text of this field to be kept in memory (the clipboard capture rule). */
    fun registerAllowed(): Boolean
    /** The field takes [text] as it is (multi-line, numeric fields). */
    fun accepts(text: String): Boolean
}

/** A short message for the user; null for the normal case and for a command that fails silently (as a beep in Vim). */
enum class VimNotice {
    /** Vim mode is not available in this field. */
    UNAVAILABLE,
    /** The editor gives no text: only cursor motions work. */
    NO_TEXT,
    /** The editor gave only a part of the field. */
    PARTIAL_TEXT,
    /** The field is longer than the window the layer reads. */
    TOO_LONG,
    /** The field or the selection changed under the command; nothing was done. */
    CHANGED,
    /** Text is selected; press Esc, then the command. */
    SELECTED,
    /** The field does not take that text. */
    NOT_ACCEPTED,
    /** Nothing is stored here: the register is off in this field. */
    REGISTER_OFF,
    /** The register is empty. */
    REGISTER_EMPTY,
}

/**
 * What the engine tells the keyboard after a key: [notice] is a short message for the user (null for the normal case and for a
 * command that fails silently, as a beep does in Vim); [handled] is false when the key is not for the Vim layer (typing mode).
 * [failed] is true when the command could not be done (Vim beeps; the tests stop a key sequence there, as Vim does).
 * The mode and the pending character argument are read from the engine.
 */
class VimOutcome(val notice: VimNotice? = null, val handled: Boolean = true, val failed: Boolean = false)
