// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The user's words on voice text: casing, and the personal and learned words (expected values written by hand from the rules in VoiceWords). */
class VoiceWordsTest {
    private val terminators = ".?!‽" // res/values symbols_sentence_terminators (English and Bulgarian use the default)
    private val bulgarian = Locale.forLanguageTag("bg")

    private fun casing(locale: Locale = Locale.ENGLISH, start: Boolean = true, sentences: Boolean = true) =
        VoiceWords.Casing(locale, start, sentences) { it.toChar() in terminators }

    private class Words(private val dictionary: Set<String>, private val user: Collection<String>?) : VoiceWords.Source {
        var userReads = 0
        override fun isDictionaryWord(word: String) = word in dictionary
        override fun userWords(): Collection<String>? { userReads++; return user }
    }

    private val english = setOf("hello", "world", "the", "is", "this", "it", "my", "name", "server", "runs", "on", "i", "use", "and", "next",
        "percent", "costs", "tea", "said", "he")

    private fun apply(text: String, user: Collection<String>?, casing: VoiceWords.Casing = casing(start = false, sentences = false), dictionary: Set<String> = english) =
        VoiceWords.apply(text, casing, Words(dictionary, user))

    // ---- casing ----

    @Test fun aSentenceStartGetsACapitalInEnglish() {
        assertEquals("Hello world. This is it", VoiceWords.apply("hello world. this is it", casing(), Words(english, emptyList())))
    }

    @Test fun aSentenceStartGetsACapitalInBulgarian() {
        val words = setOf("здравей", "как", "си", "добре", "съм")
        assertEquals("Здравей. Как си? Добре съм! Добре", VoiceWords.apply("здравей. как си? добре съм! добре", casing(bulgarian), Words(words, emptyList())))
        // the field is not at a sentence start: only the sentences inside the text start with a capital
        assertEquals("как си? Добре", VoiceWords.apply("как си? добре", casing(bulgarian, start = false), Words(words, emptyList())))
    }

    @Test fun upperCaseFollowsTheLanguage() {
        // Turkish: the capital of i is İ (the locale's rule, StringUtils.capitalizeFirstCodePoint); English: I
        assertEquals("İstanbul", VoiceWords.apply("istanbul", casing(Locale.forLanguageTag("tr")), Words(setOf("istanbul"), emptyList())))
        assertEquals("Istanbul", VoiceWords.apply("istanbul", casing(), Words(setOf("istanbul"), emptyList())))
    }

    @Test fun noCapitalsWhereTheFieldOrTheSettingsHaveNone() {
        assertEquals("hello world. this is it", VoiceWords.apply("hello world. this is it", casing(start = false, sentences = false), Words(english, emptyList())))
        // a field at a sentence start whose sentences inside get no capitals (the start alone)
        assertEquals("Hello. this", VoiceWords.apply("hello. this", casing(sentences = false), Words(english, emptyList())))
        // capitals the engine wrote are never taken away
        assertEquals("Hello World", VoiceWords.apply("Hello World", casing(start = false, sentences = false), Words(english, emptyList())))
    }

    @Test fun aCapitalNeedsATerminatorAndASpace() {
        val c = casing(start = false)
        assertEquals("it costs 3.5 percent. Next", VoiceWords.apply("it costs 3.5 percent. next", c, Words(english, emptyList())))
        assertEquals("he said \"hello.\" Next", VoiceWords.apply("he said \"hello.\" next", c, Words(english, emptyList()))) // a closing quote between
        assertEquals("it.is", VoiceWords.apply("it.is", c, Words(english, emptyList()))) // no space: not a sentence start
        assertEquals("3 tea", VoiceWords.apply("3 tea", casing(), Words(english, emptyList()))) // a number starts the sentence: no letter to raise
    }

    // ---- the personal and learned words ----

    @Test fun aLearnedWordReplacesAnUnknownWordOneEditAway() {
        assertEquals("My server runs kubernetes", VoiceWords.apply("my server runs kubernetis", casing(), Words(english, listOf("kubernetes"))))
        assertEquals("my server runs kubernetes", apply("my server runs kubernetes", listOf("kubernetes"))) // already right: unchanged
        assertEquals("kubernetes", apply("kubernets", listOf("kubernetes"))) // a letter missing
        assertEquals("kubernetes", apply("kuberneetes", listOf("kubernetes"))) // a letter too many
    }

    @Test fun aPersonalWordGivesItsSpellingIgnoringCaseAndDiacritics() {
        assertEquals("my name is José", apply("my name is jose", listOf("José")))
        assertEquals("i use Taen", apply("i use taen", listOf("Taen")))
        assertEquals("i use McAfee", apply("i use mcafee", listOf("McAfee")))
        assertEquals("O'Neill", apply("o'neil", listOf("O'Neill"))) // one word with its apostrophe, one letter missing
    }

