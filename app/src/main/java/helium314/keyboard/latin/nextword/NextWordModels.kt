// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword

import android.content.res.AssetManager
import helium314.keyboard.latin.nextword.engine.BpeTokenizer
import helium314.keyboard.latin.nextword.engine.NextWordRanker
import helium314.keyboard.latin.nextword.engine.OrtNextWord
import helium314.keyboard.latin.nextword.engine.PreSplit
import java.util.Locale

/**
 * The measured result of a model on the project's reference set (tools/nextword, the 1000 held-out contexts) and the reference phone:
 * top-3 next-word hits of the dictionary alone and with the model (per mille), and the step time and added memory on that phone. [adopted] is the
 * outcome of the gate: at least 50 per mille (5 points) better, p95 at most 150 ms and at most 300 MB added on the reference phone, or, for a model that
 * is checked on each phone ([NextWordModel.speedTestOnEachPhone], the limits of NextWordGate.Speed), the accuracy part alone; its reference phone numbers
 * are then recorded but do not decide. The settings page shows these numbers.
 */
class Reference(val dictionaryPerMille: Int, val modelPerMille: Int, val referenceP95Ms: Long, val referenceExtraMb: Long, val adopted: Boolean) {
    val gainPerMille: Int get() = modelPerMille - dictionaryPerMille
}

/** One downloadable next-word model: its catalogue row ([id]), language, tokenizer files in the APK and how its tokenizer splits text. */
class NextWordModel(
    val id: String,
    val language: String,
    val tokenizerAssets: String,
    val split: PreSplit,
    val speedTestOnEachPhone: Boolean,
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
    val ENGLISH = NextWordModel("model-en", "en", "nextword/smollm2-135m", PreSplit.SMOLLM2, speedTestOnEachPhone = false,
        reference = Reference(121, 409, 272, 337, adopted = false)) // 28.8 points better, but over 150 ms and 300 MB on the reference phone
    val BULGARIAN = NextWordModel("model-bg", "bg", "nextword/qwen3-0.6b", PreSplit.QWEN2, speedTestOnEachPhone = true,
        reference = Reference(69, 205, 1403, 1395, adopted = true)) // 13.6 points better; time and memory are decided on each phone
    val ALL = listOf(ENGLISH, BULGARIAN)

    fun byId(id: String): NextWordModel? = ALL.firstOrNull { it.id == id }

    /** The model of the language of [locale] (English for every English variant), or null. */
    fun forLocale(locale: Locale): NextWordModel? = ALL.firstOrNull { it.language == locale.language }
}
