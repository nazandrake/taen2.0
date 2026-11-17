// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.LocaleList
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.TextView
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.clipboard.ClipboardHistoryView
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.suggestions.SuggestionStripView
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Number, phone and date fields through the running keyboard: the strip carries paste, select all and clear all (a tap on them reaches the field
 * through the strip's own listener), the clipboard paths (paste key, suggestion, history entry) paste what the field accepts, and a text field
 * keeps its strip as it was. Remove the strip keys, the paste filter or the clear all branch and a test here fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class, ShadowFacilitator2::class])
class NumberFieldWiringTest {
    private val number = InputType.TYPE_CLASS_NUMBER
    private val decimal = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    private val text = InputType.TYPE_CLASS_TEXT

    private fun withKeyboard(test: (LatinIME) -> Unit) {
        Privacy.forgetCachedState()
        ClipboardDao.forgetInstance()
        ShadowInputMethodService.reset()
        val ime = Robolectric.setupService(LatinIME::class.java)
        Settings.init(ime)
        KeyboardSwitcher.getInstance().onCreateInputView(ime, true)
        try {
            test(ime)
        } finally {
            Privacy.unlock(ime)
            Privacy.forgetCachedState()
            ClipboardDao.forgetInstance()
            ShadowInputMethodService.reset()
        }
    }

    private fun startField(ime: LatinIME, inputType: Int, content: String = "", hintLocales: LocaleList? = null) {
        ShadowInputMethodService.currentInputType = inputType
        ShadowInputMethodService.currentHintLocales = hintLocales
        ShadowInputMethodService.text = content
        ShadowInputMethodService.selectionStart = content.length
        ShadowInputMethodService.selectionEnd = content.length
        ShadowInputMethodService.composingStart = -1
        ShadowInputMethodService.composingEnd = -1
        val ei = EditorInfo().apply {
            this.inputType = inputType
            initialSelStart = content.length
            initialSelEnd = content.length
            this.hintLocales = hintLocales
        }
        ime.mHandler.onStartInput(ei, false)
        ime.mHandler.onStartInputView(ei, false)
    }

    private fun strip(ime: LatinIME) = assertNotNull(
        LatinIME::class.java.getDeclaredField("mSuggestionStripView").apply { isAccessible = true }.get(ime) as SuggestionStripView?
    )

    private fun pinned(ime: LatinIME) = strip(ime).findViewById<ViewGroup>(R.id.pinned_keys)
    private fun pinnedKeys(ime: LatinIME) = pinned(ime).let { group -> (0 until group.childCount).map { group.getChildAt(it).tag } }
    private fun tap(ime: LatinIME, key: ToolbarKey) = assertNotNull(pinned(ime).findViewWithTag<View>(key), "$key is on the strip").performClick()

    @Test fun theStripOfANumberPhoneOrDateFieldCarriesPasteSelectAllAndClearAll() = withKeyboard { ime ->
        for (type in listOf(number, decimal, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE)) {
            startField(ime, type)
            val keys = pinnedKeys(ime)
            assertTrue(ToolbarKey.PASTE in keys && ToolbarKey.SELECT_ALL in keys && ToolbarKey.CLEAR_ALL in keys, "type $type: $keys")
            assertEquals(1, keys.count { it == ToolbarKey.PASTE }, "type $type: one paste key")
        }
    }

    @Test fun aTextFieldStripHasNoneOfThemAndTheyLeaveWhenTheFieldChanges() = withKeyboard { ime ->
        startField(ime, text)
        val before = pinnedKeys(ime)
        assertTrue(ToolbarKey.VOICE in before, "positive control: the pinned key of the strip is there: $before")
        assertTrue(ToolbarKey.PASTE !in before && ToolbarKey.SELECT_ALL !in before && ToolbarKey.CLEAR_ALL !in before, "$before")
        startField(ime, number)
        assertTrue(ToolbarKey.CLEAR_ALL in pinnedKeys(ime))
        startField(ime, text)
        assertEquals(before, pinnedKeys(ime))
    }

