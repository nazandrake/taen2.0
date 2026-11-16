// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bench

import helium314.keyboard.latin.nextword.engine.BpeTokenizer
import helium314.keyboard.latin.nextword.engine.NextWordEval
import helium314.keyboard.latin.nextword.engine.NextWordRanker
import helium314.keyboard.latin.nextword.engine.OrtNextWord
import helium314.keyboard.latin.nextword.engine.PreSplit
import java.io.File

/**
 * Phone benchmark of a next-word model on the reference set (the measurement of NextWordEval, as on the build machine), compiled only into the
 * debugNoMinify variant, never into a shipped build. Run from a shell without the keyboard:
 *   CLASSPATH=<base.apk> LD_LIBRARY_PATH=<nativeLibraryDir> app_process / helium314.keyboard.bench.NextWordBench <model.onnx> <tokenizer dir> <SMOLLM2|QWEN2> <eval dir> <threads> [<contexts>]
 * The eval dir holds contexts.tsv and words.txt (tools/nextword/contexts.py). Prints the hits, the step times and the resident memory before the model was
 * loaded (the word list already in memory) and at its peak, so the memory the model adds is their difference.
 */
object NextWordBench {
    private fun statusKb(field: String): Long = File("/proc/self/status").readLines().first { it.startsWith("$field:") }.filter { it.isDigit() }.toLong()

    @JvmStatic
    fun main(args: Array<String>) {
        val model = args[0]
        val tokenizerDir = File(args[1])
        val split = PreSplit.valueOf(args[2])
        val eval = File(args[3])
        val threads = args[4].toInt()
        val limit = args.getOrNull(5)?.toInt() ?: Int.MAX_VALUE
        val cases = File(eval, "contexts.tsv").readLines().filter { it.isNotEmpty() }.map(NextWordEval::parse).take(limit)
        val words = File(eval, "words.txt").readLines().toHashSet()
        val tok = BpeTokenizer.read(File(tokenizerDir, "vocab.json").readText(), File(tokenizerDir, "merges.txt").readText(), split)
        System.gc()
        val before = statusKb("VmRSS")
        val hwmBefore = statusKb("VmHWM")
        val t0 = System.nanoTime()
        NextWordRanker(tok, OrtNextWord(model, threads)).use { ranker ->
            val loaded = statusKb("VmRSS")
            println("BENCH LOAD ms=${(System.nanoTime() - t0) / 1_000_000} threads=$threads rss_before_kb=$before rss_loaded_kb=$loaded")
            // a sampler thread reads the resident memory every 100 ms, so the timed steps do not read /proc themselves
            val peak = java.util.concurrent.atomic.AtomicLong(loaded)
            val sampler = Thread {
                while (!Thread.currentThread().isInterrupted) {
                    peak.accumulateAndGet(statusKb("VmRSS"), ::maxOf)
                    try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                }
            }.apply { isDaemon = true; start() }
            val r = NextWordEval(System::nanoTime).run(ranker, cases) { it in words }
            sampler.interrupt()
            sampler.join()
            peak.accumulateAndGet(statusKb("VmRSS"), ::maxOf)
            println("BENCH EVAL cases=${r.cases} dictionary_top3=${r.dictionaryHits} model_top3=${r.modelHits} steps=${r.stepMillis.size} p50_ms=${r.percentile(0.5)} " +
                "p95_ms=${r.percentile(0.95)} max_ms=${r.stepMillis.maxOrNull()}")
            // the high-water mark also holds the word-list load before the model, so the sampled peak is the number that counts
            println("BENCH DONE rss_before_kb=$before peak_rss_kb=${peak.get()} added_mb=${(peak.get() - before) / 1024} hwm_before_kb=$hwmBefore hwm_end_kb=${statusKb("VmHWM")}")
        }
    }
}
