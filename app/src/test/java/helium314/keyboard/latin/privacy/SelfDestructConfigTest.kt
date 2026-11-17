// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryConfigStorage : ConfigStorage {
    var content: ConfigLoad = ConfigLoad.None
    var failWrites = false
    var writes = 0
    val config: SelfDestructConfig? get() = (content as? ConfigLoad.Present)?.config
    override fun load(): ConfigLoad = content
    override fun save(config: SelfDestructConfig): Boolean {
        if (failWrites) return false
        writes++
        content = ConfigLoad.Present(config)
        return true
    }
}

class MemoryActivityStorage : ActivityStorage {
    var content: ActivityState = ActivityState.Missing
    var writes = 0
    var failWrites = false
    override fun load(): ActivityState = content
    override fun save(lastActive: Long): Boolean {
        if (failWrites) return false
        writes++
        content = ActivityState.Present(lastActive)
        return true
    }
}

/** The self-destruct configuration: defaults, its stored form, and the rules that keep a damaged one from weakening or arming anything. */
class SelfDestructConfigTest {
    private val kdf = SecretRecords.KDF
    private fun secret(seed: Int, salt: Int = seed) = SecretRecord(ByteArray(16) { (salt + it).toByte() }, ByteArray(32) { (seed * 3 + it).toByte() }, kdf)

    private val full = SelfDestructConfig(
        duress = secret(1), duressMode = TriggerMode.CONFIRM,
        word = secret(2), wordMode = TriggerMode.SILENT,
        commitOnSpace = true, holdEnter = false, checkInPasswordFields = true,
        wrongPins = 7, inactivityDays = 45,
        scope = WipeScope(learned = true, clipboard = false, settings = true, config = false, imported = true),
        rehearsalUntil = 1_700_000_123_456L,
    )

    private fun same(a: SecretRecord?, b: SecretRecord?) =
        (a == null && b == null) || (a != null && b != null && a.salt.contentEquals(b.salt) && a.hash.contentEquals(b.hash) && a.kdf == b.kdf)

    private fun assertSame(a: SelfDestructConfig, b: SelfDestructConfig) {
        assertTrue(same(a.duress, b.duress)); assertTrue(same(a.word, b.word))
        assertEquals(a.copy(duress = null, word = null), b.copy(duress = null, word = null))
    }

    @Test fun everythingIsOffByDefaultAndTheScopeIsEverything() {
        val d = SelfDestructConfig.DEFAULT
        assertNull(d.duress); assertNull(d.word)
        assertEquals(0, d.wrongPins); assertEquals(0, d.inactivityDays); assertEquals(0L, d.rehearsalUntil)
        assertEquals(TriggerMode.SILENT, d.duressMode); assertEquals(TriggerMode.SILENT, d.wordMode)
        assertTrue(d.holdEnter); assertFalse(d.commitOnSpace); assertFalse(d.checkInPasswordFields)
        assertEquals(WipeScope(true, true, true, true, true), d.scope)
        assertTrue(d.scope.shreds)
    }

    @Test fun shreddingNeedsEveryUserStoreButNotTheConfiguration() {
        assertTrue(WipeScope.ALL.shreds)
        assertTrue(WipeScope(config = false).shreds) // keep armed: still a crypto-shred, the configuration is written back
        assertFalse(WipeScope(learned = false).shreds)
        assertFalse(WipeScope(clipboard = false).shreds)
        assertFalse(WipeScope(settings = false).shreds)
        assertFalse(WipeScope(imported = false).shreds)
    }

    @Test fun everyFieldSurvivesTheStoredForm() {
        val decoded = SelfDestructConfigCodec.decode(SelfDestructConfigCodec.encode(full))
        assertSame(full, decoded)
        assertSame(SelfDestructConfig.DEFAULT, SelfDestructConfigCodec.decode(SelfDestructConfigCodec.encode(SelfDestructConfig.DEFAULT)))
    }