    @Test fun aCapitalOfTheSpokenWordStays() {
        assertEquals("Kubernetes runs", apply("Kubernetis runs", listOf("kubernetes")))
        assertEquals("Taen", apply("Taen", listOf("taen"))) // the same word apart from the capital: nothing to change
    }

    @Test fun twoMatchesAreAmbiguousAndNothingChanges() {
        assertEquals("kubernetis", apply("kubernetis", listOf("kubernetes", "kubernetix"))) // two words one edit away
        assertEquals("taen", apply("taen", listOf("Taen", "TAEN"))) // two spellings of the same letters
        assertEquals("ana", apply("ana", listOf("Ána", "Anà")))
        // positive control: each one alone is taken
        assertEquals("kubernetes", apply("kubernetis", listOf("kubernetes")))
        assertEquals("Taen", apply("taen", listOf("Taen")))
        // the same word in the personal dictionary and the learned words is one candidate
        assertEquals("kubernetes", apply("kubernetis", listOf("kubernetes", "kubernetes")))
    }

    @Test fun theSameLettersWinOverWordsOneEditAway() {
        assertEquals("Zoë", apply("zoe", listOf("Zoë", "zone"))) // "zoe" folds to the letters of "Zoë"; "zone" would only be one edit away
    }

    @Test fun aWordThatIsAUserWordItselfStays() {
        assertEquals("kubernetis", apply("kubernetis", listOf("kubernetes", "kubernetis")))
    }

    @Test fun dictionaryWordsAreNeverChanged() {
        assertEquals("the server", apply("the server", listOf("sever", "teh")))
        assertEquals("Hello world", apply("Hello world", listOf("Hallo", "wörld"))) // valid in lower case
        // positive control: the same user words change a word that is not in the dictionary
        assertEquals("sever", apply("severr", listOf("sever")))
    }

    @Test fun shortWordsAreOnlyMatchedByTheirLetters() {
        assertEquals("teh", apply("teh", listOf("tea"), dictionary = setOf("the"))) // three letters: no edit match
        assertEquals("zork", apply("zorc", listOf("zork"))) // four letters: one edit is enough
        assertEquals("Ivo", apply("ivo", listOf("Ivo"))) // the same letters at any length
    }

    @Test fun withoutUserWordsOnlyTheCasingApplies() {
        val refused = Words(english, null) // the owner refused (incognito, locked) or could not read them all
        assertEquals("My server runs kubernetis", VoiceWords.apply("my server runs kubernetis", casing(), refused))
        assertEquals(1, refused.userReads)
    }

    @Test fun theUserWordsAreNotReadWhenEveryWordIsInTheDictionary() {
        val words = Words(english, listOf("kubernetes"))
        assertEquals("Hello world", VoiceWords.apply("hello world", casing(), words))
        assertEquals(0, words.userReads)
        assertEquals("Hello kubernetes", VoiceWords.apply("hello kubernetis", casing(), words)) // positive control: an unknown word reads them, once
        assertEquals(1, words.userReads)
    }

    @Test fun bulgarianWordsAndLetters() {
        val words = setOf("моята", "майка")
        assertEquals("моята Яница", VoiceWords.apply("моята яница", casing(bulgarian, start = false), Words(words, listOf("Яница"))))
        assertEquals("Моята Яница", VoiceWords.apply("моята яница", casing(bulgarian), Words(words, listOf("Яница"))))
        assertEquals("Пейо", apply("пейо", listOf("Пейо")))
        assertEquals("Пейо", apply("пеио", listOf("Пейо"))) // и for й is a changed letter (one edit), not a diacritic
        assertEquals("пеио", apply("пеио", listOf("Пейо", "Пеия"))) // two words one edit away
    }

    @Test fun foldingAndOneEdit() {
        assertEquals("jose", VoiceWords.fold("José", Locale.ENGLISH))
        assertEquals("zoe", VoiceWords.fold("ZOË", Locale.ENGLISH))
        assertEquals("пейо", VoiceWords.fold("Пейо", bulgarian)) // й keeps its breve: another letter of the alphabet
        assertEquals("ёлка", VoiceWords.fold("Ёлка", Locale.forLanguageTag("ru")))
        assertTrue(VoiceWords.oneEdit("abc", "abd"))
        assertTrue(VoiceWords.oneEdit("abc", "abcd"))
        assertTrue(VoiceWords.oneEdit("abcd", "abc"))
        assertTrue(VoiceWords.oneEdit("abc", "xbc"))
        assertTrue(VoiceWords.oneEdit("bc", "abc"))
        assertFalse(VoiceWords.oneEdit("abc", "abc"))
        assertFalse(VoiceWords.oneEdit("abc", "acb")) // two letters swapped: two edits
        assertFalse(VoiceWords.oneEdit("abc", "axyc"))
        assertFalse(VoiceWords.oneEdit("abc", "abcde"))
        assertFalse(VoiceWords.oneEdit("abcd", "xbcy"))
    }
}
