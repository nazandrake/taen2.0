// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword

import helium314.keyboard.latin.nextword.engine.NextWordRanker

/**
 * One model's engine, loaded at the first use and freed again ([unload]: two minutes idle, the screen locks, the service ends). Before the first load in a
 * process the model file is checked against its pinned SHA-256 ([verify]); a file that does not match is [State.DAMAGED] and is never loaded in this
 * process. Before every load [mayLoad] asks whether the phone has the memory for it now (NextWordGate.mayLoadNow); when not, nothing is loaded
 * ([State.NO_MEMORY]) and the next request asks again. [beginLoad] sets a marker before the load and [endLoad] clears it when the load returns, so a
 * process that the system ends during the load leaves the marker for the next one (ModelFiles.settleCrashedLoads); a marker that cannot be set means no
 * load. A load that throws is [State.FAILED] (not tried again until the next process). Not thread safe: the keyboard uses it from its one model thread.
 */
class NextWordEngineHolder(
    private val verify: () -> Boolean,
    private val mayLoad: () -> Boolean = { true },
    private val beginLoad: () -> Boolean = { true },
    private val endLoad: () -> Unit = {},
    private val load: () -> NextWordRanker,
) {
    enum class State { UNLOADED, NO_MEMORY, LOADED, DAMAGED, FAILED }

    var state = State.UNLOADED
        private set
    private var ranker: NextWordRanker? = null
    private var verified = false

    /** The loaded engine, loading it first when needed; null when the model is damaged, cannot be loaded or there is not enough memory now. */
    fun get(): NextWordRanker? {
        ranker?.let { return it }
        if (state == State.DAMAGED || state == State.FAILED) return null
        if (!verified) {
            if (!runCatching(verify).getOrDefault(false)) {
                state = State.DAMAGED
                return null
            }
            verified = true
        }
        if (!runCatching(mayLoad).getOrDefault(false)) {
            state = State.NO_MEMORY
            return null
        }
        if (!runCatching(beginLoad).getOrDefault(false)) {
            state = State.FAILED
            return null
        }
        return try {
            load().also {
                ranker = it
                state = State.LOADED
            }
        } catch (_: Throwable) {
            state = State.FAILED
            null
        } finally {
            runCatching(endLoad)
        }
    }

    /** Drops the kept state of the last text (a field change), the model stays loaded. */
    fun forget() { ranker?.forget() }

    /** Frees the engine; the next [get] loads it again (without a new hash check, with a new memory check). */
    fun unload() {
        ranker?.let { runCatching { it.close() } }
        ranker = null
        if (state == State.LOADED) state = State.UNLOADED
    }
}
