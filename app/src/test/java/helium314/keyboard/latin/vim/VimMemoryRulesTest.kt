// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the layer remembers is decided at the moment of the write: the clipboard capture rule is asked again for every store, so a switch to
 * incognito in the middle of a command, an insert or a recording keeps the rest out, and nothing typed after it is kept. The expected values
 * are what a keyboard that keeps nothing in such a field must show (the control of each test runs the same keys where capture is allowed).
 */
class VimMemoryRulesTest {
    @Test fun typedTextAfterTheDecisionTurnsOffNeverReachesTheDotRecord() {
        val r = VimRig("foo bar baz", 0)
        r.keys("cw") // the change that "." would repeat starts: capture is allowed here
        r.rules.registerAllowed = false // incognito is switched on from the toolbar
        r.field.type("secret")
        r.engine.escape()
        r.rules.registerAllowed = true
        r.newField("xyz")
        val out = r.send(".")
        assertEquals(VimNotice.NOTHING_TO_REPEAT, out.notice)
        assertEquals("xyz", r.text)
        assertNull(r.engine.registers.lastInsert)
    }

    @Test fun theControlOfTheDotRecord_WhereCaptureStaysAllowedTheTypedTextIsRepeated() {
        val r = VimRig("foo bar baz", 0)
        r.keys("cwsecret<Esc>")
        assertEquals("secret bar baz", r.text)
        r.newField("xyz")
        assertNull(r.send(".").notice)
        assertEquals("secret", r.text)
    }

    @Test fun theSubstituteStateIsNotKeptWhereCaptureIsOff() {
        val r = VimRig("x y x", 0, TestRules(registerAllowed = false))
        r.send(":s/x/hunter2/\n")
        assertEquals("hunter2 y x", r.text) // the command does its work
        assertNull(r.engine.registers.lastCommand)
        r.rules.registerAllowed = true
        r.newField("x")
        val out = r.send(":&&\n")
        assertEquals(VimNotice.NO_PATTERN, out.notice) // nothing was kept to repeat
        assertEquals("x", r.text)
    }

    @Test fun theControlOfTheSubstituteState_WhereCaptureIsAllowedTheCommandIsRepeated() {
        val r = VimRig("x y x", 0)
        r.send(":s/x/hunter2/\n")
        r.newField("x")
        assertNull(r.send(":&&\n").notice)
        assertEquals("hunter2", r.text)
    }
}
