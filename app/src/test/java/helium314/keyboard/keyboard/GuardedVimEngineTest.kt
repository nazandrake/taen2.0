// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import helium314.keyboard.latin.vim.FakeVimField
import helium314.keyboard.latin.vim.TestRules
import helium314.keyboard.latin.vim.VimEngine
import helium314.keyboard.latin.vim.VimMode
import helium314.keyboard.latin.vim.VimRules
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object VimBoom { val n = "foo bar baz".toInt() } // an initializer of a helper object that parses text: ExceptionInInitializerError

/** The guard around the engine: what the engine throws never gets out, whatever the exception and wherever in the engine it is thrown. */
class GuardedVimEngineTest {
    private class Fixture(text: String = "foo bar baz", caret: Int = 4, val rules: VimRules = TestRules()) {
        val field = FakeVimField(text, caret)
        val engine = VimEngine(field, rules)
        var failures = 0
        var onFailure: () -> Unit = { failures++; guard.reset() }
        val guard: GuardedVimEngine = GuardedVimEngine(engine) { onFailure() }
    }

    private fun Fixture.armedRead(error: () -> Throwable) { field.readAnswer = { throw error() } }

    @Test fun anExceptionWithTheFieldTextInItsMessageIsDroppedAndTheFallbackIsReturned() {
        val f = Fixture()
        f.guard.escape() // Normal mode
        assertEquals(VimMode.NORMAL, f.guard.mode)
        f.guard.key('2'.code)
        f.armedRead { NumberFormatException("For input string: \"foo bar baz\"") }
        val outcome = f.guard.key('w'.code) // a motion reads the field
        assertEquals(1, f.failures)
        assertNull(outcome.notice)
        assertTrue(outcome.handled)
        assertFalse(outcome.failed)
        assertEquals(VimMode.INSERT, f.guard.mode, "onFailure reset the engine")
        assertEquals("", f.guard.pending, "no count is left")
        assertEquals("foo bar baz", f.field.text)
    }

    @Test fun everyThrowableButAVirtualMachineErrorIsDropped() {
        // Exceptions and Errors alike: a class initializer that fails is an Error that carries the cause (and so the text) in its stack trace
        for (error in listOf<Throwable>(IllegalStateException("x"), IllegalArgumentException("x"), NumberFormatException("x"), NullPointerException("x"), ClassCastException("x"),
            IndexOutOfBoundsException("x"), ArithmeticException("x"), UnsupportedOperationException("x"), IOException("a checked exception that Kotlin lets through"), Exception("plain"), RuntimeException(),
            AssertionError("x"), ExceptionInInitializerError(NumberFormatException("For input string: \"foo bar baz\"")), NoClassDefFoundError("Could not initialize class"), LinkageError("x"),
            NotImplementedError("An operation is not implemented: foo bar baz"), object : Error("a Kotlin or library Error built with the text") {})) {
            val f = Fixture()
            f.guard.escape()
            f.armedRead { error }
            f.guard.key('x'.code)
            assertEquals(1, f.failures, error.toString())
            assertEquals(VimMode.INSERT, f.guard.mode, error.toString())
        }
        // the JVM's own failures carry no caller text and are not ours to hide: rethrown as they are, nothing reset
        for (error in listOf<Throwable>(StackOverflowError(), OutOfMemoryError(), InternalError("x"), UnknownError("x"))) {
            val f = Fixture()
            f.guard.escape()
            f.armedRead { error }
            assertEquals(error, assertFailsWith<Throwable> { f.guard.key('x'.code) }, "a VirtualMachineError passes unchanged")
            assertEquals(0, f.failures)
            assertEquals(VimMode.NORMAL, f.guard.mode, "nothing was reset")
        }
    }

    @Test fun anObjectInitializerThatParsesTheFieldTextFailsWithAnErrorThatIsDropped() {
        // a real ExceptionInInitializerError (its cause is a NumberFormatException with the text), then the NoClassDefFoundError of every later use
        val f = Fixture()
        f.guard.escape()
        f.armedRead { VimBoom.n; throw IllegalStateException("unreachable") }
        f.guard.key('x'.code)
        assertEquals(1, f.failures)
        f.guard.escape() // Esc reads the field too: the second use of the object
        assertEquals(2, f.failures)
        val cause = runCatching { VimBoom.n }.exceptionOrNull()
        assertTrue(cause is NoClassDefFoundError, "positive control: a later use is a NoClassDefFoundError, an Error: $cause")
    }

