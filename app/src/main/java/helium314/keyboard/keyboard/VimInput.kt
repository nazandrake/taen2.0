// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.content.SharedPreferences
import android.text.InputType
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.inputlogic.InputLogic
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.FieldText
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.vim.VimEditorReads
import helium314.keyboard.latin.vim.VimEngine
import helium314.keyboard.latin.vim.VimExtracted
import helium314.keyboard.latin.vim.VimMode
import helium314.keyboard.latin.vim.VimNav
import helium314.keyboard.latin.vim.VimNotice
import helium314.keyboard.latin.vim.VimOutcome
import helium314.keyboard.latin.vim.VimPort
import helium314.keyboard.latin.vim.VimRead
import helium314.keyboard.latin.vim.VimRules
import helium314.keyboard.latin.vim.VimWindow

/**
 * The adapter of the Vim layer (helium314.keyboard.latin.vim) to the keyboard: it reads and edits the field through the input
 * connection (no typing path, so nothing is learned), shows the Normal and Visual layouts and the mode line, and shows the
 * notices. [sendCode] presses a key code of the keyboard (cursor keys, undo, redo). Settings and the privacy decision are asked
 * for at every key, nothing is cached; everything the engine remembers (registers, macros, marks, search and command history) lives in memory
 * only and is cleared at the screen lock and wipe ([Privacy.onLock]) and when the setting is turned off. The system clipboard (the + and *
 * registers) is reached only through the keyboard's own paste and copy owners ([ClipboardHistoryManager.getPrimaryClipIfText] and
 * [RichInputConnection.copyToClipboard]); the command line (:, / and ?) is typed on the keyboard's own layout and goes to the engine, never to the field.
 */
class VimInput(private val latinIME: LatinIME, private val inputLogic: InputLogic, private val sendCode: (Int) -> Unit) {
    private val connection = inputLogic.mConnection
    private val settings = Settings.getInstance()
    private val switcher = KeyboardSwitcher.getInstance()

    /** The reads of [VimWindow]: the extracted text and the two bounded reads, all from the editor itself (never the keyboard's own cache). */
    private val editorReads = object : VimEditorReads {
        override fun extracted(hintMaxChars: Int): VimExtracted? =
            connection.getExtractedText(hintMaxChars)?.let { VimExtracted(it.text, it.startOffset, it.selectionStart, it.selectionEnd) }
        override fun before(n: Int): CharSequence? = connection.getTextBeforeCursorFromEditor(n, 0)
        override fun after(n: Int): CharSequence? = connection.getTextAfterCursor(n, 0)
        override fun selected(): CharSequence? = connection.getSelectedText(0)
    }

    private val port = object : VimPort {
        override fun read(): VimRead {
            inputLogic.finishInput() // no composing text under the edit
            return VimWindow.read(editorReads, VimEngine.MAX_CHARS)
        }

        override fun select(start: Int, end: Int) {
            inputLogic.finishInput()
            connection.setSelection(start, end)
        }

        override fun replace(start: Int, end: Int, text: String, caret: Int) {
            inputLogic.finishInput()
            connection.beginBatchEdit()
            try {
                connection.setSelection(start, end)
                connection.commitText(text, 1) // the one commit path of pasted text: nothing is composed, nothing is learned
                connection.setSelection(caret, caret)
            } finally {
                connection.endBatchEdit()
            }
        }

        override fun navigate(nav: VimNav, times: Int) {
            inputLogic.finishInput()
            val code = when (nav) {
                VimNav.LEFT -> KeyCode.ARROW_LEFT
                VimNav.RIGHT -> KeyCode.ARROW_RIGHT
                VimNav.UP -> KeyCode.ARROW_UP
                VimNav.DOWN -> KeyCode.ARROW_DOWN
                VimNav.WORD_LEFT -> KeyCode.WORD_LEFT
                VimNav.WORD_RIGHT -> KeyCode.WORD_RIGHT
                VimNav.LINE_START -> KeyCode.MOVE_START_OF_LINE
                VimNav.LINE_END -> KeyCode.MOVE_END_OF_LINE
                VimNav.TEXT_START -> KeyCode.MOVE_START_OF_PAGE
                VimNav.TEXT_END -> KeyCode.MOVE_END_OF_PAGE
            }
            repeat(times) { sendCode(code) }
        }

        override fun history(redo: Boolean) = sendCode(if (redo) KeyCode.REDO else KeyCode.UNDO)

        // the + and * registers: the paste owner (what the paste key commits: text only, filtered for number fields) and the copy owner
        override fun clipboardRead(): String? {
            val attributes = settings.current.mInputAttributes
            val text = latinIME.clipboardHistoryManager.getPrimaryClipIfText() ?: return null
            return FieldText.forField(text, attributes.mInputType, attributes.mDecimalSeparator).takeIf { it.isNotEmpty() }
        }

        override fun clipboardWrite(text: String): Boolean = connection.copyToClipboard(text)
    }

