// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.engine

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real model, real speech, on the host (make voice-smoke): the Kotlin engine on ONNX Runtime 1.27.0 transcribes the fixed FLEURS test
 * set (20 English and 20 Bulgarian utterances, tools/voice/prepare_samples.py; public CC-BY-4.0 audio, never committed) and the corpus
 * WER is checked against the design limits (EN <= 10 %, BG <= 20 %). Skipped (not counted as a pass) without TAEN_VOICE_MODEL_DIR and
 * TAEN_VOICE_SAMPLES_DIR. Prints the "SMOKE" lines that BUILDING.md quotes.
 */
class EngineSmokeTest {
    private fun readWav(file: File): FloatArray {
        val bytes = file.readBytes()
        var pos = 12
        while (pos + 8 <= bytes.size) { // find the "data" chunk of a plain PCM16 file
            val size = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (String(bytes, pos, 4, Charsets.US_ASCII) == "data") {
                val shorts = ByteBuffer.wrap(bytes, pos + 8, size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                return FloatArray(size / 2) { shorts.get(it) / 32768f }
            }
            pos += 8 + size
        }
        error("no data chunk in ${file.name}")
    }

    @Test fun wordErrorRateOnTheFixedSamples() {
        val modelDir = System.getenv("TAEN_VOICE_MODEL_DIR")
        val samplesDir = System.getenv("TAEN_VOICE_SAMPLES_DIR")
        assumeTrue("TAEN_VOICE_MODEL_DIR and TAEN_VOICE_SAMPLES_DIR not set: smoke test skipped", modelDir != null && samplesDir != null)
        val threads = System.getenv("TAEN_VOICE_THREADS")?.toInt() ?: 4
        val model = OrtParakeet("$modelDir/encoder.int8.onnx", "$modelDir/decoder.int8.onnx", "$modelDir/joiner.int8.onnx", threads)
        val engine = TransducerSpeechEngine.parakeetV3(model, File("$modelDir/tokens.txt").readLines())
        engine.use {
            val limits = mapOf("en" to 10.0, "bg" to 20.0)
            for ((lang, limit) in limits) {
                val refs = File("$samplesDir/$lang/refs.tsv").readLines().associate { it.substringBefore('\t') to it.substringAfter('\t') }
                assertEquals(20, refs.size)
                var edits = 0
                var words = 0
                var audioSeconds = 0.0
                var decodeSeconds = 0.0
                for ((id, ref) in refs.toSortedMap()) {
                    val pcm = readWav(File("$samplesDir/$lang/$id.wav"))
                    val start = System.nanoTime()
                    val text = engine.transcribe(pcm, pcm.size)
                    decodeSeconds += (System.nanoTime() - start) / 1e9
                    audioSeconds += pcm.size / 16000.0
                    val refWords = Wer.words(ref)
                    edits += Wer.edits(refWords, Wer.words(text))
                    words += refWords.size
                    println("SMOKE $lang $id | $text")
                }
                val wer = 100.0 * edits / words
                println("SMOKE $lang WER %.2f %% ($edits/$words words), audio %.1f s, decode %.1f s, RTF %.3f".format(wer, audioSeconds, decodeSeconds, decodeSeconds / audioSeconds))
                assertTrue(wer <= limit, "$lang WER $wer % is above the limit of $limit %")
            }
        }
    }
}
