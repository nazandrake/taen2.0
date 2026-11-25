// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.manual

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every Vim example of the manual runs through the real engine. The text before, the keys and the text after, with the cursor marked, come from the
 * page; the test fails on any difference in the text, the cursor or the mode. The examples avoid what this keyboard does differently on purpose
 * (see the notes of tools/gen-vim-golden.py: tabs and wide characters, the paragraph macros of Vim, quotes in %, J after a sentence end).
 */
class ManualVimExamplesTest {
    private fun all(): List<Triple<String, String, Block.Example>> = ManualFiles.checkedLanguages.flatMap { lang ->
        ManualFiles.ids(lang).flatMap { id -> ManualFiles.examples(ManualFiles.blocks(lang, id)).map { Triple(lang, id, it) } }
    }

    // ---- pass 1 ----

    @Test fun everyExampleOfEveryCheckedLanguageMatchesTheEngine() {
        val failures = ArrayList<String>()
        val examples = all()
        for ((lang, id, e) in examples) {
            val problem = try { ManualExampleRunner.run(e) } catch (t: Throwable) { "threw $t" }
            if (problem != null) failures.add("$lang/$id [${e.keys}] on '${e.before.replace("\n", "\\n")}': $problem")
        }
        assertTrue(examples.size >= 30, "positive control: ${examples.size} examples were run")
        assertEquals(emptyList(), failures)
    }

    // ---- pass 2 ----

    @Test fun theRunnerSeesADifferenceInTheTextTheCursorAndTheMode() { // negative controls with the positive one
        val ok = Block.Example("|one two", "dw", "|two")
        assertNull(ManualExampleRunner.run(ok), "positive control")
        assertNotNull(ManualExampleRunner.run(ok.copy(after = "|one")), "wrong text")
        assertNotNull(ManualExampleRunner.run(ok.copy(after = "t|wo")), "wrong cursor")
        assertNotNull(ManualExampleRunner.run(Block.Example("|one", "i", "|one")), "the engine is left in insert mode")
        assertNotNull(ManualExampleRunner.run(Block.Example("one two", "dw", "|two")), "no marker before")
    }

    @Test fun aCursorThatNormalModeCannotHaveIsRejected() {
        assertNotNull(ManualExampleRunner.run(Block.Example("one|\ntwo", "x", "|ne\ntwo")), "on the line break of a line that has text")
        assertNotNull(ManualExampleRunner.run(Block.Example("one|", "x", "on|")), "after the last character")
        assertNull(ManualExampleRunner.run(Block.Example("|", "x", "|")), "an empty text is a valid place")
        assertNull(ManualExampleRunner.run(Block.Example("a\n|\nb", "dd", "a\n|b")), "an empty line is a valid place")
    }

    /** The `example` fences written in a page text: an opening line is "```example" (a closing fence has no word after it). */
    private fun fencesIn(text: String) = text.replace("\r\n", "\n").split("\n").count { it.startsWith("```") && it.substring(3).trim() == "example" }

    @Test fun everyExampleFenceOfEveryPageAndLanguageIsShownAsAnExampleNeverAsPlainCode() {
        var fences = 0
        for (lang in ManualFiles.checkedLanguages) for (id in ManualFiles.ids(lang)) {
            val written = fencesIn(ManualFiles.text(lang, id))
            fences += written
            assertEquals(written, ManualFiles.examples(ManualFiles.blocks(lang, id)).size, "$lang/$id: an example fence does not follow the format and shows as code")
        }
        assertTrue(fences >= 60, "positive control: $fences example fences were compared")
        // the control of the comparison: a malformed fence is counted as written and not as an example
        val bad = "```example\nBefore\none two\nKeys\ndw\nAfter\none |\n```"
        assertEquals(1, fencesIn(bad))
        assertEquals(0, ManualFiles.examples(ManualMarkdown.parse(bad)).size)
    }

    @Test fun thePagesHaveTheirShareOfExamplesAndSixLessons() {
        fun count(page: String) = ManualFiles.examples(ManualFiles.blocks("en", page)).size
        assertTrue(count("vim-keys") >= 30, "vim-keys has ${count("vim-keys")}")
        assertTrue(count("vim-power") >= 30, "vim-power has ${count("vim-power")}")
        assertTrue(count("vim-lessons") >= 12, "vim-lessons has ${count("vim-lessons")}")
        val lessons = ManualFiles.blocks("en", "vim-lessons").filterIsInstance<Block.Heading>().filter { it.level == 2 }
            .map { (it.spans.single() as Span.Text).text }
        assertEquals(6, lessons.size, "lessons: $lessons")
        assertEquals((1..6).map { "Lesson $it" }, lessons.map { it.substringBefore(":") })
    }

    @Test fun everyLessonHasAGoalStepsAnExampleAndAResult() {
        val blocks = ManualFiles.blocks("en", "vim-lessons")
        val starts = blocks.indices.filter { (blocks[it] as? Block.Heading)?.level == 2 }
        assertEquals(6, starts.size)
        for ((n, start) in starts.withIndex()) {
            val end = starts.getOrNull(n + 1) ?: blocks.size
            val lesson = blocks.subList(start + 1, end)
            val goal = lesson.firstOrNull() as? Block.Paragraph
            assertNotNull(goal, "lesson ${n + 1} starts with its goal")
            assertTrue((goal.spans.first() as? Span.Bold)?.children == listOf(Span.Text("Goal:")), "lesson ${n + 1}: $goal")
            assertTrue(lesson.any { it is Block.Numbered }, "lesson ${n + 1} has numbered steps")
            assertTrue(lesson.any { it is Block.Example }, "lesson ${n + 1} has an example that is run")
            val last = lesson.last() as? Block.Paragraph
            assertTrue(last != null && (last.spans.first() as? Span.Bold)?.children == listOf(Span.Text("Result:")), "lesson ${n + 1} ends with its result")
        }
    }

    @Test fun noExampleDependsOnTheDeclaredDeviationsFromVim() {
        val wide = Regex("[\\u1100-\\u115F\\u2E80-\\uA4CF\\uAC00-\\uD7A3\\uF900-\\uFAFF\\uFE30-\\uFE6F\\uFF00-\\uFF60\\uFFE0-\\uFFE6]")
        val sentenceEnd = Regex("[.!?]\\s*$", RegexOption.MULTILINE)
        for ((lang, id, e) in all()) {
            val where = "$lang/$id [${e.keys}]"
            for (text in listOf(e.before, e.after)) {
                assertTrue('\t' !in text, "$where: a tab in the text (a tab is one column here, not a screen column)")
                assertTrue(text.none { Character.isSurrogate(it) } && !wide.containsMatchIn(text), "$where: a wide character in the text")
            }
            if ('J' in e.keys) assertTrue(!sentenceEnd.containsMatchIn(e.before), "$where: J after a sentence end (Vim joins with two spaces there)")
            if ('%' in e.keys && ":" !in e.keys) assertTrue('"' !in e.before && '\'' !in e.before, "$where: quotes around % (Vim looks at them, this keyboard does not)")
            assertTrue("<C-a>" !in e.keys || !Regex("0[0-9]").containsMatchIn(e.before), "$where: a number with leading zeros")
        }
    }

    @Test fun everyExampleDoesSomethingOrIsAMotion() {
        // an example whose text does not change must move the cursor, or it shows nothing
        for ((lang, id, e) in all()) {
            val b = ManualExample.place(e.before)!!
            val a = ManualExample.place(e.after)!!
            assertTrue(b.text != a.text || b.cursor != a.cursor, "$lang/$id [${e.keys}] changes nothing")
        }
    }
}
