// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.media

import helium314.keyboard.latin.privacy.filesContaining
import kotlin.test.Test
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

    @Test fun idsAreRandomAndUnique() {
        val ids = (1..20).map { added(vault.import(MediaFixtures.gifClean)).id }
        assertEquals(20, ids.toSet().size)
        assertTrue(ids.all { it.matches(Regex("[0-9a-f]{16}")) })
        assertNotNull(vault.read(ids.first()))
    }
}
