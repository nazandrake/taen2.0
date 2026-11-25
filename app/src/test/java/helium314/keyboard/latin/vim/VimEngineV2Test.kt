// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the complete Vim package adds to the first version, beyond the table made by Vim: the bounds (macro length, the cap of a play, searches and
 * patterns that could run long), the privacy rules (nothing is remembered where the clipboard capture rule says no, everything is forgotten on
 * request, the system clipboard only through the copy and paste owners, command line text never in the field), and that a command is one text change.
 * The results of the commands themselves are Vim's (VimGoldenTest).
 */
class VimEngineV2Test {
    private class Rig(text: String, caret: Int, val rules: TestRules = TestRules()) {
        val field = FakeVimField(text, caret)
        val engine = VimEngine(field, rules)
        init { engine.escape() }
        fun keys(k: String) = engine.feed(field, k, stopOnFail = false)
        fun key(c: Int) = engine.key(c)
        fun press(k: Char) = engine.key(k.code)
        /** Presses each character as a key (the engine is not in insert mode) and returns what the last one answered. */
        fun send(k: String): VimOutcome {
            var out = VimOutcome()
            for (c in k) out = engine.key(c.code)
            return out
        }
        val regs get() = engine.registers
    }

    private fun text(r: Rig) = r.field.text

    // ---- bounds ---------------------------------------------------------------------------------------------------------------

    @Test fun aMacroOfExactlyTheLimitIsKeptAndOneKeyMoreIsDropped() {
        val r = Rig("x".repeat(3000), 0)
        r.keys("qa")
        assertEquals('a', r.engine.recordingRegister)
        repeat(VimEngine.MAX_MACRO_KEYS) { assertNull(r.press('l').notice) }
        r.press('q')
        assertEquals(VimEngine.MAX_MACRO_KEYS, r.regs.get('a')!!.text.length) // exactly 2 000 keys: kept
        assertNull(r.engine.recordingRegister)

        r.keys("qb")
        repeat(VimEngine.MAX_MACRO_KEYS) { r.press('l') }
        assertEquals(VimNotice.RECORDING_DROPPED, r.press('l').notice) // the 2 001st key
        assertNull(r.engine.recordingRegister)
        assertNull(r.regs.get('b')) // nothing of the recording is kept
        assertEquals(VimNotice.REGISTER_EMPTY, r.send("@b").notice)
    }

    /** The number of characters a play took away from [before] characters (the macros below delete one character per "x"). */
    private fun deleted(r: Rig, before: Int) = before - text(r).length

    @Test fun aRecursiveMacroStopsAtTheLimitOfOnePlayWithANotice() {
        val r = Rig("x".repeat(8000), 0)
        r.keys("qaq") // empties a
        r.regs.store('a', Reg("x@a", RegType.CHAR)) // the same macro as a recording of "x@a" would make
        val out = r.send("@a")
        assertEquals(VimNotice.PLAY_LIMIT, out.notice)
        // at the limit (20 000 keys) the whole play stops and nothing it did is written: the field is as it was (contract of the one budget)
        assertEquals(0, deleted(r, 8000))
        assertEquals(emptyList(), r.field.editCalls)
        // the engine goes on working: a normal command after the play
        r.keys("x")
        assertEquals(8000 - 1, text(r).length)
    }

    @Test fun nestedPlaysCountTowardsTheSameLimit() {
        val r = Rig("x".repeat(20_000), 0)
        r.regs.store('b', Reg("x".repeat(1000), RegType.CHAR))
        r.regs.store('a', Reg("@b".repeat(30), RegType.CHAR)) // 30 plays of 1 000 keys: 30 000, and two keys of each call
        assertEquals(VimNotice.PLAY_LIMIT, r.send("@a").notice)
        // 30 plays of 1 000 keys are over the 20 000 of one play, nested plays counted: the play stops as a whole and writes nothing
        assertEquals(0, deleted(r, 20_000))
        assertEquals(emptyList(), r.field.editCalls)
        // the control: plays of exactly 19 000 keys are not stopped
        val c = Rig("x".repeat(20_000), 0)
        c.regs.store('b', Reg("x".repeat(1000), RegType.CHAR))
        c.regs.store('a', Reg("@b".repeat(19), RegType.CHAR))
        assertNull(c.send("@a").notice)
        assertEquals(19_000, deleted(c, 20_000))
    }

