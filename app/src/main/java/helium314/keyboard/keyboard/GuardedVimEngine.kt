// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import helium314.keyboard.latin.vim.VimEngine
import helium314.keyboard.latin.vim.VimMode
import helium314.keyboard.latin.vim.VimOutcome

/**
 * The one door into the Vim engine. Library calls inside the engine put their input in an exception message (Integer.parseInt, Enum.valueOf,
 * a cast), and an exception that reaches the system is written to the log together with that message, which could hold field text. So every
 * call into the engine runs here: anything it throws (also from the port and the rules it calls, and from the lambdas it runs) is dropped
 * without reading, logging or keeping it, [onFailure] tells the user, and the call answers with the quiet fallback. That includes an Error that a
 * failing object initializer throws (ExceptionInInitializerError, whose cause carries the text). Only a VirtualMachineError (out of memory, stack
 * overflow) passes: it carries no caller text and is not ours to hide.
 * VimInput holds no other reference to a [VimEngine]; the class has the engine's public members and nothing else.
 */
internal class GuardedVimEngine(private val engine: VimEngine, private val onFailure: () -> Unit) {
    private var failing = false

    /** Whatever the call does, nothing but a VirtualMachineError gets out; a failure inside [onFailure] itself does not start another one. */
    private inline fun <T> guarded(fallback: T, call: () -> T): T = try {
        call()
    } catch (e: Throwable) {
        if (e is VirtualMachineError) throw e // no text of ours in it
        fail()
        fallback
    }

    private fun fail() {
        if (failing) return
        failing = true
        try { onFailure() } finally { failing = false }
    }

    val mode: VimMode get() = guarded(VimMode.INSERT) { engine.mode }

    val wantsChar: Boolean get() = guarded(false) { engine.wantsChar }

    val pending: String get() = guarded("") { engine.pending }

    fun clearRegister() = guarded(Unit) { engine.clearRegister() }

    fun reset() = guarded(Unit) { engine.reset() }

    fun escape(): VimOutcome = guarded(VimOutcome()) { engine.escape() }

    fun key(code: Int): VimOutcome = guarded(VimOutcome()) { engine.key(code) }
}
