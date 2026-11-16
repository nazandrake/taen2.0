// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The duress PIN at the app-lock prompt (trigger 1) and the damaged lock record. */
class AppLockDuressTest {
    private var now = 5_000_000L
    private val storage = MemoryLockStorage()
    private val fastKdf = ExportCodec.Kdf(memoryKiB = 64, iterations = 1, lanes = 1)
    private val hasher = CountingHasher(fastKdf)
    private val real = "482915".toCharArray()
    private val duressPin = "1111".toCharArray()
    private var duress: SecretRecord? = SecretRecords.create(duressPin, hasher, SecureRandom())
    private fun lock() = AppLock(storage, { now }, hasher, duress = { duress }, onMainThread = notMainThread)

    private fun enabled(): AppLock = lock().also { assertEquals(AppLock.SetResult.OK, it.enable(real)); hasher.calls = 0 }

    @Test fun theDuressPinGivesDuressNeverOkAndNeverUnlocks() {
        val lock = enabled()
        assertEquals(AppLock.Verify.Duress, lock.verify(duressPin))
        assertTrue(lock.needsUnlock())
        assertEquals(AppLock.Verify.Ok, lock.verify(real)) // positive control: the real PIN still unlocks
        assertFalse(lock.needsUnlock())
    }

    @Test fun theRealPinWinsWhenBothAreTheSame() {
        duress = SecretRecords.create(real, hasher, SecureRandom())
        val lock = enabled()
        assertEquals(AppLock.Verify.Ok, lock.verify(real))
        assertFalse(lock.needsUnlock())
    }

    @Test fun everyAttemptComputesBothHashesWhenADuressPinIsSet() {
        val lock = enabled()
        lock.verify("0000".toCharArray()); assertEquals(2, hasher.calls)
        hasher.calls = 0; lock.verify(duressPin); assertEquals(2, hasher.calls)
        hasher.calls = 0; lock.verify(real); assertEquals(2, hasher.calls)
    }

    /** Hash calls of one attempt with and without a duress PIN, in a state made by [prepare]; the time of an attempt follows this count. */
    private fun callsWith(duressSet: Boolean, pin: CharArray, prepare: (AppLock) -> Unit = {}): Int {
        duress = if (duressSet) SecretRecords.create(duressPin, hasher, SecureRandom()) else null
        storage.content = LockLoad.None
        val lock = enabled()
        prepare(lock)
        hasher.calls = 0
        lock.verify(pin)
        return hasher.calls
    }

    @Test fun noDuressPinCostsTheSameHashesAsADuressPinInEveryState() {
        val wrong = "0000".toCharArray()
        val states = mapOf<String, Pair<CharArray, (AppLock) -> Unit>>(
            "wrong PIN" to (wrong to {}),
            "real PIN" to (real to {}),
            "real PIN after failures" to (real to { l -> repeat(3) { l.verify(wrong) } }),
            "during the wait" to (real to { l -> repeat(5) { l.verify(wrong) } }),
            "wrong PIN during the wait" to (wrong to { l -> repeat(5) { l.verify(wrong) } }),
        )
        for ((name, state) in states) {
            val (pin, prepare) = state
            val without = callsWith(false, pin, prepare)
            val with = callsWith(true, pin, prepare)
            assertEquals(without, with, "$name: with and without a duress PIN")
            assertTrue(without >= 1, "$name: a hash is computed")
        }
        assertEquals(2, callsWith(false, wrong)) // positive control: two hashes outside a wait, one during it
        assertEquals(1, callsWith(false, wrong) { l -> repeat(5) { l.verify(wrong) } })
    }

    @Test fun theStandInRecordNeverGivesDuressWhenNoDuressPinIsSet() {
        duress = null
        val lock = enabled()
        assertEquals(1, assertIs<AppLock.Verify.Wrong>(lock.verify("1111".toCharArray())).failedAttempts) // also the PIN a duress record would hold
        repeat(4) { lock.verify("0000".toCharArray()) }
        assertIs<AppLock.Verify.Wait>(lock.verify("1111".toCharArray())) // during the wait
        assertIs<AppLock.Verify.Wait>(lock.verify(real))
        now += AppLock.BASE_DELAY_MILLIS + 1
        assertEquals(AppLock.Verify.Ok, lock.verify(real)) // positive control: after the wait the real PIN works
    }

    @Test fun aDuressRecordThatCannotBeReadCostsTheSameAsOneThatIsThere() {
        duress = null // the configuration store is unavailable: the lookup gives null
        val lock = enabled()
        lock.verify(real)
        assertEquals(2, hasher.calls)
    }

    @Test fun aWrongPinThatIsNotTheDuressPinStaysWrongAndCountsAsBefore() {
        val lock = enabled()
        assertEquals(1, assertIs<AppLock.Verify.Wrong>(lock.verify("0000".toCharArray())).failedAttempts)
        assertEquals(2, assertIs<AppLock.Verify.Wrong>(lock.verify("0001".toCharArray())).failedAttempts)
    }

    @Test fun theDuressPinIsRecognisedDuringAWaitAndTheRealPinIsNotJudgedThen() {
        val lock = enabled()
        repeat(5) { lock.verify("0000".toCharArray()) } // the fifth failure starts the wait
        assertIs<AppLock.Verify.Wait>(lock.verify(real)) // not judged
        val failed = storage.record!!.failedAttempts
        assertEquals(AppLock.Verify.Duress, lock.verify(duressPin))
        assertEquals(failed, storage.record!!.failedAttempts) // no count for a duress PIN during the wait
        assertIs<AppLock.Verify.Wait>(lock.verify("0002".toCharArray())) // another wrong PIN: still only a wait, nothing learned
    }

    @Test fun noLockMeansNothingIsJudgedAndNoDuressResult() {
        val lock = lock()
        assertEquals(AppLock.Verify.Ok, lock.verify(duressPin))
    }

    @Test fun aLockRecordThatCannotBeDecodedIsCorruptAndStaysClosed() {
        val lock = lock()
        hasher.calls = 0
        storage.content = LockLoad.Corrupt
        assertEquals(AppLock.Verify.Corrupt, lock.verify(real))
        assertEquals(AppLock.Verify.Corrupt, lock.verify(duressPin))
        assertTrue(lock.isEnabled())
        assertTrue(lock.needsUnlock())
        assertEquals(AppLock.SetResult.NOT_SAVED, lock.enable(real)) // never replaced silently
        assertEquals(AppLock.SetResult.NOT_SAVED, lock.changePin(real, "123456".toCharArray()))
        assertEquals(LockLoad.Corrupt, storage.content)
        assertEquals(0, hasher.calls)
    }

    @Test fun collidesTellsWhetherAPinIsTheCurrentPinWithoutCountingAnAttempt() {
        val lock = enabled()
        val writes = storage.writes
        assertEquals(true, lock.collides(real))
        assertEquals(false, lock.collides(duressPin))
        assertEquals(writes, storage.writes)
        storage.content = LockLoad.Unavailable
        assertNull(lock.collides(real))
        storage.content = LockLoad.None
        assertNull(lock.collides(real)) // no lock: nothing to collide with, and no answer either
    }
}
