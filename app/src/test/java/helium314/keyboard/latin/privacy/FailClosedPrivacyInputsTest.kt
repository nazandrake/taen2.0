// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.Context
import android.content.ContextWrapper
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.compat.isDeviceLocked
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.ScreenLockPolicy
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsValues
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The inputs of the privacy decision fail closed: an app list that cannot be read (a Keystore error of the credential-encrypted preferences) makes
 * every app incognito, and a lock state that the system cannot tell counts as locked.
 */
@RunWith(RobolectricTestRunner::class)
class FailClosedPrivacyInputsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun before() { Privacy.unlock(ctx); Privacy.forgetCachedState(); ShadowAndroidKeyVault.ceUnwrapError = null }
    @After fun after() { ShadowAndroidKeyVault.ceUnwrapError = null; Privacy.unlock(ctx); Privacy.forgetCachedState() }

    private fun field(pkg: String) = InputAttributes(EditorInfo().apply { packageName = pkg; inputType = EditorInfo.TYPE_CLASS_TEXT }, false, ctx.packageName)

    /** A context whose keyguard service cannot be reached (the system call fails). */
    private val noKeyguard = object : ContextWrapper(ctx) {
        override fun getSystemService(name: String): Any? = if (name == Context.KEYGUARD_SERVICE) null else super.getSystemService(name)
    }

    @Test fun anAppListThatCannotBeReadMakesEveryAppIncognito() {
        assertTrue(ctx.protectedPrefs().edit().putString(Settings.PREF_INCOGNITO_APPS, "org.example.bank").commit())
        Privacy.forgetCachedState() // a new process: the data key is unwrapped again
        ShadowAndroidKeyVault.ceUnwrapError = VaultUnavailableException("the keystore is busy")
        assertTrue(SettingsValues.decidePrivacy(ctx, ctx.prefs(), field("org.example.chat"), false).incognito)
    }

    @Test fun aKeyguardThatCannotBeAskedCountsAsLocked() {
        assertTrue(isDeviceLocked(noKeyguard))
    }

    // ---- pass 2 ----

    @Test fun theListDecidesAgainWhenItCanBeReadAndTheErrorNeverWidensIt() {
        assertTrue(ctx.protectedPrefs().edit().putString(Settings.PREF_INCOGNITO_APPS, "org.example.bank").commit())
        Privacy.forgetCachedState()
        // positive controls: a readable list keeps an app off it out of incognito and puts a listed app in it
        assertFalse(SettingsValues.decidePrivacy(ctx, ctx.prefs(), field("org.example.chat"), false).incognito)
        assertTrue(SettingsValues.decidePrivacy(ctx, ctx.prefs(), field("org.example.bank"), false).incognito)
        Privacy.forgetCachedState()
        ShadowAndroidKeyVault.ceUnwrapError = VaultUnavailableException("the keystore is busy")
        for (pkg in listOf("org.example.chat", "org.example.bank", ctx.packageName))
            assertTrue(SettingsValues.decidePrivacy(ctx, ctx.prefs(), field(pkg), false).incognito, pkg)
        ShadowAndroidKeyVault.ceUnwrapError = null // the error passes: the list decides again, nothing was lost
        assertFalse(SettingsValues.decidePrivacy(ctx, ctx.prefs(), field("org.example.chat"), false).incognito)
        assertTrue(SettingsValues.decidePrivacy(ctx, ctx.prefs(), field("org.example.bank"), false).incognito)
    }

    @Test fun thePolicyTreatsAnUnreadableListAsListingEveryApp() {
        val text = EditorInfo.TYPE_CLASS_TEXT
        assertFalse(PrivacyPolicy.decide(text, 0, "org.example.chat", false, emptySet(), false).incognito) // control
        assertTrue(PrivacyPolicy.decide(text, 0, "org.example.chat", false, emptySet(), false, appListReadable = false).incognito)
        assertTrue(PrivacyPolicy.decide(text, 0, null, false, emptySet(), false, appListReadable = false).incognito) // no package either
        assertFalse(PrivacyPolicy.decide(text, 0, "org.example.chat", false, emptySet(), false, appListReadable = false).passwordField) // nothing else changes
    }

    @Test fun aScreenLockThatCannotTellTheKeyguardStaysLocked() {
        ScreenLockPolicy.sync(noKeyguard) // the service starts and cannot ask the keyguard: it starts locked
        assertTrue(Privacy.isLocked)
        ScreenLockPolicy.onScreenOn(noKeyguard) // screen on without an answer: no unlock (only the user's presence opens it)
        assertTrue(Privacy.isLocked)
        ScreenLockPolicy.onUserPresent(ctx)
        assertFalse(Privacy.isLocked)
        ScreenLockPolicy.sync(ctx) // positive control: a keyguard that answers "not locked" leaves it open
        assertFalse(Privacy.isLocked)
        Privacy.lock(ctx)
        ScreenLockPolicy.onScreenOn(ctx) // and opens it at screen on
        assertFalse(Privacy.isLocked)
    }

    @Test fun aKeyguardThatAnswersIsBelieved() {
        assertFalse(isDeviceLocked(ctx)) // positive control for the restrictive answer above
    }
}
