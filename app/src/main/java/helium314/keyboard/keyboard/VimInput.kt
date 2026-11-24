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
import helium314.keyboard.latin.vim.VimEngine
import helium314.keyboard.latin.vim.VimMode
import helium314.keyboard.latin.vim.VimNav
import helium314.keyboard.latin.vim.VimNotice
import helium314.keyboard.latin.vim.VimOutcome
import helium314.keyboard.latin.vim.VimPort
import helium314.keyboard.latin.vim.VimRead
import helium314.keyboard.latin.vim.VimRules

/**
 * The adapter of the Vim layer (helium314.keyboard.latin.vim) to the keyboard: it reads and edits the field through the input
 * connection (no typing path, so nothing is learned), shows the Normal and Visual layouts and the mode line, and shows the
 * notices. [sendCode] presses a key code of the keyboard (cursor keys, undo, redo). Settings and the privacy decision are asked
 * for at every key, nothing is cached; the engine's register lives in memory only and is cleared at the screen lock and wipe
 * ([Privacy.onLock]) and when the setting is turned off.
 */
class VimInput(private val latinIME: LatinIME, private val inputLogic: InputLogic, private val sendCode: (Int) -> Unit) {
    private val connection = inputLogic.mConnection
    private val settings = Settings.getInstance()
    private val switcher = KeyboardSwitcher.getInstance()

    private val port = object : VimPort {
        override fun read(): VimRead {
            inputLogic.finishInput() // no composing text under the edit
            val extracted = connection.getExtractedText(VimEngine.MAX_CHARS + 1) ?: return VimRead.Unreadable
            val text = extracted.text ?: return VimRead.Unreadable
            if (extracted.startOffset != 0) return VimRead.Partial
            if (text.length > VimEngine.MAX_CHARS) return VimRead.TooLong
            // The selection can run backwards (the anchor after the cursor): it is ordered once here, and a and b are the only pair used below
            // and handed to the engine, which sees the same range whichever way the editor reports it.
            val rawStart = extracted.selectionStart
            val rawEnd = extracted.selectionEnd
            val a = minOf(rawStart, rawEnd)
            val b = maxOf(rawStart, rawEnd)
            if (a < 0 || b > text.length) return VimRead.Partial
            // The editor's size hint and startOffset prove nothing: an editor may cut its answer short and still say offset 0. The text counts
            // as the whole field only when the text on both sides of the selection, read from the editor with explicit bounds, is exactly
            // what the extracted text has there; any doubt is a refusal. Editors differ for a reversed selection: the text before the
            // cursor ends at the lower end of the selection (the platform editors) or at its start as reported (some others); either one
            // is a match, and the reported pair is used for nothing else.
            val before = connection.getTextBeforeCursorFromEditor(VimEngine.MAX_CHARS + 1, 0) ?: return VimRead.Unreadable
            val after = connection.getTextAfterCursor(VimEngine.MAX_CHARS + 1, 0) ?: return VimRead.Unreadable
            if (before.length + (b - a) + after.length > VimEngine.MAX_CHARS) return VimRead.TooLong
            val whole = text.toString()
            val ordered = before.toString() == whole.substring(0, a) && after.toString() == whole.substring(b)
            val asReported = before.toString() == whole.substring(0, rawStart) && after.toString() == whole.substring(rawEnd)
            if (!ordered && !asReported) return VimRead.Partial
            return VimRead.Text(whole, a, b)
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
    }

    internal val engine = VimEngine(port, rules)

    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    init {
        Privacy.onLock("vim") { // the register is forgotten at the screen lock and at a wipe (Privacy.beginWipe runs these hooks too)
            engine.clearRegister()
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
     * Gives [code] to the Vim layer when it is a key of the Normal or Visual layout, or the character that f, F, t, T or r wait for.
     * Returns true when the key is used up: a key of a Vim layout is never typed.
     */
    fun consumes(code: Int): Boolean {
        val onVimLayout = isVimLayout()
        if (engine.mode == VimMode.INSERT) {
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
        outcome.notice?.let { switcher.showToast(noticeText(it), true) }
        inputLogic.setVimActive(engine.mode != VimMode.INSERT)
        val wanted = when {
            engine.mode == VimMode.INSERT || engine.wantsChar -> null // the typing keyboard (a character is typed for f, t, r)
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
        switcher.setVimStatus(if (engine.mode == VimMode.INSERT) null else statusText())
    }

    private fun statusText(): String {
        val mode = latinIME.getString(when (engine.mode) {
            VimMode.VISUAL -> R.string.vim_visual
            VimMode.VISUAL_LINE -> R.string.vim_visual_line
            else -> R.string.vim_normal
        })
        val pending = engine.pending
        return if (pending.isEmpty()) mode else "$mode  $pending"
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
    })

    /**
     * The register and the Vim layouts follow the value of the setting, not one key name: a change of any key, clear() (key == null), a restored
     * backup and an applied profile all call this, and while the value is off the register is empty and the layouts are left.
     */
    private fun watchSetting() {
        if (listener != null) return
        val prefs = latinIME.prefs()
        val l = SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
            if (!p.getBoolean(Settings.PREF_VIM_MODE, Defaults.PREF_VIM_MODE)) {
                engine.clearRegister()
                leave()
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(l)
        listener = l // the preferences hold a listener weakly
    }
}