    @Test fun aPlayThatTakesTooLongStopsAtTheTimeLimit() {
        val rules = TestRules()
        val r = Rig("x".repeat(10_000), 0, rules)
        r.regs.store('a', Reg("x".repeat(5000), RegType.CHAR))
        rules.tick = 100 // every look at the clock is 100 ms later; the limit is 2 500 ms, looked at every 64 keys
        assertEquals(VimNotice.PLAY_LIMIT, r.send("@a").notice)
        assertEquals(0, deleted(r, 10_000)) // the clock ended the play long before its 5 000 keys: nothing it did is written
        assertEquals(emptyList(), r.field.editCalls)
        // the control: the same macro with a clock that stands still runs to its end
        val still = Rig("x".repeat(10_000), 0)
        still.regs.store('a', Reg("x".repeat(5000), RegType.CHAR))
        assertNull(still.send("@a").notice)
        assertEquals(5000, deleted(still, 10_000))
    }

    @Test fun aSearchThatIsTooBigOrTooSlowChangesNothingAndSaysSo() {
        val big = "a".repeat(20_000)
        // the steps: a program of about 2 000 instructions over 20 000 characters
        val r = Rig(big, 0)
        r.keys("/\\(a\\{1,100}\\)\\{1,20}b")
        val out = r.press('\n')
        assertEquals(VimNotice.SEARCH_LIMIT, out.notice)
        assertEquals(0, r.field.selStart)
        assertEquals(emptyList(), r.field.editCalls)
        // the same through :s: nothing is replaced
        val s = Rig(big, 0)
        s.keys(":%s/\\(a\\{1,100}\\)\\{1,20}b/x/")
        assertEquals(VimNotice.SEARCH_LIMIT, s.press('\n').notice)
        assertEquals(big, text(s))
        assertEquals(emptyList(), s.field.editCalls)
        // the time: a clock that jumps
        val rules = TestRules()
        val t = Rig("a".repeat(5_000), 0, rules)
        rules.tick = 1_000
        t.keys("/a*b")
        assertEquals(VimNotice.SEARCH_LIMIT, t.press('\n').notice)
        // the control: with a clock that stands still the same search ends with "not found", not with the limit
        val c = Rig("a".repeat(5_000), 0)
        c.keys("/a*b")
        assertEquals(VimNotice.NOT_FOUND, c.press('\n').notice)
    }

    @Test fun aPatternOfAHugeProgramIsRefusedWithoutRunning() {
        val r = Rig("aaa", 0)
        r.keys(":s/a\\{1000}\\{1000}/x/")
        assertEquals(VimNotice.SEARCH_LIMIT, r.press('\n').notice)
        assertEquals("aaa", text(r))
        r.keys("/\\(a\\{1,100}\\)\\{1,100}\\{1,100}")
        assertEquals(VimNotice.SEARCH_LIMIT, r.press('\n').notice)
        r.keys("/a\\nb")
        assertEquals(VimNotice.PATTERN_UNSUPPORTED, r.press('\n').notice)
    }

    @Test fun aFailingCommandStopsThePlayAsInVim() {
        val r = Rig("ab\ncd", 0)
        r.keys("qaxjq") // x, then down (the fake field moves by lines)
        assertEquals("b\ncd", text(r))
        r.keys("5@a")
        // x on the second line, then j fails on the last line: the play ends there
        assertEquals("b\nd", text(r))
        assertEquals(2, r.field.editCalls.size) // the change made while recording, and one for the whole play
    }

    // ---- privacy ---------------------------------------------------------------------------------------------------------------

    private fun noCapture() = Rig("foo bar\nbaz qux", 0, TestRules(registerAllowed = false))

