// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsValues
import helium314.keyboard.latin.utils.prefs
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real settings store (Privacy.prefs): when it is reset after a failed integrity check, the privacy switches start restrictive instead of
 * falling back to their permissive defaults, and a fresh install still starts with the defaults.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsResetWiringTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val file get() = File(ctx.createDeviceProtectedStorageContext().noBackupFilesDir, "taen/prefs_de.enc")

    @Before fun before() { Privacy.unlock(ctx); Privacy.forgetCachedState(); file.delete() }
    @After fun after() { Privacy.unlock(ctx); Privacy.forgetCachedState() }

    private fun values() = SettingsValues(ctx, ctx.prefs(), ctx.resources,
        InputAttributes(EditorInfo().apply { packageName = "org.example.chat"; inputType = EditorInfo.TYPE_CLASS_TEXT }, false, ctx.packageName))

    private fun loosened() {
        assertTrue(ctx.prefs().edit().putBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, false).putBoolean(Settings.PREF_KEY_USE_PERSONALIZED_DICTS, true)
            .putBoolean(Settings.PREF_ENABLE_CLIPBOARD_HISTORY, true).putBoolean(Settings.PREF_VOICE_TYPING, true)
            .putString(Settings.PREF_OVERLAY_MODE, "off").putInt(Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, 120).commit())
        Privacy.forgetCachedState()
    }

    private fun damage() {
        val bytes = file.readBytes()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 1).toByte() // the authentication tag no longer matches
        file.writeBytes(bytes)
    }

    private fun assertRestrictive(v: SettingsValues) {
        assertTrue(v.mIncognitoModeEnabled)
        assertFalse(v.mUsePersonalizedDicts)
        assertFalse(v.mClipboardHistoryEnabled)
        assertFalse(v.mShowsVoiceInputKey)
        assertEquals(OverlayMode.BLOCK, v.mOverlayMode)
        assertEquals(ClipboardLimits.MIN_RETENTION_MINUTES * 60_000L, ClipboardDao.limitsFrom(ctx).retentionMillis)
    }

    @Test fun aSettingsStoreThatFailsItsIntegrityCheckStartsRestrictiveAndStaysSoAfterARestart() {
        loosened()
        // positive control: the loosened values decide while the store is healthy
        var v = values()
        assertFalse(v.mIncognitoModeEnabled)
        assertTrue(v.mUsePersonalizedDicts)
        assertTrue(v.mClipboardHistoryEnabled)
        assertTrue(v.mShowsVoiceInputKey) // the voice key shows while loosened, so that "off" after the reset is a change and not the starting state
        assertEquals(OverlayMode.OFF, v.mOverlayMode)
        assertEquals(120 * 60_000L, ClipboardDao.limitsFrom(ctx).retentionMillis)
        Privacy.forgetCachedState()
        damage()
        assertRestrictive(values())
        Privacy.forgetCachedState() // a new process: the restrictive values were written, not only held in memory
        assertTrue(file.exists())
        assertRestrictive(values())
    }

    @Test fun aResetThatCannotBeWrittenLeavesTheDamagedFileAndTheNextStartIsRestrictiveToo() {
        loosened()
        Privacy.forgetCachedState()
        damage()
        val damaged = file.readBytes()
        val dir = file.parentFile!!
        assertTrue(dir.setWritable(false)) // no new file can be created: the write of the restrictive snapshot fails
        try {
            assertRestrictive(values())
            Privacy.forgetCachedState() // a new process before any edit
            assertTrue(damaged.contentEquals(file.readBytes()), "the damaged file is still the one on disk")
            assertRestrictive(values())
        } finally {
            dir.setWritable(true)
        }
    }

    @Test fun theUserLoosensAResetStoreOnPurpose() {
        loosened()
        damage()
        assertRestrictive(values())
        assertTrue(ctx.prefs().edit().putBoolean(Settings.PREF_KEY_USE_PERSONALIZED_DICTS, true).putBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, false).commit())
        Privacy.forgetCachedState()
        val v = values()
        assertTrue(v.mUsePersonalizedDicts)
        assertFalse(v.mIncognitoModeEnabled)
        assertFalse(v.mClipboardHistoryEnabled) // what the user did not change stays restrictive
    }

    @Test fun aFreshInstallStillStartsWithTheDefaults() { // positive control: no store, no reset
        assertFalse(file.exists())
        val v = values()
        assertFalse(v.mIncognitoModeEnabled)
        assertTrue(v.mUsePersonalizedDicts)
        assertTrue(v.mClipboardHistoryEnabled)
        assertEquals(60 * 60_000L, ClipboardDao.limitsFrom(ctx).retentionMillis)
    }
}
