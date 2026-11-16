// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword

import android.content.res.AssetManager
import helium314.keyboard.latin.nextword.engine.BpeTokenizer
import helium314.keyboard.latin.nextword.engine.NextWordRanker
import helium314.keyboard.latin.nextword.engine.OrtNextWord
import helium314.keyboard.latin.nextword.engine.PreSplit
import java.util.Locale

/**
 * The measured result of a model on the project's reference set (tools/nextword, the 1000 held-out contexts) and on the reference phone:
 * top-3 next-word hits of the dictionary alone and with the model (per mille), and the step time and added memory on that phone. [adopted] is the accuracy
 * part of the gate (at least [NextWordGate.MIN_GAIN_PER_MILLE] better); time and memory are decided by the speed test on each phone. The settings page
 * shows these numbers.
 */
class Reference(val dictionaryPerMille: Int, val modelPerMille: Int, val referenceP95Ms: Long, val referenceExtraMb: Long) {
    val gainPerMille: Int get() = modelPerMille - dictionaryPerMille
    val adopted: Boolean get() = gainPerMille >= NextWordGate.MIN_GAIN_PER_MILLE
}

/**
 * One downloadable next-word model: its catalogue row ([id]), language, tokenizer files in the APK, how its tokenizer splits text, and [maxP95Ms], the
 * step time that 95 % of the steps of this phone's speed test must not exceed.
 */
class NextWordModel(
    val id: String,
    val language: String,
    val tokenizerAssets: String,
    val split: PreSplit,
    val maxP95Ms: Long,
    val reference: Reference,
) {
    /** The engine: the tokenizer from the APK and the model file at [modelPath] (already verified by the caller) on ONNX Runtime with [threads] threads. */
    fun load(assets: AssetManager, modelPath: String, threads: Int): NextWordRanker {
        val vocab = assets.open("$tokenizerAssets/vocab.json").use { it.readBytes() }.toString(Charsets.UTF_8)
        val merges = assets.open("$tokenizerAssets/merges.txt").use { it.readBytes() }.toString(Charsets.UTF_8)
        val tokenizer = BpeTokenizer.read(vocab, merges, split)
        return NextWordRanker(tokenizer, OrtNextWord(modelPath, threads))
    }
}

object NextWordModels {
    // measured on the reference phone over the 1000 contexts: 28.8 points better, 272 ms at p95, 337 MB added (over its 150 ms there)
    val ENGLISH = NextWordModel("model-en", "en", "nextword/smollm2-135m", PreSplit.SMOLLM2, maxP95Ms = 150,
        reference = Reference(121, 409, 272, 337))
    // accuracy on the build machine over the 1000 contexts (13.6 points better); on the reference phone over the first 100: 1403 ms at p95, 1395 MB added
    val BULGARIAN = NextWordModel("model-bg", "bg", "nextword/qwen3-0.6b", PreSplit.QWEN2, maxP95Ms = 250,
        reference = Reference(69, 205, 1403, 1395))
    val ALL = listOf(ENGLISH, BULGARIAN)

    fun byId(id: String): NextWordModel? = ALL.firstOrNull { it.id == id }

    /** The model of the language of [locale] (English for every English variant), or null. */
    fun forLocale(locale: Locale): NextWordModel? = ALL.firstOrNull { it.language == locale.language }
}
