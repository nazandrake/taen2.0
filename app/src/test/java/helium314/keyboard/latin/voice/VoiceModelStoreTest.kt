// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The import and verification of the model files with three small stand-in entries (the SHA-256 values were computed with Python hashlib, not by the code). */
class VoiceModelStoreTest {
    private val encoder = "encoder-bytes-0123456789".toByteArray()
    private val decoder = "decoder-bytes".toByteArray()
    private val tokens = "tokens-A".toByteArray()
    private val entries = listOf(
        VoiceModelSpec.Entry("encoder.int8.onnx", 24, "2ec14182a4ecd456990f2d3a937a7c57a327b43e87c02d4ffa741efcd78e4932"),
        VoiceModelSpec.Entry("decoder.int8.onnx", 13, "82128e098f9c9fb7988bf6b2a73b54732e29dd5c93ffd14b150addc0dbe01f4a"),
        VoiceModelSpec.Entry("tokens.txt", 8, "95dcd6c357b3d2b656b1ade801e28b5da10a255a537245e9c5f6d5ee586669ff"),
    )

    private fun dir(): File = File(System.getProperty("java.io.tmpdir"), "taen-voice-test-${System.nanoTime()}").also { it.deleteOnExit() }
    private fun store(dir: File) = VoiceModelStore(dir, entries)
    private fun importBytes(s: VoiceModelStore, bytes: ByteArray) = s.importFile { ByteArrayInputStream(bytes) }