    private val rules = object : VimRules {
        override fun available() = settings.current.mVimMode && settings.current.mPrivacy.vimMode // PrivacyPolicy: never in a password field or while locked

        override fun registerAllowed() = settings.current.mPrivacy.storeClipboard // PrivacyPolicy: the clipboard capture rule

        override fun accepts(text: String): Boolean {
            val attributes = settings.current.mInputAttributes
            val inputType = attributes.mInputType
            val multiLine = inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT && inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
            return (multiLine || '\n' !in text) && FieldText.forField(text, inputType, attributes.mDecimalSeparator) == text
        }

        override fun nowMillis(): Long = android.os.SystemClock.elapsedRealtime()

        override fun indentWithTab() = settings.current.mVimIndentTab
    }

    // every call into the engine goes through the guard: an exception of the engine is dropped unread, never reaches the crash handler and its log (the field text can be in the message)
    internal val engine = GuardedVimEngine(VimEngine(port, rules), ::engineFailed)

    /** An engine call threw (the exception is already dropped): back to typing, nothing pending, one generic notice. */
    private fun engineFailed() {
        leave()
        switcher.showToast(latinIME.getString(R.string.vim_failed), true)
    }

    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    init {
        Privacy.onLock("vim") { // everything the layer remembers is forgotten at the screen lock and at a wipe (Privacy.beginWipe runs these hooks too)
            engine.clearMemory()
            leave() // Vim is not available while the device is locked (PrivacyPolicy): no Vim layout is left on the screen
        }
    }

    /** The Esc key of the typing keyboard (the toolbar key) and of the Vim layouts. */
    fun escapeKey() {
        watchSetting()
        if (!settings.current.mVimMode) {
            end()
            return
        }
        if (engine.mode != VimMode.INSERT && !isVimLayout() && !engine.wantsChar) end() // a state left over from another layout or field: Esc starts from typing
        after(engine.escape())
    }

    /**
     * True while the keyboard is asked for a character or a command line: what is typed, swiped or tapped as a suggestion then belongs to the Vim
     * layer (or is dropped), never to the field.
     */
    fun capturesTyping(): Boolean = engine.wantsChar

    private fun available() = rules.available()

