// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The full contract behind VimLimitsTest: the budget and the text ceiling in every kind of command (a macro that doubles the text, "." repeating
 * growth, counts, shifts, :g, :normal, the clock), one text change per key for the Visual block commands, and the table of ex lines that must fail
 * closed. Where Vim would carry on until memory ends, the expected result is the notice and the field as it was.
 */
class VimBoundsTest {
    private fun noChange(r: VimRig, before: String, out: VimOutcome, notice: VimNotice) {
        assertEquals(notice, out.notice)
        assertTrue(out.failed)
        assertEquals(before, r.text)
        assertEquals(emptyList(), r.field.editCalls)
    }

    // ---- the ceiling and the budget ---------------------------------------------------------------------------------------------------

    @Test fun aMacroThatDoublesTheTextStopsTheWholePlayAtTheCeilingAndChangesNothing() {
        val r = VimRig("a".repeat(1000), 0)
        r.keys("qaggVGyGpq")
        assertEquals("ggVGyGp", r.engine.registers.get('a')!!.text)
        assertEquals(2001, r.text.length) // recorded once: the control that the macro doubles
        val before = r.text
        r.field.calls.clear()
        // 2 001, 4 003, 8 007, 16 015 fit; the next one would be 32 031 characters
        noChange(r, before, r.send("10@a"), VimNotice.PLAY_LIMIT)
        // the control: a count that stays under the ceiling plays (three more doublings: 16 015)
        r.keys("3@a")
        assertEquals(16_015, r.text.length)
        assertEquals(1, r.field.editCalls.size)
    }

    @Test fun aRepeatWithDotOfAGrowthCommandStopsAtTheCeilingToo() {
        val r = VimRig("x".repeat(6000), 0)
        r.keys("yyp")
        assertEquals(12_001, r.text.length)
        r.keys(".")
        assertEquals(18_002, r.text.length)
        val before = r.text
        r.field.calls.clear()
        noChange(r, before, r.send("."), VimNotice.PLAY_LIMIT) // the next put would make 24 003
        // the engine goes on working after the refusal
        r.keys("x")
        assertEquals(before.length - 1, r.text.length)
    }

    @Test fun aCountedPutOfALongRegisterIsRefusedBeforeItIsBuilt() {
        val r = VimRig("x".repeat(100), 0)
        r.keys("yy")
        val before = r.text
        noChange(r, before, r.send("999p"), VimNotice.TOO_LONG)
        // the control: a count that fits
        r.keys("5p")
        assertEquals(100 + 5 * 101, r.text.length)
    }

    @Test fun aCountedShiftAndACountedInsertAreRefusedBeforeTheyAreBuilt() {
        val shift = VimRig((1..100).joinToString("\n") { "x" }, 0)
        val before = shift.text
        shift.send("VG")
        noChange(shift, before, shift.send("999>"), VimNotice.TOO_LONG) // 100 lines times 999 times 4 blanks
        val typed = VimRig("abc", 0)
        typed.keys("999i" + "y".repeat(300))
        val out = typed.engine.escape()
        assertEquals(VimNotice.TOO_LONG, out.notice) // 999 times 300 characters: the repeats are not written
        assertTrue(typed.text.length <= VimEngine.MAX_CHARS)
        assertEquals("y".repeat(300) + "abc", typed.text) // what was typed once stays
    }

