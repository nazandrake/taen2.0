// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.CharBuffer
import java.security.SecureRandom
import java.text.Normalizer

/** Salted Argon2id records of the duress PIN and the trigger word: parameters, limits and the way a record is made. */
object SecretRecords {
    /** Same parameters as the PIN: 8 MiB x 2 passes, about 240 ms on the reference phone. */
    val KDF = AppLock.PIN_KDF
    /** A stored record below the floor is refused; above the ceiling it is refused too, so a damaged record cannot cost memory or time. */
    const val MIN_MEMORY_KIB = 8192
    const val MIN_ITERATIONS = 2
    const val MAX_MEMORY_KIB = 131072 // 128 MiB
    const val MAX_ITERATIONS = 10
    const val MAX_LANES = 8
    const val SALT_SIZE = 16
    const val HASH_SIZE = 32

    /** Hashes the NFC form of [secret] with a new salt. The caller clears [secret]. */
    fun create(secret: CharArray, hasher: PinHasher, random: SecureRandom): SecretRecord {
        val salt = ByteArray(SALT_SIZE).also { random.nextBytes(it) }
        val normalized = TriggerWordRules.normalize(secret)
        try {
            return SecretRecord(salt, hasher.hash(normalized, salt), hasher.kdf)
        } finally {
            normalized.fill('\u0000')
        }
    }
}

/** Binary form of [SelfDestructConfig]. Decoding checks every field, so a damaged record can neither cost memory and time nor arm or weaken a trigger. */
object SelfDestructConfigCodec {
    private const val VERSION = 1

    fun encode(c: SelfDestructConfig): ByteArray = encodeRecords {
        writeInt(VERSION)
        writeRecord(c.duress)
        writeInt(c.duressMode.ordinal)
        writeRecord(c.word)
        writeInt(c.wordMode.ordinal)
        writeBoolean(c.commitOnSpace); writeBoolean(c.holdEnter); writeBoolean(c.checkInPasswordFields)
        writeInt(c.wrongPins); writeInt(c.inactivityDays)
        writeBoolean(c.scope.learned); writeBoolean(c.scope.clipboard); writeBoolean(c.scope.settings); writeBoolean(c.scope.config); writeBoolean(c.scope.imported)
        writeLong(c.rehearsalUntil)
    }

    fun decode(data: ByteArray): SelfDestructConfig = decodeRecords(data) {
        if (readInt() != VERSION) throw CorruptStoreException("unknown self-destruct version")
        val duress = readRecord()
        val duressMode = readMode()
        val word = readRecord()
        val wordMode = readMode()
        val commitOnSpace = readBoolean(); val holdEnter = readBoolean(); val checkInPasswordFields = readBoolean()
        val wrongPins = readInt(); val days = readInt()
        val scope = WipeScope(readBoolean(), readBoolean(), readBoolean(), readBoolean(), readBoolean())
        val rehearsalUntil = readLong()
        val config = SelfDestructConfig(duress, duressMode, word, wordMode, commitOnSpace, holdEnter, checkInPasswordFields, wrongPins, days, scope, rehearsalUntil)
        if (!isValid(config)) throw CorruptStoreException("bad self-destruct values")
        config
    }

    /** The value ranges: wrong PINs 0 (off) or 5..20, inactivity 0 (off) or 3..365 days, a rehearsal end that is not negative. */
    fun isValid(c: SelfDestructConfig): Boolean =
        (c.wrongPins == 0 || c.wrongPins in 5..20) && (c.inactivityDays == 0 || c.inactivityDays in 3..365) && c.rehearsalUntil >= 0

    private fun DataOutputStream.writeRecord(r: SecretRecord?) {
        if (r == null) { writeInt(0); return }
        writeInt(1)
        writeInt(r.salt.size); write(r.salt)
        writeInt(r.hash.size); write(r.hash)
        writeInt(r.kdf.memoryKiB); writeInt(r.kdf.iterations); writeInt(r.kdf.lanes)
    }

    private fun DataInputStream.readRecord(): SecretRecord? {
        when (readInt()) {
            0 -> return null
            1 -> Unit
            else -> throw CorruptStoreException("bad record flag")
        }
        val salt = readSized(SecretRecords.SALT_SIZE)
        val hash = readSized(SecretRecords.HASH_SIZE)
        val kdf = ExportCodec.Kdf(readInt(), readInt(), readInt())
        if (kdf.lanes !in 1..SecretRecords.MAX_LANES || kdf.iterations !in SecretRecords.MIN_ITERATIONS..SecretRecords.MAX_ITERATIONS ||
            kdf.memoryKiB !in maxOf(SecretRecords.MIN_MEMORY_KIB, 8 * kdf.lanes)..SecretRecords.MAX_MEMORY_KIB)
            throw CorruptStoreException("unsupported hash parameters")
        return SecretRecord(salt, hash, kdf)
    }

    private fun DataInputStream.readMode(): TriggerMode =
        TriggerMode.entries.getOrNull(readInt()) ?: throw CorruptStoreException("bad mode")

    private fun DataInputStream.readSized(size: Int): ByteArray {
        if (readInt() != size) throw CorruptStoreException("bad field size")
        return ByteArray(size).also { readFully(it) }
    }
}

sealed interface ConfigLoad {
    /** Nothing was saved: the defaults (everything off). */
    object None : ConfigLoad
    class Present(val config: SelfDestructConfig) : ConfigLoad
    /** Cannot be read now (store locked, unreadable record): no trigger fires on it and nothing overwrites it. */
    object Unavailable : ConfigLoad
}

