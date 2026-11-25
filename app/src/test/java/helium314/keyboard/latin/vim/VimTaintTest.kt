// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The text typed in insert mode does not pass through the engine: the typing keyboard writes it to the field and the engine reads the difference at Esc.
 * So the answer of the capture rule at Esc says nothing about the time before. The session carries one flag, "capture allowed throughout": set when the
 * session starts, cleared by any event the adapter reports (VimEngine.captureChanged) and by any key that meets a "no", never set again. A session whose
 * flag is cleared keeps nothing: not the last inserted text, not the "." record, not the recording, not the marks that hold a copy of the field.
 */
class VimTaintTest {
    /** One way into insert mode, with the text it works on. */
    private class Kind(val name: String, val text: String, val begin: String)

    private val kinds = listOf(
        Kind("cw", "foo bar", "cw"), Kind("cc", "foo bar", "cc"), Kind("C", "foo bar", "C"), Kind("s", "foo bar", "s"), Kind("i", "foo bar", "i"),
        Kind("a", "foo bar", "a"), Kind("A", "foo bar", "A"), Kind("I", "foo bar", "I"), Kind("o", "foo bar", "o"), Kind("O", "foo bar", "O"),
        Kind("R", "foo bar", "R"), Kind("count i", "foo bar", "3i"), Kind("visual c", "foo bar", "vec"),
        Kind("block I", "ab\ncd", "<C-v>jI"), Kind("block A", "ab\ncd", "<C-v>jA"), Kind("block c", "ab\ncd", "<C-v>jc"),
    )

    /** What the layer kept of one insert session. */
    private class Kept(val lastInsert: String?, val recording: Char?, val marksBase: String?, val dot: VimNotice?, val dotText: String)

    /** `qa`, the insert of [k], "ab" typed, [event], "SECRET" typed, Esc; then what is remembered, and what "." does in a new field. */
    private fun session(k: Kind, event: (VimRig) -> Unit): Kept {
        val r = VimRig(k.text, 0)
        r.keys("ma") // a mark that exists before the insert: it holds a copy of the field and follows the typed text
        r.keys("qa" + k.begin)
        r.field.type("ab")
        event(r)
        r.field.type("SECRET")
        r.keys("<Esc>")
        val m = r.engine.registers
        val lastInsert = m.lastInsert
        val recording = r.engine.recordingRegister
        val marksBase = m.marksBase
        r.newField("xyz")
        val dot = r.send(".")
        return Kept(lastInsert, recording, marksBase, dot.notice, r.text)
    }

    @Test fun aSessionThatTheAdapterReportedIsDroppedWholeInEveryKindOfInsert() {
        for (k in kinds) {
            val control = session(k) { }
            assertEquals("abSECRET", control.lastInsert, "${k.name}: the control keeps the typed text")
            assertEquals('a', control.recording, "${k.name}: the control is still recording")
            assertNotNull(control.marksBase, "${k.name}: the control keeps the marks of the insert")
            assertNull(control.dot, "${k.name}: the control repeats")
            assertTrue(control.dotText != "xyz", "${k.name}: the control's repeat changed the field")
            val tainted = session(k) { it.engine.captureChanged() }
            assertNull(tainted.lastInsert, "${k.name}: last inserted text")
            assertNull(tainted.recording, "${k.name}: recording")
            assertNull(tainted.marksBase, "${k.name}: marks (they hold a copy of the field)")
            assertEquals(VimNotice.NOTHING_TO_REPEAT, tainted.dot, "${k.name}: the repeat record")
            assertEquals("xyz", tainted.dotText, "${k.name}: nothing was typed into the field by the repeat")
        }
    }

    @Test fun textPutByCtrlRAndTakenBackByCtrlWInASessionFollowsTheFlagOfTheSession() {
        for ((event, expected) in listOf<Pair<(VimRig) -> Unit, String?>>({ _: VimRig -> } to "abfoo ", { r: VimRig -> r.engine.captureChanged() } to null)) {
            val r = VimRig("foo bar", 0)
            r.keys("\"ayw") // register a holds "foo "
            r.keys("Aab<C-r>a") // the register's text is put into the field by the engine, it is part of what the session typed
            event(r)
            r.field.type("SECRET")
            r.keys("<C-w><Esc>") // the typed word is taken back: what is left is "abfoo "
            assertEquals(expected, r.engine.registers.lastInsert)
        }
    }

    @Test fun theFlagIsSetWhenTheSessionStartsSoASessionBegunWhereNothingMayBeKeptKeepsNothingEvenIfTheRuleTurnsToYes() {
        for (k in kinds) {
            val rules = TestRules(registerAllowed = false)
            val r = VimRig(k.text, 0, rules)
            r.keys(k.begin)
            rules.registerAllowed = true // no event reaches the engine: the flag was cleared at the start
            r.field.type("SECRET")
            r.keys("<Esc>")
            val m = r.engine.registers
            assertNull(m.lastInsert, k.name)
            assertNull(m.marksBase, k.name)
            r.newField("xyz")
            assertEquals(VimNotice.NOTHING_TO_REPEAT, r.send(".").notice, k.name)
        }
    }