    @Test fun aGlobalCommandStopsAtTheCeilingAndTheControlRuns() {
        val text = (1..25).joinToString("\n") { "l$it" }
        val r = VimRig(text, 0)
        noChange(r, text, r.send(":g/^/%t\$\n"), VimNotice.PLAY_LIMIT)
        // the control: ":g/^/t." duplicates each line once and stays far under the ceiling
        val c = VimRig("a\nb", 0)
        assertNull(c.send(":g/^/t.\n").notice)
        assertEquals("a\na\nb\nb", c.text)
        // a copy of a register of the maximum size is refused too
        val big = VimRig("x".repeat(15_000), 0)
        big.keys("yy")
        val bigBefore = big.text
        noChange(big, bigBefore, big.send(":pu\n"), VimNotice.PLAY_LIMIT)
        noChange(big, bigBefore, big.send(":t.\n"), VimNotice.PLAY_LIMIT)
        noChange(big, bigBefore, big.send(":s/x/xxxxx/g\n"), VimNotice.PLAY_LIMIT)
        // seven shifts of a line that is 19 995 characters long: 28 blanks over the ceiling
        val shifted = VimRig("x".repeat(19_995), 0)
        noChange(shifted, shifted.text, shifted.send(":>>>>>>>\n"), VimNotice.PLAY_LIMIT)
        val fits = VimRig("x".repeat(19_970), 0)
        assertNull(fits.send(":>>>>>>>\n").notice) // the control: 28 blanks make 19 998
        assertEquals(19_998, fits.text.length)
    }

    @Test fun aSubstituteThatBuildsALongLineStopsBeforeBuildingIt() {
        val r = VimRig("x".repeat(5000), 0)
        val before = r.text
        // "&" nine hundred times for every match would be 4 500 000 characters: refused as the replacement is built
        val rep = "&".repeat(900)
        noChange(r, before, r.send(":s/x/$rep/g\n"), VimNotice.PLAY_LIMIT)
        // the control: a replacement that fits
        val c = VimRig("xx", 0)
        assertNull(c.send(":s/x/ab/g\n").notice)
        assertEquals("abab", c.text)
    }

    @Test fun theStepsOfOneCommandLineAreOneBudgetAndTheControlRuns() {
        val lines = (1..9999).joinToString("\n") { "a" } + "\na" // 19 999 characters
        val r = VimRig(lines, 0)
        noChange(r, lines, r.send(":g/^/%s/a/b/\n"), VimNotice.PLAY_LIMIT) // 10 000 lines for each of 10 000 lines
        val c = VimRig(lines, 0)
        assertNull(c.send(":%s/a/b/\n").notice)
        assertEquals(lines.replace('a', 'b'), c.text)
        val g = VimRig(lines, 0)
        assertNull(g.send(":g/^/s/a/b/\n").notice) // 10 000 lines, a few steps each: inside the budget
        assertEquals(lines.replace('a', 'b'), g.text)
    }

    @Test fun theKeysOfNormalRunsInsideOneGlobalAreOneBudget() {
        val lines = (1..4500).joinToString("\n") { "a b" }
        val r = VimRig(lines, 0)
        // dw, dw and a third dw that fails on the empty line: 6 keys for each of 4 500 lines is 27 000, over the 20 000 of one command
        noChange(r, lines, r.send(":%norm dwdwdw\n"), VimNotice.PLAY_LIMIT)
        val c = VimRig(lines, 0)
        assertNull(c.send(":%norm dw\n").notice) // 9 000 keys
        assertEquals((1..4500).joinToString("\n") { "b" }, c.text)
    }

    @Test fun theClockIsPartOfTheSameBudget() {
        val lines = (1..2000).joinToString("\n") { "ab" }
        val rules = TestRules()
        rules.tick = 100 // each look at the clock is 100 ms later: the 2 500 ms of a command line are used up in a few hundred steps
        val r = VimRig(lines, 0, rules)
        noChange(r, lines, r.send(":%norm x\n"), VimNotice.PLAY_LIMIT)
        val c = VimRig(lines, 0) // a clock that stands still
        assertNull(c.send(":%norm x\n").notice)
        assertEquals(lines.replace("ab", "b"), c.text)
    }

