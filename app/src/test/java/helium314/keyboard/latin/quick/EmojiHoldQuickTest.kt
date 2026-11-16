// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.quick

import helium314.keyboard.latin.profiles.FakePrefs
import helium314.keyboard.latin.profiles.KeyClass
import helium314.keyboard.latin.profiles.ProfileKeys
import helium314.keyboard.latin.settings.EmojiHold
import helium314.keyboard.latin.settings.Settings
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Open emoji by holding" in profiles and the quick panel: one INCLUDED device-protected key, five valid values, a cycle that writes exactly that key. */
@RunWith(RobolectricTestRunner::class)
class EmojiHoldQuickTest {
    @Test fun theKeyIsIncludedAndOnlyTheFiveValuesAreValid() {
        assertEquals("emoji_hold", Settings.PREF_EMOJI_HOLD)
        assertEquals(KeyClass.INCLUDED, ProfileKeys.classOf(Settings.PREF_EMOJI_HOLD))
        assertTrue(Settings.PREF_EMOJI_HOLD !in ProfileKeys.privacyKeys)
        for (h in EmojiHold.entries) assertNotNull(ProfileKeys.validate(Settings.PREF_EMOJI_HOLD, h.pref), h.pref)
        for (bad in listOf("", "Comma", "exotic", "comma ", 3, true, null)) assertNull(ProfileKeys.validate(Settings.PREF_EMOJI_HOLD, bad), bad.toString())
    }

    @Test fun theDefaultIsNoneAndEachTapStepsToTheNextAndBackToTheStart() {
        val prefs = FakePrefs()
        assertEquals(EmojiHold.NONE, QuickSettings.emojiHold(prefs))
        val seen = ArrayList<EmojiHold>()
        repeat(5) { seen.add(QuickSettings.cycleEmojiHold(prefs)) }
        assertEquals(listOf(EmojiHold.COMMA, EmojiHold.PERIOD, EmojiHold.SPACE, EmojiHold.LANGUAGE, EmojiHold.NONE), seen)
    }

    @Test fun theCycleWritesExactlyOneKeyInOneCommit() {
        val prefs = FakePrefs()
        QuickSettings.cycleEmojiHold(prefs)
        assertEquals(mapOf<String, Any>("emoji_hold" to "comma"), prefs.map)
        assertEquals(listOf(setOf("emoji_hold")), prefs.commits.map { it.keys })
    }

    @Test fun aStoredValueThatIsNotOneOfTheFiveIsReadAsTheDefault() {
        val prefs = FakePrefs()
        prefs.edit().putString("emoji_hold", "exotic").commit()
        assertEquals(EmojiHold.NONE, QuickSettings.emojiHold(prefs))
    }
}
