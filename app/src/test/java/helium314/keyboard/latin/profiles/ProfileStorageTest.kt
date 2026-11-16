// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.profiles

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.privacy.ExportService
import helium314.keyboard.latin.privacy.FileWiper
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.privacy.WipeScope
import helium314.keyboard.latin.privacy.filesContaining
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The profiles in the real encrypted stores (P7, P15, P16, I4): ciphertext at rest, unreadable and read-only while locked, in the export, wiped by a
 * wipe with the settings scope and kept by one without, and the templates survive a wipe because they are in the APK.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileStorageTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val canary = "TaenCanaryProfile7q"
    private fun taen(name: String) = File(ctx.noBackupFilesDir, "taen/$name")
    private fun store() = ProfileStore(ctx.protectedPrefs(), ctx.prefs(), { Privacy.isLocked })
    private fun listed() = (store().list() as ProfileList.Ok).profiles.map { it.name }

    @Before fun before() { Privacy.unlock(ctx); Privacy.forgetCachedState() }
    @After fun after() { Privacy.unlock(ctx); Privacy.forgetCachedState() }

    @Test fun aSavedProfileIsCiphertextAtRestAndLoadsAgainAfterARestart() {
        ctx.prefs().edit().putBoolean("show_number_row", true).commit()
        assertEquals(ProfileOutcome.DONE, store().saveCurrent(canary))
        assertEquals(emptyList(), filesContaining(ctx.dataDir, canary)) // not in any file of the app in plain text, not even as UTF-16
        assertEquals(emptyList(), filesContaining(ctx.dataDir, ProfileStore.KEY))
        Privacy.forgetCachedState() // a new process
        assertEquals(listOf(canary), listed())
    }

    @Test fun whileLockedTheListIsUnavailableAndNothingIsWrittenThenItIsBack() {
        ctx.prefs().edit().putBoolean("show_number_row", true).commit()
        assertEquals(ProfileOutcome.DONE, store().saveCurrent("Walk"))
        val ceFile = taen("prefs_ce.enc"); val deFile = File(Privacy.wipeRoots(ctx).second, "no_backup/taen/prefs_de.enc")
        assertTrue(ceFile.isFile && deFile.isFile, "positive control: both stores exist")
        val ceBefore = ceFile.readBytes(); val deBefore = deFile.readBytes()
        Privacy.lock(ctx)
        val s = store()
        assertIs<ProfileList.Locked>(s.list())
        for (o in listOf(s.saveCurrent("Late"), s.apply("Walk"), s.rename("Walk", "W"), s.delete("Walk"), s.deleteAll())) assertEquals(ProfileOutcome.LOCKED, o)
        assertContentEquals(ceBefore, ceFile.readBytes()); assertContentEquals(deBefore, deFile.readBytes())
        Privacy.unlock(ctx)
        assertEquals(listOf("Walk"), listed()) // positive control
        assertEquals(ProfileOutcome.DONE, store().apply("Walk"))
    }

    @Test fun pr10TheProfilesAreInTheEncryptedExportAndComeBackByteIdentical() {
        ctx.prefs().edit().putBoolean("show_number_row", true).commit()
        store().saveCurrent("One"); store().saveCurrent(canary)
        val raw = ctx.protectedPrefs().getString(ProfileStore.KEY, null)!!
        val parts = ExportService.collect(ctx) { false }
        assertEquals(raw, parts.prefsCe!![ProfileStore.KEY])
        ctx.protectedPrefs().edit().clear().commit() // an empty install
        assertEquals(emptyList(), listed())
        ExportService.apply(ctx, parts) { false }
        assertEquals(raw, ctx.protectedPrefs().getString(ProfileStore.KEY, null))
        assertEquals(listOf("One", canary), listed())
    }

    private val noSettings = WipeScope(learned = true, clipboard = true, settings = false, config = true, imported = true)

    @Test fun pr11AWipeWithTheSettingsScopeRemovesTheProfilesAndOneWithoutKeepsThem() {
        ctx.prefs().edit().putBoolean("show_number_row", true).commit()
        store().saveCurrent("Walk")
        val (ce, de) = Privacy.wipeRoots(ctx)
        Privacy.forgetCachedState()
        FileWiper(ce, de).delete(noSettings, emptySet(), ceOnly = false)
        Privacy.forgetCachedState()
        assertEquals(listOf("Walk"), listed()) // the scope has no settings: the profiles stay (stated in PRIVACY.md)
        assertEquals(true, ctx.prefs().getBoolean("show_number_row", false))
        Privacy.forgetCachedState()
        assertTrue(FileWiper(ce, de).delete(WipeScope.ALL, emptySet(), ceOnly = false)) // the default scope
        Privacy.forgetCachedState()
        assertEquals(emptyList(), listed())
        assertEquals(emptySet(), ctx.prefs().all.keys)
    }

    @Test fun tp4AfterAWipeTheSettingsAreBackAtTheirDefaultsNotAtTheTemplateAndAllFourTemplatesAreStillThere() {
        val catalog = TemplateCatalog.fromAssets(ctx.assets)
        val minimal = (catalog.load().first { (it as TemplateEntry.Ok).template.id == "privacy_max" } as TemplateEntry.Ok).template
        assertEquals(ProfileOutcome.DONE, TemplateApply.apply(minimal, ctx.prefs()))
        assertEquals(true, ctx.prefs().getBoolean("always_incognito_mode", false)) // positive control: it was applied
        val (ce, de) = Privacy.wipeRoots(ctx)
        Privacy.forgetCachedState()
        assertTrue(FileWiper(ce, de).delete(WipeScope.ALL, emptySet(), ceOnly = false))
        Privacy.forgetCachedState()
        assertEquals(false, ctx.prefs().getBoolean("always_incognito_mode", false))
        assertTrue("always_incognito_mode" !in ctx.prefs().all)
        assertEquals(listOf("bulgarian_daily", "large_one_handed", "minimal", "privacy_max"), catalog.load().map { (it as TemplateEntry.Ok).template.id })
    }
}
