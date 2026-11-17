// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The measurement on the build machine (make nextword-eval, BUILDING.md section 10): the shipped models on the reference set. Needs the model files
 * (TAEN_NEXTWORD_MODEL_DIR with model-en.onnx and model-bg.onnx, tools/nextword-eval.sh fetches them) and the contexts (TAEN_NEXTWORD_EVAL_DIR with en/ and
 * bg/ from tools/nextword/contexts.py). Prints one EVAL line per model: top-3 hits of the dictionary alone and with the model, and the step times.
 */
class NextWordEvalTest {
    private val assets = File(System.getProperty("taen.assets"))

    private fun run(language: String, model: String, tokenizer: String, split: PreSplit) {
        val models = System.getenv("TAEN_NEXTWORD_MODEL_DIR")?.let(::File)
        val eval = System.getenv("TAEN_NEXTWORD_EVAL_DIR")?.let(::File)
        assumeTrue("TAEN_NEXTWORD_MODEL_DIR and TAEN_NEXTWORD_EVAL_DIR are needed", models != null && eval != null)
        val modelFile = File(models, "$model.onnx")
        val dir = File(eval, language)
        val cases = File(dir, "contexts.tsv").readLines().filter { it.isNotEmpty() }.map(NextWordEval::parse)
        val words = File(dir, "words.txt").readLines().toHashSet()
        val tok = BpeTokenizer.read(File(assets, "nextword/$tokenizer/vocab.json").readText(), File(assets, "nextword/$tokenizer/merges.txt").readText(), split)
        val threads = System.getenv("TAEN_NEXTWORD_THREADS")?.toInt() ?: 4
        NextWordRanker(tok, OrtNextWord(modelFile.path, threads)).use { ranker ->
            val r = NextWordEval(System::nanoTime).run(ranker, cases) { it in words }
            println("EVAL $language $model cases=${r.cases} dictionary_top3=${r.dictionaryHits} model_top3=${r.modelHits} gain_points=" +
                "%.1f".format((r.modelHits - r.dictionaryHits) / (r.cases / 100.0)) + " steps=${r.stepMillis.size} p50_ms=${r.percentile(0.5)} p95_ms=${r.percentile(0.95)} threads=$threads")
        }
    }

    /** The kept attention state is exact: a context fed in three steps gives the same distribution as the same context read at once by a fresh model. */
    /**
     * The wrapper against the Python ONNX Runtime (tools/nextword/reference_logprobs.py, smollm2-reference.tsv): the same sentence fed at once (chunks of 8)
     * and word by word with the kept attention state gives the reference's 10 most likely tokens with the same log-probabilities. The two feedings differ
     * from each other (the model quantises its activations per run), so each is compared with its own reference.
     */
    @Test fun theWrapperMatchesThePythonReferenceForBothFeedings() {
        val models = System.getenv("TAEN_NEXTWORD_MODEL_DIR")?.let(::File)
        assumeTrue("TAEN_NEXTWORD_MODEL_DIR is needed", models != null)
        val tok = BpeTokenizer.read(File(assets, "nextword/smollm2-135m/vocab.json").readText(), File(assets, "nextword/smollm2-135m/merges.txt").readText(), PreSplit.SMOLLM2)
        val path = File(models, "model-en.onnx").path
        val reference = javaClass.getResource("/smollm2-reference.tsv")!!.readText().lines().filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.split('\t') }.groupBy({ it[0] }, { it[1].toInt() to it[2].toFloat() })
        val full = tok.encode("Thank you very much for the lovely dinner and the great")
        org.junit.Assert.assertEquals(11, full.size)
        val fresh = OrtNextWord(path, 4).use { it.logProbs(full) }
        val stepwise = OrtNextWord(path, 4).use { m ->
            m.logProbs(full.copyOfRange(0, 4))
            m.logProbs(full.copyOfRange(0, 9))
            m.logProbs(full)
        }
        for ((name, got) in listOf("fresh" to fresh, "stepwise" to stepwise)) {
            val want = reference.getValue(name)
            val top = got.indices.sortedByDescending { got[it] }.take(10)
            println("REFERENCE $name top10=${top.joinToString(",")} max_abs_diff=${want.maxOf { (id, lp) -> kotlin.math.abs(got[id] - lp) }}")
            org.junit.Assert.assertEquals(name, want.map { it.first }, top)
            for ((id, lp) in want) org.junit.Assert.assertEquals("$name token $id", lp, got[id], 2e-3f)
        }
    }

    @Test fun english() = run("en", "model-en", "smollm2-135m", PreSplit.SMOLLM2)

    @Test fun bulgarian() = run("bg", "model-bg", "qwen3-0.6b", PreSplit.QWEN2)
}
