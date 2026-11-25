// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

/**
 * The program that [VimStressTest] runs in a child JVM with a small heap and a small stack: every form of nested execution of the Vim layer, each
 * at and past its limit, on fields of the largest size. One line per scenario on the standard output: "name|notice|unchanged" or "name|THROWN class"
 * ("unchanged" compares the field after the last command with the field before it). Run in the child, so that an OutOfMemoryError or a
 * StackOverflowError that the layer lets through ends one scenario with a line (and the parent sees it) and not the test JVM.
 */
object VimStressMain {
    private class Scenario(val name: String, val text: String, val prepare: (VimRig) -> Unit = { }, val run: (VimRig) -> VimOutcome)

    /** 19 999 characters in 10 000 lines: the most lines a field of the largest size can have. */
    private val oneCharLines = (1..10_000).joinToString("\n") { "a" }
    private val longLine = "x".repeat(20_000)

    private fun VimRig.store(name: Char, text: String) { engine.registers.store(name, Reg(text, RegType.CHAR)) }

    /** Macros a, b, c...: each one calls the next with [call]; the last one does [last]. */
    private fun VimRig.chain(n: Int, call: (Char) -> String, last: String) {
        for (i in 0 until n) store('a' + i, if (i < n - 1) call('a' + i + 1) else last)
    }

    private val scenarios: List<Scenario> = listOf(
        Scenario("normal-at-10000-lines", oneCharLines) { it.send(":normal @:\n") },
        Scenario("normal-at-one-char", "x") { it.send(":normal @:\n") },
        Scenario("normal-at-longest-line", longLine) { it.send(":normal @:\n") },
        Scenario("global-normal-at", oneCharLines) { it.send(":g/^/normal @:\n") },
        Scenario("range-normal-at", oneCharLines) { it.send(":%normal @:\n") },
        Scenario("macro-calls-itself", longLine, { it.store('a', "x@a") }) { it.send("@a") },
        Scenario("macro-calls-itself-twice", longLine, { it.store('a', "x@a@a") }) { it.send("@a") },
        Scenario("macro-at-at", longLine, { it.store('b', "x@@") }) { it.send("@b") },
        Scenario("macro-chain-11", longLine, { it.chain(11, { c -> "@$c" }, "x") }) { it.send("@a") },
        Scenario("macro-chain-12-with-counts", longLine, { it.chain(12, { c -> "9@$c" }, "x") }) { it.send("99@a") },
        Scenario("macro-chain-of-normal-6", oneCharLines, { it.chain(6, { c -> ":normal @$c\n" }, ":normal x\n") }) { it.send("@a") },
        Scenario("global-normal-chain-10", oneCharLines, { it.chain(10, { c -> "@$c" }, "x") }) { it.send(":g/^/normal @a\n") },
        Scenario("dot-in-chain-10", longLine, { it.keys("x"); it.chain(10, { c -> "@$c" }, ".") }) { it.send("@a") },
        // the controls: the last level that is allowed runs and changes the field
        Scenario("macro-chain-10-runs", longLine, { it.chain(10, { c -> "@$c" }, "x") }) { it.send("@a") },
        Scenario("normal-chain-5-runs", oneCharLines, { it.chain(5, { c -> ":normal @$c\n" }, ":normal x\n") }) { it.send("@a") },
        // the deepest level that is allowed, with the deepest pattern the regex parser takes (99 nested groups): the stack of all the levels and of the parser together
        Scenario("deepest-pattern-at-level-9", oneCharLines, { it.chain(5, { c -> ":normal @$c\n" }, ":s/" + "\\%(".repeat(99) + "a" + "\\)".repeat(99) + "/b/\n") }) { it.send("@a") },
    )

    @JvmStatic fun main(args: Array<String>) {
        for (s in scenarios) {
            val line = try {
                val r = VimRig(s.text, 0)
                s.prepare(r)
                val before = r.text
                val out = s.run(r)
                "${s.name}|${out.notice}|${r.text == before}"
            } catch (t: Throwable) {
                "${s.name}|THROWN ${t.javaClass.name}"
            }
            println(line)
        }
    }
}
