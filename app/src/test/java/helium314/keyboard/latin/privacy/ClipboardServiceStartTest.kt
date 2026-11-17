// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Looper
import android.os.SystemClock
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.ShadowFacilitator2
import helium314.keyboard.latin.ShadowKeyboardSwitcher
import helium314.keyboard.latin.database.ClipboardDao
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * F1 (leak audit row D6b), P10: the clip that is on the system clipboard when the keyboard service starts is not taken into the
 * history. The service does not know in which field it was copied (a password field copy is not flagged sensitive), so only copies
 * seen while the keyboard runs, with the field known, are stored.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class, ShadowFacilitator2::class])
class ClipboardServiceStartTest {
    private fun clip(text: String): ClipData {
        SystemClock.setCurrentTimeMillis(System.currentTimeMillis()) // see CanaryAtRestTest.clip
        return ClipData.newPlainText("label", text)
    }

    @Test fun clipLeftOnTheClipboardBeforeServiceStartIsNotStored() {
        Privacy.forgetCachedState()
        ClipboardDao.forgetInstance()
        val app = Robolectric.buildService(LatinIME::class.java).get().applicationContext
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip("left from a password field pwqzx"))
        val ime = Robolectric.setupService(LatinIME::class.java) // the service starts with that clip on the clipboard
        try {
            val dao = assertNotNull(ClipboardDao.getInstance(ime))
            assertEquals(emptyList(), dao.getAll().map { it.text })

            // positive control: a copy made while the service runs is stored, so an empty history above is the start behaviour, not a dead store
            ime.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip("copied while running"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("copied while running"), dao.getAll().map { it.text })
        } finally {
            Privacy.unlock(ime)
            Privacy.forgetCachedState()
            ClipboardDao.forgetInstance()
        }
    }
}
