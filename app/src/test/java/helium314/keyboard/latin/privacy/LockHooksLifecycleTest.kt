// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.ShadowFacilitator2
import helium314.keyboard.latin.ShadowKeyboardSwitcher
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lock hooks that the keyboard service registers (Vim, navigation, next word, voice, media panel) hold the service and its views. They are
 * removed when the service is destroyed, so a destroyed service is neither kept in memory nor run at the next screen lock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class, ShadowFacilitator2::class])
class LockHooksLifecycleTest {
    private val serviceHooks = setOf("vim", "navigation", "nextword", "voice")

    @Before fun before() { Privacy.forgetCachedState() }
    @After fun after() { Privacy.unlock(androidx.test.core.app.ApplicationProvider.getApplicationContext()); Privacy.forgetCachedState() }

    // the test shadow of the input method service has no Service shadow, so the last line of onDestroy (super.onDestroy) cannot run here (as in VimWiringTest)
    private fun destroy(ime: LatinIME) = try { ime.onDestroy() } catch (_: ClassCastException) { }

    @Test fun theServiceRemovesItsLockHooksWhenItIsDestroyed() {
        val ime = Robolectric.setupService(LatinIME::class.java)
        assertTrue(Privacy.lockHookNames().containsAll(serviceHooks), Privacy.lockHookNames().toString()) // positive control: registered while it runs
        destroy(ime)
        assertEquals(emptySet(), Privacy.lockHookNames() intersect serviceHooks)
    }

    @Test fun aHookOfANewerOwnerWithTheSameNameStays() {
        val older: () -> Unit = {}
        val newer: () -> Unit = {}
        Privacy.onLock("probe", older)
        Privacy.onLock("probe", newer) // a new service registered before the old one ended
        Privacy.removeOnLock("probe", older)
        assertTrue("probe" in Privacy.lockHookNames())
        Privacy.removeOnLock("probe", newer)
        assertTrue("probe" !in Privacy.lockHookNames())
    }

    @Test fun theAppWideHooksStayWhenTheServiceEnds() {
        val ime = Robolectric.setupService(LatinIME::class.java)
        Privacy.appLock(ime) // the app lock registers its hook for the process, not for the service
        assertTrue("applock" in Privacy.lockHookNames())
        destroy(ime)
        assertTrue("applock" in Privacy.lockHookNames())
    }
}
