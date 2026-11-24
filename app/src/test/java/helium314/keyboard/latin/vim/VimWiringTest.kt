// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.ShadowFacilitator2
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Settings
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
}
