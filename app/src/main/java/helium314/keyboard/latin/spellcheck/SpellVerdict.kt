// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.spellcheck

import helium314.keyboard.latin.common.StringUtils

/**
 * Decides whether a word that is not in the dictionary is underlined. The word lists behind the dictionaries lack names, abbreviations and rarer
 * word forms: of the 133,603 Bulgarian words of a held-out Wikipedia text, 6.7 % are not in main_bg.dict, and 4.6 points of those are written with a
 * capital (names such as Джеймс, abbreviations such as САЩ). An unknown lowercase word is a typo; an unknown capitalized or all-capitals word is one only
 * when the dictionary has a close word that it recommends instead.
 */
object SpellVerdict {
    /** @param capitalizeType StringUtils.CAPITALIZE_NONE, _FIRST or _ALL of the word; [hasRecommendedSuggestion] is only asked for a capitalized word. */
    fun isTypo(capitalizeType: Int, hasRecommendedSuggestion: () -> Boolean): Boolean =
        capitalizeType == StringUtils.CAPITALIZE_NONE || hasRecommendedSuggestion()
}
