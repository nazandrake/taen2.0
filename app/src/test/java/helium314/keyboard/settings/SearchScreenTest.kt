// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** E4: the settings search finds Profiles, Self-destruct, Voice typing and App lock in English and in Bulgarian (the container is what SearchScreen filters). */
@RunWith(RobolectricTestRunner::class)
class SearchScreenTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun container(locale: Locale) = SettingsContainer(ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply { setLocale(locale) }))
    private fun keys(c: SettingsContainer, term: String) = c.filter(term).map { it.key }

    // ---- ----

    @Test fun profilesAreFoundInEnglish() {
        assertTrue(SettingsWithoutKey.PROFILES in keys(container(Locale.ENGLISH), "profiles"))
    }

    // ---- ----

    @Test fun theFourScreensAreFoundByTheirNamesInEnglish() {
        val c = container(Locale.ENGLISH)
        assertTrue(SettingsWithoutKey.PROFILES in keys(c, "profiles") && SettingsWithoutKey.PROFILES in keys(c, "templates"))
        assertTrue(SettingsWithoutKey.SELF_DESTRUCT in keys(c, "self-destruct") && SettingsWithoutKey.SELF_DESTRUCT in keys(c, "self"))
        assertTrue(SettingsWithoutKey.VOICE_TYPING in keys(c, "voice"))
        assertTrue(SettingsWithoutKey.APP_LOCK in keys(c, "app lock") && SettingsWithoutKey.APP_LOCK in keys(c, "lock"))
    }

    @Test fun theFourScreensAreFoundByTheirNamesInBulgarian() {
        val c = container(Locale("bg"))
        assertTrue(SettingsWithoutKey.PROFILES in keys(c, "профили") && SettingsWithoutKey.PROFILES in keys(c, "шаблони"))
        assertTrue(SettingsWithoutKey.SELF_DESTRUCT in keys(c, "самоунищожение"))
        assertTrue(SettingsWithoutKey.VOICE_TYPING in keys(c, "гласово"))
        assertTrue(SettingsWithoutKey.APP_LOCK in keys(c, "заключване"))
    }

    @Test fun aTermThatMatchesNothingFindsNoneOfThem() { // negative control with the positive ones above
        val c = container(Locale.ENGLISH)
        val taen = setOf(SettingsWithoutKey.PROFILES, SettingsWithoutKey.SELF_DESTRUCT, SettingsWithoutKey.VOICE_TYPING, SettingsWithoutKey.APP_LOCK)
        assertEquals(emptySet(), keys(c, "zzzz").toSet() intersect taen)
        assertEquals(setOf(SettingsWithoutKey.PROFILES), keys(c, "profiles").toSet() intersect taen)
    }
}
