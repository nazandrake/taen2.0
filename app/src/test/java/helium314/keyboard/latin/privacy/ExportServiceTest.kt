// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.Context
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Export and import through the production entry points: what comes back is what was exported, and a failed import changes nothing. */
@RunWith(RobolectricTestRunner::class)
class ExportServiceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val canary = "TAENcanary-Qx93"
    private val passphrase = "correct horse battery".toCharArray()
    private val fast = ExportCodec.Kdf(64, 3, 1)
    private val accept: (String) -> Boolean = { it.startsWith("layouts/") || it.startsWith("blacklists/") || it == "custom_font" }

    @Before fun before() = Privacy.forgetCachedState()
    @After fun after() = Privacy.forgetCachedState()

    private fun fileDirs() = listOf(ctx.filesDir, DeviceProtectedUtils.getFilesDir(ctx))
    private fun learned(tag: String) = Privacy.learnedStorage(ctx, tag, null)

    private fun fill(variant: String) {
        ctx.prefs().edit().clear().putString("de", "de-$variant").putInt("n", variant.length).commit()
        ctx.protectedPrefs().edit().clear().putString("ce", "ce-$canary-$variant").commit()
        assertTrue(assertNotNull(Privacy.personalDictionary(ctx)).replaceAll(listOf(PersonalWord("word-$canary-$variant", "sc", 200, "en_US"))))
        assertTrue(learned("en-US").save(("learned-$variant-$canary").toByteArray() + ByteArray(40)))
        File(ctx.filesDir, "layouts").mkdirs()
        File(ctx.filesDir, "layouts/custom.$variant.json").writeText("layout $variant")
        File(ctx.filesDir, "blacklists").mkdirs()
        File(ctx.filesDir, "blacklists/en.txt").writeText("blocked-$variant")
        File(DeviceProtectedUtils.getFilesDir(ctx), "custom_font").writeText("font $variant")
    }

    private fun state() = mapOf(
        "de" to ctx.prefs().all.toMap(),
        "ce" to ctx.protectedPrefs().all.toMap(),
        "personal" to assertNotNull(Privacy.personalDictionary(ctx)).all(),
        "learned" to Privacy.readAllLearned(ctx).mapValues { it.value.toList() },
        "files" to fileDirs().map { d -> FileSet.read(d, accept).mapValues { it.value.toList() } },
    )

    // ---- core behaviour -------------------------------------------------------------------------------

    @Test fun exportedDataComesBackAfterItWasChanged() {
        fill("one")
        val expected = state()
        val file = ExportCodec.encrypt(passphrase, ExportBundle.pack(ExportService.collect(ctx, accept)), fast)

        fill("two-changed")
        File(ctx.filesDir, "layouts/custom.extra.json").writeText("not in the export")
        assertTrue(expected != state())

        ExportService.apply(ctx, ExportBundle.unpack(ExportCodec.decrypt(passphrase, file)), accept)
        assertEquals(expected, state())
        assertFalse(File(ctx.filesDir, "layouts/custom.extra.json").exists()) // restore replaces, like before
    }

    // ---- failure, secrecy, locked, files --------------------------------------------------------------

    @Test fun aFailedImportPutsTheEarlierStepsBack() {
        fill("one")
        val parts = ExportService.collect(ctx, accept)
        val broken = ExportParts(
            parts.prefsDe!! + ("de" to "changed"), parts.prefsCe, listOf(PersonalWord("", null, 1, null)), // invalid: blank word
            parts.learned, parts.files, parts.protectedFiles,
        )
        val before = state()
        assertFailsWith<IllegalStateException> { ExportService.apply(ctx, broken, accept) }
        assertEquals(before, state()) // the preferences step ran first and was undone
    }

    // ---- a running dictionary must not flush its old words over the import --------------------

    @Test fun learnedWordsCannotBeSavedOrReloadedWhileAnImportRuns() {
        fill("exported")
        val imported = ExportService.collect(ctx, accept)
        fill("old")
        val oldBytes = "late-flush-of-the-old-words".toByteArray() + ByteArray(30)
        val storage = learned("en-US")
        assertTrue(storage.isAvailable()) // control: outside an import the same calls work
        var discards = 0
        ExportService.applyImport(ctx, imported, accept) {
            discards++ // before the import and again after it, both while the guard is closed
            assertFalse(storage.isAvailable())
            assertFalse(storage.save(oldBytes)) // a dictionary that flushes now writes nothing
            assertFalse(storage.load().available) // and does not reload either
        }
        assertEquals(2, discards)
        val loaded = storage.load()
        assertTrue(loaded.available)
        assertContentEquals(imported.learned.getValue("en-US"), assertNotNull(loaded.bytes)) // the imported words, not the late flush
        assertTrue(storage.save(oldBytes)) // the guard is open again
    }

    @Test fun theImportGuardIsReleasedWhenTheImportFails() {
        fill("one")
        val parts = ExportService.collect(ctx, accept)
        val broken = ExportParts(parts.prefsDe, parts.prefsCe, listOf(PersonalWord("", null, 1, null)), parts.learned, parts.files, parts.protectedFiles)
        assertFailsWith<IllegalStateException> { ExportService.applyImport(ctx, broken, accept) { } }
        assertTrue(learned("en-US").isAvailable())
        assertTrue(learned("en-US").save("x".toByteArray()))
    }

    @Test fun theExportFileAndTheStoresHoldNoPlaintext() {
        fill("one")
        val parts = ExportService.collect(ctx, accept)
        assertTrue(parts.prefsCe!!.values.any { it.toString().contains(canary) }) // positive control: the canary is in the exported data
        assertTrue(parts.learned.values.any { it.indexOfSub(canary.toByteArray()) >= 0 })
        val file = ExportCodec.encrypt(passphrase, ExportBundle.pack(parts), fast)
        for (e in encodings(canary)) assertEquals(-1, file.indexOfSub(e))
        val dirs = listOf(ctx.dataDir, DeviceProtectedUtils.getFilesDir(ctx).parentFile!!).distinct()
        for (d in dirs) assertEquals(emptyList(), filesContaining(File(d, "no_backup"), canary), d.path)
    }

    @Test fun theNextWordModelsAndTheirSpeedResultsAreNeverExported() {
        fill("one")
        val models = Privacy.nextWordModelDir(ctx).apply { mkdirs() }
        File(models, "model-en.onnx").writeBytes("model-$canary-weights".toByteArray())
        File(models, "model-en.speed").writeText("p95=1 mb=1")
        File(ctx.filesDir, "layouts/other.json").writeText("layout-$canary-control")
        val parts = ExportService.collect(ctx) { true } // even with every name of the file folders accepted
        val files = parts.files + parts.protectedFiles
        assertTrue(files.values.any { it.indexOfSub("layout-$canary-control".toByteArray()) >= 0 }, "positive control: an accepted file is in the export")
        assertTrue(files.values.none { it.indexOfSub("model-$canary-weights".toByteArray()) >= 0 }, "the model file is not")
        assertTrue(files.values.none { it.indexOfSub("p95=1 mb=1".toByteArray()) >= 0 }, "nor its speed result")
        assertTrue(files.keys.none { it.contains(Privacy.MODELS_DIR) || it.endsWith(".onnx") || it.endsWith(".speed") })
    }

    @Test fun lockedDeviceCannotExportOrImport() {
        val users = ctx.getSystemService(UserManager::class.java)
        fill("one")
        val parts = ExportService.collect(ctx, accept)
        shadowOf(users).setUserUnlocked(false)
        Privacy.forgetCachedState() // a new process: nothing is loaded yet
        assertFailsWith<IllegalStateException> { ExportService.collect(ctx, accept) }
        assertFailsWith<IllegalStateException> { ExportService.apply(ctx, parts, accept) }
        shadowOf(users).setUserUnlocked(true)
        ExportService.collect(ctx, accept) // control
    }

    @Test fun filesReplaceOnlyAcceptedNamesAndStayInsideTheDirectory() {
        val dir = tempDir()
        File(dir, "layouts").mkdirs()
        File(dir, "layouts/old.json").writeText("old")
        File(dir, "keep.txt").writeText("not covered")
        val evil = mapOf("layouts/../../escape.json" to byteArrayOf(1))
        assertFailsWith<IllegalArgumentException> { FileSet.replace(dir, { true }, evil) }
        assertEquals("old", File(dir, "layouts/old.json").readText()) // rolled back
        assertFalse(File(dir.parentFile, "escape.json").exists())

        FileSet.replace(dir, { it.startsWith("layouts/") }, mapOf("layouts/new.json" to "new".toByteArray(), "keep.txt" to "overwritten?".toByteArray()))
        assertFalse(File(dir, "layouts/old.json").exists())
        assertEquals("new", File(dir, "layouts/new.json").readText())
        assertEquals("not covered", File(dir, "keep.txt").readText()) // not accepted: neither deleted nor written
    }

    @Test fun aFailingWriteRestoresTheOldFiles() {
        val dir = tempDir()
        File(dir, "layouts").mkdirs()
        File(dir, "layouts/a.json").writeText("a")
        var calls = 0
        assertFailsWith<java.io.IOException> {
            FileSet.replace(dir, { true }, linkedMapOf("layouts/x.json" to byteArrayOf(1), "layouts/y.json" to byteArrayOf(2))) { f, b ->
                if (++calls == 2) throw java.io.IOException("disk full")
                f.parentFile?.mkdirs(); f.writeBytes(b)
            }
        }
        assertContentEquals("a".toByteArray(), File(dir, "layouts/a.json").readBytes())
        assertFalse(File(dir, "layouts/x.json").exists())
        assertFalse(File(dir, "layouts/y.json").exists())
    }

    // ---- an import does not go through Settings.setIncognitoApps, so the per-app languages are settled after it ------------

    @Test fun anImportThatListsAnAppAsIncognitoAndRemembersItsLanguageKeepsNoLanguageForIt() {
        fill("base")
        val parts = ExportService.collect(ctx, accept)
        val ce = parts.prefsCe!! + mapOf(
            helium314.keyboard.latin.settings.Settings.PREF_INCOGNITO_APPS to PrivacyPolicy.formatApps(setOf("org.example.x")),
            AppLanguageMemory.KEY to AppLanguageMemory.format(linkedMapOf("org.example.x" to "bg", "org.example.y" to "en_US")),
        )
        ExportService.applyImport(ctx, ExportParts(parts.prefsDe, ce, parts.personal, parts.learned, parts.files, parts.protectedFiles), accept) {}
        assertNull(AppLanguageMemory.recall(ctx.protectedPrefs(), "org.example.x"), "incognito app X must have no remembered language right after the import")
        assertEquals("en_US", AppLanguageMemory.recall(ctx.protectedPrefs(), "org.example.y")) // control: the other app kept its language
    }
}
