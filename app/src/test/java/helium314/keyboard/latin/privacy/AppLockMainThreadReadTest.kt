// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Audit follow-up (1.0.1): the read-only calls (needsUnlock, isEnabled, timeoutMinutes) must not read the encrypted store on the main thread once a
 * snapshot exists. Under Robolectric the test thread is the main thread; [offMain] is any other thread (a writer, the prime at start).
 */
@RunWith(RobolectricTestRunner::class)
class AppLockMainThreadReadTest {
    private val fastKdf = ExportCodec.Kdf(memoryKiB = 64, iterations = 1, lanes = 1)
    private val pin = "482915".toCharArray()

    private class CountingStorage(val inner: MemoryLockStorage) : LockStorage {
        var loads = 0
        override fun load(): LockLoad { loads++; return inner.load() }
        override fun save(record: LockRecord?): Boolean = inner.save(record)
    }

    private val memory = MemoryLockStorage()
    private val storage = CountingStorage(memory)
    private val lock = AppLock(storage, { 1_000_000L }, PinHasher(fastKdf)) // the app's own main-thread check

    // ---- the core behaviour -------------------------------------------------------------------------------------

    @Test fun afterAWriterTheMainThreadAnswersFromTheSnapshotWithoutReadingTheStore() {
        offMain { lock.enable(pin) }
        val before = storage.loads
        assertTrue(lock.isEnabled())
        assertTrue(lock.needsUnlock())
        assertEquals(AppLock.DEFAULT_TIMEOUT_MINUTES, lock.timeoutMinutes())
        assertEquals(before, storage.loads, "the main thread read the store")
        // positive control: another thread reads the store
        offMain { lock.isEnabled() }
        assertEquals(before + 1, storage.loads)
    }

    // ---- the whole contract -------------------------------------------------------------------------------------

    @Test fun withoutASnapshotTheMainThreadReadsOnceAndThenAnswersFromMemory() {
        assertFalse(lock.isEnabled()) // the one read per process that the main thread still does
        assertEquals(1, storage.loads)
        repeat(5) { assertFalse(lock.isEnabled()); assertFalse(lock.needsUnlock()); lock.timeoutMinutes() }
        assertEquals(1, storage.loads)
    }

    @Test fun anUnreadableOrUndecodableRecordIsReadAgainEveryTimeAndNeverAnsweredFromMemory() {
        memory.content = LockLoad.Unavailable
        repeat(3) { assertTrue(lock.needsUnlock()) } // closed, as before
        assertEquals(3, storage.loads)
        memory.content = LockLoad.Corrupt
        repeat(2) { assertTrue(lock.needsUnlock()) }
        assertEquals(5, storage.loads)
        // the store works again: the next call sees it at once (nothing from the failed reads was kept)
        memory.content = LockLoad.None
        offMain { lock.enable(pin) }
        assertTrue(lock.isEnabled() && lock.needsUnlock())
        // and a failed read off the main thread drops the snapshot, so the main thread reads again
        val loadsBefore = storage.loads
        memory.content = LockLoad.Unavailable
        offMain { lock.needsUnlock() }
        lock.needsUnlock()
        assertEquals(loadsBefore + 2, storage.loads)
    }

    @Test fun everyWriterLeavesTheSnapshotAtTheStoredState() {
        offMain { lock.enable(pin) }
        val loads = storage.loads
        assertTrue(lock.isEnabled())
        offMain { lock.disable(pin) }
        val afterDisable = storage.loads
        assertFalse(lock.isEnabled()) // changed by a writer: the main thread follows without a read
        assertFalse(lock.needsUnlock())
        assertEquals(afterDisable, storage.loads)
        assertTrue(afterDisable > loads) // positive control: the writer did read
        offMain { lock.enable(pin) }
        assertTrue(lock.isEnabled()) // (the unlock of the PIN that disabled it is still open, as before: needsUnlock is not asserted here)
    }

    @Test fun aWriterThatCouldNotSaveLeavesTheSnapshotAtTheOldRecord() {
        offMain { lock.enable(pin) }
        memory.failWrites = true
        assertEquals(AppLock.SetResult.NOT_SAVED, offMain { lock.changePin(pin, "135790".toCharArray()) })
        val before = storage.loads
        assertTrue(lock.isEnabled())
        assertEquals(before, storage.loads)
    }

    @Test fun anExternalChangeIsSeenAfterTheNextReadOnAnotherThreadAndNeverMadeLessStrict() {
        offMain { lock.enable(pin) }
        memory.content = LockLoad.None // changed behind the class (the only way: damage or deletion of the store)
        assertTrue(lock.isEnabled()) // the snapshot still says "locked": never less strict than the stored record
        assertTrue(lock.needsUnlock())
        offMain { lock.isEnabled() } // a read on another thread refreshes it
        assertFalse(lock.isEnabled())
        assertFalse(lock.needsUnlock())
    }

    @Test fun primeReadsOnlyOffTheMainThread() {
        lock.prime() // on the main thread: no read
        assertEquals(0, storage.loads)
        offMain { lock.prime() }
        assertEquals(1, storage.loads)
        assertFalse(lock.isEnabled())
        assertEquals(1, storage.loads) // answered from the primed snapshot
    }

    @Test fun theUnlockAndItsTimeoutFollowTheSnapshotWithoutReadingTheStore() {
        var now = 1_000_000L
        val timed = AppLock(storage, { now }, PinHasher(fastKdf))
        offMain { timed.enable(pin) }
        assertEquals(AppLock.Verify.Ok, offMain { timed.verify(pin) })
        assertEquals(5, offMain { timed.cycleTimeoutMinutes() })
        val before = storage.loads
        assertFalse(timed.needsUnlock())
        assertEquals(5, timed.timeoutMinutes())
        now += 5 * 60_000L - 1
        assertFalse(timed.needsUnlock())
        now += 2
        assertTrue(timed.needsUnlock()) // the expiry is judged on every call, only the record comes from memory
        timed.relock()
        assertTrue(timed.needsUnlock())
        assertEquals(before, storage.loads)
    }

    @Test fun readsOnAnotherThreadStillReadTheStoreEveryTime() { // the snapshot serves the main thread only
        offMain { lock.enable(pin) }
        val before = storage.loads
        offMain { lock.needsUnlock(); lock.isEnabled(); lock.timeoutMinutes() }
        assertEquals(before + 3, storage.loads)
    }

    @Test fun theAppPrimesTheSnapshotInTheBackgroundAtStart() { // the wiring: App.onCreate calls prime from its background coroutine
        val app = java.io.File("src/main/java/helium314/keyboard/latin/App.kt").readText()
        assertTrue(Regex("""Privacy\.appLock\(this@App\)\.prime\(\)""").containsMatchIn(app))
    }
}