    @Test fun aKeyThatMeetsANoClearsTheFlagToo() {
        val rules = TestRules()
        val r = VimRig("foo bar", 0, rules)
        r.keys("cwab")
        rules.registerAllowed = false
        r.engine.key('x'.code) // in insert mode the engine only sees this key to ignore it; it still asks the rule first
        rules.registerAllowed = true
        r.field.type("SECRET")
        r.keys("<Esc>")
        assertNull(r.engine.registers.lastInsert)
        r.newField("xyz")
        assertEquals(VimNotice.NOTHING_TO_REPEAT, r.send(".").notice)
    }

    @Test fun theFlagOnlyGoesFromAllowedToNotAllowedAndTheNextSessionStartsClean() {
        val r = VimRig("foo bar", 0)
        r.keys("cwab")
        r.engine.captureChanged()
        r.keys("<Esc>")
        assertNull(r.engine.registers.lastInsert)
        // a later session is a new one: it keeps what is typed in it
        r.keys("cwxy<Esc>")
        assertEquals("xy", r.engine.registers.lastInsert)
        r.newField("abc")
        assertNull(r.send(".").notice)
        assertEquals("xy", r.text)
        // an event between two sessions (no session is open) leaves the next one alone
        r.engine.captureChanged()
        r.newField("foo bar")
        r.keys("cwzz<Esc>")
        assertEquals("zz", r.engine.registers.lastInsert)
    }

    @Test fun anEventDropsTheRecordingAtOnceAndTheNextKeyTellsTheUser() {
        val r = VimRig("foo bar", 0)
        r.keys("qa")
        assertEquals('a', r.engine.recordingRegister)
        r.keys("x")
        r.engine.captureChanged()
        assertNull(r.engine.recordingRegister)
        assertEquals(VimNotice.RECORDING_DROPPED, r.engine.key('l'.code).notice)
        assertNull(r.engine.registers.get('a')) // nothing of it is kept
        assertNull(r.engine.key('l'.code).notice) // told once
        // the control: the same keys with no event record
        val c = VimRig("foo bar", 0)
        c.keys("qaxq")
        assertEquals("x", c.engine.registers.get('a')!!.text)
    }

    @Test fun anEventDropsTheRepeatRecordOfAnInsertThatIsOpenButNotOneThatIsComplete() {
        val r = VimRig("foo bar", 0)
        r.keys("xx") // "." now repeats x: a complete record
        r.engine.captureChanged()
        assertNull(r.send(".").notice) // kept
        r.keys("cw") // an open record: its typed text is still to come
        r.engine.captureChanged()
        r.keys("<Esc>")
        r.newField("abc")
        assertEquals(VimNotice.NOTHING_TO_REPEAT, r.send(".").notice)
    }

    @Test fun aSessionThatIsLeftForOneNormalCommandWithCtrlOKeepsNothingOfItsTypedText() {
        for (event in listOf<(VimRig) -> Unit>({ }, { it.engine.captureChanged() })) {
            val r = VimRig("foo bar", 0)
            r.keys("qaAab")
            event(r)
            r.field.type("SECRET")
            r.keys("<C-o>") // the insert so far ends here: its text is not told to the "." record or the "." register in either case
            assertNull(r.engine.registers.lastInsert)
            assertNull(r.engine.registers.marksBase, "the marks held a copy of the field with the typed text")
        }
    }

    // ---- the holder: a second layer, whatever the engine does ----------------------------------------------------------------------------

    @Test fun theHolderRefusesTheTypedTextOfATaintedSessionEvenWhereTheRuleSaysYes() {
        val m = VimMemory(TestRules())
        m.insertBegin()
        assertTrue(m.typedMayBeKept())
        m.taint()
        assertTrue(!m.typedMayBeKept())
        m.setLastTyped("secret")
        m.marksAfterInsert("a", 0, "secret", "secretA")
        m.markSet('a', "text", 1)
        m.marksAfterInsert("text", 0, "secret", "secrettext")
        m.insertMarks("secrettext", 0, 6)
        assertNull(m.lastInsert)
        assertNull(m.marksBase) // marks of the session are forgotten, not remapped to a text that holds what was typed
        m.dotSet(null, null, listOf('c'.code), null, openForInsert = true)
        m.dotTyped("secret") // the session is tainted and its record is open: dropped whole
        assertEquals(DotRepeat.None, m.dotRepeat(null))
        m.recordStart('a')
        m.recordTyped("secret")
        assertNull(m.recording)
        assertTrue(m.takeDropped())
        // a new session starts clean where the rule allows it, and the same writes are kept
        m.insertBegin()
        assertTrue(m.typedMayBeKept())
        m.setLastTyped("kept")
        assertEquals("kept", m.lastInsert)
    }

    @Test fun clearTaintsTheOpenSessionToo() {
        val m = VimMemory(TestRules())
        m.insertBegin()
        m.clear()
        assertTrue(!m.typedMayBeKept())
        m.setLastTyped("secret")
        assertEquals(emptyList(), m.nonEmpty())
    }
}
