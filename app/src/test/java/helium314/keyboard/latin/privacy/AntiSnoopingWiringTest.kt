// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.EditorInfo
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.ShadowFacilitator2
import helium314.keyboard.latin.ShadowKeyboardSwitcher
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Production wiring (P9, P12, P13): the policy decisions reach the running keyboard service. Every test runs the real
 * LatinIME, its settings loader and its clipboard listener, so removing a call in the production code fails it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class, ShadowFacilitator2::class])
class AntiSnoopingWiringTest {
    private val text = InputType.TYPE_CLASS_TEXT

    private fun withKeyboard(test: (LatinIME, Context) -> Unit) {
        Privacy.forgetCachedState()
        ClipboardDao.forgetInstance()
        ShadowInputMethodService.reset()
        PrivacyWarnings.reset()
        val ime = Robolectric.setupService(LatinIME::class.java)
        Settings.init(ime) // the preferences objects were forgotten above: the singleton must read the new ones, as a fresh process does
        try {
            test(ime, ime.applicationContext)
        } finally {
            Privacy.unlock(ime)
            Privacy.forgetCachedState()
            ClipboardDao.forgetInstance()
            ShadowInputMethodService.reset()
            PrivacyWarnings.show = { context, message -> android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    private fun load(ime: LatinIME, inputType: Int = text, imeOptions: Int = 0, packageName: String? = "org.example.app") {
        val editor = EditorInfo().apply { this.inputType = inputType; this.imeOptions = imeOptions; this.packageName = packageName }
        Settings.getInstance().loadSettings(ime, Locale.ENGLISH, InputAttributes(editor, false, ime.packageName))
    }

    // ---- P9: the decision reaches SettingsValues -----------------------------------------------------------------------

    @Test fun aPasswordFieldTurnsOffIncognitoLearningKeyPreviewsAndTheClipboardSuggestion() = withKeyboard { ime, ctx ->
        ctx.prefs().edit().putBoolean(Settings.PREF_POPUP_ON, true).putBoolean(Settings.PREF_SUGGEST_CLIPBOARD_CONTENT, true).commit()
        load(ime) // positive control: a plain field keeps both features
        var v = Settings.getValues()
        assertTrue(v.mKeyPreviewPopupOn)
        assertTrue(v.mSuggestClipboardContent)
        assertFalse(v.mIncognitoModeEnabled)
        for (type in listOf(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)) {
            load(ime, type)
            v = Settings.getValues()
            assertTrue(v.mIncognitoModeEnabled, "type $type")
            assertFalse(v.mKeyPreviewPopupOn, "type $type")
            assertFalse(v.mSuggestClipboardContent, "type $type")
            assertFalse(v.mPrivacy.suggestionStrip, "type $type")
        }
    }

    @Test fun theNoPersonalizedLearningFlagIsHonouredInEveryInputClass() = withKeyboard { ime, _ -> // the old code read it for text fields only
        load(ime, InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertTrue(Settings.getValues().mIncognitoModeEnabled)
        load(ime, InputType.TYPE_CLASS_PHONE, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertTrue(Settings.getValues().mIncognitoModeEnabled)
        load(ime, text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertTrue(Settings.getValues().mIncognitoModeEnabled)
        load(ime, InputType.TYPE_CLASS_NUMBER, 0)
        assertFalse(Settings.getValues().mIncognitoModeEnabled)
    }

    @Test fun forceIncognitoAndTheListOfAppsReachTheSettingsValues() = withKeyboard { ime, ctx ->
        load(ime)
        assertFalse(Settings.getValues().mIncognitoModeEnabled)
        ctx.prefs().edit().putBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, true).commit()
        load(ime)
        assertTrue(Settings.getValues().mIncognitoModeEnabled)
        ctx.prefs().edit().putBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, false).commit()

        ctx.protectedPrefs().edit().putString(Settings.PREF_INCOGNITO_APPS, "org.bank.app\nchat.secret").commit()
        load(ime, packageName = "org.bank.app")
        assertTrue(Settings.getValues().mIncognitoModeEnabled)
        load(ime, packageName = "chat.secret")
        assertTrue(Settings.getValues().mIncognitoModeEnabled)
        load(ime, packageName = "org.example.app") // positive control: another app is unaffected
        assertFalse(Settings.getValues().mIncognitoModeEnabled)
    }

    @Test fun theDecisionReachesTheSuggestionSettingsSoThatIncognitoFieldsGetNoLearnedOrPersonalWords() = withKeyboard { ime, ctx ->
        load(ime)
        assertTrue(Settings.getValues().mSettingsValuesForSuggestion.mPersonalized) // positive control: a plain field
        load(ime, text or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertFalse(Settings.getValues().mSettingsValuesForSuggestion.mPersonalized)
        load(ime, text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertFalse(Settings.getValues().mSettingsValuesForSuggestion.mPersonalized)
        ctx.protectedPrefs().edit().putString(Settings.PREF_INCOGNITO_APPS, "org.bank.app").commit()
        load(ime, packageName = "org.bank.app")
        assertFalse(Settings.getValues().mSettingsValuesForSuggestion.mPersonalized)
        load(ime, packageName = "org.example.app")
        assertTrue(Settings.getValues().mSettingsValuesForSuggestion.mPersonalized)
        ctx.prefs().edit().putBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, true).commit()
        load(ime)
        assertFalse(Settings.getValues().mSettingsValuesForSuggestion.mPersonalized)
    }

    @Test fun aLockedScreenIsIncognito() = withKeyboard { ime, ctx ->
        load(ime)
        assertFalse(Settings.getValues().mIncognitoModeEnabled)
        ctx.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(Privacy.isLocked)
        load(ime)
        assertTrue(Settings.getValues().mIncognitoModeEnabled)
    }

    @Test fun theKeyboardNoticesThatTheFieldChangedItsPrivacyRulesWithoutChangingItsType() = withKeyboard { ime, _ ->
        val plain = EditorInfo().apply { inputType = text; packageName = "org.example.app" }
        load(ime)
        val values = Settings.getValues()
        assertTrue(values.isSameInputType(plain))
        assertFalse(values.isSameInputType(EditorInfo().apply { inputType = text; imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING; packageName = "org.example.app" }))
        assertFalse(values.isSameInputType(EditorInfo().apply { inputType = text; packageName = "org.bank.app" }))
    }

    // ---- P9/P10: the clipboard listener of the running service ----------------------------------------------------------

    private fun clip(text: String): ClipData {
        SystemClock.setCurrentTimeMillis(System.currentTimeMillis())
        return ClipData.newPlainText("label", text)
    }

    private fun copy(ctx: Context, text: String) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip(text))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun history(ctx: Context) = assertNotNull(ClipboardDao.getInstance(ctx)).getAll().map { it.text }

    @Test fun copiesAreKeptOutOfTheHistoryInIncognitoFieldsAppsAndForcedIncognito() = withKeyboard { ime, ctx ->
        load(ime)
        copy(ctx, "normal copy")
        assertEquals(listOf("normal copy"), history(ctx)) // positive control: the listener stores in a plain field

        ctx.protectedPrefs().edit().putString(Settings.PREF_INCOGNITO_APPS, "org.bank.app").commit()
        load(ime, packageName = "org.bank.app")
        copy(ctx, "copy in a listed app")
        load(ime, text or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        copy(ctx, "copy in a password field")
        load(ime, InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        copy(ctx, "copy in a no-learning field")
        ctx.prefs().edit().putBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, true).commit()
        load(ime)
        copy(ctx, "copy while forced incognito")
        assertEquals(listOf("normal copy"), history(ctx))

        ctx.prefs().edit().putBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, false).commit()
        load(ime)
        copy(ctx, "copy afterwards")
        assertEquals(setOf("normal copy", "copy afterwards"), history(ctx).toSet())
    }

    // (measured on the reference phone, Android 12): focus moving to a window with no text field calls onFinishInput and then
    // onStartInput with an empty editor of the new app (dumpsys: SESSION_CREATED_BY_IME, inputType=0x0), but no onStartInputView, so
    // the settings (and with them the PrivacyPolicy decision) still belong to the previous field. A copy made there ("copy" button
    // with the keyboard closed) is judged by the app that has the focus now.
    private fun windowWithoutField(ime: LatinIME, packageName: String) =
        ime.onStartInput(EditorInfo().apply { this.packageName = packageName }, false)

    @Test fun aCopyInAListedAppWithNoFieldIsNotStoredWhateverTheLastFieldWas() = withKeyboard { ime, ctx ->
        ctx.protectedPrefs().edit().putString(Settings.PREF_INCOGNITO_APPS, "org.bank.app").commit()
        load(ime) // the last field was a normal one of an unlisted app
        windowWithoutField(ime, "org.bank.app")
        copy(ctx, "copied in the listed app")
        assertEquals(emptyList(), history(ctx))
        windowWithoutField(ime, "org.example.app") // positive control: the same copy button in an unlisted app is stored
        copy(ctx, "copied in an unlisted app")
        assertEquals(listOf("copied in an unlisted app"), history(ctx))
    }

    @Test fun aCopyWithNoFieldIsNotBlockedByAPasswordFieldOfAnotherApp() = withKeyboard { ime, ctx ->
        load(ime, text or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        copy(ctx, "in the password field")
        assertEquals(emptyList(), history(ctx)) // the field itself is incognito
        windowWithoutField(ime, "org.example.reader") // the user left that app: normal use must keep working
        copy(ctx, "copied from a web page")
        assertEquals(listOf("copied from a web page"), history(ctx))
    }

    @Test fun aPreferenceWriteDoesNotBringBackThePreviousFieldsDecision() = withKeyboard { ime, ctx ->
        ctx.protectedPrefs().edit().putString(Settings.PREF_INCOGNITO_APPS, "org.bank.app").commit()
        load(ime) // the last field was a normal one of an unlisted app
        windowWithoutField(ime, "org.bank.app")
        val before = Settings.getValues()
        ctx.prefs().edit().putBoolean(Settings.PREF_POPUP_ON, false).commit() // the settings are rebuilt, with the previous field's attributes
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(Settings.getValues() !== before, "positive control: the preference write rebuilt the settings")
        copy(ctx, "copied in the listed app after a preference write")
        assertEquals(emptyList(), history(ctx))
    }

    @Test fun aNewFieldReplacesTheNoFieldDecision() = withKeyboard { ime, ctx ->
        ctx.protectedPrefs().edit().putString(Settings.PREF_INCOGNITO_APPS, "org.bank.app").commit()
        windowWithoutField(ime, "org.bank.app")
        load(ime) // a field of an unlisted app starts (the service reloads its settings)
        copy(ctx, "typed field copy")
        assertEquals(listOf("typed field copy"), history(ctx))
        windowWithoutField(ime, "org.bank.app")
        load(ime, text or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        copy(ctx, "password field copy")
        assertEquals(listOf("typed field copy"), history(ctx))
    }

    // ---- P12 and P13: the keyboard window -------------------------------------------------------------------------------

    @Test fun theKeyboardWindowIsSecureAccordingToTheSetting() = withKeyboard { ime, ctx ->
        val window = assertNotNull(ime.window.window)
        assertTrue(SecureWindow.isSecure(window)) // default: on
        ctx.prefs().edit().putBoolean(Settings.PREF_SECURE_KEYBOARD_WINDOW, false).commit()
        ime.onStartInputView(EditorInfo().apply { inputType = text }, false) // the service reloads its settings for a new field
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(SecureWindow.isSecure(window))
        ctx.prefs().edit().putBoolean(Settings.PREF_SECURE_KEYBOARD_WINDOW, true).commit()
        ime.onStartInputView(EditorInfo().apply { inputType = text }, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(SecureWindow.isSecure(window))
    }

    @Test fun theKeyboardWindowIsMarkedAsSensitiveForAccessibilityServices() = withKeyboard { ime, _ ->
        // Robolectric runs API 36 here; below API 34 the platform has no such flag (a documented gap on the reference phone)
        val decor = assertNotNull(ime.window.window).decorView
        assertTrue(decor.isAccessibilityDataSensitive)
    }

    @Test fun aRiskyAccessibilityServiceIsWarnedAboutWhenInputStarts() = withKeyboard { ime, ctx ->
        val shown = ArrayList<String>()
        PrivacyWarnings.show = { _, message -> shown.add(message) }
        PrivacyWarnings.services = { listOf(A11yService("x/Svc", "Screen watcher", readsContent = true, isTool = false)) }
        load(ime)
        ime.onStartInputView(EditorInfo().apply { inputType = text }, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, shown.size, shown.toString())
        assertTrue(shown[0].contains("Screen watcher"), shown[0])
        // the same service in the next field is not announced again; a setting turns the notice off
        ime.onStartInputView(EditorInfo().apply { inputType = text }, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, shown.size)
        ctx.prefs().edit().putBoolean(Settings.PREF_A11Y_WARNING, false).commit()
        PrivacyWarnings.reset()
        ime.onStartInputView(EditorInfo().apply { inputType = text }, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, shown.size)
    }
}
