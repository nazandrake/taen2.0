// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.media

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CC BY 4.0, section 3(a)(1): the starter GIFs are shown with the name of the work, its author, the licence with its address and a note that
 * the files were changed. The text is the description of the setting that turns the starter set on, in English and in Bulgarian.
 */
@RunWith(RobolectricTestRunner::class)
class StarterAttributionTest {
    private fun text(language: String): String {
        val base: Context = ApplicationProvider.getApplicationContext()
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return base.createConfigurationContext(config).getString(R.string.media_starter_gif_summary)
    }

    private fun assertCredits(text: String, changed: List<String>) {
        for (part in listOf("Noto Animated Emoji", "Google", "CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/") + changed)
            assertTrue(part in text, "'$part' is in: $text")
    }

    @Test fun theEnglishTextCreditsTheWorkAndSaysItWasChanged() = assertCredits(text("en"), listOf("scaled", "re-encoded"))

    @Test fun theBulgarianTextCreditsTheWorkAndSaysItWasChanged() {
        val bg = text("bg")
        assertTrue(bg != text("en"), "positive control: the Bulgarian text is its own")
        assertCredits(bg, listOf("смалени", "прекодирани"))
    }
}
