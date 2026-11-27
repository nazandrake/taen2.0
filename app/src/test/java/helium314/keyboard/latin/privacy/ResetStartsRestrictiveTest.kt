// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A store that is reset (failed integrity check, lost key, malformed content) is readable but empty, so every privacy switch would fall back to its
 * permissive default. The owner of the reset starts the new store with the restrictive values; the user loosens them on purpose.
 */
class ResetStartsRestrictiveTest {
    private val restrictive = mapOf<String, Any>("incognito" to true, "learn" to false, "retention" to 5)

    private fun prefs(dir: File, key: ByteArray = fixedKey(), reset: Map<String, Any> = restrictive) =
        EncryptedPrefs({ testStore(dir, "prefs", 1, key) }, directExecutor, directNotify, reset)

    @Test fun aStoreThatFailsItsIntegrityCheckStartsWithTheRestrictiveValues() {
        val dir = tempDir()
        prefs(dir, fixedKey(1)).edit().putBoolean("incognito", false).putBoolean("learn", true).putInt("retention", 120).commit()
        val p = prefs(dir, fixedKey(2)) // the data does not authenticate under this key: the store is deleted and reported as reset
        assertTrue(p.getBoolean("incognito", false))
        assertFalse(p.getBoolean("learn", true))
        assertEquals(5, p.getInt("retention", 60))
    }

    @Test fun aFailedWriteOfTheResetLeavesTheDamagedFileSoTheNextStartResetsAgain() { // a restart before any edit, after a failed write
        val dir = tempDir()
        prefs(dir, fixedKey(1), emptyMap()).edit().putBoolean("incognito", false).putBoolean("learn", true).putInt("retention", 120).commit()
        val failingWrite = object : StoreFiles {
            override fun writeSynced(file: File, bytes: ByteArray) = throw java.io.IOException("disk full")
            override fun replace(from: File, to: File) = DefaultStoreFiles.replace(from, to)
        }
        val first = EncryptedPrefs({ testStore(dir, "prefs", 1, fixedKey(2), failingWrite) }, directExecutor, directNotify, restrictive)
        assertTrue(first.getBoolean("incognito", false)) // memory: restrictive
        val restarted = prefs(dir, fixedKey(2)) // a restart before any edit
        assertTrue(restarted.getBoolean("incognito", false))
        assertFalse(restarted.getBoolean("learn", true))
        assertEquals(5, restarted.getInt("retention", 60))
    }

    // ---- pass 2 ----

    /** A file system whose write of the replacement fails (or the process dies) at one step of write-new-then-rename. */
    private enum class Step { WRITE_IO_ERROR, WRITE_DIES_HALF_WAY, VERIFY_MISMATCH, RENAME_REFUSED, RENAME_THROWS, DIES_BEFORE_RENAME }

    private class Died : Error("the process died here")

