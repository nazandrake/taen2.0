// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The glide engine on small word lists: the word whose key path the finger followed comes first. */
class GlideEngineTest {
    private val engine = GlideEngine()
    private val qwertyKeys = GlideTestKit.keys("qwerty")
    private val qwerty = GlideKeyboard(qwertyKeys)

    private fun lexicon(vararg words: String, probability: Int = 200) =
        GlideTestKit.lexicon(words.map { it to probability })

    private val small = lexicon("hello", "help", "jello", "hells", "yellow", "world", "word", "wood", "good", "food")

    private fun top(lexicon: GlideLexicon, g: GlideTestKit.Gesture, n: Int = 3, keyboard: GlideKeyboard = qwerty) =
        engine.suggest(lexicon, keyboard, g.xs, g.ys, g.count, n).map { it.word }

    @Test
    fun idealPathOfAWordGivesThatWordFirst() {
        for (word in listOf("hello", "world", "yellow", "good")) {
            assertEquals(word, top(small, GlideTestKit.idealGesture(word, qwertyKeys)).first(), word)
        }
    }

    // ----

    @Test
    fun noisyFingerStillFindsTheWord() {
        // hand-made list whose words are far apart on the keyboard: a sloppy finger (0.25 key) must not confuse them
        val lex = lexicon("hello", "world", "yellow", "good", "quiet", "zebra", "banana")
        for ((i, word) in listOf("hello", "world", "yellow", "quiet", "zebra", "banana").withIndex()) {
            val g = GlideTestKit.gesture(word, qwertyKeys, 0.25f, 100L + i)
            assertEquals(word, top(lex, g).first(), word)
        }
    }

    @Test
    fun firstAndLastKeyDecideTheDirection() {
        // same keys, opposite direction: "to" starts on t and ends on o, "ot" the other way round
        val lex = lexicon("to", "ot")
        assertEquals("to", top(lex, GlideTestKit.idealGesture("to", qwertyKeys)).first())
        assertEquals("ot", top(lex, GlideTestKit.idealGesture("ot", qwertyKeys)).first())
    }

    @Test
    fun theLetterOrderMatters() {
        val lex = lexicon("from", "form")
        assertEquals("from", top(lex, GlideTestKit.idealGesture("from", qwertyKeys)).first())
        assertEquals("form", top(lex, GlideTestKit.idealGesture("form", qwertyKeys)).first())
    }

    @Test
    fun theMoreProbableWordWinsAnEqualPath() {
        // "good" and "god": a double letter is one touch of its key, so both have the same plain path
        val lex = GlideTestKit.lexicon(listOf("good" to 250, "god" to 120))
        val g = GlideTestKit.idealGesture("god", qwertyKeys)
        assertEquals(listOf("good", "god"), top(lex, g, 2))
        val reversed = GlideTestKit.lexicon(listOf("good" to 120, "god" to 250))
        assertEquals(listOf("god", "good"), top(reversed, g, 2))
    }

    @Test
    fun aLetterWithoutAKeyIsSkippedAndKeepsItsCase() {
        // the apostrophe has no key, "Café" is typed on the c-a-f-e keys and comes back as the dictionary has it
        val lex = lexicon("don't", "dot", "Café", "cafe's")
        assertEquals("don't", top(lex, GlideTestKit.idealGesture("dont", qwertyKeys)).first())
        assertEquals("Café", top(lex, GlideTestKit.idealGesture("cafe", qwertyKeys)).first())
    }

    @Test
    fun capitalAndSmallFormOfAWordAreOneSuggestion() {
        val lex = GlideTestKit.lexicon(listOf("the" to 220, "The" to 100, "tie" to 100))
        val result = top(lex, GlideTestKit.idealGesture("the", qwertyKeys), 5)
        assertEquals(1, result.count { it.lowercase() == "the" }, result.toString())
        assertEquals("the", result.first())
    }

    @Test
    fun aTapOrAShortWiggleIsNoGlide() {
        val lex = small
        assertTrue(engine.suggest(lex, qwerty, floatArrayOf(), floatArrayOf(), 0, 3).isEmpty())
        assertTrue(engine.suggest(lex, qwerty, floatArrayOf(500f), floatArrayOf(70f), 1, 3).isEmpty())
        assertTrue(engine.suggest(lex, qwerty, floatArrayOf(500f, 900f), floatArrayOf(70f, 70f), 2, 0).isEmpty())
        // a 20 px wiggle on a 108 px key over the key where "as" starts: positive control first, the real glide finds the word
        val short = lexicon("as")
        assertEquals(listOf("as"), top(short, GlideTestKit.idealGesture("as", qwertyKeys)))
        val a = qwertyKeys.first { it.code == 'a'.code }
        assertTrue(engine.suggest(short, qwerty, floatArrayOf(a.x, a.x + 10f, a.x + 20f), floatArrayOf(a.y, a.y, a.y + 2f), 3, 3).isEmpty())
    }

