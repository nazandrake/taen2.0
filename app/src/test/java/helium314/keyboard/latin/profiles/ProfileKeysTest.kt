// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.profiles

import helium314.keyboard.latin.settings.Settings
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/** I3: every PREF_ key of Settings.java has exactly one class in ProfileKeys. */
@RunWith(RobolectricTestRunner::class)
class ProfileKeysTest {
    private fun settingsKeys(): List<String> = Settings::class.java.fields
        .filter { it.name.startsWith("PREF_") && it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .map { it.get(null) as String }

    // ---- ----

    @Test fun everyPrefKeyOfSettingsHasAClass() {
        val keys = settingsKeys()
        assertEquals(true, keys.size > 100, "positive control: the keys were read (${keys.size})")
        assertEquals(emptyList(), ProfileKeys.unclassified(keys))
    }

    // ---- the classes, the validators, the strict values ---------------------------------------------------------------

    private val contractPrivacy = listOf(
        "always_incognito_mode", "secure_keyboard_window", "accessibility_warning", "overlay_mode", "enable_clipboard_history",
        "clipboard_history_retention_time", "clipboard_history_max_items", "suggest_clipboard_content", "clipboard_in_secure_fields", "use_personalized_dicts",
        "add_to_personal_dictionary", "save_subtype_per_app", "voice_typing", "nextword_model",
    )

    @Test fun thePrivacyClassIsExactlyTheContractList() {
        assertEquals(contractPrivacy.sorted(), ProfileKeys.privacyKeys.sorted())
        for (k in contractPrivacy) assertEquals(KeyClass.PRIVACY, ProfileKeys.classOf(k), k)
    }

    @Test fun theExcludedKeysOfTheContractAreExcluded() {
        val excluded = listOf(
            "recent_emojis", "last_shown_emoji_category_id", "last_shown_emoji_category_page_id", "incognito_apps", "app_languages",
            "saved_app_subtype_org.example", "space_bar_text", "version_code", "emoji_max_sdk", "dont_show_missing_dict_dialog", ProfileStore.KEY,
            "user_colors_light", "floating_height720", "floating_pos_x720", "custom_currency_key", "some_key_nobody_knows",
        )
        for (k in excluded) assertEquals(KeyClass.EXCLUDED, ProfileKeys.classOf(k), k)
        assertEquals(null, ProfileKeys.validate("recent_emojis", "x")) // excluded: no value is valid
        assertEquals("incognito_apps", Settings.PREF_INCOGNITO_APPS) // ProfileKeys names it by text (the P9 rule), this keeps the two equal
    }

    @Test fun includedKeysAndTheirBooleanIndexedFamiliesAreIncluded() {
        for (k in listOf("show_number_row", "font_scale", "vibrate_on", "keyboard_height_scale_false_false", "keyboard_height_scale_true_true",
            "one_handed_mode_enabled_false_false_false", "side_padding_scale_true_false_true", "floating_enabled1080", "layout_MAIN", "toolbar_keys", "enabled_subtypes"))
            assertEquals(KeyClass.INCLUDED, ProfileKeys.classOf(k), k)
        // a key of a family with a wrong index, a second family spelling or a custom layout name is not accepted
        for (k in listOf("keyboard_height_scale_false", "keyboard_height_scale_false_false_false", "keyboard_height_scale_x_y", "floating_enabled", "floating_enabled123456", "layout_NOT_A_TYPE"))
            assertEquals(KeyClass.EXCLUDED, ProfileKeys.classOf(k), k)
    }

    @Test fun noSavedKeyIsExcludedOrPrivacyAndTheListHasNoDuplicates() {
        val keys = ProfileKeys.savedKeys
        assertEquals(true, keys.size > 100, "positive control: ${keys.size} keys")
        assertEquals(keys.size, keys.toSet().size)
        for (k in keys) assertEquals(KeyClass.INCLUDED, ProfileKeys.classOf(k), k)
        assertEquals(emptySet(), keys.toSet() intersect ProfileKeys.privacyKeys.toSet())
    }

    @Test fun aPlantedUnclassifiedKeyIsFound() {
        // positive control of I3: the check is able to fail
        assertEquals(listOf("pref_added_later"), ProfileKeys.unclassified(settingsKeys() + "pref_added_later"))
    }

    @Test fun validatorsCheckTypeAndRangeAtTheEdges() {
        val h = ProfileKeys.heightKey()
        assertEquals("keyboard_height_scale_false_false", h)
        assertEquals(0.3f, ProfileKeys.validate(h, 0.3f))
        assertEquals(1.5f, ProfileKeys.validate(h, 1.5))
        assertEquals(null, ProfileKeys.validate(h, 0.29f))
        assertEquals(null, ProfileKeys.validate(h, 1.51f))
        assertEquals(null, ProfileKeys.validate(h, Float.NaN))
        assertEquals(null, ProfileKeys.validate(h, "1.0"))
        assertEquals(null, ProfileKeys.validate(h, true))
        assertEquals(true, ProfileKeys.validate("show_number_row", true))
        assertEquals(null, ProfileKeys.validate("show_number_row", 1))
        assertEquals(5, ProfileKeys.validate("clipboard_history_retention_time", 5))
        assertEquals(null, ProfileKeys.validate("clipboard_history_retention_time", 4))
        assertEquals(10080, ProfileKeys.validate("clipboard_history_retention_time", 10080))
        assertEquals(null, ProfileKeys.validate("clipboard_history_retention_time", 10081))
        assertEquals(null, ProfileKeys.validate("clipboard_history_retention_time", 5.5))
        assertEquals("block", ProfileKeys.validate("overlay_mode", "block"))
        assertEquals(null, ProfileKeys.validate("overlay_mode", "loud"))
        assertEquals(null, ProfileKeys.validate("horizontal_space_swipe", "FLY_AWAY"))
        assertEquals("SWITCH_LANGUAGE", ProfileKeys.validate("horizontal_space_swipe", "SWITCH_LANGUAGE"))
        assertEquals(null, ProfileKeys.validate("toolbar_keys", "NO_SUCH_KEY:true"))
        assertEquals("VOICE:true|CLIPBOARD:false", ProfileKeys.validate("toolbar_keys", "VOICE:true|CLIPBOARD:false"))
        assertEquals(null, ProfileKeys.validate("enabled_subtypes", "en-US§KeyboardLayoutSet=MAIN:custom.main.abc.json"))
        assertEquals(null, ProfileKeys.validate("font_scale", "x".repeat(10)))
    }

    @Test fun everyPrivacyKeyHasAStrictValueThatItsOwnValidatorAcceptsAndADefaultThatIsNotStricter() {
        for (k in ProfileKeys.privacyKeys) {
            val strict = ProfileKeys.strictValue(k)
            val default = ProfileKeys.privacyDefault(k)
            assertEquals(strict, ProfileKeys.validate(k, strict), k)
            assertEquals(default, ProfileKeys.validate(k, default), k)
            assertEquals(strict, ProfileKeys.stricter(k, default, strict), "$k: strict wins over the default")
            assertEquals(strict, ProfileKeys.stricter(k, strict, default), "$k: strict stays against the default")
        }
    }
}