    @Test fun nothingIsRememberedWhereTheClipboardCaptureRuleSaysNo() {
        val r = noCapture()
        // registers: a yank says so and keeps nothing
        assertEquals(VimNotice.REGISTER_OFF, r.send("\"ayw").notice)
        assertEquals(VimNotice.REGISTER_OFF, r.send("\"Ayy").notice)
        assertEquals(VimNotice.REGISTER_OFF, r.send("yy").notice)
        assertNull(r.regs.get('a'))
        assertEquals(emptyList(), r.regs.names())
        // a delete is done, its text is not kept
        r.keys("dw")
        assertEquals("bar\nbaz qux", text(r))
        assertEquals(emptyList(), r.regs.names())
        // marks
        assertEquals(VimNotice.REGISTER_OFF, r.send("ma").notice)
        assertEquals(VimNotice.MARK_NOT_SET, r.send("'a").notice)
        // a macro is not recorded
        assertEquals(VimNotice.REGISTER_OFF, r.send("qa").notice)
        assertNull(r.engine.recordingRegister)
        // "." is not recorded
        r.keys("x")
        assertEquals("ar\nbaz qux", text(r))
        assertEquals(VimNotice.NOTHING_TO_REPEAT, r.send(".").notice)
        // the search works but its pattern is not kept
        assertNull(r.send("/baz\n").notice)
        assertEquals(3, r.field.selStart) // "ar\n" is 3 characters: the b of baz
        assertEquals(VimNotice.NO_PATTERN, r.send("n").notice)
        assertNull(r.regs.lastSearch)
        // an ex command works but is not remembered
        r.send(":s/a/A/\n")
        assertEquals("ar\nbAz qux", text(r)) // on the line of the cursor
        assertNull(r.regs.lastCommand)
        // typing in insert mode: the last inserted text is not kept
        r.keys("ix<Esc>")
        assertNull(r.regs.lastInsert)
        // the histories: a command line that walks its history finds nothing
        r.send(":")
        r.key(VimEngine.HISTORY_PREVIOUS)
        assertEquals(":", r.engine.cmdline)
    }

    @Test fun theControlOfThePrivacyTest_WhereCaptureIsAllowedEverythingIsRemembered() {
        val r = Rig("foo bar\nbaz qux", 0)
        assertNull(r.send("\"ayw").notice)
        assertEquals("foo ", r.regs.get('a')!!.text)
        assertNull(r.send("ma").notice)
        r.send("qb")
        assertEquals('b', r.engine.recordingRegister)
        r.send("lq")
        assertEquals("l", r.regs.get('b')!!.text)
        r.send("x")
        assertNull(r.send(".").notice)
        r.send("/baz\n")
        assertEquals("baz", r.regs.lastSearch)
        r.send(":s/o/0/\n")
        assertEquals("s/o/0/", r.regs.lastCommand)
        r.keys("iy<Esc>")
        assertEquals("y", r.regs.lastInsert)
        r.send(":")
        r.key(VimEngine.HISTORY_PREVIOUS)
        assertEquals(":s/o/0/", r.engine.cmdline)
    }

    @Test fun aRecordingStopsWhenTheFieldChangesToOneWhereCaptureIsNotAllowed() {
        val rules = TestRules()
        val r = Rig("foo bar", 0, rules)
        r.send("qa")
        r.send("l")
        rules.registerAllowed = false // the field changed to one where nothing may be kept
        assertEquals(VimNotice.RECORDING_DROPPED, r.send("l").notice)
        assertNull(r.engine.recordingRegister)
        assertEquals(VimNotice.REGISTER_OFF, r.send("qa").notice) // a new recording is refused here
        assertNull(r.regs.get('a'))
        // a play of a macro that was kept is still allowed there, as a paste is
        rules.registerAllowed = true
        r.regs.store('c', Reg("x", RegType.CHAR))
        rules.registerAllowed = false
        val before = r.field.selStart
        assertNull(r.send("@c").notice)
        assertEquals("foo bar".removeRange(before, before + 1), text(r))
    }

