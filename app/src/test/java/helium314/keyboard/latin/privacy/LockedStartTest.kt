// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.app.KeyguardManager
import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsValues
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The process starts while the phone is locked after the first unlock (App.onCreate opens the credential-encrypted preferences before the keyboard
 * service can close them). The Keystore then refuses the credential-encrypted key, and whatever error it reports, that means "locked, try later":
 * no key or store is replaced, nothing is marked lost, the decision is restrictive meanwhile, and after the unlock everything is as it was.
 */
@RunWith(RobolectricTestRunner::class)
class LockedStartTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dir get() = File(ctx.dataDir, "no_backup/taen")
    private val keyguard get() = shadowOf(ctx.getSystemService(KeyguardManager::class.java))

    @Before fun before() { AppStartWork.finishEarlier(); Privacy.unlock(ctx); Privacy.forgetCachedState(); ShadowAndroidKeyVault.ceUnwrapError = null; keyguard.setIsDeviceLocked(false) }
    @After fun after() { ShadowAndroidKeyVault.ceUnwrapError = null; ShadowAndroidKeyVault.ceUnwrapErrorOnce = null; keyguard.setIsDeviceLocked(false); Privacy.unlock(ctx); Privacy.forgetCachedState() }

    private fun field(pkg: String) = InputAttributes(EditorInfo().apply { packageName = pkg; inputType = EditorInfo.TYPE_CLASS_TEXT }, false, ctx.packageName)
    private fun incognito(pkg: String, locked: Boolean = false) = SettingsValues.decidePrivacy(ctx, ctx.prefs(), field(pkg), locked).incognito

    private fun stored(): Pair<ByteArray, ByteArray> {
        assertTrue(ctx.protectedPrefs().edit().putString(Settings.PREF_INCOGNITO_APPS, "org.example.bank").commit())
        Privacy.forgetCachedState() // the process ends; the next one starts with nothing in memory
        return File(dir, "dek_ce.bin").readBytes() to File(dir, "prefs_ce.enc").readBytes()
    }

    /** A Keystore that reports the refused key of a locked device as a lost key (the worst case: the one error that may start a new key). */
    private fun lostKeyError() = translateKeystoreError(java.security.UnrecoverableKeyException("refused"))
    private fun refuseAsLost() { ShadowAndroidKeyVault.ceUnwrapError = lostKeyError() }

    @Test fun aStartWhileLockedReplacesNothingAndTheUnlockFindsEverything() {
        val (key, prefs) = stored()
        keyguard.setIsDeviceLocked(true)
        refuseAsLost()
        Privacy.openAtStart(ctx) // what App.onCreate calls
        assertFalse(ctx.protectedPrefs().isReadable(), "locked: the store is unavailable, not reset")
        assertTrue(incognito("org.example.chat", locked = true))
        assertTrue(incognito("org.example.chat", locked = false), "even a caller that thinks the device is unlocked gets the restrictive answer")
        assertContentEquals(key, File(dir, "dek_ce.bin").readBytes(), "the wrapped key was not replaced")
        assertContentEquals(prefs, File(dir, "prefs_ce.enc").readBytes(), "the preferences were not replaced")

        keyguard.setIsDeviceLocked(false) // the user unlocks; the Keystore works again
        ShadowAndroidKeyVault.ceUnwrapError = null
        assertTrue(ctx.protectedPrefs().isReadable())
        assertFalse(Settings.isIncognitoListLost(ctx.protectedPrefs()), "nothing was marked lost")
        assertEquals("org.example.bank", ctx.protectedPrefs().getString(Settings.PREF_INCOGNITO_APPS, null))
        assertFalse(incognito("org.example.chat"))
        assertTrue(incognito("org.example.bank"))
        assertContentEquals(key, File(dir, "dek_ce.bin").readBytes())
    }

    @Test fun theDeviceProtectedTierStillStartsWhileLocked() { // its key needs no unlocked device: a first start on the lock screen makes it
        keyguard.setIsDeviceLocked(true)
        val deKey = File(ctx.createDeviceProtectedStorageContext().noBackupFilesDir, "taen/dek_de.bin")
        deKey.delete()
        File(dir, "dek_ce.bin").delete()
        File(dir, "prefs_ce.enc").delete()
        Privacy.forgetCachedState()
        assertTrue(ctx.prefs().edit().putBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, true).commit())
        assertTrue(deKey.isFile)
        // the contrast: the credential-encrypted tier makes no first key while locked
        val ceKey = File(dir, "dek_ce.bin")
        assertFalse(ctx.protectedPrefs().edit().putString(Settings.PREF_INCOGNITO_APPS, "org.example.bank").commit())
        assertFalse(ceKey.exists())
    }

    @Test fun theSameErrorOnAnUnlockedDeviceIsALossThatFailsClosed() { // positive control: the lock state is what kept the key above
        val (key, _) = stored()
        ShadowAndroidKeyVault.ceUnwrapErrorOnce = lostKeyError() // the same error, once: the new key that follows works
        Privacy.openAtStart(ctx)
        assertTrue(ctx.protectedPrefs().isReadable())
        assertNotEquals(key.toList(), File(dir, "dek_ce.bin").readBytes().toList(), "a new data key started")
        assertTrue(Settings.isIncognitoListLost(ctx.protectedPrefs()), "and the lost list is recorded")
        assertTrue(incognito("org.example.chat"))
    }
}
