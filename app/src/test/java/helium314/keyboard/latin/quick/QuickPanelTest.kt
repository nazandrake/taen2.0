// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.quick

import helium314.keyboard.latin.profiles.FakePrefs
import helium314.keyboard.latin.profiles.KeyClass
import helium314.keyboard.latin.profiles.ProfileKeys
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** E1 and E2: the quick panel's eight controls, each writing exactly one INCLUDED device-protected key, and the height steps. */
@RunWith(RobolectricTestRunner::class)
class QuickPanelTest {
    private val portrait = ScreenState(landscape = false, split = false, folded = false, widthPixels = 1080)

    // ---- ----

    @Test fun togglingTheNumberRowWritesExactlyItsKey() {
        val de = FakePrefs()
        assertEquals(true, QuickSettings.toggle(de, QuickControl.NUMBER_ROW, portrait))
        assertEquals(mapOf<String, Any>("show_number_row" to true), de.map)
        assertEquals(listOf(setOf("show_number_row")), de.commits.map { it.keys })
    }

    // ---- ----

    @Test fun e1ThereAreNineControlsAndEveryKeyIsIncludedNeverPrivacyNeverTheCredentialTier() {
        assertEquals(9, QuickControl.entries.size)
        val ceKeys = setOf("taen_profiles", "incognito_apps", "app_languages")
        for (landscape in listOf(false, true)) for (split in listOf(false, true)) for (folded in listOf(false, true)) {
            val s = ScreenState(landscape, split, folded, 1080)
            val keys = QuickControl.entries.map { QuickSettings.keyOf(it, s) }
            assertEquals(9, keys.toSet().size, "every control has its own key: $keys")
            for (k in keys) {
                assertEquals(KeyClass.INCLUDED, ProfileKeys.classOf(k), "$k ($landscape $split $folded)")
                assertTrue(k !in ceKeys && k !in ProfileKeys.privacyKeys, k)
            }
        }
    }

    @Test fun eachSwitchWritesExactlyItsOneKeyInOneCommitAndNothingElse() {
        val switches = QuickControl.entries.filter { it != QuickControl.HEIGHT }
        val expected = mapOf(
            QuickControl.NUMBER_ROW to "show_number_row", QuickControl.ONE_HANDED to "one_handed_mode_enabled_false_false_false",
            QuickControl.SPLIT to "split_keyboard", QuickControl.FLOATING to "floating_enabled1080", QuickControl.HAPTICS to "vibrate_on",
            QuickControl.SOUND to "sound_on", QuickControl.GLIDE to "gesture_input", QuickControl.EMOJI_KEY to "show_emoji_key",
        )
        for (c in switches) {
            val de = FakePrefs(mapOf("recent_emojis" to "x", "incognito_apps_marker" to 1))
            QuickSettings.toggle(de, c, portrait)
            assertEquals(1, de.commits.size, "$c")
            assertEquals(setOf(expected.getValue(c)), de.commits.single().keys, "$c")
            assertEquals(QuickSettings.keyOf(c, portrait), expected.getValue(c))
        }
    }

    @Test fun aSwitchStartsFromItsDefaultAndFlipsBackAndForth() {
        for ((c, default) in listOf(QuickControl.NUMBER_ROW to false, QuickControl.HAPTICS to true, QuickControl.SOUND to false, QuickControl.GLIDE to true, QuickControl.SPLIT to false, QuickControl.EMOJI_KEY to true)) {
            val de = FakePrefs()
            assertEquals(default, QuickSettings.isOn(de, c, portrait), "$c default")
            assertEquals(!default, QuickSettings.toggle(de, c, portrait), "$c first")
            assertEquals(!default, QuickSettings.isOn(de, c, portrait))
            assertEquals(default, QuickSettings.toggle(de, c, portrait), "$c back")
        }
    }

    @Test fun theKeysFollowTheScreenState() {
        assertEquals("split_keyboard_folded_landscape", QuickSettings.keyOf(QuickControl.SPLIT, ScreenState(true, false, true, 1)))
        assertEquals("split_keyboard_landscape", QuickSettings.keyOf(QuickControl.SPLIT, ScreenState(true, false, false, 1)))
        assertEquals("split_keyboard_folded", QuickSettings.keyOf(QuickControl.SPLIT, ScreenState(false, false, true, 1)))
        assertEquals("one_handed_mode_enabled_true_true_false", QuickSettings.keyOf(QuickControl.ONE_HANDED, ScreenState(true, true, false, 1)))
        assertEquals("keyboard_height_scale_true_false", QuickSettings.keyOf(QuickControl.HEIGHT, ScreenState(true, true, false, 1)))
        assertEquals("keyboard_height_scale_false_true", QuickSettings.keyOf(QuickControl.HEIGHT, ScreenState(false, false, true, 1)))
        assertEquals("floating_enabled720", QuickSettings.keyOf(QuickControl.FLOATING, ScreenState(false, false, false, 720)))
    }

    @Test fun aFailedCommitLeavesTheStateAsItWas() {
        val de = FakePrefs()
        de.failCommits = 1
        assertEquals(false, QuickSettings.toggle(de, QuickControl.NUMBER_ROW, portrait)) // still off
        assertTrue(de.map.isEmpty())
        assertEquals(true, QuickSettings.toggle(de, QuickControl.NUMBER_ROW, portrait)) // positive control
    }

    @Test fun e2HeightMovesInStepsOfFiveHundredthsAndStaysBetweenThreeTenthsAndOneAndAHalf() {
        val key = "keyboard_height_scale_false_false"
        fun step(from: Float?, up: Boolean): Pair<Float, List<Map<String, Any?>>> {
            val de = FakePrefs(if (from == null) emptyMap() else mapOf(key to from))
            val result = QuickSettings.stepHeight(de, portrait, up)
            return result to de.commits
        }
        assertEquals(0.3f, step(0.3f, false).first); assertTrue(step(0.3f, false).second.isEmpty(), "at the lower limit nothing is written")
        assertEquals(0.35f, step(0.3f, true).first)
        assertEquals(1.5f, step(1.45f, true).first)
        assertEquals(1.5f, step(1.5f, true).first); assertTrue(step(1.5f, true).second.isEmpty())
        assertEquals(1.45f, step(1.5f, false).first)
        assertEquals(0.95f, step(null, false).first); assertEquals(1.05f, step(null, true).first) // from the default 1.0
        assertEquals(0.3f, step(0.32f, false).first); assertEquals(1.5f, step(1.48f, true).first) // a value off the grid is pulled into the range
        val de = FakePrefs(); QuickSettings.stepHeight(de, portrait, true)
        assertEquals(mapOf<String, Any>(key to 1.05f), de.map)
        assertEquals(setOf(key), de.commits.single().keys)
        assertEquals(1.05f, ProfileKeys.validate(key, de.map[key]))
    }

    @Test fun everyStepFromBottomToTopStaysOnTheGridAndIsAValidProfileValue() {
        val de = FakePrefs(mapOf("keyboard_height_scale_false_false" to 0.3f))
        var v = 0.3f; var count = 0
        while (true) { val n = QuickSettings.stepHeight(de, portrait, true); if (n == v) break; v = n; count++; assertTrue(ProfileKeys.validate("keyboard_height_scale_false_false", v) != null) }
        assertEquals(1.5f, v); assertEquals(24, count) // (1.5 - 0.3) / 0.05
    }
}
