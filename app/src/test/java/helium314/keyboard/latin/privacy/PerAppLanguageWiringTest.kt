// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.ShadowFacilitator2
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
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
 * "Remember language per app" in the running keyboard (a report on 1.0.1: English in one app, Bulgarian in another, back in the first app it
 * is Bulgarian there too). The setting existed (save_subtype_per_app) but was off by default, recorded only explicit switches, wrote to the
 * device-protected preferences and recorded in password fields, for incognito apps and without a bound. Every test runs the real LatinIME: onStartInput,
 * the language switch (switchToSubtype, what the language key calls) and the handler that applies an automatic switch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class, ShadowFacilitator2::class])
class PerAppLanguageWiringTest {
    private val text = InputType.TYPE_CLASS_TEXT
    private val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

    private fun withKeyboard(test: (LatinIME, Context) -> Unit) {
        Privacy.forgetCachedState()
        ClipboardDao.forgetInstance()
        ShadowInputMethodService.reset()
        val ime = Robolectric.setupService(LatinIME::class.java)
        Settings.init(ime)
        KeyboardSwitcher.getInstance().onCreateInputView(ime, true) // the keyboard view, as InputTest makes it: a language switch reloads the keyboard
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

    private fun subtype(language: String): InputMethodSubtype =
        SubtypeSettings.getEnabledSubtypes(true).first { it.toSettingsSubtype().locale.language == language }

    private fun current(): String = RichInputMethodManager.getInstance().currentSubtype.locale.language

    /** The app starts input in a field (what the system does when the user opens or returns to an app). */
    private fun open(ime: LatinIME, pkg: String, inputType: Int = text, imeOptions: Int = 0) {
        ShadowInputMethodService.currentPackage = pkg
        ShadowInputMethodService.currentInputType = inputType
        ShadowInputMethodService.currentImeOptions = imeOptions
        val editor = EditorInfo().apply { this.inputType = inputType; this.imeOptions = imeOptions; packageName = pkg }
        ime.onStartInput(editor, false)
        shadowOf(Looper.getMainLooper()).idle() // the automatic switch is posted to the handler
    }

    /** The language key (and the language picker) switch through this. */
    private fun useLanguage(ime: LatinIME, language: String) {
        ime.switchToSubtype(subtype(language))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun remembered(ctx: Context, pkg: String): String? = AppLanguageMemory.recall(ctx.protectedPrefs(), pkg)?.toSettingsSubtype()?.locale?.language

    // ---- the reported sequence ---------------------------------------------------------------------------------

    @Test fun englishInOneAppBulgarianInAnotherBackInTheFirstAppItIsEnglishAgain() = withKeyboard { ime, _ ->
        useLanguage(ime, "en")
        open(ime, "org.example.a")
        assertEquals("en", current())
        open(ime, "org.example.b")
        useLanguage(ime, "bg")
        assertEquals("bg", current())
        open(ime, "org.example.a") // back to the first app
        assertEquals("en", current(), "the language of the first app must come back")
        open(ime, "org.example.b")
        assertEquals("bg", current())
    }

    // ---- the whole contract -------------------------------------------------------------------------------------

    @Test fun theSettingIsOnByDefaultAndTheAppsAreRememberedByTheirFirstUseToo() = withKeyboard { ime, ctx ->
        assertTrue(Defaults.PREF_SAVE_SUBTYPE_PER_APP)
        assertTrue(ctx.prefs().getBoolean(Settings.PREF_SAVE_SUBTYPE_PER_APP, Defaults.PREF_SAVE_SUBTYPE_PER_APP))
        useLanguage(ime, "en")
        open(ime, "org.example.a") // never switched in this app: it is English, and that must be remembered
        assertEquals("en", remembered(ctx, "org.example.a"))
        open(ime, "org.example.b")
        useLanguage(ime, "bg")
        assertEquals("bg", remembered(ctx, "org.example.b"))
        assertEquals("en", remembered(ctx, "org.example.a")) // the other app is untouched
    }

    @Test fun theLanguageKeyStillSwitchesNormallyAndTheLastChoiceInAnAppWins() = withKeyboard { ime, ctx ->
        open(ime, "org.example.a")
        useLanguage(ime, "bg"); assertEquals("bg", current())
        useLanguage(ime, "en"); assertEquals("en", current())
        useLanguage(ime, "bg"); assertEquals("bg", current())
        assertEquals("bg", remembered(ctx, "org.example.a"))
        open(ime, "org.example.b") // a second app opened while Bulgarian is in use starts in Bulgarian and is remembered so
        assertEquals("bg", current())
        useLanguage(ime, "en")
        open(ime, "org.example.a")
        assertEquals("bg", current())
        open(ime, "org.example.b")
        assertEquals("en", current())
    }

    @Test fun aPasswordFieldRecordsNothingButRestoringStillWorks() = withKeyboard { ime, ctx ->
        open(ime, "org.example.pw", password)
        useLanguage(ime, "bg")
        assertNull(remembered(ctx, "org.example.pw"), "nothing is recorded in a password field")
        open(ime, "org.example.plain") // positive control: a normal field of another app records
        useLanguage(ime, "en")
        assertEquals("en", remembered(ctx, "org.example.plain"))
        // restoring: a language remembered earlier for the app is applied in its password field too
        AppLanguageMemory.remember(ctx.protectedPrefs(), "org.example.pw", subtype("bg").toSettingsSubtype().toPref())
        open(ime, "org.example.plain")
        assertEquals("en", current())
        open(ime, "org.example.pw", password)
        assertEquals("bg", current())
    }

    @Test fun anIncognitoAppAndAFieldThatAsksForNoLearningRecordNothing() = withKeyboard { ime, ctx ->
        ctx.protectedPrefs().edit().putString(Settings.PREF_INCOGNITO_APPS, "org.example.secret").commit()
        open(ime, "org.example.secret")
        useLanguage(ime, "bg")
        assertNull(remembered(ctx, "org.example.secret"))
        open(ime, "org.example.nolearn", text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        useLanguage(ime, "en")
        assertNull(remembered(ctx, "org.example.nolearn"))
        open(ime, "org.example.normal") // positive control
        useLanguage(ime, "bg")
        assertEquals("bg", remembered(ctx, "org.example.normal"))
        // restoring in an incognito app is fine
        AppLanguageMemory.remember(ctx.protectedPrefs(), "org.example.secret", subtype("en").toSettingsSubtype().toPref())
        open(ime, "org.example.secret")
        assertEquals("en", current())
    }

    @Test fun whileTheDeviceIsLockedNothingIsRecordedOrRestoredAndAfterTheUnlockTheMemoryIsBack() = withKeyboard { ime, ctx ->
        open(ime, "org.example.a")
        useLanguage(ime, "bg")
        assertEquals("bg", remembered(ctx, "org.example.a"))
        useLanguage(ime, "en")
        open(ime, "org.example.b")
        useLanguage(ime, "bg")
        Privacy.lock(ime) // the screen is off: the credential-encrypted tier is closed
        assertNull(remembered(ctx, "org.example.a"), "nothing is readable while locked")
        open(ime, "org.example.c")
        useLanguage(ime, "en")
        assertEquals("en", current()) // the language key works while locked
        open(ime, "org.example.a")
        assertEquals("en", current(), "nothing is restored while locked")
        Privacy.unlock(ime)
        assertEquals("bg", remembered(ctx, "org.example.b"))
        assertNull(remembered(ctx, "org.example.c"), "nothing was recorded while locked")
        open(ime, "org.example.b")
        assertEquals("bg", current())
    }

    @Test fun withTheSettingOffNothingIsRecordedOrRestored() = withKeyboard { ime, ctx ->
        ctx.prefs().edit().putBoolean(Settings.PREF_SAVE_SUBTYPE_PER_APP, false).commit()
        open(ime, "org.example.a")
        useLanguage(ime, "bg")
        assertNull(remembered(ctx, "org.example.a"))
        assertNull(ctx.protectedPrefs().getString(AppLanguageMemory.KEY, null))
        open(ime, "org.example.b")
        assertEquals("bg", current()) // no per-app restore: the language stays as it is
    }

    @Test fun theMapLivesOnlyInTheEncryptedCredentialPreferencesAndOnlyThere() = withKeyboard { ime, ctx ->
        open(ime, "org.example.a")
        useLanguage(ime, "bg")
        assertNotNull(ctx.protectedPrefs().getString(AppLanguageMemory.KEY, null))
        assertTrue(ctx.prefs().all.keys.none { it.startsWith(AppLanguageMemory.LEGACY_PREFIX) || it == AppLanguageMemory.KEY }, "not in the device-protected preferences")
    }

    @Test fun entriesOfTheEarlierFormatAreMovedIntoTheEncryptedMapOnceAndRemoved() = withKeyboard { ime, ctx ->
        val bg = subtype("bg").toSettingsSubtype().toPref()
        ctx.prefs().edit().putString(AppLanguageMemory.LEGACY_PREFIX + "org.example.old", bg).commit()
        Privacy.openAtStart(ctx) // the move is done at the start of the app and at the unlock, not while a field is being opened
        open(ime, "org.example.old")
        assertEquals("bg", current())
        assertEquals("bg", remembered(ctx, "org.example.old"))
        assertTrue(ctx.prefs().all.keys.none { it.startsWith(AppLanguageMemory.LEGACY_PREFIX) })
    }

    private fun legacyKeys(ctx: Context) = ctx.prefs().all.keys.filter { it.startsWith(AppLanguageMemory.LEGACY_PREFIX) }

    @Test fun turningTheSettingOffClearsTheListAndTheEarlierEntries() = withKeyboard { ime, ctx ->
        open(ime, "org.example.a")
        useLanguage(ime, "bg")
        ctx.prefs().edit().putString(AppLanguageMemory.LEGACY_PREFIX + "org.example.old", "bg").commit()
        assertEquals("bg", remembered(ctx, "org.example.a"))
        ctx.prefs().edit().putBoolean(Settings.PREF_SAVE_SUBTYPE_PER_APP, false).commit()
        shadowOf(Looper.getMainLooper()).idle() // the change reaches the listener on the main thread
        assertNull(ctx.protectedPrefs().getString(AppLanguageMemory.KEY, null))
        assertEquals(emptyList(), legacyKeys(ctx))
        // positive control: turning it on again starts an empty list that fills again
        ctx.prefs().edit().putBoolean(Settings.PREF_SAVE_SUBTYPE_PER_APP, true).commit()
        shadowOf(Looper.getMainLooper()).idle()
        open(ime, "org.example.b")
        useLanguage(ime, "bg")
        assertEquals("bg", remembered(ctx, "org.example.b"))
        assertNull(remembered(ctx, "org.example.a"))
    }

    @Test fun addingAnAppToTheIncognitoListRemovesItsLanguage() = withKeyboard { ime, ctx ->
        open(ime, "org.example.a")
        useLanguage(ime, "bg")
        open(ime, "org.example.b")
        useLanguage(ime, "en")
        Settings.setIncognitoApps(ctx, setOf("org.example.a"))
        assertNull(remembered(ctx, "org.example.a"))
        assertEquals("en", remembered(ctx, "org.example.b")) // positive control: another app keeps its entry
        assertEquals("org.example.a", ctx.protectedPrefs().getString(Settings.PREF_INCOGNITO_APPS, null))
    }

    @Test fun theClearActionEmptiesTheListAndTheEarlierEntries() = withKeyboard { ime, ctx ->
        open(ime, "org.example.a")
        useLanguage(ime, "bg")
        ctx.prefs().edit().putString(AppLanguageMemory.LEGACY_PREFIX + "org.example.old", "bg").commit()
        Settings.clearAppLanguages(ctx)
        assertNull(ctx.protectedPrefs().getString(AppLanguageMemory.KEY, null))
        assertEquals(emptyList(), legacyKeys(ctx))
        assertTrue(ctx.prefs().getBoolean(Settings.PREF_SAVE_SUBTYPE_PER_APP, Defaults.PREF_SAVE_SUBTYPE_PER_APP)) // the setting itself stays on
        open(ime, "org.example.b") // the list fills again
        useLanguage(ime, "bg")
        assertEquals("bg", remembered(ctx, "org.example.b"))
    }

    @Test fun theEarlierEntriesAreDeletedAtStartWhateverTheSettingAndOnlyMovedForAppsThatMayBeRemembered() = withKeyboard { _, ctx ->
        val bg = subtype("bg").toSettingsSubtype().toPref()
        fun plant() = ctx.prefs().edit().putString(AppLanguageMemory.LEGACY_PREFIX + "org.example.secret", bg)
            .putString(AppLanguageMemory.LEGACY_PREFIX + "org.example.plain", bg).commit()
        ctx.protectedPrefs().edit().putString(Settings.PREF_INCOGNITO_APPS, "org.example.secret").commit()
        // setting on: the incognito app's entry is deleted, the other one moves
        plant()
        Privacy.openAtStart(ctx)
        assertEquals(emptyList(), legacyKeys(ctx))
        assertNull(remembered(ctx, "org.example.secret"))
        assertEquals("bg", remembered(ctx, "org.example.plain"))
        // setting off: everything is deleted and nothing moves (the list is cleared as well)
        ctx.prefs().edit().putBoolean(Settings.PREF_SAVE_SUBTYPE_PER_APP, false).commit()
        shadowOf(Looper.getMainLooper()).idle()
        plant()
        Privacy.openAtStart(ctx)
        assertEquals(emptyList(), legacyKeys(ctx))
        assertNull(ctx.protectedPrefs().getString(AppLanguageMemory.KEY, null))
    }

    @Test fun whileLockedNothingIsSettledAndTheUnlockDoesIt() = withKeyboard { ime, ctx ->
        val bg = subtype("bg").toSettingsSubtype().toPref()
        ctx.prefs().edit().putString(AppLanguageMemory.LEGACY_PREFIX + "org.example.plain", bg).commit()
        Privacy.lock(ime)
        Privacy.openAtStart(ctx)
        assertEquals(listOf(AppLanguageMemory.LEGACY_PREFIX + "org.example.plain"), legacyKeys(ctx)) // the closed tier cannot be written or read: deferred
        Privacy.unlock(ime)
        assertEquals(emptyList(), legacyKeys(ctx))
        assertEquals("bg", remembered(ctx, "org.example.plain"))
    }
}
