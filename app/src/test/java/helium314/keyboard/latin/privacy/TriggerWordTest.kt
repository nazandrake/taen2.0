// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.security.SecureRandom
import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A hasher that counts how often the slow hash ran: the cost of a check is the number of hashes. */
/** The word separators of res/values/donottranslate-config-spacing-and-punctuations.xml (symbols_word_separators) without the white space; the sweep over the real resource is in SelfDestructWiringTest. */
const val KEYBOARD_SEPARATORS = ".,;:!?/_()[]{}*&<>+=|\"„“…‽"
val noSeparators = { _: Int -> false }

class CountingHasher(kdf: ExportCodec.Kdf) : PinHasher(kdf) {
    var calls = 0
    override fun hash(pin: CharArray, salt: ByteArray, params: ExportCodec.Kdf): ByteArray {
        calls++
        return super.hash(pin, salt, params)
    }
}

/** The hidden trigger word: the rules for a word, which text is checked, and the match. */
class TriggerWordTest {
    private val fastKdf = ExportCodec.Kdf(memoryKiB = 64, iterations = 1, lanes = 1)
    private val hasher = CountingHasher(fastKdf)
    private val matcher = TriggerMatcher(hasher)
    private val word = "Blue#Horse-4711" // 15 characters
    private val rec = SecretRecords.create(word.toCharArray(), hasher, SecureRandom())

    init { hasher.calls = 0 } // making the record is not a check

    private fun tokenOf(before: CharSequence) = matcher.tokenOf(before, noSeparators)

    private fun check(before: CharSequence): Boolean? {
        val token = tokenOf(before) ?: return null
        return matcher.matches(token, rec)
    }

    // ---- which text is checked: only a token of 12 to 64 characters, so the slow hash runs rarely -------------------

    @Test fun onlyATokenOfTwelveToSixtyFourCharactersIsHashed() {
        fun t(n: Int) = "a".repeat(n - 1) + "1" // not only letters, so only the length decides
        for (n in listOf(1, 11)) assertNull(tokenOf(t(n)), "length $n")
        assertNull(tokenOf(""))
        for (n in listOf(12, 13, 63, 64)) assertEquals(n, tokenOf(t(n))?.size, "length $n")
        assertNull(tokenOf(t(65)))
        assertNull(tokenOf(t(66)))
        assertEquals(0, hasher.calls) // tokenOf never hashes
    }

    /** Setup refuses a word that is only letters or holds a separator, so such a token is never the word: no hash, and it keeps autocorrect and learning. */
    @Test fun aTokenThatCouldNotBeTheWordCostsNoHash() {
        for (n in listOf(12, 20, 64)) assertNull(tokenOf("a".repeat(n)), "all letters, length $n")
        assertNull(tokenOf("hello ${"Ünïcödé".repeat(3)}")) // letters of other scripts and accents are still letters
        assertNull(tokenOf("АБВГДЕЖЗИЙКЛМ"))
        assertEquals(15, tokenOf("Blue#Horse-4711")?.size) // positive control: a symbol and digits make it a candidate
        assertEquals(12, tokenOf("abcdefghijk1")?.size)
        assertEquals(12, tokenOf("abcdefghijk#")?.size)
        val isSeparator = { cp: Int -> cp.toChar() in KEYBOARD_SEPARATORS }
        for (c in KEYBOARD_SEPARATORS) {
            for (w in listOf("${c}abcdefghij1", "abcde${c}fghij1", "abcdefghij1$c")) {
                assertNull(matcher.tokenOf(w, isSeparator), w)
                assertEquals(12, matcher.tokenOf(w, noSeparators)?.size, "control $w") // the same text passes without the separator rule
            }
        }
        assertNull(tokenOf("abcdefghij1😀")) // an emoji splits the input whatever the separator set
        assertEquals(0, hasher.calls)
    }