    @Test fun theLimitsAreExactlyTheNumbersOfTheContract() {
        // an ex put that makes the text exactly 20 000 characters runs, one character more is refused
        val exact = VimRig("x".repeat(9_999), 0)
        exact.engine.registers.store('a', Reg("y".repeat(10_000), RegType.CHAR))
        assertNull(exact.send(":pu a\n").notice)
        assertEquals(VimEngine.MAX_CHARS, exact.text.length)
        val over = VimRig("x".repeat(10_000), 0)
        over.engine.registers.store('a', Reg("y".repeat(10_000), RegType.CHAR))
        noChange(over, over.text, over.send(":pu a\n"), VimNotice.PLAY_LIMIT)
        // a macro that types the text up to exactly 20 000 characters runs, one character more stops the play
        val typed = VimRig("a".repeat(19_990), 0)
        typed.engine.registers.store('m', Reg("A" + "z".repeat(10) + "\u001b", RegType.CHAR))
        assertNull(typed.send("@m").notice)
        assertEquals(VimEngine.MAX_CHARS, typed.text.length)
        val typedOver = VimRig("a".repeat(19_990), 0)
        typedOver.engine.registers.store('m', Reg("A" + "z".repeat(11) + "\u001b", RegType.CHAR))
        noChange(typedOver, typedOver.text, typedOver.send("@m"), VimNotice.PLAY_LIMIT)
        // a play of exactly 20 000 keys runs, 20 001 do not (the "@b" counts as two keys and the x it plays as one)
        val keys = VimRig("x".repeat(20_000), 0)
        keys.engine.registers.store('a', Reg("x".repeat(20_000), RegType.CHAR))
        assertNull(keys.send("@a").notice)
        assertEquals("", keys.text)
        val keysOver = VimRig("x".repeat(20_000), 0)
        keysOver.engine.registers.store('b', Reg("x", RegType.CHAR))
        keysOver.engine.registers.store('a', Reg("x".repeat(19_998) + "@b", RegType.CHAR))
        noChange(keysOver, keysOver.text, keysOver.send("@a"), VimNotice.PLAY_LIMIT)
    }

    @Test fun aReplaceThatMakesTheTextLongerThanTheWindowIsRefused() {
        // a character of two UTF-16 units in place of each of 15 000 characters: 30 000
        val r = VimRig("a".repeat(15_000), 0)
        r.send("Vr")
        noChange(r, r.text, r.engine.key(0x1F600), VimNotice.TOO_LONG)
        // the control: the same keys on a text that stays inside the window
        val c = VimRig("a".repeat(5_000), 0)
        c.send("Vr")
        assertNull(c.engine.key(0x1F600).notice)
        assertEquals("😀".repeat(5_000), c.text)
    }

    @Test fun aBlockAppendWhosePaddingWouldMakeTheTextTooLongIsRefusedWhenTheInsertEnds() {
        val text = "a\n" + "b".repeat(12_000)
        val r = VimRig(text, 0)
        r.keys("<C-v>j")
        repeat(12) { r.keys("999l") }
        r.keys("A")
        r.keys("X")
        val out = r.engine.escape()
        assertEquals(VimNotice.TOO_LONG, out.notice) // the first line would be padded with about 12 000 blanks: nothing is written
        assertEquals("aX\n" + "b".repeat(12_000), r.text)
        assertEquals(emptyList(), r.field.editCalls)
    }

    private val padText = "a\nbbb\nbbb\nbbb\n" + "z".repeat(19_966) // 19 980 characters: three lines of ten more would pass the window

    @Test fun aBlockAppendWhoseOtherLinesAreRefusedWritesNoPaddingEither() {
        val r = VimRig(padText, 0)
        r.keys("<C-v>3jlA")
        r.keys("XXXXXXXXXX")
        val typed = r.text
        assertEquals("a" + "XXXXXXXXXX" + padText.substring(1), typed) // what was typed stays
        val out = r.engine.escape()
        assertEquals(VimNotice.TOO_LONG, out.notice)
        assertEquals(typed, r.text) // no blank is written in front of what was typed
        assertEquals(emptyList(), r.field.editCalls)
    }

    @Test fun theBlockAppendThatFitsWritesThePaddingAndTheLinesAndTheLimitIsExact() {
        // the controls of the refusals: 19 980 + 4 typed + 3 lines of 4 + one blank = 19 997, one change
        val fits = VimRig(padText, 0)
        fits.keys("<C-v>3jlAXXXX<Esc>")
        assertEquals(19_997, fits.text.length)
        assertEquals("a XXXX\nbbXXXXb\nbbXXXXb\nbbXXXXb\n" + "z".repeat(19_966), fits.text)
        assertEquals(1, fits.field.editCalls.size)
        // five typed make 20 001: the lines fit by themselves, the padding takes the total over, and nothing is written
        val over = VimRig(padText, 0)
        over.keys("<C-v>3jlAXXXXX")
        val typed = over.text
        over.field.calls.clear()
        assertEquals(VimNotice.TOO_LONG, over.engine.escape().notice)
        assertEquals(typed, over.text)
        assertEquals(emptyList(), over.field.editCalls)
    }

