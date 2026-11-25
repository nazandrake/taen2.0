// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The engine against Vim itself. src/test/resources/vim/golden.jsonl is written by tools/gen-vim-golden.py, which runs each
 * case in the real vim (text, cursor and keys in; text, cursor and the registers out). Nothing here needs vim.
 */
class VimGoldenTest {
    private class Row(val name: String, val text: String, val cursor: Int, val keys: String, val insert: Boolean,
                      val textAfter: String, val cursorAfter: Int, val reg: String, val regtype: String,
                      val regs: Map<String, Pair<String, String>>?, val indent: String, val cat: String)

    private fun rows(): List<Row> {
        val file = System.getenv("VIM_GOLDEN") // a table written to another place while the generator is being worked on
        val stream = if (file != null) java.io.File(file).inputStream()
        else javaClass.classLoader!!.getResourceAsStream("vim/golden.jsonl") ?: error("vim/golden.jsonl is missing: run tools/gen-vim-golden.py")
        return stream.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }.map {
            val o = Json.parseToJsonElement(it) as JsonObject
            fun str(k: String) = o.getValue(k).jsonPrimitive.content
            val opts = o["opts"]?.jsonObject
            val regs = if (opts == null) null else (o.getValue("regs") as JsonObject).entries.associate { (k, v) ->
                val a = v as JsonArray
                k to (a[0].jsonPrimitive.content to a[1].jsonPrimitive.content)
            }
            Row(str("name"), str("text"), o.getValue("cursor").jsonPrimitive.int, str("keys"), o.getValue("insert").jsonPrimitive.boolean,
                str("text_after"), o.getValue("cursor_after").jsonPrimitive.int, str("reg"), str("regtype"),
                regs, opts?.get("indent")?.jsonPrimitive?.content ?: "spaces", opts?.get("cat")?.jsonPrimitive?.content ?: "")
        }
    }

    /** What Vim's getreg and getregtype say for a register of the engine: the text (a line register ends with a line break), and the type. */
    private fun signature(r: Reg?): Pair<String, String>? = r?.let {
        when (it.type) {
            RegType.CHAR -> it.text to "v"
            RegType.LINE -> (it.text + "\n") to "V"
            RegType.BLOCK -> it.text to "\u0016${it.width}"
        }
    }

    /** Runs one case the way the keyboard does and returns what differs from Vim (empty when equal). */
    private fun run(row: Row): String? {
        val caret = row.text.offsetByCodePoints(0, row.cursor)
        val b = VimBuf(row.text)
        // the typing caret that leaves the cursor on this character after Esc: just after it (an empty line has only one place)
        val insertCaret = if (caret < b.lineEnd(caret)) b.next(caret) else caret
        val field = FakeVimField(row.text, insertCaret)
        val engine = VimEngine(field, TestRules(tabs = row.indent == "tab"))
        engine.escape()
        if (field.selStart != caret || field.selEnd != caret) return "Esc left the caret at ${field.selStart}, expected $caret"
        engine.feed(field, row.keys, stopOnFail = row.cat.isEmpty()) // the first group ran with :normal!, the second one as typed keys
        // a command that failed inside Visual mode leaves Vim in Visual mode, where the cursor is the end of the selection: Esc shows it
        if (engine.mode == VimMode.VISUAL || engine.mode == VimMode.VISUAL_LINE || engine.mode == VimMode.VISUAL_BLOCK) engine.escape()
        if (row.insert && engine.mode == VimMode.INSERT) {
            field.type("X")
            engine.escape()
        }
        val problems = ArrayList<String>()
        if (field.text != row.textAfter) problems.add("text '${field.text}' expected '${row.textAfter}'")
        val cursorAfter = if (field.selStart == field.selEnd) field.text.codePointCount(0, field.selStart) else -1
        // Vim can leave the cursor on the end of the line after a Visual command that ended there ($, or a word motion that ran out of
        // text); this layer puts it on the last character, where Vim moves it with the next key
        val endOfLineQuirk = (row.keys.first() in "vV" || row.keys.startsWith("<C-v>")) && field.selStart == field.selEnd && row.cursorAfter <= field.text.codePointCount(0, field.text.length) &&
            field.text.offsetByCodePoints(0, row.cursorAfter).let { at -> VimBuf(field.text).let { fb -> at == fb.lineEnd(at) && fb.lineStart(at) < at && field.selStart == fb.prev(at) } }
        if (cursorAfter != row.cursorAfter && !endOfLineQuirk) problems.add("cursor $cursorAfter (${field.selStart}-${field.selEnd}) expected ${row.cursorAfter}")
        val reg = engine.register ?: ""
        val regtype = signature(engine.registers.unnamed)?.second ?: ""
        if (reg != row.reg) problems.add("register '$reg' expected '${row.reg}'")
        if (reg.isNotEmpty() && regtype != row.regtype) problems.add("register type $regtype expected ${row.regtype}")
        val expected = row.regs
        if (expected != null) {
            for ((name, want) in expected) {
                val got = signature(engine.registers.get(name[0]))
                if (got != want) problems.add("register $name $got expected $want")
            }
            for (n in engine.registers.names()) if (n != '"' && !expected.containsKey(n.toString()) && engine.registers.get(n)?.text?.isNotEmpty() == true) problems.add("register $n holds ${signature(engine.registers.get(n))}, Vim has none")
        }
        return if (problems.isEmpty()) null else problems.joinToString("; ")
    }

    @Test fun theTableIsBigAndCoversEveryCategory() {
        val rows = rows()
        assertTrue(rows.size >= 3000, "only ${rows.size} cases")
        for (key in listOf("w", "b", "e", "dw", "cw", "dd", "yy", "p", "P", "x", "r", "f", "t", "vd", "Vd", "i\"", "iw", "3", "gg", "G", "$", "^", "0"))
            assertTrue(rows.any { it.keys.contains(key) }, "no case with $key")
        if (System.getenv("VIM_GOLDEN") != null) return
        val cats = rows.map { it.cat }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
        for (cat in listOf("dot", "macro", "register", "mark", "search", "ex", "object", "case", "number", "shift", "visual2", "block", "insert2"))
            assertTrue((cats[cat] ?: 0) >= 100, "only ${cats[cat]} cases of $cat")
        for (key in listOf("@a", "q", "\"a", "\"A", "\"1p", "ma", "`a", "'a", "/", "?", "n", "N", "*", "#", ":s/", ":g/", ":v/", ":m", ":t", ":j", ":norm", ":pu", ":%s", ":'<,'>", "J", "gJ", "g~", "gU", "gu",
            ">>", "<<", "<C-a>", "<C-x>", "<C-v>", "gv", "R", "gi", "<C-w>", "<C-u>", "<C-h>", "<C-o>", "<C-r>", "%", "}", ")", "2iw", "i(", "aw"))
            assertTrue(rows.any { it.keys.contains(key) }, "no case with $key")
    }

    @Test fun everyCaseMatchesVim() {
        val rows = rows()
        val failures = ArrayList<String>()
        val byCat = HashMap<String, Int>()
        for (row in rows) {
            val problem = try { run(row) } catch (e: Throwable) { "threw $e" }
            if (problem != null && row.name !in knownDeviations) {
                failures.add("${row.name}: $problem".replace("\n", "\\n"))
                byCat.merge(row.cat.ifEmpty { "first" }, 1, Int::plus)
            }
        }
        val limit = System.getenv("VIM_GOLDEN_SHOW")?.toIntOrNull() ?: 60
        assertEquals(emptyList(), failures.take(limit), "${failures.size} of ${rows.size} cases differ from Vim: $byCat")
    }

    /** Cases where this layer differs from Vim on purpose; each name is listed with the reason. */
    private val knownDeviations: Set<String> = emptySet()
}
