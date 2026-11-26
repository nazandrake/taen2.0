// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.io.File
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The words the user removed from the suggestions (the blacklist of one language): kept only in an encrypted store, read and written
 * only while the store is available, and moved in from the plain file of an earlier version only after the encrypted copy was read
 * back and found equal.
 */
class RemovedWordsTest {
    private val canary = "TAENcanary-Qx93"
    private val cyrillic = "ТАЕНканарче"
    private val dir = tempDir()
    private val key = fixedKey()
    private var storeAvailable = true
    private fun storage(legacy: LegacyRemovedWords? = null) =
        RemovedWordsStorage({ if (storeAvailable) testStore(dir, "blacklist_en-US", key = key) else null }, legacy)

    @Test fun removedWordsAreKeptEncryptedAndComeBackInANewProcess() {
        val words = RemovedWords(storage())
        assertTrue(words.add(canary))
        assertTrue(words.add(cyrillic))
        assertTrue(words.contains(canary))
        // a new process reads them back from the store
        assertEquals(setOf(canary, cyrillic), RemovedWords(storage()).all())
        assertEquals(listOf("blacklist_en-US.enc"), dir.list()!!.toList())
        assertEquals(emptyList(), filesContaining(dir, canary))
        assertEquals(emptyList(), filesContaining(dir, cyrillic))
        assertTrue(File(dir, "blacklist_en-US.enc").length() > 0)
    }

    /** The plain file of an earlier version, in memory, with a switch for a read that fails. */
    private class Legacy(var words: List<String>?) : LegacyRemovedWords {
        var present = true
        var deletes = 0
        override fun exists() = present
        override fun read() = words
        override fun delete(): Boolean { deletes++; present = false; return true }
    }

    @Test fun thePlainFileOfAnEarlierVersionIsMovedInAndThenDeleted() {
        val legacy = Legacy(listOf(canary, cyrillic))
        assertEquals(setOf(canary, cyrillic), RemovedWords(storage(legacy)).all())
        assertEquals(1, legacy.deletes)
        // the words are in the encrypted store now: a new process without the plain file still has them
        assertEquals(setOf(canary, cyrillic), RemovedWords(storage()).all())
        assertEquals(emptyList(), filesContaining(dir, canary))
    }

    // ---- pass 2: locked, damaged, failed writes, bounds, the memory copy ---------------------------------------------

    private val storeFile get() = File(dir, "blacklist_en-US.enc")

    @Test fun whileLockedNothingIsReadWrittenOrMovedAndThePlainFileStays() {
        val legacy = Legacy(listOf(canary))
        storeAvailable = false
        val words = RemovedWords(storage(legacy))
        assertNull(words.all())
        assertFalse(words.add(cyrillic))
        assertFalse(words.remove(canary))
        assertFalse(words.forget())
        assertEquals(0, legacy.deletes) // the plain file is left as it is while locked
        assertTrue(legacy.present)
        assertEquals(emptyList(), dir.list()!!.toList()) // no store, no key file
        storeAvailable = true // positive control: the same object works after the unlock and moves the file then
        assertEquals(setOf(canary), words.all())
        assertEquals(1, legacy.deletes)
    }

    @Test fun aKeyThatCannotBeUsedNowLeavesTheStoreAndThePlainFile() {
        val vault = FakeKeyVault()
        fun keyed(legacy: LegacyRemovedWords?) = RemovedWordsStorage({ SecureStore(dir, "blacklist_en-US", 1, DataKeys(vault, Tier.CE, File(dir, "dek_ce.bin"))::key) }, legacy)
        assertTrue(RemovedWords(keyed(null)).add(canary))
        val before = storeFile.readBytes()
        vault.locked = true
        val legacy = Legacy(listOf(cyrillic))
        assertNull(keyed(legacy).load())
        assertContentEquals(before, storeFile.readBytes()) // not reset
        assertEquals(0, legacy.deletes)
        vault.locked = false
        assertEquals(setOf(canary, cyrillic), keyed(legacy).load()) // positive control
        assertEquals(1, legacy.deletes)
    }

