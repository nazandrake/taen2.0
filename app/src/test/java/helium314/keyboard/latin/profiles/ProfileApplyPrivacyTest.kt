// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.profiles

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/** PR3 / TP2: applying never makes a PRIVACY key looser and does make it stricter, for every PRIVACY key, in every direction. */
@RunWith(RobolectricTestRunner::class)
class ProfileApplyPrivacyTest {
    // key -> (strict, loose); the loose values are legal values for the key (spec: contract 19.3, ClipboardLimits)
    private val pairs: Map<String, Pair<Any, Any>> = mapOf(
        "always_incognito_mode" to (true to false),
        "secure_keyboard_window" to (true to false),
        "accessibility_warning" to (true to false),
        "overlay_mode" to ("block" to "off"),
        "enable_clipboard_history" to (false to true),
        "clipboard_history_retention_time" to (5 to 60),
        "clipboard_history_max_items" to (1 to 50),
        "suggest_clipboard_content" to (false to true),
        "clipboard_in_secure_fields" to (false to true),
        "use_personalized_dicts" to (false to true),
        "add_to_personal_dictionary" to (false to true),
        "save_subtype_per_app" to (false to true),
        "voice_typing" to (false to true),
        "nextword_model" to (false to true),
    )

    private fun apply(current: Map<String, Any>, proposed: Map<String, Any>): Map<String, Any> {
        val de = FakePrefs(current)
        SettingsApply.commit(de, SettingsApply.plan(de, proposed))
        return de.map
    }

    @Test fun theTableCoversEveryPrivacyKey() {
        assertEquals(ProfileKeys.privacyKeys.sorted(), pairs.keys.sorted())
        for ((k, p) in pairs) {
            assertEquals(ProfileKeys.strictValue(k), p.first, k)
            assertEquals(p.first, ProfileKeys.validate(k, p.first), k)
            assertEquals(p.second, ProfileKeys.validate(k, p.second), k)
        }
    }

    @Test fun aStrictKeyStaysStrictWhenTheProfileHasTheLooseValue() {
        for ((k, p) in pairs) assertEquals(p.first, apply(mapOf(k to p.first), mapOf(k to p.second))[k], k)
    }

    @Test fun aLooseKeyBecomesStrictWhenTheProfileHasTheStrictValue() {
        for ((k, p) in pairs) assertEquals(p.first, apply(mapOf(k to p.second), mapOf(k to p.first))[k], k)
    }

    @Test fun anUnsetKeyIsJudgedByItsDefaultNotByNothing() {
        for ((k, p) in pairs) {
            val default = ProfileKeys.privacyDefault(k)
            val afterStrict = apply(emptyMap(), mapOf(k to p.first))
            val afterLoose = apply(emptyMap(), mapOf(k to p.second))
            if (default == p.first) { // the default is already strict: a loose value must not be written
                assertEquals(null, afterLoose[k], "$k: loose proposed over a strict default writes nothing")
            } else {
                assertEquals(p.first, afterStrict[k], "$k: strict proposed over a loose default is written")
                assertEquals(null, afterLoose[k], "$k: loose over the loose default is no change")
            }
        }
    }

    @Test fun bothStrictOrBothLooseChangeNothing() {
        for ((k, p) in pairs) {
            val strict = FakePrefs(mapOf(k to p.first))
            val loose = FakePrefs(mapOf(k to p.second))
            assertEquals(emptyList(), SettingsApply.plan(strict, mapOf(k to p.first)).map { it.key }, k)
            assertEquals(emptyList(), SettingsApply.plan(loose, mapOf(k to p.second)).map { it.key }, k)
        }
    }

    @Test fun numbersTakeTheLowerOfTheTwoInBothDirections() {
        assertEquals(10, apply(mapOf("clipboard_history_retention_time" to 30), mapOf("clipboard_history_retention_time" to 10))["clipboard_history_retention_time"])
        assertEquals(10, apply(mapOf("clipboard_history_retention_time" to 10), mapOf("clipboard_history_retention_time" to 30))["clipboard_history_retention_time"])
        assertEquals(20, apply(mapOf("clipboard_history_max_items" to 20), mapOf("clipboard_history_max_items" to 20))["clipboard_history_max_items"])
    }

    @Test fun theOverlayModeOrderIsBlockOverWarnOverOff() {
        val order = listOf("off", "warn", "block")
        for (current in order) for (proposed in order) {
            val expected = if (order.indexOf(proposed) > order.indexOf(current)) proposed else current
            assertEquals(expected, apply(mapOf("overlay_mode" to current), mapOf("overlay_mode" to proposed))["overlay_mode"], "$current <- $proposed")
        }
    }

    @Test fun anInvalidValueIsNeverWritten() {
        assertEquals(mapOf<String, Any>("use_personalized_dicts" to true), apply(mapOf("use_personalized_dicts" to true), mapOf("use_personalized_dicts" to "no")))
        assertEquals(emptyMap(), apply(emptyMap(), mapOf("clipboard_history_retention_time" to 1)))
    }

    @Test fun anIncludedKeyIsWrittenAsIsLooseOrNot() { // positive control: the writer is reached, only privacy keys are held back
        assertEquals(false, apply(mapOf("show_number_row" to true), mapOf("show_number_row" to false))["show_number_row"])
    }
}
