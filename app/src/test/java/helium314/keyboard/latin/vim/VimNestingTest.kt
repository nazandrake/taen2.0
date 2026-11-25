// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * One limit for every nested execution: a macro inside a macro, :normal inside a macro or a command line, @: and @@, "." inside a macro, :g with
 * :normal. At most [VimEngine.MAX_NEST] executions are open inside one another (a play is one, every :normal run one more); a deeper one stops the
 * command with a notice and the field is as it was. The executions that are two or more levels deep also may not build more than
 * [VimEngine.MAX_RUN_CHARS] characters of text copies in one command.
 * The levels are counted from the rule, not from what the code does: macro k of a chain runs at level k, a :normal inside it at level k + 1.
 */
class VimNestingTest {
    private fun store(r: VimRig, name: Char, text: String) { r.engine.registers.store(name, Reg(text, RegType.CHAR)) }

    /** Macros a, b, c...: each one calls the next with [call]; the last one does [last]. */
    private fun chain(r: VimRig, n: Int, call: (Char) -> String, last: String) {
        for (i in 0 until n) store(r, 'a' + i, if (i < n - 1) call('a' + i + 1) else last)
    }

    private fun refused(r: VimRig, before: String, out: VimOutcome) {
        assertEquals(VimNotice.PLAY_LIMIT, out.notice)
        assertEquals(before, r.text)
        assertEquals(emptyList(), r.field.editCalls)
        r.keys("x") // the engine goes on working
        assertEquals(before.substring(1), r.text)
    }

    @Test fun aChainOfMacrosMayBeTenDeepAndNotEleven() {
        val ok = VimRig("hello", 0)
        chain(ok, 10, { "@$it" }, "x")
        assertNull(ok.send("@a").notice)
        assertEquals("ello", ok.text) // level 10 ran
        val deep = VimRig("hello", 0)
        chain(deep, 11, { "@$it" }, "x")
        refused(deep, "hello", deep.send("@a"))
    }

    @Test fun aMacroThatCallsItselfStopsAtTheLimitWhetherOrNotItEndsWithTheCall() {
        for (body in listOf("@a", "x@a", "@ax", "xx@a@a")) {
            val r = VimRig("x".repeat(500), 0)
            store(r, 'a', body)
            refused(r, "x".repeat(500), r.send("@a"))
        }
    }

    @Test fun atAtRepeatsTheLastMacroAndInsideAMacroItNestsLikeAnyOther() {
        val r = VimRig("hello world", 0)
        store(r, 'a', "x")
        r.send("@a")
        assertNull(r.send("@@").notice)
        assertEquals("llo world", r.text)
        val loop = VimRig("hello", 0)
        store(loop, 'b', "x@@")
        refused(loop, "hello", loop.send("@b"))
    }

    @Test fun aRepeatWithDotInsideAChainOfMacrosCountsAsALevel() {
        val ok = VimRig("hello world", 0)
        chain(ok, 9, { "@$it" }, ".")
        ok.keys("x")
        assertNull(ok.send("@a").notice) // "." at level 10
        assertEquals("llo world", ok.text)
        val deep = VimRig("hello world", 0)
        chain(deep, 10, { "@$it" }, ".")
        deep.keys("x")
        val before = deep.text
        assertEquals(VimNotice.PLAY_LIMIT, deep.send("@a").notice) // "." at level 11
        assertEquals(before, deep.text)
    }

    @Test fun normalInsideAChainOfMacrosAndMacrosInsideNormalAreLevelsToo() {
        // a -> :normal @b -> b -> :normal @c ...: a runs at level 1, its :normal at 2, b at 3 ... the :normal of the 5th macro is the 10th level
        val ok = VimRig("hello", 0)
        chain(ok, 5, { ":normal @$it\n" }, ":normal x\n")
        assertNull(ok.send("@a").notice)
        assertEquals("ello", ok.text)
        val deep = VimRig("hello", 0)
        chain(deep, 6, { ":normal @$it\n" }, ":normal x\n")
        refused(deep, "hello", deep.send("@a"))
    }

    @Test fun aNormalRunThatWouldBeTheEleventhLevelIsRefusedToo() {
        val ok = VimRig("hello", 0)
        chain(ok, 9, { "@$it" }, ":normal x\n") // the 9th macro runs at level 9, its :normal at level 10
        assertNull(ok.send("@a").notice)
        assertEquals("ello", ok.text)
        val deep = VimRig("hello", 0)
        chain(deep, 10, { "@$it" }, ":normal x\n") // the :normal is the 11th level
        refused(deep, "hello", deep.send("@a"))
    }