    @Test fun everythingIsForgottenOnRequest() {
        val r = Rig("foo bar\nbaz qux", 0)
        r.send("\"ayw")
        r.send("ma")
        r.send("qbxq")
        r.send("x.")
        r.send("/baz\n")
        r.send(":s/o/0/\n")
        r.keys("iy<Esc>")
        r.send("qc") // a recording in progress
        assertEquals('c', r.engine.recordingRegister)
        assertTrue(r.regs.names().isNotEmpty())
        r.engine.clearMemory()
        assertEquals(emptyList(), r.regs.names())
        assertNull(r.regs.lastInsert)
        assertNull(r.regs.lastSearch)
        assertNull(r.regs.lastCommand)
        assertNull(r.engine.recordingRegister)
        assertEquals(VimNotice.REGISTER_EMPTY, r.send("@a").notice)
        assertEquals(VimNotice.REGISTER_EMPTY, r.send("@b").notice)
        assertEquals(VimNotice.NOTHING_TO_REPEAT, r.send(".").notice)
        assertEquals(VimNotice.NO_PATTERN, r.send("n").notice)
        r.send(":")
        r.key(VimEngine.HISTORY_PREVIOUS)
        assertEquals(":", r.engine.cmdline)
        r.engine.escape()
        r.send("/")
        r.key(VimEngine.HISTORY_PREVIOUS)
        assertEquals("/", r.engine.cmdline)
        r.engine.escape()
        // marks belong to a field: they are gone with reset(), which the end of a field calls
        val m = Rig("foo bar", 0)
        m.send("wma")
        assertNull(m.send("'a").notice)
        m.engine.reset()
        m.engine.escape()
        assertEquals(VimNotice.MARK_NOT_SET, m.send("'a").notice)
    }

    @Test fun marksAreInvalidWhenTheTextChangedOutsideTheEngine() {
        val r = Rig("foo bar baz", 0)
        r.keys("wma")
        r.field.text = "changed behind the engine's back"
        r.field.selStart = 0
        r.field.selEnd = 0
        r.press('l') // any command that reads the field forgets marks that belong to other text
        assertEquals(VimNotice.MARK_INVALID, r.send("`a").notice)
    }

    // ---- the system clipboard -----------------------------------------------------------------------------------------------------

    @Test fun thePlusAndStarRegistersAreTheClipboardThroughTheCopyAndPasteOwners() {
        val r = Rig("foo bar", 0)
        r.keys("\"+yw")
        assertEquals("foo ", r.field.clipboard)
        assertEquals(listOf("clipboardWrite"), r.field.calls.filter { it.startsWith("clipboard") })
        r.field.clipboard = "from outside "
        r.keys("\"+P")
        assertEquals("from outside foo bar", text(r))
        r.field.clipboard = null
        assertEquals(VimNotice.CLIPBOARD_EMPTY, r.press('"').let { r.press('+'); r.press('p').notice })
        r.field.clipboard = "x\n" // a text that ends with a line break is put as a line
        r.keys("\"*p")
        assertEquals("from outside foo bar\nx", text(r))
        r.field.clipboard = "y"
        r.keys("A\u0000".take(1)) // A: insert at the end; the register is put with Ctrl-R in insert mode
        r.key(VimEngine.CTRL_R)
        assertTrue(r.engine.wantsChar)
        r.field.calls.clear()
        r.press('+')
        assertTrue(r.field.calls.contains("clipboardRead"))
    }

    @Test fun theClipboardOwnerMayRefuseTheWrite() {
        val r = Rig("foo bar", 0)
        r.field.clipboardRefuses = true
        r.keys("\"+yw")
        assertNull(r.field.clipboard)
        assertNull(r.regs.unnamed) // nothing was copied, so nothing is remembered
        r.press('"')
        r.press('+')
        r.press('y')
        assertEquals(VimNotice.CLIPBOARD_REFUSED, r.press('w').notice)
        val d = Rig("foo bar", 0)
        d.field.clipboardRefuses = true
        d.press('"'); d.press('+'); d.press('d')
        assertEquals(VimNotice.CLIPBOARD_REFUSED, d.press('w').notice) // a delete into the clipboard that is refused still deleted: the notice says so
    }

    @Test fun thePlusRegisterWritesTheClipboardEvenWhereNothingIsKept() {
        val r = noCapture()
        r.keys("\"+yw")
        assertEquals("foo ", r.field.clipboard) // as the copy key does in a field that keeps no history
        assertNull(r.regs.unnamed) // nothing is remembered
        assertEquals(VimNotice.REGISTER_OFF, r.press('p').notice)
    }

