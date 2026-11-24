// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shape that keeps the memory rule from being forgotten: everything the layer remembers between commands is in [VimMemory], and a new store
 * or a new writer cannot be added without this test naming it (and so without a row in VimStoresTest and a check of the capture rule).
 * 1. every field of VimMemory is either a store with a label or is on the short list of what is not a store;
 * 2. every public method of VimMemory is a reader or a writer, and each writer is called here with the capture rule saying no and leaves every store empty;
 * 3. nothing else in the package keeps state across commands: no class has a static mutable field, the objects of the package have no fields, and the
 *    engine's own fields that are not numbers, flags or enums are all on a list with the reason each one is not a memory.
 */
class VimStoresStructureTest {
    // ---- 1. the stores ------------------------------------------------------------------------------------------------------------------

    /** The label that VimMemory.nonEmpty uses for each field that is a store; the fields that are no store are in [notStores]. */
    private val storeLabels = mapOf(
        "regs" to "registers", "marks" to "marks", "dot" to "dot", "dotOpen" to "dot", "recName" to "recording", "recBuf" to "recording",
        "recKeys" to "recording", "recBroken" to "recording", "macro" to "lastMacro", "find" to "lastFind", "searchFwd" to "searchState",
        "searchOff" to "searchState", "sub" to "substitute", "cmdHistory" to "commandHistory", "searchHistory" to "searchHistory",
    )

    /** Not a store: the rule object, the flag that tells the user once that a recording was dropped, and the flag of the insert session that is open (neither holds text). */
    private val notStores = setOf("rules", "recDropped", "insertClean")

    @Test fun everyFieldOfTheMemoryIsAStoreWithALabelOrIsKnownNotToBeOne() {
        val fields = VimMemory::class.java.declaredFields.filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(storeLabels.keys + notStores, fields, "a field was added to or removed from VimMemory: name it as a store (and give it a row in VimStoresTest) or as a non-store")
        // the registers' own labels (lastInsert, lastSearch, lastCommand) come from the register store's fields
        val labels = storeLabels.values.toSet() + setOf("lastInsert", "lastSearch", "lastCommand")
        val rules = TestRules()
        val m = VimMemory(rules)
        fill(m)
        assertEquals(labels, m.nonEmpty().toSet(), "filling every store through its writer must show every label (and nonEmpty must know every store)")
        m.clear()
        assertEquals(emptyList(), m.nonEmpty())
    }

    /** Fills every store through its writer, with the capture rule saying yes. */
    private fun fill(m: VimMemory) {
        m.yank('a', Reg("yank", RegType.CHAR))
        m.setLastInsert("typed")
        m.setLastCommand("s/a/b/")
        m.setSearch("pattern", true, VimSearch.Offset.NONE)
        m.setSubstitute("a", "b", "g")
        m.setLastFind('f', 'x'.code)
        m.setLastMacro('a')
        m.addHistory(true, "cmd")
        m.addHistory(false, "search")
        m.markSet('a', "text", 1)
        m.dotSet(null, null, listOf('x'.code), null, openForInsert = false)
        m.recordStart('b')
        m.recordKey('l'.code)
        // an offset of the last search that is not the default also counts as search state
        m.setSearch("pattern", false, VimSearch.Offset.NONE)
    }

    // ---- 2. every writer asks the rule ----------------------------------------------------------------------------------------------------

    private val readers = setOf(
        "get", "getUnnamed", "names", "getLastInsert", "getLastSearch", "getLastCommand", "writable", "getSearchForward", "getSearchOffset", "getSubstitute", "getLastFind",
        "getLastMacro", "history", "getMarksBase", "markGet", "markIsSet", "markNames", "dotRepeat", "getRecording", "takeDropped", "nonEmpty", "mayKeep",
        "typedMayBeKept",
    )