interface ConfigStorage {
    fun load(): ConfigLoad
    /** False when it could not be written, or when [config] is not valid. */
    fun save(config: SelfDestructConfig): Boolean
}

/** The configuration in the encrypted `selfdestruct` store of the device-protected tier (readable on the lock screen, never exported). */
class StoreConfigStorage(private val store: () -> SecureStore?) : ConfigStorage {
    override fun load(): ConfigLoad {
        val s = store() ?: return ConfigLoad.Unavailable
        return when (val content = s.read()) {
            is StoreContent.Missing, is StoreContent.Reset -> ConfigLoad.None // a store that fails its tag is deleted by SecureStore: it goes with the data it guarded
            is StoreContent.Unavailable -> ConfigLoad.Unavailable
            is StoreContent.Data -> try {
                ConfigLoad.Present(SelfDestructConfigCodec.decode(content.bytes))
            } catch (_: CorruptStoreException) {
                ConfigLoad.Unavailable // authentic but unreadable (a newer version or a bug): fire nothing, delete nothing
            }
        }
    }

    override fun save(config: SelfDestructConfig): Boolean {
        if (!SelfDestructConfigCodec.isValid(config)) return false
        val s = store() ?: return false
        return try {
            s.write(SelfDestructConfigCodec.encode(config))
            true
        } catch (_: StoreWriteException) {
            false
        } catch (_: VaultLockedException) {
            false
        } catch (_: VaultKeyException) {
            false
        } catch (_: VaultUnavailableException) {
            false
        }
    }
}

/** When the keyboard was last used: the clock of the inactivity trigger. */
sealed interface ActivityState {
    object Missing : ActivityState
    class Present(val lastActive: Long) : ActivityState
    /** Authentic but not a time: counts as exhausted when the inactivity trigger is on. */
    object Corrupt : ActivityState
    object Unavailable : ActivityState
}

interface ActivityStorage {
    fun load(): ActivityState
    fun save(lastActive: Long): Boolean
}

/** The last-use time in the encrypted `activity` store (device-protected tier). */
class StoreActivityStorage(private val store: () -> SecureStore?) : ActivityStorage {
    override fun load(): ActivityState {
        val s = store() ?: return ActivityState.Unavailable
        return when (val content = s.read()) {
            is StoreContent.Missing, is StoreContent.Reset -> ActivityState.Missing
            is StoreContent.Unavailable -> ActivityState.Unavailable
            is StoreContent.Data -> {
                val t = content.bytes.takeIf { it.size == 8 }?.let { java.nio.ByteBuffer.wrap(it).long }
                if (t == null || t < 0) ActivityState.Corrupt else ActivityState.Present(t)
            }
        }
    }

    override fun save(lastActive: Long): Boolean {
        if (lastActive < 0) return false
        val s = store() ?: return false
        return try {
            s.write(java.nio.ByteBuffer.allocate(8).putLong(lastActive).array())
            true
        } catch (_: StoreWriteException) {
            false
        } catch (_: VaultLockedException) {
            false
        } catch (_: VaultKeyException) {
            false
        } catch (_: VaultUnavailableException) {
            false
        }
    }
}

/** The rules for a trigger word (false-trigger protection). Lengths are counted in the NFC form. */
object TriggerWordRules {
    enum class Result { OK, TOO_SHORT, TOO_LONG, WHITESPACE, SEPARATOR, NEEDS_DIGIT_OR_SYMBOL, KNOWN_WORD }

    /** A new array with the NFC form of [word]; the caller clears both. */
    fun normalize(word: CharArray): CharArray {
        val buffer = CharBuffer.wrap(word)
        if (Normalizer.isNormalized(buffer, Normalizer.Form.NFC)) return word.copyOf()
        return Normalizer.normalize(buffer, Normalizer.Form.NFC).toCharArray()
    }

    /**
     * True for a character the input logic handles as a separator (it ends the composed word there, can auto-correct what came before and
     * can add or swap a space): white space, the keyboard's word separators ([isWordSeparator], from its spacing and punctuation settings)
     * and symbols of the "other symbol" class (emoji). A word with one of them could be changed in the field before the commit key.
     */
    fun splitsInput(codePoint: Int, isWordSeparator: (Int) -> Boolean): Boolean =
        Character.isWhitespace(codePoint) || Character.getType(codePoint) == Character.OTHER_SYMBOL.toInt() || isWordSeparator(codePoint)

    /**
     * [isKnown] tells whether a candidate is in a loaded dictionary or the learned words, [isWordSeparator] whether the keyboard treats a
     * code point as a word separator (see [splitsInput]). [word] is not changed.
     */
    fun check(word: CharArray, isWordSeparator: (Int) -> Boolean, isKnown: (CharSequence) -> Boolean): Result {
        val w = normalize(word)
        try {
            if (w.size < TriggerMatcher.MIN_LENGTH) return Result.TOO_SHORT
            if (w.size > TriggerMatcher.MAX_LENGTH) return Result.TOO_LONG
            if (w.any { TriggerMatcher.isBreak(it) }) return Result.WHITESPACE
            var i = 0
            while (i < w.size) {
                val cp = Character.codePointAt(w, i)
                if (splitsInput(cp, isWordSeparator)) return Result.SEPARATOR
                i += Character.charCount(cp)
            }
            if (w.all { it.isLetter() }) return Result.NEEDS_DIGIT_OR_SYMBOL
            if (isKnown(String(w))) return Result.KNOWN_WORD
            return Result.OK
        } finally {
            w.fill('\u0000')
        }
    }
}