    @Test fun anOversizedClipboardIsRefusedAsARegisterValue() {
        val r = Rig("foo", 0)
        r.field.clipboard = "x".repeat(VimMemory.MAX_CHARS + 1)
        r.press('"'); r.press('+')
        assertEquals(VimNotice.CLIPBOARD_EMPTY, r.press('p').notice)
        assertEquals("foo", text(r))
        // a text of exactly the window is put into an empty field; into a field that has 3 characters it would be over the window and is refused
        val short = Rig("foo", 0)
        short.field.clipboard = "x".repeat(VimMemory.MAX_CHARS)
        short.press('"'); short.press('+')
        assertEquals(VimNotice.TOO_LONG, short.press('p').notice)
        assertEquals("foo", text(short))
        val empty = Rig("", 0)
        empty.field.clipboard = "x".repeat(VimMemory.MAX_CHARS)
        empty.keys("\"+p")
        assertEquals(VimMemory.MAX_CHARS, text(empty).length) // exactly the limit is put
    }

    @Test fun aRegisterHoldsAtMost20000CharactersWhenAppending() {
        val r = Rig("a".repeat(15_000) + "\n" + "b".repeat(15_000), 0)
        r.keys("\"ayy")
        assertEquals(15_000, r.regs.get('a')!!.text.length)
        r.press('j')
        r.press('"'); r.press('A'); r.press('y')
        assertEquals(VimNotice.REGISTER_FULL, r.press('y').notice) // 15 000 + 15 000 would be over the limit
        assertEquals(15_000, r.regs.get('a')!!.text.length)
    }

    // ---- the command line never reaches the field -----------------------------------------------------------------------------------

    @Test fun theCommandLineIsTypedIntoTheEngineAndNeverIntoTheField() {
        val r = Rig("foo bar", 0)
        r.press(':')
        assertEquals(":", r.engine.cmdline)
        assertTrue(r.engine.wantsChar, "the typing keyboard shows while the line is typed")
        for (c in "s/o/0/g") assertTrue(r.press(c).handled)
        assertEquals(":s/o/0/g", r.engine.cmdline)
        assertEquals("foo bar", text(r))
        assertEquals(emptyList(), r.field.editCalls)
        r.press('\n')
        assertEquals("f00 bar", text(r))
        assertEquals(1, r.field.editCalls.size)
        assertNull(r.engine.cmdline)
        // Backspace edits the line, Esc leaves it with nothing done
        r.press(':'); r.press('d')
        r.key(VimEngine.BACKSPACE); r.key(VimEngine.BACKSPACE)
        assertNull(r.engine.cmdline) // Backspace on an empty line leaves it
        r.press(':'); r.press('d')
        r.engine.escape()
        assertNull(r.engine.cmdline)
        assertEquals("f00 bar", text(r))
        assertEquals(1, r.field.editCalls.size)
    }

    @Test fun theCommandLineHistoryWalksUpAndDown() {
        val r = Rig("foo bar", 0)
        r.keys(":s/o/0/")
        r.press('\n')
        r.keys(":s/a/A/")
        r.press('\n')
        r.press(':')
        r.key(VimEngine.HISTORY_PREVIOUS)
        assertEquals(":s/a/A/", r.engine.cmdline)
        r.key(VimEngine.HISTORY_PREVIOUS)
        assertEquals(":s/o/0/", r.engine.cmdline)
        r.key(VimEngine.HISTORY_PREVIOUS) // at the oldest
        assertEquals(":s/o/0/", r.engine.cmdline)
        r.key(VimEngine.HISTORY_NEXT)
        assertEquals(":s/a/A/", r.engine.cmdline)
        r.key(VimEngine.HISTORY_NEXT)
        assertEquals(":", r.engine.cmdline) // back to what was typed
    }