    /**
     * Gives [code] to the Vim layer when it is a key of the Normal or Visual layout, the character that f, t, r, a register name, a mark or q ask for,
     * a key of the command line, or a Ctrl key of insert mode ([ctrl]: Ctrl is held; r, w, u, h and o). Returns true when the key is used up: a key of
     * a Vim layout is never typed, and a command line is never typed into the field.
     */
    fun consumes(code: Int, ctrl: Boolean = false): Boolean {
        val onVimLayout = isVimLayout()
        if (engine.cmdline != null) return commandLineKey(code)
        if (engine.mode == VimMode.INSERT) {
            if (engine.wantsChar && code > 0) { // the register after Ctrl-R
                after(engine.key(code))
                return true
            }
            if (ctrl && available() && code > 0) {
                val control = when (code.toChar().lowercaseChar()) {
                    'r' -> VimEngine.CTRL_R
                    'w' -> VimEngine.CTRL_W
                    'u' -> VimEngine.CTRL_U
                    'h' -> VimEngine.CTRL_H
                    'o' -> VimEngine.CTRL_O
                    else -> 0
                }
                if (control != 0) {
                    after(engine.key(control))
                    return true
                }
            }
            if (!onVimLayout) return false
            after(VimOutcome()) // a Vim layout that the engine does not know (restored after a rotation): back to typing, the key is dropped
            return true
        }
        if (!onVimLayout && !engine.wantsChar) {
            end() // the user left the Vim layouts with another key
            return false
        }
        if (engine.wantsChar && (code == KeyCode.DELETE || code == Constants.CODE_ENTER)) {
            after(engine.escape()) // cancels the command that waits for a character
            return true
        }
        if (code <= 0) return false // toolbar and function keys work as always
        after(engine.key(code))
        return true
    }

    /** A key typed while the command line is open: letters and symbols join the line, Enter runs it, Backspace edits it, up and down walk its history. */
    private fun commandLineKey(code: Int): Boolean {
        val key = when {
            code == KeyCode.DELETE -> VimEngine.BACKSPACE
            code == Constants.CODE_ENTER -> VimEngine.ENTER
            code == KeyCode.ARROW_UP -> VimEngine.HISTORY_PREVIOUS
            code == KeyCode.ARROW_DOWN -> VimEngine.HISTORY_NEXT
            code > 0 -> code
            else -> return false // layout and function keys (the symbols page, shift) work as always
        }
        after(engine.key(key))
        return true
    }

