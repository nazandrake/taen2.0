package taen

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The notes of the "What's new" box have one owner: the CHANGELOG.md entry of the running versionName. The rule: the lines of the entry up to the first
 * blank line (the summary, 3 to 6 lines, the details follow after a blank line), Markdown markers removed, one paragraph per bullet.
 */
class WhatsNewTest {
    @get:Rule val tmp = TemporaryFolder()

    private val log = "# Changelog\n\n## 2.0.3\n\n- New: a.\n- Fixed: b.\n\n- detail\n\n## 2.0.2\n\n- old\n"
    private val dot = "•"

    @Test fun theEntryOfTheVersionIsFound() {
        assertEquals("$dot New: a.\n\n$dot Fixed: b.", WhatsNew.notes(log, "2.0.3"))
    }

    @Test fun onlyTheSummaryUpToTheFirstBlankLineIsTaken() {
        val text = WhatsNew.notes(log, "2.0.3")!!
        assertFalse(text, text.contains("detail"))
        assertFalse(text, text.contains("old"))
        // the entry that has no blank line is taken whole
        assertEquals("$dot old", WhatsNew.notes(log, "2.0.2"))
        assertEquals("$dot x\n\n$dot y\n\n$dot z", WhatsNew.notes("## 1.0\n\n- x\n- y\n- z\n", "1.0"))
    }

    @Test fun blankLinesBetweenTheHeadingAndTheFirstLineAreSkippedAndNoneIsNeeded() {
        assertEquals("$dot a", WhatsNew.notes("## 1.0\n\n\n\n- a\n\n- more\n", "1.0"))
        assertEquals("$dot a", WhatsNew.notes("## 1.0\n- a\n", "1.0"))
    }

    @Test fun theLastEntryOfTheFileIsFoundWithAndWithoutATrailingNewline() {
        assertEquals("$dot last", WhatsNew.notes("# Changelog\n\n## 2.0.1\n\n- new\n\n## 2.0.0\n\n- last", "2.0.0"))
        assertEquals("$dot last", WhatsNew.notes("# Changelog\n\n## 2.0.0\n\n- last\n", "2.0.0"))
        assertEquals("$dot new", WhatsNew.notes("# Changelog\n\n## 2.0.1\n\n- new\n\n## 2.0.0\n\n- last", "2.0.1")) // the first entry stops at the next heading
    }

    @Test fun aMissingOrEmptyEntryGivesNull() {
        assertNull(WhatsNew.notes(log, "9.9.9"))
        assertNull(WhatsNew.notes("", "2.0.3"))
        assertNull(WhatsNew.notes("## 2.0.3\n\n## 2.0.2\n\n- old\n", "2.0.3"))
        assertNull(WhatsNew.notes("## 2.0.3\n\n\n", "2.0.3"))
        assertNull(WhatsNew.notes("## 2.0.3", "2.0.3"))
    }

    @Test fun theVersionMustMatchTheWholeHeading() {
        val text = "## 2.0.30\n\n- thirty\n\n## 2.0.3-beta\n\n- beta\n\n### 2.0.3\n\n- sub\n\nsee ## 2.0.3\n"
        assertNull(WhatsNew.notes(text, "2.0.3"))
        assertEquals("$dot thirty", WhatsNew.notes(text, "2.0.30"))
        assertEquals("$dot a", WhatsNew.notes("## 2.0.3  \n\n- a\n", "2.0.3")) // trailing blanks of the heading
        assertEquals("$dot x", WhatsNew.notes("## 2.0.3\n\n- x\n\n## 2.0.2\n\n- y\n", "2.0.3"))
        assertEquals("$dot a", WhatsNew.notes("## 2x0x3\n\n- b\n\n## 2.0.3\n\n- a\n", "2.0.3")) // the dots are not wildcards
    }

    @Test fun aBulletThatWrapsOverSeveralLinesIsOneParagraph() {
        val text = "## 1.0\n\n- New: first\n  second line\n  third line.\n- Next one.\n"
        assertEquals("$dot New: first second line third line.\n\n$dot Next one.", WhatsNew.notes(text, "1.0"))
        assertEquals("$dot a b", WhatsNew.notes("## 1.0\n\n- a\r\n  b\r\n\r\n- detail\r\n", "1.0")) // Windows line ends
    }

    @Test fun aPlainParagraphWithoutBulletsIsKept() {
        assertEquals("Just text on two lines", WhatsNew.notes("## 1.0\n\nJust text\non two lines\n\n- detail\n", "1.0"))
    }

    @Test fun markdownMarkersAreRemoved() {
        assertEquals("$dot bold and code and link and under", WhatsNew.notes("## 1.0\n\n- **bold** and `code` and [link](https://example.org/x) and __under__\n", "1.0"))
        assertEquals("$dot a * b and snake_case_name and 3 * 4 and a_b", WhatsNew.notes("## 1.0\n\n- a * b and snake_case_name and 3 * 4 and a_b\n", "1.0")) // nothing else is touched
        assertEquals("Added\n\n$dot x", WhatsNew.notes("## 1.0\n\n### Added\n- x\n", "1.0")) // a sub heading loses its marker
        assertEquals("$dot a `b", WhatsNew.notes("## 1.0\n\n- a `b\n", "1.0")) // an unpaired backtick is left as written
    }

    @Test fun anAsteriskBulletIsABulletToo() {
        assertEquals("$dot a\n\n$dot b", WhatsNew.notes("## 1.0\n\n* a\n* b\n", "1.0"))
    }

    // ---- the build step: the file the release build writes ----

    private fun write(changelog: String, version: String, required: Boolean): File {
        val file = tmp.newFile().apply { writeText(changelog) }
        val out = tmp.newFolder()
        WhatsNew.write(file, version, required, out)
        return File(out, WhatsNew.ASSET)
    }

    @Test fun theBuildWritesTheNotesOfTheVersionAsOneAsset() {
        val asset = write(log, "2.0.3", true)
        assertEquals("$dot New: a.\n\n$dot Fixed: b.", asset.readText())
        assertEquals(write(log, "2.0.3", true).readText(), asset.readText()) // deterministic
    }

    @Test fun aReleaseBuildFailsWithoutAnEntryAndOtherBuildsWriteAnEmptyAsset() {
        try {
            write(log, "9.9.9", true)
            fail("a release build without a CHANGELOG entry must fail")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("9.9.9"))
        }
        try {
            write("## 2.0.3\n\n## 2.0.2\n\n- old\n", "2.0.3", true)
            fail("an empty entry must fail too")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("2.0.3"))
        }
        val asset = write(log, "9.9.9", false)
        assertTrue(asset.isFile)
        assertEquals("", asset.readText())
    }

    @Test fun theRealChangelogHasAShortReadableSummaryForTheVersionOfTheApp() {
        val root = File("..").canonicalFile
        val version = Regex("""versionName = "(.*)"""").find(File(root, "app/build.gradle.kts").readText())!!.groupValues[1]
        val text = WhatsNew.notes(File(root, "CHANGELOG.md").readText(), version)
        assertNotNull("CHANGELOG.md has no entry for $version", text)
        for (marker in listOf("`", "**", "](", "__")) assertFalse(marker, text!!.contains(marker))
        assertTrue("at most 6 bullets: $text", text!!.split("\n\n").size in 1..6)
        assertTrue("short enough for a dialog (${text.length} characters)", text.length <= 1200)
    }
}