    @Test fun aCommandLineIsBoundedInLength() {
        val r = Rig("foo", 0)
        r.press(':')
        repeat(VimEngine.MAX_CMDLINE + 500) { r.press('x') }
        assertTrue(r.engine.cmdline!!.length <= VimEngine.MAX_CMDLINE + 1, "${r.engine.cmdline!!.length} characters")
    }

    // ---- one command is one text change ---------------------------------------------------------------------------------------------

    private data class One(val setup: String, val keys: String, val result: String)

    @Test fun everyCommandIsExactlyOneTextChange() {
        val text = "foo bar baz\nline two\nthird line"
        val cases = listOf(
            One("", "J", "foo bar baz line two\nthird line"),
            One("", "3J", "foo bar baz line two third line"),
            One("", "gUU", "FOO BAR BAZ\nline two\nthird line"),
            One("", ">>", "    foo bar baz\nline two\nthird line"),
            One("", "3~", "FOO bar baz\nline two\nthird line"),
            One("", "dd", "line two\nthird line"),
            One("", "p", "foo bar baz\nline two\nthird line"), // nothing in the register: no change at all (see below)
            One("yw", "\$p", "foo bar bazfoo \nline two\nthird line"),
            One("", ":%s/o/0/g", "f00 bar baz\nline tw0\nthird line"),
            One("", ":g/line/d", "foo bar baz"),
            One("", ":%norm Ax", "foo bar bazx\nline twox\nthird linex"),
            One("", ":g/^/m0", "third line\nline two\nfoo bar baz"),
            One("", ":2,3t0", "line two\nthird line\nfoo bar baz\nline two\nthird line"),
            One("", ":1,2j", "foo bar baz line two\nthird line"),
            One("", ":%>", "    foo bar baz\n    line two\n    third line"),
            One("", ":%norm dw", "bar baz\ntwo\nline"),
            One("", ":g/o/norm A!", "foo bar baz!\nline two!\nthird line"),
            One("qaA!<Esc>jq", "@a", "foo bar baz!\nline two!\nthird line"),
            One("qaxjq", "5@a", ""),
            One("dw", ".", ""),
            One("x", "3.", ""),
            One("<C-v>jld", "", ""),
            One("", "<C-v>jlx", ""),
            One("", "V2>", "        foo bar baz\nline two\nthird line"),
            One("", "<C-a>", "foo bar baz\nline two\nthird line"), // no number: no change
        )
        for ((setup, keys, expected) in cases) {
            val r = Rig(text, 1)
            r.keys(setup)
            r.field.calls.clear()
            r.keys(if (keys.startsWith(":")) "$keys<CR>" else keys)
            val changes = r.field.editCalls.size
            assertTrue(changes <= 1, "$keys made $changes text changes: ${r.field.editCalls}")
            if (expected.isNotEmpty()) assertEquals(expected, text(r), keys)
            if (expected.isNotEmpty() && expected != text) assertEquals(1, changes, "$keys changed the text, so it is exactly one change")
        }
        // a play with an insert in it, repeated: still one change
        val r = Rig(text, 1)
        r.keys("qaA!<Esc>jq")
        r.field.calls.clear()
        r.keys("2@a")
        assertEquals(1, r.field.editCalls.size, "2@a: ${r.field.editCalls}")
        assertEquals("foo bar baz!\nline two!\nthird line!", text(r))
    }

    @Test fun aRepeatWithADotOfAChangeAndItsInsertIsOneChange() {
        val r = Rig("a b c d e f", 0)
        r.keys("cwX<Esc>")
        r.field.calls.clear()
        r.keys("w.")
        assertEquals("X X c d e f", text(r))
        assertEquals(1, r.field.editCalls.size)
    }

    // ---- the guard of an undo inside a play ------------------------------------------------------------------------------------------

    @Test fun undoInsideAMacroStopsItWithANotice() {
        val r = Rig("foo bar", 0)
        r.regs.store('a', Reg("xux", RegType.CHAR))
        r.press('@')
        assertEquals(VimNotice.UNDO_IN_PLAY, r.press('a').notice)
        assertTrue(r.field.calls.none { it == "undo" }, "the keyboard's undo was not pressed inside the play")
    }

    // ---- recording, repeat and registers: behaviours the table cannot show ---------------------------------------------------------------

