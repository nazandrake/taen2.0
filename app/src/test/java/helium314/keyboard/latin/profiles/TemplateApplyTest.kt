// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.profiles

import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** TP2: applying a template writes exactly its keys (privacy ones only stricter), nothing to the credential tier, nothing recorded. */
@RunWith(RobolectricTestRunner::class)
class TemplateApplyTest {
    private val dir = File("src/main/assets/templates")
    private fun template(id: String) = (TemplateCatalog({ dir.list()!!.toList() }, { File(dir, it).inputStream() }).load()
        .first { (it as TemplateEntry.Ok).template.id == id } as TemplateEntry.Ok).template

    @Test fun minimalWritesExactlyItsSixKeysInOneCommitAndTouchesNothingElse() {
        val de = FakePrefs(mapOf("show_number_row" to true, "recent_emojis" to "x", "font_scale" to 1.4f, "sound_on" to true))
        assertEquals(ProfileOutcome.DONE, TemplateApply.apply(template("minimal"), de))
        assertEquals(1, de.commits.size)
        assertEquals(setOf("show_number_row", "sound_on", "vibrate_on", "show_hints", "show_popup_hints", "show_emoji_key", "emoji_hold"), de.commits.single().keys) // positive control: its keys are written
        assertEquals(mapOf<String, Any>("show_number_row" to false, "recent_emojis" to "x", "font_scale" to 1.4f, "sound_on" to false,
            "vibrate_on" to true, "show_hints" to false, "show_popup_hints" to false, "show_emoji_key" to true, "emoji_hold" to "none"), de.map)
    }

    @Test fun aSecondApplyChangesNothingAndTheCredentialTierIsNeverTouched() {
        val de = FakePrefs()
        assertEquals(ProfileOutcome.DONE, TemplateApply.apply(template("large_one_handed"), de))
        val after = LinkedHashMap(de.map)
        de.commits.clear()
        assertEquals(ProfileOutcome.DONE, TemplateApply.apply(template("large_one_handed"), de))
        assertTrue(de.commits.isEmpty())
        assertEquals(after, de.map)
        assertEquals(1.3f, de.map["keyboard_height_scale_false_false"]); assertEquals(1.2f, de.map["font_scale"]); assertEquals(true, de.map["one_handed_mode_enabled_false_false_false"])
        // nothing records which template was applied: the only keys are the template's
        assertEquals(setOf("keyboard_height_scale_false_false", "font_scale", "one_handed_mode_enabled_false_false_false", "show_emoji_key"), de.map.keys)
    }

    @Test fun privacyMaxNeverLoosensAndMakesTheLooseOnesStrict() {
        val de = FakePrefs(mapOf("overlay_mode" to "block", "clipboard_history_retention_time" to 20, "enable_clipboard_history" to true, "use_personalized_dicts" to true))
        TemplateApply.apply(template("privacy_max"), de)
        assertEquals("block", de.map["overlay_mode"]); assertEquals(5, de.map["clipboard_history_retention_time"])
        assertEquals(false, de.map["enable_clipboard_history"]); assertEquals(false, de.map["use_personalized_dicts"]); assertEquals(true, de.map["always_incognito_mode"])
    }

    @Test fun aFailedCommitLeavesTheSettingsAsTheyWere() {
        val de = FakePrefs(mapOf("show_number_row" to true))
        de.failCommits = 1
        assertEquals(ProfileOutcome.WRITE_FAILED, TemplateApply.apply(template("minimal"), de))
        assertEquals(mapOf<String, Any>("show_number_row" to true), de.map)
    }

    @Test fun bulgarianDailyKeepsLearningOnAndNeverTurnsItBackOnForSomeoneWhoSwitchedItOff() {
        val fresh = FakePrefs()
        TemplateApply.apply(template("bulgarian_daily"), fresh)
        assertTrue("use_personalized_dicts" !in fresh.map, "learning is on by default: the template has nothing to write for it")
        assertEquals(true, fresh.map["show_emoji_key"])
        val off = FakePrefs(mapOf("use_personalized_dicts" to false))
        TemplateApply.apply(template("bulgarian_daily"), off)
        assertEquals(false, off.map["use_personalized_dicts"]) // a template can only make privacy stricter, so the user's choice stays
    }
}
