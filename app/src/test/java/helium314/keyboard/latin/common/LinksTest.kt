// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.common

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.settings.SettingsContainer
import helium314.keyboard.settings.SettingsWithoutKey
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The links the settings open: Taen's own ones go to Taen's repository, and none to a wiki. */
@RunWith(RobolectricTestRunner::class)
class LinksTest {
    @Test fun theAboutScreenOpensTaensRepositoryAndLicence() {
        assertEquals("https://github.com/nazandrake/taen2.0", Links.GITHUB)
        assertEquals("https://github.com/nazandrake/taen2.0/blob/main/LICENSE", Links.LICENSE)
    }

    @Test fun theLayoutHelpOpensTheLayoutsDocumentOfTaensRepository() {
        assertEquals("https://github.com/nazandrake/taen2.0/blob/main/layouts.md", linkConstants()["LAYOUTS_DOC"])
        assertTrue(File("../layouts.md").isFile, "the linked document is in the repository")
    }

    /** Every String constant of [Links] by name (read by reflection, so a test of a constant fails rather than not compiling). */
    private fun linkConstants(): Map<String, String> =
        Links::class.java.declaredFields.filter { it.type == String::class.java }.associate { it.isAccessible = true; it.name to it.get(null) as String }

    @Test fun noLinkConstantGoesToAWiki() {
        val links = linkConstants().values
        assertTrue(Links.GITHUB in links, "positive control: the constants are read")
        assertEquals(emptyList(), links.filter { "/wiki" in it })
    }

    @Test fun theSettingsHaveNoWikiItem() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val c = SettingsContainer(ctx)
        assertTrue(SettingsWithoutKey.GITHUB in c.filter("github").map { it.key }, "positive control: the About items are searched")
        assertEquals(emptyList(), c.filter("wiki").map { it.key })
    }

    /** Every locale: no string sends to HeliBoard's wiki; the only wiki links left are Wikipedia articles about languages. */
    @Test fun noStringInAnyLocaleLinksToAWiki() {
        val files = File("src/main/res").listFiles { f -> f.name.startsWith("values") }!!.mapNotNull { File(it, "strings.xml").takeIf { f -> f.isFile } }
        assertTrue(files.size > 30, "positive control: the strings of every locale are read (${files.size})")
        val bad = files.flatMap { f -> f.readLines().filter { "/wiki" in it && !Regex("""https://[a-z]+\.wikipedia\.org/wiki/""").containsMatchIn(it) }.map { "${f.parentFile.name}: $it" } }
        assertEquals(emptyList(), bad)
        assertEquals(emptyList(), files.filter { f -> f.readText().contains("HeliBorg/HeliBoard/wiki") || f.readText().contains("about_wiki_link") }.map { it.parentFile.name })
    }
}