    @Test fun theRecordedMacroHoldsTheTypedTextAndEscape() {
        val r = Rig("foo", 0)
        r.keys("qaAbar<Esc>q")
        assertEquals("A" + "bar" + "\u001b", r.regs.get('a')!!.text)
        assertEquals("foobar", text(r))
        r.keys("@a")
        assertEquals("foobarbar", text(r))
    }

    @Test fun anUpperCaseRegisterAppendsToTheMacro() {
        val r = Rig("foo bar", 0)
        r.keys("qaxq")
        r.keys("qAxq")
        assertEquals("xx", r.regs.get('a')!!.text)
    }

    @Test fun aRecordingThatCannotBeToldIsDropped() {
        val r = Rig("foo", 0)
        r.keys("qaA")
        // typing that deletes text before where the insert began: what was typed cannot be told
        r.field.type("x")
        r.field.selStart = 1
        r.field.selEnd = 1
        r.engine.escape()
        r.keys("q")
        assertNull(r.regs.get('a'))
    }

    @Test fun dotWithACountReplacesTheCountAndKeepsIt() {
        val r = Rig("a b c d e f g h", 0)
        r.keys("dw")
        assertEquals("b c d e f g h", text(r))
        r.keys("2.")
        assertEquals("d e f g h", text(r))
        r.keys(".") // the count 2 is kept
        assertEquals("f g h", text(r))
    }

    @Test fun theNumberedRegisterOfADotMovesOn() {
        val r = Rig("1\n2\n3\n4", 0)
        r.keys("dddddd") // registers 1 to 3 hold 3, 2, 1... deleted lines "1", "2", "3"
        r.keys("\"1p..")
        // "1p puts the last deleted line "3", then "2p, "3p with the dot: 3, 2, 1
        assertEquals("4\n3\n2\n1", text(r))
    }

    @Test fun nothingToRepeatAndNotRepeatableHaveNotices() {
        val r = Rig("foo", 0)
        assertEquals(VimNotice.NOTHING_TO_REPEAT, r.press('.').notice)
        r.keys("A")
        r.field.type("x")
        r.field.selStart = 0 // the user moved the caret and typed elsewhere: the typed text cannot be told
        r.field.selEnd = 0
        r.engine.escape()
        assertEquals(VimNotice.NOT_REPEATABLE, r.press('.').notice)
    }

    @Test fun screenCommandsAreSkippedWithANotice() {
        val r = Rig("foo", 0)
        for (k in listOf('H', 'M', 'L')) assertEquals(VimNotice.SCREEN_COMMAND, r.press(k).notice)
        for (c in listOf(VimEngine.CTRL_D, VimEngine.CTRL_U, VimEngine.CTRL_F, VimEngine.CTRL_B, VimEngine.CTRL_E, VimEngine.CTRL_Y)) assertEquals(VimNotice.SCREEN_COMMAND, r.key(c).notice)
        r.press('z')
        assertEquals(VimNotice.SCREEN_COMMAND, r.press('z').notice)
        assertEquals("foo", text(r))
    }

    @Test fun exCommandsThatAKeyboardCannotDoAnswerWithANotice() {
        val r = Rig("foo", 0)
        for ((cmd, notice) in listOf("w" to VimNotice.CANNOT_SAVE, "q" to VimNotice.CANNOT_SAVE, "x" to VimNotice.CANNOT_SAVE, "wq" to VimNotice.CANNOT_SAVE,
            "noh" to VimNotice.NO_HIGHLIGHT, "bogus" to VimNotice.EX_ERROR, "5,2d" to VimNotice.EX_ERROR, "2,9d" to VimNotice.EX_ERROR)) {
            r.press(':')
            for (c in cmd) r.press(c)
            assertEquals(notice, r.press('\n').notice, cmd)
        }
        assertEquals("foo", text(r))
    }

    @Test fun regAndMarksListWhatTheyHold() {
        val r = Rig("foo bar", 0)
        r.keys("\"ayw")
        r.keys("wma")
        r.keys(":reg")
        val reg = r.press('\n').message
        assertNotNull(reg)
        assertTrue(reg!!.contains("\"a  foo "), reg)
        r.keys(":marks")
        val marks = r.press('\n').message
        assertTrue(marks!!.contains("a  1:4"), marks)
    }

