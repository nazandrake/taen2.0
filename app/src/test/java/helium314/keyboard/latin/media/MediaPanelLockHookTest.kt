// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.media

import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.MediaPanelView
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.ShadowFacilitator2
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Settings
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The picture panel of the running keyboard registers a lock hook; the service removes it when it ends, so the panel and the service are not kept. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class, ShadowFacilitator2::class])
class MediaPanelLockHookTest {
    @Test fun thePanelsLockHookGoesWithTheService() {
        Privacy.forgetCachedState()
        ClipboardDao.forgetInstance()
        ShadowInputMethodService.reset()
        val ime = Robolectric.setupService(LatinIME::class.java)
        Settings.init(ime)
        KeyboardSwitcher.getInstance().onCreateInputView(ime, true)
        try {
            assertNotNull(KeyboardSwitcher.getInstance().mediaPanelView)
            assertTrue(Privacy.lockHookNames().containsAll(setOf("media-panel", "vim", "navigation")), Privacy.lockHookNames().toString()) // positive control
            try { ime.onDestroy() } catch (_: ClassCastException) { } // the test shadow has no Service shadow: super.onDestroy cannot run here
            for (name in listOf("media-panel", "vim", "navigation", "nextword", "voice")) assertFalse(name in Privacy.lockHookNames(), name)
        } finally {
            MediaTokens.revokeAll()
            Privacy.unlock(ime)
            Privacy.forgetCachedState()
            ClipboardDao.forgetInstance()
            ShadowInputMethodService.reset()
        }
    }
}
