// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No input may make the Vim layer throw an OutOfMemoryError or a StackOverflowError (the guard of the keyboard lets those two through). The
 * nested forms run in a child JVM with a 64 MB heap and a 512 KB stack ([VimStressMain]); at its limit each one must answer with a notice and
 * leave the field as it was.
 */
class VimStressTest {
    private fun childOutput(): List<String> {
        fun location(c: Class<*>) = File(c.protectionDomain.codeSource.location.toURI()).path
        val classpath = listOf(VimStressMain::class.java, VimEngine::class.java, Unit::class.java).map { location(it) }.distinct().joinToString(File.pathSeparator)
        val java = File(System.getProperty("java.home"), "bin/java").path
        val child = ProcessBuilder(java, "-Xmx64m", "-Xss512k", "-cp", classpath, VimStressMain::class.java.name).redirectErrorStream(true).start()
        val lines = ArrayList<String>()
        val reader = Thread { child.inputStream.bufferedReader().forEachLine { lines.add(it) } }.also { it.isDaemon = true; it.start() }
        val finished = child.waitFor(240, TimeUnit.SECONDS)
        if (!finished) child.destroyForcibly() // the child is ours
        reader.join(5_000)
        assertTrue(finished, "the child JVM ended")
        return lines.toList()
    }

    @Test fun theNestedFormsEndWithANoticeAndAnUnchangedFieldUnderASmallHeapAndStack() {
        assertEquals(
            listOf(
                "normal-at-10000-lines|PLAY_LIMIT|true",
                "normal-at-one-char|PLAY_LIMIT|true",
                "normal-at-longest-line|PLAY_LIMIT|true",
                "global-normal-at|PLAY_LIMIT|true",
                "range-normal-at|PLAY_LIMIT|true",
                "macro-calls-itself|PLAY_LIMIT|true",
                "macro-calls-itself-twice|PLAY_LIMIT|true",
                "macro-at-at|PLAY_LIMIT|true",
                "macro-chain-11|PLAY_LIMIT|true",
                "macro-chain-12-with-counts|PLAY_LIMIT|true",
                "macro-chain-of-normal-6|PLAY_LIMIT|true",
                "global-normal-chain-10|PLAY_LIMIT|true",
                "dot-in-chain-10|PLAY_LIMIT|true",
                // the controls: the deepest level that is allowed runs and changes the field
                "macro-chain-10-runs|null|false",
                "normal-chain-5-runs|null|false",
                "deepest-pattern-at-level-9|null|false",
            ),
            childOutput(),
        )
    }
}