    @Test fun anIndentUsesATabOrFourSpacesAsTheSettingSays() {
        val spaces = Rig("foo", 0)
        spaces.keys(">>")
        assertEquals("    foo", text(spaces))
        val tabs = Rig("foo", 0, TestRules(tabs = true))
        tabs.keys(">>")
        assertEquals("\tfoo", text(tabs))
        tabs.keys("<<")
        assertEquals("foo", text(tabs))
    }

    @Test fun anInsertKeyOfInsertModeWorksOnlyWhereVimIsAvailable() {
        val r = Rig("foo bar", 0)
        r.keys("A")
        r.field.type(" baz")
        assertNull(r.key(VimEngine.CTRL_W).notice)
        assertEquals("foo bar ", text(r)) // Ctrl-W took the word that was typed
        r.rules.available = false
        val out = r.key(VimEngine.CTRL_W)
        assertEquals(VimNotice.UNAVAILABLE, out.notice)
        assertEquals("foo bar ", text(r)) // and does nothing where Vim is not available
        assertEquals(VimMode.INSERT, r.engine.mode)
        assertFalse(r.key('x'.code).handled) // an ordinary key in insert mode is for the typing path
    }
    @Test fun aCountedPlayThatWouldQueueTooManyKeysIsRefusedAtOnce() {
        val r = Rig("x".repeat(100), 0)
        r.regs.store('a', Reg("x".repeat(2000), RegType.CHAR))
        // 999 plays of 2 000 keys would be 1 998 000 keys: not queued at all, nothing is changed
        val out = r.send("999@a")
        assertEquals(VimNotice.PLAY_LIMIT, out.notice)
        assertEquals(100, text(r).length)
        assertEquals(emptyList(), r.field.editCalls)
        // the control: a count that stays inside the limit plays
        val c = Rig("x".repeat(100), 0)
        c.regs.store('a', Reg("x", RegType.CHAR))
        assertNull(c.send("10@a").notice)
        assertEquals(90, text(c).length)
    }

    @Test fun aRegisterOfExactlyTheLimitIsKept() {
        val r = Rig("a".repeat(VimMemory.MAX_CHARS), 0)
        r.keys("\"ayy")
        assertEquals(VimMemory.MAX_CHARS, r.regs.get('a')!!.text.length)
        r.keys("\"byw")
        assertEquals(VimMemory.MAX_CHARS, r.regs.get('b')!!.text.length)
        assertEquals(VimNotice.REGISTER_FULL, r.send("\"Ayy").notice) // one more would be over
        assertEquals(VimMemory.MAX_CHARS, r.regs.get('a')!!.text.length)
    }

    @Test fun theHistoriesKeepTheNewestFiftyCommands() {
        val r = Rig("foo", 0)
        for (i in 1..60) { r.send(":s/o/$i/\n") }
        r.send(":")
        var count = 0
        var last = ""
        while (true) {
            r.key(VimEngine.HISTORY_PREVIOUS)
            val line = r.engine.cmdline!!
            if (line == last) break
            last = line
            count++
        }
        assertEquals(VimEngine.MAX_HISTORY, count)
        assertEquals(":s/o/11/", last) // the oldest that is left
    }

    @Test fun marksAreForgottenByClearMemory() {
        val r = Rig("foo bar", 0)
        r.send("wma")
        r.send("0")
        assertNull(r.send("'a").notice) // the control: the mark is there
        r.engine.clearMemory()
        assertEquals(VimNotice.MARK_NOT_SET, r.send("'a").notice)
    }

    @Test fun theLastInsertAndTheDotRecordAreForgottenByClearMemory() {
        val r = Rig("foo bar", 0)
        r.keys("ix<Esc>")
        r.keys("x")
        assertNull(r.send(".").notice)
        r.engine.clearMemory()
        assertEquals(VimNotice.NOTHING_TO_REPEAT, r.send(".").notice)
        assertNull(r.regs.lastInsert)
    }
}
