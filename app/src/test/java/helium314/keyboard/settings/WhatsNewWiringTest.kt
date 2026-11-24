// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The settings screen runs the "What's new" check when it opens, shows the box only for an update, and its dismissal stores the version. Each case fails
 * when SettingsActivity stops calling WhatsNew.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class])
class WhatsNewWiringTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val code = BuildConfig.VERSION_CODE

    @Before fun before() { Privacy.unlock(ctx); Privacy.forgetCachedState(); ctx.prefs().edit { remove(Settings.PREF_WHATS_NEW_SHOWN) } }
    @After fun after() { ctx.prefs().edit { remove(Settings.PREF_WHATS_NEW_SHOWN) }; Privacy.unlock(ctx); Privacy.forgetCachedState() }

    private fun installTimes(first: Long, last: Long) {
        val info = shadowOf(ctx.packageManager).getInternalMutablePackageInfo(ctx.packageName)
        info.firstInstallTime = first
        info.lastUpdateTime = last
    }

    private fun open(): SettingsActivity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

    private fun stored(): Int? = ctx.prefs().takeIf { it.contains(Settings.PREF_WHATS_NEW_SHOWN) }?.getInt(Settings.PREF_WHATS_NEW_SHOWN, -1)

    @Test fun theFirstOpeningAfterAnUpdateHasTheNotesAndTheDismissalStoresTheVersion() {
        installTimes(1_000, 2_000) // updated: no stored version yet (what 2.0.2 left)
        val first = open()
        val notes = assertNotNull(first.whatsNewNotes, "the settings must run the check on opening")
        assertTrue(notes.isNotBlank())
        assertNull(stored(), "showing the box stores nothing")
        first.dismissWhatsNew()
        assertEquals(code, stored())
        assertNull(first.whatsNewNotes)
        // the next opening: no box
        assertNull(open().whatsNewNotes)
        assertEquals(code, stored())
    }

    @Test fun aFreshInstallOpensWithoutTheBoxAndRemembersTheVersion() {
        installTimes(5_000, 5_000)
        assertNull(open().whatsNewNotes)
        assertEquals(code, stored(), "the check ran and stored the version silently")
    }

    @Test fun aLowerStoredVersionShowsTheBoxAndAHigherOneDoesNot() {
        installTimes(5_000, 5_000)
        ctx.prefs().edit { putInt(Settings.PREF_WHATS_NEW_SHOWN, code - 1) }
        assertNotNull(open().whatsNewNotes)
        ctx.prefs().edit { putInt(Settings.PREF_WHATS_NEW_SHOWN, code + 1) }
        assertNull(open().whatsNewNotes)
        assertEquals(code + 1, stored())
    }

    @Test fun theBoxIsComposedFromTheSettingsScreenAfterTheGuideAndTheWizard() {
        // the dialog itself is Compose; its call in the screen is pinned by text (no Compose UI test dependency)
        val screen = File("src/main/java/helium314/keyboard/settings/SettingsActivity.kt").readText()
        assertTrue(screen.contains("WhatsNewDialog("), "SettingsActivity composes WhatsNewDialog")
        assertTrue(screen.contains("dismissWhatsNew()"), "the dismissal goes through the activity")
        assertTrue(File("src/main/java/helium314/keyboard/settings/WhatsNewDialog.kt").isFile)
    }
}
