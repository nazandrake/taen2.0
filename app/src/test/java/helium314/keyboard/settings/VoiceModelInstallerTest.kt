// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.latin.voice.VoiceModelSpec
import helium314.keyboard.latin.voice.VoiceModelStore
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Install downloaded model" (P20, 1.0.2): the picked documents are the single archive and/or any subset of the model files, in any round,
 * with any file name (the content decides). Stand-in entries, SHA-256 computed with Python hashlib (not by the code).
 */
class VoiceModelInstallerTest {
    private val encoder = "encoder-bytes-0123456789".toByteArray()
    private val decoder = "decoder-bytes".toByteArray()
    private val tokens = "tokens-A".toByteArray()
    private val entries = listOf(
        VoiceModelSpec.Entry("encoder.int8.onnx", 24, "2ec14182a4ecd456990f2d3a937a7c57a327b43e87c02d4ffa741efcd78e4932"),
        VoiceModelSpec.Entry("decoder.int8.onnx", 13, "82128e098f9c9fb7988bf6b2a73b54732e29dd5c93ffd14b150addc0dbe01f4a"),
        VoiceModelSpec.Entry("tokens.txt", 8, "95dcd6c357b3d2b656b1ade801e28b5da10a255a537245e9c5f6d5ee586669ff"),
    )

    private class Doc(override val name: String, val bytes: ByteArray, override val size: Long? = bytes.size.toLong(), val deletable: Boolean = true, val readable: Boolean = true) : VoiceModelInstaller.Source {
        var deleted = false
        var opened = 0
        override fun open(): java.io.InputStream? { opened++; return if (deleted || !readable) null else ByteArrayInputStream(bytes) }
        override fun delete(): Boolean { if (deletable) deleted = true; return deletable }
    }

    private fun dir(): File = File(System.getProperty("java.io.tmpdir"), "taen-installer-test-${System.nanoTime()}").also { it.deleteOnExit() }
    private fun store(d: File, free: (File) -> Long = { Long.MAX_VALUE }) = VoiceModelStore(d, entries, free)
    private fun installer(s: VoiceModelStore) = VoiceModelInstaller(s, entries, archive = null)

    // ---- the core behaviour -------------------------------------------------------------------------------------

    @Test fun anySubsetOfTheFilesIsInstalledAndTheRestIsReportedMissing() {
        val d = dir()
        val store = store(d)
        val docs = listOf(Doc("decoder.int8.onnx", decoder), Doc("tokens.txt", tokens))
        val report = installer(store).install(docs, deleteDownloaded = true)
        assertEquals(listOf("decoder.int8.onnx", "tokens.txt"), report.installed.sorted())
        assertEquals(listOf("encoder.int8.onnx"), report.missing)
        assertTrue(!report.complete)
        assertEquals(listOf(true, true), docs.map { it.deleted })
        // the second round finishes the model
        val second = installer(store).install(listOf(Doc("encoder.int8.onnx", encoder)), deleteDownloaded = true)
        assertTrue(second.complete)
        assertTrue(store.verify())
    }

    // ---- the whole contract -------------------------------------------------------------------------------------

