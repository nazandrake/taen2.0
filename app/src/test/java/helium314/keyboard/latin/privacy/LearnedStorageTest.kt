// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The storage of one language's learned-word dictionary (the serialized native dictionary): encrypted at rest,
 * unavailable while the device is locked, a damaged file means the learned words are lost (never read as plain or
 * partly), and the plain directory of an earlier version is moved in only after the encrypted copy is stored.
 */
class LearnedStorageTest {
    private val canary = "TAENcanary-Qx93"
    private val dir = tempDir()
    private val key = fixedKey()
    private var storeAvailable = true
    private val storage = LearnedStorage({ if (storeAvailable) testStore(dir, "learned_en", key = key) else null }, null)

    private fun payload(text: String = canary) = byteArrayOf(1, 2, 3) + text.toByteArray() + ByteArray(20) { it.toByte() }

    // ---- core behaviour -------------------------------------------------------------------------------

    @Test fun savedBytesComeBackAndAreNotOnDiskAsPlaintext() {
        val bytes = payload()
        assertTrue(storage.save(bytes))
        val loaded = storage.load()
        assertTrue(loaded.available)
        assertContentEquals(bytes, assertNotNull(loaded.bytes))
        assertEquals(emptyList(), filesContaining(dir, canary))
        assertEquals(listOf("learned_en.enc"), dir.list()!!.toList())
    }

    @Test fun nothingSavedMeansEmptyButAvailable() {
        val loaded = storage.load()
        assertTrue(loaded.available)
        assertNull(loaded.bytes)
    }

    // ---- locked, damaged, legacy, isolation ---------------------------------------------------------

    @Test fun unavailableStoreIsReportedAndNothingIsWritten() {
        storeAvailable = false
        assertFalse(storage.isAvailable())
        val loaded = storage.load()
        assertFalse(loaded.available)
        assertNull(loaded.bytes)
        assertFalse(storage.save(payload()))
        assertEquals(emptyList(), dir.list()!!.toList()) // no file, no key file
        storeAvailable = true // positive control: the same calls work once the store is available
        assertTrue(storage.isAvailable())
        assertTrue(storage.save(payload()))
        assertTrue(storage.load().available)
    }

    @Test fun lockedKeyIsUnavailableAndKeepsTheFile() {
        val vault = FakeKeyVault()
        val locked = LearnedStorage({
            val keys = DataKeys(vault, Tier.CE, File(dir, "dek_ce.bin"))
            SecureStore(dir, "learned_en", 1, keys::key)
        }, null)
        assertTrue(locked.save(payload()))
        val before = File(dir, "learned_en.enc").readBytes()
        vault.locked = true
        // a new process after a reboot: the data key is not cached
        val rebooted = LearnedStorage({
            val keys = DataKeys(vault, Tier.CE, File(dir, "dek_ce.bin"))
            SecureStore(dir, "learned_en", 1, keys::key)
        }, null)
        val loaded = rebooted.load()
        assertFalse(loaded.available)
        assertNull(loaded.bytes)
        assertFalse(rebooted.save(payload("other")))
        assertContentEquals(before, File(dir, "learned_en.enc").readBytes()) // untouched, not reset
        vault.locked = false
        assertContentEquals(payload(), assertNotNull(rebooted.load().bytes))
    }

    @Test fun tamperedFileLosesTheLearnedWordsAndStartsEmpty() {
        assertTrue(storage.save(payload()))
        val file = File(dir, "learned_en.enc")
        val bytes = file.readBytes()
        bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        val loaded = storage.load()
        assertTrue(loaded.available)
        assertNull(loaded.bytes)
        assertFalse(file.exists()) // deleted, no fallback
        assertTrue(storage.save(payload("fresh"))) // and usable again
        assertContentEquals(payload("fresh"), assertNotNull(storage.load().bytes))
    }

    @Test fun wrongKeyLosesTheLearnedWords() {
        assertTrue(storage.save(payload()))
        val other = LearnedStorage({ testStore(dir, "learned_en", key = fixedKey(9)) }, null)
        val loaded = other.load()
        assertTrue(loaded.available)
        assertNull(loaded.bytes)
        assertFalse(File(dir, "learned_en.enc").exists())
    }

