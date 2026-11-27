// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

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
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The per-app incognito list lives in the credential-encrypted preferences. When that store is damaged or its key is lost, the list is gone: every
 * app then counts as incognito until the user enters the list again or dismisses the notice. A fresh install (no store ever) keeps the normal default.
 */
@RunWith(RobolectricTestRunner::class)
class IncognitoListLostTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dir get() = File(ctx.dataDir, "no_backup/taen")
    private val file get() = File(dir, "prefs_ce.enc")

    @Before fun before() { AppStartWork.finishEarlier(); Privacy.unlock(ctx); Privacy.forgetCachedState(); file.delete(); ShadowAndroidKeyVault.ceUnwrapError = null }
    @After fun after() { ShadowAndroidKeyVault.ceUnwrapError = null; dir.setWritable(true); Privacy.unlock(ctx); Privacy.forgetCachedState() }

    private fun field(pkg: String) = InputAttributes(EditorInfo().apply { packageName = pkg; inputType = EditorInfo.TYPE_CLASS_TEXT }, false, ctx.packageName)
    private fun incognito(pkg: String) = SettingsValues.decidePrivacy(ctx, ctx.prefs(), field(pkg), false).incognito

    private fun listed() {
        assertTrue(ctx.protectedPrefs().edit().putString(Settings.PREF_INCOGNITO_APPS, "org.example.bank").commit())
        Privacy.forgetCachedState()
        // positive controls: the healthy list puts the listed app in incognito and leaves another one out
        assertTrue(incognito("org.example.bank"))
        assertFalse(incognito("org.example.chat"))
        Privacy.forgetCachedState()
    }

    private fun damage() {
        val bytes = file.readBytes()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 1).toByte() // the authentication tag no longer matches
        file.writeBytes(bytes)
    }

    @Test fun aDamagedStoreMakesEveryAppIncognito() {
        listed()
        damage()
        assertTrue(incognito("org.example.chat"), "the list is lost: an app that was not on it is incognito now")
        assertTrue(incognito("org.example.bank"))
    }

    // ---- pass 2 ----

    private fun assertLost() {
        for (pkg in listOf("org.example.chat", "org.example.bank", "org.example.other"))
            assertTrue(incognito(pkg), pkg)
        assertTrue(Settings.isIncognitoListLost(ctx.protectedPrefs()), "the loss is recorded")
        assertTrue(ctx.protectedPrefs().isReadable(), "the store itself is readable again: only the list is unknown")
    }

    @Test fun aDamagedStoreIsRecordedAndStaysLostAfterARestart() {
        listed()
        damage()
        assertLost()
        Privacy.forgetCachedState() // a new process: the mark was written, not only held in memory
        assertTrue(file.exists())
        assertLost()
    }

    @Test fun aLostKeyMakesEveryAppIncognitoToo() {
        listed()
        Privacy.keyVault().deleteKey(Tier.CE) // the Keystore key is gone: the data key cannot be unwrapped, a new one starts and the store fails its tag
        assertLost()
        Privacy.forgetCachedState()
        assertLost()
    }

    @Test fun enteringTheListAgainEndsTheLoss() {
        listed()
        damage()
        assertLost()
        Settings.setIncognitoApps(ctx, setOf("org.example.bank"))
        assertFalse(Settings.isIncognitoListLost(ctx.protectedPrefs()))
        assertFalse(incognito("org.example.chat"))
        assertTrue(incognito("org.example.bank"), "positive control: the new list decides")
        Privacy.forgetCachedState() // and it was stored
        assertFalse(incognito("org.example.chat"))
        assertTrue(incognito("org.example.bank"))
    }

    @Test fun dismissingTheNoticeEndsTheLossWithAnEmptyList() {
        listed()
        damage()
        assertLost()
        assertTrue(Settings.dismissIncognitoListLost(ctx))
        assertFalse(Settings.isIncognitoListLost(ctx.protectedPrefs()))
        assertFalse(incognito("org.example.chat"))
        assertFalse(incognito("org.example.bank"), "the old list is gone: nothing is listed now")
        Privacy.forgetCachedState()
        assertFalse(incognito("org.example.chat"))
    }

    @Test fun aFreshInstallKeepsTheNormalDefault() {
        assertFalse(file.exists())
        assertTrue(ctx.protectedPrefs().isReadable(), "positive control: the store was opened")
        assertFalse(Settings.isIncognitoListLost(ctx.protectedPrefs()))
        assertFalse(incognito("org.example.chat"))
        assertFalse(file.exists(), "no mark was written for a store that never existed")
    }

    @Test fun aTemporaryErrorIsNotALoss() {
        listed()
        val stored = file.readBytes()
        ShadowAndroidKeyVault.ceUnwrapError = VaultUnavailableException("the keystore is busy")
        try {
            assertTrue(incognito("org.example.chat"), "unreadable: every app is incognito meanwhile")
        } finally {
            ShadowAndroidKeyVault.ceUnwrapError = null
        }
        assertContentEquals(stored, file.readBytes(), "the store was not replaced")
        assertFalse(Settings.isIncognitoListLost(ctx.protectedPrefs()))
        assertFalse(incognito("org.example.chat"))
        assertTrue(incognito("org.example.bank"))
    }

    @Test fun aLossThatCannotBeWrittenIsLostAgainAtTheNextStart() {
        listed()
        damage()
        val damaged = file.readBytes()
        assertTrue(dir.setWritable(false)) // the mark cannot be written: the damaged file stays
        try {
            assertLost()
            Privacy.forgetCachedState()
            assertContentEquals(damaged, file.readBytes(), "the damaged file is still the one on disk")
            assertLost()
        } finally {
            dir.setWritable(true)
        }
    }

    @Test fun aLostListMovesNoLanguageOfTheEarlierFormat() {
        listed()
        damage()
        assertTrue(ctx.prefs().edit().putString(AppLanguageMemory.LEGACY_PREFIX + "org.example.chat", "bg").commit())
        Privacy.openAtStart(ctx)
        assertTrue(ctx.prefs().all.keys.none { it.startsWith(AppLanguageMemory.LEGACY_PREFIX) }, "the old entry is deleted")
        assertNull(AppLanguageMemory.recall(ctx.protectedPrefs(), "org.example.chat"), "and not moved: the app counts as incognito")
        // positive control: once the list is known again, an old entry of an app that is not on it moves
        Settings.setIncognitoApps(ctx, setOf("org.example.bank"))
        assertTrue(ctx.prefs().edit().putString(AppLanguageMemory.LEGACY_PREFIX + "org.example.chat", "bg").commit())
        Privacy.openAtStart(ctx)
        assertEquals("bg", AppLanguageMemory.recall(ctx.protectedPrefs(), "org.example.chat"))
    }
}
