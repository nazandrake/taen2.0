// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.quick

import helium314.keyboard.latin.profiles.FakePrefs
import helium314.keyboard.latin.profiles.KeyClass
import helium314.keyboard.latin.profiles.ProfileKeys
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** E3: the guide is shown at the first start and only once; Skip and Done both set the one device-protected flag. */
@RunWith(RobolectricTestRunner::class)
class SetupGuideTest {
    // ---- ----

    @Test fun theGuideShowsOnAFreshInstallAndNotAfterItWasDone() {
        val de = FakePrefs()
        assertTrue(SetupGuide.shouldShow(de))
        assertTrue(SetupGuide.markDone(de))
        assertFalse(SetupGuide.shouldShow(de))
    }

    // ---- ----

    @Test fun theFlagIsOneBooleanAndItIsNeverPartOfAProfile() {
        val de = FakePrefs()
        SetupGuide.markDone(de)
        assertEquals(mapOf<String, Any>("setup_guide_done" to true), de.map)
        assertEquals(KeyClass.EXCLUDED, ProfileKeys.classOf("setup_guide_done"))
        assertEquals(1, de.commits.size)
    }

    @Test fun aFlagThatCouldNotBeStoredShowsTheGuideAgainNextTime() {
        val de = FakePrefs(); de.failCommits = 1
        assertFalse(SetupGuide.markDone(de))
        assertTrue(SetupGuide.shouldShow(de))
    }

    @Test fun aWipeOfTheSettingsBringsTheGuideBack() {
        val de = FakePrefs(mapOf("setup_guide_done" to true))
        assertFalse(SetupGuide.shouldShow(de))
        de.map.clear() // what FileWiper does to prefs_de with the settings scope (ProfileStorageTest covers the files)
        assertTrue(SetupGuide.shouldShow(de))
    }

    @Test fun theLanguageSwitchChoicesWriteOnlyIncludedKeysAndOnlyTheirOwn() {
        val expected = mapOf(
            LanguageSwitch.KEY to ("show_language_switch_key" to (true as Any)),
            LanguageSwitch.SPACE_SWIPE to ("horizontal_space_swipe" to ("SWITCH_LANGUAGE" as Any)),
            LanguageSwitch.LONG_PRESS_SPACE to ("prefs_long_press_keyboard_to_change_lang" to (true as Any)),
        )
        for (how in LanguageSwitch.entries) {
            val de = FakePrefs(mapOf("show_language_switch_key" to false, "prefs_long_press_keyboard_to_change_lang" to false, "horizontal_space_swipe" to "MOVE_CURSOR"))
            assertTrue(SetupGuide.chooseLanguageSwitch(de, how))
            val (key, value) = expected.getValue(how)
            assertEquals(setOf(key), de.commits.single().keys, "$how")
            assertEquals(value, de.map[key])
            assertEquals(KeyClass.INCLUDED, ProfileKeys.classOf(key))
        }
    }

    @Test fun enablingEnglishAndBulgarianRestoresTheTwoSubtypesAndTouchesNothingElse() {
        val de = FakePrefs(mapOf("enabled_subtypes" to "en-US§KeyboardLayoutSet=MAIN:qwerty", "show_number_row" to true))
        assertTrue(SetupGuide.enableEnglishAndBulgarian(de))
        assertEquals("en-US§KeyboardLayoutSet=MAIN:qwerty;bg§KeyboardLayoutSet=MAIN:bulgarian", de.map["enabled_subtypes"])
        assertEquals(true, de.map["show_number_row"])
        assertEquals(setOf("enabled_subtypes"), de.commits.single().keys)
    }
}
