// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.ShadowFacilitator2
import helium314.keyboard.latin.ShadowKeyboardSwitcher
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The toolbar opened with the chevron key replaces the suggestion strip, and it stays open when a toolbar key was used or while
 * the user types, so that no word suggestion shows until the chevron is tapped again. "Auto hide toolbar" (the toolbar closes as
 * soon as suggestions are available) is what brings the strip back by itself: it is on unless the user turned it off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class, ShadowFacilitator2::class])
class ToolbarGivesWayToSuggestionsTest {
    private val text = InputType.TYPE_CLASS_TEXT

    private fun withKeyboard(test: (LatinIME, Context) -> Unit) {
        Privacy.forgetCachedState()
        ShadowInputMethodService.reset()
        val ime = Robolectric.setupService(LatinIME::class.java)
        Settings.init(ime) // a fresh process reads the new preferences
        try {
            test(ime, ime.applicationContext)
        } finally {
            Privacy.unlock(ime)
            Privacy.forgetCachedState()
            ShadowInputMethodService.reset()
        }
    }

    private fun load(ime: LatinIME, inputType: Int = text) {
        val editor = EditorInfo().apply { this.inputType = inputType; this.packageName = "org.example.app" }
        Settings.getInstance().loadSettings(ime, Locale.ENGLISH, InputAttributes(editor, false, ime.packageName))
    }

    // ---- pass 1: the default ------------------------------------------------------------------------------------------

    @Test fun aNewInstallClosesTheToolbarWhenSuggestionsAppear() = withKeyboard { ime, ctx ->
        ctx.prefs().edit().clear().commit() // a new install: nothing stored
        load(ime)
        assertTrue(Settings.getValues().mAutoHideToolbar)
    }

    // ---- pass 2: where it applies, and the user's choice ------------------------------------------------------------

    @Test fun theUserCanTurnItOffAndTheChoiceIsKept() = withKeyboard { ime, ctx ->
        ctx.prefs().edit().clear().commit()
        load(ime)
        assertTrue(Settings.getValues().mAutoHideToolbar) // control: on before the choice
        ctx.prefs().edit().putBoolean(Settings.PREF_AUTO_HIDE_TOOLBAR, false).commit()
        load(ime)
        assertFalse(Settings.getValues().mAutoHideToolbar)
        ctx.prefs().edit().putBoolean(Settings.PREF_AUTO_HIDE_TOOLBAR, true).commit()
        load(ime)
        assertTrue(Settings.getValues().mAutoHideToolbar)
    }

    @Test fun itNeedsSuggestionsInTheField() = withKeyboard { ime, ctx ->
        ctx.prefs().edit().clear().commit()
        load(ime)
        assertTrue(Settings.getValues().mAutoHideToolbar) // control: a plain text field
        load(ime, text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) // no suggestions are shown, so the toolbar has nothing to give way to
        assertFalse(Settings.getValues().mSuggestionsEnabled)
        assertFalse(Settings.getValues().mAutoHideToolbar)
        load(ime, text or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertFalse(Settings.getValues().mAutoHideToolbar)
        load(ime, InputType.TYPE_CLASS_NUMBER)
        assertFalse(Settings.getValues().mAutoHideToolbar)
        load(ime, text or InputType.TYPE_TEXT_VARIATION_URI) // control: a URL field shows suggestions
        assertTrue(Settings.getValues().mAutoHideToolbar)
    }

    @Test fun itNeedsTheSuggestionSettingAndAStripThatShowsThem() = withKeyboard { ime, ctx ->
        ctx.prefs().edit().clear().commit()
        ctx.prefs().edit().putBoolean(Settings.PREF_SHOW_SUGGESTIONS, false).commit()
        load(ime)
        assertFalse(Settings.getValues().mAutoHideToolbar)
        ctx.prefs().edit().clear().putString(Settings.PREF_TOOLBAR_MODE, "TOOLBAR_KEYS").commit() // toolbar only: no strip to go back to
        load(ime)
        assertFalse(Settings.getValues().mAutoHideToolbar)
        ctx.prefs().edit().clear().putString(Settings.PREF_TOOLBAR_MODE, "SUGGESTION_STRIP").commit()
        load(ime)
        assertTrue(Settings.getValues().mAutoHideToolbar) // control: the other mode that shows suggestions
    }
}
