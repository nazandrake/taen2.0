// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoicePolicyTest {
    private fun decide(enabled: Boolean = true, password: Boolean = false, locked: Boolean = false, model: Boolean = true, permission: Boolean = true) =
        VoiceGate.decide(enabled, password, locked, model, permission)

    @Test fun allConditionsMetStarts() = assertEquals(VoiceGate.Action.START, decide())

    @Test fun theBlockersComeFirstWhateverElseIsMissing() {
        for (blocked in listOf(decide(enabled = false), decide(password = true), decide(locked = true),
            decide(enabled = false, password = true, locked = true, model = false, permission = false), decide(password = true, model = false), decide(locked = true, permission = false)))
            assertEquals(VoiceGate.Action.IGNORE, blocked)
    }

    @Test fun modelBeforePermission() {
        assertEquals(VoiceGate.Action.NEED_MODEL, decide(model = false))
        assertEquals(VoiceGate.Action.NEED_MODEL, decide(model = false, permission = false))
        assertEquals(VoiceGate.Action.NEED_PERMISSION, decide(permission = false))
    }

    @Test fun everyCombinationOfTheFiveInputsMatchesTheTable() { // the whole truth table, written out from the rule: blockers, then model, then permission
        for (bits in 0 until 32) {
            val enabled = bits and 1 != 0; val password = bits and 2 != 0; val locked = bits and 4 != 0; val model = bits and 8 != 0; val permission = bits and 16 != 0
            val expected = when {
                !enabled || password || locked -> VoiceGate.Action.IGNORE
                !model -> VoiceGate.Action.NEED_MODEL
                !permission -> VoiceGate.Action.NEED_PERMISSION
                else -> VoiceGate.Action.START
            }
            assertEquals(expected, decide(enabled, password, locked, model, permission), "bits $bits")
        }
    }

    // ---- spacing of the inserted text ------------------------------------------------------------------------------

    @Test fun textAfterAWordOrPunctuationGetsOneLeadingSpace() {
        assertEquals(" and then", VoiceText.forInsertion("d", "and then"))
        assertEquals(" Next.", VoiceText.forInsertion(".", "Next."))
        assertEquals(" изречение", VoiceText.forInsertion("а", "изречение"))
        assertEquals(" x", VoiceText.forInsertion("5", "x"))
    }

    @Test fun noSpaceAtTheStartAfterWhitespaceOrAnOpeningMark() {
        assertEquals("hello", VoiceText.forInsertion(null, "hello"))
        assertEquals("hello", VoiceText.forInsertion("", "hello"))
        for (before in listOf(" ", "\n", "\t", "(", "[", "{", "\"", "'", "„", "“", "«", "‘", "-", "/", " "))
            assertEquals("hello", VoiceText.forInsertion(before, "hello"), "after '$before'")
        assertEquals(" hello", VoiceText.forInsertion("x", " hello")) // text that starts with a space is not given another one
    }

    @Test fun onlyTheLastCharacterBeforeTheCursorCounts() {
        assertEquals(" a", VoiceText.forInsertion("hello (world", "a")) // the last one is a letter
        assertEquals("a", VoiceText.forInsertion("hello world ", "a"))
    }

    @Test fun emptyTextStaysEmpty() = assertEquals("", VoiceText.forInsertion("x", ""))

    // ---- the pinned model list equals the lock file the builder's fetch script reads ------------------------------------

    @Test fun theModelSpecEqualsTheLockFile() {
        val lock = File("../tools/voice-model.lock").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        assertEquals(4, lock.size)
        assertEquals(VoiceModelSpec.entries.map { it.name }, lock.map { it[0] })
        assertEquals(VoiceModelSpec.entries.map { it.size }, lock.map { it[1].toLong() })
        assertEquals(VoiceModelSpec.entries.map { it.sha256 }, lock.map { it[2] })
        assertEquals(VoiceModelSpec.entries.map { it.url }, lock.map { it[3] }) // the browser download opens exactly the pinned URLs
        for (row in lock) {
            assertTrue(Regex("[0-9a-f]{64}").matches(row[2]), row[0])
            assertTrue(row[3].startsWith("https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/ed5d745623472f97cfc2e6c2916ee248798fc5a8/") && row[3].endsWith("/${row[0]}?download=true"), row[0]) // 1.0.2: ?download=true makes Hugging Face send the .onnx files as attachments
        }
        assertEquals(670_576_784L, VoiceModelSpec.totalBytes)
        assertEquals(652_282_294L, VoiceModelSpec.largestBytes)
    }

    // ---- 1.0.2 pass 1: the one archive, pinned like the files ------------------------------------------------------------

    @Test fun theArchiveSpecEqualsItsLockFile() {
        val lock = File("../tools/voice-model-archive.lock").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        assertEquals(1, lock.size)
        val a = VoiceModelSpec.archive
        assertEquals(listOf(a.name, a.size.toString(), a.sha256, a.url, a.folder), lock[0])
    }

    @Test fun everyDownloadAddressForcesADownloadAtThePinnedRevision() {
        // measured with curl -sIL on 2026-11-14: with ?download=true the three .onnx files end in "content-disposition: attachment"; tokens.txt stays inline text/plain
        // (Hugging Face does not force small text files), which is why the one archive is the preferred download
        for (entry in VoiceModelSpec.entries) {
            val url = java.net.URI(entry.url)
            assertEquals("https", url.scheme, entry.name)
            assertEquals("huggingface.co", url.host, entry.name)
            assertEquals("/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/ed5d745623472f97cfc2e6c2916ee248798fc5a8/${entry.name}", url.path, entry.name) // revision, not a branch
            assertEquals("download=true", url.query, entry.name)
        }
        val archive = java.net.URI(VoiceModelSpec.archive.url)
        assertEquals("github.com", archive.host) // a release asset: GitHub answers it with content-disposition: attachment, no parameter needed
        assertEquals("/k2-fsa/sherpa-onnx/releases/download/asr-models/${VoiceModelSpec.archive.name}", archive.path)
        assertEquals(null, archive.query)
    }

    @Test fun theArchivePinIsComplete() {
        val a = VoiceModelSpec.archive
        assertTrue(Regex("[0-9a-f]{64}").matches(a.sha256))
        assertTrue(a.name.endsWith(".tar.bz2") && a.url.endsWith("/${a.name}") && a.folder == a.name.removeSuffix(".tar.bz2"))
        assertEquals(487_211_443L, a.size)
        // an archive is never mistaken for a single file by its size (the installer sorts a picked document by size first)
        assertTrue(VoiceModelSpec.entries.none { it.size == a.size })
    }
}
