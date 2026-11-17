// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.latin.voice.VoiceModelSpec
import helium314.keyboard.latin.voice.VoiceModelStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * "Install from the one archive" (P20): the archive hash is checked, then only the model files are read out of the tar and installed.
 * Stand-in entries (SHA-256 computed with Python hashlib); the archive is a tar made by TarBuilder, with identity decompression here, and one
 * real tar.bz2 fixture (made by tools/voice/make_bzip2_fixtures.py) goes through the real decoder.
 */
class VoiceArchiveInstallerTest {
    private val encoder = "encoder-bytes-0123456789".toByteArray()
    private val decoder = "decoder-bytes".toByteArray()
    private val tokens = "tokens-A".toByteArray()
    private val entries = listOf(
        VoiceModelSpec.Entry("encoder.int8.onnx", 24, "2ec14182a4ecd456990f2d3a937a7c57a327b43e87c02d4ffa741efcd78e4932"),
        VoiceModelSpec.Entry("decoder.int8.onnx", 13, "82128e098f9c9fb7988bf6b2a73b54732e29dd5c93ffd14b150addc0dbe01f4a"),
        VoiceModelSpec.Entry("tokens.txt", 8, "95dcd6c357b3d2b656b1ade801e28b5da10a255a537245e9c5f6d5ee586669ff"),
    )
    private val folder = "standin-model"

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun archiveSpec(bytes: ByteArray) = VoiceModelSpec.Archive("standin.tar", bytes.size.toLong(), sha256(bytes), "", folder)
    private fun dir(): File = File(System.getProperty("java.io.tmpdir"), "taen-archive-test-${System.nanoTime()}").also { it.deleteOnExit() }
    private fun store(d: File, free: (File) -> Long = { Long.MAX_VALUE }) = VoiceModelStore(d, entries, free)
    private fun installer(d: File, archive: ByteArray, decompress: (InputStream) -> InputStream = { it }, free: (File) -> Long = { Long.MAX_VALUE }) =
        VoiceArchiveInstaller(store(d, free), archiveSpec(archive), entries, decompress)

    private fun goodBuilder(): TarBuilder = TarBuilder()
        .dir(folder)
        .entry("$folder/tokens.txt", tokens)
        .entry("$folder/README.md", "readme".toByteArray())
        .entry("$folder/decoder.int8.onnx", decoder)
        .entry("$folder/encoder.int8.onnx", encoder)
        .dir("$folder/test_wavs")
        .entry("$folder/test_wavs/en.wav", "RIFF".toByteArray())

    private fun goodTar(): ByteArray = goodBuilder().build()

    private fun run(tar: ByteArray, d: File = dir()): Pair<VoiceArchiveInstaller.Result, File> =
        installer(d, tar).install({ ByteArrayInputStream(tar) }) to d

    private fun ByteArray.indexOfSlice(part: ByteArray): Int = (0..size - part.size).first { i -> part.indices.all { this[i + it] == part[it] } }

    private fun leftovers(d: File): List<String> = d.list()?.toList()?.sorted() ?: emptyList()

    // ---- the core behaviour -------------------------------------------------------------------------------------

    @Test fun theModelFilesAreExtractedFromTheArchiveAndInstalled() {
        val d = dir()
        val tar = goodTar()
        val result = installer(d, tar).install({ ByteArrayInputStream(tar) })
        assertEquals(entries.map { it.name }.sorted(), assertIs<VoiceArchiveInstaller.Result.Installed>(result).names.sorted())
        assertTrue(store(d).isComplete() && store(d).verify())
        assertEquals(entries.map { it.name }.sorted(), d.list()!!.toList().sorted()) // README.md and the wav were not extracted, no temporary file is left
        assertEquals(encoder.toList(), File(d, "encoder.int8.onnx").readBytes().toList())
    }

    // ---- the whole contract -------------------------------------------------------------------------------------