    @Test fun aKnownFileIsImportedByItsContentWhateverItIsCalled() {
        val d = dir()
        val s = store(d)
        assertEquals(listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"), s.missing().map { it.name })
        val result = importBytes(s, decoder)
        assertEquals("decoder.int8.onnx", assertIs<VoiceModelStore.ImportResult.Imported>(result).name)
        assertEquals(decoder.toList(), File(d, "decoder.int8.onnx").readBytes().toList())
        assertEquals(listOf("encoder.int8.onnx", "tokens.txt"), s.missing().map { it.name }) // exact size counts as present
        assertEquals(listOf("decoder.int8.onnx"), d.list()!!.toList()) // no temporary file is left
    }

    @Test fun anUnknownFileIsRejectedAndNothingIsWritten() {
        val d = dir()
        val s = store(d)
        assertIs<VoiceModelStore.ImportResult.NotAModelFile>(importBytes(s, "not a model file".toByteArray()))
        assertFalse(d.exists(), "a wrong file (for example a private photo) must not be copied anywhere")
        // positive control: the same call with a model file does write
        importBytes(s, tokens)
        assertTrue(d.exists())
    }

    @Test fun aFileLargerThanTheLargestEntryIsRejectedWithoutBeingRead() {
        val d = dir()
        var bytesRead = 0L
        val endless = object : InputStream() {
            override fun read(): Int { bytesRead++; return 7 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { bytesRead += len; b.fill(7, off, off + len); return len }
        }
        assertIs<VoiceModelStore.ImportResult.NotAModelFile>(store(d).importFile { endless })
        assertTrue(bytesRead < 4L * 1024 * 1024 + 24, "read $bytesRead bytes of an endless stream") // one buffer past the limit at most
        assertFalse(d.exists())
    }

    @Test fun aFileThatChangesBetweenTheTwoPassesIsRefusedAndRemoved() {
        val d = dir()
        val s = store(d)
        var opened = 0
        val result = s.importFile { ByteArrayInputStream(if (opened++ == 0) encoder else encoder.copyOf().also { it[3] = 'X'.code.toByte() }) }
        assertIs<VoiceModelStore.ImportResult.Failed>(result)
        assertEquals(emptyList(), d.list()!!.toList()) // neither the part file nor a model file
        assertEquals(3, s.missing().size)
    }

    @Test fun aFileThatGrowsBetweenTheTwoPassesIsRefused() {
        val d = dir()
        var opened = 0
        val result = store(d).importFile { ByteArrayInputStream(if (opened++ == 0) tokens else tokens + "more".toByteArray()) }
        assertIs<VoiceModelStore.ImportResult.Failed>(result)
        assertEquals(emptyList(), d.list()!!.toList())
    }

    @Test fun anInputErrorFailsAndLeavesNoPartFile() {
        val d = dir()
        var opened = 0
        val result = store(d).importFile {
            if (opened++ == 0) ByteArrayInputStream(decoder)
            else object : InputStream() {
                var n = 0
                override fun read(): Int = throw IOException("disk gone")
                override fun read(b: ByteArray, off: Int, len: Int): Int { if (n++ == 0) { b[off] = 1; return 1 } else throw IOException("disk gone") }
            }
        }
        assertIs<VoiceModelStore.ImportResult.Failed>(result)
        assertEquals(emptyList(), d.list()!!.toList())
        assertIs<VoiceModelStore.ImportResult.Failed>(store(dir()).importFile { throw IOException("cannot open") })
        assertIs<VoiceModelStore.ImportResult.Failed>(store(dir()).importFile { null })
    }

    @Test fun verifyChecksEveryFileAgainstItsHash() {
        val d = dir()
        val s = store(d)
        assertFalse(s.verify())
        importBytes(s, encoder); importBytes(s, decoder); importBytes(s, tokens)
        assertTrue(s.isComplete())
        assertTrue(s.verify())
        // same size, other content: the size check passes (cheap check) and the hash check refuses it
        File(d, "tokens.txt").writeBytes("tokens-B".toByteArray())
        assertTrue(s.isComplete())
        assertFalse(s.verify())
        // wrong size: not complete at all
        File(d, "tokens.txt").writeBytes("tokens".toByteArray())
        assertFalse(s.isComplete())
        assertEquals(listOf("tokens.txt"), s.missing().map { it.name })
    }

    @Test fun importingAgainReplacesAChangedFile() {
        val d = dir()
        val s = store(d)
        importBytes(s, tokens)
        File(d, "tokens.txt").writeBytes("tokens-B".toByteArray())
        assertFalse(s.verify())
        importBytes(s, tokens)
        assertEquals(tokens.toList(), File(d, "tokens.txt").readBytes().toList())
    }

    @Test fun deleteAllRemovesTheModelAndAnyPartFile() {
        val d = dir()
        val s = store(d)
        importBytes(s, encoder); importBytes(s, tokens)
        File(d, "import.part").writeBytes(byteArrayOf(1))
        s.deleteAll()
        assertEquals(emptyList(), d.list()!!.toList())
        assertEquals(3, s.missing().size)
    }

    @Test fun tokenLinesAreReadAsUtf8() {
        val d = dir()
        d.mkdirs()
        File(d, "tokens.txt").writeBytes("▁б 3\n".toByteArray())
        assertEquals(listOf("▁б 3"), store(d).tokenLines())
    }

    // ---- 1.0.2 pass 1: status rows and cancel ------------------------------------------------------------------------

    @Test fun theStatusRowsNameEveryFileWithItsSizeAndWhetherItIsInstalled() {
        val d = dir()
        val s = store(d)
        importBytes(s, decoder)
        val rows = s.status()
        assertEquals(listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"), rows.map { it.entry.name })
        assertEquals(listOf(24L, 13L, 8L), rows.map { it.entry.size })
        assertEquals(listOf(false, true, false), rows.map { it.installed })
    }

    @Test fun aCancelledImportInstallsNothingAndLeavesNoFile() {
        val d = dir()
        val s = store(d)
        val result = s.importFile(VoiceModelStore.Hooks(cancelled = { true })) { ByteArrayInputStream(encoder) }
        assertIs<VoiceModelStore.ImportResult.Cancelled>(result)
        assertEquals(emptyList(), d.list()?.toList() ?: emptyList())
        assertEquals(3, s.missing().size)
    }

    // ---- 1.0.2 pass 2: staging, commit, progress, cancel, partial sets ------------------------------------------------

    @Test fun aStagedFileIsVerifiedAndOnlyACommitMakesItInstalled() {
        val d = dir()
        val s = store(d)
        val entry = entries[1]
        assertIs<VoiceModelStore.ImportResult.Staged>(s.stage(entry, ByteArrayInputStream(decoder)))
        assertEquals(listOf("decoder.int8.onnx.part"), d.list()!!.toList()) // waits under a temporary name
        assertEquals(3, s.missing().size)
        assertTrue(s.commitStaged(listOf("decoder.int8.onnx")))
        assertEquals(listOf("decoder.int8.onnx"), d.list()!!.toList())
        assertEquals(listOf("encoder.int8.onnx", "tokens.txt"), s.missing().map { it.name })
    }

    @Test fun aStreamThatIsNotTheEntryIsMismatchAndLeavesNoFile() {
        val d = dir()
        val s = store(d)
        assertIs<VoiceModelStore.ImportResult.Mismatch>(s.stage(entries[1], ByteArrayInputStream("decoder-bytez".toByteArray()))) // same size, other hash
        assertIs<VoiceModelStore.ImportResult.Mismatch>(s.stage(entries[1], ByteArrayInputStream("decoder-byte".toByteArray()))) // shorter
        assertIs<VoiceModelStore.ImportResult.Mismatch>(s.stage(entries[1], ByteArrayInputStream(decoder + 'x'.code.toByte()))) // longer
        assertEquals(emptyList(), d.list()!!.toList())
        assertIs<VoiceModelStore.ImportResult.Staged>(s.stage(entries[1], ByteArrayInputStream(decoder))) // positive control
    }

    @Test fun aStreamThatIsClosedByStageEvenWhenItIsRefused() {
        var closed = 0
        fun stream(bytes: ByteArray) = object : java.io.FilterInputStream(ByteArrayInputStream(bytes)) { override fun close() { closed++; super.close() } }
        store(dir()).stage(entries[1], stream("nope".toByteArray()))
        VoiceModelStore(dir(), entries) { 0L }.stage(entries[1], stream(decoder)) // no space: refused before reading
        assertEquals(2, closed)
    }

    @Test fun commitOfANameThatWasNotStagedIsRefusedAndMovesNothing() {
        val d = dir()
        val s = store(d)
        s.stage(entries[1], ByteArrayInputStream(decoder))
        assertFalse(s.commitStaged(listOf("decoder.int8.onnx", "tokens.txt"))) // the second one was never staged
        assertEquals(listOf("decoder.int8.onnx.part"), d.list()!!.toList()) // nothing was moved
        assertFalse(s.commitStaged(listOf("not-a-model.bin")))
    }

    @Test fun aCommitWhoseLaterRenameFailsRemovesTheFilesItCreatedAndKeepsEarlierOnes() {
        val d = dir()
        val s = store(d)
        importBytes(s, tokens) // installed in an earlier round
        assertIs<VoiceModelStore.ImportResult.Staged>(s.stage(entries[0], ByteArrayInputStream(encoder)))
        assertIs<VoiceModelStore.ImportResult.Staged>(s.stage(entries[1], ByteArrayInputStream(decoder)))
        assertIs<VoiceModelStore.ImportResult.Staged>(s.stage(entries[2], ByteArrayInputStream(tokens)))
        // the third rename cannot replace tokens.txt: a rename that fails after two files were moved (a non-empty folder stands where the file must go)
        File(d, "tokens.txt").delete()
        File(d, "tokens.txt").mkdirs()
        File(d, "tokens.txt/x").writeBytes(byteArrayOf(1))
        assertFalse(s.commitStaged(listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt")))
        assertFalse(File(d, "encoder.int8.onnx").exists())
        assertFalse(File(d, "decoder.int8.onnx").exists())
        s.discardStaged()
        assertEquals(listOf("tokens.txt"), d.list()!!.toList())
        // positive control: without the obstacle all three are installed
        File(d, "tokens.txt/x").delete(); File(d, "tokens.txt").delete()
        listOf(entries[0] to encoder, entries[1] to decoder, entries[2] to tokens).forEach { (e, b) -> assertIs<VoiceModelStore.ImportResult.Staged>(s.stage(e, ByteArrayInputStream(b))) }
        assertTrue(s.commitStaged(listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt")))
        assertTrue(s.isComplete() && s.verify())
    }

    @Test fun aFailedCommitKeepsAnInstalledFileThatWasReplacedBeforeTheFailure() {
        val d = dir()
        val s = store(d)
        importBytes(s, encoder)
        assertIs<VoiceModelStore.ImportResult.Staged>(s.stage(entries[0], ByteArrayInputStream(encoder)))
        assertIs<VoiceModelStore.ImportResult.Staged>(s.stage(entries[2], ByteArrayInputStream(tokens)))
        File(d, "tokens.txt").mkdirs()
        File(d, "tokens.txt/x").writeBytes(byteArrayOf(1))
        assertFalse(s.commitStaged(listOf("encoder.int8.onnx", "tokens.txt")))
        assertTrue(File(d, "encoder.int8.onnx").isFile && s.verify().not()) // the earlier encoder is still installed (verify is false: the others are absent)
        assertEquals(encoder.toList(), File(d, "encoder.int8.onnx").readBytes().toList())
    }

    @Test fun onlyOneInstallRunsPerDirectoryAndTheOtherLeavesItsTemporaryFilesAlone() {
        val d = dir()
        val first = store(d)
        val second = store(d) // another object of the same directory (the screen and the install thread)
        var opened = 0
        val inner = first.exclusive<VoiceModelStore.ImportResult>(VoiceModelStore.ImportResult.Failed) {
            assertIs<VoiceModelStore.ImportResult.Staged>(first.stage(entries[1], ByteArrayInputStream(decoder)))
            val refused = second.importFile { opened++; ByteArrayInputStream(decoder) }
            assertIs<VoiceModelStore.ImportResult.Failed>(refused)
            assertFalse(second.deleteAll())
            assertEquals(listOf("decoder.int8.onnx.part"), d.list()!!.toList()) // the running install's temporary file was not touched
            VoiceModelStore.ImportResult.NotAModelFile
        }
        assertIs<VoiceModelStore.ImportResult.NotAModelFile>(inner)
        assertEquals(0, opened)
        // positive control: after it ended the directory is free again
        assertIs<VoiceModelStore.ImportResult.Imported>(importBytes(second, tokens))
        assertTrue(second.deleteAll())
        assertEquals(emptyList(), d.list()!!.toList())
    }

    @Test fun discardRemovesTheTemporaryFilesAndKeepsTheInstalledOnes() {
        val d = dir()
        val s = store(d)
        importBytes(s, tokens)
        s.stage(entries[0], ByteArrayInputStream(encoder))
        File(d, "import.part").writeBytes(byteArrayOf(1))
        s.discardStaged()
        assertEquals(listOf("tokens.txt"), d.list()!!.toList())
    }

    @Test fun cancelInTheMiddleOfACopyLeavesNoTemporaryFile() {
        val d = dir()
        val s = store(d)
        var reads = 0
        val result = s.stage(entries[0], ByteArrayInputStream(encoder), VoiceModelStore.Hooks(cancelled = { reads++ >= 1 })) // the first read is allowed, the second asks to stop
        assertIs<VoiceModelStore.ImportResult.Cancelled>(result)
        assertEquals(emptyList(), d.list()!!.toList())
        // and a cancel in the hash pass of an import writes nothing at all
        val d2 = dir()
        assertIs<VoiceModelStore.ImportResult.Cancelled>(store(d2).importFile(VoiceModelStore.Hooks(cancelled = { reads++ >= 3 })) { ByteArrayInputStream(encoder) })
        assertFalse(d2.exists())
    }

    @Test fun theByteCountsOfBothPassesAreReportedForProgress() {
        var bytes = 0L
        val result = store(dir()).importFile(VoiceModelStore.Hooks(onBytes = { bytes += it })) { ByteArrayInputStream(encoder) }
        assertIs<VoiceModelStore.ImportResult.Imported>(result)
        assertEquals(2L * encoder.size, bytes) // hash pass and copy pass
    }

    @Test fun partialSetsSurviveANewStoreObject() { // the app was closed between two rounds: the directory is the state
        val d = dir()
        importBytes(store(d), tokens)
        val again = store(d)
        assertEquals(listOf(false, false, true), again.status().map { it.installed })
        assertFalse(again.isComplete())
        importBytes(again, encoder); importBytes(again, decoder)
        assertEquals(listOf(true, true, true), store(d).status().map { it.installed })
        assertTrue(store(d).isComplete() && store(d).verify())
    }

    @Test fun aFileOfTheWrongSizeIsNotInstalledInTheStatusRows() {
        val d = dir()
        d.mkdirs()
        File(d, "tokens.txt").writeBytes("tok".toByteArray())
        File(d, "decoder.int8.onnx").mkdirs() // a directory with the name of a file
        assertEquals(listOf(false, false, false), store(d).status().map { it.installed })
    }

    @Test fun hasSpaceForIncludesTheMarginAtTheBoundary() {
        val d = dir()
        assertTrue(VoiceModelStore(d, entries) { 100L + VoiceModelStore.SPACE_MARGIN }.hasSpaceFor(100))
        assertFalse(VoiceModelStore(d, entries) { 99L + VoiceModelStore.SPACE_MARGIN }.hasSpaceFor(100))
    }

    @Test fun lessFreeSpaceThanTheFilePlusTheMarginIsReportedBeforeAnythingIsWritten() {
        val d = dir()
        val tooLittle = VoiceModelStore.SPACE_MARGIN + 8 - 1
        assertIs<VoiceModelStore.ImportResult.NoSpace>(VoiceModelStore(d, entries) { tooLittle }.importFile { ByteArrayInputStream(tokens) })
        assertEquals(emptyList(), d.list()!!.toList()) // the directory was made, no file
        // boundary: exactly the size plus the margin is enough
        assertIs<VoiceModelStore.ImportResult.Imported>(VoiceModelStore(d, entries) { tooLittle + 1 }.importFile { ByteArrayInputStream(tokens) })
        assertEquals(listOf("tokens.txt"), d.list()!!.toList())
    }
}
