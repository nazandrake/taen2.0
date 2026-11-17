// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every writer of the lock record against every writer: two instances share one store (as the unlock screen and the
 * settings do). Thread A is stopped right after its k-th read of the record, so that thread B runs in the middle of A's step; whatever
 * the interleaving, the answers of both and the stored record must be those of A then B, or of B then A (no lost update).
 */
class AppLockConcurrencyTest {
    private val now = 1_000_000L
    private val fastKdf = ExportCodec.Kdf(memoryKiB = 64, iterations = 1, lanes = 1)
    private val pin = "482915".toCharArray()
    private val wrong = "482916".toCharArray()
    private val pins = mapOf("A" to "111111", "B" to "222222")

    /** Waits (after reading) the first time the thread named [holdThread] reaches its [nth] read, until [release] or [maxMillis]. */
    private class Stall(private val inner: MemoryLockStorage, private val holdThread: String, private val nth: Int, private val maxMillis: Long) : LockStorage {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val reads = AtomicInteger()
        override fun load(): LockLoad {
            val read = inner.load()
            if (Thread.currentThread().name == holdThread && reads.incrementAndGet() == nth) {
                arrived.countDown()
                release.await(maxMillis, TimeUnit.MILLISECONDS)
            }
            return read
        }
        override fun save(record: LockRecord?) = inner.save(record)
    }

    private class Writer(val name: String, val run: (AppLock, String) -> String)

    private fun answer(v: AppLock.Verify) = when (v) {
        is AppLock.Verify.Wrong -> "Wrong(${v.failedAttempts})"
        is AppLock.Verify.Wait -> "Wait"
        else -> v.javaClass.simpleName
    }

    private val writers = listOf(
        Writer("verifyWrong") { l, _ -> answer(l.verify(wrong)) },
        Writer("verifyRight") { l, _ -> answer(l.verify(pin)) },
        Writer("changePin") { l, who -> l.changePin(pin, pins.getValue(who).toCharArray()).name },
        Writer("disable") { l, _ -> l.disable(pin).name },
        Writer("enable") { l, who -> l.enable(pins.getValue(who).toCharArray()).name },
        Writer("setTimeout") { l, who -> l.setTimeoutMinutes(if (who == "A") 5 else 15).toString() },
        Writer("cycleTimeout") { l, _ -> l.cycleTimeoutMinutes().toString() },
    )

    /** The stored record as the outcome sees it: whether it exists, the timeout, the count and which of the PINs is the stored one. */
    private fun state(storage: MemoryLockStorage): String {
        val r = storage.record ?: return storage.content.javaClass.simpleName
        val candidates = listOf(pin) + pins.values.map { it.toCharArray() }
        val which = candidates.firstOrNull { PinHasher(fastKdf).hash(it, r.salt, r.kdf).contentEquals(r.hash) }?.let { String(it) } ?: "?"
        return "pin=$which timeout=${r.timeoutMinutes} failed=${r.failedAttempts}"
    }

    private class Env(val storage: MemoryLockStorage, val a: AppLock, val b: AppLock)

    private fun env(present: Boolean, wrap: (MemoryLockStorage) -> LockStorage = { it }): Env {
        val storage = MemoryLockStorage()
        if (present) assertEquals(AppLock.SetResult.OK, AppLock(storage, { now }, PinHasher(fastKdf), onMainThread = notMainThread).enable(pin))
        val shared = wrap(storage)
        val a = AppLock(shared, { now }, PinHasher(fastKdf), onMainThread = notMainThread)
        val b = AppLock(shared, { now }, PinHasher(fastKdf), onMainThread = notMainThread)
        if (present) for (l in listOf(a, b)) assertEquals(AppLock.Verify.Ok, l.verify(pin)) // both unlocked: the timeout may change
        return Env(storage, a, b)
    }

    private fun serial(present: Boolean, first: Pair<Writer, String>, second: Pair<Writer, String>): Triple<String, String, String> {
        val e = env(present)
        val lockOf = { who: String -> if (who == "A") e.a else e.b }
        val r1 = first.first.run(lockOf(first.second), first.second)
        val r2 = second.first.run(lockOf(second.second), second.second)
        return if (first.second == "A") Triple(r1, r2, state(e.storage)) else Triple(r2, r1, state(e.storage))
    }

    private fun concurrent(present: Boolean, wa: Writer, wb: Writer, nth: Int): Triple<String, String, String> {
        lateinit var stall: Stall
        val e = env(present) { Stall(it, "A", nth, 100).also { s -> stall = s } } // only reads made by the thread named A count
        var ra = ""
        var rb = ""
        val ta = Thread({ ra = wa.run(e.a, "A") }, "A")
        ta.start()
        while (!stall.arrived.await(5, TimeUnit.MILLISECONDS) && ta.isAlive) Unit
        val tb = Thread({ rb = wb.run(e.b, "B") }, "B")
        tb.start()
        tb.join(10_000)
        stall.release.countDown()
        ta.join(10_000)
        assertTrue(!ta.isAlive && !tb.isAlive, "a writer did not finish (dead lock?)")
        return Triple(ra, rb, state(e.storage))
    }

    @Test fun everyPairOfWritersEndsAsOneAfterTheOtherInEveryInterleaving() {
        val failures = ArrayList<String>()
        var runs = 0
        for (present in listOf(true, false)) for (wa in writers) for (wb in writers) for (nth in 1..3) {
            val allowed = setOf(serial(present, wa to "A", wb to "B"), serial(present, wb to "B", wa to "A"))
            val got = concurrent(present, wa, wb, nth)
            runs++
            if (got !in allowed) failures += "lock ${if (present) "set" else "not set"}, A=${wa.name} stopped after read $nth, B=${wb.name}: got $got, allowed $allowed"
        }
        assertEquals(2 * writers.size * writers.size * 3, runs)
        assertEquals(emptyList(), failures)
    }
}