    private fun encodeRaw(version: Int = 1, mutate: DataOutputStream.() -> Unit = {}): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply { writeInt(version); mutate() }
        return out.toByteArray()
    }

    @Test fun aDamagedRecordIsRejectedWhateverTheDamage() {
        val good = SelfDestructConfigCodec.encode(full)
        assertFailsWith<CorruptStoreException> { SelfDestructConfigCodec.decode(encodeRaw(version = 2)) }
        assertFailsWith<CorruptStoreException> { SelfDestructConfigCodec.decode(good.copyOf(good.size - 1)) } // truncated
        assertFailsWith<CorruptStoreException> { SelfDestructConfigCodec.decode(good + byteArrayOf(0)) } // trailing byte
        assertFailsWith<CorruptStoreException> { SelfDestructConfigCodec.decode(ByteArray(0)) }
        for (bad in listOf(
            full.copy(wrongPins = 4), full.copy(wrongPins = 21), full.copy(wrongPins = -1),
            full.copy(inactivityDays = 2), full.copy(inactivityDays = 366), full.copy(inactivityDays = -5),
            full.copy(rehearsalUntil = -1),
        )) assertFailsWith<CorruptStoreException>("$bad") { SelfDestructConfigCodec.decode(SelfDestructConfigCodec.encode(bad)) }
        // the boundaries that are valid
        for (ok in listOf(full.copy(wrongPins = 5), full.copy(wrongPins = 20), full.copy(wrongPins = 0),
            full.copy(inactivityDays = 3), full.copy(inactivityDays = 365), full.copy(inactivityDays = 0)))
            assertSame(ok, SelfDestructConfigCodec.decode(SelfDestructConfigCodec.encode(ok)))
    }

    @Test fun aSecretRecordBelowTheArgonFloorOrAboveTheCeilingIsRejected() {
        fun withKdf(k: ExportCodec.Kdf) = full.copy(duress = SecretRecord(ByteArray(16), ByteArray(32), k))
        fun decode(c: SelfDestructConfig) = SelfDestructConfigCodec.decode(SelfDestructConfigCodec.encode(c))
        decode(withKdf(ExportCodec.Kdf(SecretRecords.MIN_MEMORY_KIB, SecretRecords.MIN_ITERATIONS, 1))) // the floor itself is fine
        assertFailsWith<CorruptStoreException> { decode(withKdf(ExportCodec.Kdf(SecretRecords.MIN_MEMORY_KIB - 1, SecretRecords.MIN_ITERATIONS, 1))) }
        assertFailsWith<CorruptStoreException> { decode(withKdf(ExportCodec.Kdf(SecretRecords.MIN_MEMORY_KIB, SecretRecords.MIN_ITERATIONS - 1, 1))) }
        assertFailsWith<CorruptStoreException> { decode(withKdf(ExportCodec.Kdf(SecretRecords.MAX_MEMORY_KIB + 1, 2, 1))) } // a damaged record cannot cost 1 GiB
        assertFailsWith<CorruptStoreException> { decode(withKdf(ExportCodec.Kdf(8192, SecretRecords.MAX_ITERATIONS + 1, 1))) }
        assertFailsWith<CorruptStoreException> { decode(withKdf(ExportCodec.Kdf(8192, 2, 0))) }
        assertFailsWith<CorruptStoreException> { decode(withKdf(ExportCodec.Kdf(8192, 2, SecretRecords.MAX_LANES + 1))) }
    }

    @Test fun theArgonFloorAndCeilingAreTheMeasuredValues() {
        assertEquals(8192, SecretRecords.MIN_MEMORY_KIB) // 8 MiB: the PIN parameters measured on the reference phone
        assertEquals(2, SecretRecords.MIN_ITERATIONS)
        assertEquals(131072, SecretRecords.MAX_MEMORY_KIB) // 128 MiB
        fun decode(memory: Int) = SelfDestructConfigCodec.decode(SelfDestructConfigCodec.encode(full.copy(duress = SecretRecord(ByteArray(16), ByteArray(32), ExportCodec.Kdf(memory, 2, 1)))))
        assertFailsWith<CorruptStoreException> { decode(8191) }
        decode(8192)
        decode(131072)
        assertFailsWith<CorruptStoreException> { decode(131073) }
    }

    @Test fun aBadSaltOrHashSizeOrModeIsRejected() {
        fun raw(saltSize: Int, hashSize: Int, mode: Int): ByteArray = encodeRaw {
            writeInt(1) // duress present
            writeInt(saltSize); write(ByteArray(saltSize))
            writeInt(hashSize); write(ByteArray(hashSize))
            writeInt(8192); writeInt(2); writeInt(1)
            writeInt(mode)
            writeInt(0) // no word
            writeInt(0) // word mode
            writeBoolean(false); writeBoolean(true); writeBoolean(false)
            writeInt(0); writeInt(0)
            writeBoolean(true); writeBoolean(true); writeBoolean(true); writeBoolean(true); writeBoolean(true)
            writeLong(0)
        }
        assertNotNull(SelfDestructConfigCodec.decode(raw(16, 32, 0)).duress)
        assertFailsWith<CorruptStoreException> { SelfDestructConfigCodec.decode(raw(15, 32, 0)) }
        assertFailsWith<CorruptStoreException> { SelfDestructConfigCodec.decode(raw(16, 31, 0)) }
        assertFailsWith<CorruptStoreException> { SelfDestructConfigCodec.decode(raw(16, 32, 2)) } // unknown mode
        assertFailsWith<CorruptStoreException> { SelfDestructConfigCodec.decode(raw(16, 32, -1)) }
    }

    // ---- the stored file --------------------------------------------------------------------------------------------

    @Test fun theConfigurationLivesInAnEncryptedStoreWithoutAnyPlainSecret() {
        val dir = File(java.nio.file.Files.createTempDirectory("sdc").toString())
        val storage = StoreConfigStorage { testStore(dir, "selfdestruct") }
        assertEquals(ConfigLoad.None, storage.load())
        val word = "Blue#Horse-4711"
        val hasher = PinHasher(SecretRecords.KDF)
        val config = full.copy(word = SecretRecords.create(word.toCharArray(), hasher, SecureRandom()))
        assertTrue(storage.save(config))
        val loaded = assertIs<ConfigLoad.Present>(storage.load())
        assertSame(config, loaded.config)
        assertEquals(emptyList(), filesContaining(dir, word)) // positive control follows
        File(dir, "control.txt").writeText(word)
        assertEquals(listOf("control.txt"), filesContaining(dir, word))
        dir.deleteRecursively()
    }

    @Test fun unreadableStatesAreNeverAnArmedOrAnEmptyConfiguration() {
        assertEquals(ConfigLoad.Unavailable, StoreConfigStorage { null }.load())
        assertFalse(StoreConfigStorage { null }.save(full))
        val dir = File(java.nio.file.Files.createTempDirectory("sdc2").toString())
        // authentic content that is not a configuration (a newer version or a bug): unavailable, nothing deleted
        testStore(dir, "selfdestruct").write(byteArrayOf(0, 0, 0, 9, 1, 2, 3))
        val storage = StoreConfigStorage { testStore(dir, "selfdestruct") }
        assertEquals(ConfigLoad.Unavailable, storage.load())
        assertTrue(File(dir, "selfdestruct.enc").exists())
        // a locked key: unavailable, the file stays
        val locked = StoreConfigStorage { SecureStore(dir, "selfdestruct", 1, { throw VaultLockedException("locked") }) }
        assertEquals(ConfigLoad.Unavailable, locked.load())
        assertFalse(locked.save(full))
        assertTrue(File(dir, "selfdestruct.enc").exists())
        // a damaged file (tag mismatch) is reset like every store: the configuration is gone, nothing is armed
        val bytes = File(dir, "selfdestruct.enc").readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        File(dir, "selfdestruct.enc").writeBytes(bytes)
        assertEquals(ConfigLoad.None, storage.load())
        assertFalse(File(dir, "selfdestruct.enc").exists())
        dir.deleteRecursively()
    }

    @Test fun anInvalidConfigurationIsNotSaved() {
        val dir = File(java.nio.file.Files.createTempDirectory("sdc3").toString())
        val storage = StoreConfigStorage { testStore(dir, "selfdestruct") }
        assertFalse(storage.save(full.copy(wrongPins = 3)))
        assertFalse(storage.save(full.copy(inactivityDays = 1)))
        assertFalse(File(dir, "selfdestruct.enc").exists())
        assertTrue(storage.save(full.copy(wrongPins = 5, inactivityDays = 3))) // control: the boundary values are accepted
        dir.deleteRecursively()
    }

    @Test fun theActivityTimeIsAnEncryptedValueAndADamagedOneIsCorrupt() {
        val dir = File(java.nio.file.Files.createTempDirectory("sda").toString())
        val storage = StoreActivityStorage { testStore(dir, "activity") }
        assertEquals(ActivityState.Missing, storage.load())
        assertTrue(storage.save(1_700_000_000_000L))
        assertEquals(1_700_000_000_000L, assertIs<ActivityState.Present>(storage.load()).lastActive)
        testStore(dir, "activity").write(byteArrayOf(1, 2, 3)) // authentic but not a time
        assertEquals(ActivityState.Corrupt, storage.load())
        assertTrue(File(dir, "activity.enc").exists())
        testStore(dir, "activity").write(ByteArray(8) { -1 }) // a negative time
        assertEquals(ActivityState.Corrupt, storage.load())
        assertEquals(ActivityState.Unavailable, StoreActivityStorage { null }.load())
        assertFalse(StoreActivityStorage { null }.save(1L))
        dir.deleteRecursively()
    }
}