    @Test fun aWrongArchiveHashOrSizeInstallsNothingEvenWhenEveryFileInsideMatches() {
        val tar = goodTar()
        val tampered = tar.copyOf().also { it[tar.indexOfSlice("readme".toByteArray())] = 'R'.code.toByte() } // an extra file's byte: every model file still matches
        for ((name, spec) in listOf(
            "right size, other hash" to VoiceModelSpec.Archive("a", tar.size.toLong(), sha256(tar + byteArrayOf(1)), "", folder),
            "right hash, size one more" to VoiceModelSpec.Archive("a", tar.size + 1L, sha256(tar), "", folder),
            "longer than the pinned size" to VoiceModelSpec.Archive("a", tar.size - 1L, sha256(tar), "", folder),
        )) {
            val d = dir()
            val result = VoiceArchiveInstaller(store(d), spec, entries, { it }).install({ ByteArrayInputStream(tar) })
            assertIs<VoiceArchiveInstaller.Result.NotThisArchive>(result, name)
            assertEquals(emptyList(), leftovers(d), name)
        }
        val d = dir()
        assertIs<VoiceArchiveInstaller.Result.NotThisArchive>(installer(d, tar).install({ ByteArrayInputStream(tampered) }))
        assertEquals(emptyList(), leftovers(d), "the archive hash decides, not only the hashes of the files")
        // positive control: the pinned bytes install
        assertIs<VoiceArchiveInstaller.Result.Installed>(installer(dir(), tar).install({ ByteArrayInputStream(tar) }))
    }

    @Test fun theDocumentIsOpenedOnceAndTheDecoderOnlySeesTheBytesThatWereHashed() {
        val tar = goodTar()
        val other = goodBuilder().entry("$folder/test_wavs/x.wav", "other".toByteArray()).build().copyOf(tar.size) // a provider that serves other bytes on the next open
        var opens = 0
        val seen = java.io.ByteArrayOutputStream()
        val recording: (InputStream) -> InputStream = { source ->
            object : FilterInputStream(source) {
                override fun read(b: ByteArray, off: Int, len: Int): Int { val n = super.read(b, off, len); if (n > 0) seen.write(b, off, n); return n }
            }
        }
        val d = dir()
        val result = installer(d, tar, recording).install({ if (opens++ == 0) ByteArrayInputStream(tar) else ByteArrayInputStream(other) })
        assertEquals(1, opens)
        assertEquals(tar.toList(), seen.toByteArray().toList())
        assertIs<VoiceArchiveInstaller.Result.Installed>(result)
        assertTrue(store(d).verify())
    }

    @Test fun anArchiveThatTheDecoderRefusesIsNotThisArchiveWhenItsHashIsNotPinned() {
        val garbage = ByteArray(3000) { 7 }
        val d = dir()
        val refusing: (InputStream) -> InputStream = { object : InputStream() { override fun read(): Int = throw java.io.IOException("not bzip2") } }
        assertIs<VoiceArchiveInstaller.Result.NotThisArchive>(installer(d, goodTar(), refusing).install({ ByteArrayInputStream(garbage) }))
        assertEquals(emptyList(), leftovers(d))
        // positive control: a pinned archive that the decoder refuses is a failure of the archive, not "another file"
        assertIs<VoiceArchiveInstaller.Result.Failed>(installer(dir(), garbage, refusing).install({ ByteArrayInputStream(garbage) }))
    }

