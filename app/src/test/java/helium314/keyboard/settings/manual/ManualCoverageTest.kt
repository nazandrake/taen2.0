// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.manual

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.privacy.SelfDestruct
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.DebugSettings
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.screens.createAboutSettings
import helium314.keyboard.settings.screens.createAdvancedSettings
import helium314.keyboard.settings.screens.createAppearanceSettings
import helium314.keyboard.settings.screens.createCorrectionSettings
import helium314.keyboard.settings.screens.createGestureTypingSettings
import helium314.keyboard.settings.screens.createLayoutSettings
import helium314.keyboard.settings.screens.createPreferencesSettings
import helium314.keyboard.settings.screens.createPrivacySettings
import helium314.keyboard.settings.screens.createToolbarSettings
import java.io.File
import java.util.Locale
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every setting has one owner in the manual: every preference key that the settings screens show has an entry (a heading with its hidden `{#key}`),
 * and every entry names a key that exists. Keys that the screens mention and the manual does not describe are on [excluded], each with its reason.
 */
@RunWith(RobolectricTestRunner::class)
class ManualCoverageTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** The keys of the screens that the manual does not describe, and why. Each must still be mentioned by a screen, or the list is stale. */
    private val excluded: Map<String, String> = mapOf(
        DebugSettings.PREF_DEBUG_MODE to "hidden debug screen (opened by tapping Version five times), not for daily use",
        DebugSettings.PREF_SHOW_DEBUG_SETTINGS to "hidden debug screen switch, not for daily use",
        DebugSettings.PREF_SHOW_SUGGESTION_INFOS to "hidden debug screen, not for daily use",
        DebugSettings.PREF_FORCE_NON_DISTINCT_MULTITOUCH to "hidden debug screen, not for daily use",
        DebugSettings.PREF_SLIDING_KEY_INPUT_PREVIEW to "hidden debug screen, not for daily use",
        DebugSettings.PREF_KEY_DUMP_DICT_PREFIX to "hidden debug screen, not for daily use",
        SettingsWithoutKey.DEBUG_SETTINGS to "the row that opens the hidden debug screen",
        SettingsWithoutKey.APP to "the name and slogan row at the top of About, not a setting",
        Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE to "a switch inside the dialog of Enable split keyboard, described under split_keyboard",
        Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED to "a switch inside the dialog of Enable split keyboard, described under split_keyboard",
        Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE to "a switch inside the dialog of Enable split keyboard, described under split_keyboard",
        Settings.PREF_SHOW_SETUP_WIZARD_ICON to "shown only on Android 9 and older (the screen checks SDK_INT < Q); Taen needs Android 11",
        Settings.PREF_GESTURE_DYNAMIC_PREVIEW_FOLLOW_SYSTEM to "written together with the dynamic floating preview, described under gesture_floating_preview_dynamic",
    )

    /**
     * Entries whose "Default: on|off" is not compared with Defaults, each with its reason. Every other entry whose key has a Boolean default in
     * [Defaults] must name the same value.
     */
    private val defaultNotCompared: Map<String, String> = mapOf(
        Settings.PREF_POPUP_ON to "the default depends on the screen size (Defaults.initDynamicDefaults): off on phones, on on tablets",
        Settings.PREF_AUTO_HIDE_TOOLBAR to "the manual describes the 2.0.3 default (on), which Defaults reaches in the same release; until then either value passes (theToolbarAutoHideDefaultIsDescribedAsOn pins the text)",
    )

    private val classes = mapOf(
        "Settings" to Settings::class.java,
        "DebugSettings" to DebugSettings::class.java,
        "SelfDestruct" to SelfDestruct::class.java,
        "SettingsWithoutKey" to SettingsWithoutKey::class.java,
    )

    private val reference = Regex("\\b(Settings|DebugSettings|SelfDestruct|SettingsWithoutKey)\\.(PREFS?_[A-Z0-9_]+|[A-Z][A-Z0-9_]+)\\b")

    /** The preference keys (string values) that the screens of the settings mention: `Settings.PREF_X`, `SettingsWithoutKey.X` and the like, plus the layouts. */
    private fun shownKeys(sources: List<String> = screenSources()): Set<String> {
        val keys = HashSet<String>()
        for (source in sources) {
            for (m in reference.findAll(source)) {
                val owner = m.groupValues[1]
                val name = m.groupValues[2]
                if (name == "PREF_LAYOUT_PREFIX") continue // one key for each layout, below
                if (owner != "SettingsWithoutKey" && !name.startsWith("PREF")) continue
                keys.add(classes.getValue(owner).getField(name).get(null) as String)
            }
        }
        for (type in LayoutType.entries) if (type != LayoutType.MAIN) keys.add(Settings.PREF_LAYOUT_PREFIX + type.name)
        return keys
    }

    private fun screenSources(): List<String> =
        File("src/main/java/helium314/keyboard/settings/screens").listFiles { f -> f.name.endsWith(".kt") }.orEmpty().map { it.readText() }

    private fun manualKeys(): List<String> = ManualFiles.ids("en").flatMap { ManualFiles.keys(ManualFiles.blocks("en", it)) }

    // ---- pass 1 ----

    @Test fun everyShownKeyHasAnEntryAndEveryEntryIsAShownKey() {
        val shown = shownKeys()
        assertTrue(shown.size > 100, "positive control: ${shown.size} keys are shown by the screens")
        val manual = manualKeys().toSet()
        assertEquals(emptySet(), shown - excluded.keys - manual, "keys that the screens show and the manual does not describe")
        assertEquals(emptySet(), manual - shown, "entries of the manual that name no key of the screens")
    }

    // ---- pass 2 ----

    @Test fun theCoverageCheckSeesAMissingKeyAnInventedKeyAndAStaleExclusion() { // negative controls on the same functions
        val shown = shownKeys()
        val manual = manualKeys().toSet()
        // positive control: nothing is missing now
        assertEquals(emptySet(), shown - excluded.keys - manual)
        // a key that the manual lost
        assertEquals(setOf("vibrate_on"), shown - excluded.keys - (manual - "vibrate_on"))
        // an entry for a key that no screen shows
        assertEquals(setOf("no_such_key"), (manual + "no_such_key") - shown)
        // a source without the keys of a screen
        val fewer = shownKeys(screenSources().filterNot { "PREF_VIBRATE_ON" in it && "PREF_VIBRATE_IN_DND_MODE" in it })
        assertTrue("vibrate_on" !in fewer && shown.size > fewer.size, "the scan depends on the screen sources")
    }

    @Test fun everyExclusionIsStillMentionedByAScreenAndHasAReason() {
        val shown = shownKeys()
        for ((key, reason) in excluded) {
            assertTrue(reason.length > 20, "$key has a reason")
            assertTrue(key in shown, "$key is on the exclusion list but no screen mentions it any more")
        }
        assertTrue(excluded.keys.none { it in manualKeys() }, "an excluded key has an entry too")
    }

    @Test fun everyKeyHasExactlyOneEntry() {
        val keys = manualKeys()
        assertEquals(emptyList(), keys.groupBy { it }.filter { it.value.size > 1 }.keys.toList(), "keys with more than one entry")
        assertTrue(keys.size > 100, "positive control: ${keys.size} entries")
    }

    @Test fun theIncognitoListIsNamedByTextInTheTitleTableAndThatIsItsRealKey() {
        assertEquals("incognito_apps", Settings.PREF_INCOGNITO_APPS) // ManualTitles names it by text (the P9 rule), this keeps the two equal
        assertNotNull(ManualTitles[Settings.PREF_INCOGNITO_APPS])
    }

    @Test fun theTitleTableHasTheKeysOfTheManualAndNoOthers() {
        val manual = manualKeys().toSet()
        assertEquals(emptySet(), manual - ManualTitles.keys, "entries without a title")
        assertEquals(emptySet(), ManualTitles.keys - manual, "titles without an entry")
    }

    @Test fun anEntryTakesItsTitleFromTheAppAndHasNoTitleOfItsOwn() {
        var seen = 0
        for (id in ManualFiles.ids("en")) for (h in ManualFiles.blocks("en", id).filterIsInstance<Block.Heading>()) {
            if (h.key == null) continue
            seen++
            assertEquals(emptyList(), h.spans, "$id: the entry ${h.key} has words of its own in the heading; the title comes from the app")
        }
        assertTrue(seen > 100, "positive control: $seen entries")
    }

    @Test fun everyEntryHasABodyAndEveryDefaultOfASettingIsStated() {
        val withDefault = HashSet<String>()
        for (id in ManualFiles.ids("en")) {
            val blocks = ManualFiles.blocks("en", id)
            for ((i, b) in blocks.withIndex()) {
                if (b !is Block.Heading || b.key == null) continue
                val next = blocks.getOrNull(i + 1)
                assertTrue(next is Block.Paragraph, "$id: the entry ${b.key} has no text")
                val text = (next as Block.Paragraph).spans.joinToString("") { s -> if (s is Span.Text) s.text else "" }
                assertTrue(text.length > 30, "$id: the entry ${b.key} is too short: $text")
                if ("Default:" in text) withDefault.add(b.key)
            }
        }
        // a setting that has a default in Defaults says what it is
        val missing = manualKeys().filter { key -> defaultOf(key) != null && key !in withDefault && key !in defaultNotCompared.keys }
        assertEquals(emptyList(), missing, "entries of settings with a default that do not state it")
    }

    /** The Boolean default of a setting (the constant of the same name in Defaults), or null when it has none or is not a Boolean. */
    private fun defaultOf(key: String): Boolean? {
        val name = Settings::class.java.fields.firstOrNull { it.name.startsWith("PREF") && it.type == String::class.java && it.get(null) == key }?.name ?: return null
        val field = Defaults::class.java.fields.firstOrNull { it.name == name } ?: return null
        return field.get(Defaults) as? Boolean
    }

    @Test fun theDefaultsThatTheManualStatesAreTheOnesOfTheApp() {
        var compared = 0
        val wrong = ArrayList<String>()
        for (id in ManualFiles.ids("en")) {
            val blocks = ManualFiles.blocks("en", id)
            for ((i, b) in blocks.withIndex()) {
                if (b !is Block.Heading || b.key == null) continue
                val expected = defaultOf(b.key) ?: continue
                if (b.key in defaultNotCompared) continue
                val text = (blocks[i + 1] as Block.Paragraph).spans.joinToString("") { s -> if (s is Span.Text) s.text else "" }
                val stated = Regex("Default: (on|off)\\b").find(text)?.groupValues?.get(1) ?: continue
                compared++
                if ((stated == "on") != expected) wrong.add("${b.key}: the manual says $stated, Defaults says ${if (expected) "on" else "off"}")
            }
        }
        assertTrue(compared > 70, "positive control: $compared defaults were compared")
        assertEquals(emptyList(), wrong)
    }

    @Test fun theToolbarAutoHideDefaultIsDescribedAsOn() {
        // the one default that the manual states ahead of the code (see defaultNotCompared)
        val id = ManualFiles.ids("en").first { Settings.PREF_AUTO_HIDE_TOOLBAR in ManualFiles.keys(ManualFiles.blocks("en", it)) }
        val blocks = ManualFiles.blocks("en", id)
        val at = blocks.indexOfFirst { it is Block.Heading && it.key == Settings.PREF_AUTO_HIDE_TOOLBAR }
        val text = (blocks[at + 1] as Block.Paragraph).spans.joinToString("") { if (it is Span.Text) it.text else "" }
        assertTrue("Default: on" in text, text)
    }

    private fun localized(locale: Locale): Context = ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply { setLocale(locale) })

    /** The entries that show the same title (and the same line under it) on one page, as "key and key"; the same words on two pages are fine ("Number row"). */
    private fun titleClashes(c: Context, pageOf: Map<String, String>): List<String> {
        val seen = HashMap<Triple<String, String, String?>, String>()
        val out = ArrayList<String>()
        for (key in ManualTitles.keys) {
            val t = ManualTitles[key]!!
            val title = c.getString(t.title)
            assertTrue(title.isNotBlank(), "$key has a title")
            val sub = t.subtitle?.let { c.getString(it) }
            if (sub != null) assertTrue(sub.isNotBlank(), "$key subtitle")
            val clash = seen.put(Triple(pageOf.getValue(key), title, sub), key)
            if (clash != null) out.add("$clash and $key show '$title' ${sub ?: ""} on the page ${pageOf[key]}")
        }
        return out
    }

    @Test fun everyTitleExistsInEnglishAndBulgarianAndTwoEntriesOfOnePageNeverShareTheSameTitle() {
        // the same words can be the title of two settings on different screens ("Number row"); on one page the reader must be able to tell the entries apart
        val pageOf = ManualFiles.ids("en").flatMap { page -> ManualFiles.keys(ManualFiles.blocks("en", page)).map { it to page } }.toMap()
        assertEquals(ManualTitles.keys, pageOf.keys, "every title belongs to one page")
        for (locale in listOf(Locale.ENGLISH, Locale("bg"))) assertEquals(emptyList(), titleClashes(localized(locale), pageOf), "$locale")
    }

    @Test fun theClashCheckFindsTwoEntriesThatShareATitleOnOnePage() { // negative controls on the same function, with the positive one
        val pageOf = ManualFiles.ids("en").flatMap { page -> ManualFiles.keys(ManualFiles.blocks("en", page)).map { it to page } }.toMap()
        val c = localized(Locale.ENGLISH)
        val numberRow = Settings.PREF_SHOW_NUMBER_ROW
        val layoutNumberRow = Settings.PREF_LAYOUT_PREFIX + "NUMBER_ROW"
        assertTrue(pageOf.getValue(numberRow) != pageOf.getValue(layoutNumberRow), "the two live on different pages")
        assertEquals(emptyList(), titleClashes(c, pageOf), "positive control: none now")
        val together = titleClashes(c, pageOf + (layoutNumberRow to pageOf.getValue(numberRow)))
        assertEquals(1, together.size, together.toString())
        assertTrue("Number row" in together.single())
    }

    @Test fun theUniquenessCheckSeesTwoEntriesWithTheSameTitleOnOnePage() { // the shared title of the four bottom-row entries is told apart by the line under it
        val c = localized(Locale.ENGLISH)
        val entries = listOf(Settings.PREF_ABC_AFTER_SYMBOL_SPACE, Settings.PREF_ABC_AFTER_NUMPAD_SPACE, Settings.PREF_ABC_AFTER_EMOJI, Settings.PREF_ABC_AFTER_CLIP)
        assertEquals(1, entries.map { c.getString(ManualTitles[it]!!.title) }.toSet().size, "they share the title")
        assertEquals(4, entries.map { c.getString(ManualTitles[it]!!.subtitle!!) }.toSet().size, "and differ in the line under it")
        assertEquals(c.getString(ManualTitles[Settings.PREF_SHOW_NUMBER_ROW]!!.title), c.getString(ManualTitles[Settings.PREF_LAYOUT_PREFIX + "NUMBER_ROW"]!!.title), "the same words on two screens")
    }

    @Test fun theTitleOfAnEntryIsTheTitleThatTheScreenGivesTheSameKey() {
        val screens: List<Setting> = createAboutSettings(ctx) + createAdvancedSettings(ctx) + createAppearanceSettings(ctx) + createCorrectionSettings(ctx) +
            createGestureTypingSettings(ctx) + createLayoutSettings(ctx) + createPreferencesSettings(ctx) + createToolbarSettings(ctx) + createPrivacySettings(ctx)
        var compared = 0
        for (locale in listOf(Locale.ENGLISH, Locale("bg"))) {
            val c = localized(locale)
            val ofLocale: List<Setting> = createAboutSettings(c) + createAdvancedSettings(c) + createAppearanceSettings(c) + createCorrectionSettings(c) +
                createGestureTypingSettings(c) + createLayoutSettings(c) + createPreferencesSettings(c) + createToolbarSettings(c) + createPrivacySettings(c)
            for (setting in ofLocale) {
                val t = ManualTitles[setting.key] ?: continue
                compared++
                assertEquals(setting.title, c.getString(t.title), "$locale ${setting.key}")
                if (t.subtitle != null) assertEquals(setting.description, c.getString(t.subtitle), "$locale ${setting.key} subtitle")
            }
        }
        assertTrue(compared > 200, "positive control: $compared titles were compared (${screens.size} settings)")
        // every entry that a screen registers is compared; the ones that only Taen screens draw are not registered and are listed here
        val registered = screens.map { it.key }.toSet()
        val drawnByTheScreenItself = ManualTitles.keys - registered
        assertEquals(
            setOf(
                Settings.PREF_INCOGNITO_APPS, Settings.PREF_CLIPBOARD_IN_SECURE_FIELDS, Settings.PREF_SECURE_KEYBOARD_WINDOW, Settings.PREF_A11Y_WARNING,
                Settings.PREF_OVERLAY_MODE, Settings.PREF_VOICE_TYPING, Settings.PREF_MEDIA_STARTER_PACK, Settings.PREF_NEXTWORD_MODEL, SelfDestruct.PREF_PANIC_SHORTCUT,
            ),
            drawnByTheScreenItself,
        )
        assertNotNull(ManualTitles[Settings.PREF_LAYOUT_PREFIX + LayoutType.SYMBOLS.name])
    }
}