    @Test fun theTokenIsTheTextAfterTheLastWhitespaceOfTheTypedText() {
        assertEquals("Blue#Horse-4711", String(tokenOf("see me Blue#Horse-4711")!!))
        assertEquals("Blue#Horse-4711", String(tokenOf("line one\nBlue#Horse-4711")!!))
        assertEquals("Blue#Horse-4711", String(tokenOf("a\tBlue#Horse-4711")!!))
        assertEquals("Blue#Horse-4711", String(tokenOf("a Blue#Horse-4711")!!)) // no-break space
        assertNull(tokenOf("Blue#Horse-4711 ")) // the cursor is after a space: no token
        assertNull(tokenOf("Blue#Horse-4711\n"))
        assertNull(tokenOf(""))
    }

    @Test fun onlyTheLastSixtyFiveCharactersAreLookedAtSoAHugeFieldCostsNothing() {
        val huge = object : CharSequence { // 50 million characters that all count as token characters
            override val length = 50_000_000
            var reads = 0L
            override fun get(index: Int): Char { reads++; return 'x' }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = throw UnsupportedOperationException()
            override fun toString(): String = throw UnsupportedOperationException()
        }
        assertNull(tokenOf(huge))
        assertTrue(huge.reads <= TriggerMatcher.WINDOW + 1, "read ${huge.reads} characters")
    }

    // ---- the match -------------------------------------------------------------------------------------------------

    @Test fun theExactWordMatchesAndOnlyTheExactWord() {
        assertEquals(true, check("hello Blue#Horse-4711"))
        assertEquals(1, hasher.calls)
        for (near in listOf(
            "blue#Horse-4711", // case
            "Blue#Horse-4712", // one character different
            "Blue#Horse-471", // prefix of the word (14 characters, so it is hashed and refused)
            "Blue#Horse-47111", // word plus a character
            "xBlue#Horse-4711", // a character in front, glued
            "Blue#Horse-4711x",
            "Blue#Horse4711",
            "BlueHorse-4711",
        )) assertEquals(false, check(near), near)
    }

    @Test fun aWordInsideALongerStringDoesNotMatch() {
        assertEquals(false, check("prefix" + word + "suffix12"))
        assertEquals(false, check("12345" + word))
        assertEquals(false, check(word + "12345"))
    }