    private val writers: Map<String, (VimMemory) -> Unit> = mapOf(
        "clear" to { m -> m.clear() },
        "yank" to { m -> m.yank('a', Reg("secret", RegType.CHAR)); m.yank(null, Reg("secret", RegType.LINE)) },
        "delete" to { m -> m.delete(null, Reg("secret", RegType.CHAR), false, false); m.delete('b', Reg("secret", RegType.LINE), true, false) },
        "store" to { m -> m.store('a', Reg("secret", RegType.CHAR)) },
        "point" to { m -> m.point(Reg("secret", RegType.CHAR)) },
        "setLastInsert" to { m -> m.setLastInsert("secret") },
        "setLastTyped" to { m -> m.setLastTyped("secret") },
        "setLastCommand" to { m -> m.setLastCommand("secret") },
        "setSearch" to { m -> m.setSearch("secret", false, VimSearch.Offset.NONE) },
        "setSearchPattern" to { m -> m.setSearchPattern("secret") },
        "setSubstitute" to { m -> m.setSubstitute("secret", "secret", "g") },
        "setLastFind" to { m -> m.setLastFind('f', 's'.code) },
        "setLastMacro" to { m -> m.setLastMacro('a') },
        "addHistory" to { m -> m.addHistory(true, "secret"); m.addHistory(false, "secret") },
        "markSet" to { m -> m.markSet('a', "secret", 1) },
        "marksForgetIfChanged" to { m -> m.marksForgetIfChanged("secret") },
        "marksClear" to { m -> m.marksClear() },
        "marksRemapEdit" to { m -> m.marksRemapEdit("secret", 0, 1, "x") },
        "marksRemap" to { m -> m.marksRemap("secret", "other") },
        "marksAfterInsert" to { m -> m.marksAfterInsert("secret", 0, "x", "xsecret") },
        "insertMarks" to { m -> m.insertMarks("secret", 0, 1) },
        "dotSet" to { m -> m.dotSet(null, null, listOf('s'.code), null, openForInsert = true) },
        "dotTyped" to { m -> m.dotTyped("secret") },
        "dotBroken" to { m -> m.dotBroken() },
        "dotCloseInsert" to { m -> m.dotCloseInsert() },
        "recordStart" to { m -> m.recordStart('a') },
        "recordKey" to { m -> m.recordKey('s'.code) },
        "recordTyped" to { m -> m.recordTyped("secret") },
        "recordBroken" to { m -> m.recordBroken() },
        "recordStop" to { m -> m.recordStop() },
        "sync" to { m -> m.sync() },
        "insertBegin" to { m -> m.insertBegin() },
        "taint" to { m -> m.taint() }, // last: a tainted session refuses the typed text, which the positive control below does not want to meet before the other writers
    )

