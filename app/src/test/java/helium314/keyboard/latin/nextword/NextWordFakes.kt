// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword

import helium314.keyboard.latin.nextword.engine.BpeTokenizer
import helium314.keyboard.latin.nextword.engine.NextTokenModel
import helium314.keyboard.latin.nextword.engine.NextWordRanker
import helium314.keyboard.latin.nextword.engine.PreSplit
import java.util.concurrent.Executor

/** A tokenizer of six one-token words ("Ġ" is the space of byte-level BPE): " cat"=1, " dog"=2, " fox"=3, " owl"=4, " zzz"=5, plus "the"=0. */
internal fun tinyTokenizer(): BpeTokenizer {
    val vocab = """{"the":0,"Ġcat":1,"Ġdog":2,"Ġfox":3,"Ġowl":4,"Ġzzz":5,"Ġ":6,"c":7,"a":8,"t":9,"d":10,"o":11,"g":12,"f":13,"x":14,"w":15,"l":16,"z":17,"h":18,"e":19,"Ġc":20,"Ġca":21,"Ġd":22,"Ġdo":23,"Ġf":24,"Ġfo":25,"Ġo":26,"Ġow":27,"Ġz":28,"Ġzz":29,"th":30}"""
    val merges = "#version: 0.2\nĠ c\nĠc a\nĠca t\nĠ d\nĠd o\nĠdo g\nĠ f\nĠf o\nĠfo x\nĠ o\nĠo w\nĠow l\nĠ z\nĠz z\nĠzz z\nt h\nth e\n"
    return BpeTokenizer.read(vocab, merges, PreSplit.SMOLLM2)
}

/** A model whose next-token log-probabilities are [scores] (missing ids get -100); it counts its calls and what it was told to forget. */
internal class FakeModel(private val scores: Map<Int, Float>, private val size: Int = 32) : NextTokenModel {
    var calls = 0
    var forgets = 0
    var closed = false
    var lastContext: IntArray? = null
    var fail = false

    override fun logProbs(context: IntArray): FloatArray {
        calls++
        if (fail) throw IllegalStateException("model failure")
        lastContext = context.copyOf()
        return FloatArray(size) { scores[it] ?: -100f }
    }

    override fun forget() { forgets++ }
    override fun close() { closed = true }
}

internal fun fakeRanker(model: NextTokenModel) = NextWordRanker(tinyTokenizer(), model)

/** An executor that runs a task only when [runAll] is called, so a test decides what happens in between. */
internal class QueueExecutor : Executor {
    private val tasks = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) { tasks.addLast(command) }
    fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    val pending: Int get() = tasks.size
}
