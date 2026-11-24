// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

import android.app.KeyguardManager
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ShadowFacilitator2
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.privacy.ExportService
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.suggestions.SuggestionStripView
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Vim layer through the running keyboard service (the production entry points): the Esc toolbar key code and the key codes of the
 * Vim layouts go to KeyboardActionListenerImpl, the field is a fake InputConnection. Remove the wiring in the listener and a test
 * here fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class, ShadowFacilitator2::class])
class VimWiringTest {
    private val multiLine = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

    private fun withKeyboard(vimMode: Boolean = true, test: (LatinIME) -> Unit) {
        Privacy.forgetCachedState()
        ClipboardDao.forgetInstance()
        ShadowInputMethodService.reset()
        val ime = Robolectric.setupService(LatinIME::class.java)
        Settings.init(ime)
        ime.prefs().edit().putBoolean(Settings.PREF_VIM_MODE, vimMode).commit()
        KeyboardSwitcher.getInstance().onCreateInputView(ime, true)
        KeyboardSwitcher.getInstance().reloadMainKeyboard()
        try {
            ShadowInputMethodService.extractedTextWithText = true
            test(ime)
        } finally {
            Privacy.unlock(ime)
            Privacy.forgetCachedState()
            ClipboardDao.forgetInstance()
            ShadowInputMethodService.reset()
        }
    }

    private fun field(ime: LatinIME, text: String, cursor: Int, inputType: Int = multiLine, imeOptions: Int = 0) {
        ShadowInputMethodService.text = text
        ShadowInputMethodService.selectionStart = cursor
        ShadowInputMethodService.selectionEnd = cursor
        ShadowInputMethodService.currentInputType = inputType
        ShadowInputMethodService.currentImeOptions = imeOptions
        val ei = EditorInfo().also {
            it.inputType = inputType
            it.imeOptions = imeOptions
            it.initialSelStart = cursor
            it.initialSelEnd = cursor
        }
        ime.mHandler.onStartInput(ei, false)
        ime.mHandler.onStartInputView(ei, false)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun press(ime: LatinIME, code: Int) = ime.mKeyboardActionListener.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)

    private fun keys(ime: LatinIME, s: String) = s.forEach { press(ime, it.code) }

    private val element get() = KeyboardSwitcher.getInstance().keyboard?.mId?.element
    private val selection get() = ShadowInputMethodService.selectionStart to ShadowInputMethodService.selectionEnd
    private val text get() = ShadowInputMethodService.text

    @Test fun theEscKeyOpensTheNormalLayoutAndStepsLeftThenItsKeysMoveAndEditWithoutTyping() = withKeyboard { ime ->
        field(ime, "foo bar baz", 3)
        assertTrue(element!!.isAlphabet) // the typing keyboard before
        press(ime, KeyCode.VIM_MODE)
        assertEquals(KeyboardElement.VIM_NORMAL, element)
        assertEquals(2 to 2, selection) // Esc: one character left
        keys(ime, "w")
        assertEquals(4 to 4, selection)
        assertEquals("foo bar baz", text) // the letter was a command, not typing
        keys(ime, "dw")
        assertEquals("foo baz", text)
        assertEquals(KeyboardElement.VIM_NORMAL, element)
    }

    @Test fun cwStartsTypingOnTheOrdinaryKeyboardAndEscComesBack() = withKeyboard { ime ->
        field(ime, "foo bar", 1)
        press(ime, KeyCode.VIM_MODE)
        keys(ime, "cw")
        assertEquals(" bar", text)
        assertTrue(element!!.isAlphabet)
        keys(ime, "ok") // typing again: the ordinary path
        assertEquals("ok bar", text)
        press(ime, KeyCode.VIM_MODE)
        assertEquals(KeyboardElement.VIM_NORMAL, element)
        assertEquals(1 to 1, selection)
    }

    @Test fun yankAndPutWorkWithTheRegisterInMemory() = withKeyboard { ime ->
        field(ime, "foo bar", 1)
        press(ime, KeyCode.VIM_MODE)
        keys(ime, "yw")
        keys(ime, "$")
        keys(ime, "p")
        assertEquals("foo barfoo ", text)
    }