    @Test fun everyPublicMethodOfTheMemoryIsAReaderOrAWriterThatTheTestKnows() {
        val methods = VimMemory::class.java.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }.map { it.name }.toSet()
        assertEquals(readers + writers.keys, methods, "a method was added to VimMemory: it is a reader (add it to the readers) or a writer (add it to the writers, which are checked against the rule)")
    }

    @Test fun everyWriterLeavesEveryStoreEmptyWhereTheRuleSaysNo() {
        val rules = TestRules(registerAllowed = false)
        val m = VimMemory(rules)
        for ((name, write) in writers) {
            write(m)
            assertEquals(emptyList(), m.nonEmpty(), "$name filled a store where nothing may be kept")
        }
        // the positive control of the table: the same writers fill the stores where the rule says yes
        val allowed = VimMemory(TestRules())
        for ((name, write) in writers) if (name != "clear" && name != "marksClear" && name != "recordStop") write(allowed)
        assertTrue(allowed.nonEmpty().size >= 10, "the writers must reach the stores: ${allowed.nonEmpty()}")
    }

    @Test fun theRuleIsAskedAgainAtEveryWriteAndTheAnswerIsNeverKept() {
        val rules = TestRules()
        val m = VimMemory(rules)
        val before = rules.asked
        m.setLastInsert("a")
        m.setLastCommand("b")
        m.setSubstitute("a", "b", "")
        assertEquals(before + 3, rules.asked, "one question for each write")
        rules.registerAllowed = false
        m.setLastInsert("kept?")
        assertEquals("a", m.lastInsert, "a write that is refused leaves the old value; it does not replace it")
        // a recording and an open dot record are dropped the moment the answer turns to no
        rules.registerAllowed = true
        m.recordStart('a')
        m.dotSet(null, null, listOf('c'.code), null, openForInsert = true)
        rules.registerAllowed = false
        m.dotTyped("secret")
        m.recordTyped("secret")
        assertEquals(emptyList(), m.nonEmpty().filter { it == "recording" || it == "dot" })
        assertTrue(m.takeDropped())
    }

    // ---- 3. nothing else keeps state ----------------------------------------------------------------------------------------------------------

    private fun packageClasses(): List<Class<*>> {
        val url = VimEngine::class.java.getResource("VimEngine.class")!!
        val prefix = "helium314/keyboard/latin/vim/"
        val files: List<String> = if (url.protocol == "file") {
            File(url.toURI()).parentFile.listFiles { f -> f.name.endsWith(".class") }!!.map { it.name }
        } else { // the classes of the module sit in a jar of the test run
            java.util.zip.ZipFile(url.path.substringBefore("!").removePrefix("file:")).use { z ->
                z.entries().asSequence().map { it.name }.filter { it.startsWith(prefix) && it.endsWith(".class") && '/' !in it.removePrefix(prefix) }.map { it.removePrefix(prefix) }.toList()
            }
        }
        val classes = files.map { Class.forName("helium314.keyboard.latin.vim." + it.removeSuffix(".class")) }
        assertTrue(classes.size > 20, "the scan found only ${classes.size} classes of the package")
        return classes
    }

    @Test fun noClassOfThePackageHasAStaticMutableField() {
        for (c in packageClasses()) for (f in c.declaredFields) {
            if (Modifier.isStatic(f.modifiers) && !Modifier.isFinal(f.modifiers)) throw AssertionError("${c.name}.${f.name} is static and not final: state that outlives a field")
        }
    }

    @Test fun theObjectsOfThePackageHaveNoInstanceFields() {
        for (c in packageClasses()) {
            val isObject = c.declaredFields.any { it.name == "INSTANCE" && Modifier.isStatic(it.modifiers) }
            if (!isObject) continue
            val fields = c.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.map { it.name }
            assertEquals(emptyList(), fields, "${c.name} is an object: it must not hold state")
        }
    }

    /** The fields of the engine that are not numbers, flags or enums, and why each is not a memory (it is a collaborator, or it lives until the command ends). */
    private val engineFields = mapOf(
        "port" to "the field's input connection", "rules" to "the decisions of the keyboard", "io" to "the port, or the copy of the text while a play runs",
        "memory" to "the one holder", "run" to "the budget of the running command: counters and a clock, no text", "playQueue" to "the keys of the play that is running (Int codes), emptied at its end",
        "cmd" to "the command line being typed, until Enter or Esc", "ins" to "the text before the insert that is going on, until Esc",
        "cmdKeys" to "the keys of the command that is being typed (Int codes): dropped where nothing may be kept, emptied when the command ends",
        "visualDot" to "the size of the Visual area of the command that is being typed: numbers", "exVisualLines" to "two line numbers", "shownSelection" to "two offsets",
        "playStopped" to "a notice (enum)", "playFailure" to "a notice (enum)", "failure" to "a notice (enum)", "lastVisualMode" to "an enum",
        "blockRight" to "a column", "wantCol" to "a column",
    )

    @Test fun everyEngineFieldThatCouldHoldTextIsKnownAndIsEmptiedByReset() {
        val unknown = VimEngine::class.java.declaredFields.filter { f ->
            !Modifier.isStatic(f.modifiers) && !f.isSynthetic && !f.type.isPrimitive && !f.type.isEnum && f.name !in engineFields &&
                f.type != java.lang.Integer::class.java && f.type != java.lang.Boolean::class.java && f.type != java.lang.Character::class.java
        }.map { "${it.name}: ${it.type.simpleName}" }
        assertEquals(emptyList(), unknown, "a field was added to VimEngine: a memory belongs in VimMemory; give a transient one a reason in this table and a reset")
        // what lives until the command ends is gone after reset(): the line being typed and the pending command
        val field = FakeVimField("foo bar", 0)
        val e = VimEngine(field, TestRules())
        e.escape()
        e.key(':'.code)
        e.key('x'.code)
        assertEquals(":x", e.cmdline)
        e.reset()
        assertEquals(null, e.cmdline)
        assertEquals("", e.pending)
    }
}
