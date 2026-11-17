// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Storage in memory: the tests of the lock rules do not need the encrypted file. */
class MemoryLockStorage : LockStorage {
    var content: LockLoad = LockLoad.None
    var writes = 0
    var failWrites = false
    val record: LockRecord? get() = (content as? LockLoad.Present)?.record
    override fun load(): LockLoad = content
    override fun save(record: LockRecord?): Boolean {
        if (failWrites) return false
        writes++
        content = if (record == null) LockLoad.None else LockLoad.Present(record)
        return true
    }
}

/** App lock (design 7.4 item 7): a PIN in front of the settings and the clipboard history. */
class AppLockTest {
    private var now = 1_000_000L
    private val storage = MemoryLockStorage()
    private val fastKdf = ExportCodec.Kdf(memoryKiB = 64, iterations = 1, lanes = 1) // real parameters are measured on the device
    private fun lock(hasher: PinHasher = PinHasher(fastKdf)) = AppLock(storage, { now }, hasher, onMainThread = notMainThread)
    private fun same(a: LockRecord?, b: LockRecord) = a != null && a.salt.contentEquals(b.salt) && a.hash.contentEquals(b.hash)
    private val pin = "482915".toCharArray()
    private val wrong = "482916".toCharArray()

    // ---- the core behaviour -----------------------------------------------------------------------------------

    @Test fun aSetPinUnlocksOnlyWithTheRightDigitsAndStaysOpenUntilTheTimeoutRuns() {
        val lock = lock()
        assertFalse(lock.needsUnlock()) // no PIN set: nothing to unlock
        assertEquals(AppLock.SetResult.OK, lock.enable(pin))
        assertTrue(lock.needsUnlock())
        assertIs<AppLock.Verify.Wrong>(lock.verify("482916".toCharArray()))
        assertTrue(lock.needsUnlock())
        assertEquals(AppLock.Verify.Ok, lock.verify(pin))
        assertFalse(lock.needsUnlock())
        now += AppLock.DEFAULT_TIMEOUT_MINUTES * 60_000L - 1
        assertFalse(lock.needsUnlock())
        now += 2
        assertTrue(lock.needsUnlock()) // positive control: the unlock does expire
    }

    // ---- the whole contract -----------------------------------------------------------------------------------

    @Test fun aPinIsFourToSixteenDigits() {
        val lock = lock()
        for (bad in listOf("", "1", "123", "12a456", "12 456", "١٢٣٤٥٦", "1".repeat(17), "-12345", "12.345")) // 3 digits, letters, space, Arabic-Indic digits, too long
            assertEquals(AppLock.SetResult.BAD_PIN, lock.enable(bad.toCharArray()), "'$bad'")
        assertEquals(LockLoad.None, storage.content) // nothing stored for a rejected PIN
        assertEquals(AppLock.SetResult.OK, lock.enable("1234".toCharArray()))
        assertEquals(AppLock.SetResult.OK, lock().changePin("1234".toCharArray(), "1".repeat(16).toCharArray()))
        assertEquals(AppLock.Verify.Ok, lock().verify("1".repeat(16).toCharArray()))
    }

