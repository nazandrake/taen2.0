// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.profiles.FakePrefs
import helium314.keyboard.latin.profiles.KeyClass
import helium314.keyboard.latin.profiles.ProfileKeys
import helium314.keyboard.latin.settings.Settings
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "What's new" box (shown once, the first time the settings open after an update). The notes are the CHANGELOG entry of the version, built into an
 * asset; the one stored value is the version code of the box that was shown, in the device-protected preferences.
 */
@RunWith(RobolectricTestRunner::class)
class WhatsNewTest {
    private val key = "whats_new_shown"

    @Test fun anUpdateFromAVersionThatStoredNothingShowsTheNotes() { // 2.0.2 -> 2.0.3: 2.0.2 stored no key
        val de = FakePrefs()
        assertEquals("notes", WhatsNew.pending(de, 12, true, "notes"))
        assertEquals(emptyList(), de.commits, "showing stores nothing; the dismissal does")
    }

    @Test fun aFreshInstallStoresTheCurrentVersionSilentlyAndShowsNothing() {
        val de = FakePrefs()
        assertNull(WhatsNew.pending(de, 12, false, "notes"))
        assertEquals(mapOf<String, Any>(key to 12), de.map)
        assertEquals(1, de.commits.size)
        assertNull(WhatsNew.pending(de, 12, false, "notes")) // and again: nothing
        // the next update after that install shows the box
        assertEquals("notes", WhatsNew.pending(de, 13, true, "notes"))
    }

    @Test fun theSameVersionShowsNothing() {
        val de = FakePrefs(mapOf(key to 12))
        assertNull(WhatsNew.pending(de, 12, true, "notes"))
        assertEquals(emptyList(), de.commits)
        // positive control: one version lower shows it
        assertEquals("notes", WhatsNew.pending(FakePrefs(mapOf(key to 11)), 12, true, "notes"))
    }

    @Test fun aNewerVersionShowsTheNotes() {
        assertEquals("notes", WhatsNew.pending(FakePrefs(mapOf(key to 11)), 12, true, "notes"))
        assertEquals("notes", WhatsNew.pending(FakePrefs(mapOf(key to 1)), 12, true, "notes"))
        assertEquals("notes", WhatsNew.pending(FakePrefs(mapOf(key to 11)), 12, false, "notes")) // a stored version decides, not the install times
    }

    @Test fun aDowngradeShowsNothingAndKeepsTheStoredVersion() {
        val de = FakePrefs(mapOf(key to 13))
        assertNull(WhatsNew.pending(de, 12, true, "notes"))
        assertEquals(mapOf<String, Any>(key to 13), de.map)
        assertEquals(emptyList(), de.commits)
    }

    @Test fun theDismissalStoresTheVersionAndTheBoxIsNotShownAgain() {
        val de = FakePrefs(mapOf(key to 11))
        assertEquals("notes", WhatsNew.pending(de, 12, true, "notes"))
        assertTrue(WhatsNew.markShown(de, 12))
        assertEquals(mapOf<String, Any>(key to 12), de.map)
        assertNull(WhatsNew.pending(de, 12, true, "notes"))
        // 2.0.2 -> 2.0.3 without a key: the same
        val none = FakePrefs()
        assertTrue(WhatsNew.markShown(none, 12))
        assertNull(WhatsNew.pending(none, 12, true, "notes"))
    }

    @Test fun aDismissalThatCouldNotBeStoredShowsTheBoxAgainNextTime() {
        val de = FakePrefs(mapOf(key to 11)); de.failCommits = 1
        assertFalse(WhatsNew.markShown(de, 12))
        assertEquals("notes", WhatsNew.pending(de, 12, true, "notes"))
    }

    @Test fun missingOrEmptyNotesShowNothingAndTheVersionIsRemembered() {
        for (notes in listOf(null, "", "  \n ")) {
            val de = FakePrefs(mapOf(key to 11))
            assertNull(WhatsNew.pending(de, 12, true, notes), "$notes")
            assertEquals(mapOf<String, Any>(key to 12), de.map, "no box and no repeat")
        }
    }

    @Test fun theStoredValueIsOneIntegerThatNeverBelongsToAProfile() {
        assertEquals(key, Settings.PREF_WHATS_NEW_SHOWN)
        assertEquals(KeyClass.EXCLUDED, ProfileKeys.classOf(key))
        assertEquals(null, ProfileKeys.validate(key, 12))
    }

    // ---- the install times: an update is told from a fresh install without a permission ----

    private fun info(first: Long, last: Long) = PackageInfo().apply { firstInstallTime = first; lastUpdateTime = last }

    @Test fun anUpdateHasALaterUpdateTimeThanItsInstallTime() {
        assertFalse(WhatsNew.isUpdate(info(1000, 1000)))
        assertTrue(WhatsNew.isUpdate(info(1000, 2000)))
        assertTrue(WhatsNew.isUpdate(info(2000, 1000)))
    }

    // ---- the notes asset ----

    @Test fun theNotesAreReadFromTheAssetThatTheBuildMade() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val notes = assertNotNull(WhatsNew.readNotes(ctx.assets), "the build wrote ${WhatsNew.ASSET} from CHANGELOG.md")
        assertEquals(notes.trim(), notes)
        // it is the entry of the running version: the first words of its first bullet are in it
        val changelog = File("../CHANGELOG.md").readText().lines()
        val entry = changelog.drop(changelog.indexOf("## ${BuildConfig.VERSION_NAME}") + 1).dropWhile { it.isBlank() }
        val first = entry.first().removePrefix("- ").take(30)
        assertTrue(notes.contains(first), "'$first' is in the notes")
        assertFalse(notes.contains("`"))
    }

    @Test fun anAssetThatIsNotThereGivesNull() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(WhatsNew.readNotes(ctx.assets, "no_such_notes.txt"))
    }

    @Test fun theTitleAndButtonAreInEnglishAndBulgarian() {
        val res = File("src/main/res")
        for ((dir, title, button) in listOf(Triple("values", "What's new in Taen %s", "OK"), Triple("values-bg", "Какво е новото в Taen %s", "Добре"))) {
            val text = File(res, "$dir/strings.xml").readText()
            val t = Regex("""<string name="whats_new_title">(.*)</string>""").find(text)!!.groupValues[1].trim('"')
            val b = Regex("""<string name="whats_new_ok">(.*)</string>""").find(text)!!.groupValues[1].trim('"')
            assertEquals(title, t.replace("\\'", "'"), dir)
            assertEquals(button, b, dir)
        }
    }
}
