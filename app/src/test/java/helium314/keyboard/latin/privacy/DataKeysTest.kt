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

class DataKeysTest {
    private val vault = FakeKeyVault()
    private val dir = tempDir()
    private fun keys(tier: Tier = Tier.CE, files: StoreFiles = DefaultStoreFiles) = DataKeys(vault, tier, File(dir, "dek_${tier.name}.bin"), files)

    @Test fun keyIsCreatedOnceAndReloadedFromTheWrappedFile() {
        val first = keys().key()
        assertEquals(32, first.size)
        assertEquals(1, vault.wrapCalls)
        assertContentEquals(first, keys().key()) // new instance reads the file
        assertEquals(1, vault.wrapCalls)
    }

    @Test fun keyFileDoesNotContainTheKey() {
        val key = keys().key()
        assertEquals(-1, File(dir, "dek_CE.bin").readBytes().indexOfSub(key))
    }

    @Test fun deletingTheVaultKeyMakesStoredDataUnreadable() { // P8
        val k = keys()
        val store = SecureStore(dir, "clipboard", 1, k::key)
        store.write("secret".toByteArray())
        assertIs<StoreContent.Data>(store.read())

        vault.deleteKey(Tier.CE)
        val fresh = keys() // a new process after the key was deleted
        val newKey = fresh.key()
        assertNotEquals(k.key().toList(), newKey.toList())
        val reopened = SecureStore(dir, "clipboard", 1, fresh::key)
        assertIs<StoreContent.Reset>(reopened.read()) // the tag check fails, the data is gone
        assertFalse(File(dir, "clipboard.enc").exists())
    }