    /** P9: a password field gets no strip keys of its own and no clipboard suggestion, in the number class as in the others. */
    @Test fun aPasswordFieldGetsNoStripKeysAndNoClipboardSuggestion() = withKeyboard { ime ->
        val keys = listOf(ToolbarKey.PASTE, ToolbarKey.SELECT_ALL, ToolbarKey.CLEAR_ALL)
        startField(ime, number)
        assertTrue(keys.all { it in pinnedKeys(ime) }, "positive control: a plain number field has them: ${pinnedKeys(ime)}")
        assertNotNull(suggestion(ime, number, "12"), "positive control: a plain number field is offered the clip")
        for (type in listOf(
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) {
            startField(ime, type)
            assertTrue(Settings.getValues().mPrivacy.passwordField, "type $type is a password field")
            for (key in keys) assertFalse(key in pinnedKeys(ime), "type $type: $key on the strip: ${pinnedKeys(ime)}")
            assertNull(suggestion(ime, type, "12"), "type $type: no clipboard suggestion")
        }
        startField(ime, number)
        assertTrue(keys.all { it in pinnedKeys(ime) }, "the keys come back in a plain number field: ${pinnedKeys(ime)}")
    }

    /** P9: the keys the user pinned follow the same decision: a password field shows none of them (the strip is not there), other fields show them. */
    @Test fun keysPinnedByTheUserAreNotShownInAPasswordField() = withKeyboard { ime ->
        val pinnedByUser = listOf(ToolbarKey.PASTE, ToolbarKey.SELECT_ALL, ToolbarKey.CLEAR_ALL, ToolbarKey.UNDO)
        val pref = pinnedByUser.fold(helium314.keyboard.latin.utils.defaultPinnedToolbarPref) { p, key -> p.replace("${key.name}:false", "${key.name}:true") }
        ime.prefs().edit().putString(Settings.PREF_PINNED_TOOLBAR_KEYS, pref).commit()
        ime.setInputView(KeyboardSwitcher.getInstance().onCreateInputView(ime, true)) // the strip reads the pinned keys when it is created
        fun shown(): List<Any?> = pinned(ime).let { row -> (0 until row.childCount).map { row.getChildAt(it) }.filter { it.visibility == View.VISIBLE }.map { it.tag } }
        for (type in listOf(text, number)) {
            startField(ime, type)
            assertTrue(pinnedByUser.all { it in shown() }, "positive control: type $type shows the keys the user pinned: ${shown()}")
        }
        for (type in listOf(
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) {
            startField(ime, type)
            assertTrue(Settings.getValues().mPrivacy.passwordField, "type $type is a password field")
            assertTrue(pinnedByUser.none { it in shown() }, "type $type: ${shown()}")
        }
        startField(ime, number)
        assertEquals(1, shown().count { it == ToolbarKey.CLEAR_ALL }, "the keys come back in a number field: ${shown()}")
        assertTrue(pinnedByUser.all { it in shown() }, "${shown()}")
    }

    @Test fun aKeyTheUserPinnedIsNotAddedTwice() = withKeyboard { ime ->
        ime.prefs().edit().putString(Settings.PREF_PINNED_TOOLBAR_KEYS, helium314.keyboard.latin.utils.defaultPinnedToolbarPref.replace("PASTE:false", "PASTE:true")).commit()
        ime.setInputView(KeyboardSwitcher.getInstance().onCreateInputView(ime, true)) // the strip reads the pinned keys when it is created
        startField(ime, text)
        val textKeys = pinnedKeys(ime)
        assertEquals(1, textKeys.count { it == ToolbarKey.PASTE }, "positive control: the setting pinned the paste key in a text field: $textKeys")
        startField(ime, number)
        val numberKeys = pinnedKeys(ime)
        assertEquals(1, numberKeys.count { it == ToolbarKey.PASTE }, "$numberKeys")
        assertTrue(ToolbarKey.SELECT_ALL in numberKeys && ToolbarKey.CLEAR_ALL in numberKeys, "$numberKeys")
    }

    /**
     * The strip keys of a number field come from the field type, not from the pinned-key setting: a long press on the same key in the open toolbar
     * (quick pin on) pins it as the user's key and never removes the field's key, and the next refresh of the strip still has all three.
     */
    @Test fun aLongPressInTheToolbarDoesNotRemoveTheKeysOfANumberField() = withKeyboard { ime ->
        ime.prefs().edit().putBoolean(Settings.PREF_QUICK_PIN_TOOLBAR_KEYS, true).commit()
        startField(ime, number)
        assertTrue(Settings.getValues().mQuickPinToolbarKeys, "positive control: quick pin is on")
        val toolbar = strip(ime).findViewById<ViewGroup>(R.id.toolbar)
        for (key in listOf(ToolbarKey.PASTE, ToolbarKey.SELECT_ALL, ToolbarKey.CLEAR_ALL)) {
            assertNotNull(toolbar.findViewWithTag<View>(key), "$key is in the toolbar").performLongClick()
            assertEquals(1, pinnedKeys(ime).count { it == key }, "$key stays on the strip after the long press: ${pinnedKeys(ime)}")
            assertTrue(key in helium314.keyboard.latin.utils.getPinnedToolbarKeys(ime.prefs()), "the long press pinned $key")
        }
        startField(ime, number) // the strip is refreshed for the field
        val keys = pinnedKeys(ime)
        for (key in listOf(ToolbarKey.PASTE, ToolbarKey.SELECT_ALL, ToolbarKey.CLEAR_ALL))
            assertEquals(1, keys.count { it == key }, "$key once on the refreshed strip: $keys")
        // a second long press unpins the user's key; the field keeps its own
        toolbar.findViewWithTag<View>(ToolbarKey.PASTE).performLongClick()
        assertFalse(ToolbarKey.PASTE in helium314.keyboard.latin.utils.getPinnedToolbarKeys(ime.prefs()), "the second long press unpinned paste")
        assertEquals(1, pinnedKeys(ime).count { it == ToolbarKey.PASTE }, "${pinnedKeys(ime)}")
        startField(ime, text)
        assertFalse(ToolbarKey.PASTE in pinnedKeys(ime), "a text field has no paste key once it is unpinned: ${pinnedKeys(ime)}")
        assertTrue(ToolbarKey.CLEAR_ALL in pinnedKeys(ime), "the user's pin of clear all stays in a text field: ${pinnedKeys(ime)}")
    }

    @Test fun theKeysOfANumberFieldComeBackWhenThePinnedRowWasEmptied() = withKeyboard { ime ->
        startField(ime, number)
        assertTrue(ToolbarKey.PASTE in pinnedKeys(ime), "positive control: ${pinnedKeys(ime)}")
        pinned(ime).removeAllViews() // the row lost its views (as when it is rebuilt)
        startField(ime, number)
        val keys = pinnedKeys(ime)
        assertTrue(ToolbarKey.PASTE in keys && ToolbarKey.SELECT_ALL in keys && ToolbarKey.CLEAR_ALL in keys, "$keys")
    }

    @Test fun tappingClearAllOnTheStripEmptiesTheField() = withKeyboard { ime ->
        startField(ime, number, "123456")
        tap(ime, ToolbarKey.CLEAR_ALL)
        assertEquals("", ShadowInputMethodService.text)
    }

    @Test fun tappingSelectAllOnTheStripSelectsTheField() = withKeyboard { ime ->
        startField(ime, number, "123456")
        tap(ime, ToolbarKey.SELECT_ALL)
        assertEquals("123456", ShadowInputMethodService.selectedText)
    }

    @Test fun tappingPasteOnTheStripPastesWhatTheFieldAccepts() = withKeyboard { ime ->
        (ime.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("t", "+1 (2) 3x"))
        startField(ime, number, "9")
        tap(ime, ToolbarKey.PASTE)
        assertEquals("91", ShadowInputMethodService.text) // the first number of the clip
        startField(ime, InputType.TYPE_CLASS_PHONE, "9")
        tap(ime, ToolbarKey.PASTE)
        assertEquals("9+1 (2) 3", ShadowInputMethodService.text)
    }

    @Test fun aDecimalFieldThatNamesALocaleGetsItsSeparator() = withKeyboard { ime ->
        (ime.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("t", "1,234.50 EUR"))
        startField(ime, decimal, hintLocales = LocaleList(java.util.Locale.GERMANY))
        tap(ime, ToolbarKey.PASTE)
        assertEquals("1234,50", ShadowInputMethodService.text)
        startField(ime, decimal) // control: no locale named, the platform's decimal field takes a dot
        tap(ime, ToolbarKey.PASTE)
        assertEquals("1234.50", ShadowInputMethodService.text)
    }

    @Test fun theClipboardSuggestionUsesTheSeparatorOfTheField() = withKeyboard { ime ->
        ime.prefs().edit().putBoolean(Settings.PREF_SUGGEST_CLIPBOARD_CONTENT, true).commit()
        startField(ime, decimal, hintLocales = LocaleList(java.util.Locale.GERMANY))
        (ime.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(recentClip("1,234.50"))
        val ei = EditorInfo().apply { inputType = decimal; hintLocales = LocaleList(java.util.Locale.GERMANY) }
        val view = assertNotNull(ime.clipboardHistoryManager.getClipboardSuggestionView(ei, FrameLayout(ime)))
        assertEquals("1234,50", view.findViewById<TextView>(R.id.clipboard_suggestion_text).text.toString())
    }

    /** A clip copied just now. A clip is stamped when it is made; the test framework's clock starts near 0, so it is moved to the wall clock first. */
    private fun recentClip(text: String): ClipData {
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(System.currentTimeMillis()))
        return ClipData.newPlainText("t", text)
    }

    private fun suggestion(ime: LatinIME, inputType: Int, clip: String): View? {
        ime.prefs().edit().putBoolean(Settings.PREF_SUGGEST_CLIPBOARD_CONTENT, true).commit()
        startField(ime, inputType)
        (ime.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(recentClip(clip))
        val ei = EditorInfo().apply { this.inputType = inputType }
        return ime.clipboardHistoryManager.getClipboardSuggestionView(ei, FrameLayout(ime))
    }

    @Test fun theClipboardSuggestionOfANumberFieldOffersWhatItAcceptsAndPastesIt() = withKeyboard { ime ->
        val view = assertNotNull(suggestion(ime, decimal, "1,5"), "positive control: a number is offered")
        assertEquals("1.5", view.findViewById<TextView>(R.id.clipboard_suggestion_text).text.toString()) // with the field's separator
        view.findViewById<View>(R.id.clipboard_suggestion_text).performClick()
        assertEquals("1.5", ShadowInputMethodService.text)
    }

    @Test fun theClipboardSuggestionIsFilteredLikeAPaste() = withKeyboard { ime ->
        val view = assertNotNull(suggestion(ime, number, "12abc3"))
        assertEquals("12", view.findViewById<TextView>(R.id.clipboard_suggestion_text).text.toString())
        view.findViewById<View>(R.id.clipboard_suggestion_text).performClick()
        assertEquals("12", ShadowInputMethodService.text)
    }

    @Test fun theClipboardSuggestionIsNotOfferedWhenTheFieldTakesNothingOfIt() = withKeyboard { ime ->
        assertNull(suggestion(ime, number, "NaN"))
        assertNull(suggestion(ime, number, "no digits"))
        assertNull(suggestion(ime, number, "1,234")) // read two ways: nothing is offered
        assertNotNull(suggestion(ime, text, "no digits"), "positive control: a text field is offered the same text")
    }

    @Test fun aHistoryEntryPastedIntoANumberFieldIsFiltered() = withKeyboard { ime ->
        startField(ime, number)
        val dao = assertNotNull(ClipboardDao.getInstance(ime))
        dao.addClip(System.currentTimeMillis(), "12abc")
        ime.mKeyboardActionListener.onCodeInput(KeyCode.CLIPBOARD, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        val view = KeyboardSwitcher::class.java.getDeclaredField("mClipboardHistoryView").apply { isAccessible = true }.get(KeyboardSwitcher.getInstance()) as ClipboardHistoryView
        assertEquals(1, dao.count(), "positive control: the entry is in the history")
        view.onKeyUp(dao.getAt(0).id)
        assertEquals("12", ShadowInputMethodService.text)
        assertEquals(1, dao.count()) // pasting stores nothing new
        assertFalse(dao.getAt(0).pinned)
    }
}
