// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.latin.common.StringUtils
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import java.text.Normalizer
import java.util.Locale

/**
 * The user's words on voice text: the one step between the speech engine's text and the field ([apply], called by LatinIME.commitVoiceText).
 *
 * Rules, in this order:
 * 1. A word that is in a main dictionary of the active languages (as spoken or in lower case) is never changed.
 * 2. Any other word is compared with the personal and learned words. It is replaced only when exactly one of them matches:
 *    first the words with the same letters ignoring case and diacritics; when there is none, the words one edit away (one letter
 *    changed, added or removed, compared ignoring case and diacritics; only for words of at least [MIN_EDIT_LETTERS] letters). Two or
 *    more matches are ambiguous and nothing is changed. A word that is itself a personal or learned word is kept as it is.
 *    The replacement is spelled as the user's word; a capital first letter of the spoken word stays.
 * 3. A capital at a sentence start: the first word when the field is at a sentence start, and a word after a sentence terminator of the
 *    language and a space, only where the field and the settings use automatic capitals; upper case by the language's rules.
 *
 * Step 2 reads the personal and learned words only through [Source.userWords], which the owner of that decision gives or refuses
 * (DictionaryFacilitatorImpl.userWordsAllowed: not in a password, no-learning or incognito field or app, not while locked). Nothing is
 * learned or stored here.
 */
object VoiceWords {
    interface Source {
        /** The word is in a main dictionary of the active languages; also true when that cannot be told now (nothing is changed then). */
        fun isDictionaryWord(word: String): Boolean

        /** The personal and learned words, or null where they may not be used or could not all be read (nothing is replaced then). */
        fun userWords(): Collection<String>?
    }

    /**
     * @property atSentenceStart the field expects a capital at the cursor (the keyboard's automatic capitals: setting, field and text before it)
     * @property sentences the field and the settings use automatic capitals, so a sentence that starts inside the text gets one too
     */
    class Casing(val locale: Locale, val atSentenceStart: Boolean, val sentences: Boolean, val isTerminator: (Int) -> Boolean)

    const val MIN_EDIT_LETTERS = 4

    fun apply(text: String, casing: Casing, source: Source): String {
        val folded by lazy { source.userWords()?.distinct()?.map { it to fold(it, casing.locale) } }
        val out = StringBuilder(text.length)
        var capNext = casing.atSentenceStart
        var afterTerminator = false // a terminator was seen; the next word gets a capital once a space follows it
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetter(cp)) {
                val end = wordEnd(text, i)
                var word = text.substring(i, end)
                if (!source.isDictionaryWord(word) && !source.isDictionaryWord(word.lowercase(casing.locale))) {
                    folded?.let { users -> match(word, users, casing.locale)?.let { word = it } }
                }
                if (capNext) word = StringUtils.capitalizeFirstCodePoint(word, casing.locale)
                out.append(word)
                capNext = false
                afterTerminator = false
                i = end
                continue
            }
            when {
                casing.sentences && casing.isTerminator(cp) -> afterTerminator = true
                afterTerminator && Character.isWhitespace(cp) -> capNext = true
                Character.isDigit(cp) -> { afterTerminator = false; capNext = false }
            }
            out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** The user's word that is the only match of [word], or null (no match, several, or the word is a user word itself). */
    private fun match(word: String, users: List<Pair<String, String>>, locale: Locale): String? {
        val key = fold(word, locale)
        val same = users.filter { it.second == key }.map { it.first }
        val found = when {
            same.isNotEmpty() -> if (word in same || same.size > 1) return null else same[0]
            key.codePointCount(0, key.length) < MIN_EDIT_LETTERS -> return null
            else -> users.filter { oneEdit(it.second, key) }.map { it.first }.singleOrNull() ?: return null
        }
        val keepCapital = Character.isUpperCase(word.codePointAt(0)) && !Character.isUpperCase(found.codePointAt(0))
        return if (keepCapital) StringUtils.capitalizeFirstCodePoint(found, locale) else found
    }

    /** The end of the word that starts at [start]: letters and combining marks, with an apostrophe between two letters. */
    private fun wordEnd(text: String, start: Int): Int {
        var i = start
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val next = i + Character.charCount(cp)
            val inWord = Character.isLetter(cp) || Character.getType(cp) == Character.NON_SPACING_MARK.toInt()
                || ((cp == '\''.code || cp == '’'.code) && next < text.length && Character.isLetter(text.codePointAt(next)))
            if (!inWord) break
            i = next
        }
        return i
    }

    /**
     * Lower case without the diacritics of Latin letters: "José" and "jose" are the same letters. Marks on other letters stay, because there they
     * make another letter of the alphabet (Bulgarian "й" is not "и").
     */
    fun fold(word: String, locale: Locale): String {
        val decomposed = Normalizer.normalize(word.lowercase(locale), Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var latinBase = false
        for (c in decomposed) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) {
                if (!latinBase) out.append(c)
                continue
            }
            latinBase = c.code < LATIN_END
            out.append(c)
        }
        return Normalizer.normalize(out, Normalizer.Form.NFC)
    }

    private const val LATIN_END = 0x0250 // Basic Latin to Latin Extended-B

    /** [a] and [b] differ by exactly one letter changed, added or removed. */
    fun oneEdit(a: String, b: String): Boolean {
        if (a == b) return false
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (long.length - short.length > 1) return false
        var i = 0
        while (i < short.length && short[i] == long[i]) i++
        return if (short.length == long.length) short.regionMatches(i + 1, long, i + 1, short.length - i - 1)
        else short.regionMatches(i, long, i + 1, short.length - i)
    }

    /** The words of [facilitator] for the field of [settings]: the personal and learned words only where the facilitator allows them. */
    fun source(facilitator: DictionaryFacilitator, settings: SettingsValuesForSuggestion): Source = object : Source {
        override fun isDictionaryWord(word: String) = facilitator.isMainDictionaryWord(word)
        override fun userWords(): Collection<String>? = facilitator.userWordsForVoice(settings)
    }
}
