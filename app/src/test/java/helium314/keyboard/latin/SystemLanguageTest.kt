// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.mainLayoutName
import helium314.keyboard.latin.utils.mainLayoutNameOrQwerty
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The system and other apps learn the typing language from the keyboard's current subtype (the spell checker of the phone, translation offers).
 * Taen keeps its languages to itself (method_dummy.xml), so it tells the system the language in use whenever it changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class, ShadowFacilitator2::class])
class SystemLanguageTest {
    @AfterTest fun after() {
        Privacy.forgetCachedState()
        ClipboardDao.forgetInstance()
        ShadowInputMethodService.reset()
    }

    private fun service(): LatinIME {
        Privacy.forgetCachedState()
        ShadowInputMethodService.reset()
        val ime = Robolectric.setupService(LatinIME::class.java)
        Settings.init(ime)
        KeyboardSwitcher.getInstance().onCreateInputView(ime, true)
        return ime
    }

    private fun bulgarian(layout: String) = SubtypeSettings.getResourceSubtypesForLocale(Locale.forLanguageTag("bg")).first { it.mainLayoutName() == layout }

    private val english get() = SubtypeSettings.getResourceSubtypesForLocale(Locale.US).first { it.mainLayoutNameOrQwerty() == "qwerty" }

    private val ownId get() = "${BuildConfig.APPLICATION_ID}/LatinIME" // as ShadowInputMethodManager2 lists this keyboard

    private val calls get() = ShadowInputMethodService.systemSubtypes.size

    /** The system's subtype: the language, nothing else, and the only one registered, overriding the implicitly enabled ones. */
    private fun assertSystemShows(locale: String, tag: String) {
        val shown = ShadowInputMethodService.systemSubtypes.last()
        assertEquals(locale, shown.locale)
        assertEquals(tag, shown.languageTag)
        assertEquals("keyboard", shown.mode)
        assertEquals(RichInputMethodManager.SYSTEM_LANGUAGE_EXTRA_VALUE, shown.extraValue)
        assertTrue(shown.overridesImplicitlyEnabledSubtype())
        assertFalse(shown.isAuxiliary)
        assertNotEquals(0x7000000f, shown.hashCode(), "not the dummy subtype")
        assertEquals(ownId to listOf(shown), ShadowInputMethodService.additionalSubtypeCalls.last(), "registered before the switch, alone")
        assertEquals(ShadowInputMethodService.systemSubtypes.size, ShadowInputMethodService.additionalSubtypeCalls.size)
        assertEquals(shown, ShadowInputMethodService.systemCurrent, "the system shows it now")
    }

    @Test fun typingBulgarianTellsTheSystemBulgarian() {
        val ime = service()
        ime.switchToSubtype(bulgarian("bulgarian"))
        val shown = ShadowInputMethodService.systemSubtypes.last()
        assertEquals("bg", shown.languageTag)
    }

    @Test fun everyBulgarianLayoutTellsTheSystemBulgarianAndEnglishTellsEnglish() {
        val ime = service()
        for (layout in listOf("bulgarian", "bulgarian_bds", "bulgarian_bekl")) {
            ime.switchToSubtype(english)
            assertEquals(Locale.US, richImm.currentSubtypeLocale)
            assertSystemShows("en_US", "en-US")
            ime.switchToSubtype(bulgarian(layout))
            assertEquals(layout, richImm.currentSubtype.rawSubtype.mainLayoutName(), "positive control: Taen uses the layout")
            assertSystemShows("bg", "bg")
        }
    }

    @Test fun theLanguageKeyTellsTheSystemTheNextLanguage() {
        val ime = service()
        ime.switchToSubtype(english)
        assertSystemShows("en_US", "en-US")
        ime.switchToNextSubtype() // the default languages: English (US) and Bulgarian
        assertEquals("bg", richImm.currentSubtypeLocale.language, "positive control: the key switched to Bulgarian")
        assertSystemShows("bg", "bg")
        ime.switchToNextSubtype()
        assertEquals(Locale.US, richImm.currentSubtypeLocale)
        assertSystemShows("en_US", "en-US")
    }

