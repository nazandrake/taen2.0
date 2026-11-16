// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.media

import helium314.keyboard.latin.privacy.filesContaining
import helium314.keyboard.latin.privacy.indexOfSub
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Import (formats, limits, refusals), tags, search, delete, delete all, the damaged and the locked cases, and ciphertext at rest. The stores are real
 * AES-GCM stores in a temporary directory.
 */
class MediaVaultTest {
    private val storage = MediaFixtures.TestStorage()
    private val vault = MediaVault(storage)

    private fun items(v: MediaVault = vault) = assertIs<MediaVault.Listing.Items>(v.list()).items
    private fun added(result: MediaVault.ImportResult) = assertIs<MediaVault.ImportResult.Added>(result).item

    // ---- the four formats, stored stripped and encrypted --------------------------------------------

    @Test fun eachFormatIsStoredWithoutMetadataAndComesBackByteIdentical() {
        val cases = listOf(
            Triple(MediaFixtures.jpegDirty, MediaFixtures.jpegClean, MediaFormat.JPEG), Triple(MediaFixtures.pngDirty, MediaFixtures.pngClean, MediaFormat.PNG),
            Triple(MediaFixtures.gifDirty, MediaFixtures.gifClean, MediaFormat.GIF), Triple(MediaFixtures.webpDirty, MediaFixtures.webpClean, MediaFormat.WEBP),
        )
        for ((dirty, clean, format) in cases) {
            val item = added(vault.import(dirty))
            assertEquals(format, item.format)
            assertEquals(clean.size, item.size)
            assertContentEquals(clean, vault.read(item.id), "$format")
        }
        assertEquals(4, items().size)
    }

    @Test fun nothingOfAPictureIsOnDiskInThePlain() {
        added(vault.import(MediaFixtures.jpegDirty, listOf("TaenCanaryTag7q")))
        for (text in listOf(MediaFixtures.EXIF, MediaFixtures.XMP, "TaenCanaryTag7q", "JFIF"))
            assertEquals(emptyList(), filesContaining(storage.dir, text), text)
        assertTrue(storage.files().all { it.startsWith("media_") && it.endsWith(".enc") }, storage.files().toString())
    }

    // ---- limits, refusals, tags, search, delete, damaged, locked --------------------------------------

    @Test fun theLimitsAreTheExpectedNumbers() {
        assertEquals(8 * 1024 * 1024, MediaVault.MAX_ITEM_BYTES)
        assertEquals(500, MediaVault.MAX_ITEMS)
        assertEquals(256L * 1024 * 1024, MediaVault.MAX_TOTAL_BYTES)
        assertEquals(8, MediaVault.MAX_TAGS)
        assertEquals(32, MediaVault.MAX_TAG_LENGTH)
    }

    @Test fun eightMebibytesAreAcceptedAndOneByteMoreIsRefusedWithNothingWritten() {
        val max = MediaFixtures.gifOfSize(MediaVault.MAX_ITEM_BYTES)
        val over = max + byteArrayOf(0)
        assertIs<MediaVault.ImportResult.TooBig>(vault.import(over))
        assertEquals(emptyList(), storage.files(), "nothing was written for the refusal")
        assertEquals(MediaVault.MAX_ITEM_BYTES, added(vault.import(max)).size) // positive control: the limit itself is accepted
    }

    @Test fun theFiveHundredAndFirstPictureIsRefused() {
        val small = MediaVault(storage, maxItems = 3)
        repeat(3) { added(small.import(MediaFixtures.gifClean)) }
        val before = storage.files()
        assertIs<MediaVault.ImportResult.TooMany>(small.import(MediaFixtures.gifClean))
        assertEquals(before, storage.files())
    }

    @Test fun theTotalLimitIsInclusiveAndOneByteOverIsRefused() {
        val size = MediaFixtures.gifClean.size
        val limited = MediaVault(storage, maxTotalBytes = size * 2L)
        added(limited.import(MediaFixtures.gifClean))
        added(limited.import(MediaFixtures.gifClean)) // exactly the limit
        val before = storage.files()
        assertIs<MediaVault.ImportResult.TotalTooBig>(limited.import(MediaFixtures.gifClean))
        assertEquals(before, storage.files())
    }

