// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.os.Looper
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * What is stored about the app lock: never the PIN, only its Argon2id hash with the parameters it was made with, the
 * lock timeout, and the failed-attempt state (the count and the time before which no attempt is judged).
 */
class LockRecord(
    val salt: ByteArray,
    val hash: ByteArray,
    val kdf: ExportCodec.Kdf,
    val timeoutMinutes: Int,
    val failedAttempts: Int,
    val lockedUntil: Long,
)

sealed interface LockLoad {
    /** No lock is set. */
    object None : LockLoad
    class Present(val record: LockRecord) : LockLoad
    /** The lock cannot be read now (store locked, transient error): treated as locked, never as "no lock". */
    object Unavailable : LockLoad
    /** The record is authentic but cannot be decoded (a newer version or a bug): closed, nothing deleted, and a corrupt attempt counter counts as exhausted for the self-destruct. */
    object Corrupt : LockLoad
}

interface LockStorage {
    fun load(): LockLoad
    /** Stores [record], or removes the lock when it is null. False when it could not be written. */
    fun save(record: LockRecord?): Boolean
}

/** Binary form of [LockRecord]. Decoding checks every field, so a damaged record can neither cost memory and time nor weaken the lock. */
object LockRecordCodec {
    private const val VERSION = 1
    private const val SALT_SIZE = 16
    private const val HASH_SIZE = 32
    private const val MAX_MEMORY_KIB = 131072 // 128 MiB
    private const val MAX_ITERATIONS = 10
    private const val MAX_LANES = 8

    fun encode(r: LockRecord): ByteArray = encodeRecords {
        writeInt(VERSION)
        writeInt(r.salt.size); write(r.salt)
        writeInt(r.hash.size); write(r.hash)
        writeInt(r.kdf.memoryKiB); writeInt(r.kdf.iterations); writeInt(r.kdf.lanes)
        writeInt(r.timeoutMinutes)
        writeInt(r.failedAttempts)
        writeLong(r.lockedUntil)
    }

    fun decode(data: ByteArray): LockRecord = decodeRecords(data) {
        if (readInt() != VERSION) throw CorruptStoreException("unknown lock record version")
        val salt = readSized(SALT_SIZE)
        val hash = readSized(HASH_SIZE)
        val kdf = ExportCodec.Kdf(readInt(), readInt(), readInt())
        val timeout = readInt()
        val failed = readInt()
        val until = readLong()
        if (kdf.lanes !in 1..MAX_LANES || kdf.iterations !in 1..MAX_ITERATIONS || kdf.memoryKiB !in 8 * kdf.lanes..MAX_MEMORY_KIB)
            throw CorruptStoreException("unsupported lock parameters")
        if (timeout !in AppLock.TIMEOUT_CHOICES_MINUTES || failed < 0 || until < 0) throw CorruptStoreException("bad lock record")
        LockRecord(salt, hash, kdf, timeout, failed, until)
    }

    private fun java.io.DataInputStream.readSized(size: Int): ByteArray {
        if (readInt() != size) throw CorruptStoreException("bad field size")
        return ByteArray(size).also { readFully(it) }
    }
}

/** The lock record in the encrypted `security` store of the device-protected tier (readable on the lock screen, never exported). */
class StoreLockStorage(private val store: () -> SecureStore?) : LockStorage {
    override fun load(): LockLoad {
        val s = store() ?: return LockLoad.Unavailable
        return when (val content = s.read()) {
            is StoreContent.Missing -> LockLoad.None
            // a store that fails its integrity check or whose key is lost is deleted by SecureStore, together with every other
            // store under that key: the lock goes with the data it protected (PRIVACY.md)
            is StoreContent.Reset -> LockLoad.None
            is StoreContent.Unavailable -> LockLoad.Unavailable
            is StoreContent.Data -> try {
                LockLoad.Present(LockRecordCodec.decode(content.bytes))
            } catch (_: CorruptStoreException) {
                LockLoad.Corrupt // authentic but unreadable (a newer version or a bug): stay closed, delete nothing
            }
        }
    }