    @Test fun anExceptionOfThePortOfTheRulesAndOfARunningLambdaIsDroppedToo() {
        fun failsOnce(what: String, f: Fixture, act: () -> Unit) { act(); assertEquals(1, f.failures, what) }
        val badPort = Fixture()
        badPort.guard.escape()
        failsOnce("port.read", badPort) { badPort.field.onRead = { throw IllegalStateException("text") }; badPort.guard.key('l'.code) }
        val badRules = object : VimRules {
            var armed = false
            override fun available() = if (armed) throw IllegalStateException("text") else true
            override fun registerAllowed() = true
            override fun accepts(text: String) = true
            override fun nowMillis() = 0L
            override fun indentWithTab() = false
        }
        val withRules = Fixture(rules = badRules)
        withRules.guard.escape()
        badRules.armed = true
        failsOnce("rules.available", withRules) { withRules.guard.key('l'.code) }
        badRules.armed = false
        val control = Fixture() // the control: the same engine without the failure answers normally
        control.guard.escape()
        control.guard.key('l'.code)
        assertEquals(0, control.failures)
    }

    @Test fun aFailureInsideTheFailureHandlerDoesNotStartAnotherOne() {
        val f = Fixture()
        f.guard.escape()
        f.armedRead { IllegalStateException("text") }
        f.onFailure = { f.failures++; f.guard.key('x'.code); f.guard.escape(); f.guard.mode } // each of these throws again
        f.guard.key('x'.code)
        assertEquals(1, f.failures)
        f.onFailure = { f.failures++ } // and it is armed again afterwards
        f.guard.key('x'.code)
        assertEquals(2, f.failures)
    }

    @Test fun aNormalCommandStillEditsExactlyAndTheRegisterAndNavigationWork() {
        val f = Fixture(caret = 5)
        f.guard.escape() // one character left: on the b of bar
        assertEquals(VimMode.NORMAL, f.guard.mode)
        "dw".forEach { f.guard.key(it.code) }
        assertEquals("foo baz", f.field.text)
        assertEquals(listOf("replace 4,8"), f.field.editCalls)
        f.guard.key('f'.code)
        assertTrue(f.guard.wantsChar)
        assertEquals("f", f.guard.pending)
        f.guard.key('z'.code)
        assertFalse(f.guard.wantsChar)
        assertEquals("", f.guard.pending)
        f.guard.clearMemory()
        assertNull(f.engine.register)
        f.guard.reset()
        assertEquals(VimMode.INSERT, f.guard.mode)
        assertEquals(0, f.failures)
    }

    // ---- wiring: no way into the engine that is not guarded

    private val main = File("src/main/java/helium314/keyboard")

    @Test fun theGuardHasEveryPublicMemberOfTheEngineAndNothingElse() {
        fun publicNames(c: Class<*>) = c.declaredMethods.filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.name.contains('$') }.map { it.name }.toSet()
        val engine = publicNames(VimEngine::class.java)
        assertTrue(engine.containsAll(setOf("escape", "key", "reset", "clearMemory", "captureChanged", "getMode", "getWantsChar", "getPending", "getCmdline", "getRecordingRegister")), "positive control: the list is read: $engine")
        assertEquals(engine, publicNames(GuardedVimEngine::class.java), "a public member of the engine without a guarded copy (or the other way round)")
    }

    /** Not every member can be made to throw by a fake (reading the mode, resetting), so the source is held to the rule: each public member's one expression is a guarded call. */
    @Test fun everyPublicMemberOfTheGuardRunsItsEngineCallInsideGuarded() {
        val members = File(main, "keyboard/GuardedVimEngine.kt").readLines().filter { Regex("""^    (val|fun) """).containsMatchIn(it) }
        assertEquals(listOf("mode", "wantsChar", "pending", "cmdline", "recordingRegister", "clearMemory", "captureChanged", "reset", "escape", "key"), members.map { Regex("""^    (?:val|fun) (\w+)""").find(it)!!.groupValues[1] })
        for (m in members) assertTrue(Regex("""= guarded\(|get\(\) = guarded\(""").containsMatchIn(m) && m.contains("engine."), "not guarded: $m")
    }

    @Test fun theKeyboardHoldsTheEngineOnlyThroughTheGuard() {
        assertTrue(VimInput::class.java.declaredFields.none { it.type == VimEngine::class.java }, "VimInput has no reference to a raw engine")
        assertTrue(VimInput::class.java.declaredFields.any { it.type == GuardedVimEngine::class.java }, "positive control: it holds the guard")
        val constructions = main.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") && !it.path.contains("/latin/vim/") }
            .flatMap { f -> Regex("""\bVimEngine\(""").findAll(f.readText()).map { f.name } }.toList()
        assertEquals(listOf("VimInput.kt"), constructions, "the engine is built in one place")
        assertTrue(File(main, "keyboard/VimInput.kt").readText().contains("GuardedVimEngine(VimEngine(port, rules)"), "and built inside the guard")
    }
}