    private fun sha256(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun leftovers(d: File): List<String> = d.list()?.toList()?.sorted() ?: emptyList()

    private fun archiveTar(): ByteArray = TarBuilder().dir("m").entry("m/encoder.int8.onnx", encoder).entry("m/README.md", "r".toByteArray())
        .entry("m/decoder.int8.onnx", decoder).entry("m/tokens.txt", tokens).build()
    private fun archiveSpec(tar: ByteArray) = VoiceModelSpec.Archive("m.tar", tar.size.toLong(), sha256(tar), "", "m")
    private fun withArchive(s: VoiceModelStore, tar: ByteArray) = VoiceModelInstaller(s, entries, archiveSpec(tar)) { it }

    @Test fun aFileIsFoundByItsContentWhateverTheBrowserCalledIt() {
        val d = dir()
        val docs = listOf(Doc("tokens (1).txt", tokens), Doc("download-3", decoder), Doc("Encoder.int8.onnx", encoder))
        val report = installer(store(d)).install(docs, deleteDownloaded = true)
        assertEquals(entries.map { it.name }.sorted(), report.installed.sorted())
        assertTrue(report.complete)
        assertEquals(emptyList(), report.missing)
        assertEquals(entries.map { it.name }.sorted(), leftovers(d))
    }

    @Test fun aWrongFileDoesNotUndoTheGoodOnesOfTheSameRound() {
        val d = dir()
        val bad = Doc("tokens.txt", "tokens-B".toByteArray()) // right name and size, other content
        val docs = listOf(Doc("encoder.int8.onnx", encoder), bad, Doc("decoder.int8.onnx", decoder))
        val report = installer(store(d)).install(docs, deleteDownloaded = true)
        assertEquals(listOf(VoiceModelInstaller.Kind.FILE_INSTALLED, VoiceModelInstaller.Kind.NOT_A_MODEL_FILE, VoiceModelInstaller.Kind.FILE_INSTALLED), report.outcomes.map { it.kind })
        assertEquals(listOf("tokens.txt"), report.missing)
        assertEquals(listOf("decoder.int8.onnx", "encoder.int8.onnx"), leftovers(d))
        assertEquals(listOf(true, false, true), docs.map { it.deleted }) // only what was installed is deleted
    }

    @Test fun aDocumentWhoseSizeIsNoModelSizeIsNeverRead() {
        val d = dir()
        val photo = Doc("holiday.jpg", "not a model, and of another size".toByteArray())
        val report = installer(store(d)).install(listOf(photo), deleteDownloaded = true)
        assertEquals(listOf(VoiceModelInstaller.Kind.NOT_A_MODEL_FILE), report.outcomes.map { it.kind })
        assertEquals(0, photo.opened)
        assertFalse(photo.deleted)
        assertFalse(d.exists())
        assertEquals(entries.map { it.name }, report.missing)
        // positive control: the same call with a document of a model size does read it
        val sameSizeAsDecoder = Doc("x.bin", "xxxxxxxxxxxxx".toByteArray())
        installer(store(d)).install(listOf(sameSizeAsDecoder), deleteDownloaded = true)
        assertTrue(sameSizeAsDecoder.opened > 0)
    }

    @Test fun anUnknownSizeIsJudgedByTheContent() {
        val d = dir()
        val good = Doc("a", decoder, size = null)
        val junk = Doc("b", "junk".toByteArray(), size = null)
        val report = installer(store(d)).install(listOf(junk, good), deleteDownloaded = false)
        assertEquals(listOf(VoiceModelInstaller.Kind.NOT_A_MODEL_FILE, VoiceModelInstaller.Kind.FILE_INSTALLED), report.outcomes.map { it.kind })
        assertEquals(listOf("decoder.int8.onnx"), leftovers(d))
    }

    @Test fun deletionIsOptionalAndACopyThatCannotBeDeletedIsReported() {
        val keep = listOf(Doc("encoder.int8.onnx", encoder))
        installer(store(dir())).install(keep, deleteDownloaded = false)
        assertEquals(listOf(false), keep.map { it.deleted })
        val docs = listOf(Doc("encoder.int8.onnx", encoder), Doc("decoder.int8.onnx", decoder, deletable = false), Doc("tokens.txt", tokens))
        val store = store(dir())
        val report = installer(store).install(docs, deleteDownloaded = true)
        assertEquals(listOf("decoder.int8.onnx"), report.notDeleted)
        assertTrue(store.isComplete()) // the model stays installed
        assertEquals(listOf(true, false, true), docs.map { it.deleted })
    }

    @Test fun anUnreadableDocumentFailsAloneAndTheRestInstalls() {
        val d = dir()
        val docs = listOf(Doc("encoder.int8.onnx", encoder), Doc("decoder.int8.onnx", decoder, readable = false), Doc("tokens.txt", tokens))
        val report = installer(store(d)).install(docs, deleteDownloaded = true)
        assertEquals(listOf(VoiceModelInstaller.Kind.FILE_INSTALLED, VoiceModelInstaller.Kind.FAILED, VoiceModelInstaller.Kind.FILE_INSTALLED), report.outcomes.map { it.kind })
        assertEquals(listOf("decoder.int8.onnx"), report.missing)
        assertEquals(listOf(true, false, true), docs.map { it.deleted })
    }

    @Test fun missingSpaceIsReportedPerDocument() {
        val d = dir()
        val docs = listOf(Doc("encoder.int8.onnx", encoder))
        val report = installer(store(d) { 0L }).install(docs, deleteDownloaded = true)
        assertEquals(listOf(VoiceModelInstaller.Kind.NO_SPACE), report.outcomes.map { it.kind })
        assertEquals(emptyList(), leftovers(d))
        assertEquals(listOf(false), docs.map { it.deleted })
    }

    @Test fun theModelIsCompletedAcrossSessionsFromWhateverIsInTheDirectory() { // three rounds, a new store and installer each time (the app was closed in between)
        val d = dir()
        val first = installer(store(d)).install(listOf(Doc("tokens.txt", tokens)), deleteDownloaded = true)
        assertEquals(listOf("encoder.int8.onnx", "decoder.int8.onnx"), first.missing) // in the order of the pinned list
        assertEquals(listOf(true, false, false), store(d).status().map { it.installed }.let { listOf(it[2], it[0], it[1]) }) // tokens.txt in place only
        val second = installer(store(d)).install(listOf(Doc("decoder.int8.onnx", decoder)), deleteDownloaded = true)
        assertEquals(listOf("encoder.int8.onnx"), second.missing)
        assertFalse(second.complete)
        val third = installer(store(d)).install(listOf(Doc("encoder.int8.onnx", encoder)), deleteDownloaded = true)
        assertTrue(third.complete)
        assertEquals(listOf(true, true, true), store(d).status().map { it.installed })
        assertTrue(store(d).verify())
        assertEquals(entries.map { it.name }.sorted(), leftovers(d))
    }

    @Test fun theArchiveAloneInstallsEverythingAndIsDeleted() {
        val tar = archiveTar()
        val doc = Doc("model.tar.bz2", tar)
        val d = dir()
        val report = withArchive(store(d), tar).install(listOf(doc), deleteDownloaded = true)
        assertEquals(listOf(VoiceModelInstaller.Kind.ARCHIVE_INSTALLED), report.outcomes.map { it.kind })
        assertTrue(report.complete)
        assertEquals(entries.map { it.name }.sorted(), report.installed.sorted())
        assertTrue(doc.deleted)
        assertEquals(entries.map { it.name }.sorted(), leftovers(d))
    }

    @Test fun anArchiveOfTheRightSizeButOtherContentIsNotTheArchiveAndIsKept() {
        val tar = archiveTar()
        val other = tar.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        val doc = Doc("model.tar.bz2", other)
        val d = dir()
        val report = withArchive(store(d), tar).install(listOf(doc), deleteDownloaded = true)
        assertEquals(listOf(VoiceModelInstaller.Kind.NOT_THE_ARCHIVE), report.outcomes.map { it.kind })
        assertFalse(doc.deleted)
        assertEquals(emptyList(), leftovers(d)) // nothing is installed or staged (the folder may exist: the space check makes it)
    }

    @Test fun aBadArchiveAndTheSingleFilesOfTheSameRoundAreHandledSeparately() {
        val evil = TarBuilder().dir("m").entry("m/../evil", "x".toByteArray()).entry("m/encoder.int8.onnx", encoder)
            .entry("m/decoder.int8.onnx", decoder).entry("m/tokens.txt", tokens).build()
        val d = dir()
        val archiveDoc = Doc("evil.tar", evil)
        val tokensDoc = Doc("tokens.txt", tokens)
        val report = withArchive(store(d), evil).install(listOf(archiveDoc, tokensDoc), deleteDownloaded = true)
        assertEquals(listOf(VoiceModelInstaller.Kind.BAD_ARCHIVE, VoiceModelInstaller.Kind.FILE_INSTALLED), report.outcomes.map { it.kind })
        assertEquals(listOf("tokens.txt"), leftovers(d))
        assertEquals(listOf(false, true), listOf(archiveDoc, tokensDoc).map { it.deleted })
    }

    @Test fun aWrongFileInsideAGoodArchiveIsReportedByName() {
        val tar = TarBuilder().dir("m").entry("m/encoder.int8.onnx", encoder).entry("m/decoder.int8.onnx", "decoder-bytez".toByteArray()).entry("m/tokens.txt", tokens).build()
        val report = withArchive(store(dir()), tar).install(listOf(Doc("m.tar", tar)), deleteDownloaded = true)
        assertEquals(VoiceModelInstaller.Kind.ENTRY_REJECTED, report.outcomes[0].kind)
        assertEquals("decoder.int8.onnx", report.outcomes[0].detail)
    }

    @Test fun anArchiveWithoutAKnownSizeIsRecognisedByItsHash() {
        val tar = archiveTar()
        val d = dir()
        val doc = Doc("model", tar, size = null)
        val report = withArchive(store(d), tar).install(listOf(doc), deleteDownloaded = false)
        assertEquals(listOf(VoiceModelInstaller.Kind.ARCHIVE_INSTALLED), report.outcomes.map { it.kind })
        assertTrue(report.complete)
        // unknown size and neither a file nor the archive: one answer, not two
        val junk = Doc("junk", "junk".toByteArray(), size = null)
        assertEquals(listOf(VoiceModelInstaller.Kind.NOT_A_MODEL_FILE), withArchive(store(dir()), tar).install(listOf(junk), deleteDownloaded = false).outcomes.map { it.kind })
    }

    @Test fun theRealArchiveFixtureInstallsThroughTheRealDecoder() {
        val bytes = File("src/test/resources/bzip2/standin-model.tar.bz2").readBytes()
        val spec = VoiceModelSpec.Archive("standin-model.tar.bz2", bytes.size.toLong(), sha256(bytes), "", "standin-model")
        val d = dir()
        val doc = Doc("standin-model.tar.bz2", bytes)
        val report = VoiceModelInstaller(store(d), entries, spec).install(listOf(doc), deleteDownloaded = true)
        assertTrue(report.complete)
        assertTrue(store(d).verify())
        assertTrue(doc.deleted)
    }

    @Test fun aCancelStopsTheRoundKeepsWhatWasInstalledAndLeavesNoTemporaryFile() {
        val d = dir()
        val control = InstallControl()
        val cancelling = object : VoiceModelInstaller.Source {
            override val name = "decoder.int8.onnx"
            override val size: Long? = decoder.size.toLong()
            override fun open(): java.io.InputStream? = object : java.io.FilterInputStream(ByteArrayInputStream(decoder)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int { control.cancel(); return super.read(b, off, len) }
            }
            override fun delete() = true
        }
        val first = Doc("encoder.int8.onnx", encoder)
        val last = Doc("tokens.txt", tokens)
        val report = installer(store(d)).install(listOf(first, cancelling, last), deleteDownloaded = true, control = control)
        assertEquals(listOf(VoiceModelInstaller.Kind.FILE_INSTALLED, VoiceModelInstaller.Kind.CANCELLED), report.outcomes.map { it.kind })
        assertEquals(0, last.opened) // the rest of the round is not started
        assertEquals(listOf("encoder.int8.onnx"), leftovers(d))
        assertEquals(listOf(true, false), listOf(first, last).map { it.deleted })
    }

    @Test fun theProgressCountsBothPassesOfAFile() {
        val control = InstallControl()
        installer(store(dir())).install(listOf(Doc("encoder.int8.onnx", encoder)), deleteDownloaded = false, control = control)
        assertEquals("encoder.int8.onnx", control.progress.label)
        assertEquals(2L * encoder.size, control.progress.total)
        assertEquals(2L * encoder.size, control.progress.done)
    }
}
