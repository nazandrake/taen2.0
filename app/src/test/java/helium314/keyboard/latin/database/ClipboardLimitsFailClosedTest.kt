// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.privacy.ClipboardLimits
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.privacy.ShadowAndroidKeyVault
import helium314.keyboard.latin.privacy.VaultUnavailableException
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/** The clipboard retention time and the item count are read like the privacy switches: an unreadable settings store gives the strictest values the app offers. */
@RunWith(RobolectricTestRunner::class)
class ClipboardLimitsFailClosedTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun before() { Privacy.unlock(ctx); Privacy.forgetCachedState(); ShadowAndroidKeyVault.deUnwrapError = null }
    @After fun after() { ShadowAndroidKeyVault.deUnwrapError = null; Privacy.unlock(ctx); Privacy.forgetCachedState() }

    @Test fun unreadableSettingsGiveTheShortestRetentionAndTheSmallestCount() {
        assertEquals(true, ctx.prefs().edit().putInt(Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, 120).putInt(Settings.PREF_CLIPBOARD_HISTORY_MAX_ITEMS, 7).commit())
        Privacy.forgetCachedState()
        // positive control: the stored values decide while the store can be read
        assertEquals(120 * 60_000L, ClipboardDao.limitsFrom(ctx).retentionMillis)
        assertEquals(7, ClipboardDao.limitsFrom(ctx).maxItems)
        Privacy.forgetCachedState()
        ShadowAndroidKeyVault.deUnwrapError = VaultUnavailableException("the keystore is busy")
        assertEquals(ClipboardLimits.MIN_RETENTION_MINUTES * 60_000L, ClipboardDao.limitsFrom(ctx).retentionMillis)
        assertEquals(1, ClipboardDao.limitsFrom(ctx).maxItems)
    }
}
