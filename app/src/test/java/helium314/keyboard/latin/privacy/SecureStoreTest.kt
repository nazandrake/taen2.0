// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecureStoreTest {
    // ---- core behaviour -------------------------------------------------------------------------------

    @Test fun roundTrip() {
        val store = testStore(tempDir())
        assertIs<StoreContent.Missing>(store.read())
        store.write("hello".toByteArray())
        val read = assertIs<StoreContent.Data>(store.read())
        assertContentEquals("hello".toByteArray(), read.bytes)
    }

    @Test fun fileIsCiphertext() {
        val dir = tempDir()
        testStore(dir).write("canary-Zq7".toByteArray())
        assertEquals(emptyList(), filesContaining(dir, "canary-Zq7"))
        assertEquals(listOf("test.enc"), dir.listFiles()!!.map { it.name }) // no temporary file left behind
    }

    // ---- tamper, wrong key, schema, failing file system, locked device ---------------------------------

    private fun written(dir: File = tempDir(), name: String = "test", content: String = "secret"): Pair<File, SecureStore> {
        val store = testStore(dir, name)
        store.write(content.toByteArray())
        return dir to store
    }

    @Test fun flippedCiphertextByteIsRejectedAndTheFileDeleted() {
        val (dir, store) = written()
        val file = File(dir, "test.enc")
        val bytes = file.readBytes()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertIs<StoreContent.Reset>(store.read())
        assertFalse(file.exists())
        assertIs<StoreContent.Missing>(store.read()) // starts fresh, no fallback to anything
    }

    @Test fun flippedHeaderBytesAreRejected() {
        for (index in listOf(0, 3, 4, 7, 8, 15, 19)) { // magic, schema version, nonce
            val (dir, store) = written()
            val file = File(dir, "test.enc")
            val bytes = file.readBytes()
            bytes[index] = (bytes[index].toInt() xor 0x40).toByte()
            file.writeBytes(bytes)
            assertIs<StoreContent.Reset>(store.read(), "header byte $index")
        }
    }

    @Test fun wrongKeyReadsNothing() {
        val dir = tempDir()
        testStore(dir, key = fixedKey(1)).write("secret".toByteArray())
        assertIs<StoreContent.Reset>(testStore(dir, key = fixedKey(2)).read())
        assertFalse(File(dir, "test.enc").exists())
    }

    @Test fun fileMovedToAnotherStoreNameIsRejected() { // the store name is part of the AAD
        val (dir, _) = written(name = "clipboard")
        File(dir, "clipboard.enc").copyTo(File(dir, "snippets.enc"))
        assertIs<StoreContent.Reset>(testStore(dir, "snippets").read())
    }

    @Test fun otherSchemaVersionIsRejected() {
        val dir = tempDir()
        testStore(dir, version = 1).write("secret".toByteArray())
        assertIs<StoreContent.Reset>(testStore(dir, version = 2).read())
    }

    @Test fun truncatedAndEmptyFilesAreRejected() {
        for (size in listOf(0, 1, 19, 20, 35)) {
            val (dir, store) = written()
            val file = File(dir, "test.enc")
            file.writeBytes(file.readBytes().copyOf(size))
            assertIs<StoreContent.Reset>(store.read(), "size $size")
        }
    }

    @Test fun twoWritesOfTheSameDataDiffer() { // fresh nonce each time
        val (dir, store) = written()
        val first = File(dir, "test.enc").readBytes()
        store.write("secret".toByteArray())
        assertNotEquals(first.toList(), File(dir, "test.enc").readBytes().toList())
    }

    @Test fun emptyAndLargeContent() {
        val store = testStore(tempDir())
        store.write(ByteArray(0))
        assertEquals(0, assertIs<StoreContent.Data>(store.read()).bytes.size)
        val big = ByteArray(2_000_000) { (it % 251).toByte() }
        store.write(big)
        assertContentEquals(big, assertIs<StoreContent.Data>(store.read()).bytes)
    }

    private class FailingFiles(
        val failWrite: Boolean = false,
        val failReplace: Boolean = false,
        val corruptTemp: Boolean = false,
    ) : StoreFiles {
        var replaceCalls = 0
        override fun writeSynced(file: File, bytes: ByteArray) {
            if (failWrite) throw IOException("disk full")
            DefaultStoreFiles.writeSynced(file, if (corruptTemp) bytes.copyOf(bytes.size - 3) else bytes)
        }
        override fun replace(from: File, to: File): Boolean {
            replaceCalls++
            return if (failReplace) false else DefaultStoreFiles.replace(from, to)
        }
    }

    private fun assertOldGenerationSurvives(files: FailingFiles) {
        val dir = tempDir()
        testStore(dir).write("old generation".toByteArray())
        val failing = testStore(dir, files = files)
        assertFailsWith<StoreWriteException> { failing.write("new generation".toByteArray()) }
        val read = assertIs<StoreContent.Data>(testStore(dir).read())
        assertEquals("old generation", String(read.bytes))
        assertEquals(listOf("test.enc"), dir.listFiles()!!.map { it.name }) // temporary file removed
    }

    @Test fun failedReplaceKeepsTheOldGeneration() = assertOldGenerationSurvives(FailingFiles(failReplace = true))

    @Test fun failedWriteKeepsTheOldGeneration() = assertOldGenerationSurvives(FailingFiles(failWrite = true))

    @Test fun unverifiableTemporaryFileNeverReplacesTheOldGeneration() {
        val files = FailingFiles(corruptTemp = true)
        assertOldGenerationSurvives(files)
        assertEquals(0, files.replaceCalls) // the swap was never attempted
    }

    @Test fun lockedDeviceReportsUnavailableAndTouchesNothing() {
        val dir = tempDir()
        testStore(dir).write("secret".toByteArray())
        val before = File(dir, "test.enc").readBytes()
        val locked = SecureStore(dir, "test", 1, { throw VaultLockedException("locked") })
        assertIs<StoreContent.Unavailable>(locked.read())
        assertFailsWith<VaultLockedException> { locked.write("x".toByteArray()) }
        assertContentEquals(before, File(dir, "test.enc").readBytes()) // not deleted, not rewritten
        assertTrue(File(dir, "test.enc").exists())
        assertEquals(listOf("test.enc"), dir.listFiles()!!.map { it.name })
    }

    @Test fun unreadableDataKeyIsUnavailableNotReset() {
        val dir = tempDir()
        testStore(dir).write("secret".toByteArray())
        val broken = SecureStore(dir, "test", 1, { throw StoreWriteException("no key file") })
        assertIs<StoreContent.Unavailable>(broken.read())
        assertTrue(File(dir, "test.enc").exists())
    }

    // ---- a key eviction during a read or write must never delete the store or write under zeros -----

    private fun realKeys(dir: File) = DataKeys(FakeKeyVault(), Tier.CE, File(dir, "dek_CE.bin"))

    @Test fun evictionDuringAReadNeverDeletesTheStore() {
        val dir = tempDir()
        val keys = realKeys(dir)
        SecureStore(dir, "s", 1, keys::key).write("secret".toByteArray())
        val evictingRead = object : StoreFiles by DefaultStoreFiles {
            override fun read(file: File): ByteArray { keys.evict(); return file.readBytes() } // the screen lock gave up waiting
        }
        val result = SecureStore(dir, "s", 1, keys::key, evictingRead).read()
        assertTrue(File(dir, "s.enc").exists()) // never deleted
        assertTrue(result is StoreContent.Data || result is StoreContent.Unavailable, "was $result") // never Reset
        assertEquals("secret", String(assertIs<StoreContent.Data>(SecureStore(dir, "s", 1, keys::key).read()).bytes)) // readable afterwards
    }

    @Test fun evictionRightAfterTheKeyWasHandedOutNeverWritesUnderZeros() {
        val dir = tempDir()
        val keys = realKeys(dir)
        val store = SecureStore(dir, "s", 1, { keys.key().also { keys.evict() } })
        try { store.write("secret".toByteArray()) } catch (_: StoreWriteException) { } // failing is allowed, a wrong key is not
        assertTrue(File(dir, "s.enc").exists()) // the write used the real key (the copy), so it went through
        assertEquals("secret", String(assertIs<StoreContent.Data>(SecureStore(dir, "s", 1, keys::key).read()).bytes))
    }

    @Test fun allZeroKeyIsNeverUsed() { // fail closed, whoever handed it out
        val dir = tempDir()
        testStore(dir).write("secret".toByteArray())
        val before = File(dir, "test.enc").readBytes()
        val zeros = SecureStore(dir, "test", 1, { ByteArray(32) })
        assertIs<StoreContent.Unavailable>(zeros.read()) // not Reset
        assertContentEquals(before, File(dir, "test.enc").readBytes())
        assertFailsWith<StoreWriteException> { SecureStore(dir, "z", 1, { ByteArray(32) }).write("x".toByteArray()) }
        assertFalse(File(dir, "z.enc").exists())
        assertFalse(File(dir, "z.enc.tmp").exists())
    }

    @Test fun storeZeroesItsKeyCopyAfterReadAndWrite() {
        val dir = tempDir()
        val handed = ArrayList<ByteArray>()
        val store = SecureStore(dir, "s", 1, { fixedKey().also { handed.add(it) } })
        store.write("secret".toByteArray())
        assertIs<StoreContent.Data>(store.read())
        assertEquals(2, handed.size)
        for (k in handed) assertTrue(k.all { it.toInt() == 0 })
        val missing = SecureStore(dir, "none", 1, { fixedKey().also { handed.add(it) } })
        assertIs<StoreContent.Missing>(missing.read()) // no key is taken for a missing file
        assertEquals(2, handed.size)
    }

    // ---- a transient error must not delete the data ----------------------------------------

    @Test fun ioErrorWhileReadingKeepsTheFileAndIsUnavailable() {
        val dir = tempDir()
        testStore(dir).write("secret".toByteArray())
        val before = File(dir, "test.enc").readBytes()
        val failingRead = object : StoreFiles by DefaultStoreFiles {
            override fun read(file: File): ByteArray = throw IOException("I/O error")
        }
        assertIs<StoreContent.Unavailable>(testStore(dir, files = failingRead).read())
        assertContentEquals(before, File(dir, "test.enc").readBytes()) // not deleted
        assertEquals("secret", String(assertIs<StoreContent.Data>(testStore(dir).read()).bytes)) // readable once the I/O works again
    }

    @Test fun unusableKeyObjectIsUnavailableNotReset() { // a key that is not a valid AES key is a failure of the caller, not of the data
        val dir = tempDir()
        testStore(dir).write("secret".toByteArray())
        assertIs<StoreContent.Unavailable>(SecureStore(dir, "test", 1, { ByteArray(5) }).read())
        assertTrue(File(dir, "test.enc").exists())
        assertIs<StoreContent.Data>(testStore(dir).read())
    }

    @Test fun onlyAnAuthenticationFailureOrABrokenFormatResets() { // controls: these still delete the file
        val dir = tempDir()
        testStore(dir).write("secret".toByteArray())
        assertIs<StoreContent.Reset>(testStore(dir, key = fixedKey(2)).read()) // wrong key: tag mismatch
        assertFalse(File(dir, "test.enc").exists())
        File(dir, "test.enc").writeBytes(ByteArray(10)) // too short to be a store
        assertIs<StoreContent.Reset>(testStore(dir).read())
        assertFalse(File(dir, "test.enc").exists())
    }

    @Test fun aDamagedFileIsKeptWhenTheCallerWillReplaceItAndOnlyThenDoesTheNewWriteTakeItsPlace() {
        val dir = tempDir()
        testStore(dir).write("secret".toByteArray())
        val before = File(dir, "test.enc").readBytes()
        val other = testStore(dir, key = fixedKey(2))
        assertIs<StoreContent.Reset>(other.read(deleteDamaged = false)) // reported as damaged ...
        assertContentEquals(before, File(dir, "test.enc").readBytes()) // ... but not deleted
        assertIs<StoreContent.Reset>(other.read(deleteDamaged = false)) // so it is damaged again at the next start
        other.write("new".toByteArray())
        assertContentEquals("new".toByteArray(), assertIs<StoreContent.Data>(other.read(deleteDamaged = false)).bytes)
    }

    @Test fun separateObjectsForTheSameFileNeverCorruptEachOther() { // they share one lock per file
        val dir = tempDir()
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val threads = (0 until 4).map { t ->
            Thread {
                val store = testStore(dir) // every thread has its own SecureStore object for the same file
                repeat(40) { i ->
                    try { store.write("writer $t round $i".toByteArray()) } catch (_: Exception) { failures.incrementAndGet() }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(0, failures.get())
        val last = String(assertIs<StoreContent.Data>(testStore(dir).read()).bytes)
        assertTrue(Regex("writer [0-3] round \\d+").matches(last), last)
        assertEquals(listOf("test.enc"), dir.listFiles()!!.map { it.name }) // no temporary file left behind
    }
}
