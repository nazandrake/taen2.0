// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.latin.voice.VoiceModelSpec
import helium314.keyboard.latin.voice.VoiceModelStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Gated check with the real 487 MB archive (never committed, not part of make check): set TAEN_VOICE_ARCHIVE to the downloaded
 * sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2 and TAEN_VOICE_OUT to an empty scratch directory on a disk (about 670 MB are written there), then
 * `./gradlew --offline :app:testRunTestsUnitTest --tests '*RealArchiveGatedTest' --rerun`. A pass prints "REAL ARCHIVE PASS" lines; without the variables the test does
 * nothing and prints "REAL ARCHIVE SKIPPED", which is not a pass. It runs the production installer with the production pins and the production bzip2 decoder.
 */
class RealArchiveGatedTest {
    @Test fun theRealArchiveInstallsThroughTheProductionPinsAndDecoder() {
        val archivePath = System.getenv("TAEN_VOICE_ARCHIVE")
        val outPath = System.getenv("TAEN_VOICE_OUT")
        if (archivePath.isNullOrEmpty() || outPath.isNullOrEmpty()) { println("REAL ARCHIVE SKIPPED (TAEN_VOICE_ARCHIVE / TAEN_VOICE_OUT not set)"); return }
        val archive = File(archivePath)
        val out = File(outPath, "taen-real-archive-${System.nanoTime()}")
        val store = VoiceModelStore(out)
        val control = InstallControl()
        val start = System.nanoTime()
        val result = VoiceArchiveInstaller(store).install({ archive.inputStream() }, control)
        val seconds = (System.nanoTime() - start) / 1_000_000_000
        assertIs<VoiceArchiveInstaller.Result.Installed>(result)
        assertEquals(VoiceModelSpec.entries.map { it.name }.sorted(), result.names.sorted())
        println("REAL ARCHIVE PASS installed ${result.names.size} files in $seconds s from ${archive.length()} bytes")
        assertTrue(store.isComplete())
        assertTrue(store.verify())
        println("REAL ARCHIVE PASS verify(): all four files match the pinned SHA-256 values")
        assertEquals(VoiceModelSpec.entries.map { it.name }.sorted(), out.list()!!.toList().sorted())
        println("REAL ARCHIVE PASS directory holds exactly the four model files and no temporary file")
        assertEquals(archive.length(), control.progress.done)
        println("REAL ARCHIVE PASS progress reached ${control.progress.done} of ${control.progress.total} bytes")
        store.deleteAll()
        out.delete()
    }
}
