// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bench

import helium314.keyboard.latin.voice.AudioCapture
import helium314.keyboard.latin.voice.PauseDetector
import helium314.keyboard.latin.voice.VoiceEngineHolder
import helium314.keyboard.latin.voice.VoiceSession
import helium314.keyboard.latin.voice.engine.OrtParakeet
import helium314.keyboard.latin.voice.engine.TransducerSpeechEngine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Device benchmark of phrase-by-phrase voice typing, compiled only into the debugNoMinify variant, never into a shipped build. It runs the app's own
 * VoiceSession (pause detector, reader and transcriber threads) and the real engine on public FLEURS utterances that a fake microphone delivers in
 * real time (100 ms blocks), with [gap] ms of silence after each one. Run from a shell without the keyboard:
 *   CLASSPATH=<base.apk> LD_LIBRARY_PATH=<nativeLibraryDir> app_process / helium314.keyboard.bench.LiveBench <model dir> <samples dir> <en|bg> <count> <gap ms> <cold|warm> [pause ms] [min phrase ms] [threads]
 * cold: the model starts loading when the microphone opens (as at a tap); warm: it is loaded first (a second recording within 2 minutes).
 * The session's length limit is raised to the length of the stream. Prints the text of each phrase, for each utterance the time from the end of its speech to the
 * next text (the end of speech: the last block at 4 times the utterance's quietest block), the process's peak resident memory, its CPU time and how late the microphone reads were served.
 */
object LiveBench {
    private fun readWav(file: File): ShortArray {
        val bytes = file.readBytes()
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val size = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (String(bytes, pos, 4, Charsets.US_ASCII) == "data") {
                val shorts = ByteBuffer.wrap(bytes, pos + 8, size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                return ShortArray(size / 2) { shorts.get(it) }
            }
            pos += 8 + size
        }
        error("no data chunk")
    }

    private fun status(key: String): Long = File("/proc/self/status").readLines().first { it.startsWith("$key:") }.filter { it.isDigit() }.toLong()

    private fun cpuMs(): Long { // utime + stime in clock ticks of 10 ms
        val fields = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(' ')
        return (fields[11].toLong() + fields[12].toLong()) * 10
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val model = args[0]
        val lang = args[2]
        val count = args[3].toInt()
        val gap = args[4].toInt() * VoiceSession.RATE / 1000
        val warm = args[5] == "warm"
        val pause = args.getOrNull(6)?.toInt()?.let { it * VoiceSession.RATE / 1000 } ?: PauseDetector.PAUSE_SAMPLES
        val minPhrase = args.getOrNull(7)?.toInt()?.let { it * VoiceSession.RATE / 1000 } ?: PauseDetector.MIN_PHRASE_SAMPLES
        val threads = args.getOrNull(8)?.toInt() ?: 4
        println("LIVE PARAMS pause_ms=${pause * 1000 / VoiceSession.RATE} min_phrase_ms=${minPhrase * 1000 / VoiceSession.RATE} threads=$threads")
        val dir = File(args[1], lang)
        val wavs = dir.listFiles { f -> f.name.endsWith(".wav") }!!.sortedBy { it.name }.take(count)

        // the stream and, per utterance, the block in which its speech ends
        val parts = wavs.map { readWav(it) }
        val stream = ShortArray(parts.sumOf { it.size + gap })
        val speechEndBlock = IntArray(parts.size)
        var at = 0
        parts.forEachIndexed { u, p ->
            p.copyInto(stream, at)
            val levels = DoubleArray(p.size / VoiceSession.CHUNK) { b ->
                var sum = 0.0
                for (i in b * VoiceSession.CHUNK until (b + 1) * VoiceSession.CHUNK) sum += p[i].toDouble() * p[i]
                sqrt(sum / VoiceSession.CHUNK)
            }
            // the detector's speech level for this utterance alone: 4 times its quietest block, at least the absolute minimum
            val floor = levels.filter { it >= PauseDetector.MIN_FLOOR_RMS }.minOrNull() ?: PauseDetector.MIN_FLOOR_RMS
            val speech = maxOf(PauseDetector.MIN_SPEECH_RMS, PauseDetector.SPEECH_FACTOR * floor)
            val last = levels.indexOfLast { it >= speech }.coerceAtLeast(0)
            speechEndBlock[u] = at / VoiceSession.CHUNK + last
            at += p.size + gap
        }
        val blocks = stream.size / VoiceSession.CHUNK
        val delivered = LongArray(blocks) // when each block was handed to the session
        var lateMax = 0L

        val holder = VoiceEngineHolder {
            TransducerSpeechEngine.parakeetV3(OrtParakeet("$model/encoder.int8.onnx", "$model/decoder.int8.onnx", "$model/joiner.int8.onnx", threads),
                File("$model/tokens.txt").readLines())
        }
        val tLoad = System.nanoTime()
        holder.prepare()
        if (warm) { holder.transcribe(FloatArray(VoiceSession.RATE), VoiceSession.RATE) } // waits for the load
        val loadMs = (System.nanoTime() - tLoad) / 1_000_000
        if (warm) println("LIVE LOAD ms=$loadMs (before the microphone)")

        val t0 = System.nanoTime()
        val capture = object : AudioCapture {
            var next = 0
            override fun start() {}
            override fun read(buffer: ShortArray, offset: Int, length: Int): Int {
                if (next >= blocks) return -1
                val due = t0 + next * 100_000_000L
                val now = System.nanoTime()
                if (now < due) Thread.sleep((due - now) / 1_000_000, ((due - now) % 1_000_000).toInt())
                else lateMax = maxOf(lateMax, (now - due) / 1_000_000)
                val n = minOf(VoiceSession.CHUNK, length)
                stream.copyInto(buffer, offset, next * VoiceSession.CHUNK, next * VoiceSession.CHUNK + n)
                delivered[next++] = System.nanoTime()
                return n
            }
            override fun abort() {}
            override fun release() {}
        }
        val texts = ArrayList<Pair<Long, String>>()
        val done = CountDownLatch(1)
        val cpu0 = cpuMs()
        val reader = Executors.newSingleThreadExecutor()
        val transcriber = Executors.newSingleThreadExecutor()
        val main = Executors.newSingleThreadExecutor()
        val session = VoiceSession({ capture }, { holder }, reader, transcriber, main, object : VoiceSession.Listener {
            override fun onState(state: VoiceSession.State) { if (state == VoiceSession.State.IDLE) done.countDown() }
            override fun onText(text: String) {
                val t = System.nanoTime()
                texts.add(t to text)
                println("LIVE TEXT t_ms=${(t - t0) / 1_000_000} audio_ms=${capture.next * 100} | $text")
            }
            override fun onFailure(failure: VoiceSession.Failure) { println("LIVE FAILURE $failure") }
        }, maxSeconds = blocks / 10 + 1) { // longer than the app's 60 s, so that one run measures all utterances (the array grows to the stream)
            PauseDetector(pauseSamples = pause, minPhraseSamples = minPhrase)
        }
        session.start()
        check(done.await(600, TimeUnit.SECONDS)) { "the session did not end" }
        val wallMs = (System.nanoTime() - t0) / 1_000_000
        val cpu = cpuMs() - cpu0
        // with one text per utterance the n-th text is the n-th utterance's; otherwise the next text after the end of the speech
        val oneEach = texts.size == parts.size
        val latencies = speechEndBlock.mapIndexed { u, b ->
            val end = delivered[b]
            val next = if (oneEach) texts[u].first else texts.firstOrNull { it.first >= end }?.first
            val ms = if (next == null) -1 else (next - end) / 1_000_000
            println("LIVE LAT $lang ${wavs[u].name.removeSuffix(".wav")} speech_end_ms=${(end - t0) / 1_000_000} ms=$ms")
            ms
        }.filter { it >= 0 }
        val steady = latencies.drop(1).sorted()
        println("LIVE SUMMARY $lang ${if (warm) "warm" else "cold"} utterances=${parts.size} texts=${texts.size} audio_ms=${blocks * 100} wall_ms=$wallMs " +
            "matched=${if (oneEach) "one_text_per_utterance" else "next_text"} first_ms=${latencies.firstOrNull()} median_ms=${steady.getOrNull(steady.size / 2)} p90_ms=${steady.getOrNull(steady.size * 9 / 10)} max_ms=${steady.lastOrNull()} " +
            "cpu_ms=$cpu cores_avg=${"%.2f".format(cpu.toDouble() / wallMs)} peak_rss_kb=${status("VmHWM")} late_read_max_ms=$lateMax")
        println("LIVE JOINED $lang | ${texts.joinToString(" ") { it.second }}")
        holder.unload()
        reader.shutdown(); transcriber.shutdown(); main.shutdown()
    }
}
