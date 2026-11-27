// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import java.io.File
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Privacy is one object per process, but every unit test has its own application and its own data directory. A test that does not call
 * forgetCachedState leaves the app lock and the data keys of its application behind, and the start of the next application (its
 * background start-up work reads the app lock) must not meet them: a store of the new directory would then be written under the data key
 * of the old one and read as damaged. The two tests run one after the other, in this order.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(RobolectricTestRunner::class)
class AppStartIsolationTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val storeDir get() = File(ctx.createDeviceProtectedStorageContext().noBackupFilesDir, "taen")

    companion object {
        /** The directory of the first test, which does not clean up after itself on purpose. */
        var firstTestDir: File? = null
    }

    @Test fun a1_aTestThatLeavesItsAppLockAndKeysBehind() {
        Privacy.appLock(ctx)
        ctx.prefs().edit().putInt(Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, 120).commit()
        assertTrue(File(storeDir, "dek_de.bin").isFile, "positive control: the first test's own keys are in its own directory")
        firstTestDir = storeDir
    }

    @Test fun a2_theNextApplicationStartsWithNothingOfTheFirst() {
        assertNotEquals(firstTestDir, storeDir, "positive control: the tests have separate application directories")
        assertEquals(true, ctx.prefs().edit().putInt(Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, 7).commit())
        assertTrue(File(storeDir, "dek_de.bin").isFile, "the data key of this test is in this test's directory")
        assertTrue(File(storeDir, "prefs_de.enc").isFile)
        Privacy.forgetCachedState() // the process starts again: the files are the same
        assertEquals(7, ctx.prefs().getInt(Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, -1), "the stored value is read back, the store is not damaged")
    }
}