    @Test fun aRefusedBlockInsertOrChangeWritesNothingAfterTheCommandThatMadeTheEdit() {
        val c = VimRig("bbb\nbbb\nbbb\n" + "z".repeat(19_987), 0)
        c.keys("<C-v>2jlc") // the delete of the block is the command's own change
        c.keys("XXXX")
        val typedC = c.text
        c.field.calls.clear()
        assertEquals(VimNotice.TOO_LONG, c.engine.escape().notice)
        assertEquals(typedC, c.text)
        assertEquals(emptyList(), c.field.editCalls)
        val i = VimRig("bbb\nbbb\nbbb\n" + "z".repeat(19_980), 0)
        i.keys("<C-v>2jIXXXX")
        val typedI = i.text
        i.field.calls.clear()
        assertEquals(VimNotice.TOO_LONG, i.engine.escape().notice)
        assertEquals(typedI, i.text)
        assertEquals(emptyList(), i.field.editCalls)
        // the control of both: the same keys with less typed are written, in one change
        val ok = VimRig("bbb\nbbb\nbbb\n" + "z".repeat(19_987), 0)
        ok.keys("<C-v>2jlcX")
        ok.field.calls.clear()
        assertNull(ok.engine.escape().notice)
        assertEquals("Xb\nXb\nXb\n" + "z".repeat(19_987), ok.text)
        assertEquals(1, ok.field.editCalls.size)
    }

    @Test fun aRefusedBlockAppendInAMacroOrADotRepeatOrANormalCommandWritesNothing() {
        val keys = "\u0016" + "3jlAXXXXXXXXXX\u001b"
        val m = VimRig(padText, 0)
        m.engine.registers.store('m', Reg(keys, RegType.CHAR))
        noChange(m, padText, m.send("@m"), VimNotice.PLAY_LIMIT)
        val n = VimRig(padText, 0)
        n.engine.registers.store('m', Reg(keys, RegType.CHAR))
        noChange(n, padText, n.send(":norm @m\n"), VimNotice.PLAY_LIMIT)
        val g = VimRig(padText, 0)
        g.engine.registers.store('m', Reg(keys, RegType.CHAR))
        noChange(g, padText, g.send(":g/^a/norm @m\n"), VimNotice.PLAY_LIMIT)
        // the control: a macro that fits plays, as one change
        val ok = VimRig(padText, 0)
        ok.engine.registers.store('m', Reg("\u0016" + "3jlAXXXX\u001b", RegType.CHAR))
        assertNull(ok.send("@m").notice)
        assertEquals(19_997, ok.text.length)
        assertEquals(1, ok.field.editCalls.size)
        // "." repeats a block append on another short line and runs over the window: nothing is written
        val d = VimRig("a\nbbb\nbbb\nbbb\nc\nddd\nddd\nddd\n" + "z".repeat(19_957), 0)
        d.keys("<C-v>3jlAXX<Esc>")
        assertEquals(19_994, d.text.length)
        d.keys("5G")
        val before = d.text
        d.field.calls.clear()
        noChange(d, before, d.send("."), VimNotice.PLAY_LIMIT)
    }

    @Test fun aReadOnlyRegisterIsNotWrittenByAYankOrADeleteAndTheCommandDoesNothing() {
        for (keys in listOf("\".dd", "\".yy", "\":dw", "\"/yw", "\".x", "\"/D")) {
            val r = VimRig("a b\nc d", 0)
            val out = r.send(keys)
            assertTrue(out.failed, keys)
            assertEquals("a b\nc d", r.text, keys)
            assertEquals(emptyList(), r.engine.registers.names(), keys)
        }
    }