    @Test fun unknownMagicAndBrokenPicturesAreRefusedWithNothingWritten() {
        assertIs<MediaVault.ImportResult.UnknownFormat>(vault.import(MediaFixtures.ascii("this is not a picture")))
        assertIs<MediaVault.ImportResult.UnknownFormat>(vault.import(ByteArray(0)))
        assertIs<MediaVault.ImportResult.ParseError>(vault.import(MediaFixtures.pngClean.copyOf(30)))
        assertIs<MediaVault.ImportResult.ParseError>(vault.import(MediaFixtures.jpegSoi + MediaFixtures.bytes(0xFF, 0xE1, 0xFF, 0xFF)))
        assertEquals(emptyList(), storage.files())
    }

    @Test fun tagsAreNormalisedAndLimited() {
        val item = added(vault.import(MediaFixtures.gifClean, listOf(" Cat ", "CAT", "", "a b", "x".repeat(33), "ok-1", "t3", "t4", "t5", "t6", "t7", "t8", "t9")))
        assertEquals(listOf("cat", "ok-1", "t3", "t4", "t5", "t6", "t7", "t8"), item.tags) // lower case, trimmed, no duplicate, no empty, none with a space or over 32, at most 8
        assertEquals(listOf("x".repeat(32)), MediaVault.normalizeTags(listOf("X".repeat(32))))
        assertEquals(item.tags, items().single().tags)
    }

    @Test fun searchMatchesTheStartOfEveryWordAndNothingElse() {
        val cat = added(vault.import(MediaFixtures.gifClean, listOf("cat", "funny")))
        val dog = added(vault.import(MediaFixtures.pngClean, listOf("dog", "funny", "котка")))
        val none = added(vault.import(MediaFixtures.jpegClean))
        val all = items()
        assertEquals(listOf(cat.id, dog.id, none.id), MediaVault.search(all, "").map { it.id })
        assertEquals(listOf(cat.id, dog.id), MediaVault.search(all, "fun").map { it.id })
        assertEquals(listOf(cat.id), MediaVault.search(all, "FUN ca").map { it.id }) // every word must match; case is ignored
        assertEquals(listOf(dog.id), MediaVault.search(all, "кот").map { it.id })
        assertEquals(emptyList(), MediaVault.search(all, "unny")) // the start of a tag, not the middle
        assertEquals(emptyList(), MediaVault.search(all, "cat dog"))
    }

    @Test fun setTagsDeleteAndDeleteAllChangeExactlyTheNamedItems() {
        val a = added(vault.import(MediaFixtures.gifClean, listOf("a")))
        val b = added(vault.import(MediaFixtures.pngClean, listOf("b")))
        val c = added(vault.import(MediaFixtures.jpegClean, listOf("c")))
        assertTrue(vault.setTags(b.id, listOf("Bee", "x")))
        assertEquals(listOf("a"), items().first { it.id == a.id }.tags)
        assertEquals(listOf("bee", "x"), items().first { it.id == b.id }.tags)
        assertFalse(vault.setTags("0000000000000000", listOf("zzz")), "an id that is not there")
        assertTrue(vault.delete(a.id))
        assertEquals(setOf(b.id, c.id), items().map { it.id }.toSet())
        assertNull(vault.read(a.id))
        assertFalse(storage.files().contains("media_${a.id}.enc"), "the store of the deleted picture is gone")
        assertContentEquals(MediaFixtures.pngClean, vault.read(b.id)) // the others are untouched
        assertFalse(vault.delete(a.id), "a second delete finds nothing")
        assertTrue(vault.deleteAll())
        assertEquals(emptyList(), items())
        assertEquals(emptyList(), storage.files())
    }