    private fun brokenAt(step: Step) = object : StoreFiles {
        override fun writeSynced(file: File, bytes: ByteArray) {
            when (step) {
                Step.WRITE_IO_ERROR -> throw java.io.IOException("disk full")
                Step.WRITE_DIES_HALF_WAY -> { file.writeBytes(bytes.copyOf(bytes.size / 2)); throw Died() }
                else -> DefaultStoreFiles.writeSynced(file, bytes)
            }
        }
        override fun read(file: File): ByteArray {
            val bytes = file.readBytes()
            return if (step == Step.VERIFY_MISMATCH && file.name.endsWith(".tmp")) bytes.also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() } else bytes
        }
        override fun replace(from: File, to: File): Boolean = when (step) {
            Step.RENAME_REFUSED -> false
            Step.RENAME_THROWS -> throw java.io.IOException("rename failed")
            Step.DIES_BEFORE_RENAME -> throw Died()
            else -> DefaultStoreFiles.replace(from, to)
        }
    }

    private fun assertRestrictiveAfterRestart(dir: File, key: ByteArray) {
        val restarted = prefs(dir, key)
        assertTrue(restarted.getBoolean("incognito", false))
        assertFalse(restarted.getBoolean("learn", true))
        assertEquals(5, restarted.getInt("retention", 60))
        assertEquals(mapOf<String, Any?>("incognito" to true, "learn" to false, "retention" to 5), restarted.map()) // and the old values are not back
    }

    @Test fun aFailureOrDeathAtEveryStepOfTheReplacementStillStartsRestrictiveAfterARestart() {
        for (step in Step.values()) {
            val dir = tempDir()
            everythingStored(dir)
            val p = EncryptedPrefs({ testStore(dir, "prefs", 1, fixedKey(2), brokenAt(step)) }, directExecutor, directNotify, restrictive)
            try { p.getBoolean("incognito", false) } catch (_: Died) { assertTrue(step == Step.WRITE_DIES_HALF_WAY || step == Step.DIES_BEFORE_RENAME, "$step") }
            assertTrue(File(dir, "prefs.enc").exists(), "$step: the damaged file is not deleted first")
            assertRestrictiveAfterRestart(dir, fixedKey(2))
        }
    }

    @Test fun aFailedReplacementOfMalformedContentLeavesItSoTheNextStartResetsAgain() {
        for (step in Step.values()) {
            val dir = tempDir()
            testStore(dir, "prefs").write(byteArrayOf(1, 2, 3)) // authenticates, but is not a preferences snapshot
            val p = EncryptedPrefs({ testStore(dir, "prefs", 1, fixedKey(), brokenAt(step)) }, directExecutor, directNotify, restrictive)
            try { p.getBoolean("incognito", false) } catch (_: Died) { }
            assertTrue(File(dir, "prefs.enc").exists(), "$step")
            assertRestrictiveAfterRestart(dir, fixedKey())
        }
    }

    @Test fun theReplacementIsAtomicTheDamagedFileIsNeverMissingBeforeTheNewOneIsInPlace() {
        val dir = tempDir()
        everythingStored(dir)
        val seen = ArrayList<Boolean>()
        val watching = object : StoreFiles {
            override fun writeSynced(file: File, bytes: ByteArray) = DefaultStoreFiles.writeSynced(file, bytes).also { seen.add(File(dir, "prefs.enc").exists()) }
            override fun replace(from: File, to: File) = DefaultStoreFiles.replace(from, to)
        }
        EncryptedPrefs({ testStore(dir, "prefs", 1, fixedKey(2), watching) }, directExecutor, directNotify, restrictive).getBoolean("incognito", false)
        assertEquals(listOf(true), seen) // positive control: the write was reached, and the damaged file was still there when it happened
        assertRestrictiveAfterRestart(dir, fixedKey(2))
    }

    @Test fun aFreshInstallStillStartsWithTheDefaultsAfterARestart() { // positive control: nothing was damaged, so nothing is made restrictive
        val dir = tempDir()
        assertFalse(prefs(dir).getBoolean("incognito", false))
        val again = prefs(dir)
        assertFalse(again.getBoolean("incognito", false))
        assertTrue(again.getBoolean("learn", true))
        assertEquals(60, again.getInt("retention", 60))
    }

    @Test fun aLaterLockWritesTheResetValuesThatCouldNotBeWrittenAtFirst() {
        val dir = tempDir()
        everythingStored(dir)
        var failing = true
        val files = object : StoreFiles {
            override fun writeSynced(file: File, bytes: ByteArray) { if (failing) throw java.io.IOException("disk full") else DefaultStoreFiles.writeSynced(file, bytes) }
            override fun replace(from: File, to: File) = DefaultStoreFiles.replace(from, to)
        }
        val p = EncryptedPrefs({ testStore(dir, "prefs", 1, fixedKey(2), files) }, directExecutor, directNotify, restrictive)
        assertTrue(p.getBoolean("incognito", false))
        failing = false
        assertTrue(p.flushAndEvict()) // the screen lock retries the write that failed
        assertEquals(mapOf<String, Any?>("incognito" to true, "learn" to false, "retention" to 5), prefs(dir, fixedKey(2), emptyMap()).map())
    }

    private fun everythingStored(dir: File, key: ByteArray = fixedKey(1)) =
        prefs(dir, key, emptyMap()).edit().putBoolean("incognito", false).putBoolean("learn", true).putInt("retention", 120).putString("other", "kept?").commit()

    @Test fun theResetValuesAreWrittenAtOnceAndHoldAfterARestart() {
        val dir = tempDir()
        everythingStored(dir)
        prefs(dir, fixedKey(2)).getBoolean("incognito", false) // opens the store: the reset happens here
        val reopened = prefs(dir, fixedKey(2), emptyMap()) // no reset values this time: what it reads is on disk
        assertEquals(mapOf<String, Any?>("incognito" to true, "learn" to false, "retention" to 5), reopened.map()) // and nothing else of the old store survived
    }

    @Test fun aStoreWithMalformedContentStartsWithTheRestrictiveValuesToo() {
        val dir = tempDir()
        testStore(dir, "prefs").write(byteArrayOf(1, 2, 3)) // authenticates, but is not a preferences snapshot
        val p = prefs(dir)
        assertTrue(p.getBoolean("incognito", false))
        assertEquals(5, p.getInt("retention", 60))
        assertEquals(5, prefs(dir, reset = emptyMap()).getInt("retention", 60)) // written
    }

    @Test fun aStoreThatNeverExistedStartsEmptyAndWritesNothing() { // positive control: the reset values are for a reset, not for every empty store
        val dir = tempDir()
        val p = prefs(dir)
        assertFalse(p.getBoolean("incognito", false))
        assertTrue(p.getBoolean("learn", true))
        assertEquals(60, p.getInt("retention", 60))
        assertFalse(File(dir, "prefs.enc").exists())
    }

    @Test fun aStoreThatCanBeReadKeepsItsValues() { // positive control: the loosened values of a healthy store are not touched
        val dir = tempDir()
        everythingStored(dir)
        val p = prefs(dir)
        assertFalse(p.getBoolean("incognito", true))
        assertTrue(p.getBoolean("learn", false))
        assertEquals(120, p.getInt("retention", 60))
    }

    @Test fun anUnavailableStoreIsNotResetAndNotTouched() {
        val dir = tempDir()
        everythingStored(dir)
        val before = File(dir, "prefs.enc").readBytes()
        val p = EncryptedPrefs({ SecureStore(dir, "prefs", 1, { throw VaultUnavailableException("busy") }) }, directExecutor, directNotify, restrictive)
        assertFalse(p.isAvailable())
        assertTrue(before.contentEquals(File(dir, "prefs.enc").readBytes()))
        assertEquals(true, prefs(dir, fixedKey(1), emptyMap()).contains("other")) // the old values are still there once it can be read
    }

    @Test fun theUserLoosensTheValuesOnPurposeAndTheChoiceHolds() {
        val dir = tempDir()
        everythingStored(dir)
        assertTrue(prefs(dir, fixedKey(2)).edit().putBoolean("incognito", false).putInt("retention", 60).commit())
        val p = prefs(dir, fixedKey(2))
        assertFalse(p.getBoolean("incognito", true))
        assertEquals(60, p.getInt("retention", 5))
        assertFalse(p.getBoolean("learn", true)) // what was not changed stays restrictive
    }

    @Test fun aFailedWriteOfTheResetKeepsTheValuesInMemoryAndTheNextEditWritesThemAll() {
        val dir = tempDir()
        everythingStored(dir)
        var failing = true
        val files = object : StoreFiles {
            override fun writeSynced(file: File, bytes: ByteArray) { if (failing) throw java.io.IOException("disk full") else DefaultStoreFiles.writeSynced(file, bytes) }
            override fun replace(from: File, to: File) = DefaultStoreFiles.replace(from, to)
        }
        val p = EncryptedPrefs({ testStore(dir, "prefs", 1, fixedKey(2), files) }, directExecutor, directNotify, restrictive)
        assertTrue(p.getBoolean("incognito", false)) // the restrictive value decides even though it could not be saved
        assertEquals(5, p.getInt("retention", 60))
        failing = false
        assertTrue(p.edit().putString("x", "y").commit()) // the next edit writes the complete snapshot
        assertEquals(mapOf<String, Any?>("incognito" to true, "learn" to false, "retention" to 5, "x" to "y"), prefs(dir, fixedKey(2), emptyMap()).map())
    }
}
