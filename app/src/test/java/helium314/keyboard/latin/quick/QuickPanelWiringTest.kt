// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.quick

import android.content.Context
import android.view.KeyEvent
import android.view.View
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.KeyboardWrapperView
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.ShadowFacilitator2
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.getCodeForToolbarKey
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E1 through the running keyboard (the production entry point): the QUICK_SETTINGS key code reaches the panel, the panel is a child of the keyboard view,
 * its controls write the preferences and a layout reload that they cause does not close it. Remove the branch in KeyboardActionListenerImpl, the panel
 * in KeyboardSwitcher.onCreateInputView or the toolbar key mapping and a test here fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class, ShadowFacilitator2::class])
class QuickPanelWiringTest {
    private fun withKeyboard(test: (LatinIME, Context) -> Unit) {
        Privacy.forgetCachedState()
        ClipboardDao.forgetInstance()
        ShadowInputMethodService.reset()
        val ime = Robolectric.setupService(LatinIME::class.java)
        Settings.init(ime)
        KeyboardSwitcher.getInstance().onCreateInputView(ime, true)
        KeyboardSwitcher.getInstance().reloadMainKeyboard()
        try {
            test(ime, ime.applicationContext)
        } finally {
            Privacy.unlock(ime)
            Privacy.forgetCachedState()
            ClipboardDao.forgetInstance()
            ShadowInputMethodService.reset()
        }
    }

    private fun press(ime: LatinIME, code: Int) = ime.mKeyboardActionListener.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)

    @Test fun theToolbarKeyOpensAndClosesThePanelThroughTheKeyboardsListener() = withKeyboard { ime, _ ->
        val switcher = KeyboardSwitcher.getInstance()
        val panel = assertNotNull(switcher.quickPanelView)
        assertTrue(panel.parent is KeyboardWrapperView, "the panel is a child of the keyboard view wrapper")
        assertFalse(switcher.isShowingQuickPanel())
        press(ime, KeyCode.QUICK_SETTINGS)
        assertTrue(switcher.isShowingQuickPanel())
        assertEquals(View.VISIBLE, panel.visibility)
        press(ime, KeyCode.QUICK_SETTINGS)
        assertFalse(switcher.isShowingQuickPanel())
    }

    /** A custom layout or toolbar key with the F12 code must send KEYCODE_F12, not open the panel. */
    @Test fun theF12KeyStillSendsF12AndDoesNotOpenThePanel() = withKeyboard { ime, _ ->
        val switcher = KeyboardSwitcher.getInstance()
        press(ime, KeyCode.F12)
        assertFalse(switcher.isShowingQuickPanel(), "F12 is not the panel key")
        assertEquals(listOf(KeyEvent.KEYCODE_F12), ShadowInputMethodService.keyDownCodes)
        // positive control: the panel key still opens the panel and sends no key
        press(ime, KeyCode.QUICK_SETTINGS)
        assertTrue(switcher.isShowingQuickPanel())
        assertEquals(listOf(KeyEvent.KEYCODE_F12), ShadowInputMethodService.keyDownCodes)
    }

    @Test fun theToolbarKeyIsMappedToTheCodeAndIsOnByDefault() {
        assertEquals(KeyCode.QUICK_SETTINGS, getCodeForToolbarKey(ToolbarKey.QUICK_SETTINGS))
    }

    @Test fun theControlsWriteThePreferencesAndTheReloadTheyCauseKeepsThePanelOpen() = withKeyboard { ime, ctx ->
        val switcher = KeyboardSwitcher.getInstance()
        val panel = assertNotNull(switcher.quickPanelView)
        val ceBefore = ctx.protectedPrefs().all.toMap()
        press(ime, KeyCode.QUICK_SETTINGS)

        panel.perform(QuickControl.NUMBER_ROW)
        assertEquals(true, ctx.prefs().getBoolean("show_number_row", false))
        assertTrue(switcher.isShowingQuickPanel(), "the keyboard reload of the number row does not close the panel")
        panel.perform(QuickControl.HAPTICS)
        assertEquals(false, ctx.prefs().getBoolean("vibrate_on", true))
        panel.perform(QuickControl.SOUND)
        assertEquals(true, ctx.prefs().getBoolean("sound_on", false))
        panel.perform(QuickControl.GLIDE)
        assertEquals(false, ctx.prefs().getBoolean("gesture_input", true))
        panel.perform(QuickControl.EMOJI_KEY)
        assertEquals(false, ctx.prefs().getBoolean("show_emoji_key", true)) // on by default, so the first tap hides it
        assertTrue(switcher.isShowingQuickPanel())
        panel.perform(QuickControl.HEIGHT, up = false)
        assertEquals(1.05f, ctx.prefs().getFloat("keyboard_height_scale_false_false", 1f)) // one step below the portrait default 1.1
        assertEquals(1.05f, Settings.getValues().mKeyboardHeightScale) // the running keyboard saw it
        panel.perform(QuickControl.NUMBER_ROW)
        assertEquals(false, ctx.prefs().getBoolean("show_number_row", true))
        assertEquals(ceBefore, ctx.protectedPrefs().all.toMap(), "the panel never writes the credential-encrypted preferences")
    }

    @Test fun theLayoutTogglesGoThroughTheKeyboardsOwnCodesAndWriteTheKeyThePanelNames() = withKeyboard { ime, ctx ->
        val panel = assertNotNull(KeyboardSwitcher.getInstance().quickPanelView)
        press(ime, KeyCode.QUICK_SETTINGS)
        panel.perform(QuickControl.ONE_HANDED)
        assertEquals(true, ctx.prefs().getBoolean("one_handed_mode_enabled_false_false_false", false), "one-handed")
        panel.perform(QuickControl.SPLIT)
        assertEquals(true, ctx.prefs().getBoolean("split_keyboard", false), "split")
        assertTrue(KeyboardSwitcher.getInstance().isShowingQuickPanel())
    }

    @Test fun anotherLayoutChangeClosesThePanel() = withKeyboard { ime, _ ->
        val switcher = KeyboardSwitcher.getInstance()
        press(ime, KeyCode.QUICK_SETTINGS)
        assertTrue(switcher.isShowingQuickPanel())
        switcher.reloadMainKeyboard() // not caused by the panel
        assertFalse(switcher.isShowingQuickPanel())
        press(ime, KeyCode.QUICK_SETTINGS)
        switcher.onHideWindow()
        assertFalse(switcher.isShowingQuickPanel())
    }
}