    @Test fun globalWithNormalStartsAtLevelOneAndCountsTheMacrosItCalls() {
        val text = "ab\ncd\nef"
        val ok = VimRig(text, 0)
        chain(ok, 9, { "@$it" }, "x") // :normal is level 1, the macros 2 to 10
        assertNull(ok.send(":g/^/normal @a\n").notice)
        assertEquals("b\nd\nf", ok.text)
        val deep = VimRig(text, 0)
        chain(deep, 10, { "@$it" }, "x")
        assertEquals(VimNotice.PLAY_LIMIT, deep.send(":g/^/normal @a\n").notice)
        assertEquals(text, deep.text)
        assertEquals(emptyList(), deep.field.editCalls)
    }

    @Test fun theCommandLineThatRunsItselfWithAtColonStopsAtTheLimit() {
        val r = VimRig("hello", 0)
        refused(r, "hello", r.send(":normal @:\n"))
        val again = VimRig("hello", 0)
        again.send(":normal x\n")
        assertNull(again.send("@:").notice) // the control: at top level @: plays the line once, :normal inside it
        assertEquals("llo", again.text)
        val g = VimRig("ab\ncd", 0)
        assertEquals(VimNotice.PLAY_LIMIT, g.send(":g/^/normal @:\n").notice)
        assertEquals("ab\ncd", g.text)
    }

    // ---- the copies a command line builds ---------------------------------------------------------------------------------------------------

    /** [lines] lines of [width] characters each. */
    private fun block(lines: Int, width: Int) = (1..lines).joinToString("\n") { "a".repeat(width) }

    /** [lines] lines of nearly the same width that together are [total] characters long (the first lines are one character wider). */
    private fun blockOfLength(lines: Int, total: Int): String {
        val chars = total - (lines - 1)
        return (0 until lines).joinToString("\n") { "a".repeat(chars / lines + if (it < chars % lines) 1 else 0) }
    }

    /** What `rX` on every line leaves. */
    private fun xFirst(text: String) = text.split("\n").joinToString("\n") { "X" + it.drop(1) }

    @Test fun theCopiesBuiltByNestedExecutionsAreCountedAndStopAtTwoMillionCharacters() {
        // b runs :%normal rX; a reaches it through a :normal, so the range command runs at level 3 and its copies count: the line list of the ex run (n
        // characters), then for every line the join, the text of the result (2n) and the one key the outer play still has to run (1)
        fun nested(r: VimRig): VimOutcome {
            store(r, 'a', ":normal @b\n")
            store(r, 'b', ":%normal rX\n")
            return r.send("@a")
        }
        // 100 lines in 9 949 characters: 9 949 + 100 * (2 * 9 949 + 1) = 1 999 849, under the limit
        val text = blockOfLength(100, 9_949)
        val under = VimRig(text, 0)
        assertNull(nested(under).notice)
        assertEquals(xFirst(text), under.text)
        // 100 lines in 9 950 characters: 9 950 + 100 * (2 * 9 950 + 1) = 2 000 050, over it (the one key of each line is what tips it)
        val big = blockOfLength(100, 9_950)
        val over = VimRig(big, 0)
        assertEquals(VimNotice.PLAY_LIMIT, nested(over).notice)
        assertEquals(big, over.text)
        assertEquals(emptyList(), over.field.editCalls)
    }

    @Test fun theCopiesOfTheFirstLevelAreFreedOneIterationAfterTheOtherAndAreNotCounted() {
        // the same range command typed on the command line (level 1): 100 lines of 99 characters are fine, and so are 4 500 lines of a 17 999 character text
        val r = VimRig(block(100, 99), 0)
        assertNull(r.send(":%normal rX\n").notice)
        assertEquals(xFirst(block(100, 99)), r.text)
        val many = VimRig((1..4500).joinToString("\n") { "a b" }, 0)
        assertNull(many.send(":%norm dw\n").notice)
        assertEquals((1..4500).joinToString("\n") { "b" }, many.text)
    }

    @Test fun aCommandLineThatNeverNestsIsNotCountedAsCopies() {
        val r = VimRig(block(2000, 8), 0) // 18 999 characters, 2 000 lines: :g and :s steps, no :normal and no copy per line
        assertNull(r.send(":g/^/s/a/b/\n").notice)
        assertEquals((1..2000).joinToString("\n") { "b" + "a".repeat(7) }, r.text)
    }
}
