// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.privacy.ClipboardLimits
import helium314.keyboard.latin.profiles.ProfileKeys
import helium314.keyboard.latin.settings.Settings
import java.util.Locale
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/** The retention setting offers the choices of [ClipboardLimits] (5 minutes to 7 days, then Forever) with readable labels. */
@RunWith(RobolectricTestRunner::class)
class ClipboardRetentionChoicesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun inLocale(locale: Locale) = ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply { setLocale(locale) })

    @Test fun theChoicesAndTheirEnglishLabels() {
        val choices = clipboardRetentionChoices(inLocale(Locale.ENGLISH))
        assertEquals(ClipboardLimits.RETENTION_CHOICES_MINUTES, choices.map { it.second })
        assertEquals(listOf("5 min", "15 min", "30 min", "1 h", "2 h", "6 h", "12 h", "1 day", "3 days", "7 days", "Forever"), choices.map { it.first })
    }

    @Test fun foreverInBulgarian() {
        assertEquals("Завинаги", clipboardRetentionChoices(inLocale(Locale("bg"))).last().first)
    }

    @Test fun aProfileCanKeepForeverButNeverLoosensToIt() {
        val key = Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME
        assertEquals(ClipboardLimits.FOREVER_MINUTES, ProfileKeys.validate(key, ClipboardLimits.FOREVER_MINUTES), "Forever is a valid value")
        assertEquals(null, ProfileKeys.validate(key, ClipboardLimits.FOREVER_MINUTES - 1), "a value between 7 days and Forever is not")
        assertEquals(60, ProfileKeys.stricter(key, 60, ClipboardLimits.FOREVER_MINUTES), "a profile cannot loosen 1 h to Forever")
        assertEquals(60, ProfileKeys.stricter(key, ClipboardLimits.FOREVER_MINUTES, 60), "but can tighten Forever to 1 h")
    }
}