    /** Text from a key that types several characters, an emoji or a swipe word: while a character or a command line is asked for, it goes to the engine. */
    fun consumesText(text: String?): Boolean {
        if (text == null || !capturesTyping()) return false
        var i = 0
        while (i < text.length && engine.wantsChar) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            after(engine.key(cp))
        }
        return true
    }

    /** A new field, or the end of the input: the typing keyboard is back (the next keyboard load shows it). */
    fun fieldFinished() = end()

    private fun end() {
        engine.reset()
        inputLogic.setVimActive(false)
        switcher.setVimStatus(null)
    }

    /** Back to typing at once: the engine is reset and a Vim layout that is showing is left (the setting off, the screen lock). */
    private fun leave() {
        end()
        if (isVimLayout()) switcher.leaveVimLayout(latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
    }

    private fun isVimLayout(): Boolean = switcher.keyboard?.mId?.element.let { it == KeyboardElement.VIM_NORMAL || it == KeyboardElement.VIM_VISUAL }

    private fun after(outcome: VimOutcome) {
        // A notice is a fixed text of the keyboard: a toast. A listing (:reg, :marks) holds register and mark data and is never given to a toast
        // (the system toast of API 32 and below is broadcast to accessibility services): it is shown in the keyboard's own mode line view.
        outcome.notice?.let { switcher.showToast(noticeText(it), true) }
        val listing = outcome.message?.takeIf { it.isNotEmpty() }
        inputLogic.setVimActive(engine.mode != VimMode.INSERT)
        val wanted = when {
            engine.mode == VimMode.INSERT || engine.wantsChar -> null // the typing keyboard (a character is typed for f, t, r; the command line is typed too)
            engine.mode == VimMode.NORMAL -> KeyboardElement.VIM_NORMAL
            else -> KeyboardElement.VIM_VISUAL
        }
        val current = switcher.keyboard?.mId?.element
        if (wanted == null) {
            if (current == KeyboardElement.VIM_NORMAL || current == KeyboardElement.VIM_VISUAL)
                switcher.leaveVimLayout(latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
        } else if (current != wanted) {
            switcher.showVimLayout(wanted)
        }
        val line = engine.cmdline
        switcher.setVimStatus(when {
            listing != null -> listing // until the next key
            line != null -> "$line▏" // the command line, with a cursor
            engine.mode == VimMode.INSERT && engine.recordingRegister == null -> null
            else -> statusText()
        })
    }

    private fun statusText(): String {
        val mode = latinIME.getString(when (engine.mode) {
            VimMode.VISUAL -> R.string.vim_visual
            VimMode.VISUAL_LINE -> R.string.vim_visual_line
            VimMode.VISUAL_BLOCK -> R.string.vim_visual_block
            VimMode.INSERT -> R.string.vim_insert
            else -> R.string.vim_normal
        })
        val pending = engine.pending
        val recording = engine.recordingRegister?.let { latinIME.getString(R.string.vim_recording, it.toString()) }
        return listOfNotNull(mode, pending.takeIf { it.isNotEmpty() }, recording).joinToString("  ")
    }

    private fun noticeText(n: VimNotice): String = latinIME.getString(when (n) {
        VimNotice.UNAVAILABLE -> R.string.vim_not_available
        VimNotice.NO_TEXT -> R.string.vim_no_text
        VimNotice.PARTIAL_TEXT -> R.string.vim_partial_text
        VimNotice.TOO_LONG -> R.string.vim_too_long
        VimNotice.CHANGED -> R.string.vim_changed
        VimNotice.SELECTED -> R.string.vim_selected
        VimNotice.NOT_ACCEPTED -> R.string.vim_not_accepted
        VimNotice.REGISTER_OFF -> R.string.vim_register_off
        VimNotice.REGISTER_EMPTY -> R.string.vim_register_empty
        VimNotice.REGISTER_FULL -> R.string.vim_register_full
        VimNotice.CLIPBOARD_EMPTY -> R.string.vim_clipboard_empty
        VimNotice.CLIPBOARD_REFUSED -> R.string.vim_clipboard_refused
        VimNotice.MARK_NOT_SET -> R.string.vim_mark_not_set
        VimNotice.MARK_INVALID -> R.string.vim_mark_invalid
        VimNotice.NO_PATTERN -> R.string.vim_no_pattern
        VimNotice.NOT_FOUND -> R.string.vim_not_found
        VimNotice.PATTERN_UNSUPPORTED -> R.string.vim_pattern_unsupported
        VimNotice.SEARCH_LIMIT -> R.string.vim_search_limit
        VimNotice.PLAY_LIMIT -> R.string.vim_play_limit
        VimNotice.NOTHING_TO_REPEAT -> R.string.vim_nothing_to_repeat
        VimNotice.NOT_REPEATABLE -> R.string.vim_not_repeatable
        VimNotice.RECORDING_DROPPED -> R.string.vim_recording_dropped
        VimNotice.CANNOT_SAVE -> R.string.vim_cannot_save
        VimNotice.NO_HIGHLIGHT -> R.string.vim_no_highlight
        VimNotice.EX_ERROR -> R.string.vim_ex_error
        VimNotice.SCREEN_COMMAND -> R.string.vim_screen_command
        VimNotice.UNDO_IN_PLAY -> R.string.vim_undo_in_play
    })

    /**
     * The memory of the layer and the Vim layouts follow the value of the setting, not one key name: a change of any key, clear() (key == null), a restored
     * backup and an applied profile all call this, and while the value is off everything is forgotten and the layouts are left.
     */
    private fun watchSetting() {
        if (listener != null) return
        val prefs = latinIME.prefs()
        val l = SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
            if (!p.getBoolean(Settings.PREF_VIM_MODE, Defaults.PREF_VIM_MODE)) {
                engine.clearMemory()
                leave()
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(l)
        listener = l // the preferences hold a listener weakly
    }
}