    override fun save(record: LockRecord?): Boolean {
        val s = store() ?: return false
        return try {
            if (record == null) {
                s.delete()
                !s.exists()
            } else {
                s.write(LockRecordCodec.encode(record))
                true
            }
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

/** Argon2id hash of a PIN (Bouncy Castle, the library of the export). */
open class PinHasher(val kdf: ExportCodec.Kdf) {
    open fun hash(pin: CharArray, salt: ByteArray, params: ExportCodec.Kdf = kdf): ByteArray {
        // no String of the PIN or the word is made: the bytes come straight from the characters and are zeroed after the hash
        return SecretBytes.withUtf8(pin) { bytes ->
            val argon = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(params.iterations).withMemoryAsKB(params.memoryKiB).withParallelism(params.lanes).withSalt(salt).build()
            val generator = Argon2BytesGenerator()
            generator.init(argon)
            ByteArray(32).also { generator.generateBytes(bytes, it) }
        }
    }
}

/**
 * App lock (design 7.4 item 7): a PIN in front of the settings and the clipboard history. The PIN is 4 to 16 digits. A wrong
 * attempt is counted and stored before the PIN is compared (so ending the process cannot undo it), from the fifth failure on
 * the lock waits [BASE_DELAY_MILLIS], doubling up to [MAX_DELAY_MILLIS], and nothing is judged during the wait. If the lock cannot
 * be read or the count cannot be stored the answer is "unavailable", never "open". An unlock lasts the timeout, counted from the
 * last [touch], and ends at once on [relock] (screen off).
 */
class AppLock(
    private val storage: LockStorage,
    private val clock: () -> Long,
    private val hasher: PinHasher,
    /** The duress PIN record (self-destruct), or null when there is none. */
    private val duress: () -> SecretRecord? = { null },
    /** True on the main thread. Tests without a Looper pass `{ false }`; the app uses the real check. */
    private val onMainThread: () -> Boolean = AppLock::isMainThread,
) {
    enum class SetResult { OK, BAD_PIN, WRONG_CURRENT, WAIT, NOT_SAVED }

    sealed interface Verify {
        object Ok : Verify
        class Wrong(val failedAttempts: Int) : Verify
        /** Attempts are not judged for another [millis] milliseconds. */
        class Wait(val millis: Long) : Verify
        /** The duress PIN was entered (never the real one): the caller decides about the self-destruct. */
        object Duress : Verify
        /** The lock record cannot be decoded: never open, never replaced. The self-destruct counts it as an exhausted counter. */
        object Corrupt : Verify
        object Unavailable : Verify
    }

    @Volatile private var unlockedAt: Long? = null
    private val random = SecureRandom()

    /**
     * The one owner of every change of the record. [update] takes the process-wide [attemptLock], reads the record
     * INSIDE it ([Tx.load]) and runs [block], which decides from that read and stores through [Tx.save]; nothing else reads the record
     * to write it (enable, change, remove, timeout, the attempt count and its wait are all written here), so a change made between a
     * read and a write cannot be overwritten, whichever two writers meet. The record is in one process-wide store, so the lock is too
     * (each unlock screen asks the instance it is given). Hashing a PIN (to compare it, or the new one) happens inside the lock: the
     * attempt is stored before the compare and the answer after it, as one step, and a corrupt or unreadable record costs no hash.
     * So the lock is held for hundreds of milliseconds and every caller of a writer runs off the main thread (SourceRules: the UI
     * calls the writers inside withContext or an executor only).
     */
    private inner class Tx {
        val load: LockLoad = storage.load()
        fun save(record: LockRecord?): Boolean = storage.save(record)
    }

    private fun <T> update(block: (Tx) -> T): T {
        // fail closed, and the guard of the "off the main thread" rule: a writer waits for the lock and hashes a PIN under it, so on the main
        // thread it would freeze the screen; it is refused before anything is read or written (the SourceRules hint only catches the common forms)
        check(!onMainThread()) { "AppLock writers must not run on the main thread" }
        return synchronized(attemptLock) {
            try {
                block(Tx())
            } finally {
                snapshot.set(runCatching { storage.load() }.getOrNull()) // the record as stored after this writer: what the main thread answers from (see [snapshot])
            }
        }
    }

    /**
     * The last record read from the store, for the read-only calls on the main thread (audit follow-up of 1.0.1: they read and decrypt the store, which
     * the main thread must not do). It is written only with a fresh read: by every writer ([update]) right after its change, by every read-only call
     * on another thread ([load]) and by [prime], all under the process-wide lock so an older read cannot overwrite a newer one; the main thread fills an
     * empty snapshot only (compare and set), which is the one read it still does per process. Only a lock that is set or not set is answered from it: an
     * unreadable or undecodable record is read again every time (never "unlocked" from memory). The record is changed by [update] alone, so the snapshot
     * cannot miss a change; if the store is damaged or deleted behind this class the snapshot says "locked" at most, until the next read on another thread.
     */
    private val snapshot = AtomicReference<LockLoad?>(null)

    private fun load(): LockLoad {
        if (!onMainThread()) return synchronized(attemptLock) { storage.load().also { snapshot.set(it) } }
        val kept = snapshot.get()
        if (kept is LockLoad.None || kept is LockLoad.Present) return kept
        return storage.load().also { snapshot.compareAndSet(null, it) }
    }

    /** Reads the record once, off the main thread, so that the main thread finds it in the snapshot (the app calls this at start, in the background). */
    fun prime() {
        if (!onMainThread()) load()
    }

    /** What an attempt is hashed against when no duress PIN is set: random bytes, same cost as a real record, never a match. */
    private val noDuress: SecretRecord by lazy {
        SecretRecord(ByteArray(SecretRecords.SALT_SIZE).also { random.nextBytes(it) }, ByteArray(SecretRecords.HASH_SIZE).also { random.nextBytes(it) }, hasher.kdf)
    }

    private fun validPin(pin: CharArray) = isValidPin(pin)

    /** Sets the first PIN. When a lock exists it must be changed with [changePin]. */
    fun enable(pin: CharArray): SetResult {
        if (!validPin(pin)) return SetResult.BAD_PIN
        return update { tx ->
            when (tx.load) {
                is LockLoad.Present -> SetResult.WRONG_CURRENT
                is LockLoad.Unavailable, is LockLoad.Corrupt -> SetResult.NOT_SAVED
                is LockLoad.None -> saved(tx.save(newRecord(pin, DEFAULT_TIMEOUT_MINUTES)))
            }
        }
    }

    /** Replaces the PIN; needs the current one. The timeout of the lock at that moment stays. */
    fun changePin(current: CharArray, new: CharArray): SetResult {
        if (!validPin(new)) return SetResult.BAD_PIN
        return update { tx ->
            val attempt = attempt(tx, current)
            val record = attempt.record
            when (attempt.verify) {
                is Verify.Ok -> if (record == null) SetResult.WRONG_CURRENT else saved(tx.save(newRecord(new, record.timeoutMinutes)))
                else -> refused(attempt.verify)
            }
        }
    }

    /** Removes the lock; needs the current PIN. Removing what is not there is fine. */
    fun disable(current: CharArray): SetResult = update { tx ->
        val attempt = attempt(tx, current)
        when (attempt.verify) {
            is Verify.Ok -> if (attempt.record == null) SetResult.OK else saved(tx.save(null))
            else -> refused(attempt.verify)
        }
    }

    private fun refused(v: Verify): SetResult = when (v) {
        is Verify.Wrong, is Verify.Duress -> SetResult.WRONG_CURRENT
        is Verify.Wait -> SetResult.WAIT
        else -> SetResult.NOT_SAVED
    }

    private fun saved(ok: Boolean) = if (ok) SetResult.OK else SetResult.NOT_SAVED

    private fun newRecord(pin: CharArray, timeoutMinutes: Int): LockRecord {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        return LockRecord(salt, hasher.hash(pin, salt), hasher.kdf, timeoutMinutes, 0, 0L)
    }

    fun verify(pin: CharArray): Verify = update { tx -> attempt(tx, pin).verify }

    /** The answer of one check of a PIN and, when it is right, the record it was checked against (null: there is no lock). */
    private class Attempt(val verify: Verify, val record: LockRecord? = null)

    /** One check of [pin] inside an [update]: stores the attempt before comparing, resets the count on a right PIN, opens the unlock. */
    private fun attempt(tx: Tx, pin: CharArray): Attempt {
        val record = when (val loaded = tx.load) {
            is LockLoad.None -> return Attempt(Verify.Ok) // nothing to verify against
            is LockLoad.Unavailable -> return Attempt(Verify.Unavailable)
            is LockLoad.Corrupt -> return Attempt(Verify.Corrupt)
            is LockLoad.Present -> loaded.record
        }
        val now = clock()
        // a clock moved back cannot make the wait longer than the cap
        val remaining = minOf(record.lockedUntil - now, MAX_DELAY_MILLIS)
        if (record.lockedUntil > 0 && remaining > 0) {
            // the real PIN is not judged during a wait, but the duress PIN is: it counts nothing and tells nothing about the real one.
            // One hash is computed whether a duress PIN is set or not (a stand-in record when not), so the time does not tell
            val waiting = duress() ?: noDuress
            val hit = matches(pin, waiting) && waiting !== noDuress
            return Attempt(if (hit) Verify.Duress else Verify.Wait(remaining))
        }
        val failed = if (record.failedAttempts == Int.MAX_VALUE) Int.MAX_VALUE else record.failedAttempts + 1
        val delay = delayFor(failed)
        // the attempt is stored first: if that is not possible there is no free guess
        if (!tx.save(copy(record, failed = failed, lockedUntil = if (delay > 0) now + delay else 0L))) return Attempt(Verify.Unavailable)
        val real = MessageDigest.isEqual(hasher.hash(pin, record.salt, record.kdf), record.hash)
        // both hashes are always computed (a stand-in record when no duress PIN is set), so the time tells neither the real PIN from the
        // duress PIN nor whether there is one; the real PIN wins a collision
        val duressRecord = duress() ?: noDuress
        val duressHit = matches(pin, duressRecord) && duressRecord !== noDuress
        if (!real) return Attempt(if (duressHit) Verify.Duress else Verify.Wrong(failed))
        tx.save(copy(record, failed = 0, lockedUntil = 0L)) // if this fails the count stays: the next wrong PIN is judged more strictly
        unlockedAt = now
        return Attempt(Verify.Ok, record)
    }

    private fun matches(pin: CharArray, record: SecretRecord) = MessageDigest.isEqual(hasher.hash(pin, record.salt, record.kdf), record.hash)

    /**
     * True when [pin] is the PIN of the lock, false when not, null when there is no lock or it cannot be read. Counts no attempt: only
     * for refusing a duress PIN that equals the real one (the caller is already unlocked).
     */
    fun collides(pin: CharArray): Boolean? {
        val record = (storage.load() as? LockLoad.Present)?.record ?: return null
        return MessageDigest.isEqual(hasher.hash(pin, record.salt, record.kdf), record.hash)
    }

    private fun copy(r: LockRecord, failed: Int = r.failedAttempts, lockedUntil: Long = r.lockedUntil, timeoutMinutes: Int = r.timeoutMinutes) =
        LockRecord(r.salt, r.hash, r.kdf, timeoutMinutes, failed, lockedUntil)

    /** True when a lock is set (or cannot be read: then it is treated as set). */
    fun isEnabled(): Boolean = load() !is LockLoad.None

    /** True while a lock exists that is not unlocked (or cannot be read). */
    fun needsUnlock(): Boolean = needsUnlock(load())

    private fun needsUnlock(loaded: LockLoad): Boolean {
        val record = when (loaded) {
            is LockLoad.None -> return false
            is LockLoad.Unavailable, is LockLoad.Corrupt -> return true
            is LockLoad.Present -> loaded.record
        }
        val at = unlockedAt ?: return true
        val elapsed = clock() - at
        return elapsed < 0 || elapsed >= record.timeoutMinutes * 60_000L // a clock set back never extends an unlock
    }

    /** Activity in a protected screen extends the unlock; it never opens a locked lock. */
    fun touch() {
        if (!needsUnlock()) unlockedAt = clock()
    }

    /** Ends the unlock at once (the screen turned off). */
    fun relock() {
        unlockedAt = null
    }

    fun timeoutMinutes(): Int = (load() as? LockLoad.Present)?.record?.timeoutMinutes ?: DEFAULT_TIMEOUT_MINUTES

    /** Changes the timeout of an unlocked lock to one of [TIMEOUT_CHOICES_MINUTES]. */
    /** Moves an unlocked lock to the next offered timeout (wrapping), read and stored in one step; the new value, or null when nothing was changed. */
    fun cycleTimeoutMinutes(): Int? = update { tx ->
        val record = (tx.load as? LockLoad.Present)?.record
        if (record == null || needsUnlock(tx.load)) null
        else {
            val next = TIMEOUT_CHOICES_MINUTES[(TIMEOUT_CHOICES_MINUTES.indexOf(record.timeoutMinutes) + 1) % TIMEOUT_CHOICES_MINUTES.size]
            if (tx.save(copy(record, timeoutMinutes = next))) next else null
        }
    }

    fun setTimeoutMinutes(minutes: Int): Boolean {
        if (minutes !in TIMEOUT_CHOICES_MINUTES) return false
        return update { tx ->
            val record = (tx.load as? LockLoad.Present)?.record
            if (record == null || needsUnlock(tx.load)) false else tx.save(copy(record, timeoutMinutes = minutes))
        }
    }

    private fun delayFor(failed: Int): Long =
        if (failed < FREE_ATTEMPTS) 0L else minOf(MAX_DELAY_MILLIS, BASE_DELAY_MILLIS shl minOf(failed - FREE_ATTEMPTS, 20))

    companion object {
        private val attemptLock = Any()
        /** The app's check for [onMainThread]: the calling thread is the one of the main looper. */
        fun isMainThread(): Boolean = Looper.getMainLooper().isCurrentThread
        fun isValidPin(pin: CharArray) = pin.size in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it in '0'..'9' }
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 16
        const val DEFAULT_TIMEOUT_MINUTES = 1
        val TIMEOUT_CHOICES_MINUTES = listOf(1, 5, 15, 60)
        /** Wrong attempts that are judged without a wait. */
        const val FREE_ATTEMPTS = 5
        const val BASE_DELAY_MILLIS = 30_000L
        const val MAX_DELAY_MILLIS = 3_600_000L
        /**
         * Argon2id for the PIN: 8 MiB, 2 passes. Measured on the reference phone: 230-260 ms warm, 390 ms for
         * the first call; 8 MiB x 3 passes took 340-380 ms and 16 MiB x 2 passes 460 ms, so the time follows memory x
         * passes. The hash is inside an AES-GCM store and the attempts are rate limited, so the parameters only have to make a guess cost time;
         * the design asks for 150-300 ms for PIN checks.
         */
        val PIN_KDF = ExportCodec.Kdf(memoryKiB = 8192, iterations = 2, lanes = 1)
    }
}
