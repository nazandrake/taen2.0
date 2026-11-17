// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bench

import helium314.keyboard.latin.voice.engine.OrtParakeet
import helium314.keyboard.latin.voice.engine.TransducerSpeechEngine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Device benchmark of the voice engine, compiled only into the debugNoMinify variant, never into a shipped build. Run from a shell without the keyboard:
 *   CLASSPATH=<base.apk> LD_LIBRARY_PATH=<nativeLibraryDir> app_process / helium314.keyboard.bench.BenchMain <model dir> <samples dir> <threads>
 * Prints one BENCH line per utterance (transcript, decode time, audio length) plus load time and the process's peak resident memory. The audio is the public FLEURS set.
 */
object BenchMain {
    private fun readWav(file: File): FloatArray {
        val bytes = file.readBytes()
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val size = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (String(bytes, pos, 4, Charsets.US_ASCII) == "data") {
                val shorts = ByteBuffer.wrap(bytes, pos + 8, size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                return FloatArray(size / 2) { shorts.get(it) / 32768f }
            }
            pos += 8 + size
        }
        error("no data chunk")
    }

    private fun peakKb(): Long = File("/proc/self/status").readLines().first { it.startsWith("VmHWM:") }.filter { it.isDigit() }.toLong()

    @JvmStatic
    fun main(args: Array<String>) {
        val model = args[0]
        val samples = args[1]
        val threads = args.getOrNull(2)?.toInt() ?: 4
        val t0 = System.nanoTime()
        val engine = TransducerSpeechEngine.parakeetV3(
            OrtParakeet("$model/encoder.int8.onnx", "$model/decoder.int8.onnx", "$model/joiner.int8.onnx", threads),
            File("$model/tokens.txt").readLines(),
        )
        println("BENCH LOAD ms=${(System.nanoTime() - t0) / 1_000_000} threads=$threads peak_rss_kb=${peakKb()}")
        for (lang in listOf("en", "bg")) {
            val dir = File("$samples/$lang")
            for (wav in dir.listFiles { f -> f.name.endsWith(".wav") }!!.sortedBy { it.name }) {
                val pcm = readWav(wav)
                val start = System.nanoTime()
                val text = engine.transcribe(pcm, pcm.size)
                val ms = (System.nanoTime() - start) / 1_000_000
                println("BENCH $lang ${wav.name.removeSuffix(".wav")} ms=$ms audio_ms=${pcm.size / 16} | $text")
            }
        }
        println("BENCH DONE peak_rss_kb=${peakKb()}")
        engine.close()
    }
}
