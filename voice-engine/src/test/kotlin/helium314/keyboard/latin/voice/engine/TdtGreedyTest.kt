// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The greedy TDT search against a scripted model. Expected token lists are worked out by hand from the rules in [TdtGreedy]. */
class TdtGreedyTest {
    // vocabulary 0..2, blank 3, durations 0, 1, 2 -> joiner output of 4 + 3 values
    private val blank = 3
    private val durations = intArrayOf(0, 1, 2)

    private class Step(val token: Int, val durationIndex: Int)

    private class ScriptedModel(private val frames: Int, private val script: (call: Int, t: Int) -> Step) : TransducerModel {
        var joinCalls = 0
        val decoderInputs = ArrayList<Int>()
        val frameOfCall = ArrayList<Int>()
        private class S(val n: Int) : DecoderState

        override fun encode(features: FloatArray, frames: Int) = Encoded(FloatArray(2 * this.frames), 2, this.frames)
        override fun initialDecoderState(): DecoderState = S(0)
        override fun decoderStep(token: Int, state: DecoderState): DecoderOutput {
            decoderInputs.add(token)
            return DecoderOutput(floatArrayOf((state as S).n + 1f), S(state.n + 1))
        }

        override fun joint(encoded: Encoded, t: Int, decoder: FloatArray): FloatArray {
            val step = script(joinCalls++, t)
            frameOfCall.add(t)
            val logits = FloatArray(7)
            logits[step.token] = 1f
            logits[4 + step.durationIndex] = 1f
            return logits
        }
    }

    private fun run(model: ScriptedModel, max: Int = 10) = TdtGreedy(blank, durations, max).decode(model, FloatArray(0), 0)

    @Test fun emitsTokensAndSkipsByTheChosenDuration() {
        // t=0 token 1 skip 1 | t=1 blank, duration 0 -> forced to 1 | t=2 token 2 skip 2 | t=4 token 0 skip 0 (stays) | t=4 blank skip 2 -> t=6
        val steps = listOf(Step(1, 1), Step(blank, 0), Step(2, 2), Step(0, 0), Step(blank, 2))
        val model = ScriptedModel(6) { call, _ -> steps[call] }
        assertContentEquals(intArrayOf(1, 2, 0), run(model))
        assertEquals(listOf(0, 1, 2, 4, 4), model.frameOfCall)
        assertEquals(5, model.joinCalls)
        // the prediction network sees the blank first, then every emitted token in order
        assertEquals(listOf(blank, 1, 2, 0), model.decoderInputs)
    }

    @Test fun aFrameStopsAfterTheMaximumNumberOfSymbols() {
        val model = ScriptedModel(2) { _, _ -> Step(0, 0) } // always token 0 with duration 0
        assertEquals(6, run(model, max = 3).size) // 3 per frame, 2 frames
        assertEquals(listOf(0, 0, 0, 1, 1, 1), model.frameOfCall)
        val model10 = ScriptedModel(2) { _, _ -> Step(0, 0) }
        assertEquals(20, run(model10).size) // the default limit is 10
    }

    @Test fun blankUsesItsDurationWhenPositive() {
        val model = ScriptedModel(6) { _, _ -> Step(blank, 2) } // blank, skip 2 every time: frames 0, 2, 4
        assertEquals(0, run(model).size)
        assertEquals(listOf(0, 2, 4), model.frameOfCall)
    }

    @Test fun aSkipPastTheEndEnds() {
        val model = ScriptedModel(3) { call, _ -> if (call == 0) Step(2, 2) else Step(1, 2) } // t=0 emit skip 2, t=2 emit skip 2 -> 4 >= 3
        assertContentEquals(intArrayOf(2, 1), run(model))
        assertEquals(2, model.joinCalls)
    }

    @Test fun noFramesMeansNoTokensAndNoJoinerCall() {
        val model = ScriptedModel(0) { _, _ -> error("not called") }
        assertContentEquals(intArrayOf(), run(model))
        assertEquals(0, model.joinCalls)
        assertEquals(listOf(blank), model.decoderInputs) // only the start step
    }

    @Test fun ties_goToTheLowestIndex() {
        val flat = object : TransducerModel {
            override fun encode(features: FloatArray, frames: Int) = Encoded(FloatArray(2), 2, 1)
            override fun initialDecoderState() = object : DecoderState {}
            override fun decoderStep(token: Int, state: DecoderState) = DecoderOutput(FloatArray(1), state)
            override fun joint(encoded: Encoded, t: Int, decoder: FloatArray) = FloatArray(7) // all equal: token 0, duration index 0
        }
        // token 0 is emitted with duration 0 until the symbol limit (2), then the frame advances
        assertContentEquals(intArrayOf(0, 0), TdtGreedy(blank, durations, 2).decode(flat, FloatArray(0), 0))
    }

    @Test fun wrongJoinerWidthIsRejected() {
        val model = object : TransducerModel {
            override fun encode(features: FloatArray, frames: Int) = Encoded(FloatArray(2), 2, 1)
            override fun initialDecoderState() = object : DecoderState {}
            override fun decoderStep(token: Int, state: DecoderState) = DecoderOutput(FloatArray(1), state)
            override fun joint(encoded: Encoded, t: Int, decoder: FloatArray) = FloatArray(6)
        }
        assertFailsWith<IllegalArgumentException> { run0(model) }
    }

    private fun run0(model: TransducerModel) = TdtGreedy(blank, durations).decode(model, FloatArray(0), 0)