    @Test fun failedWriteKeepsTheOldGeneration() {
        assertTrue(storage.save(payload("old")))
        val failing = LearnedStorage({
            testStore(dir, "learned_en", key = key, files = object : StoreFiles {
                override fun writeSynced(file: File, bytes: ByteArray) = throw java.io.IOException("disk full")
                override fun replace(from: File, to: File) = false
            })
        }, null)
        assertFalse(failing.save(payload("new")))
        assertContentEquals(payload("old"), assertNotNull(storage.load().bytes))
    }

    @Test fun deleteRemovesTheStoreAndTheLegacyCopy() {
        val legacy = FakeLegacy(payload("legacy"))
        val s = LearnedStorage({ testStore(dir, "learned_en", key = key) }, legacy)
        assertTrue(s.save(payload()))
        s.delete()
        assertFalse(File(dir, "learned_en.enc").exists())
        assertFalse(legacy.exists())
        val loaded = s.load()
        assertTrue(loaded.available)
        assertNull(loaded.bytes)
    }

    @Test fun languagesAreSeparateStores() {
        val en = LearnedStorage({ testStore(dir, "learned_en", key = key) }, null)
        val bg = LearnedStorage({ testStore(dir, "learned_bg", key = key) }, null)
        assertTrue(en.save(payload("english")))
        assertNull(bg.load().bytes)
        assertTrue(bg.save(payload("bulgarian")))
        assertContentEquals(payload("english"), assertNotNull(en.load().bytes))
        assertContentEquals(payload("bulgarian"), assertNotNull(bg.load().bytes))
    }

    private class FakeLegacy(private val converted: ByteArray?, private var present: Boolean = true) : LegacyLearned {
        var converts = 0
        override fun exists() = present
        override fun toSerialized(): ByteArray? { converts++; return converted }
        override fun delete() { present = false }
    }

    @Test fun legacyDictionaryIsMovedInAndDeletedOnlyAfterTheEncryptedCopyIsStored() {
        val legacy = FakeLegacy(payload("from the old file"))
        val s = LearnedStorage({ testStore(dir, "learned_en", key = key) }, legacy)
        val loaded = s.load()
        assertContentEquals(payload("from the old file"), assertNotNull(loaded.bytes))
        assertFalse(legacy.exists())
        assertContentEquals(payload("from the old file"), assertNotNull(s.load().bytes)) // it is in the store now
        assertEquals(1, legacy.converts)
    }

    @Test fun legacyIsKeptWhileTheEncryptedCopyCannotBeStored() {
        val legacy = FakeLegacy(payload("from the old file"))
        val failing = LearnedStorage({
            testStore(dir, "learned_en", key = key, files = object : StoreFiles {
                override fun writeSynced(file: File, bytes: ByteArray) = throw java.io.IOException("disk full")
                override fun replace(from: File, to: File) = false
            })
        }, legacy)
        val loaded = failing.load()
        assertContentEquals(payload("from the old file"), assertNotNull(loaded.bytes)) // usable for this run
        assertTrue(legacy.exists()) // but the original stays until it is stored
        assertFalse(File(dir, "learned_en.enc").exists())
    }

    @Test fun unreadableLegacyIsDeletedAndStartsEmpty() {
        val legacy = FakeLegacy(null)
        val s = LearnedStorage({ testStore(dir, "learned_en", key = key) }, legacy)
        val loaded = s.load()
        assertTrue(loaded.available)
        assertNull(loaded.bytes)
        assertFalse(legacy.exists()) // a plaintext file never lingers
    }

    @Test fun legacyIsLeftAloneWhileTheStoreIsUnavailable() {
        val legacy = FakeLegacy(payload("old"))
        storeAvailable = false
        val s = LearnedStorage({ if (storeAvailable) testStore(dir, "learned_en", key = key) else null }, legacy)
        assertFalse(s.load().available)
        assertTrue(legacy.exists())
        assertEquals(0, legacy.converts)
    }

    @Test fun existingStoreWinsOverALeftoverLegacyCopy() {
        // interrupted earlier move: the encrypted copy was stored, the plain one not yet deleted
        val s0 = LearnedStorage({ testStore(dir, "learned_en", key = key) }, null)
        assertTrue(s0.save(payload("newer")))
        val legacy = FakeLegacy(payload("older"))
        val s = LearnedStorage({ testStore(dir, "learned_en", key = key) }, legacy)
        assertContentEquals(payload("newer"), assertNotNull(s.load().bytes))
        assertFalse(legacy.exists())
        assertEquals(0, legacy.converts)
    }
}
