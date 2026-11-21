// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.profiles

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TemplateCatalogTest {
    private val dir = File("src/main/assets/templates")
    private fun real() = TemplateCatalog({ dir.list()!!.toList() }, { File(dir, it).inputStream() })
    private fun fixtures(files: Map<String, String>) = TemplateCatalog({ files.keys.toList() }, { ByteArrayInputStream(files.getValue(it).toByteArray()) })
    private fun templates() = real().load().associate { val t = (it as TemplateEntry.Ok).template; t.id to t }

    // ---- ----

    @Test fun theRealAssetsGiveExactlyTheFourTemplates() {
        val ids = real().load().map { (it as TemplateEntry.Ok).template.id }
        assertEquals(listOf("bulgarian_daily", "large_one_handed", "minimal", "privacy_max"), ids.sorted())
    }

    // ---- TP1 and TP3 ----------------------------------------------------------------------------------------------

    @Test fun tp1EveryTemplateHasTheContractValuesAndNothingElse() {
        val t = templates()
        assertEquals(
            mapOf<String, Any>("show_number_row" to false, "show_hints" to false, "show_popup_hints" to false, "sound_on" to false, "vibrate_on" to true, "show_emoji_key" to true,
                "emoji_hold" to "none"), // the plain keyboard keeps every key as it is, no hold opens emoji
            t.getValue("minimal").values,
        )
        assertEquals(
            mapOf<String, Any>(
                "enabled_subtypes" to "en-US§KeyboardLayoutSet=MAIN:qwerty;bg§KeyboardLayoutSet=MAIN:bulgarian", // en-US QWERTY and the Bulgarian phonetic layout ("bulgarian")
                "show_language_switch_key" to true, "smart_quotes" to true, "gesture_input" to true, "show_emoji_key" to true, "emoji_hold" to "comma", // hold the comma key for emoji
                "use_personalized_dicts" to true, // learning stays on (it is the default; a template can never turn a privacy key from strict to loose)
            ),
            t.getValue("bulgarian_daily").values,
        )
        assertEquals(
            mapOf<String, Any>(
                "always_incognito_mode" to true, "secure_keyboard_window" to true, "accessibility_warning" to true, "overlay_mode" to "block",
                "enable_clipboard_history" to false, "clipboard_history_retention_time" to 5, "clipboard_history_max_items" to 1,
                "suggest_clipboard_content" to false, "clipboard_in_secure_fields" to false, "use_personalized_dicts" to false, "add_to_personal_dictionary" to false,
                "save_subtype_per_app" to false, "voice_typing" to false, "nextword_model" to false,
            ),
            t.getValue("privacy_max").values,
        )
        assertEquals(
            mapOf<String, Any>("keyboard_height_scale_false_false" to 1.3f, "font_scale" to 1.2f, "one_handed_mode_enabled_false_false_false" to true, "show_emoji_key" to true),
            t.getValue("large_one_handed").values,
        )
        for (template in t.values) {
            assertEquals(1, template.version, template.id)
            for (k in template.values.keys) assertNotEquals(KeyClass.EXCLUDED, ProfileKeys.classOf(k), "${template.id}: $k")
        }
    }

    @Test fun thePrivacyMaxTemplateSetsEveryPrivacyKeyToItsStrictValue() {
        val v = templates().getValue("privacy_max").values
        assertEquals(ProfileKeys.privacyKeys.sorted(), v.keys.sorted())
        for (k in ProfileKeys.privacyKeys) assertEquals(ProfileKeys.strictValue(k), v[k], k)
    }

    @Test fun everyTemplateNamesItsTitleAndSummaryStringsAndTheFilesAreFormatOne() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        for (template in templates().values) {
            for (name in listOf(template.titleRes, template.summaryRes)) {
                val id = ctx.resources.getIdentifier(name, "string", ctx.packageName)
                assertNotEquals(0, id, name)
                assertTrue(ctx.getString(id).isNotBlank(), name)
            }
            assertTrue(dir.resolve(template.id + ".json").readText().contains("\"format\": 1"), template.id)
        }
    }

    private fun good(id: String = "t", values: String = """{"show_number_row": true}""", extra: String = "") =
        """{"format":1,"id":"$id","version":1,"title":"template_minimal_title","summary":"template_minimal_summary","values":$values$extra}"""

    @Test fun tp3ADamagedAssetRejectsTheWholeTemplateAndIsListedUnavailable() {
        val bad = mapOf(
            "badjson.json" to "{nope",
            "format2.json" to good("format2").replace("\"format\":1", "\"format\":2"),
            "unknownkey.json" to good("unknownkey", """{"show_number_row": true, "no_such_key": 1}"""),
            "excluded.json" to good("excluded", """{"show_number_row": true, "recent_emojis": "x"}"""),
            "profiles.json" to good("profiles", """{"taen_profiles": "x"}"""),
            "range.json" to good("range", """{"show_number_row": true, "keyboard_height_scale_false_false": 9.0}"""),
            "type.json" to good("type", """{"show_number_row": "true"}"""),
            "privacyrange.json" to good("privacyrange", """{"overlay_mode": "loud"}"""),
            "empty.json" to good("empty", "{}"),
            "novalues.json" to """{"format":1,"id":"novalues","version":1,"title":"a","summary":"b"}""",
            "otherid.json" to good("someone_else"),
            "badtitle.json" to good("badtitle").replace("template_minimal_title", "Not A Resource Name"),
            "version0.json" to good("version0").replace("\"version\":1", "\"version\":0"),
            "huge.json" to good("huge", """{"show_number_row": true}""", ""","pad":"${"x".repeat(TemplateCatalog.MAX_BYTES)}\""""),
        )
        val ok = mapOf("fine.json" to good("fine"))
        val entries = fixtures(bad + ok + mapOf("readme.txt" to "ignored")).load()
        assertEquals((bad.keys + ok.keys).map { it.removeSuffix(".json") }.sorted(), entries.map { if (it is TemplateEntry.Ok) it.template.id else (it as TemplateEntry.Unavailable).id })
        for (e in entries) if (e is TemplateEntry.Unavailable) assertTrue(e.id in bad.keys.map { it.removeSuffix(".json") }, e.id)
        assertIs<TemplateEntry.Ok>(entries.first { (it as? TemplateEntry.Ok)?.template?.id == "fine" }) // positive control: a good one is accepted
        assertEquals(1, entries.count { it is TemplateEntry.Ok })
    }

    @Test fun theCatalogReadsTheRealMergedAssetsOfTheApp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val ids = TemplateCatalog.fromAssets(ctx.assets).load().map { (it as TemplateEntry.Ok).template.id }
        assertEquals(listOf("bulgarian_daily", "large_one_handed", "minimal", "privacy_max"), ids)
    }
}