    @Test fun theStoredRecordHoldsNoPinAndEveryPinGetsItsOwnSalt() {
        val lock = lock()
        lock.enable(pin)
        val first = storage.record!!
        val bytes = first.hash + first.salt
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("482915"))
        assertEquals(32, first.hash.size)
        assertEquals(16, first.salt.size)
        lock.changePin(pin, pin) // the same digits again
        val second = storage.record!!
        assertFalse(first.salt.contentEquals(second.salt))
        assertFalse(first.hash.contentEquals(second.hash))
        assertEquals(AppLock.Verify.Ok, lock().verify(pin))
    }

    @Test fun changingOrRemovingThePinNeedsTheCurrentPin() {
        val lock = lock()
        lock.enable(pin)
        val before = storage.record!!
        assertEquals(AppLock.SetResult.WRONG_CURRENT, lock.changePin(wrong, "999999".toCharArray()))
        assertEquals(AppLock.SetResult.WRONG_CURRENT, lock.disable(wrong))
        assertTrue(same(storage.record, before)) // nothing changed
        assertEquals(AppLock.SetResult.BAD_PIN, lock.changePin(pin, "12".toCharArray())) // right PIN, bad new one: nothing changed
        assertTrue(same(storage.record, before))
        // positive controls
        assertEquals(AppLock.SetResult.OK, lock.changePin(pin, "999999".toCharArray()))
        assertIs<AppLock.Verify.Wrong>(lock().verify(pin)) // the old PIN is gone
        assertEquals(AppLock.Verify.Ok, lock().verify("999999".toCharArray()))
        assertEquals(AppLock.SetResult.OK, lock.disable("999999".toCharArray()))
        assertEquals(LockLoad.None, storage.content)
        assertFalse(lock.needsUnlock())
    }

    @Test fun enablingWhenALockExistsNeedsTheCurrentPinSoItCannotBeReplacedFromTheOutside() {
        val lock = lock()
        lock.enable(pin)
        val before = storage.record!!
        assertEquals(AppLock.SetResult.WRONG_CURRENT, lock.enable("111111".toCharArray()))
        assertTrue(same(storage.record, before))
    }

    @Test fun aFailedWriteIsReportedAndLeavesTheOldLock() {
        val lock = lock()
        lock.enable(pin)
        val before = storage.record!!
        storage.failWrites = true
        assertEquals(AppLock.SetResult.NOT_SAVED, lock.changePin(pin, "999999".toCharArray()))
        assertEquals(AppLock.SetResult.NOT_SAVED, lock.disable(pin))
        storage.failWrites = false
        assertTrue(same(storage.record, before))
        assertEquals(AppLock.Verify.Ok, lock.verify(pin))
    }

    @Test fun everyWrongAttemptIsCountedAndStoredBeforeThePinIsChecked() {
        var failedWhileHashing = -1
        val spy = object : PinHasher(fastKdf) {
            override fun hash(pin: CharArray, salt: ByteArray, params: ExportCodec.Kdf): ByteArray {
                failedWhileHashing = storage.record?.failedAttempts ?: -1
                return super.hash(pin, salt, params)
            }
        }
        val lock = lock(spy)
        lock.enable(pin)
        lock.verify(wrong)
        assertEquals(1, failedWhileHashing) // the attempt was already stored as failed while its PIN was being hashed: killing the process cannot undo it
        lock.verify(wrong)
        assertEquals(2, failedWhileHashing)
        lock.verify(wrong)
        assertEquals(3, failedWhileHashing)
        assertEquals(3, storage.record!!.failedAttempts)
        assertEquals(AppLock.Verify.Ok, lock.verify(pin))
        assertEquals(0, storage.record!!.failedAttempts) // a right PIN clears the count
    }

    @Test fun afterFiveWrongAttemptsTheLockWaitsAndTheWaitGrowsToACap() {
        val lock = lock()
        lock.enable(pin)
        repeat(AppLock.FREE_ATTEMPTS - 1) { assertIs<AppLock.Verify.Wrong>(lock.verify(wrong)) }
        val fifth = lock.verify(wrong)
        assertIs<AppLock.Verify.Wrong>(fifth) // the fifth attempt is still judged
        assertEquals(AppLock.FREE_ATTEMPTS, fifth.failedAttempts)
        // now it waits: even the right PIN is not looked at
        val wait = lock.verify(pin)
        assertIs<AppLock.Verify.Wait>(wait)
        assertEquals(AppLock.BASE_DELAY_MILLIS, wait.millis)
        assertTrue(lock.needsUnlock())
        now += AppLock.BASE_DELAY_MILLIS - 1
        assertEquals(1L, (lock.verify(pin) as AppLock.Verify.Wait).millis)
        now += 1
        assertIs<AppLock.Verify.Wrong>(lock.verify(wrong)) // sixth: the wait doubles
        assertEquals(2 * AppLock.BASE_DELAY_MILLIS, (lock.verify(pin) as AppLock.Verify.Wait).millis)
        now += 2 * AppLock.BASE_DELAY_MILLIS
        assertIs<AppLock.Verify.Wrong>(lock.verify(wrong)) // seventh
        assertEquals(4 * AppLock.BASE_DELAY_MILLIS, (lock.verify(pin) as AppLock.Verify.Wait).millis)
        // many failures: capped
        repeat(30) { now += AppLock.MAX_DELAY_MILLIS; lock.verify(wrong) }
        assertEquals(AppLock.MAX_DELAY_MILLIS, (lock.verify(pin) as AppLock.Verify.Wait).millis)
        // after the wait the right PIN unlocks and clears everything
        now += AppLock.MAX_DELAY_MILLIS
        assertEquals(AppLock.Verify.Ok, lock.verify(pin))
        assertEquals(0, storage.record!!.failedAttempts)
        assertEquals(0L, storage.record!!.lockedUntil)
    }

    @Test fun theWaitSurvivesARestartOfTheApp() {
        val first = lock()
        first.enable(pin)
        repeat(AppLock.FREE_ATTEMPTS) { first.verify(wrong) }
        assertIs<AppLock.Verify.Wait>(lock().verify(pin)) // a new object, same storage
    }

    @Test fun movingTheClockBackDoesNotShortenOrExtendTheWaitBeyondTheCap() {
        val lock = lock()
        lock.enable(pin)
        repeat(AppLock.FREE_ATTEMPTS + 20) { now += AppLock.MAX_DELAY_MILLIS; lock.verify(wrong) }
        now -= 10L * 24 * 3600 * 1000 // ten days back
        val wait = lock.verify(pin) as AppLock.Verify.Wait
        assertTrue(wait.millis <= AppLock.MAX_DELAY_MILLIS, "${wait.millis}")
        assertTrue(wait.millis > 0)
    }

    @Test fun unavailableStorageKeepsTheLockClosedAndUnverifiable() { // fail closed
        val lock = lock()
        lock.enable(pin)
        lock.verify(pin)
        assertFalse(lock.needsUnlock())
        storage.content = LockLoad.Unavailable
        assertTrue(lock.needsUnlock())
        assertEquals(AppLock.Verify.Unavailable, lock.verify(pin))
        assertEquals(AppLock.SetResult.NOT_SAVED, lock.changePin(pin, "999999".toCharArray()))
        assertEquals(AppLock.SetResult.NOT_SAVED, lock.disable(pin))
        assertEquals(LockLoad.Unavailable, storage.content)
    }

    @Test fun ifTheCountCannotBeStoredThePinIsNotChecked() { // fail closed: no free guesses
        val lock = lock()
        lock.enable(pin)
        storage.failWrites = true
        assertEquals(AppLock.Verify.Unavailable, lock.verify(pin))
        assertTrue(lock.needsUnlock())
        storage.failWrites = false
        assertEquals(AppLock.Verify.Ok, lock.verify(pin))
    }

    @Test fun aClockSetBackNeverExtendsAnUnlock() { // mutation survivor of the first run: the negative elapsed time was not tested
        val lock = lock()
        lock.enable(pin)
        lock.verify(pin)
        assertFalse(lock.needsUnlock())
        now -= 1L // one millisecond back is already "before the unlock"
        assertTrue(lock.needsUnlock())
        now -= 24L * 3600 * 1000 // a day back
        assertTrue(lock.needsUnlock())
        now += 24L * 3600 * 1000 + 1 // and forward again to the unlock time: open again (positive control)
        assertFalse(lock.needsUnlock())
    }

    @Test fun relockAndTouchControlTheUnlockWindow() {
        val lock = lock()
        lock.enable(pin)
        lock.touch() // a locked lock cannot be opened by touching
        assertTrue(lock.needsUnlock())
        lock.verify(pin)
        now += 50_000
        lock.touch() // activity in the settings extends the window
        now += 50_000
        assertFalse(lock.needsUnlock())
        now += 10_001
        assertTrue(lock.needsUnlock())
        lock.verify(pin)
        lock.relock() // screen off
        assertTrue(lock.needsUnlock())
    }

    @Test fun theTimeoutIsOneOfTheOfferedValuesAndIsPartOfTheStoredRecord() {
        val lock = lock()
        lock.enable(pin)
        lock.verify(pin)
        for (bad in listOf(0, -1, 2, 10, 61, 1000)) assertFalse(lock.setTimeoutMinutes(bad), "$bad")
        assertEquals(AppLock.DEFAULT_TIMEOUT_MINUTES, storage.record!!.timeoutMinutes)
        for (ok in AppLock.TIMEOUT_CHOICES_MINUTES) assertTrue(lock.setTimeoutMinutes(ok), "$ok")
        assertEquals(AppLock.TIMEOUT_CHOICES_MINUTES.last(), storage.record!!.timeoutMinutes)
        assertEquals(AppLock.TIMEOUT_CHOICES_MINUTES.last(), lock.timeoutMinutes())
        now += 59 * 60_000L
        assertFalse(lock.needsUnlock()) // 60 minute timeout
        now += 2 * 60_000L
        assertTrue(lock.needsUnlock())
    }

    @Test fun changingThePinKeepsTheChosenTimeoutAndResetsTheCount() { // the timeout is read inside the change
        val lock = lock()
        lock.enable(pin)
        lock.verify(pin)
        assertTrue(lock.setTimeoutMinutes(15))
        assertEquals(AppLock.SetResult.OK, lock.changePin(pin, "135790".toCharArray()))
        assertEquals(15, storage.record!!.timeoutMinutes)
        assertEquals(0, storage.record!!.failedAttempts)
        assertEquals(15, lock().also { assertEquals(AppLock.Verify.Ok, it.verify("135790".toCharArray())) }.timeoutMinutes())
    }

    @Test fun theTimeoutCannotBeChangedWhileLockedOrWithoutALock() {
        val lock = lock()
        assertFalse(lock.setTimeoutMinutes(5)) // no lock
        lock.enable(pin)
        assertFalse(lock.setTimeoutMinutes(5)) // locked
        assertEquals(AppLock.DEFAULT_TIMEOUT_MINUTES, storage.record!!.timeoutMinutes)
    }

    @Test fun aPinMadeWithOtherParametersStillVerifies() {
        lock(PinHasher(ExportCodec.Kdf(64, 1, 1))).enable(pin)
        val newer = lock(PinHasher(ExportCodec.Kdf(128, 2, 1))) // the defaults moved on
        assertEquals(AppLock.Verify.Ok, newer.verify(pin))
        assertEquals(64, storage.record!!.kdf.memoryKiB)
        newer.changePin(pin, pin)
        assertEquals(128, storage.record!!.kdf.memoryKiB) // a new PIN uses the new parameters
    }

    @Test fun withoutALockNothingIsAskedOrWritten() {
        val lock = lock()
        assertEquals(AppLock.Verify.Ok, lock.verify(wrong)) // no lock: nothing to verify against
        assertEquals(AppLock.SetResult.OK, lock.disable(pin)) // removing what is not there is fine
        assertEquals(AppLock.SetResult.WRONG_CURRENT, lock.changePin(pin, "999999".toCharArray())) // a PIN is set with enable
        assertFalse(lock.needsUnlock())
        assertEquals(0, storage.writes)
    }

    @Test fun theRecordRoundTripsThroughTheEncodedForm() {
        val lock = lock()
        lock.enable(pin)
        lock.verify(pin)
        lock.setTimeoutMinutes(15)
        repeat(AppLock.FREE_ATTEMPTS) { lock.verify(wrong) }
        val record = storage.record!!
        val copy = LockRecordCodec.decode(LockRecordCodec.encode(record))
        assertContentEquals(record.salt, copy.salt)
        assertContentEquals(record.hash, copy.hash)
        assertEquals(record.kdf.memoryKiB, copy.kdf.memoryKiB)
        assertEquals(record.kdf.iterations, copy.kdf.iterations)
        assertEquals(record.kdf.lanes, copy.kdf.lanes)
        assertEquals(15, copy.timeoutMinutes)
        assertEquals(AppLock.FREE_ATTEMPTS, copy.failedAttempts)
        assertEquals(record.lockedUntil, copy.lockedUntil)
    }

    @Test fun aMalformedRecordIsRejectedWhateverTheDamage() {
        val good = LockRecordCodec.encode(LockRecord(ByteArray(16) { 1 }, ByteArray(32) { 2 }, ExportCodec.Kdf(64, 1, 1), 5, 0, 0L))
        for (cut in listOf(0, 1, good.size - 1)) assertFailsWithCorrupt(good.copyOf(cut))
        assertFailsWithCorrupt(good + byteArrayOf(0)) // trailing byte
        assertFailsWithCorrupt(good.copyOf().also { it[3] = 9 }) // unknown version
        // absurd Argon2 parameters would exhaust memory or time on the next PIN check
        for (kdf in listOf(ExportCodec.Kdf(Int.MAX_VALUE, 1, 1), ExportCodec.Kdf(64, 1000, 1), ExportCodec.Kdf(64, 1, 100), ExportCodec.Kdf(0, 1, 1)))
            assertFailsWithCorrupt(LockRecordCodec.encode(LockRecord(ByteArray(16), ByteArray(32), kdf, 5, 0, 0L)))
        assertFailsWithCorrupt(LockRecordCodec.encode(LockRecord(ByteArray(16), ByteArray(32), ExportCodec.Kdf(64, 1, 1), 7, 0, 0L))) // unoffered timeout
        assertFailsWithCorrupt(LockRecordCodec.encode(LockRecord(ByteArray(16), ByteArray(32), ExportCodec.Kdf(64, 1, 1), 5, -1, 0L))) // negative count
    }

    // ---- every writer of the record takes the same lock as verify (a concurrent verify must not save over it) ----

    /** A storage whose [nth] load by [holdThread] waits (after reading) until [release] is counted down, so the other thread runs in between. */
    private class HeldStorage(private val inner: LockStorage, private val holdThread: String, private val nth: Int = 1) : LockStorage {
        val arrived = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        private val loads = java.util.concurrent.atomic.AtomicInteger()
        override fun load(): LockLoad {
            val read = inner.load()
            if (Thread.currentThread().name == holdThread && loads.incrementAndGet() == nth) {
                arrived.countDown()
                release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            }
            return read
        }
        override fun save(record: LockRecord?) = inner.save(record)
    }

    private fun threadOf(name: String, body: () -> Unit) = Thread({ body() }, name).also { it.start() }

    @Test fun aVerifyCannotSaveOverATimeoutChange() {
        val other = lock()
        assertEquals(AppLock.SetResult.OK, other.enable(pin))
        assertEquals(AppLock.Verify.Ok, other.verify(pin)) // unlocked: the timeout may change
        val held = HeldStorage(storage, "checker")
        val checker = AppLock(held, { now }, PinHasher(fastKdf), onMainThread = notMainThread)
        val setter = AppLock(held, { now }, PinHasher(fastKdf), onMainThread = notMainThread).also { assertEquals(AppLock.Verify.Ok, it.verify(pin)) }
        val v = threadOf("checker") { checker.verify(wrong) }
        assertTrue(held.arrived.await(10, java.util.concurrent.TimeUnit.SECONDS))
        var changed: Boolean? = null
        val s = threadOf("setter") { changed = setter.setTimeoutMinutes(5) }
        s.join(500) // without the lock the change is finished here; with it the setter waits for the verify
        held.release.countDown()
        v.join(10_000); s.join(10_000)
        assertEquals(true, changed)
        assertEquals(5, storage.record!!.timeoutMinutes) // the timeout survived the stale save of the verify
        assertEquals(1, storage.record!!.failedAttempts) // and so did the wrong attempt
    }

    @Test fun aVerifyCannotSaveOverAPinChange() {
        val other = lock()
        assertEquals(AppLock.SetResult.OK, other.enable(pin))
        // the changer is held after reading the record (its only read, inside the lock of the update), the checker after its read:
        // without one lock around read and save, the checker saves the old PIN back over the new one (the changer used to
        // read three times, held at the third; the one-read owner holds at the first, same scenario)
        val changerHeld = HeldStorage(storage, "changer", 1)
        val checkerHeld = HeldStorage(changerHeld, "checker")
        val checker = AppLock(checkerHeld, { now }, PinHasher(fastKdf), onMainThread = notMainThread)
        val changer = AppLock(checkerHeld, { now }, PinHasher(fastKdf), onMainThread = notMainThread)
        val newPin = "135790".toCharArray()
        var result: AppLock.SetResult? = null
        val c = threadOf("changer") { result = changer.changePin(pin, newPin) }
        assertTrue(changerHeld.arrived.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val v = threadOf("checker") { checker.verify(wrong) }
        v.join(500) // the checker has read the old record and waits (without the lock), or waits for the lock
        changerHeld.release.countDown() // the changer saves the new record
        c.join(500)
        checkerHeld.release.countDown() // the checker saves what it read before
        v.join(10_000); c.join(10_000)
        assertEquals(AppLock.SetResult.OK, result)
        assertEquals(AppLock.Verify.Ok, lock().verify(newPin)) // the new PIN was not replaced by the old record of the verify
    }

    @Test fun twoConcurrentEnablesLeaveOneWinner() {
        val held = HeldStorage(storage, "first")
        val results = java.util.concurrent.ConcurrentLinkedQueue<AppLock.SetResult>()
        val first = threadOf("first") { results.add(AppLock(held, { now }, PinHasher(fastKdf), onMainThread = notMainThread).enable(pin)) }
        assertTrue(held.arrived.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val second = threadOf("second") { results.add(AppLock(held, { now }, PinHasher(fastKdf), onMainThread = notMainThread).enable("135790".toCharArray())) }
        second.join(500)
        held.release.countDown()
        first.join(10_000); second.join(10_000)
        assertEquals(setOf(AppLock.SetResult.OK, AppLock.SetResult.WRONG_CURRENT), results.toSet()) // the second enable saw the first one's record
        assertEquals(2, results.size)
    }

    // ---- two instances (two unlock screens) must not lose a wrong-PIN count -----------------------------

    @Test fun wrongAttemptsOfTwoInstancesAreAllCounted() {
        val a = lock()
        assertEquals(AppLock.SetResult.OK, a.enable(pin))
        val slow = object : LockStorage by storage { // widens the read-increment-save window of an unsynchronised count
            override fun load(): LockLoad = storage.load().also { Thread.sleep(25) }
        }
        val locks = listOf(AppLock(slow, { now }, PinHasher(fastKdf), onMainThread = notMainThread), AppLock(slow, { now }, PinHasher(fastKdf), onMainThread = notMainThread))
        val start = java.util.concurrent.CountDownLatch(1)
        val results = java.util.concurrent.ConcurrentLinkedQueue<AppLock.Verify>()
        val threads = locks.map { l -> Thread { start.await(); repeat(2) { results.add(l.verify(wrong)) } } }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(20_000) }
        assertEquals(4, results.size)
        assertTrue(results.all { it is AppLock.Verify.Wrong })
        assertEquals(setOf(1, 2, 3, 4), results.map { (it as AppLock.Verify.Wrong).failedAttempts }.toSet()) // every attempt saw a distinct count
        assertEquals(4, storage.record!!.failedAttempts)
    }

    private fun assertFailsWithCorrupt(bytes: ByteArray) {
        try {
            LockRecordCodec.decode(bytes)
        } catch (_: CorruptStoreException) {
            return
        }
        throw AssertionError("a damaged record was accepted")
    }
}