    @Test fun theWordIsComparedInNormalisedFormSoComposedAndDecomposedLettersAgree() {
        val composed = "Café#Horse-4711" // é as one code point
        val decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD)
        assertTrue(composed != decomposed)
        val record = SecretRecords.create(decomposed.toCharArray(), hasher, SecureRandom()) // stored from the decomposed form
        assertTrue(matcher.matches(tokenOf(composed)!!, record))
        assertTrue(matcher.matches(tokenOf(decomposed)!!, record))
        assertFalse(matcher.matches(tokenOf("Cafe#Horse-4711")!!, record))
    }

    @Test fun couldBeWordFollowsTheSetupRules() {
        val isSeparator = { cp: Int -> cp.toChar() in KEYBOARD_SEPARATORS }
        for (w in listOf("Blue#Horse-4711", "abcdefghijk1", "a".repeat(63) + "1", "\uD835\uDC00".repeat(12) + "1")) { // the last: supplementary letters and a digit
            assertTrue(TriggerMatcher.couldBeWord(w, isSeparator), w)
            assertEquals(TriggerWordRules.Result.OK, TriggerWordRules.check(w.toCharArray(), isSeparator) { false }, "setup agrees: $w") // one rule, two uses
        }
        for (w in listOf("abcdefghij1", "a".repeat(64) + "1", "a".repeat(20), "abcde.fghij1", "abc def ghij1")) {
            assertFalse(TriggerMatcher.couldBeWord(w, isSeparator), w)
        }
        // an only-letters word of supplementary characters is not "all letters" for setup (per char), and not for the matcher either
        val supplementary = "\uD835\uDC00".repeat(12)
        assertEquals(TriggerWordRules.Result.OK, TriggerWordRules.check(supplementary.toCharArray(), noSeparators) { false })
        assertTrue(TriggerMatcher.couldBeWord(supplementary, noSeparators))
    }

    @Test fun theTokenIsClearedAfterTheCheck() {
        val token = tokenOf("Blue#Horse-4711")!!
        matcher.matches(token, rec)
        assertContentEquals(CharArray(token.size), token)
    }

    @Test fun aWrongRecordSaltOrHashNeverMatches() {
        val token = { tokenOf(word)!! }
        assertFalse(matcher.matches(token(), SecretRecord(rec.salt.reversedArray(), rec.hash, rec.kdf)))
        assertFalse(matcher.matches(token(), SecretRecord(rec.salt, ByteArray(32), rec.kdf)))
        assertTrue(matcher.matches(token(), rec)) // positive control
    }

    // ---- the rules for a word that is set ------------------------------------------------------------------------

    @Test fun lengthBoundariesAreTwelveAndSixtyFour() {
        fun r(w: String) = TriggerWordRules.check(w.toCharArray(), noSeparators) { false }
        assertEquals(TriggerWordRules.Result.TOO_SHORT, r("abcdefghij1")) // 11
        assertEquals(TriggerWordRules.Result.OK, r("abcdefghijk1")) // 12
        assertEquals(TriggerWordRules.Result.OK, r("a".repeat(63) + "1")) // 64
        assertEquals(TriggerWordRules.Result.TOO_LONG, r("a".repeat(64) + "1")) // 65
        assertEquals(TriggerWordRules.Result.TOO_SHORT, r(""))
    }

    @Test fun aWordNeedsADigitOrASymbolAndHoldsNoWhitespace() {
        fun r(w: String) = TriggerWordRules.check(w.toCharArray(), noSeparators) { false }
        assertEquals(TriggerWordRules.Result.NEEDS_DIGIT_OR_SYMBOL, r("abcdefghijklmnop"))
        assertEquals(TriggerWordRules.Result.NEEDS_DIGIT_OR_SYMBOL, r("АБВГДЕЖЗИЙКЛМ")) // letters of another script are still only letters
        assertEquals(TriggerWordRules.Result.OK, r("abcdefghijkl1"))
        assertEquals(TriggerWordRules.Result.OK, r("abcdefghijkl#"))
        assertEquals(TriggerWordRules.Result.OK, r("123456789012")) // digits only is allowed
        assertEquals(TriggerWordRules.Result.WHITESPACE, r("abcdef ghijkl1"))
        assertEquals(TriggerWordRules.Result.WHITESPACE, r("abcdefghijkl1\n"))
        assertEquals(TriggerWordRules.Result.WHITESPACE, r("abcdefghijkl1 "))
    }

    @Test fun aWordWithACharacterTheKeyboardTreatsAsASeparatorIsRefused() {
        val isSeparator = { cp: Int -> cp.toChar() in KEYBOARD_SEPARATORS }
        for (c in KEYBOARD_SEPARATORS) {
            // at the start, in the middle and at the end: each place ends or changes the composed word
            for (w in listOf("${c}abcdefghij1", "abcde${c}fghij1", "abcdefghij1$c"))
                assertEquals(TriggerWordRules.Result.SEPARATOR, TriggerWordRules.check(w.toCharArray(), isSeparator) { false }, w)
            // positive control: with no separator rule the same words pass (they have no white space and no symbol of the other-symbol class)
            assertEquals(TriggerWordRules.Result.OK, TriggerWordRules.check("abcde${c}fghij1".toCharArray(), noSeparators) { false }, "control $c")
        }
        // '#' and '-' are not separators of the keyboard: the example word still passes
        assertEquals(TriggerWordRules.Result.OK, TriggerWordRules.check(word.toCharArray(), isSeparator) { false })
    }

    @Test fun anEmojiAndAnyOtherSymbolTheInputLogicSplitsOnIsRefusedWhateverTheSeparatorSet() {
        for (w in listOf("abcdefghij1😀", "abc😀defghij1", "abcdefghij1☃")) // emoji, snowman (other symbol)
            assertEquals(TriggerWordRules.Result.SEPARATOR, TriggerWordRules.check(w.toCharArray(), noSeparators) { false }, w)
        assertEquals(TriggerWordRules.Result.OK, TriggerWordRules.check("abcdefghij1+".toCharArray(), noSeparators) { false }) // a math symbol is not split on
        assertEquals(TriggerWordRules.Result.WHITESPACE, TriggerWordRules.check("abcdef ghij1".toCharArray(), noSeparators) { false }) // white space is still reported as such
    }

    @Test fun aWordFoundInADictionaryOrTheLearnedWordsIsRefused() {
        val seen = ArrayList<String>()
        val known = { s: CharSequence -> seen += s.toString(); s.toString() == "Blue#Horse-4711" }
        assertEquals(TriggerWordRules.Result.KNOWN_WORD, TriggerWordRules.check(word.toCharArray(), noSeparators, known))
        assertEquals(TriggerWordRules.Result.OK, TriggerWordRules.check("Other#Horse-4711".toCharArray(), noSeparators, known)) // positive control
        assertEquals(listOf("Blue#Horse-4711", "Other#Horse-4711"), seen)
    }

    @Test fun theLengthIsCountedAfterNormalisation() {
        val decomposed = Normalizer.normalize("é".repeat(11) + "1", Normalizer.Form.NFD) // 12 letters, 23 chars decomposed
        assertEquals(TriggerWordRules.Result.OK, TriggerWordRules.check(decomposed.toCharArray(), noSeparators) { false })
        val tooShortComposed = Normalizer.normalize("é".repeat(10) + "1", Normalizer.Form.NFD) // 11 letters composed, 21 chars decomposed
        assertEquals(TriggerWordRules.Result.TOO_SHORT, TriggerWordRules.check(tooShortComposed.toCharArray(), noSeparators) { false })
    }

    // ---- the record ---------------------------------------------------------------------------------------------

    @Test fun theRecordHoldsOnlyASaltAndAHashAndEachRecordGetsItsOwnSalt() {
        val a = SecretRecords.create(word.toCharArray(), hasher, SecureRandom())
        val b = SecretRecords.create(word.toCharArray(), hasher, SecureRandom())
        assertEquals(16, a.salt.size)
        assertEquals(32, a.hash.size)
        assertFalse(a.salt.contentEquals(b.salt))
        assertFalse(a.hash.contentEquals(b.hash))
        for (bytes in listOf(a.salt + a.hash, SelfDestructConfigCodec.encode(SelfDestructConfig.DEFAULT.copy(word = a.withFloorKdf())))) {
            for (needle in encodings(word) + encodings("Blue#Horse") + encodings("4711")) assertEquals(-1, bytes.indexOfSub(needle))
        }
    }

    @Test fun theSlowHashIsAboveTheFloorAndBoundedInTimeAndMemory() {
        val kdf = SecretRecords.KDF
        assertTrue(kdf.memoryKiB >= SecretRecords.MIN_MEMORY_KIB && kdf.iterations >= SecretRecords.MIN_ITERATIONS)
        assertTrue(kdf.memoryKiB <= SecretRecords.MAX_MEMORY_KIB)
        assertEquals(8192, kdf.memoryKiB) // 8 MiB: measured about 240 ms per check on the reference phone (the PIN parameters)
        // a check with the real parameters is one hash of at most 64 characters; on the build machine it is far below a second
        val real = PinHasher(kdf)
        val record = SecretRecords.create(word.toCharArray(), real, SecureRandom())
        val start = System.nanoTime()
        assertTrue(TriggerMatcher(real).matches(TriggerMatcher(real).tokenOf(word, noSeparators)!!, record))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3000)
    }

    private fun SecretRecord.withFloorKdf() = SecretRecord(salt, hash, SecretRecords.KDF)
}