    @Test fun anEndlessStreamIsStoppedAtTheArchiveSize() {
        var read = 0L
        val endless = object : InputStream() {
            override fun read(): Int { read++; return 1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { read += len; return len }
        }
        val spec = VoiceModelSpec.Archive("a", 5_000, "00".repeat(32), "", folder)
        assertIs<VoiceArchiveInstaller.Result.NotThisArchive>(VoiceArchiveInstaller(store(dir()), spec, entries) { it }.install({ endless }))
        assertTrue(read <= 5_000 + VoiceArchiveInstaller.BUFFER, "read $read bytes")
    }

    @Test fun oneWrongInnerHashInstallsNothingFromTheArchiveAndKeepsEarlierFiles() {
        val d = dir()
        assertIs<VoiceModelStore.ImportResult.Imported>(store(d).importFile { ByteArrayInputStream(tokens) }) // installed in an earlier round
        val tar = TarBuilder().dir(folder)
            .entry("$folder/encoder.int8.onnx", encoder)
            .entry("$folder/decoder.int8.onnx", "decoder-bytez".toByteArray()) // same size, other content
            .entry("$folder/tokens.txt", tokens).build()
        val result = installer(d, tar).install({ ByteArrayInputStream(tar) })
        assertEquals("decoder.int8.onnx", assertIs<VoiceArchiveInstaller.Result.EntryRejected>(result).name)
        assertEquals(listOf("tokens.txt"), leftovers(d)) // the encoder that was read before it is gone too, the earlier tokens.txt stays
    }

    @Test fun aModelEntryOfAnotherSizeIsRejectedWithoutReadingItAll() {
        val tar = TarBuilder().dir(folder).entry("$folder/tokens.txt", "tokens-AB".toByteArray())
            .entry("$folder/decoder.int8.onnx", decoder).entry("$folder/encoder.int8.onnx", encoder).build()
        val (result, d) = run(tar)
        assertEquals("tokens.txt", assertIs<VoiceArchiveInstaller.Result.EntryRejected>(result).name)
        assertEquals(emptyList(), leftovers(d))
    }

    private fun assertBad(name: String, tar: ByteArray, reason: String? = null) {
        val (result, d) = run(tar)
        val bad = assertIs<VoiceArchiveInstaller.Result.BadArchive>(result, name)
        if (reason != null) assertEquals(reason, bad.reason, name)
        assertEquals(emptyList(), leftovers(d), "$name: nothing may stay installed or staged")
    }

    @Test fun anEntryThatIsNotOneOfTheExpectedNamesIsRefusedAndNothingIsInstalled() {
        assertIs<VoiceArchiveInstaller.Result.Installed>(run(goodTar()).first) // positive control: README.md, test_wavs/ and a wav are the expected extras
        fun with(vararg more: TarBuilder.() -> TarBuilder): ByteArray = more.fold(goodBuilder()) { b, f -> b.f() }.build()
        assertBad("extra file", with({ entry("$folder/notes.txt", "x".toByteArray()) }))
        assertBad("extra file in the folder of wavs", with({ entry("$folder/test_wavs/run.sh", "x".toByteArray()) }))
        assertBad("model name in another case", with({ entry("$folder/Tokens.txt", "x".toByteArray()) }))
        assertBad("model file in a sub folder", with({ entry("$folder/test_wavs/tokens.txt", "x".toByteArray()) }))
        assertBad("outside the folder", with({ entry("other/encoder.int8.onnx", encoder) }))
        assertBad("sibling folder with the same prefix", with({ entry("$folder-2/x", "x".toByteArray()) }))
        assertBad("parent directory", with({ entry("$folder/../evil", "x".toByteArray()) }), "unsafe name")
        assertBad("parent directory first", with({ entry("../evil", "x".toByteArray()) }), "unsafe name")
        assertBad("absolute path", with({ entry("/data/evil", "x".toByteArray()) }))
        assertBad("absolute path in the folder name", with({ entry("/$folder/evil", "x".toByteArray()) }))
        assertBad("backslash", with({ entry("$folder\\evil", "x".toByteArray()) }))
        assertBad("dot segment", with({ entry("$folder/./evil", "x".toByteArray()) }))
        assertBad("empty segment", with({ entry("$folder//evil", "x".toByteArray()) }))
        assertBad("long name by prefix field", with({ entry("evil", "x".toByteArray(), prefix = "../..") }))
    }

    @Test fun aGnuHeaderIsReadByItsNameAndItsTimeFieldsAreNoPathPrefix() {
        // GNU tar (old GNU format, magic "ustar  "): the bytes where POSIX ustar has the path prefix hold access and change times
        fun gnuTar(extra: TarBuilder.() -> TarBuilder = { this }) = TarBuilder().dir(folder)
            .entry("$folder/tokens.txt", tokens, prefix = "14447136277 14447136277", gnu = true)
            .entry("$folder/decoder.int8.onnx", decoder, prefix = "14447136277 14447136277", gnu = true)
            .entry("$folder/encoder.int8.onnx", encoder, prefix = "14447136277 14447136277", gnu = true).extra().build()
        assertIs<VoiceArchiveInstaller.Result.Installed>(run(gnuTar()).first)
        // positive control: the same bytes in a POSIX header are a prefix, and that path is not expected
        val posix = TarBuilder().dir(folder).entry("$folder/tokens.txt", tokens, prefix = "14447136277 14447136277")
            .entry("$folder/decoder.int8.onnx", decoder).entry("$folder/encoder.int8.onnx", encoder).build()
        assertBad("posix prefix", posix, "an entry that is not expected")
        // a name that is unsafe stays refused in a GNU header
        assertBad("gnu header, parent directory", gnuTar { entry("$folder/../evil", "x".toByteArray(), gnu = true) }, "unsafe name")
    }

    @Test fun linksDevicesAndExtendedHeadersAreRefused() {
        fun with(vararg more: TarBuilder.() -> TarBuilder): ByteArray = more.fold(goodBuilder()) { b, f -> b.f() }.build()
        assertBad("symbolic link", with({ entry("$folder/link", type = '2', linkName = "/data/data/x") }))
        assertBad("symbolic link with a model name", with({ entry("$folder/extra.int8.onnx", type = '2', linkName = "encoder.int8.onnx") }))
        assertBad("hard link", with({ entry("$folder/link", type = '1', linkName = "$folder/tokens.txt") }))
        assertBad("character device", with({ entry("$folder/dev", type = '3') }))
        assertBad("block device", with({ entry("$folder/dev", type = '4') }))
        assertBad("fifo", with({ entry("$folder/fifo", type = '6') }))
        assertBad("pax extended header", with({ entry("$folder/PaxHeader", "path=../../evil\n".toByteArray(), type = 'x') }))
        assertBad("pax global header", with({ entry("pax_global_header", "comment=x\n".toByteArray(), type = 'g') }))
        assertBad("GNU long name", with({ entry("././@LongLink", "$folder/../../evil\u0000".toByteArray(), type = 'L') }))
        // a model name that is a link or a directory is no model file
        val linkTokens = TarBuilder().dir(folder).entry("$folder/tokens.txt", type = '2', linkName = "x")
            .entry("$folder/decoder.int8.onnx", decoder).entry("$folder/encoder.int8.onnx", encoder).build()
        assertBad("model name as a link", linkTokens)
        val dirTokens = TarBuilder().dir(folder).dir("$folder/tokens.txt")
            .entry("$folder/decoder.int8.onnx", decoder).entry("$folder/encoder.int8.onnx", encoder).build()
        assertBad("model name as a directory", dirTokens)
    }

    @Test fun missingDuplicateAndTooManyEntriesAreRefused() {
        assertBad("tokens.txt missing", TarBuilder().dir(folder).entry("$folder/decoder.int8.onnx", decoder).entry("$folder/encoder.int8.onnx", encoder).build())
        assertBad("empty archive", TarBuilder().build())
        assertBad("model file twice", goodBuilder().entry("$folder/tokens.txt", tokens).build(), "a model file twice")
        val many = TarBuilder().dir(folder).entry("$folder/tokens.txt", tokens).entry("$folder/decoder.int8.onnx", decoder).entry("$folder/encoder.int8.onnx", encoder)
        for (i in 0 until 40) many.entry("$folder/test_wavs/$i.wav", "RIFF".toByteArray())
        assertBad("40 wavs", many.build())
    }

    @Test fun anOversizeEntryIsRefusedBeforeItIsReadOrSkipped() {
        // the header says 2 GiB; the data is not there. A reader that skipped the size would end in "the archive ends early" (Failed), not BadArchive
        assertBad("extra file of 2 GiB", goodBuilder().entry("$folder/test_wavs/big.wav", "RIFF".toByteArray(), declaredSize = 2L * 1024 * 1024 * 1024).build())
        assertBad("extra file over the cap", goodBuilder().entry("$folder/test_wavs/big.wav", ByteArray(1024 * 1024 + 1)).build())
        // positive control: the cap itself is fine
        assertIs<VoiceArchiveInstaller.Result.Installed>(run(goodBuilder().entry("$folder/test_wavs/ok.wav", ByteArray(1024 * 1024)).build()).first)
    }

    @Test fun aDamagedHeaderOrATruncatedArchiveFailsAndLeavesNothing() {
        val tar = goodTar()
        val badChecksum = tar.copyOf().also { it[512 + 5] = (it[512 + 5].toInt() xor 1).toByte() } // a byte of the name in the header of the second entry
        assertBad("header checksum", badChecksum)
        // damage that no name or size check sees (the modification time of the second entry): only the header checksum notices it
        val badMtime = tar.copyOf().also { it[512 + 140] = (it[512 + 140].toInt() xor 1).toByte() }
        assertBad("header checksum, time field", badMtime, "damaged header")
        for (cut in listOf(100, 512, 512 + 20, 512 * 3 + 7, tar.size - 1024 - 513)) {
            val cutTar = tar.copyOf(cut)
            val d = dir()
            val result = installer(d, cutTar).install({ ByteArrayInputStream(cutTar) })
            assertTrue(result is VoiceArchiveInstaller.Result.Failed || result is VoiceArchiveInstaller.Result.BadArchive, "cut at $cut: $result")
            assertEquals(emptyList(), leftovers(d))
        }
    }

    @Test fun dataAfterTheEndOfTheArchiveIsBoundedAndReadSoThatTheCompressionTrailerIsChecked() {
        val small = goodBuilder().build() + ByteArray(4096) // record padding as GNU tar writes it
        assertIs<VoiceArchiveInstaller.Result.Installed>(run(small).first)
        assertBad("junk after the end", goodBuilder().build() + ByteArray(5 * 1024 * 1024))
        // the end of the stream is read to the end: its last bytes count as progress
        val tar = goodTar()
        val control = InstallControl()
        assertIs<VoiceArchiveInstaller.Result.Installed>(installer(dir(), tar).install({ ByteArrayInputStream(tar) }, control))
        assertEquals(tar.size.toLong(), control.progress.done)
    }

    @Test fun aCancelStopsTheInstallAndLeavesNothingStaged() {
        val tar = goodTar()
        // while the document is being opened
        val early = InstallControl()
        val d1 = dir()
        assertIs<VoiceArchiveInstaller.Result.Cancelled>(installer(d1, tar).install({ early.cancel(); ByteArrayInputStream(tar) }, early))
        assertEquals(emptyList(), leftovers(d1))
        // in the middle of the tar: after the first model file was staged
        val mid = InstallControl()
        val d2 = dir()
        val cancelling: (InputStream) -> InputStream = { source ->
            object : FilterInputStream(source) {
                var total = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, minOf(len, 700))
                    total += maxOf(n, 0)
                    if (total > 1500) mid.cancel() // inside the first model entry or after it
                    return n
                }
            }
        }
        assertIs<VoiceArchiveInstaller.Result.Cancelled>(installer(d2, tar, cancelling).install({ ByteArrayInputStream(tar) }, mid))
        assertEquals(emptyList(), leftovers(d2))
        // a cancel before the call: nothing is read
        val before = InstallControl().also { it.cancel() }
        val d3 = dir()
        assertIs<VoiceArchiveInstaller.Result.Cancelled>(installer(d3, tar).install({ ByteArrayInputStream(tar) }, before))
        assertFalse(d3.exists())
    }

    @Test fun aStreamThatBlocksIsUnblockedByTheCancelThatClosesIt() {
        val tar = goodTar()
        val control = InstallControl()
        val latch = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val blocking = object : InputStream() {
            override fun read(): Int = read(ByteArray(1), 0, 1)
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (closed.get()) throw java.io.IOException("closed")
                latch.countDown() // the reader is in the read call
                while (!closed.get()) Thread.sleep(5)
                throw java.io.IOException("closed")
            }
            override fun close() { closed.set(true) }
        }
        var result: VoiceArchiveInstaller.Result? = null
        val t = Thread { result = installer(dir(), tar).install({ blocking }, control) }
        t.start()
        assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS))
        control.cancel()
        t.join(10_000)
        assertFalse(t.isAlive, "the blocked read was not ended by the cancel")
        assertIs<VoiceArchiveInstaller.Result.Cancelled>(result)
    }

    @Test fun missingSpaceIsReportedBeforeTheDocumentIsReadAndNothingIsWritten() {
        val tar = goodTar()
        val d = dir()
        var opens = 0
        val result = installer(d, tar, free = { 0L }).install({ opens++; ByteArrayInputStream(tar) })
        assertIs<VoiceArchiveInstaller.Result.NoSpace>(result)
        assertEquals(0, opens)
        assertEquals(emptyList(), leftovers(d))
        // boundary: the sum of the three files plus the margin is enough
        val enough = entries.sumOf { it.size } + VoiceModelStore.SPACE_MARGIN
        assertIs<VoiceArchiveInstaller.Result.NoSpace>(installer(dir(), tar, free = { enough - 1 }).install({ ByteArrayInputStream(tar) }))
        assertIs<VoiceArchiveInstaller.Result.Installed>(installer(dir(), tar, free = { enough }).install({ ByteArrayInputStream(tar) }))
    }

    @Test fun aLeftoverTemporaryFileOfAnEarlierRunIsRemovedAndAnOldFileIsReplaced() {
        val d = dir()
        d.mkdirs()
        File(d, "encoder.int8.onnx.part").writeBytes(byteArrayOf(1, 2, 3))
        File(d, "import.part").writeBytes(byteArrayOf(1))
        File(d, "tokens.txt").writeBytes("tokens-B".toByteArray()) // same size, other content
        assertFalse(store(d).verify())
        val tar = goodTar()
        assertIs<VoiceArchiveInstaller.Result.Installed>(installer(d, tar).install({ ByteArrayInputStream(tar) }))
        assertEquals(entries.map { it.name }.sorted(), leftovers(d))
        assertTrue(store(d).verify())
    }

    @Test fun anOpenFailureOrAnUnreadableSourceFails() {
        val tar = goodTar()
        val d = dir()
        assertIs<VoiceArchiveInstaller.Result.Failed>(installer(d, tar).install({ null }))
        val broken = object : InputStream() { override fun read(): Int = throw java.io.IOException("provider gone") }
        assertIs<VoiceArchiveInstaller.Result.Failed>(installer(d, tar).install({ broken }))
        assertEquals(emptyList(), leftovers(d))
    }

    // ---- the real decoder, end to end -------------------------------------------------------------------------------------

    private fun standin(): ByteArray = File("src/test/resources/bzip2/standin-model.tar.bz2").readBytes()
    private fun standinSpec(bytes: ByteArray): VoiceModelSpec.Archive {
        val (size, hash) = File("src/test/resources/bzip2/fixtures.txt").readLines().first { it.startsWith("standin-model.tar.bz2\t") }.split('\t').let { it[1].toLong() to it[2] }
        assertEquals(size, bytes.size.toLong()) // the manifest (hashlib) and the file agree
        assertEquals(hash, sha256(bytes))
        return VoiceModelSpec.Archive("standin-model.tar.bz2", size, hash, "", "standin-model")
    }

    @Test fun aRealTarBz2IsInstalledThroughTheRealDecoder() {
        val bytes = standin()
        val d = dir()
        val result = VoiceArchiveInstaller(store(d), standinSpec(bytes), entries).install({ ByteArrayInputStream(bytes) })
        assertEquals(entries.map { it.name }.sorted(), assertIs<VoiceArchiveInstaller.Result.Installed>(result).names.sorted())
        assertTrue(store(d).verify())
        assertEquals(entries.map { it.name }.sorted(), leftovers(d))
    }

    @Test fun aDamagedCompressedStreamFailsEvenWhenItsHashIsPinned() {
        // the damage is in the combined CRC at the end of the stream: only a decoder that reads to the end and checks it notices
        val bytes = standin().also { it[it.size - 2] = (it[it.size - 2].toInt() xor 1).toByte() }
        val d = dir()
        val spec = VoiceModelSpec.Archive("damaged.tar.bz2", bytes.size.toLong(), sha256(bytes), "", "standin-model")
        assertIs<VoiceArchiveInstaller.Result.Failed>(VoiceArchiveInstaller(store(d), spec, entries).install({ ByteArrayInputStream(bytes) }))
        assertEquals(emptyList(), leftovers(d))
    }

    // ---- a RuntimeException of the decoder is a failed install, not a crash ------------------------------------

    /** A decoder stand-in that passes [healthy] bytes through and then throws [error] (what a real decoder does on corrupt data: IllegalStateException, IndexOutOfBounds). */
    private fun throwingAfter(healthy: Int, error: () -> RuntimeException): (InputStream) -> InputStream = { source ->
        object : FilterInputStream(source) {
            var seen = 0
            override fun read(): Int { if (seen >= healthy) throw error(); seen++; return super.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (seen >= healthy) throw error()
                val n = super.read(b, off, minOf(len, healthy - seen))
                if (n > 0) seen += n
                return n
            }
        }
    }

    @Test fun aRuntimeExceptionOfTheDecoderFailsTheInstallAndLeavesNothingBehind() {
        val tar = goodTar()
        for (error in listOf<() -> RuntimeException>({ IllegalStateException("bad block") }, { IndexOutOfBoundsException("bad index") })) {
            val d = dir()
            val result = installer(d, tar, throwingAfter(1600, error)).install({ ByteArrayInputStream(tar) })
            assertIs<VoiceArchiveInstaller.Result.Failed>(result)
            assertEquals(emptyList(), leftovers(d)) // no .part file, nothing renamed into place
            // the exclusive lock was released: the next install of the same directory runs and succeeds (positive control)
            assertIs<VoiceArchiveInstaller.Result.Installed>(installer(d, tar).install({ ByteArrayInputStream(tar) }))
            assertEquals(entries.map { it.name }.sorted(), leftovers(d))
        }
    }

    @Test fun aRuntimeExceptionBeforeAnyByteAlsoFailsCleanly() {
        val tar = goodTar()
        val d = dir()
        assertIs<VoiceArchiveInstaller.Result.Failed>(installer(d, tar, throwingAfter(0) { IllegalStateException("at once") }).install({ ByteArrayInputStream(tar) }))
        assertEquals(emptyList(), leftovers(d))
    }
}