    @Test fun aDamagedStoreLosesItsWordsButNeverThePlainFile() {
        assertTrue(RemovedWords(storage()).add("old"))
        storeFile.writeBytes(storeFile.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }) // a failed authentication tag
        val legacy = Legacy(listOf(canary))
        assertEquals(setOf(canary), storage(legacy).load()) // the store's words are gone, the plain file's words moved in
        assertEquals(1, legacy.deletes)
        // a store that decrypts but does not decode: deleted, never read in part
        testStore(dir, "blacklist_en-US", key = key).write(byteArrayOf(9, 9, 9))
        val again = Legacy(listOf(cyrillic))
        assertEquals(setOf(cyrillic), storage(again).load())
        assertEquals(setOf(cyrillic), storage().load())
    }

    @Test fun thePlainFileIsDeletedOnlyAfterTheStoreReadsBackEqual() {
        // a store whose final file reads back as other bytes: the write itself verified its temporary file, the read-back does not match
        val broken = object : StoreFiles {
            override fun writeSynced(file: File, bytes: ByteArray) = DefaultStoreFiles.writeSynced(file, bytes)
            override fun replace(from: File, to: File) = DefaultStoreFiles.replace(from, to)
            override fun read(file: File): ByteArray = if (file.name.endsWith(".enc")) ByteArray(60) { 1 } else file.readBytes()
        }
        val legacy = Legacy(listOf(canary))
        RemovedWordsStorage({ testStore(dir, "blacklist_en-US", key = key, files = broken) }, legacy).load()
        assertEquals(0, legacy.deletes)
        // a write that fails (the rename): the plain file stays too
        val noRename = object : StoreFiles {
            override fun writeSynced(file: File, bytes: ByteArray) = DefaultStoreFiles.writeSynced(file, bytes)
            override fun replace(from: File, to: File) = false
        }
        RemovedWordsStorage({ testStore(dir, "blacklist_en-US", key = key, files = noRename) }, legacy).load()
        assertEquals(0, legacy.deletes)
        assertTrue(legacy.present)
        // positive control: the same plain file with a working store is moved
        storage(legacy).load()
        assertEquals(1, legacy.deletes)
    }

    @Test fun aPlainFileThatCannotBeReadOrDoesNotFitStays() {
        val unreadable = Legacy(null)
        assertEquals(emptySet(), storage(unreadable).load())
        assertEquals(0, unreadable.deletes)
        val tooMany = Legacy((1..RemovedWords.MAX_WORDS + 1).map { "w$it" })
        assertEquals(emptySet(), storage(tooMany).load())
        assertEquals(0, tooMany.deletes)
        val fits = Legacy((1..RemovedWords.MAX_WORDS).map { "w$it" }) // positive control: the largest list that fits is moved
        assertEquals(RemovedWords.MAX_WORDS, storage(fits).load()!!.size)
        assertEquals(1, fits.deletes)
    }

    @Test fun anInterruptedMoveIsFinishedWithTheWordsOfBoth() {
        assertTrue(RemovedWords(storage()).add("stored"))
        val legacy = Legacy(listOf("plain", "stored", "", "x".repeat(49)))
        assertEquals(setOf("stored", "plain"), storage(legacy).load()) // empty and over-long lines are no words
        assertEquals(1, legacy.deletes)
    }

    @Test fun wordsAreBounded() {
        val words = RemovedWords(storage())
        assertFalse(words.add(""))
        assertFalse(words.add("x".repeat(RemovedWords.MAX_WORD_LENGTH + 1)))
        assertFalse(words.add("two\nlines"))
        assertTrue(words.add("x".repeat(RemovedWords.MAX_WORD_LENGTH))) // positive control at the limit
        assertTrue(storage().save((1..RemovedWords.MAX_WORDS).map { "w$it" }.toSet()))
        val full = RemovedWords(storage())
        assertFalse(full.add("one-more"))
        assertTrue(full.add("w1")) // a word already there is fine
        assertEquals(RemovedWords.MAX_WORDS, RemovedWords(storage()).all()!!.size)
    }

    @Test fun aChangeThatCannotBeStoredIsRefused() {
        val words = RemovedWords(storage())
        assertTrue(words.add(canary))
        storeAvailable = false
        assertFalse(words.remove(canary))
        assertTrue(words.contains(canary)) // still removed: the restore was not stored
        assertFalse(words.add(cyrillic))
        assertFalse(words.contains(cyrillic))
        storeAvailable = true
        assertTrue(words.remove(canary)) // positive control
        assertFalse(words.contains(canary))
        assertEquals(emptySet(), RemovedWords(storage()).all())
    }

    @Test fun forgetDeletesTheStoreAndThePlainFile() {
        val legacy = Legacy(listOf(canary))
        val words = RemovedWords(storage(legacy))
        legacy.present = true
        assertTrue(words.add(cyrillic))
        legacy.present = true // a plain file that appeared again (an older copy restored)
        assertTrue(words.forget())
        assertEquals(emptySet(), words.all())
        assertFalse(storeFile.exists())
        assertFalse(legacy.present)
    }

    @Test fun theSuggestionPathNeverWaitsAndLoadsInTheBackgroundButNotWhileLocked() {
        assertTrue(RemovedWords(storage()).add(canary))
        val queued = ArrayList<Runnable>()
        var locked = true
        var clock = 0L
        val words = RemovedWords(storage(), Executor { queued.add(it) }, { locked }, { clock })
        assertFalse(words.contains(canary)) // not loaded: no I/O on this path
        assertEquals(0, queued.size) // and no load while locked
        locked = false
        clock += 5_000
        assertFalse(words.contains(canary))
        assertEquals(1, queued.size) // one load in the background
        assertFalse(words.contains(canary))
        assertEquals(1, queued.size) // not a second one while it runs
        queued.removeAt(0).run()
        assertTrue(words.contains(canary))
    }

    @Test fun theBinIconHidesAWordAtOnce() {
        val words = RemovedWords(storage())
        words.hideNow(canary)
        assertTrue(words.contains(canary)) // before it is stored
        assertEquals(emptySet(), RemovedWords(storage()).all()) // not stored by hiding alone
        assertTrue(words.add(canary))
        assertEquals(setOf(canary), RemovedWords(storage()).all())
        assertTrue(words.remove(canary))
        assertFalse(words.contains(canary)) // a restore also ends the hiding
    }

    @Test fun aStoreThatReadsBackOtherWordsKeepsThePlainFile() {
        assertTrue(RemovedWords(storage()).add("stale"))
        val stale = storeFile.readBytes() // a valid store of other words, as a file system that serves an older generation would
        storeFile.delete()
        val staleRead = object : StoreFiles {
            override fun writeSynced(file: File, bytes: ByteArray) = DefaultStoreFiles.writeSynced(file, bytes)
            override fun replace(from: File, to: File) = DefaultStoreFiles.replace(from, to)
            override fun read(file: File): ByteArray = if (file.name.endsWith(".enc")) stale else file.readBytes()
        }
        val legacy = Legacy(listOf(canary))
        RemovedWordsStorage({ testStore(dir, "blacklist_en-US", key = key, files = staleRead) }, legacy).load()
        assertEquals(0, legacy.deletes) // the copy that reads back is not the plain file's words
        storage(legacy).load() // positive control: read back equal, the plain file goes
        assertEquals(1, legacy.deletes)
    }
}