    @Test fun anotherLayoutOfTheSameLanguageCallsNothing() {
        val ime = service()
        ime.switchToSubtype(bulgarian("bulgarian"))
        val before = calls
        ime.switchToSubtype(bulgarian("bulgarian_bds"))
        assertEquals("bulgarian_bds", richImm.currentSubtype.rawSubtype.mainLayoutName(), "positive control: the layout changed")
        assertEquals(before, calls, "the system already shows Bulgarian")
        ime.switchToSubtype(english) // positive control: another language is told
        assertEquals(before + 1, calls)
    }

    @Test fun theSystemHandingBackItsOwnSubtypeNeverChangesTaensLanguage() {
        val ime = service()
        ime.switchToSubtype(bulgarian("bulgarian_bds"))
        val shownBg = ShadowInputMethodService.systemSubtypes.last()
        val before = calls

        ime.onCurrentInputMethodSubtypeChanged(shownBg) // the system confirms the subtype it was told
        assertEquals("bulgarian_bds", richImm.currentSubtype.rawSubtype.mainLayoutName())
        assertEquals(before, calls, "nothing to correct")

        val englishOnly = RichInputMethodManager.systemSubtypeFor(english)!!
        ShadowInputMethodService.systemCurrent = englishOnly // the system went back to an older language
        ime.onCurrentInputMethodSubtypeChanged(englishOnly)
        assertEquals("bulgarian_bds", richImm.currentSubtype.rawSubtype.mainLayoutName(), "Taen keeps its language")
        assertEquals(before + 1, calls, "the system is told Bulgarian again")
        assertSystemShows("bg", "bg")

        val dummySubtype = InputMethodSubtype.InputMethodSubtypeBuilder() // as in method_dummy.xml
            .setSubtypeId(0x7000000f).setSubtypeExtraValue("dummy").setSubtypeMode("keyboard").setIsAsciiCapable(true).build()
        ShadowInputMethodService.systemCurrent = dummySubtype // the system set the dummy subtype again
        ime.onCurrentInputMethodSubtypeChanged(dummySubtype)
        assertEquals("bulgarian_bds", richImm.currentSubtype.rawSubtype.mainLayoutName())
        assertEquals(before + 2, calls, "and again after the dummy subtype")
        assertSystemShows("bg", "bg")

        ime.onCurrentInputMethodSubtypeChanged(english) // positive control: one of Taen's own subtypes still switches
        assertEquals(Locale.US, richImm.currentSubtypeLocale)
    }

    @Test fun startingInputTellsTheSystemAgainWhenItShowsAnotherLanguage() {
        val ime = service()
        ime.switchToSubtype(bulgarian("bulgarian"))
        val before = calls
        val ei = EditorInfo().also { it.inputType = InputType.TYPE_CLASS_TEXT }
        ime.mHandler.onStartInput(ei, false)
        ime.mHandler.onStartInputView(ei, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, calls, "the system still shows Bulgarian")
        ShadowInputMethodService.systemCurrent = RichInputMethodManager.systemSubtypeFor(english)!! // another keyboard or a restart changed it
        ime.mHandler.onStartInput(ei, false)
        ime.mHandler.onStartInputView(ei, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("bg", richImm.currentSubtypeLocale.language)
        assertEquals(before + 1, calls, "the start of input told Bulgarian again")
        assertSystemShows("bg", "bg")
    }

    @Test fun aSubtypeWithoutLanguageIsNotTold() {
        val noLanguage = SubtypeSettings.getResourceSubtypesForLocale(Locale.forLanguageTag("zz")).first()
        assertNull(RichInputMethodManager.systemSubtypeFor(noLanguage))
        assertEquals("bg", RichInputMethodManager.systemSubtypeFor(bulgarian("bulgarian_bekl"))!!.languageTag, "positive control")
    }

    private val richImm get() = RichInputMethodManager.getInstance()
}
