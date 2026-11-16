// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword

import helium314.keyboard.latin.nextword.engine.NextWordRanker

/**
 * One model's engine, loaded at the first use and freed again ([unload]: two minutes idle, the screen locks, the service ends). Before the first load in a
 * process the model file is checked against its pinned SHA-256 ([verify]); a file that does not match is [State.DAMAGED] and is never loaded in this
 * process. A load that throws is [State.FAILED] (not tried again until the next process). Not thread safe: the keyboard uses it from its one model thread.
 */
class NextWordEngineHolder(private val verify: () -> Boolean, private val load: () -> NextWordRanker) {
    enum class State { UNLOADED, LOADED, DAMAGED, FAILED }

    var state = State.UNLOADED
        private set
    private var ranker: NextWordRanker? = null
    private var verified = false

    /** The loaded engine, loading it first when needed; null when the model is damaged or cannot be loaded. */
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
        return try {
            load().also {
                ranker = it
                state = State.LOADED
            }
        } catch (_: Throwable) {
            state = State.FAILED
            null
        }
    }

    /** Drops the kept state of the last text (a field change), the model stays loaded. */
    fun forget() { ranker?.forget() }

    /** Frees the engine; the next [get] loads it again (without a new hash check). */
    fun unload() {
        ranker?.let { runCatching { it.close() } }
        ranker = null
        if (state == State.LOADED) state = State.UNLOADED
    }
}
