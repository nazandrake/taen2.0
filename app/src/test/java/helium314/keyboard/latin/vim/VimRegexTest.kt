// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pattern matcher of the search and substitute commands: what it finds (the expected values are Vim's, by its documentation of the magic
 * patterns; VimGoldenTest checks the same patterns through search and :s against the real vim) and, above all, that it cannot be made to run
 * long: no backtracking, a budget of steps and time, and a limit on the size of the program.
 */
class VimRegexTest {
    private fun budget(steps: Int = 1_000_000) = VimRegex.Budget(steps, Long.MAX_VALUE) { 0L }

    private fun regex(p: String, ignoreCase: Boolean = false): VimRegex = (VimRegex.compile(p, ignoreCase) as VimRegex.Compiled.Ok).regex

    private fun matches(p: String, line: String, from: Int = 0): List<String> {
        val re = regex(p)
        val out = ArrayList<String>()
        var at = from
        while (at <= line.length) {
            val m = re.find(line, at, budget()) ?: break
            out.add(line.substring(m.start, m.end))
            at = if (m.end > m.start) m.end else m.end + 1
        }
        return out
    }

    /** The text of the first match at or after [from], or null. */
    private fun first(p: String, line: String, from: Int = 0, ignoreCase: Boolean = false): String? =
        regex(p, ignoreCase).find(line, from, budget())?.let { line.substring(it.start, it.end) }

    @Test fun literalsAndTheAnyCharacter() {
        assertEquals(listOf("o", "o", "o"), matches("o", "foo bar o"))
        assertEquals(listOf("fo", "o ", "ba"), matches("..", "foo ba")) // two characters at a time, from the left
        assertEquals(listOf("b.r"), matches("b\\.r", "bar b.r"))
    }

    @Test fun quantifiersAreGreedyAndTheStarAtTheStartIsLiteral() {
        assertEquals(listOf("oo", "o"), matches("o\\+", "foo bao"))
        assertEquals("fo", first("fo\\=", "fo"))
        assertEquals("f", first("fo\\=", "fb"))
        assertEquals("*a", first("*a", "x*a")) // a star at the start of the pattern is a character
        assertEquals("a^", first("a^", "a^")) // a ^ that is not at the start of the pattern is a character
        assertEquals("ooo", first("o\\{2,3}", "foooo"))
        assertEquals("oo", first("o\\{-2,3}", "foooo")) // \{-n,m} is the lazy form
        assertEquals("oooo", first("o\\{2,}", "foooo"))
        assertEquals("oo", first("o\\{2}", "foooo"))
    }

    @Test fun anchorsAndWordEdges() {
        assertEquals("b", first("^b", "bab"))
        assertNull(first("^b", "ab"))
        assertEquals("b", first("b$", "ab"))
        assertNull(first("b$", "ba"))
        assertEquals(listOf("foo", "foo"), matches("\\<foo\\>", "foo foobar foo"))
        assertEquals(listOf("и"), matches("\\<и\\>", "и ии")) // a Cyrillic word is a word
    }

    @Test fun classesGroupsAlternativesAndEscapes() {
        assertEquals(listOf("b", "f"), matches("[bf]", "abcdefg"))
        assertEquals(listOf("a", "C", "D"), matches("[^b-z]", "abCD"))
        assertEquals(listOf("A", "Б"), matches("[[:upper:]]", "aAbБ"))
        assertEquals(listOf("12", "3"), matches("\\d\\+", "12 3"))
        assertEquals("foo", first("\\(foo\\|bar\\)", "xfoo"))
        val m = regex("\\(a\\)\\(b\\)\\=").find("ac", 0, budget())!!
        assertEquals("a", m.group(1, "ac"))
        assertEquals("", m.group(2, "ac")) // a group that took no part is empty
        assertEquals("a", m.group(0, "ac"))
    }

    @Test fun theCaseOfAPatternFollowsItsFlagsAndTheDefault() {
        assertNull(regex("FOO").find("foo", 0, budget()))
        assertNotNull(regex("FOO", ignoreCase = true).find("foo", 0, budget()))
        assertNotNull(regex("\\cFOO").find("foo", 0, budget()))
        assertNull(regex("\\Cfoo", ignoreCase = true).find("FOO", 0, budget()))
    }

