// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.profiles

import helium314.keyboard.latin.settings.Settings
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ProfileStoreTest {
    private val heightKey = Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX + "_false_false" // portrait, not folded (Settings.readHeightScale)

    private fun store(de: FakePrefs, ce: FakePrefs = FakePrefs(), locked: Boolean = false) = ProfileStore(ce, de, { locked })

    // ---- save and apply -------------------------------------------------------------------------------------------

    @Test fun aSavedProfileHoldsTheIncludedSettingsAndApplyingItBringsThemBack() {
        val de = FakePrefs(mapOf(
            Settings.PREF_SHOW_NUMBER_ROW to true, heightKey to 1.3f,
            Settings.PREF_RECENT_EMOJIS to "x", Settings.PREF_SPACE_BAR_TEXT to "mine", // EXCLUDED keys
        ))
        val s = store(de)
        assertEquals(ProfileOutcome.DONE, s.saveCurrent("Walk"))
        val saved = assertIs<ProfileList.Ok>(s.list()).profiles.single()
        assertEquals("Walk", saved.name)
        assertEquals(true, saved.values[Settings.PREF_SHOW_NUMBER_ROW])
        assertEquals(1.3f, saved.values[heightKey])
        assertTrue(Settings.PREF_RECENT_EMOJIS !in saved.values && Settings.PREF_SPACE_BAR_TEXT !in saved.values)

        de.map[Settings.PREF_SHOW_NUMBER_ROW] = false
        de.map[heightKey] = 0.9f
        de.map[Settings.PREF_RECENT_EMOJIS] = "y"
        assertEquals(ProfileOutcome.DONE, s.apply("Walk"))
        assertEquals(true, de.map[Settings.PREF_SHOW_NUMBER_ROW])
        assertEquals(1.3f, de.map[heightKey])
        assertEquals("y", de.map[Settings.PREF_RECENT_EMOJIS]) // not in the profile: unchanged
        assertEquals("mine", de.map[Settings.PREF_SPACE_BAR_TEXT])
    }

    @Test fun aLooseProfileNeverLoosensAStrictPrivacyKey() {
        val de = FakePrefs(mapOf(Settings.PREF_KEY_USE_PERSONALIZED_DICTS to true))
        val s = store(de)
        assertEquals(ProfileOutcome.DONE, s.saveCurrent("Loose")) // learning on
        de.map[Settings.PREF_KEY_USE_PERSONALIZED_DICTS] = false // the user made it strict later
        s.apply("Loose")
        assertEquals(false, de.map[Settings.PREF_KEY_USE_PERSONALIZED_DICTS])
    }

    // ---- PR1 to PR8 -----------------------------------------------------------------------------------------------

    private fun names(s: ProfileStore) = (s.list() as ProfileList.Ok).profiles.map { it.name }

    @Test fun pr1TheProfileHoldsExactlyTheIncludedAndPrivacyValuesAndNoExcludedKey() {
        val de = FakePrefs(mapOf(
            Settings.PREF_SHOW_NUMBER_ROW to true, Settings.PREF_FONT_SCALE to 1.2f, Settings.PREF_TOOLBAR_MODE to "HIDDEN",
            Settings.PREF_KEY_USE_PERSONALIZED_DICTS to false, Settings.PREF_OVERLAY_MODE to "block",
            Settings.PREF_RECENT_EMOJIS to "x", Settings.PREF_SPACE_BAR_TEXT to "mine", Settings.PREF_VERSION_CODE to 7, "saved_app_subtype_org.a" to "bg",
            "user_colors_light" to "[]", "unknown_future_key" to true,
        ))
        val s = store(de)
        assertEquals(ProfileOutcome.DONE, s.saveCurrent("  Walk  ")) // trimmed
        val p = (s.list() as ProfileList.Ok).profiles.single()
        assertEquals("Walk", p.name)
        assertEquals(false, p.damaged)
        for (k in p.values.keys) assertTrue(ProfileKeys.classOf(k) != KeyClass.EXCLUDED, k)
        for (k in p.defaults) assertEquals(KeyClass.INCLUDED, ProfileKeys.classOf(k), k)
        assertEquals(true, p.values["show_number_row"]); assertEquals(1.2f, p.values["font_scale"]); assertEquals("HIDDEN", p.values["toolbar_mode"])
        assertEquals(false, p.values["use_personalized_dicts"]); assertEquals("block", p.values["overlay_mode"])
        // a privacy key that was not set is recorded with its default; every privacy key is in the profile
        assertEquals(ProfileKeys.privacyKeys.sorted(), p.values.keys.filter { ProfileKeys.classOf(it) == KeyClass.PRIVACY }.sorted())
        assertEquals(true, p.values["secure_keyboard_window"])
        // an included key that was not set is a "default" entry, not a value
        assertTrue("sound_on" in p.defaults && "sound_on" !in p.values)
        assertTrue("show_number_row" !in p.defaults)
        for (k in listOf("recent_emojis", "space_bar_text", "version_code", "saved_app_subtype_org.a", "user_colors_light", "unknown_future_key")) assertTrue(k !in p.values && k !in p.defaults, k)
    }

    @Test fun pr2ApplyingWritesTheSavedValuesInOneCommitAndLeavesEveryOtherKeyAlone() {
        val de = FakePrefs(mapOf(Settings.PREF_SHOW_NUMBER_ROW to true, Settings.PREF_RECENT_EMOJIS to "e", Settings.PREF_SPACE_BAR_TEXT to "mine"))
        val s = store(de)
        s.saveCurrent("A") // font_scale was unset here
        de.map[Settings.PREF_SHOW_NUMBER_ROW] = false
        de.map[Settings.PREF_FONT_SCALE] = 2.0f
        de.map["unknown_future_key"] = 5
        de.commits.clear()
        assertEquals(ProfileOutcome.DONE, s.apply("A"))
        assertEquals(1, de.commits.size) // one commit
        assertEquals(0, de.applyCalls)
        assertEquals(true, de.map[Settings.PREF_SHOW_NUMBER_ROW])
        assertTrue(Settings.PREF_FONT_SCALE !in de.map, "a key that was at its default when saved goes back to its default")
        assertEquals("e", de.map[Settings.PREF_RECENT_EMOJIS]); assertEquals("mine", de.map[Settings.PREF_SPACE_BAR_TEXT]); assertEquals(5, de.map["unknown_future_key"])
        // the plan names exactly what the commit changed
        de.map[Settings.PREF_SHOW_NUMBER_ROW] = false
        assertEquals(listOf(Settings.PREF_SHOW_NUMBER_ROW), s.plan("A")!!.map { it.key })
        s.apply("A")
        assertEquals(emptyList(), s.plan("A")!!.map { it.key }) // nothing left to change after applying
    }

    @Test fun pr4AFailedCommitLeavesEveryKeyAsItWasAndTheOutcomeSaysSo() {
        val de = FakePrefs(mapOf(Settings.PREF_SHOW_NUMBER_ROW to true))
        val s = store(de)
        s.saveCurrent("A")
        de.map[Settings.PREF_SHOW_NUMBER_ROW] = false
        de.map[heightKey] = 0.9f
        val before = LinkedHashMap(de.map)
        de.failCommits = 1
        assertEquals(ProfileOutcome.WRITE_FAILED, s.apply("A"))
        assertEquals(before, de.map)
        assertEquals(ProfileOutcome.DONE, s.apply("A")) // positive control: the same apply works when the store does
        assertEquals(true, de.map[Settings.PREF_SHOW_NUMBER_ROW])
    }

    @Test fun pr5RenameAndDeleteChangeExactlyTheNamedProfile() {
        val de = FakePrefs(); val ce = FakePrefs(); val s = store(de, ce)
        for (n in listOf("One", "Two", "Three")) assertEquals(ProfileOutcome.DONE, s.saveCurrent(n))
        val twoBefore = (s.list() as ProfileList.Ok).profiles.first { it.name == "Two" }
        assertEquals(ProfileOutcome.DONE, s.rename("one", "Uno"))
        assertEquals(listOf("Uno", "Two", "Three"), names(s))
        assertEquals(ProfileOutcome.DONE, s.delete("TWO"))
        assertEquals(listOf("Uno", "Three"), names(s))
        assertEquals(ProfileOutcome.NOT_FOUND, s.delete("Two"))
        assertEquals(ProfileOutcome.NOT_FOUND, s.rename("Two", "Zwei"))
        assertTrue(twoBefore.values.isNotEmpty()) // control: Two had content
        assertEquals(ProfileOutcome.DONE, s.deleteAll())
        assertEquals(emptyList(), names(s))
        assertTrue(de.commits.isEmpty(), "managing profiles never writes the settings")
    }

    @Test fun pr5TheEleventhProfileAndBadNamesAreRefusedAndNothingIsWritten() {
        val ce = FakePrefs(); val s = store(FakePrefs(), ce)
        for (i in 1..10) assertEquals(ProfileOutcome.DONE, s.saveCurrent("P$i"), "profile $i")
        val writes = ce.commits.size
        val stored = ce.map[ProfileStore.KEY]
        assertEquals(ProfileOutcome.FULL, s.saveCurrent("P11"))
        assertEquals(writes, ce.commits.size); assertEquals(stored, ce.map[ProfileStore.KEY])
        assertEquals(ProfileOutcome.DONE, s.delete("P10")) // 10 -> 9: one more fits again
        assertEquals(ProfileOutcome.DONE, s.saveCurrent("x".repeat(40))) // 40 characters
        s.delete("x".repeat(40)); s.deleteAll()
        val s2 = store(FakePrefs(), ce)
        val w = ce.commits.size
        for (bad in listOf("", "   ", "x".repeat(41), "line\nbreak", "tab\there")) assertEquals(ProfileOutcome.INVALID_NAME, s2.saveCurrent(bad), "'$bad'")
        assertEquals(ProfileOutcome.DONE, s2.saveCurrent("x")) // 1 character
        assertEquals(ProfileOutcome.DUPLICATE_NAME, s2.saveCurrent("X"))
        assertEquals(ProfileOutcome.DONE, s2.saveCurrent("Y"))
        assertEquals(ProfileOutcome.DUPLICATE_NAME, s2.rename("Y", "x"))
        assertEquals(ProfileOutcome.INVALID_NAME, s2.rename("Y", " "))
        assertEquals(ProfileOutcome.DONE, s2.rename("Y", "y")) // only a change of case: its own name is no duplicate
        assertEquals(listOf("x", "y"), names(s2))
        assertEquals(w + 3, ce.commits.size) // x, Y, the rename: the refused ones wrote nothing
    }

    @Test fun anOverlargeProfileIsRefusedWithTooLargeAndNothingIsWritten() {
        val long = (1..185).joinToString("|") { "VOICE:true" } // 2034 characters, a valid toolbar list
        val de = FakePrefs(mapOf(Settings.PREF_TOOLBAR_KEYS to long, Settings.PREF_PINNED_TOOLBAR_KEYS to long, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS to long,
            Settings.PREF_ADDITIONAL_SUBTYPES to "a".repeat(4000), Settings.PREF_CUSTOM_ICON_NAMES to "b".repeat(2000), Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES to "c".repeat(2000)))
        val ce = FakePrefs(); val s = store(de, ce)
        var saved = 0
        var outcome = ProfileOutcome.DONE
        while (outcome == ProfileOutcome.DONE && saved < 10) { outcome = s.saveCurrent("P$saved"); if (outcome == ProfileOutcome.DONE) saved++ }
        assertEquals(ProfileOutcome.TOO_LARGE, outcome, "10 large profiles do not fit in 64 KiB")
        assertTrue(saved in 1..9, "positive control: some fit ($saved)")
        assertTrue((ce.map[ProfileStore.KEY] as String).toByteArray().size <= ProfileStore.MAX_BYTES)
        assertEquals(saved, names(s).size) // the refused one is not there
    }

    @Test fun pr6ADamagedValueIsReportedAndRefusedAndOnlyDeleteAllRemovesIt() {
        val good = FakePrefs(); store(FakePrefs(), good).saveCurrent("Walk")
        val goodValue = good.map[ProfileStore.KEY] as String
        val damaged = listOf(
            "not json at all {", "{\"v\":2,\"p\":[]}", "{\"p\":[]}", "{\"v\":1}", "{\"v\":1,\"p\":[{\"n\":\"\"}]}", "{\"v\":1,\"p\":[{\"n\":\"A\"},{\"n\":\"a\"}]}",
            "{\"v\":1,\"p\":[" + (1..11).joinToString(",") { "{\"n\":\"P$it\"}" } + "]}", goodValue + " ".repeat(ProfileStore.MAX_BYTES),
        )
        for (raw in damaged) {
            val ce = FakePrefs(mapOf(ProfileStore.KEY to raw)); val de = FakePrefs(mapOf(Settings.PREF_SHOW_NUMBER_ROW to true)); val s = store(de, ce)
            assertIs<ProfileList.Damaged>(s.list(), raw.take(30))
            assertEquals(ProfileOutcome.DAMAGED, s.saveCurrent("New")); assertEquals(ProfileOutcome.DAMAGED, s.apply("Walk"))
            assertEquals(ProfileOutcome.DAMAGED, s.rename("a", "b")); assertEquals(ProfileOutcome.DAMAGED, s.delete("a")); assertEquals(null, s.plan("Walk"))
            assertEquals(raw, ce.map[ProfileStore.KEY], "the value is left as it is") ; assertTrue(ce.commits.isEmpty() && de.commits.isEmpty())
            assertEquals(ProfileOutcome.DONE, s.deleteAll())
            assertEquals(emptyList(), names(s))
        }
        // positive control: the undamaged value lists and applies
        val s = store(FakePrefs(mapOf(Settings.PREF_SHOW_NUMBER_ROW to false)), good)
        assertEquals(listOf("Walk"), names(s)); assertEquals(ProfileOutcome.DONE, s.apply("Walk"))
    }

    private fun docWith(vararg profiles: String) = "{\"v\":1,\"p\":[" + profiles.joinToString(",") + "]}"

    @Test fun pr7OnlyTheProfileWithABadKeyOrValueIsDamagedTheOthersStillApply() {
        val ok = """{"n":"Ok","s":{"show_number_row":true},"d":["sound_on"]}"""
        val bad = listOf(
            """{"n":"Unknown","s":{"no_such_key":true}}""", """{"n":"Excluded","s":{"recent_emojis":"x"}}""", """{"n":"Range","s":{"keyboard_height_scale_false_false":9.0}}""",
            """{"n":"Type","s":{"show_number_row":"yes"}}""", """{"n":"Both","s":{"sound_on":true},"d":["sound_on"]}""", """{"n":"ResetPrivacy","s":{},"d":["use_personalized_dicts"]}""",
            """{"n":"NoValues"}""", """{"n":"Privacy","s":{"overlay_mode":"loud"}}""",
        )
        for (b in bad) {
            val ce = FakePrefs(mapOf(ProfileStore.KEY to docWith(ok, b))); val de = FakePrefs(mapOf(Settings.PREF_SOUND_ON to true))
            val s = store(de, ce)
            val list = (s.list() as ProfileList.Ok).profiles
            assertEquals(listOf(false, true), list.map { it.damaged }, b)
            val name = list[1].name
            assertEquals(ProfileOutcome.PROFILE_DAMAGED, s.apply(name), b); assertTrue(de.commits.isEmpty(), b); assertEquals(null, s.plan(name))
            assertEquals(ProfileOutcome.DONE, s.apply("Ok"), b)
            assertEquals(true, de.map["show_number_row"]); assertTrue("sound_on" !in de.map)
            // renaming the good one keeps the damaged raw entry as it was
            val raw = ce.map[ProfileStore.KEY] as String
            assertEquals(ProfileOutcome.DONE, s.rename("Ok", "Fine"))
            assertEquals(listOf("Fine", name), names(s)); assertTrue((s.list() as ProfileList.Ok).profiles[1].damaged)
            assertTrue(raw != ce.map[ProfileStore.KEY])
            assertEquals(ProfileOutcome.DONE, s.delete(name)); assertEquals(listOf("Fine"), names(s))
        }
    }

    @Test fun pr8LockedMeansUnavailableAndNothingIsWrittenAnywhere() {
        val de = FakePrefs(mapOf(Settings.PREF_SHOW_NUMBER_ROW to true)); val ce = FakePrefs(); var locked = false
        val s = ProfileStore(ce, de, { locked })
        assertEquals(ProfileOutcome.DONE, s.saveCurrent("Walk"))
        locked = true
        de.commits.clear(); val ceWrites = ce.commits.size; val stored = ce.map[ProfileStore.KEY]
        assertIs<ProfileList.Locked>(s.list())
        for (o in listOf(s.saveCurrent("New"), s.apply("Walk"), s.rename("Walk", "W"), s.delete("Walk"), s.deleteAll())) assertEquals(ProfileOutcome.LOCKED, o)
        assertEquals(null, s.plan("Walk"))
        assertTrue(de.commits.isEmpty()); assertEquals(ceWrites, ce.commits.size); assertEquals(stored, ce.map[ProfileStore.KEY])
        locked = false // positive control: unlocked again, everything works
        assertEquals(ProfileOutcome.DONE, s.apply("Walk"))
    }

    @Test fun theFloatingSwitchOfThisScreenIsRecordedWhenTheCallerNamesIt() {
        val key = ProfileKeys.floatingKey(1080)
        val de = FakePrefs(mapOf(key to true))
        val s = ProfileStore(FakePrefs(), de, { false }, { listOf(key) })
        s.saveCurrent("F")
        de.map.remove(key)
        s.apply("F")
        assertEquals(true, de.map[key])
        de.map[key] = false; s.saveCurrent("G"); de.map[key] = true; s.apply("G") // saved as a value false
        assertEquals(false, de.map[key])
    }
}
