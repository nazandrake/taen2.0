// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every store of the layer, filled through the keys of a user: where the clipboard capture rule says no it stays empty, also when the rule turns
 * to "no" in the middle of a command, an insert or a recording; and where capture is allowed the same keys fill it (the positive control of each
 * row, so that an empty store means the path was reached and refused). A store is probed through the engine's own behaviour (the register, "n",
 * ".", a mark jump, the history of the command line...), as the user would meet it.
 */
class VimStoresTest {
    private class Store(val name: String, val steps: List<String>, val text: String = "a b o c o\nfoo xy", val caret: Int = 0, val filled: (VimRig) -> Boolean)

    private fun cmd(c: String) = listOf(":") + c.map { it.toString() } + listOf("<CR>")
    private fun search(kind: String, p: String) = listOf(kind) + p.map { it.toString() } + listOf("<CR>")

    private val stores = listOf(
        Store("unnamed register and register 0", listOf("y", "w")) { r -> r.engine.registers.unnamed != null && r.engine.registers.get('0') != null },
        Store("a named register", listOf("\"", "a", "y", "w")) { r -> r.engine.registers.get('a') != null },
        Store("the small delete register", listOf("d", "w")) { r -> r.engine.registers.get('-') != null },
        Store("the delete ring", listOf("d", "d")) { r -> r.engine.registers.get('1') != null },
        Store("the last inserted text", listOf("i", "x", "<Esc>")) { r -> r.engine.registers.lastInsert != null },
        Store("the last search", search("/", "foo")) { r -> r.engine.registers.lastSearch != null },
        Store("the last command line", cmd("s/a/b/")) { r -> r.engine.registers.lastCommand != null },
        Store("the dot record of a change", listOf("x")) { r -> r.send(".").notice != VimNotice.NOTHING_TO_REPEAT },
        Store("the dot record with its typed text", listOf("c", "w", "Z", "<Esc>")) { r ->
            r.rules.registerAllowed = true
            r.newField("abc def")
            r.send(".")
            r.text.startsWith("Z")
        },
        Store("a macro", listOf("q", "a", "x", "q")) { r -> r.engine.registers.get('a') != null },
        Store("a recording in progress", listOf("q", "a")) { r -> r.engine.recordingRegister != null },
        Store("a mark", listOf("m", "a")) { r -> r.send("'a").notice != VimNotice.MARK_NOT_SET },
        Store("the change marks of a yank", listOf("y", "w")) { r -> r.send("'[").notice != VimNotice.MARK_NOT_SET },
        Store("the visual marks", listOf("v", "l", "<Esc>")) { r -> r.send("'<").notice != VimNotice.MARK_NOT_SET },
        Store("the last find", listOf("f", "o")) { r ->
            val before = r.field.selStart
            r.send(";")
            r.field.selStart != before
        },
        Store("the last substitute", cmd("s/a/b/")) { r ->
            r.rules.registerAllowed = true
            r.newField("a")
            r.send(":&&\n").notice != VimNotice.NO_PATTERN
        },
        Store("the history of the command line", cmd("s/a/b/")) { r ->
            r.send(":")
            r.engine.key(VimEngine.HISTORY_PREVIOUS)
            val line = r.engine.cmdline
            r.engine.escape()
            line != ":"
        },
        Store("the history of searches", search("/", "foo")) { r ->
            r.send("/")
            r.engine.key(VimEngine.HISTORY_PREVIOUS)
            val line = r.engine.cmdline
            r.engine.escape()
            line != "/"
        },
    )

    private fun run(store: Store, allowedAtStart: Boolean, flipBefore: Int?): VimRig {
        val r = VimRig(store.text, store.caret, TestRules(registerAllowed = allowedAtStart))
        for ((i, step) in store.steps.withIndex()) {
            if (i == flipBefore) r.rules.registerAllowed = false // the toolbar switches incognito on between two keys of the command
            r.keys(step)
        }
        return r
    }

    @Test fun everyStoreIsFilledWhereCaptureIsAllowed() {
        for (s in stores) assertTrue(s.filled(run(s, true, null)), "${s.name}: the keys ${s.steps} must fill it where capture is allowed")
    }

    @Test fun noStoreIsFilledWhereCaptureIsNotAllowed() {
        for (s in stores) assertFalse(s.filled(run(s, false, null)), "${s.name} was filled in a field where nothing may be kept")
    }

    @Test fun noStoreIsFilledWhenTheDecisionTurnsToNoInTheMiddleOfTheCommand() {
        for (s in stores) for (k in s.steps.indices) {
            assertFalse(s.filled(run(s, true, k)), "${s.name} was filled although the decision turned to no before step $k (${s.steps[k]})")
        }
    }

