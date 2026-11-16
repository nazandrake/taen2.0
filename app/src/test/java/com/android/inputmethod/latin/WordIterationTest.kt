// SPDX-License-Identifier: GPL-3.0-only
package com.android.inputmethod.latin

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The word walk behind the glide word list (BinaryDictionary.iterateWords) against a fake of the bulk native call
 * (getWordsBulkNative), which behaves like the real one (Dictionary::getWordsBulk, tested in tools/native-test.sh):
 * it writes the records [probability, length, code points...] of as many words as fit and leaves the rest of the
 * buffer as it was (the buffer is reused, so stale data stays behind the written part). History: one JNI call per
 * word (two with the probability) took up to 20 s for the 415 000 Bulgarian words on the reference phone; a first version read
 * the tail of a longer word into the next one because the native call writes no terminating zero.
 */
@RunWith(RobolectricTestRunner::class)
class WordIterationTest {
    private class FakeNative(
        private val entries: List<Pair<String, Int>>,
        /** the buffer capacity the "native side" is given: records are cut into chunks of at most this many ints */
        private val capacity: Int = 100_000,
        private val garbage: Int = 777,
    ) : BinaryDictionary.WordSource {
        var calls = 0
        var maxBuffer = 0

        override fun nextChunk(token: Int, records: IntArray, used: IntArray): Int {
            calls++
            maxBuffer = maxOf(maxBuffer, records.size)
            records.fill(garbage) // what an earlier chunk left in the reused buffer
            var at = 0
            var i = token
            val limit = minOf(capacity, records.size)
            while (i < entries.size) {
                val cps = entries[i].first.codePoints().toArray()
                if (at + 2 + cps.size > limit) break
                records[at++] = entries[i].second
                records[at++] = cps.size
                cps.forEach { records[at++] = it }
                i++
            }
            used[0] = at
            return if (i < entries.size) i else 0
        }
    }

    /** Whether the walk says it handed over the whole list (a caller must not keep a list for which this is false). */
    private fun complete(source: BinaryDictionary.WordSource) = BinaryDictionary.iterateWords(source) { _, _ -> }