    @Test
    fun thinningKeepsTheFirstPointFarPointsAndAlwaysTheLast() {
        val xs = floatArrayOf(0f, 5f, 10f, 40f, 45f, 70f, 72f)
        val ys = FloatArray(xs.size)
        val t = thin(xs, ys, xs.size, 27f, 500)
        // 5 and 10 are within 27 of 0; 40 is 40 away: kept; 45 is within 27 of 40; 70 is 30 away: kept; 72 is the last: kept
        assertEquals(listOf(0f, 40f, 70f, 72f), t.x.take(t.n))
        // the same path without the final point: 70 is the last and kept as such
        assertEquals(listOf(0f, 40f, 70f), thin(xs, ys, 6, 27f, 500).let { it.x.take(it.n) })
        // a last point equal to the one before adds nothing
        assertEquals(listOf(0f, 40f), thin(floatArrayOf(0f, 40f, 40f), FloatArray(3), 3, 27f, 500).let { it.x.take(it.n) })
        // the limit never drops the last point
        val many = FloatArray(100) { it * 30f }
        val limited = thin(many, FloatArray(100), 100, 27f, 10)
        assertEquals(10, limited.n)
        assertEquals(many.last(), limited.x[9])
        assertEquals(0f, limited.x[0])
    }

    @Test
    fun aLoopOnADoubleLetterPicksTheDoubleLetterWord() {
        // "pol" and "pool" have the same plain path; a finger that loops on the o key means "pool"
        val lex = lexicon("pol", "pool")
        val g = GlideTestKit.loopedGesture("pool", qwertyKeys)
        val looped = engine.suggest(lex, qwerty, g.xs, g.ys, g.count, 2)
        assertEquals(listOf("pool", "pol"), looped.map { it.word })
        assertTrue(looped[1].cost - looped[0].cost > 0.05f, "${looped[0].cost} ${looped[1].cost}")
    }

    @Test
    fun aLastTouchPointCloseToThePointBeforeIsHarmless() {
        // the distance filter thins touch points; a final point inside the filter distance is still taken
        val g = GlideTestKit.idealGesture("hello", qwertyKeys)
        val xs = g.xs + floatArrayOf(g.xs.last() + 3f)
        val ys = g.ys + floatArrayOf(g.ys.last() + 2f)
        assertEquals("hello", engine.suggest(small, qwerty, xs, ys, xs.size, 1).first().word)
    }

    @Test
    fun resultsAreSortedAndBounded() {
        val g = GlideTestKit.idealGesture("word", qwertyKeys)
        val r = engine.suggest(small, qwerty, g.xs, g.ys, g.count, 2)
        assertTrue(r.size <= 2)
        assertEquals("word", r[0].word)
        assertTrue(r.zipWithNext().all { (a, b) -> a.cost <= b.cost })
    }

    @Test
    fun aWordOnOneKeyOrWithoutKeysIsNeverSuggested() {
        val lex = lexicon("aaa", "ñññ", "to")
        val g = GlideTestKit.idealGesture("to", qwertyKeys)
        assertEquals(listOf("to"), top(lex, g, 5))
    }

    @Test
    fun theWordIndexFollowsTheLetters() {
        // the same lexicon on a Bulgarian layout: nothing Latin is found, Cyrillic words are
        val bg = GlideTestKit.keys("bulgarian")
        val lex = lexicon("hello", "вода", "вили", "светло")
        val g = GlideTestKit.idealGesture("вода", bg)
        assertEquals("вода", top(lex, g, 3, GlideKeyboard(bg)).first())
        // and back on qwerty the Cyrillic words have no keys, so they are out of the index
        assertEquals("hello", top(lex, GlideTestKit.idealGesture("hello", qwertyKeys)).first())
    }

    @Test
    fun theBulgarianShortIAndItsBaseLetterShareAKey() {
        // й is its own key on the Bulgarian layouts, ѝ is typed on the и key
        val bg = GlideTestKit.keys("bulgarian")
        val lex = lexicon("мой", "ѝ", "мои", "ти")
        assertEquals("мой", top(lex, GlideTestKit.idealGesture("мой", bg), 3, GlideKeyboard(bg)).first())
        assertEquals("мои", top(lex, GlideTestKit.idealGesture("мои", bg), 3, GlideKeyboard(bg)).first())
    }

    @Test
    fun uppercaseKeyCodesAreTheSameKeys() {
        val upper = GlideKeyboard(qwertyKeys.map { GlideKey(Character.toUpperCase(it.code), it.x, it.y, it.width, it.height) })
        val g = GlideTestKit.idealGesture("hello", qwertyKeys)
        assertEquals("hello", top(small, g, 1, upper).first())
    }

    @Test
    fun lexiconKeepsEveryWordAndProbabilityAcrossGrowth() {
        val b = GlideLexicon.Builder()
        for (i in 0 until 20000) b.add("w$i", i % 256)
        b.add("x", 999) // out of range: capped
        b.add("y", -5)
        val l = b.build()
        assertEquals(20002, l.size)
        assertEquals("w19999", l.word(19999))
        assertEquals(19999 % 256, l.probability(19999))
        assertEquals(255, l.probability(20000))
        assertEquals(0, l.probability(20001))
        assertEquals('y', l.charAt(20001, 0))
        assertEquals(1, l.length(20001))
    }

    @Test
    fun theEngineKeepsNothingOfAGesture() {
        // two different gestures in a row: the second answer does not depend on the first
        val a = GlideTestKit.idealGesture("hello", qwertyKeys)
        val b = GlideTestKit.idealGesture("world", qwertyKeys)
        val before = top(small, b)
        top(small, a)
        assertEquals(before, top(small, b))
        assertFalse(before.isEmpty())
    }
}