    @Test fun visualModeUsesItsOwnLayoutAndDeletesTheSelection() = withKeyboard { ime ->
        field(ime, "foo bar baz", 1)
        press(ime, KeyCode.VIM_MODE)
        keys(ime, "vw")
        assertEquals(KeyboardElement.VIM_VISUAL, element)
        assertEquals(0 to 5, selection)
        keys(ime, "d")
        assertEquals("ar baz", text)
        assertEquals(KeyboardElement.VIM_NORMAL, element)
    }

    @Test fun aCharacterArgumentIsTypedOnTheOrdinaryKeyboardAndConsumed() = withKeyboard { ime ->
        field(ime, "a,b,c", 1)
        press(ime, KeyCode.VIM_MODE)
        keys(ime, "f")
        assertTrue(element!!.isAlphabet, "the typing keyboard shows for the character")
        press(ime, ','.code)
        assertEquals("a,b,c", text) // not typed
        assertEquals(KeyboardElement.VIM_NORMAL, element)
        assertEquals(1 to 1, selection)
        keys(ime, "r")
        press(ime, KeyCode.DELETE) // Backspace cancels the command that waits for a character
        assertEquals(KeyboardElement.VIM_NORMAL, element)
        assertEquals("a,b,c", text)
    }

    @Test fun noVimModeInAPasswordField() = withKeyboard { ime ->
        for (inputType in listOf(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)) {
            field(ime, "secret", 3, inputType)
            val before = ShadowInputMethodService.extractedTextCalls
            press(ime, KeyCode.VIM_MODE)
            assertTrue(element!!.isAlphabet || element == KeyboardElement.NUMBER || element?.isNumberLayout == true, "type $inputType: no Vim layout")
            assertEquals(before, ShadowInputMethodService.extractedTextCalls, "type $inputType: the field is not read")
            assertEquals(3 to 3, selection)
        }
        field(ime, "foo bar", 3) // positive control: a normal field enters Normal mode
        press(ime, KeyCode.VIM_MODE)
        assertEquals(KeyboardElement.VIM_NORMAL, element)
    }

    @Test fun theSettingIsOffByDefaultAndDoesNothingWhileOff() = withKeyboard(vimMode = false) { ime ->
        assertFalse(ime.prefs().getBoolean(Settings.PREF_VIM_MODE, false))
        field(ime, "foo bar", 3)
        press(ime, KeyCode.VIM_MODE)
        assertTrue(element!!.isAlphabet)
        assertEquals(3 to 3, selection)
        assertEquals("foo bar", text)
    }

    @Test fun theRegisterIsOnlyFilledWhereTheClipboardMayBeCapturedAndClearedAtTheLock() = withKeyboard { ime ->
        field(ime, "foo bar", 1, imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) // an incognito field
        press(ime, KeyCode.VIM_MODE)
        keys(ime, "dw") // the edit happens, nothing is kept
        assertEquals("bar", text)
        keys(ime, "P")
        assertEquals("bar", text)
        field(ime, "foo bar", 1) // a normal field: kept
        press(ime, KeyCode.VIM_MODE)
        keys(ime, "dw")
        field(ime, "x", 0)
        press(ime, KeyCode.VIM_MODE)
        keys(ime, "P")
        assertEquals("foo x", text) // the register survives a field change
        keys(ime, "dw") // refill, then the screen lock
        Privacy.lock(ime)
        Privacy.unlock(ime)
        field(ime, "y", 0)
        press(ime, KeyCode.VIM_MODE)
        keys(ime, "P")
        assertEquals("y", text) // forgotten at the lock
    }

