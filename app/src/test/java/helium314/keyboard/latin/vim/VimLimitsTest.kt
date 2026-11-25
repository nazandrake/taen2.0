// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One budget for every command (keys, ex steps, :g iterations, :normal runs), a ceiling on the text a command may compute, one text change
 * per command, and an ex line that fails closed on what it does not understand. Expected texts are what the keys give in Vim; where Vim
 * would run on (a text that doubles until memory ends) the expected result is the refusal: the notice and the field as it was.
 */
class VimLimitsTest {
    @Test fun aGlobalCommandThatDoublesTheTextStopsAtTheCeilingAndChangesNothing() {
        val text = (1..25).joinToString("\n") { "l$it" }
        val r = VimRig(text, 0)
        val out = r.send(":g/^/%t\$\n")
        assertEquals(VimNotice.PLAY_LIMIT, out.notice)
        assertEquals(text, r.text)
        assertEquals(emptyList(), r.field.editCalls)
        r.keys("x") // the engine goes on working
        assertEquals(text.substring(1), r.text)
    }

    @Test fun aBlockAppendOnAShortFirstLineMakesItsOnlyChangeWhenInsertEnds() {
        val r = VimRig("a\nabcdef", 0)
        r.keys("<C-v>j5lA")
        assertEquals("a\nabcdef", r.text) // the padding is not committed ahead of the insert
        assertEquals(emptyList(), r.field.editCalls)
        r.keys("X<Esc>")
        assertEquals("a     X\nabcdefX", r.text)
        assertEquals(1, r.field.editCalls.size)
        assertEquals(0, r.field.selStart)
    }

    @Test fun aPutOfAnExpressionRegisterFailsClosed() {
        val r = VimRig("x", 0)
        r.keys("yy")
        val out = r.send(":put=bogus\n")
        assertEquals(VimNotice.EX_ERROR, out.notice)
        assertTrue(out.failed)
        assertEquals("x", r.text)
        assertEquals(emptyList(), r.field.editCalls)
        // the control: the same command with a register that exists puts it
        assertEquals(null, r.send(":put\n").notice)
        assertEquals("x\nx", r.text)
    }
}
