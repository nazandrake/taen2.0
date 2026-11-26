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

    // ---- pass 2 ----

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