    @Test fun aDamagedListShowsNothingAndOnlyDeleteAllWorks() {
        val item = added(vault.import(MediaFixtures.gifClean))
        val index = storage.dir.resolve("media_index.enc")
        index.writeBytes(index.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }) // the authentication tag no longer fits
        assertIs<MediaVault.Listing.Damaged>(vault.list())
        assertIs<MediaVault.Listing.Damaged>(vault.list(), "the pictures without a list stay damaged until Delete all")
        assertNull(vault.read(item.id))
        assertIs<MediaVault.ImportResult.Damaged>(vault.import(MediaFixtures.pngClean))
        assertFalse(vault.delete(item.id))
        assertTrue(vault.deleteAll())
        assertEquals(emptyList(), storage.files())
        assertEquals(emptyList(), items()) // working again, empty
        added(vault.import(MediaFixtures.pngClean))
    }

    @Test fun aListThatDoesNotFollowTheFormatIsDamagedToo() {
        added(vault.import(MediaFixtures.gifClean))
        MediaVault.INDEX.let { storage.open(it)!!.write(byteArrayOf(2, 0, 0, 0, 0)) } // unknown version, a valid encrypted store
        assertIs<MediaVault.Listing.Damaged>(vault.list())
        storage.open(MediaVault.INDEX)!!.write(byteArrayOf(1, 0, 0, 0, 1)) // says one item, has none
        assertIs<MediaVault.Listing.Damaged>(vault.list())
    }

    @Test fun whileLockedNothingIsListedReadOrWritten() {
        val item = added(vault.import(MediaFixtures.gifClean))
        val before = storage.files()
        storage.locked = true
        assertIs<MediaVault.Listing.Locked>(vault.list())
        assertIs<MediaVault.ImportResult.Locked>(vault.import(MediaFixtures.pngClean))
        assertNull(vault.read(item.id))
        assertFalse(vault.delete(item.id))
        assertFalse(vault.setTags(item.id, listOf("x")))
        assertFalse(vault.deleteAll())
        storage.locked = false
        assertEquals(before, storage.files(), "the locked calls wrote and deleted nothing")
        assertEquals(1, items().size) // control: unlocked again, it is all there
    }

    @Test fun aFailedListWriteRemovesThePictureAgain() {
        val failing = MediaFixtures.TestStorage(failing = setOf(MediaVault.INDEX))
        val v = MediaVault(failing)
        assertIs<MediaVault.ImportResult.Failed>(v.import(MediaFixtures.gifClean))
        assertEquals(emptyList(), failing.files(), "no picture is left without a list entry")
    }

    // ---- replacing the collection (an import): the old set stays until the new one is complete ---------

    private class Crash : Error("the process ends here")

    /** Real stores in [dir] whose [crashAt]-th file write or rename ends the "process" (an Error that nothing catches), as a kill would. */
    private class CrashingStorage(private val dir: java.io.File, private val crashAt: Int) : MediaStorage {
        private var steps = 0
        private val files = object : helium314.keyboard.latin.privacy.StoreFiles by helium314.keyboard.latin.privacy.DefaultStoreFiles {
            override fun writeSynced(file: java.io.File, bytes: ByteArray) { step(); helium314.keyboard.latin.privacy.DefaultStoreFiles.writeSynced(file, bytes) }
            override fun replace(from: java.io.File, to: java.io.File): Boolean { step(); return helium314.keyboard.latin.privacy.DefaultStoreFiles.replace(from, to) }
        }
        private fun step() { if (++steps == crashAt) throw Crash() }
        override fun open(name: String) = helium314.keyboard.latin.privacy.testStore(dir, name, files = files)
        override fun names(): List<String> = dir.listFiles { f -> f.name.endsWith(".enc") }?.map { it.name.removeSuffix(".enc") }.orEmpty()
    }

    /** What the user sees of a collection: every picture's bytes with its tags. */
    private fun contents(v: MediaVault) = items(v).map { v.read(it.id)!!.toList() to it.tags }.toSet()

    private fun incoming(): MediaVault.Snapshot {
        val source = MediaVault(MediaFixtures.TestStorage())
        added(source.import(MediaFixtures.gifClean, listOf("a")))
        added(source.import(MediaFixtures.pngClean, listOf("b")))
        return assertIs<MediaVault.Export.Included>(source.exportAll(Long.MAX_VALUE)).snapshot
    }

    private val oldSet = setOf(MediaFixtures.jpegClean.toList() to listOf("old"))
    private val newSet = setOf(MediaFixtures.gifClean.toList() to listOf("a"), MediaFixtures.pngClean.toList() to listOf("b"))

    private val canaries = listOf(MediaFixtures.EXIF, MediaFixtures.XMP, MediaFixtures.ICC, MediaFixtures.TEXT, MediaFixtures.TRAIL)

    /** A list as an export holds it, for pictures of any content (the vault's own list format: version 1, then id, format, size and tags of each). */
    private fun exportedList(vararg pictures: Triple<String, MediaFormat, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(out).use { o ->
            o.writeByte(1)
            o.writeInt(pictures.size)
            for ((id, format, bytes) in pictures) { o.writeUTF(id); o.writeByte(format.ordinal); o.writeInt(bytes.size); o.writeByte(0) }
        }
        return out.toByteArray()
    }

    @Test fun anImportedPictureThatStillCarriesMetadataIsStoredWithoutIt() {
        val dirty = listOf(
            Triple("0000000000000001", MediaFormat.JPEG, MediaFixtures.jpegDirty), Triple("0000000000000002", MediaFormat.PNG, MediaFixtures.pngDirty),
            Triple("0000000000000003", MediaFormat.GIF, MediaFixtures.gifDirty), Triple("0000000000000004", MediaFormat.WEBP, MediaFixtures.webpDirty),
        )
        val clean = mapOf(MediaFormat.JPEG to MediaFixtures.jpegClean, MediaFormat.PNG to MediaFixtures.pngClean, MediaFormat.GIF to MediaFixtures.gifClean, MediaFormat.WEBP to MediaFixtures.webpClean)
        for ((_, _, bytes) in dirty) assertTrue(canaries.any { bytes.indexOfSub(MediaFixtures.ascii(it)) >= 0 }, "positive control: the fixture carries metadata")
        val snapshot = MediaVault.Snapshot(exportedList(*dirty.toTypedArray()), dirty.associate { it.first to it.third })
        assertTrue(vault.replaceAll(snapshot))
        val stored = items()
        assertEquals(4, stored.size)
        for (item in stored) {
            assertContentEquals(clean.getValue(item.format), vault.read(item.id), "${item.format}")
            assertEquals(clean.getValue(item.format).size, item.size, "the list holds the stored size")
        }
        for (text in canaries) assertEquals(emptyList(), filesContaining(storage.dir, text), text)
    }

    @Test fun aCrashAtAnyPointOfAnImportLeavesTheOldOrTheNewCollectionComplete() {
        val snapshot = incoming()
        var crashes = 0
        for (k in 1..100) {
            val dir = helium314.keyboard.latin.privacy.tempDir()
            added(MediaVault(MediaFixtures.TestStorage(dir)).import(MediaFixtures.jpegClean, listOf("old")))
            val finished = try { MediaVault(CrashingStorage(dir, k)).replaceAll(snapshot) } catch (_: Crash) { null }
            val after = contents(MediaVault(MediaFixtures.TestStorage(dir)))
            if (finished == null) {
                crashes++
                assertTrue(after == oldSet || after == newSet, "a crash at write $k left $after")
            } else {
                assertEquals(true, finished)
                assertEquals(newSet, after, "positive control: without a crash the new set is there")
                assertTrue(crashes >= 3, "the import was interrupted at every one of its $crashes writes before it finished")
                return
            }
        }
        error("the import never finished")
    }

    private fun stores(s: MediaFixtures.TestStorage) = s.files().filter { it.endsWith(".enc") }

    @Test fun afterAnImportOnlyTheNewPicturesAndTheirListAreOnDisk() {
        val s = MediaFixtures.TestStorage()
        val v = MediaVault(s)
        val old = added(v.import(MediaFixtures.jpegClean, listOf("old")))
        assertTrue(v.replaceAll(incoming()))
        assertEquals(newSet, contents(v))
        assertEquals(3, stores(s).size, "two pictures and the list: ${s.files()}")
        assertFalse(stores(s).contains("media_${old.id}.enc"), "the old picture is gone after the switch")
        assertEquals(stores(s), s.files(), "no temporary file is left")
    }

    @Test fun whatACrashedImportLeftIsRemovedByTheNextImport() {
        val dir = helium314.keyboard.latin.privacy.tempDir()
        val s = MediaFixtures.TestStorage(dir)
        added(MediaVault(s).import(MediaFixtures.jpegClean, listOf("old")))
        assertFailsWith<Crash> { MediaVault(CrashingStorage(dir, 3)).replaceAll(incoming()) } // after the first new picture was stored
        assertEquals(oldSet, contents(MediaVault(s)))
        assertEquals(3, stores(s).size, "positive control: the old picture, its list and one unlisted new picture")
        assertTrue(MediaVault(s).replaceAll(incoming()))
        assertEquals(newSet, contents(MediaVault(s)))
        assertEquals(3, stores(s).size, "the unlisted picture of the crashed import is gone: ${s.files()}")
    }

    @Test fun reimportingTheOwnExportKeepsEveryPicture() {
        val s = MediaFixtures.TestStorage()
        val v = MediaVault(s)
        added(v.import(MediaFixtures.gifClean, listOf("a")))
        added(v.import(MediaFixtures.pngClean, listOf("b")))
        val own = assertIs<MediaVault.Export.Included>(v.exportAll(Long.MAX_VALUE)).snapshot
        assertTrue(v.replaceAll(own), "the same ids as the live pictures: written under new ones first")
        assertEquals(newSet, contents(v))
        assertEquals(3, stores(s).size)
    }

    @Test fun aFailedSwitchOrALockedVaultLeavesTheOldCollectionAndNoNewStore() {
        val failing = MediaFixtures.TestStorage(failing = setOf(MediaVault.INDEX))
        val dirFiles = { failing.files() }
        // the list cannot be written: the old collection has to be put in by hand (through the default storage of the same folder)
        val plain = MediaFixtures.TestStorage(failing.dir)
        added(MediaVault(plain).import(MediaFixtures.jpegClean, listOf("old")))
        val before = dirFiles()
        assertFalse(MediaVault(failing).replaceAll(incoming()))
        assertEquals(before, dirFiles(), "the new pictures were removed again")
        assertEquals(oldSet, contents(MediaVault(plain)))
        plain.locked = true
        assertFalse(MediaVault(plain).replaceAll(incoming()))
        plain.locked = false
        assertEquals(before, dirFiles(), "locked: nothing written or removed")
        assertTrue(MediaVault(plain).replaceAll(incoming()), "positive control: unlocked and writable, it is replaced")
        assertEquals(newSet, contents(MediaVault(plain)))
    }

    @Test fun anImportReplacesADamagedCollection() {
        val s = MediaFixtures.TestStorage()
        val v = MediaVault(s)
        added(v.import(MediaFixtures.jpegClean, listOf("old")))
        java.io.File(s.dir, "${MediaVault.INDEX}.enc").delete() // pictures without a list
        assertIs<MediaVault.Listing.Damaged>(v.list())
        assertTrue(v.replaceAll(incoming()))
        assertEquals(newSet, contents(v))
        assertEquals(3, stores(s).size, "the damaged collection's picture is gone")
    }

    /** Real stores in [dir] whose rename of a picture store leaves damaged bytes behind (a disk that does not keep what it was given). */
    private class DamagingStorage(private val dir: java.io.File) : MediaStorage {
        private val files = object : helium314.keyboard.latin.privacy.StoreFiles by helium314.keyboard.latin.privacy.DefaultStoreFiles {
            override fun replace(from: java.io.File, to: java.io.File): Boolean {
                val ok = helium314.keyboard.latin.privacy.DefaultStoreFiles.replace(from, to)
                if (ok && !to.name.startsWith(MediaVault.INDEX)) to.writeBytes(to.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
                return ok
            }
        }
        override fun open(name: String) = helium314.keyboard.latin.privacy.testStore(dir, name, files = files)
        override fun names(): List<String> = dir.listFiles { f -> f.name.endsWith(".enc") }?.map { it.name.removeSuffix(".enc") }.orEmpty()
    }

    @Test fun aPictureThatDoesNotReadBackStopsTheImportBeforeTheSwitch() {
        val dir = helium314.keyboard.latin.privacy.tempDir()
        val s = MediaFixtures.TestStorage(dir)
        added(MediaVault(s).import(MediaFixtures.jpegClean, listOf("old")))
        val before = s.files()
        assertFalse(MediaVault(DamagingStorage(dir)).replaceAll(incoming()))
        assertEquals(oldSet, contents(MediaVault(s)), "the old collection is still listed and readable")
        assertEquals(before, s.files(), "the damaged new picture was removed again")
        assertTrue(MediaVault(s).replaceAll(incoming()), "positive control: on a sound disk the same import goes through")
    }

    @Test fun anInvalidSnapshotChangesNothing() {
        val s = MediaFixtures.TestStorage()
        val v = MediaVault(s)
        added(v.import(MediaFixtures.jpegClean, listOf("old")))
        val before = s.files()
        val good = incoming()
        assertFalse(v.replaceAll(MediaVault.Snapshot(good.index, emptyMap())))
        assertEquals(before, s.files())
        assertEquals(oldSet, contents(v))
    }

    @Test fun idsAreRandomAndUnique() {
        val ids = (1..20).map { added(vault.import(MediaFixtures.gifClean)).id }
        assertEquals(20, ids.toSet().size)
        assertTrue(ids.all { it.matches(Regex("[0-9a-f]{16}")) })
        assertNotNull(vault.read(ids.first()))
    }
}
