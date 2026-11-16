// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.navigation

import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.ShadowFacilitator2
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Settings
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The navigation features through the running keyboard service (the production entry points): the space-bar swipe callbacks and
 * the toolbar key codes of KeyboardActionListenerImpl, the field a fake InputConnection. Remove the wiring in the listener and a
 * test here fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class, ShadowFacilitator2::class])
class NavigationWiringTest {
    private val multiLine = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

    private fun withKeyboard(test: (LatinIME) -> Unit) {
        Privacy.forgetCachedState()
        ClipboardDao.forgetInstance()
        ShadowInputMethodService.reset()
        val ime = Robolectric.setupService(LatinIME::class.java)
        Settings.init(ime)
        KeyboardSwitcher.getInstance().onCreateInputView(ime, true)
        KeyboardSwitcher.getInstance().reloadMainKeyboard()
        try {
            test(ime)
        } finally {
            Privacy.unlock(ime)
            Privacy.forgetCachedState()
            ClipboardDao.forgetInstance()
            ShadowInputMethodService.reset()
        }
    }

    private fun field(ime: LatinIME, text: String, cursor: Int, inputType: Int, imeOptions: Int = 0) {
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

    private val selection get() = ShadowInputMethodService.selectionStart to ShadowInputMethodService.selectionEnd

    @Test fun aVerticalSwipeIsOneArrowKeyInAMultiLineFieldAndNoneInASingleLineField() = withKeyboard { ime ->
        field(ime, "ab\ncd", 1, multiLine)
        assertTrue(ime.mKeyboardActionListener.onVerticalSpaceSwipe(1))
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_DOWN), ShadowInputMethodService.keyDownCodes)
        field(ime, "abcd", 1, InputType.TYPE_CLASS_TEXT)
        ShadowInputMethodService.keyDownCodes.clear()
        assertTrue(ime.mKeyboardActionListener.onVerticalSpaceSwipe(1)) // consumed, nothing sent: the key could move the focus
        assertEquals(emptyList(), ShadowInputMethodService.keyDownCodes)
    }

    @Test fun shiftHeldWhileSwipingSelectsAndReleasingItKeepsTheSelection() = withKeyboard { ime ->
        field(ime, "0123456789abcdef", 10, multiLine)
        val listener = ime.mKeyboardActionListener
        listener.onPressKey(KeyCode.SHIFT, 0, 2, HapticEvent.NO_HAPTICS)
        assertTrue(listener.onHorizontalSpaceSwipe(3))
        assertEquals(10 to 13, selection)
        assertTrue(listener.onHorizontalSpaceSwipe(-5))
        assertEquals(10 to 8, selection)
        listener.onReleaseKey(KeyCode.SHIFT, false)
        listener.onEndSpaceSwipe()
        assertEquals(10 to 8, selection)
        assertTrue(listener.onHorizontalSpaceSwipe(1)) // positive control: without shift the swipe moves the cursor again
        assertEquals(ShadowInputMethodService.selectionStart, ShadowInputMethodService.selectionEnd)
    }

    @Test fun theNextOccurrenceKeyAndTypingEditEveryOccurrence() = withKeyboard { ime ->
        ShadowInputMethodService.extractedTextWithText = true
        field(ime, "ab ab xab ab", 1, multiLine)
        repeat(3) { press(ime, KeyCode.NEXT_OCCURRENCE) }
        assertEquals(10 to 12, selection)
        press(ime, 'c'.code)
        press(ime, 'd'.code)
        assertEquals("cd cd xab cd", ShadowInputMethodService.text)
        press(ime, KeyCode.UNDO) // the multi-cursor edit is undone while the field holds what was written
        assertEquals("ab ab xab ab", ShadowInputMethodService.text)
    }

    @Test fun theCursorBelowKeyTypesAtTheSameColumnOnEachLine() = withKeyboard { ime ->
        ShadowInputMethodService.extractedTextWithText = true
        field(ime, "a1\nb2\nc3", 1, multiLine)
        press(ime, KeyCode.CARET_BELOW)
        press(ime, KeyCode.CARET_BELOW)
        press(ime, 'x'.code)
        assertEquals("ax1\nbx2\ncx3", ShadowInputMethodService.text)
    }

    @Test fun passwordAndIncognitoFieldsAreNeverRead() = withKeyboard { ime ->
        ShadowInputMethodService.extractedTextWithText = true
        for ((inputType, imeOptions) in listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD to 0,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD to 0,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD to 0,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD to 0,
            InputType.TYPE_CLASS_TEXT to EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
        )) {
            field(ime, "ab ab", 1, inputType, imeOptions)
            val before = ShadowInputMethodService.extractedTextCalls
            press(ime, KeyCode.NEXT_OCCURRENCE)
            press(ime, KeyCode.CARET_BELOW)
            press(ime, KeyCode.SELECT_MODE)
            ime.mKeyboardActionListener.onPressKey(KeyCode.SHIFT, 0, 2, HapticEvent.NO_HAPTICS)
            ime.mKeyboardActionListener.onHorizontalSpaceSwipe(1)
            ime.mKeyboardActionListener.onReleaseKey(KeyCode.SHIFT, false)
            assertEquals(before, ShadowInputMethodService.extractedTextCalls, "type $inputType options $imeOptions")
            assertEquals(ShadowInputMethodService.selectionStart, ShadowInputMethodService.selectionEnd, "no selection in type $inputType")
        }
        field(ime, "ab ab", 1, InputType.TYPE_CLASS_TEXT) // positive control: a normal field is read once and the word selected
        val before = ShadowInputMethodService.extractedTextCalls
        press(ime, KeyCode.NEXT_OCCURRENCE)
        assertEquals(before + 1, ShadowInputMethodService.extractedTextCalls)
        assertEquals(0 to 2, selection)
    }

    @Test fun theScreenLockDropsTheCursorsAndTheUndo() = withKeyboard { ime ->
        ShadowInputMethodService.extractedTextWithText = true
        field(ime, "ab ab", 1, multiLine)
        repeat(2) { press(ime, KeyCode.NEXT_OCCURRENCE) }
        press(ime, 'c'.code)
        assertEquals("c c", ShadowInputMethodService.text)
        Privacy.lock(ime)
        Privacy.unlock(ime)
        press(ime, 'd'.code) // the cursors are gone: this is ordinary typing at the one cursor
        assertEquals("c cd", ShadowInputMethodService.text)
    }
}
