// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyMigrationTest {
    private class FakePlain(var values: Map<String, Any?>) : PlainPrefsSource {
        var deleted = false
        override fun all(): Map<String, *> = values
        override fun delete(): Boolean { deleted = true; values = emptyMap(); return true }
    }

    private fun target(dir: File, files: StoreFiles = DefaultStoreFiles, key: () -> ByteArray = { fixedKey() }) =
        EncryptedPrefs({ SecureStore(dir, "prefs", 1, key, files) }, directExecutor, directNotify)

    private val sample = mapOf<String, Any?>("s" to "ключ", "i" to 3, "l" to 4L, "f" to 0.5f, "b" to true, "set" to setOf("x", "y"))

    @Test fun plainPreferencesMoveIntoTheEncryptedStoreAndThePlainFileIsDeleted() {
        val dir = tempDir()
        val plain = FakePlain(sample)
        assertTrue(LegacyMigration.migratePrefs(target(dir), plain))
        assertTrue(plain.deleted)
        assertEquals(sample, target(dir).map()) // read back from disk by a new instance
        assertEquals(emptyList(), filesContaining(dir, "ключ"))
    }

    @Test fun nothingToMigrateTouchesNothing() {
        val dir = tempDir()
        val plain = FakePlain(emptyMap())
        assertTrue(LegacyMigration.migratePrefs(target(dir), plain))
        assertFalse(plain.deleted)
        assertFalse(File(dir, "prefs.enc").exists())
    }

    @Test fun interruptedEarlierRunJustDeletesThePlainFile() { // encrypted copy exists, plain file is a leftover
        val dir = tempDir()
        target(dir).edit().putString("kept", "encrypted").commit()
        val plain = FakePlain(mapOf("kept" to "plain", "other" to 1))
        assertTrue(LegacyMigration.migratePrefs(target(dir), plain))
        assertTrue(plain.deleted)
        assertEquals(mapOf("kept" to "encrypted"), target(dir).map()) // the encrypted data is authoritative, not overwritten
    }

    @Test fun lockedDeviceLeavesThePlainFileAlone() {
        val plain = FakePlain(sample)
        val locked = EncryptedPrefs({ null }, directExecutor, directNotify)
        assertFalse(LegacyMigration.migratePrefs(locked, plain))
        assertFalse(plain.deleted)
        assertEquals(sample, plain.values)
    }

    @Test fun failedEncryptedWriteKeepsThePlainFileAndLeavesNoEncryptedCopy() {
        val dir = tempDir()
        val failing = object : StoreFiles {
            override fun writeSynced(file: File, bytes: ByteArray) = throw IOException("disk full")
            override fun replace(from: File, to: File) = false
        }
        val plain = FakePlain(sample)
        assertFalse(LegacyMigration.migratePrefs(target(dir, failing), plain))
        assertFalse(plain.deleted)
        assertEquals(sample, plain.values)
        assertFalse(File(dir, "prefs.enc").exists())
    }

    @Test fun unknownValueTypeKeepsThePlainFile() {
        val plain = FakePlain(mapOf("ok" to 1, "weird" to 2.5))
        assertFalse(LegacyMigration.migratePrefs(target(tempDir()), plain))
        assertFalse(plain.deleted)
    }

    @Test fun retryAfterAFailureSucceeds() {
        val dir = tempDir()
        val plain = FakePlain(sample)
        val failing = object : StoreFiles {
            override fun writeSynced(file: File, bytes: ByteArray) = throw IOException("disk full")
            override fun replace(from: File, to: File) = false
        }
        assertFalse(LegacyMigration.migratePrefs(target(dir, failing), plain))
        assertTrue(LegacyMigration.migratePrefs(target(dir), plain))
        assertTrue(plain.deleted)
        assertEquals(sample, target(dir).map())
    }

    // ---- clipboard ---------------------------------------------------------------------------------------------

    @Test fun clipboardRowsAreMovedVerifiedAndOnlyThenDeleted() {
        val dir = tempDir()
        var deleted = false
        val rows = listOf(
            LegacyMigration.LegacyClip(5, true, "pinned ключ"), LegacyMigration.LegacyClip(9, false, "second"),
            LegacyMigration.LegacyClip(7, false, "second"), // duplicate text
            LegacyMigration.LegacyClip(8, false, ""), // empty
        )
        val entries = assertNotNull(LegacyMigration.migrateClipboard(testStore(dir, "clipboard"), rows) { deleted = true })
        assertTrue(deleted)
        assertEquals(listOf("pinned ключ", "second"), entries.map { it.text })
        assertEquals(listOf(true, false), entries.map { it.pinned })
        val stored = ClipboardCodec.decode((testStore(dir, "clipboard").read() as StoreContent.Data).bytes)
        assertEquals(listOf("pinned ключ", "second"), stored.map { it.text })
        assertEquals(emptyList(), filesContaining(dir, "second"))
    }

    @Test fun clipboardMigrationKeepsLegacyDataWhenTheWriteFails() {
        val failing = object : StoreFiles {
            override fun writeSynced(file: File, bytes: ByteArray) = throw IOException("disk full")
            override fun replace(from: File, to: File) = false
        }
        var deleted = false
        val result = LegacyMigration.migrateClipboard(testStore(tempDir(), "clipboard", files = failing), listOf(LegacyMigration.LegacyClip(1, false, "x"))) { deleted = true }
        assertNull(result)
        assertFalse(deleted)
    }

    @Test fun clipboardMigrationKeepsLegacyDataWhileLocked() {
        var deleted = false
        val locked = SecureStore(tempDir(), "clipboard", 1, { throw VaultLockedException("locked") })
        assertNull(LegacyMigration.migrateClipboard(locked, listOf(LegacyMigration.LegacyClip(1, false, "x"))) { deleted = true })
        assertFalse(deleted)
    }

    // ---- plain files (background image) ---------------------------------------------------------------------

    @Test fun plainFileIsEncryptedVerifiedAndDeleted() {
        val dir = tempDir()
        val plain = File(dir, "custom_background_image").also { it.writeBytes("IMGDATA-canary-Zq7".toByteArray()) }
        assertTrue(LegacyMigration.migrateFile(plain, testStore(dir, "bg")))
        assertFalse(plain.exists())
        assertEquals("IMGDATA-canary-Zq7", String(assertIs<StoreContent.Data>(testStore(dir, "bg").read()).bytes))
        assertEquals(emptyList(), filesContaining(dir, "IMGDATA-canary-Zq7"))
    }

    @Test fun plainFileStaysWhenTheEncryptedCopyCannotBeWritten() {
        val dir = tempDir()
        val plain = File(dir, "custom_background_image").also { it.writeBytes("img".toByteArray()) }
        val locked = SecureStore(dir, "bg", 1, { throw VaultLockedException("locked") })
        assertFalse(LegacyMigration.migrateFile(plain, locked))
        assertTrue(plain.exists())
        assertTrue(LegacyMigration.migrateFile(File(dir, "missing"), testStore(dir, "bg"))) // nothing to do counts as done
    }
}
