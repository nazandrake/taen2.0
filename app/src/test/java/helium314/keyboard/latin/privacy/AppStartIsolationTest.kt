// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The start work of one test's application (App.kt: the app lock is read in the background) must not arrive after the next test's
 * application has started: it would put the data key of the earlier directory into the Privacy cache that the next test uses, and a
 * settings store written under it reads as damaged and starts again with the strictest values. The first test leaves such a call pending
 * (held back on the start thread), the second one runs after it in the same JVM and is the next application. The tests run in this order.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(RobolectricTestRunner::class)
class AppStartIsolationTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val storeDir get() = File(ctx.createDeviceProtectedStorageContext().noBackupFilesDir, "taen")

    companion object {
        var firstDir: File? = null
        var late: AppStartWork.Late? = null
    }

    @Test fun a1_theStartWorkOfThisApplicationIsStillPending() {
        assertEquals(true, ctx.prefs().edit().putInt(Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, 5).commit())
        assertTrue(File(storeDir, "dek_de.bin").isFile, "positive control: the keys of this application are in its own directory")
        firstDir = storeDir
        val application = ctx
        late = AppStartWork.holdBack { Privacy.appLock(application).prime() } // the call that App.onCreate makes in the background
    }

    @Test fun a2_theNextApplicationReadsItsOwnLoosenedSettings() {
        assertNotEquals(firstDir, storeDir, "positive control: the two applications have separate directories")
        val pending = late!!
        val finishedBeforeThisApplicationStarted = pending.ran
        Privacy.forgetCachedState() // as a test does in its setup
        pending.release() // what the late start work would do now; it has run already if the next application waited for it
        assertTrue(pending.ran, "positive control: the call of the earlier application ran")

        assertEquals(true, ctx.prefs().edit().putInt(Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, 120).commit())
        Privacy.forgetCachedState() // the process starts again: the files are the same
        assertEquals(120 * 60_000L, ClipboardDao.limitsFrom(ctx).retentionMillis, "the loosened retention is read back, the store is not reset")
        assertTrue(finishedBeforeThisApplicationStarted, "the start work of the earlier application had finished before this application started")
    }

    /** Reachability: the background work that the real App.onCreate launches goes through the harness, and only that work does. */
    @Test fun b1_theWorkOfOnCreateRunsOnTheStartThread() {
        assertTrue(AppStartWork.dispatchedByCurrentApplication >= 1, "App.onCreate handed its background work to the start dispatcher")
        assertNull(AppStartWork.starting.get(), "nothing is redirected once onCreate has returned")
        val worker = runBlocking(Dispatchers.Default) { Thread.currentThread().name }
        assertNotEquals("taen-test-app-start", worker, "Dispatchers.Default outside onCreate is the real one")
        AppStartWork.finishEarlier() // returns: the work of this application is not stuck
    }
}
