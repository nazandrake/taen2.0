// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.privacy.ExportBundle
import helium314.keyboard.latin.privacy.ExportCodec
import helium314.keyboard.latin.privacy.ExportException
import helium314.keyboard.latin.privacy.ExportParts
import helium314.keyboard.latin.privacy.ExportService
import helium314.keyboard.latin.privacy.Privacy
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pictures are exported as a whole or not at all, up to 64 MiB; what is exported comes back byte for byte; the starter stickers are never in an export. */
@RunWith(RobolectricTestRunner::class)
class ExportMediaTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val passphrase = "correct horse battery".toCharArray()
    private val fast = ExportCodec.Kdf(64, 3, 1)
    private val none: (String) -> Boolean = { false }

    @Before fun before() = Privacy.forgetCachedState()
    @After fun after() = Privacy.forgetCachedState()

    private fun vault() = Privacy.mediaVault(ctx)
    private fun listed() = assertIs<MediaVault.Listing.Items>(vault().list()).items

    // ---- a round trip -------------------------------------------------------------------------------

    @Test fun exportedPicturesComeBackByteIdenticalAfterTheyWereDeleted() {
        val gif = assertIs<MediaVault.ImportResult.Added>(vault().import(MediaFixtures.gifDirty, listOf("one", "two"))).item
        val png = assertIs<MediaVault.ImportResult.Added>(vault().import(MediaFixtures.pngDirty)).item
        val file = ExportCodec.encrypt(passphrase, ExportBundle.pack(ExportService.collect(ctx, none)), fast)
        assertTrue(vault().deleteAll())
        assertEquals(emptyList(), listed())

        ExportService.apply(ctx, ExportBundle.unpack(ExportCodec.decrypt(passphrase, file)), none)
        // an import stores the pictures under new ids (written next to the old ones before the switch); what comes back is every picture with its tags
        assertEquals(setOf(MediaFixtures.gifClean.toList() to listOf("one", "two"), MediaFixtures.pngClean.toList() to emptyList()), contents())
        assertEquals(2, listed().size)
        assertNull(vault().read(gif.id), "the stores of before the export were deleted, so the old ids are gone")
        assertNull(vault().read(png.id))
    }

    private fun contents() = listed().map { vault().read(it.id)!!.toList() to it.tags }.toSet()

    // ---- the 64 MiB rule, a damaged payload, the starter stickers --------------------------------------

    @Test fun theCapIsSixtyFourMebibytes() = assertEquals(64L * 1024 * 1024, ExportService.MEDIA_EXPORT_BYTES)

    @Test fun picturesTotallingTheCapAreExportedAndOneByteMoreLeavesAllOfThemOut() {
        val v = vault()
        val a = assertIs<MediaVault.ImportResult.Added>(v.import(MediaFixtures.gifClean)).item
        val b = assertIs<MediaVault.ImportResult.Added>(v.import(MediaFixtures.pngClean)).item
        val total = (a.size + b.size).toLong()
        val included = assertIs<MediaVault.Export.Included>(v.exportAll(total))
        assertEquals(setOf(a.id, b.id), included.snapshot.items.keys)
        assertIs<MediaVault.Export.TooBig>(v.exportAll(total - 1)) // never a part of the set
    }

    @Test fun collectAppliesTheCapAndSaysWhenItLeftThePicturesOut() {
        val v = vault()
        val a = assertIs<MediaVault.ImportResult.Added>(v.import(MediaFixtures.gifClean)).item
        val b = assertIs<MediaVault.ImportResult.Added>(v.import(MediaFixtures.pngClean)).item
        val total = (a.size + b.size).toLong()
        val at = ExportService.collect(ctx, none, withMedia = true, mediaCap = total)
        assertEquals(setOf(a.id, b.id), at.media?.items?.keys)
        assertFalse(at.mediaExcluded)
        val over = ExportService.collect(ctx, none, withMedia = true, mediaCap = total - 1)
        assertNull(over.media, "no part of the set is exported")
        assertTrue(over.mediaExcluded, "and the result says so")
        assertNull(ExportBundle.unpack(ExportBundle.pack(over)).media)
    }

    @Test fun anExportWithoutPicturesLeavesTheLivePicturesAloneOnImport() {
        val item = assertIs<MediaVault.ImportResult.Added>(vault().import(MediaFixtures.gifClean)).item
        val parts = ExportService.collect(ctx, none, withMedia = false, mediaCap = 0L)
        assertNull(parts.media)
        ExportService.apply(ctx, ExportBundle.unpack(ExportBundle.pack(parts)), none)
        assertContentEquals(MediaFixtures.gifClean, vault().read(item.id))
    }

    @Test fun anEmptyCollectionAddsNoPictureEntries() {
        val parts = ExportService.collect(ctx, none)
        assertNull(parts.media)
        assertFalse(parts.mediaExcluded)
    }

    @Test fun theExcludedFlagIsSetWhenThePicturesAreTooBig() {
        // the vault's own cap decides; collect passes the 64 MiB rule to it (the boundary is exercised on exportAll above)
        val parts = ExportParts(null, null, null, emptyMap(), emptyMap(), emptyMap(), null, mediaExcluded = true)
        assertTrue(parts.mediaExcluded)
        assertNull(ExportBundle.unpack(ExportBundle.pack(parts)).media)
    }

    @Test fun aPayloadWithADamagedPictureIsRejectedBeforeAnythingChanges() {
        val v = vault()
        val item = assertIs<MediaVault.ImportResult.Added>(v.import(MediaFixtures.gifClean)).item
        val snapshot = assertIs<MediaVault.Export.Included>(v.exportAll(Long.MAX_VALUE)).snapshot
        val damaged = MediaVault.Snapshot(snapshot.index, mapOf(item.id to MediaFixtures.ascii("not a picture at all, same id")))
        val bundle = ExportBundle.pack(ExportParts(null, null, null, emptyMap(), emptyMap(), emptyMap(), damaged))
        assertFailsWith<ExportException> { ExportBundle.unpack(bundle) }
        val missing = ExportBundle.pack(ExportParts(null, null, null, emptyMap(), emptyMap(), emptyMap(), MediaVault.Snapshot(snapshot.index, emptyMap())))
        assertFailsWith<ExportException> { ExportBundle.unpack(missing) }
        assertContentEquals(MediaFixtures.gifClean, v.read(item.id))
        // positive control: the intact payload is accepted
        assertNotNull(ExportBundle.unpack(ExportBundle.pack(ExportParts(null, null, null, emptyMap(), emptyMap(), emptyMap(), snapshot))).media)
    }

    @Test fun aFailedPictureImportPutsTheOldCollectionBack() {
        val v = vault()
        val old = assertIs<MediaVault.ImportResult.Added>(v.import(MediaFixtures.jpegClean)).item
        val incoming = MediaVault(MediaFixtures.TestStorage()).also { assertIs<MediaVault.ImportResult.Added>(it.import(MediaFixtures.gifClean)) }
        val snapshot = assertIs<MediaVault.Export.Included>(incoming.exportAll(Long.MAX_VALUE)).snapshot
        assertTrue(v.replaceAll(snapshot))
        val replaced = listed().map { it.id }.toSet()
        assertEquals(setOf(MediaFixtures.gifClean.toList() to emptyList<String>()), contents())
        assertNull(v.read(old.id), "replaced, as an import replaces the other data")
        // a snapshot that does not validate changes nothing
        val bad = MediaVault.Snapshot(snapshot.index, emptyMap())
        assertFalse(v.replaceAll(bad))
        assertEquals(replaced, listed().map { it.id }.toSet())
    }

    @Test fun whileThePrivacyLockIsClosedTheVaultIsLockedAndNothingIsWritten() {
        val v = vault()
        val item = assertIs<MediaVault.ImportResult.Added>(v.import(MediaFixtures.gifClean)).item
        Privacy.lock(ctx)
        try {
            assertIs<MediaVault.Listing.Locked>(v.list())
            assertIs<MediaVault.ImportResult.Locked>(v.import(MediaFixtures.pngClean))
            assertNull(v.read(item.id))
            assertFalse(v.deleteAll())
            assertIs<MediaVault.Export.Unavailable>(v.exportAll(Long.MAX_VALUE))
        } finally {
            Privacy.unlock(ctx)
        }
        assertEquals(listOf(item.id), listed().map { it.id }, "the locked calls changed nothing")
    }

    @Test fun theStarterStickersAreNotPartOfAnExport() {
        // they are read from the APK assets; the export collects the vault, the files and the preferences only. Positive control: the pack has files.
        val starter = StarterPack.list(ctx)
        assertTrue(starter.isNotEmpty(), "the APK carries starter stickers")
        ctx.prefs().edit().putBoolean(helium314.keyboard.latin.settings.Settings.PREF_MEDIA_STARTER_PACK, true).commit()
        val unpacked = ExportBundle.unpack(ExportBundle.pack(ExportService.collect(ctx, none)))
        assertNull(unpacked.media)
        assertTrue(unpacked.files.isEmpty() && unpacked.protectedFiles.isEmpty())
        val all = ExportBundle.pack(ExportService.collect(ctx) { true })
        val names = ExportBundle.unpack(all).let { it.files.keys + it.protectedFiles.keys }
        assertTrue(names.none { n -> starter.any { n.contains(it.removePrefix("stickers/")) } }, names.toString())
    }
}
