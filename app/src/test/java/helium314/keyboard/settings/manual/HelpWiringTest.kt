// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.manual

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The Help screens are reachable: the main settings screen has the Help entry, and the navigation host opens the index and every page. */
class HelpWiringTest {
    private val src = File("src/main/java/helium314/keyboard/settings")

    @Test fun theMainSettingsScreenHasTheHelpEntryAndTheHostRoutesIt() {
        val home = File(src, "screens/MainSettingsScreen.kt").readText()
        val entry = Regex("Preference\\(\\s*name = stringResource\\(R\\.string\\.help_title\\),[\\s\\S]{0,160}?onClick = onClickHelp").containsMatchIn(home)
        assertTrue(entry, "the home screen shows the Help entry and calls onClickHelp")
        val host = File(src, "SettingsNavHost.kt").readText()
        assertTrue("onClickHelp = { navController.navigate(SettingsDestination.Help) }" in host, "the host routes the entry to the index")
    }

    @Test fun theHostOpensTheIndexAndEveryPageAndPagesLinkToPages() {
        val host = File(src, "SettingsNavHost.kt").readText()
        assertTrue(Regex("composable\\(SettingsDestination\\.Help\\)\\s*\\{\\s*HelpIndexScreen\\(").containsMatchIn(host), "the index route")
        assertTrue(Regex("composable\\(SettingsDestination\\.HelpPage \\+ \"\\{page\\}\"\\)\\s*\\{\\s*HelpPageScreen\\(").containsMatchIn(host), "the page route")
        assertTrue("onOpenPage = { navController.navigate(SettingsDestination.HelpPage + it) }" in host, "the index opens a page")
        assertTrue("onOpenPage = { page -> navController.navigate(SettingsDestination.HelpPage + page) }" in host, "a link of a page opens a page")
        val screen = File(src, "screens/HelpScreen.kt").readText()
        assertTrue("for (page in ManualPages.all)" in screen, "the index is the list of pages")
        assertTrue("ManualMarkdown.parse(" in screen && "ManualPages.read(" in screen, "a page is read from the assets and parsed")
    }
}