    @Test fun theTypedTextOfAChangeIsNotKeptAfterTheSwitchWhateverFollows() {
        // the secret is typed after the switch; none of what is kept may hold it, and "." of the field after it does not type it
        val r = VimRig("foo bar", 0)
        r.keys("cw")
        r.rules.registerAllowed = false
        r.field.type("secret")
        r.engine.escape()
        assertNull(r.engine.registers.lastInsert)
        for (n in r.engine.registers.names()) assertFalse(r.engine.registers.get(n)!!.text.contains("secret"), "register $n")
        r.rules.registerAllowed = true
        r.newField("xyz")
        assertEquals(VimNotice.NOTHING_TO_REPEAT, r.send(".").notice)
        assertEquals("xyz", r.text)
    }

    @Test fun marksAreForgottenWhenAnEditIsMadeWhereNothingMayBeKeptNow() {
        val rules = TestRules()
        val r = VimRig("foo bar", 0, rules)
        r.send("ma")
        assertNull(r.send("'a").notice) // the control: the mark is there
        rules.registerAllowed = false
        r.send("x") // an edit through the engine: the marks would be moved to the new text, which is text of a field where nothing may be kept
        rules.registerAllowed = true
        assertEquals(VimNotice.MARK_NOT_SET, r.send("'a").notice)
    }

    @Test fun aCommandThatBeganWhereNothingMayBeKeptIsNoRecordEvenIfTheDecisionTurnsBackBeforeItEnds() {
        val rules = TestRules()
        val r = VimRig("a b c d e f", 0, rules)
        r.send("d") // the operator is pending: its key is part of the record that would be made
        rules.registerAllowed = false
        r.send("2") // the next key finds that nothing may be kept now: the keys so far are dropped
        rules.registerAllowed = true
        r.send("w") // the command ends where capture is allowed again; "d2w" is done, but what "." would repeat is only "2w" without its operator
        assertEquals("c d e f", r.text)
        assertEquals(VimNotice.NOTHING_TO_REPEAT, r.send(".").notice)
        assertEquals("c d e f", r.text)
        // the control: with no switch the same keys make a record
        val c = VimRig("a b c d e f", 0)
        c.send("d2w")
        assertNull(c.send(".").notice)
        assertEquals("e f", c.text)
    }

    @Test fun aRecordingStopsAndItsTypedTextIsDroppedWhenTheDecisionTurnsToNoInsideAnInsert() {
        val r = VimRig("foo bar", 0)
        r.keys("qa")
        r.keys("A")
        r.rules.registerAllowed = false
        r.field.type("secret")
        val out = r.engine.escape()
        assertEquals(VimNotice.RECORDING_DROPPED, out.notice)
        assertNull(r.engine.recordingRegister)
        r.rules.registerAllowed = true
        r.keys("q") // nothing is recorded now: this starts nothing that stores "secret"
        assertNull(r.engine.registers.get('a'))
        r.engine.registers.get('a')?.let { assertFalse(it.text.contains("secret")) }
    }

    @Test fun aMacroThatWasKeptStillPlaysWhereNothingIsKeptButLeavesNoMemoryOfThePlay() {
        val rules = TestRules()
        val r = VimRig("a b o c o", 0, rules)
        r.engine.registers.store('a', Reg("x", RegType.CHAR))
        rules.registerAllowed = false
        assertNull(r.send("@a").notice)
        assertEquals(" b o c o", r.text)
        // "@@" is a typed memory too: where nothing may be kept it is not there
        assertEquals(VimNotice.REGISTER_EMPTY, r.send("@@").notice)
        // the control: where capture is allowed "@@" repeats the macro
        rules.registerAllowed = true
        r.send("@a")
        assertNull(r.send("@@").notice)
        assertEquals(" o c o", r.text) // the first play took the blank, "@@" took the b
    }

    @Test fun theSubstituteStateAndSearchHistoryComeBackEmptyAfterTheSettingTurnsOff() {
        val r = VimRig("a b a", 0)
        r.send(":s/a/Q/\n")
        r.send("/b\n")
        r.engine.clearMemory()
        r.newField("a")
        assertEquals(VimNotice.NO_PATTERN, r.send(":&&\n").notice)
        assertEquals(VimNotice.NO_PATTERN, r.send("n").notice)
        r.send("/")
        r.engine.key(VimEngine.HISTORY_PREVIOUS)
        assertEquals("/", r.engine.cmdline)
    }
}
