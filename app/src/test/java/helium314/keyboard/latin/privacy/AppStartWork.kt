// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue

/**
 * The background work that App.onCreate launches (App.kt, on Dispatchers.Default) outlives the test that started it. Its last step, the
 * app lock, reads the data key and the security store of that test's application, and when it lands after the next test's application was
 * reset, the Privacy cache of the next test holds the key of another directory. So in the unit tests the work of onCreate runs on one thread
 * of its own (ShadowDispatchers hands it out only to the thread that runs onCreate, ShadowApp), and the next application waits for it, in
 * order, before it resets Privacy: nothing of an earlier application is left to arrive late.
 */
object AppStartWork {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "taen-test-app-start").apply { isDaemon = true } }
    private val dispatched = AtomicInteger()
    private val held = CopyOnWriteArrayList<Late>()
    private const val BOUND_SECONDS = 60L // a hang fails with a message; nothing waits for it to pass

    // The dispatcher stays usable for the whole JVM: an object that kept it from onCreate must not meet a closed executor.
    private val dispatcher: CoroutineDispatcher = Executor { r -> dispatched.incrementAndGet(); executor.execute(r) }.asCoroutineDispatcher()

    /** Set on the thread that runs onCreate: Dispatchers.Default answers with the start dispatcher there and with the real one everywhere else. */
    internal val starting = ThreadLocal<CoroutineDispatcher?>()

    /** Number of tasks that the onCreate of the current application handed to the start dispatcher. */
    val dispatchedByCurrentApplication: Int get() = dispatched.get()

    /** Runs the real onCreate with its background work on the start dispatcher. */
    internal fun start(onCreate: () -> Unit) {
        dispatched.set(0)
        starting.set(dispatcher)
        try { onCreate() } finally { starting.remove() }
    }

    /** Completes the start work of every earlier application, held-back work included, and returns when it is all done. */
    fun finishEarlier() {
        held.forEach { it.open() }
        held.clear()
        // one thread, in order: when this empty task has run, every task queued before it has finished
        executor.submit {}.get(BOUND_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * A call of the current application that is still pending when the next one starts, for a test that needs the late arrival. [Late.release] runs
     * it now; [finishEarlier] runs it too. It runs on the start thread, like the work of onCreate.
     */
    fun holdBack(work: () -> Unit): Late = Late(work).also { held.add(it); executor.execute(it::run) }

    class Late internal constructor(private val work: () -> Unit) {
        private val gate = CountDownLatch(1)
        private val finished = CountDownLatch(1)
        @Volatile var ran = false
            private set

        internal fun open() = gate.countDown()

        internal fun run() {
            assertTrue(gate.await(BOUND_SECONDS, TimeUnit.SECONDS), "the held-back call was never released")
            try { work(); ran = true } finally { finished.countDown() }
        }

        /** The held-back call runs now (nothing happens if it ran already) and this returns when it has finished. */
        fun release() {
            open()
            assertTrue(finished.await(BOUND_SECONDS, TimeUnit.SECONDS), "the held-back call did not finish")
        }
    }
}