    private fun walk(source: BinaryDictionary.WordSource): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        BinaryDictionary.iterateWords(source) { word, probability -> out.add(word to probability) }
        return out
    }

    @Test
    fun everyWordComesOutWithItsProbabilityInOrder() {
        val words = listOf("Изворчео" to 10, "Изгрев" to 11, "до" to 12, "България" to 13, "а" to 14)
        assertEquals(words, walk(FakeNative(words)))
    }

    @Test
    fun aWalkOverSeveralBuffersGivesTheSameListAsOneBuffer() {
        val words = (0 until 500).map { "дума$it" + "x".repeat(it % 7) to (it * 7) % 256 }
        val oneBuffer = walk(FakeNative(words))
        assertEquals(words, oneBuffer)
        val small = FakeNative(words, capacity = BinaryDictionary.DICTIONARY_MAX_WORD_LENGTH + 2 + 20)
        assertEquals(words, walk(small))
        assertEquals(true, small.calls > 50, "positive control: the small buffer needed ${small.calls} calls")
    }

    @Test
    fun theBufferHoldsOneLongestWordAndIsNotTheWholeDictionary() {
        val fake = FakeNative(listOf("ab" to 1))
        walk(fake)
        // at least one longest record, at most a few hundred kilobytes (the buffer is a transient copy)
        assertEquals(true, fake.maxBuffer >= BinaryDictionary.DICTIONARY_MAX_WORD_LENGTH + 2, fake.maxBuffer.toString())
        assertEquals(true, fake.maxBuffer <= 1 shl 18, fake.maxBuffer.toString())
    }

    @Test
    fun aWordOfTheMaximumLengthAndCodePointsAboveTheBmpAreKeptWhole() {
        val long = "x".repeat(BinaryDictionary.DICTIONARY_MAX_WORD_LENGTH)
        val emoji = "a😀b" // a, a grinning face (one code point, two chars), b
        val words = listOf(long to 7, "ab" to 8, emoji to 9)
        assertEquals(words, walk(FakeNative(words)))
    }

    @Test
    fun anEmptyDictionaryGivesNothing() {
        assertEquals(emptyList(), walk(FakeNative(emptyList())))
    }

    @Test
    fun aWalkThatMakesNoProgressEnds() {
        // a broken native side that hands out no record but a next token must not loop forever
        val stuck = object : BinaryDictionary.WordSource {
            var calls = 0
            override fun nextChunk(token: Int, records: IntArray, used: IntArray): Int { calls++; used[0] = 0; return 5 }
        }
        assertEquals(emptyList(), walk(stuck))
        assertEquals(1, stuck.calls)
    }

    @Test
    fun aDamagedRecordEndsTheWalkInsteadOfReadingPastWhatWasWritten() {
        // a valid record, then one whose length field (10) is more than the 1 code point the native side says it wrote;
        // what follows in the buffer is stale data ('x'): nothing is invented from it, nothing throws
        val damaged = object : BinaryDictionary.WordSource {
            override fun nextChunk(token: Int, records: IntArray, used: IntArray): Int {
                records.fill('x'.code)
                records[0] = 7; records[1] = 2; records[2] = 'a'.code; records[3] = 'b'.code
                records[4] = 5; records[5] = 10; records[6] = 'c'.code
                used[0] = 7
                return 0
            }
        }
        assertEquals(listOf("ab" to 7), walk(damaged))
        // a record that is cut inside its two header ints ends the walk too
        val cut = object : BinaryDictionary.WordSource {
            override fun nextChunk(token: Int, records: IntArray, used: IntArray): Int {
                records.fill('x'.code)
                records[0] = 7; records[1] = 1; records[2] = 'a'.code; records[3] = 9
                used[0] = 4
                return 0
            }
        }
        assertEquals(listOf("a" to 7), walk(cut))
        // an impossible length (more than a word can have, or not positive) ends the walk
        for (length in listOf(0, -1, BinaryDictionary.DICTIONARY_MAX_WORD_LENGTH + 1)) {
            val bad = object : BinaryDictionary.WordSource {
                override fun nextChunk(token: Int, records: IntArray, used: IntArray): Int {
                    records.fill('x'.code)
                    records[0] = 1; records[1] = length
                    used[0] = 60
                    return 0
                }
            }
            assertEquals(emptyList(), walk(bad), "length $length")
        }
    }

    // ---- the walk says whether the list is complete (a short list must not be kept as current) -----------

    @Test
    fun aCompleteWalkIsCompleteAlsoForAnEmptyDictionaryAndForSeveralBuffers() {
        val words = (0 until 200).map { "дума$it" to it }
        assertEquals(true, complete(FakeNative(words)))
        assertEquals(true, complete(FakeNative(words, capacity = BinaryDictionary.DICTIONARY_MAX_WORD_LENGTH + 2 + 20)))
        assertEquals(true, complete(FakeNative(emptyList()))) // an empty dictionary has all of its (no) words
    }

    @Test
    fun aWalkThatFailsIsNotCompleteWhateverItHandedOverBeforeTheFailure() {
        // the native side reports an error (damaged dictionary, a walk ended by another one) with a negative token
        val failing = object : BinaryDictionary.WordSource {
            override fun nextChunk(token: Int, records: IntArray, used: IntArray): Int {
                if (token == 0) { records[0] = 5; records[1] = 1; records[2] = 'a'.code; used[0] = 3; return 3 }
                used[0] = 0
                return -1
            }
        }
        assertEquals(false, complete(failing))
        assertEquals(listOf("a" to 5), walk(failing)) // positive control: the first chunk was read
        // an error token next to records that were written: the walk is incomplete (and does not go on with the token -1)
        val errorWithRecords = object : BinaryDictionary.WordSource {
            var calls = 0
            override fun nextChunk(token: Int, records: IntArray, used: IntArray): Int {
                calls++
                if (token != 0) { used[0] = 0; return 0 } // a walk that went on with the error token would end here: as a complete one
                records[0] = 5; records[1] = 1; records[2] = 'a'.code; used[0] = 3
                return -1
            }
        }
        assertEquals(false, complete(errorWithRecords))
        assertEquals(1, errorWithRecords.calls)
        val negativeAfterWords = object : BinaryDictionary.WordSource {
            override fun nextChunk(token: Int, records: IntArray, used: IntArray): Int { used[0] = 0; return -1 }
        }
        assertEquals(false, complete(negativeAfterWords))
    }

    @Test
    fun noProgressAndDamagedRecordsAreIncompleteNotShortLists() {
        val stuck = object : BinaryDictionary.WordSource {
            override fun nextChunk(token: Int, records: IntArray, used: IntArray): Int { used[0] = 0; return 5 }
        }
        assertEquals(false, complete(stuck))
        val damaged = object : BinaryDictionary.WordSource {
            override fun nextChunk(token: Int, records: IntArray, used: IntArray): Int {
                records[0] = 7; records[1] = 2; records[2] = 'a'.code; records[3] = 'b'.code
                records[4] = 5; records[5] = 10; records[6] = 'c'.code
                used[0] = 7
                return 0
            }
        }
        assertEquals(false, complete(damaged))
        val cut = object : BinaryDictionary.WordSource {
            override fun nextChunk(token: Int, records: IntArray, used: IntArray): Int {
                records[0] = 7; records[1] = 1; records[2] = 'a'.code; records[3] = 9
                used[0] = 4
                return 0
            }
        }
        assertEquals(false, complete(cut))
        for (length in listOf(0, -1, BinaryDictionary.DICTIONARY_MAX_WORD_LENGTH + 1)) {
            val bad = object : BinaryDictionary.WordSource {
                override fun nextChunk(token: Int, records: IntArray, used: IntArray): Int { records[0] = 1; records[1] = length; used[0] = 60; return 0 }
            }
            assertEquals(false, complete(bad), "length $length")
        }
    }
}
