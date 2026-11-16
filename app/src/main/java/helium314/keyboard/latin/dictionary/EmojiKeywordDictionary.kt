// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictionary

import helium314.keyboard.keyboard.emoji.EmojiSearchIndex
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.makedict.ProbabilityInfo
import helium314.keyboard.latin.makedict.WeightedString
import helium314.keyboard.latin.makedict.WordProperty
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import java.util.Locale

/**
 * The emoji dictionary of the keyboard: suggestions of emoji for a typed word, from the CLDR keywords of the bundled
 * [EmojiSearchIndex] (no network, no user data, nothing stored). Type [Dictionary.TYPE_EMOJI], so everything that handles
 * emoji dictionaries (suggestion strip, inline search) works with it.
 *
 * - [searchMode] false (the suggestion strip): the emoji whose keyword is the whole typed word, at most [MAX_IN_STRIP], and
 *   only for the indexes of the typing language.
 * - [searchMode] true (inline search after ":"): the emoji for which the typed words match (words and starts of words), all of
 *   them; [indexes] may also hold English as a second language.
 *
 * Emoji are suggestions to pick: this dictionary never makes a word valid, has no word list (the glide typing word list and the
 * spell check do not see it), and Suggest never auto-corrects to an emoji.
 */
class EmojiKeywordDictionary(
    locale: Locale,
    private val indexes: List<EmojiSearchIndex>,
    private val searchMode: Boolean,
) : Dictionary(TYPE_EMOJI, locale) {

    override fun getSuggestions(
        composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
        settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
        inOutWeightOfLangModelVsSpatialModel: FloatArray?,
    ): ArrayList<SuggestedWordInfo>? {
        val typed = composedData.mTypedWord.trim()
        if (typed.length < MIN_LENGTH) return null
        val hits = indexes.flatMap { if (searchMode) it.search(typed) else it.exact(typed) }.distinct()
            .let { if (searchMode) it else it.take(MAX_IN_STRIP) }
        if (hits.isEmpty()) return null
        // the scores fall with the position in the list
        return hits.mapIndexedTo(ArrayList()) { i, emoji ->
            SuggestedWordInfo(
                emoji, "", ((BASE_SCORE - i) * weightForLocale.toDouble()).toInt().coerceAtLeast(1), SuggestedWordInfo.KIND_COMPLETION, this,
                SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE
            )
        }
    }

    // an emoji or a keyword is not a word of the language
    override fun isInDictionary(word: String) = false

    /** The description of an emoji (its CLDR name) as the one shortcut target, the form the palette and the search read. */
    override fun getWordProperty(word: String, isBeginningOfSentence: Boolean): WordProperty? {
        val name = indexes.firstNotNullOfOrNull { it.name(word) } ?: return null
        return WordProperty(word, ProbabilityInfo(1), arrayListOf(WeightedString(name, 1)), null, true, false)
    }

    companion object {
        const val MIN_LENGTH = 2
        const val MAX_IN_STRIP = 3
        // above the scores of the dictionary words (also of the corrections that fill the strip, seen on the reference phone for "heart"), so
        // that the best emoji takes the third slot of the strip: Suggest keeps the first two suggestions non-emoji
        // (makeFirstTwoSuggestionsNonEmoji) and never auto-corrects to an emoji; below the maximum score of the typed word and of
        // the whitelist
        private const val BASE_SCORE = 2_000_000_000
    }
}
