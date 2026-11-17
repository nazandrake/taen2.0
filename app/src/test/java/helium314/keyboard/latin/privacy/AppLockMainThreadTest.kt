// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.os.Looper
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * the "off the main thread" rule is owned by AppLock itself. Every writer fails closed on the thread of the main looper
 * (IllegalStateException before the record is read or written) and works on any other thread. Under Robolectric the test thread is the main thread.
 */
@RunWith(RobolectricTestRunner::class)
class AppLockMainThreadTest {
    private val fastKdf = ExportCodec.Kdf(memoryKiB = 64, iterations = 1, lanes = 1)
    private val pin = "482915".toCharArray()

    /** Counts every touch of the record. */
    private class CountingStorage(private val inner: MemoryLockStorage) : LockStorage {
        var touches = 0
        override fun load(): LockLoad { touches++; return inner.load() }
        override fun save(record: LockRecord?): Boolean { touches++; return inner.save(record) }
    }

    private val memory = MemoryLockStorage()
    private val storage = CountingStorage(memory)
    private val lock = AppLock(storage, { 1_000_000L }, PinHasher(fastKdf)) // the app's own main-thread check

    private val writers: List<Pair<String, () -> Any?>> = listOf(
        "enable" to { lock.enable(pin) },
        "changePin" to { lock.changePin(pin, "135790".toCharArray()) },
        "disable" to { lock.disable(pin) },
        "verify" to { lock.verify(pin) },
        "setTimeoutMinutes" to { lock.setTimeoutMinutes(5) },
        "cycleTimeoutMinutes" to { lock.cycleTimeoutMinutes() },
    )

    @Test fun theTestThreadIsTheMainThread() { // the control for the cases below
        assertTrue(Looper.getMainLooper().isCurrentThread)
        assertTrue(AppLock.isMainThread())
        assertEquals(false, offMain { AppLock.isMainThread() })
    }

    @Test fun everyWriterFailsClosedOnTheMainThreadAndTouchesNothing() {
        offMain { assertEquals(AppLock.SetResult.OK, lock.enable(pin)) } // a lock exists, so a writer that proceeded would change or count something
        val before = memory.record!!
        val touchesBefore = storage.touches
        for ((name, call) in writers) {
            assertFailsWith<IllegalStateException>(name) { call() }
        }
        assertEquals(touchesBefore, storage.touches, "a refused writer must not read or write the record")
        assertEquals(before, memory.record) // same object: nothing was saved
        assertEquals(0, memory.record!!.failedAttempts)
    }

    @Test fun everyWriterWorksOnAnotherThread() {
        assertEquals(AppLock.SetResult.OK, offMain { lock.enable(pin) })
        assertEquals(AppLock.Verify.Ok, offMain { lock.verify(pin) })
        assertEquals(5, offMain { lock.cycleTimeoutMinutes() })
        assertEquals(true, offMain { lock.setTimeoutMinutes(15) })
        assertEquals(AppLock.SetResult.OK, offMain { lock.changePin(pin, "135790".toCharArray()) })
        assertEquals(AppLock.SetResult.OK, offMain { lock.disable("135790".toCharArray()) })
        assertEquals(LockLoad.None, memory.content)
    }
}
