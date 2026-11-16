// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The ranking with the real SmolLM2 tokenizer and a model whose answer the test sets: order, the dictionary-only rule, ties, and cleanup. */
class NextWordRankerTest {
    private val tok = File(System.getProperty("taen.assets"), "nextword/smollm2-135m").let { BpeTokenizer.read(File(it, "vocab.json").readText(), File(it, "merges.txt").readText(), PreSplit.SMOLLM2) }
    private fun id(word: String) = tok.encode(" $word").single()

    private class Model(val scores: Map<Int, Float>) : NextTokenModel {
        var contexts = ArrayList<IntArray>()
        var returned: FloatArray? = null
        override fun logProbs(context: IntArray): FloatArray {
            contexts.add(context.copyOf())
            return FloatArray(49152) { scores[it] ?: -50f }.also { returned = it }
        }
        override fun forget() {}
        override fun close() {}
    }

    @Test fun candidatesAreOrderedByTheModelAndItsOwnWordsMustBeDictionaryWords() {
        val model = Model(mapOf(id("dinner") to -0.2f, id("lunch") to -0.9f, id("the") to -1.5f, id("cat") to -0.1f, id("you") to -3f))
        val words = setOf("dinner", "lunch", "the", "you")
        val ranked = NextWordRanker(tok, model).rank("What do you want for ", listOf("the", "you", "lunch"), { it in words })
        assertEquals(listOf("dinner", "lunch", "the", "you"), ranked.map { it.word })
        assertEquals(listOf(true, false, false, false), ranked.map { it.fromModel })
        assertTrue("cat is the model's best token but no word of these dictionaries", ranked.none { it.word == "cat" })
        assertArrayEquals("the model reads the text without the trailing space", tok.encode("What do you want for"), model.contexts.single())
        assertTrue("the answer is zeroed after use", model.returned!!.all { it == 0f })
    }

    @Test fun aWordTheModelGivesNoWeightKeepsItsDictionaryPlaceAmongEqualScores() {
        val model = Model(emptyMap()) // every token -50: all candidates tie
        val ranked = NextWordRanker(tok, model).rank("See you", listOf("soon", "later", "then"), { false })
        assertEquals("ties keep the dictionary's order", listOf("soon", "later", "then"), ranked.map { it.word })
    }

    @Test fun anEmptyContextLeavesTheDictionaryListAlone() {
        val model = Model(mapOf(id("dinner") to -0.1f))
        val ranked = NextWordRanker(tok, model).rank("   ", listOf("I", "The", "I"), { true })
        assertEquals(listOf("I", "The"), ranked.map { it.word })
        assertEquals("the model is not asked", 0, model.contexts.size)
    }

    @Test fun onlyWholeLetterTokensWithALeadingSpaceBecomeWords() {
        // ",", "ing" (no space) and " 2" are likely next tokens but not words
        val scores = mapOf(tok.encode(",").single() to -0.1f, tok.encode("ing").single() to -0.2f, tok.encode(" 2").last() to -0.3f, id("today") to -1f)
        val ranked = NextWordRanker(tok, Model(scores)).rank("It is raining", emptyList(), { true })
        assertEquals(listOf("today"), ranked.map { it.word }.filter { it == "today" || it == "," || it == "ing" || it == "2" })
        assertEquals("today", ranked.first().word)
    }
}