    @Test fun aMacroCannotGrowTheTextThroughATypedInsertEither() {
        val r = VimRig("a".repeat(15_000), 0)
        r.engine.registers.store('a', Reg("A" + "z".repeat(6000) + "\u001b", RegType.CHAR))
        val before = r.text
        noChange(r, before, r.send("@a"), VimNotice.PLAY_LIMIT)
    }

    // ---- one text change per key, in the Visual block commands ----------------------------------------------------------------------------

    private val blockTexts = listOf("a\nabcdef\nabc", "abcdef\nab\nabcd\n", "x\n\nlonger line\nm")

    private fun editsPerKey(r: VimRig, steps: List<String>): List<Int> {
        val counts = ArrayList<Int>()
        for (s in steps) {
            val before = r.field.editCalls.size
            r.keys(s)
            counts.add(r.field.editCalls.size - before)
        }
        return counts
    }

    @Test fun noKeyOfAVisualBlockCommandMakesMoreThanOneTextChange() {
        val sequences = listOf(
            listOf("<C-v>", "j", "j", "5", "l", "A", "X", "<Esc>"),
            listOf("<C-v>", "j", "j", "5", "l", "I", "X", "<Esc>"),
            listOf("l", "<C-v>", "j", "j", "I", "X", "<Esc>"),
            listOf("<C-v>", "j", "j", "$", "A", "X", "<Esc>"),
            listOf("<C-v>", "j", "j", "l", "c", "X", "<Esc>"),
            listOf("l", "<C-v>", "j", "j", "l", "r", "x"),
            listOf("<C-v>", "j", "j", "l", "d"),
            listOf("<C-v>", "j", "j", "5", "l", "d"),
            listOf("<C-v>", "j", "j", "$", "d"),
            listOf("<C-v>", "j", "j", "l", "y", "$", "p"),
            listOf("<C-v>", "j", "j", "5", "l", "A", "<Esc>"),
        )
        for (text in blockTexts) for (steps in sequences) {
            val r = VimRig(text, 0)
            val counts = editsPerKey(r, steps)
            assertTrue(counts.all { it <= 1 }, "$steps on '$text': changes per key $counts")
        }
    }

    @Test fun aBlockAppendOnShortLinesWritesNothingBeforeTheInsertEnds() {
        val r = VimRig("a\nabcdef\nabc", 0)
        for (s in listOf("<C-v>", "j", "j", "5", "l", "A")) r.keys(s)
        assertEquals("a\nabcdef\nabc", r.text)
        assertEquals(emptyList(), r.field.editCalls)
        r.keys("X<Esc>")
        assertEquals("a   X\nabcdXef\nabc X", r.text) // as the real vim gives it: the block is four columns wide here, the first and the last line are padded to it
        assertEquals(1, r.field.editCalls.size)
        assertEquals(0, r.field.selStart)
        // an insert that ends with nothing typed still pads the first line, as Vim does, in the one change
        val e = VimRig("a\nabcdef", 0)
        for (s in listOf("<C-v>", "j", "5", "l", "A")) e.keys(s)
        e.keys("<Esc>")
        assertEquals("a     \nabcdef", e.text)
        assertEquals(1, e.field.editCalls.size)
        // a field that changed meanwhile: nothing is written, the first line is not padded
        val c = VimRig("a\nabcdef", 0)
        for (s in listOf("<C-v>", "j", "5", "l", "A")) c.keys(s)
        c.field.text = "other\ntext"
        c.field.selStart = 0
        c.field.selEnd = 0
        c.keys("X<Esc>")
        assertEquals(emptyList(), c.field.editCalls)
        assertEquals("Xother\ntext", c.text)
    }

    // ---- the ex line fails closed on what it does not understand ------------------------------------------------------------------------------