    @Test fun tamperedKeyFileGivesANewKeyAndOldDataIsUnreadable() {
        val k = keys()
        val old = k.key()
        SecureStore(dir, "s", 1, k::key).write("secret".toByteArray())
        val file = File(dir, "dek_CE.bin")
        val bytes = file.readBytes()
        bytes[20] = (bytes[20].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        val fresh = keys()
        assertNotEquals(old.toList(), fresh.key().toList())
        assertIs<StoreContent.Reset>(SecureStore(dir, "s", 1, fresh::key).read())
    }

    @Test fun lockedDeviceChangesNothing() {
        val before = keys().key()
        val file = File(dir, "dek_CE.bin")
        val bytes = file.readBytes()
        vault.locked = true
        assertFailsWith<VaultLockedException> { keys().key() }
        assertContentEquals(bytes, file.readBytes()) // no reset because of a lock
        vault.locked = false
        assertContentEquals(before, keys().key())
    }

    @Test fun lockedDeviceDoesNotCreateAKey() {
        vault.locked = true
        assertFailsWith<VaultLockedException> { keys().key() }
        assertFalse(File(dir, "dek_CE.bin").exists())
        assertFalse(File(dir, "dek_CE.bin.tmp").exists())
    }

    @Test fun deTierWorksWhileLocked() {
        vault.locked = true
        assertEquals(32, keys(Tier.DE).key().size)
    }

    @Test fun evictedKeyIsUnwrappedAgain() {
        val k = keys()
        val key = k.key().copyOf()
        k.evict()
        val before = vault.unwrapCalls
        assertContentEquals(key, k.key())
        assertEquals(before + 1, vault.unwrapCalls)
    }

    // ---- a handed-out key is the caller's copy; evict must never zero it, a caller may zero it freely ----------

    @Test fun keyIsACopyThatEvictDoesNotZero() {
        val k = keys()
        val handedOut = k.key()
        val expected = handedOut.copyOf()
        assertFalse(expected.all { it.toInt() == 0 }) // positive control: a real key
        k.evict()
        assertContentEquals(expected, handedOut) // the eviction wiped the cache, not the array a caller still holds
    }

    @Test fun zeroingAHandedOutKeyDoesNotChangeTheCachedOne() {
        val k = keys()
        val expected = k.key().copyOf()
        k.key().fill(0)
        assertContentEquals(expected, k.key())
        val created = keys() // also the copy of a freshly created and of a freshly loaded key
        val first = created.key()
        first.fill(0)
        assertContentEquals(expected, created.key())
    }

    @Test fun failedKeyFileWriteLeavesNoKeyAndCanBeRetried() {
        val failing = object : StoreFiles {
            override fun writeSynced(file: File, bytes: ByteArray) = throw IOException("disk full")
            override fun replace(from: File, to: File) = false
        }
        assertFailsWith<StoreWriteException> { keys(files = failing).key() }
        assertFalse(File(dir, "dek_CE.bin").exists())
        assertFalse(File(dir, "dek_CE.bin.tmp").exists())
        assertEquals(32, keys().key().size)
        assertTrue(File(dir, "dek_CE.bin").exists())
    }

    @Test fun tiersHaveIndependentKeys() {
        assertNotEquals(keys(Tier.CE).key().toList(), keys(Tier.DE).key().toList())
    }

    @Test fun lockedUnwrapNeverReplacesTheKeyFile() { // even if a vault could wrap while it cannot unwrap
        val k = keys()
        val key = k.key().copyOf()
        val file = File(dir, "dek_CE.bin")
        val bytes = file.readBytes()
        val unwrapLocked = object : KeyVault by vault {
            override fun unwrap(tier: Tier, wrapped: ByteArray): ByteArray = throw VaultLockedException("locked")
        }
        assertFailsWith<VaultLockedException> { DataKeys(unwrapLocked, Tier.CE, file).key() }
        assertContentEquals(bytes, file.readBytes())
        assertContentEquals(key, keys().key()) // the old key still works after the lock
    }

    @Test fun wrappedValueOfTheWrongSizeIsNotUsedAsAKey() {
        val file = File(dir, "dek_CE.bin")
        file.writeBytes(vault.wrap(Tier.CE, ByteArray(16) { 1 })) // authentic, but not a 256-bit key
        val key = keys().key()
        assertEquals(32, key.size)
        assertFalse(key.all { it.toInt() == 1 })
    }

    // ---- only a permanent loss of the key may start a new one ----------------------------

    @Test fun transientVaultFailureNeitherReplacesTheKeyNorLosesTheData() {
        val k = keys()
        val key = k.key().copyOf()
        val store = SecureStore(dir, "clipboard", 1, k::key)
        store.write("secret".toByteArray())
        val file = File(dir, "dek_CE.bin")
        val bytes = file.readBytes()

        vault.unwrapError = VaultUnavailableException("keystore service died")
        assertFailsWith<VaultUnavailableException> { keys().key() } // a new process while the Keystore hiccups
        assertContentEquals(bytes, file.readBytes()) // the wrapped key is untouched, no new key was made
        assertEquals(1, vault.wrapCalls)
        val failing = keys()
        assertIs<StoreContent.Unavailable>(SecureStore(dir, "clipboard", 1, failing::key).read()) // not deleted, not reset
        assertTrue(File(dir, "clipboard.enc").exists())

        vault.unwrapError = null // the Keystore is back
        assertContentEquals(key, failing.key()) // the same key as before
        assertEquals("secret", String(assertIs<StoreContent.Data>(SecureStore(dir, "clipboard", 1, failing::key).read()).bytes))
    }

    @Test fun unreadableKeyFileIsTransientAndKeepsTheFile() {
        val key = keys().key().copyOf()
        val file = File(dir, "dek_CE.bin")
        val bytes = file.readBytes()
        val unreadable = object : StoreFiles {
            override fun writeSynced(file: File, bytes: ByteArray) = DefaultStoreFiles.writeSynced(file, bytes)
            override fun replace(from: File, to: File) = DefaultStoreFiles.replace(from, to)
            override fun read(file: File): ByteArray = throw IOException("I/O error")
        }
        assertFailsWith<VaultUnavailableException> { keys(files = unreadable).key() }
        assertContentEquals(bytes, file.readBytes())
        assertContentEquals(key, keys().key())
    }

    @Test fun permanentLossOfTheVaultKeyStillStartsANewKey() { // the one case that may reset (unchanged behaviour)
        val key = keys().key().copyOf()
        vault.failNextUnwrap = VaultKeyException("key invalidated")
        val fresh = keys().key()
        assertNotEquals(key.toList(), fresh.toList())
        assertContentEquals(fresh, keys().key())
    }

    @Test fun permanentLossDeletesTheOldVaultKeyBeforeANewOneIsMade() { // an invalidated Keystore key can never wrap again
        keys().key()
        val deleted = ArrayList<Tier>()
        val counting = object : KeyVault by vault {
            override fun deleteKey(tier: Tier) { deleted.add(tier); vault.deleteKey(tier) }
        }
        vault.failNextUnwrap = VaultKeyException("key invalidated")
        DataKeys(counting, Tier.CE, File(dir, "dek_CE.bin")).key()
        assertEquals(listOf(Tier.CE), deleted)
        deleted.clear()
        DataKeys(counting, Tier.CE, File(dir, "dek_CE.bin")).key() // control: a healthy key is not deleted
        assertEquals(emptyList(), deleted)
    }

    @Test fun transientFailureWhileCreatingTheFirstKeyLeavesNothing() {
        vault.wrapError = VaultUnavailableException("keystore service died")
        assertFailsWith<StoreWriteException> { keys().key() }
        assertFalse(File(dir, "dek_CE.bin").exists())
        vault.wrapError = null
        assertEquals(32, keys().key().size)
    }

    // ---- a closed gate stops every use of the key (screen lock) -----------------------------

    @Test fun closedGateThrowsLockedEvenForACachedKey() {
        var open = true
        val k = DataKeys(vault, Tier.CE, File(dir, "dek_CE.bin"), allowed = { open })
        val key = k.key().copyOf()
        open = false
        assertFailsWith<VaultLockedException> { k.key() } // the cached key is not handed out
        val before = vault.unwrapCalls
        open = true
        assertContentEquals(key, k.key())
        assertEquals(before, vault.unwrapCalls) // the cache is still there when only the gate was closed
        k.evict()
        open = false
        assertFailsWith<VaultLockedException> { k.key() }
        assertEquals(before, vault.unwrapCalls) // a closed gate does not even ask the vault
    }

    // ---- while the device is locked, any failure of the credential-encrypted key means "locked, try later" ----------------------

    private fun lockedKeys(locked: () -> Boolean, v: KeyVault = vault) = DataKeys(v, Tier.CE, File(dir, "dek_CE.bin"), deviceLocked = locked)

    @Test fun aKeyErrorWhileTheDeviceIsLockedIsLockedAndReplacesNothing() {
        val key = keys().key().copyOf()
        val file = File(dir, "dek_CE.bin")
        val bytes = file.readBytes()
        // a platform that reports the refused key of a locked device as a lost one
        vault.failNextUnwrap = VaultKeyException("key lost or wrapped key does not authenticate")
        assertFailsWith<VaultLockedException> { lockedKeys({ true }).key() }
        assertContentEquals(bytes, file.readBytes(), "the wrapped key was not replaced")
        assertContentEquals(key, keys().key(), "the same key works after the unlock")
    }

    // ---- pass 2 ----

    /** A vault that counts deleted keys and throws [error] at every unwrap while it is set. */
    private class FailingVault(private val inner: FakeKeyVault) : KeyVault by inner {
        var error: Exception? = null
        val deleted = ArrayList<Tier>()
        override fun unwrap(tier: Tier, wrapped: ByteArray): ByteArray = error?.let { throw it } ?: inner.unwrap(tier, wrapped)
        override fun deleteKey(tier: Tier) { deleted.add(tier); inner.deleteKey(tier) }
    }

    @Test fun everyKindOfFailureWhileLockedIsLockedAndNothingIsDeletedOrCreated() {
        val key = keys().key().copyOf()
        val store = SecureStore(dir, "clipboard", 1, keys()::key)
        store.write("secret".toByteArray())
        val file = File(dir, "dek_CE.bin")
        val bytes = file.readBytes()
        val failing = FailingVault(vault)
        for (error in listOf<Exception>(VaultKeyException("lost"), VaultUnavailableException("busy"), IllegalStateException("odd state"),
            java.security.ProviderException("keystore daemon"), StoreWriteException("odd"))) {
            failing.error = error
            assertFailsWith<VaultLockedException>(error.toString()) { lockedKeys({ true }, failing).key() }
            // a store read in this state is unavailable, never reset or deleted
            assertIs<StoreContent.Unavailable>(SecureStore(dir, "clipboard", 1, lockedKeys({ true }, failing)::key).read())
        }
        assertEquals(emptyList(), failing.deleted, "no vault key was deleted")
        assertContentEquals(bytes, file.readBytes(), "the wrapped key was not replaced")
        assertEquals(1, vault.wrapCalls, "no new key was wrapped")
        assertEquals("secret", String(assertIs<StoreContent.Data>(SecureStore(dir, "clipboard", 1, keys()::key).read()).bytes))
        assertContentEquals(key, keys().key())
        // positive control: the same lost key on an unlocked device is replaced (the one case that may reset)
        failing.error = null
        vault.failNextUnwrap = VaultKeyException("lost")
        val fresh = lockedKeys({ false }).key()
        assertNotEquals(key.toList(), fresh.toList())
    }

    @Test fun anUnreadableKeyFileWhileLockedIsLocked() {
        keys().key()
        val unreadable = object : StoreFiles {
            override fun writeSynced(file: File, bytes: ByteArray) = DefaultStoreFiles.writeSynced(file, bytes)
            override fun replace(from: File, to: File) = DefaultStoreFiles.replace(from, to)
            override fun read(file: File): ByteArray = throw IOException("I/O error")
        }
        assertFailsWith<VaultLockedException> { DataKeys(vault, Tier.CE, File(dir, "dek_CE.bin"), unreadable, deviceLocked = { true }).key() }
        // control: unlocked, the same error is the transient one it always was
        assertFailsWith<VaultUnavailableException> { DataKeys(vault, Tier.CE, File(dir, "dek_CE.bin"), unreadable, deviceLocked = { false }).key() }
    }

    @Test fun aLockOrAnUnlockDuringTheCallCountsAsLocked() {
        val key = keys().key().copyOf()
        val bytes = File(dir, "dek_CE.bin").readBytes()
        val failing = FailingVault(vault).apply { error = VaultKeyException("lost") }
        for (states in listOf(listOf(false, true), listOf(true, false))) { // locked only after the failure, locked only before it
            val answers = ArrayDeque(states)
            assertFailsWith<VaultLockedException>(states.toString()) { lockedKeys({ answers.removeFirstOrNull() ?: states.last() }, failing).key() }
        }
        assertEquals(emptyList(), failing.deleted)
        assertContentEquals(bytes, File(dir, "dek_CE.bin").readBytes())
        assertContentEquals(key, keys().key())
    }

    @Test fun noFirstKeyIsMadeWhileLocked() {
        assertFailsWith<VaultLockedException> { lockedKeys({ true }).key() }
        assertFalse(File(dir, "dek_CE.bin").exists())
        assertEquals(0, vault.wrapCalls)
        assertEquals(32, lockedKeys({ false }).key().size) // positive control: after the unlock the first key is made
        assertEquals(1, vault.wrapCalls)
    }

    @Test fun aKeyThatWorksWhileLockedIsStillHandedOut() { // only failures change: a platform that lets the key work is not blocked
        val key = keys().key().copyOf()
        assertContentEquals(key, lockedKeys({ true }).key())
    }
}
