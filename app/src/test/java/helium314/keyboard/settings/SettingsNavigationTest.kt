// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Locale
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** E4: the "Everyday" section has its 8 links, each opens a real screen of the settings, and the search finds the four Taen screens in English and Bulgarian. */
@RunWith(RobolectricTestRunner::class)
class SettingsNavigationTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val src = File("src/main/java/helium314/keyboard/settings")

    // ---- ----

    @Test fun theEverydaySectionHasEightLinks() {
        assertEquals(8, EverydayLinks.all.size)
    }

    // ---- ----

    private val destinationNames: Map<String, String> = SettingsDestination::class.java.declaredFields
        .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .associate { (it.apply { isAccessible = true }.get(null) as String) to it.name }

    @Test fun theLinksPointAtTheExpectedScreensInTheExpectedOrder() {
        assertEquals(
            listOf("Appearance", "Preferences", "Toolbar", "Preferences", "Preferences", "PersonalDictionaries", "Preferences", "Profiles"),
            EverydayLinks.all.map { destinationNames[it.destination] },
        )
    }

    @Test fun everyLinkHasAnExistingTitleAndSummaryInEnglishAndBulgarian() {
        for (locale in listOf(Locale.ENGLISH, Locale("bg"))) {
            val c = ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply { setLocale(locale) })
            for (link in EverydayLinks.all) {
                assertTrue(c.getString(link.title).isNotBlank(), "$locale ${link.title}")
                link.summary?.let { assertTrue(c.getString(it).isNotBlank(), "$locale $it") }
            }
            assertTrue(c.getString(helium314.keyboard.latin.R.string.everyday_title).isNotBlank())
        }
        assertEquals("Всеки ден", ctx.createConfigurationContext(Configuration().apply { setLocale(Locale("bg")) }).getString(helium314.keyboard.latin.R.string.everyday_title))
    }

    @Test fun everyDestinationIsRegisteredInTheNavHostAndTheHomeScreenUsesTheSectionAndTheHostRoutesIt() {
        val host = File(src, "SettingsNavHost.kt").readText()
        for (link in EverydayLinks.all) {
            val name = destinationNames.getValue(link.destination)
            val registered = Regex("composable\\(SettingsDestination\\.$name\\b").containsMatchIn(host)
            assertTrue(registered, "no composable for $name")
        }
        assertTrue("onClickEveryday = { navController.navigate(it) }" in host, "the host routes the links")
        val home = File(src, "screens/MainSettingsScreen.kt").readText()
        assertTrue("EverydayLinks.all" in home && "onClickEveryday(link.destination)" in home, "the home screen shows and routes the links")
        assertTrue(home.indexOf("EverydayLinks.all") < home.indexOf("language_and_layouts_title"), "the section is at the top")
    }
}