    @Test fun anExLineWithAnyRemainderOrUnknownSyntaxFailsClosedAndChangesNothing() {
        val text = "a b\nc d\ne f"
        val unsupported = listOf(
            "put=bogus", "pu =", "pu! =", "pu =1+1", "pu a b", "pu! x y", "pu \"=", "put ;", "d a b", "d b 2 3x", "2d xyz", "d .", "d :", "d /", "y a b", "ya 2 3", "y .",
            "m1 foo", "m0 x", "t.+1 y", "co 0 x", "j 2 3", "j! 2 x", "> 2 3", "<< x", "k a b", "ka b", "mark a b", "marks a", "reg a", "display b", "undo 3", "redo x",
            "s/a/b/ z", "s/a/b/g 2 3", "s/a/b/gx", "s/a/b/r", "s/a/b/e", "s/a/b/c", "s/a/b/n", "s/a/b/g&", "s/a/b/ 2x",
            "g/a/d x y", "v/a/d x y", "g/a/foo", "d!", "y!", "m1!", "t1!", "3reg", "2marks", "3undo", "3noh", "p x", "num x", "k", "norm", "bogus", "@", "=", "!ls", "~",
        )
        for (line in unsupported) {
            val r = VimRig(text, 0)
            r.keys("yy")
            val before = r.engine.registers.names()
            r.field.calls.clear()
            val out = r.send(":$line\n")
            assertEquals(VimNotice.EX_ERROR, out.notice, ":$line must be refused")
            assertEquals(text, r.text, ":$line")
            assertEquals(emptyList(), r.field.editCalls, ":$line")
            assertEquals(before, r.engine.registers.names(), ":$line must not touch the registers")
        }
    }

    @Test fun theSameLinesWithoutTheRemainderRunAsTheControlOfTheTable() {
        fun result(line: String, text: String = "a b\nc d\ne f"): String {
            val r = VimRig(text, 0)
            r.keys("yy")
            val out = r.send(":$line\n")
            assertNull(out.notice, ":$line")
            return r.text
        }
        assertEquals("a b\na b\nc d\ne f", result("put"))
        assertEquals("a b\na b\nc d\ne f", result("pu \""))
        assertEquals("c d\ne f", result("d"))
        assertEquals("c d\ne f", result("d x"))
        assertEquals("e f", result("d 2"))
        assertEquals("a b\na b\nc d\ne f", result("t."))
        assertEquals("a b\nc d\na b\ne f", result("t2"))
        assertEquals("a b c d\ne f", result("j"))
        assertEquals("a b\nc d\ne f", result("y x"))
        assertEquals("b b\nc d\ne f", result("s/a/b/"))
        assertEquals("b b\nc d\ne f", result("s/a/b/g"))
        assertEquals("b b\nc d\ne f", result("s/a/b/g 1"))
        assertEquals("    a b\nc d\ne f", result(">"))
        assertEquals("c d\ne f", result("g/a/d"))
        assertEquals("a b\nc d\ne f", result("reg"))
        assertEquals("a b\nc d\ne f", result("marks"))
        assertEquals("a b\nc d\ne f", result("ka"))
        // a pattern that was used earlier in the same line (":g/pat/s//new/") is the empty pattern of the command inside it, as in Vim
        assertEquals("X\nX k\nz", result("g/foo/s//X/", "foo\nfoo k\nz"))
    }

    @Test fun aRepeatOfTheLastSubstituteWithAnUnknownFlagFailsClosedToo() {
        val r = VimRig("a a\nb", 0)
        r.send(":s/a/X/\n")
        assertEquals("X a\nb", r.text)
        assertEquals(VimNotice.EX_ERROR, r.send(":&& x\n").notice)
        assertEquals(VimNotice.EX_ERROR, r.send(":&r\n").notice)
        assertEquals("X a\nb", r.text)
        assertNull(r.send(":&&\n").notice) // the control: the repeat itself
        assertEquals("X X\nb", r.text)
    }

    @Test fun anExLineWithARangeWhereNoneIsAllowedIsAnError() {
        for (line in listOf("2reg", "1,2marks", "3undo", "1redo")) {
            val r = VimRig("a\nb\nc", 0)
            assertEquals(VimNotice.EX_ERROR, r.send(":$line\n").notice, line)
            assertEquals("a\nb\nc", r.text)
        }
    }
}