    @Test fun anAppThatGivesNoTextGetsOnlyCursorMotionsAsCursorKeys() = withKeyboard { ime ->
        ShadowInputMethodService.extractedTextWithText = false
        field(ime, "foo bar", 3)
        press(ime, KeyCode.VIM_MODE)
        ShadowInputMethodService.keyDownCodes.clear()
        keys(ime, "dw")
        assertEquals("foo bar", text) // an edit is refused
        assertEquals(emptyList(), ShadowInputMethodService.keyDownCodes) // and no cursor key stands in for the motion of an edit
        keys(ime, "h")
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_LEFT), ShadowInputMethodService.keyDownCodes) // a pure motion falls back to the cursor key
    }

    @Test fun aPartialOrTooLongTextIsNotEditedThroughTheRealAdapter() = withKeyboard { ime ->
        field(ime, "foo bar", 1)
        press(ime, KeyCode.VIM_MODE)
        ShadowInputMethodService.extractedStartOffset = 5 // the app gives a part of its text
        keys(ime, "dw")
        assertEquals("foo bar", text)
        ShadowInputMethodService.extractedStartOffset = 0
        keys(ime, "dw") // positive control: the whole text is edited
        assertEquals("bar", text)
        field(ime, "x".repeat(VimEngine.MAX_CHARS + 1), 1) // longer than the window that is read
        press(ime, KeyCode.VIM_MODE)
        keys(ime, "x")
        assertEquals(VimEngine.MAX_CHARS + 1, text.length)
        field(ime, "x".repeat(VimEngine.MAX_CHARS), 1) // exactly the window: edited
        press(ime, KeyCode.VIM_MODE)
        keys(ime, "x")
        assertEquals(VimEngine.MAX_CHARS - 1, text.length)
    }

    // the keyboard's own toast-like view (it ignores a message while the last one is showing, so a test clears it first)
    private fun toastView() = KeyboardSwitcher::class.java.getDeclaredField("mFakeToastView").apply { isAccessible = true }.get(KeyboardSwitcher.getInstance()) as TextView
    private fun clearToast() { toastView().visibility = View.GONE }
    private fun toastText(): String? = toastView().takeIf { it.visibility == View.VISIBLE }?.text?.toString()

    /** The editor's answer is never trusted to be the whole field: an edit needs the text on both sides of the cursor to match what the editor gives for them. */
    @Test fun aFieldThatTheEditorGivesCutShortIsNeverEdited() = withKeyboard { ime ->
        val max = VimEngine.MAX_CHARS
        ShadowInputMethodService.extractedTextTransform = { it.take(max) } // the first 20000 of 20001 characters, startOffset 0
        field(ime, "x".repeat(max + 1), 1)
        press(ime, KeyCode.VIM_MODE)
        clearToast()
        keys(ime, "d$")
        assertEquals(max + 1, text.length, "d$ is refused: no character is left behind")
        // the extracted text is cut to the window and the read after the cursor (20000) is not longer than it: no single answer is over the window, so this is a partial text, not a too long one
        assertEquals(ime.getString(R.string.vim_partial_text), toastText())
        keys(ime, "x")
        assertEquals(max + 1, text.length)
        keys(ime, "dd")
        assertEquals(max + 1, text.length)
        field(ime, "x".repeat(max + 1), max + 1) // the cursor at the end: it lies outside the text that was given
        press(ime, KeyCode.VIM_MODE)
        keys(ime, "x")
        assertEquals(max + 1, text.length)
        // the same editor with a field of exactly 20000 characters: the answer is whole and the edit is done
        field(ime, "x".repeat(max), 1)
        press(ime, KeyCode.VIM_MODE)
        keys(ime, "d$")
        assertEquals("", text)
        // an editor whose answer differs from the field only before the cursor, only after it, or lacks the start but says offset 0
        for ((name, transform) in listOf<Pair<String, (String) -> String>>("head replaced" to { "Y" + it.drop(1) }, "tail replaced" to { it.dropLast(1) + "Z" }, "start missing" to { it.drop(1) })) {
            ShadowInputMethodService.extractedTextTransform = transform
            field(ime, "foo bar", 3)
            press(ime, KeyCode.VIM_MODE)
            clearToast()
            keys(ime, "dw")
            assertEquals("foo bar", text, name)
            assertEquals(ime.getString(R.string.vim_partial_text), toastText(), name)
        }
        ShadowInputMethodService.extractedTextTransform = { it.take(4) } // the selection lies beyond the text that was given
        field(ime, "foo bar", 6)
        press(ime, KeyCode.VIM_MODE)
        clearToast()
        keys(ime, "x")
        assertEquals("foo bar", text)
        assertEquals(ime.getString(R.string.vim_partial_text), toastText())
        field(ime, "foo bar", 3)
        press(ime, KeyCode.VIM_MODE)
        ShadowInputMethodService.extractedTextTransform = null // positive control: the same keys edit the whole field
        keys(ime, "dw")
        assertEquals("foobar", text) // the caret was on the blank: dw takes it
    }

    /** The selection of a field can run backwards (the anchor after the cursor): the whole text is still the whole text and the same range is meant. */
    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    @Test fun aReversedSelectionIsTheSameRangeAsAForwardOne() = withKeyboard { ime ->
        // Normal mode: Esc puts the caret where the selection starts, whichever way it runs and whichever way the editor answers for the text around it
        for ((name, start, end, ordered) in listOf(Quad("forward", 1, 4, false), Quad("reversed", 4, 1, false), Quad("reversed, platform editor", 4, 1, true))) {
            ShadowInputMethodService.cursorReadsOrdered = ordered
            field(ime, "foo bar", 1)
            press(ime, KeyCode.VIM_MODE)
            ShadowInputMethodService.selectionStart = start
            ShadowInputMethodService.selectionEnd = end
            press(ime, KeyCode.VIM_MODE)
            assertEquals(1 to 1, selection, name)
            keys(ime, "x")
            assertEquals("fo bar", text, name)
            assertEquals(1 to 1, selection, name)
        }
        // Visual mode: the same range is deleted whichever way the editor reports it
        for ((name, reversed, ordered) in listOf(Triple("forward", false, false), Triple("reversed", true, false), Triple("reversed, platform editor", true, true))) {
            ShadowInputMethodService.cursorReadsOrdered = ordered
            field(ime, "foo bar baz", 1)
            press(ime, KeyCode.VIM_MODE)
            keys(ime, "vll")
            assertEquals(KeyboardElement.VIM_VISUAL, element, name)
            assertEquals(0 to 3, selection, name)
            if (reversed) {
                ShadowInputMethodService.selectionStart = 3
                ShadowInputMethodService.selectionEnd = 0
            }
            keys(ime, "d")
            assertEquals(" bar baz", text, name)
            assertEquals(0 to 0, selection, name)
            assertEquals(KeyboardElement.VIM_NORMAL, element, name)
        }
        // an editor that cuts its answer short is still refused with a reversed selection
        ShadowInputMethodService.extractedTextTransform = { "Y" + it.drop(1) }
        field(ime, "foo bar", 1)
        press(ime, KeyCode.VIM_MODE)
        ShadowInputMethodService.selectionStart = 4
        ShadowInputMethodService.selectionEnd = 1
        press(ime, KeyCode.VIM_MODE)
        assertEquals(4 to 1, selection) // refused: the caret was not moved
        keys(ime, "x")
        assertEquals("foo bar", text)
    }

    /** A field of exactly the window, reversed selection (1, 0), the editor answering as reported: through the real adapter it is read and edited (VimWindowTest has the table). */
    @Test fun aFieldOfExactlyTheWindowWithAReversedSelectionIsEditedThroughTheRealAdapter() = withKeyboard { ime ->
        ShadowInputMethodService.cursorReadsOrdered = false
        field(ime, "x".repeat(VimEngine.MAX_CHARS), 1)
        press(ime, KeyCode.VIM_MODE)
        ShadowInputMethodService.selectionStart = 1
        ShadowInputMethodService.selectionEnd = 0
        press(ime, KeyCode.VIM_MODE) // Esc: the caret goes to the lower end
        assertEquals(0 to 0, selection)
        keys(ime, "x")
        assertEquals(VimEngine.MAX_CHARS - 1, text.length)
    }

    @Test fun aLineBreakIsNotInsertedInASingleLineField() = withKeyboard { ime ->
        field(ime, "one two", 1, InputType.TYPE_CLASS_TEXT)
        press(ime, KeyCode.VIM_MODE)
        keys(ime, "o")
        assertEquals("one two", text)
        assertEquals(KeyboardElement.VIM_NORMAL, element) // still Normal mode: nothing was done
        field(ime, "one two", 1, multiLine) // positive control: a multi-line field takes it
        press(ime, KeyCode.VIM_MODE)
        keys(ime, "o")
        assertEquals("one two\n", text)
    }

    @Test fun aVimLayoutNeverTypesItsKeysEvenWhenTheEngineIsInTypingMode() = withKeyboard { ime ->
        field(ime, "foo", 1)
        KeyboardSwitcher.getInstance().showVimLayout(KeyboardElement.VIM_NORMAL) // a layout that the engine does not know (as after a rotation)
        assertEquals(KeyboardElement.VIM_NORMAL, element)
        keys(ime, "x")
        assertEquals("foo", text) // dropped, not typed and not a command
        assertTrue(element!!.isAlphabet) // and the typing keyboard is back
        keys(ime, "x")
        assertEquals("fxoo", text) // positive control: typing works again
    }

    @Test fun turningTheSettingOffForgetsTheRegisterAndTheLayout() = withKeyboard { ime ->
        field(ime, "foo bar", 1)
        press(ime, KeyCode.VIM_MODE)
        keys(ime, "yw")
        assertEquals(KeyboardElement.VIM_NORMAL, element)
        ime.prefs().edit().putBoolean(Settings.PREF_VIM_MODE, false).commit() // the setting is turned off
        assertTrue(element!!.isAlphabet, "the Vim layout is gone")
        ime.prefs().edit().putBoolean(Settings.PREF_VIM_MODE, true).commit()
        field(ime, "x", 0)
        press(ime, KeyCode.VIM_MODE)
        keys(ime, "P")
        assertEquals("x", text, "the register was forgotten")
    }

    @Test fun jAndKPressTheArrowKeysAndUndoUsesTheKeyboardsUndo() = withKeyboard { ime ->
        field(ime, "ab\ncd", 1)
        press(ime, KeyCode.VIM_MODE)
        ShadowInputMethodService.keyDownCodes.clear()
        keys(ime, "j")
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_DOWN), ShadowInputMethodService.keyDownCodes)
        ShadowInputMethodService.keyDownCodes.clear()
        keys(ime, "u")
        assertEquals(listOf(KeyEvent.KEYCODE_Z), ShadowInputMethodService.keyDownCodes) // Ctrl+Z, as the undo toolbar key sends
    }

    @Test fun theLayoutsHaveTheKeysOfTheCommands() = withKeyboard { ime ->
        field(ime, "foo", 1)
        press(ime, KeyCode.VIM_MODE)
        val normal = KeyboardSwitcher.getInstance().keyboard!!
        assertEquals(KeyboardElement.VIM_NORMAL, normal.mId.element)
        val codes = normal.sortedKeys.map { it.code }.toSet()
        for (c in "1234567890wertyuiopasdfghjkl;xcvb^$,".toList()) assertTrue(c.code in codes, "no key $c in the Normal layout")
        assertTrue(27 in codes && 18 in codes, "Esc and Ctrl-R keys")
        keys(ime, "v")
        val visual = KeyboardSwitcher.getInstance().keyboard!!
        assertEquals(KeyboardElement.VIM_VISUAL, visual.mId.element)
        assertNotNull(visual.sortedKeys.firstOrNull { it.code == 'd'.code })
    }

    @Test fun aNewFieldStartsOnTheTypingKeyboardAndEngineAgrees() = withKeyboard { ime ->
        field(ime, "foo bar", 3)
        press(ime, KeyCode.VIM_MODE)
        assertEquals(KeyboardElement.VIM_NORMAL, element)
        field(ime, "other", 0) // another field: the keyboard reloads on its typing layout
        assertTrue(element!!.isAlphabet)
        keys(ime, "x") // typed, not a command
        assertEquals("xother", text)
    }

    /** What a paste of the register gives in a fresh field "x" (the register survives a field change). */
    private fun pasteIntoNewField(ime: LatinIME): String {
        field(ime, "x", 0)
        press(ime, KeyCode.VIM_MODE)
        keys(ime, "P")
        return text
    }

    private fun fillRegister(ime: LatinIME) {
        field(ime, "foo bar", 1)
        press(ime, KeyCode.VIM_MODE)
        keys(ime, "yw")
    }

    /** The register follows the effective setting, whatever path changed it: clear(), a restore of a backup without the key, the key removed. */
    @Test fun theRegisterIsForgottenWheneverTheSettingEndsUpOffWhateverPathChangedIt() = withKeyboard { ime ->
        fillRegister(ime)
        ime.prefs().edit().putString("unrelated_key", "1").commit() // positive control: another change keeps the register
        assertEquals("foo x", pasteIntoNewField(ime))
        fillRegister(ime)
        ime.prefs().edit().clear().commit() // notifies key == null; the setting is gone, so it is off
        ime.prefs().edit().putBoolean(Settings.PREF_VIM_MODE, true).commit()
        assertEquals("x", pasteIntoNewField(ime), "clear()")
        fillRegister(ime)
        ExportService.replacePrefs(ime.prefs(), mapOf("unrelated_key" to "2")) // a restored backup that has no vim_mode
        ime.prefs().edit().putBoolean(Settings.PREF_VIM_MODE, true).commit()
        assertEquals("x", pasteIntoNewField(ime), "restore without the key")
        fillRegister(ime)
        ime.prefs().edit().remove(Settings.PREF_VIM_MODE).commit()
        ime.prefs().edit().putBoolean(Settings.PREF_VIM_MODE, true).commit()
        assertEquals("x", pasteIntoNewField(ime), "key removed")
        fillRegister(ime)
        ExportService.replacePrefs(ime.prefs(), mapOf(Settings.PREF_VIM_MODE to true)) // positive control: a restore that keeps Vim on keeps the register
        assertEquals("foo x", pasteIntoNewField(ime))
    }

    /** Vim reads and edits the field: it is not available over the keyguard (PrivacyPolicy: locked), the strip has no Esc key there. */
    @Test fun noVimModeWhileTheDeviceIsLocked() = withKeyboard { ime ->
        ime.setInputView(KeyboardSwitcher.getInstance().onCreateInputView(ime, true)) // the strip view exists
        val strip = LatinIME::class.java.getDeclaredField("mSuggestionStripView").apply { isAccessible = true }.get(ime) as SuggestionStripView
        fun vimEscKey(): View? = strip.findViewWithTag(ToolbarKey.VIM)
        val keyguard = shadowOf(ime.getSystemService(KeyguardManager::class.java))
        field(ime, "foo bar", 3)
        assertNotNull(vimEscKey(), "positive control: unlocked, the strip has the Esc key")
        keyguard.setIsDeviceLocked(true)
        field(ime, "foo bar", 3)
        assertTrue(Settings.getValues().mIsLocked, "the field is shown while the device is locked")
        strip.updateFieldKeys() // the strip is asked for its keys again, as the keyboard does at a field start that shows a strip
        assertNull(vimEscKey(), "no Esc key on the strip")
        val reads = ShadowInputMethodService.extractedTextCalls
        press(ime, KeyCode.VIM_MODE)
        assertTrue(element!!.isAlphabet)
        assertEquals(reads, ShadowInputMethodService.extractedTextCalls, "the field is not read")
        assertEquals(3 to 3, selection)
        assertEquals("foo bar", text)
        keyguard.setIsDeviceLocked(false) // positive control: unlocked, the key is there and Vim works
        field(ime, "foo bar", 3)
        strip.updateFieldKeys()
        assertNotNull(vimEscKey())
        press(ime, KeyCode.VIM_MODE)
        assertEquals(KeyboardElement.VIM_NORMAL, element)
    }

    @Test fun theScreenLockLeavesTheVimLayoutAndTheKeysTypeAgain() = withKeyboard { ime ->
        field(ime, "foo bar", 3)
        press(ime, KeyCode.VIM_MODE)
        keys(ime, "yw")
        assertEquals(KeyboardElement.VIM_NORMAL, element)
        Privacy.lock(ime)
        assertTrue(element!!.isAlphabet, "no Vim layout is left behind the lock")
        keys(ime, "k")
        assertEquals("foko bar", text, "a key types after the lock") // typed into the field at the caret (2)
        Privacy.unlock(ime)
        assertEquals("x", pasteIntoNewField(ime), "the register was forgotten at the lock")
    }
}