    @Test fun invalidConfigurationIsRejected() {
        assertFailsWith<IllegalArgumentException> { TdtGreedy(blank, intArrayOf()) }
        assertFailsWith<IllegalArgumentException> { TdtGreedy(blank, intArrayOf(0, -1)) }
        assertFailsWith<IllegalArgumentException> { TdtGreedy(blank, durations, 0) }
    }

    @Test fun joinerScoresAndEncoderOutputAreClearedAfterTheSearchAlsoWhenTheOutputIsWrong() {
        val seen = ArrayList<FloatArray>()
        var encodedSeen: Encoded? = null
        val ok = object : TransducerModel {
            override fun encode(features: FloatArray, frames: Int) = Encoded(FloatArray(4) { 2f }, 2, 2).also { encodedSeen = it }
            override fun initialDecoderState() = object : DecoderState {}
            override fun decoderStep(token: Int, state: DecoderState) = DecoderOutput(FloatArray(1), state)
            override fun joint(encoded: Encoded, t: Int, decoder: FloatArray) = FloatArray(7).also { it[blank] = 1f; it[5] = 1f; seen.add(it) }
        }
        run0(ok)
        assertEquals(2, seen.size) // positive control: two frames, one joiner call each (skip 1)
        assertTrue(seen.all { l -> l.all { it == 0f } })
        assertTrue(encodedSeen!!.data.all { it == 0f })
        // a joiner that returns the wrong size: rejected, and its scores and the encoder output are cleared anyway
        seen.clear()
        val bad = object : TransducerModel {
            override fun encode(features: FloatArray, frames: Int) = Encoded(FloatArray(4) { 2f }, 2, 2).also { encodedSeen = it }
            override fun initialDecoderState() = object : DecoderState {}
            override fun decoderStep(token: Int, state: DecoderState) = DecoderOutput(FloatArray(1), state)
            override fun joint(encoded: Encoded, t: Int, decoder: FloatArray) = FloatArray(3) { 1f }.also { seen.add(it) }
        }
        assertFailsWith<IllegalArgumentException> { run0(bad) }
        assertEquals(1, seen.size)
        assertTrue(seen[0].all { it == 0f })
        assertTrue(encodedSeen!!.data.all { it == 0f })
    }

    /** A model whose decoder vectors and states are recorded, to see that every one of them is zero once the search is over (P20). */
    private class TrackingModel(private val frames: Int, private val joinerSize: Int = 7) : TransducerModel {
        class S(val h: FloatArray) : DecoderState {
            override fun clear() = h.fill(0f)
        }

        val vectors = ArrayList<FloatArray>()
        val states = ArrayList<S>()
        override fun encode(features: FloatArray, frames: Int) = Encoded(FloatArray(2 * this.frames) { 2f }, 2, this.frames)
        override fun initialDecoderState(): DecoderState = S(FloatArray(3) { 5f }).also { states.add(it) }
        override fun decoderStep(token: Int, state: DecoderState): DecoderOutput {
            val vector = FloatArray(2) { 7f }
            val next = S(FloatArray(3) { 9f })
            vectors.add(vector)
            states.add(next)
            return DecoderOutput(vector, next)
        }

        // token 1 with duration 1 at every frame: one emitted token per frame, so every frame makes a new decoder step
        override fun joint(encoded: Encoded, t: Int, decoder: FloatArray) = FloatArray(joinerSize).also { if (joinerSize == 7) { it[1] = 1f; it[5] = 1f } }
    }

    @Test fun everyDecoderVectorAndStateIsZeroAfterTheSearch() {
        val model = TrackingModel(3)
        assertContentEquals(intArrayOf(1, 1, 1), run0(model))
        assertEquals(4, model.vectors.size) // positive control: the start step and three emitted tokens
        assertEquals(5, model.states.size) // the initial state and four step states
        assertTrue(model.vectors.all { v -> v.all { it == 0f } }, "decoder vectors")
        assertTrue(model.states.all { s -> s.h.all { it == 0f } }, "decoder states")
    }

    @Test fun everyDecoderVectorAndStateIsZeroAlsoWhenTheJoinerOutputIsWrong() {
        val model = TrackingModel(3, joinerSize = 3)
        assertFailsWith<IllegalArgumentException> { run0(model) }
        assertEquals(1, model.vectors.size) // positive control: the start step ran
        assertTrue(model.vectors.all { v -> v.all { it == 0f } })
        assertTrue(model.states.all { s -> s.h.all { it == 0f } })
    }

    @Test fun aModelThatHandsTheSameStateBackKeepsItUntilTheEnd() {
        // the clearing of the previous step must not zero a state that the next step still carries (identity state)
        var calls = 0
        val shared = TrackingModel.S(FloatArray(2) { 4f })
        val model = object : TransducerModel {
            override fun encode(features: FloatArray, frames: Int) = Encoded(FloatArray(4), 2, 2)
            override fun initialDecoderState(): DecoderState = shared
            override fun decoderStep(token: Int, state: DecoderState): DecoderOutput {
                calls++
                assertTrue((state as TrackingModel.S).h.all { it == 4f }, "state still intact at step $calls")
                return DecoderOutput(FloatArray(1), state)
            }
            override fun joint(encoded: Encoded, t: Int, decoder: FloatArray) = FloatArray(7).also { it[1] = 1f; it[5] = 1f }
        }
        assertContentEquals(intArrayOf(1, 1), run0(model))
        assertEquals(3, calls)
        assertTrue(shared.h.all { it == 0f })
    }
}
