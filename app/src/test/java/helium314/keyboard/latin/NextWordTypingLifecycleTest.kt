// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.os.Looper
import android.view.inputmethod.EditorInfo
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.latin.privacy.Privacy
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The service ends (LatinIME.onDestroy shuts the model thread down first) and the keyboard's own callbacks still reach the next-word glue:
 * the finish-input callbacks the framework sends while the service stops, the screen lock hook that stays registered with the process-wide
 * Privacy object, a second onDestroy. Each of them used to throw RejectedExecutionException on the main thread, which ends the process.
 * (The test shadow of InputMethodService cannot run the framework's own onDestroy, so the order is driven here call by call.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class, ShadowFacilitator2::class])
class NextWordTypingLifecycleTest {
    private fun withKeyboard(test: (LatinIME) -> Unit) {
        Privacy.forgetCachedState()
        ShadowInputMethodService.reset()
        val ime = Robolectric.setupService(LatinIME::class.java)
        try {
            test(ime)
        } finally {
            Privacy.unlock(ime)
            Privacy.forgetCachedState()
            ShadowInputMethodService.reset()
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The model thread of a service's next-word glue (private there): the one place a step of the model runs. */
    private fun workerOf(ime: LatinIME): ExecutorService =
        ime.mNextWord.javaClass.getDeclaredField("worker").apply { isAccessible = true }.get(ime.mNextWord) as ExecutorService

    // ---- pass 1 ---------------------------------------------------------------------------------------------------------

    @Test fun finishingTheInputViewWhileTheServiceStopsDoesNotThrow() = withKeyboard { ime ->
        ime.mNextWord.onDestroy() // what LatinIME.onDestroy does first
        assertTrue(workerOf(ime).isShutdown, "positive control: the model thread is gone, so a step queued on it would be rejected")
        val thrown = runCatching {
            ime.onFinishInputView(false) // what the framework sends while the service stops: onFieldChange on the glue
            idle()
        }.exceptionOrNull()
        assertNull(thrown, "nothing is thrown while the service stops: $thrown")
    }

    // ---- pass 2 ---------------------------------------------------------------------------------------------------------

    @Test fun aScreenLockAfterTheServiceWasDestroyedDoesNotThrow() = withKeyboard { ime ->
        Privacy.lock(ime) // positive control: with the model thread alive the hook runs on the main thread without a failure
        idle()
        assertTrue(Privacy.isLocked, "the lock ran")
        Privacy.unlock(ime)
        assertFalse(Privacy.isLocked)
        ime.mNextWord.onDestroy()
        Privacy.lock(ime) // the hook of the destroyed service is still registered with the process-wide Privacy object
        idle() // throws RejectedExecutionException from NextWordTyping.onLock before the fix
        assertTrue(Privacy.isLocked, "the lock ran after the destroy as well")
        Privacy.unlock(ime)
    }

    @Test fun aSecondDestroyAndEveryFieldCallbackAfterItDoNotThrow() = withKeyboard { ime ->
        ime.mNextWord.onDestroy()
        ime.mNextWord.onDestroy() // throws before the fix: it queues an unload on the shut-down thread
        ime.mNextWord.onFieldChange()
        ime.onFinishInput()
        ime.onStartInput(EditorInfo(), false)
        idle()
    }

    @Test fun aKeyboardStartedAfterTheOldOneWasDestroyedKeepsItsOwnLiveModelThread() = withKeyboard { old ->
        old.mNextWord.onDestroy()
        val next = Robolectric.setupService(LatinIME::class.java) // the process lives on and the system starts the service again
        Privacy.lock(next)
        idle() // the hook of the new service runs
        Privacy.unlock(next)
        next.onFinishInputView(false) // and the new service's own thread still accepts work
        idle()
        old.onFinishInputView(false) // a late callback of the old service
        idle()
        assertTrue(Privacy.isLocked.not(), "the new service's lock hook ran and the unlock reopened the tier")
        // the live thread: a step submitted after all of it really runs on the new service's own thread, and the old one's is shut down
        assertTrue(workerOf(old).isShutdown)
        assertFalse(workerOf(next).isShutdown)
        val ran = CountDownLatch(1)
        val thread = AtomicReference<String>()
        workerOf(next).execute { thread.set(Thread.currentThread().name); ran.countDown() }
        assertTrue(ran.await(5, TimeUnit.SECONDS), "the step submitted after the events ran")
        assertEquals("taen-nextword", thread.get())
        assertFailsWith<RejectedExecutionException> { workerOf(old).execute { } } // the control: the old thread takes nothing
    }
}
