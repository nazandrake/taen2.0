// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The lock record in a real SecureStore: encrypted at rest, and every storage state maps to the right answer (fail closed). */
class StoreLockStorageTest {
    private val record = LockRecord(ByteArray(16) { it.toByte() }, ByteArray(32) { (100 + it).toByte() }, ExportCodec.Kdf(64, 1, 1), 5, 2, 12345L)

    @Test fun aSavedRecordComesBackAndIsNotReadableInTheFile() {
        val dir = tempDir()
        val storage = StoreLockStorage { testStore(dir, "security") }
        assertEquals(LockLoad.None, storage.load()) // nothing stored yet
        assertTrue(storage.save(record))
        val loaded = assertIs<LockLoad.Present>(storage.load()).record
        assertContentEquals(record.salt, loaded.salt)
        assertContentEquals(record.hash, loaded.hash)
        assertEquals(5, loaded.timeoutMinutes)
        assertEquals(2, loaded.failedAttempts)
        assertEquals(12345L, loaded.lockedUntil)
        val file = File(dir, "security.enc").readBytes()
        assertEquals(-1, file.indexOfSub(record.hash)) // the hash is not in the file in the clear
        assertEquals(-1, file.indexOfSub(record.salt))
    }

    @Test fun removingTheLockDeletesTheFile() {
        val dir = tempDir()
        val storage = StoreLockStorage { testStore(dir, "security") }
        storage.save(record)
        assertTrue(File(dir, "security.enc").exists())
        assertTrue(storage.save(null))
        assertFalse(File(dir, "security.enc").exists())
        assertEquals(LockLoad.None, storage.load())
    }

    @Test fun anUnavailableStoreIsUnavailableNotNone() { // a locked lock must not look like "no lock"
        assertEquals(LockLoad.Unavailable, StoreLockStorage { null }.load())
        assertFalse(StoreLockStorage { null }.save(record))
        // a key that cannot be read (store exists, key function throws): unavailable, and the file stays
        val dir = tempDir()
        StoreLockStorage { testStore(dir, "security") }.save(record)
        val broken = StoreLockStorage { SecureStore(dir, "security", 1, { throw VaultLockedException("locked") }) }
        assertEquals(LockLoad.Unavailable, broken.load())
        assertTrue(File(dir, "security.enc").exists())
        assertFalse(broken.save(record)) // a write through a locked key fails and says so
    }

    @Test fun aDamagedFileIsResetLikeEveryOtherStoreAndNoLockIsLeft() {
        val dir = tempDir()
        StoreLockStorage { testStore(dir, "security") }.save(record)
        val file = File(dir, "security.enc")
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte() // flip a bit of the tag
        file.writeBytes(bytes)
        assertEquals(LockLoad.None, StoreLockStorage { testStore(dir, "security") }.load()) // documented in PRIVACY.md
        assertFalse(file.exists()) // and the damaged file is gone, as SecureStore does for every store
    }

    @Test fun anAuthenticContentThatIsNotARecordStaysClosed() { // fail closed: a newer version or a bug must not remove the lock
        val dir = tempDir()
        testStore(dir, "security").write(byteArrayOf(0, 0, 0, 9, 1, 2, 3))
        val storage = StoreLockStorage { testStore(dir, "security") }
        // a record that cannot be decoded is Corrupt, not Unavailable, so that the self-destruct can count a corrupt attempt counter as
        // exhausted (design A5); it is as closed as before (an AppLock treats it like Unavailable everywhere else) and nothing is deleted
        assertEquals(LockLoad.Corrupt, storage.load())
        assertTrue(File(dir, "security.enc").exists()) // nothing deleted
        assertNull((storage.load() as? LockLoad.Present))
    }
}
