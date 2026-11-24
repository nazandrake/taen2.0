// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.manual

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Navigation of the manual: the index lists every page file, every link of every page points to an existing page, and the pages of every checked
 * language have the structure of the English ones.
 */
@RunWith(RobolectricTestRunner::class)
class ManualNavigationTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val registry = ManualPages.all.map { it.id }

    // ---- pass 1 ----

    @Test fun theIndexListsEveryPageFileOfEnglish() {
        assertTrue(registry.size > 10, "positive control: the registry has ${registry.size} pages")
        assertEquals(registry.sorted(), ManualFiles.ids("en"))
    }

    @Test fun everyLinkOfEveryPagePointsToAnExistingPage() {
        var seen = 0
        for (id in ManualFiles.ids("en")) {
            val links = ManualFiles.links(ManualFiles.blocks("en", id))
            seen += links.size
            for (link in links) assertTrue(link in registry, "page $id links to a page that does not exist: $link")
        }
        assertTrue(seen > 20, "positive control: $seen links were read")
    }

    // ---- pass 2 ----

    @Test fun theFoldersOfTheManualAreExactlyTheCheckedLanguages() {
        assertEquals(ManualFiles.checkedLanguages.sorted(), ManualFiles.languages())
        assertTrue("en" in ManualFiles.checkedLanguages, "English is always checked")
    }

    @Test fun everyCheckedLanguageHasEveryPageOfTheIndexAndNothingElse() {
        for (lang in ManualFiles.checkedLanguages) assertEquals(registry.sorted(), ManualFiles.ids(lang), lang)
    }

    @Test fun everyCheckedLanguageHasTheStructureOfEnglish() {
        var pages = 0
        for (lang in ManualFiles.checkedLanguages) for (id in registry) {
            pages++
            assertEquals(emptyList(), ManualParity.differences(ManualFiles.blocks("en", id), ManualFiles.blocks(lang, id)), "$lang/$id")
        }
        assertTrue(pages >= registry.size, "positive control: $pages pages were compared")
    }

    @Test fun theParityCheckSeesEveryKindOfDifference() { // negative controls with the positive one
        val en = ManualMarkdown.parse("# T\n\ntext [a](page:vim)\n\n### {#vibrate_on}\nbody\n\n```example\nBefore\n|ab\nKeys\nx\nAfter\n|b\n```\n")
        assertEquals(emptyList(), ManualParity.differences(en, ManualMarkdown.parse("# Т\n\nтекст [а](page:vim)\n\n### {#vibrate_on}\nтяло\n\n```example\nBefore\n|ab\nKeys\nx\nAfter\n|b\n```\n")))
        val wrongKey = ManualMarkdown.parse("# T\n\ntext [a](page:vim)\n\n### {#sound_on}\nbody\n\n```example\nBefore\n|ab\nKeys\nx\nAfter\n|b\n```\n")
        assertTrue(ManualParity.differences(en, wrongKey).any { "settings entries" in it })
        val wrongLink = ManualMarkdown.parse("# T\n\ntext [a](page:privacy)\n\n### {#vibrate_on}\nbody\n\n```example\nBefore\n|ab\nKeys\nx\nAfter\n|b\n```\n")
        assertTrue(ManualParity.differences(en, wrongLink).any { "links" in it })
        val wrongExample = ManualMarkdown.parse("# T\n\ntext [a](page:vim)\n\n### {#vibrate_on}\nbody\n\n```example\nBefore\n|ab\nKeys\nx\nAfter\n|a\n```\n")
        assertTrue(ManualParity.differences(en, wrongExample).any { "examples" in it })
        val wrongShape = ManualMarkdown.parse("# T\n\ntext [a](page:vim)\n\n### {#vibrate_on}\nbody\n\nmore\n\n```example\nBefore\n|ab\nKeys\nx\nAfter\n|b\n```\n")
        assertTrue(ManualParity.differences(en, wrongShape).any { "structure" in it })
    }

    @Test fun everyPageStartsWithOneTitleHeadingAndKeyedEntriesAreThirdLevel() {
        for (id in registry) {
            val blocks = ManualFiles.blocks("en", id)
            val headings = blocks.filterIsInstance<Block.Heading>()
            assertEquals(1, headings.count { it.level == 1 }, "$id has one title")
            assertTrue(blocks.first() is Block.Heading && (blocks.first() as Block.Heading).level == 1, "$id starts with its title")
            for (h in headings) if (h.key != null) assertEquals(3, h.level, "$id: the entry ${h.key} is a third-level heading")
        }
    }

    @Test fun theLanguageOfAPhoneWithoutAManualGetsEnglish() {
        assertEquals("en", ManualPages.languageFor("bg", listOf("en")))
        assertEquals("bg", ManualPages.languageFor("bg", listOf("en", "bg")))
        assertEquals("en", ManualPages.languageFor("de", listOf("en", "bg")))
        assertEquals("en", ManualPages.languageFor("en", listOf("en", "bg")))
    }

    @Test fun thePagesAreReadFromTheAssetsOfTheApp() {
        assertEquals(ManualFiles.checkedLanguages.sorted(), ManualPages.languages(ctx.assets))
        for (id in registry) assertEquals(ManualFiles.text("en", id), ManualPages.read(ctx.assets, "en", id), id)
        assertNull(ManualPages.read(ctx.assets, "en", "no-such-page"), "an id that is not in the index")
        assertNull(ManualPages.read(ctx.assets, "xx", "vim"), "a language without a manual")
    }

    @Test fun everyPageOfTheIndexHasATitleAndASummaryInEnglishAndBulgarian() {
        for (locale in listOf(Locale.ENGLISH, Locale("bg"))) {
            val c = ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply { setLocale(locale) })
            for (page in ManualPages.all) {
                assertTrue(c.getString(page.title).isNotBlank(), "$locale ${page.id} title")
                assertTrue(c.getString(page.summary).isNotBlank(), "$locale ${page.id} summary")
            }
            assertTrue(c.getString(helium314.keyboard.latin.R.string.help_title).isNotBlank())
            assertTrue(c.getString(helium314.keyboard.latin.R.string.help_example_before).isNotBlank())
        }
        val bg = ctx.createConfigurationContext(Configuration().apply { setLocale(Locale("bg")) })
        assertEquals("Помощ", bg.getString(helium314.keyboard.latin.R.string.help_title))
        assertEquals("Help", ctx.createConfigurationContext(Configuration().apply { setLocale(Locale.ENGLISH) }).getString(helium314.keyboard.latin.R.string.help_title))
        assertNotNull(ManualPages.byId("vim"))
        assertNull(ManualPages.byId("no-such-page"))
    }

    @Test fun everyPageCanBeReachedFromAnotherPageOrIsOneOfTheEntryPages() {
        // the index opens every page; a page that nothing links to is still reachable, but the guides must lead to the settings pages
        val linked = HashSet<String>()
        for (id in registry) linked.addAll(ManualFiles.links(ManualFiles.blocks("en", id)))
        val settingsPages = listOf("languages", "preferences", "appearance", "toolbar", "gesture-typing", "text-correction", "layouts", "dictionaries", "voice", "downloads", "media", "privacy-settings", "self-destruct", "profiles", "advanced", "about")
        for (p in settingsPages) assertTrue(p in linked, "no page links to the settings page $p")
        for (p in listOf("vim", "vim-keys", "vim-power", "vim-limits", "vim-lessons", "troubleshooting", "privacy", "suggestions", "clipboard"))
            assertTrue(p in linked, "no page links to $p")
    }
}