    @Test fun anEmptyMatchIsFoundAtTheEnd() {
        val m = regex("x*").find("ab", 2, budget())!!
        assertEquals(2 to 2, m.start to m.end)
    }

    @Test fun whatIsOutsideTheSubsetIsRefusedNotMisread() {
        for (p in listOf("a\\nb", "\\v(a)", "\\(a\\)\\1", "a\\zsb", "\\%[ab]", "a\\{", "\\(a", "a\\)", "[b-a]", "\\_s"))
            assertEquals(VimRegex.Compiled.Unsupported, VimRegex.compile(p, false), "$p must be refused")
        // an unclosed bracket is a literal, as in Vim
        assertEquals(listOf("[a"), matches("[a", "x[a"))
    }

    // ---- bounds ------------------------------------------------------------------------------------------------------------

    @Test fun aCatastrophicPatternRunsInLinearTime() {
        // a backtracking matcher takes exponential time here; this one needs steps in proportion to the text
        val text = "a".repeat(20_000)
        for (p in listOf("\\(a*\\)*b", "\\(a\\|aa\\)*b", "\\(a\\+\\)\\+b", "\\(a*\\)\\{1,50}b")) {
            val b = budget(10_000_000)
            assertNull(regex(p).find(text, 0, b), p)
            assertFalse(b.exhausted, "$p: steps ${b.steps}")
            assertTrue(b.steps < 20_000 * 400, "$p used ${b.steps} steps")
        }
    }

    @Test fun theStepBudgetStopsASearchAndSaysSo() {
        val text = "a".repeat(20_000)
        val b = budget(5_000)
        assertNull(regex("\\(a*\\)*b").find(text, 0, b))
        assertTrue(b.exhausted)
        // the control: the same search with enough steps finishes and is not exhausted
        val ok = budget(10_000_000)
        assertNull(regex("\\(a*\\)*b").find(text, 0, ok))
        assertFalse(ok.exhausted)
        // a spent budget answers nothing at all, also for a pattern that would match
        assertNull(regex("a").find(text, 0, b))
    }

    @Test fun theTimeBudgetStopsASearchAndSaysSo() {
        var now = 0L
        val b = VimRegex.Budget(Int.MAX_VALUE, 100L) { now += 60L; now } // every look at the clock is 60 ms later
        assertNull(regex("\\(a*\\)*b").find("a".repeat(5_000), 0, b))
        assertTrue(b.exhausted)
        // the control: a clock that stands still does not stop the same search
        val still = VimRegex.Budget(Int.MAX_VALUE, 100L) { 0L }
        assertNull(regex("\\(a*\\)*b").find("a".repeat(5_000), 0, still))
        assertFalse(still.exhausted)
    }

    @Test fun aPatternThatWouldMakeAHugeProgramIsRefused() {
        assertEquals(VimRegex.Compiled.TooBig, VimRegex.compile("a\\{1000}\\{1000}", false))
        assertEquals(VimRegex.Compiled.TooBig, VimRegex.compile("\\(\\(a\\{1,100}\\)\\{1,100}\\)\\{1,100}", false))
        assertEquals(VimRegex.Compiled.TooBig, VimRegex.compile("a\\{1001}", false), "a repeat count over the limit")
        // just inside the limits it compiles, and works
        assertTrue(VimRegex.compile("a\\{1000}", false) is VimRegex.Compiled.Ok)
        assertNotNull(regex("a\\{1000}").find("a".repeat(1000), 0, budget()))
        // nesting of groups is bounded too (no recursion that a deep pattern could overflow)
        assertEquals(VimRegex.Compiled.TooBig, VimRegex.compile("\\(".repeat(200) + "a" + "\\)".repeat(200), false))
    }

    @Test fun aLongLineIsMatchedWithoutRecursion() {
        // a recursive matcher overflows the stack on a line like this
        val text = "ab".repeat(10_000)
        val m = regex("\\(a\\|b\\)*").find(text, 0, budget(10_000_000))!!
        assertEquals(text.length, m.end - m.start)
    }
}
