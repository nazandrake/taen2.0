// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.latin.voice.VoiceModelStore
import java.io.Closeable

/**
 * The cancel button, the progress line and the stall watch of one voice model install (P20, 1.0.2). The install runs on a background thread; the screen
 * keeps this object, shows [progress] and calls [cancel]. A document of another app can block a read for ever, so [cancel] also closes the stream that is
 * being read (the blocked read then ends with an IOException), and [stalledFor] tells how long no byte arrived (the screen stops the install after [STALL_MILLIS]).
 */
class InstallControl(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    /** What the screen shows: [label] (what is being done), bytes [done] of [total] (0 when unknown) in the current step. */
    class Progress(val label: String, val done: Long, val total: Long)

    @Volatile var cancelled = false
        private set
    /** True when the stop came from the stall watch and not from the user. */
    @Volatile var stalled = false
        private set
    @Volatile var progress: Progress = Progress("", 0, 0)
        private set
    @Volatile private var lastByteAt = now()
    @Volatile private var open: Closeable? = null

    /** The hooks of [VoiceModelStore] for this install: stop when cancelled, count the bytes. */
    val hooks = VoiceModelStore.Hooks(cancelled = { cancelled }, onBytes = { n -> lastByteAt = now(); progress = Progress(progress.label, progress.done + n, progress.total) })

    /** Starts a step: [label], and [total] bytes (0 when unknown). */
    fun step(label: String, total: Long) {
        lastByteAt = now()
        progress = Progress(label, 0, total)
    }

    /** The stream being read now; [cancel] closes it. Null when none. */
    fun reading(stream: Closeable?) { open = stream; if (cancelled) closeOpen() }

    fun cancel() {
        cancelled = true
        closeOpen()
    }

    /** Stops the install because no byte arrived for too long. */
    fun stall() {
        stalled = true
        cancel()
    }

    /** Milliseconds since the last byte (or since the step started). */
    fun stalledFor(): Long = now() - lastByteAt

    private fun closeOpen() {
        val s = open ?: return
        open = null
        try { s.close() } catch (_: Exception) { /* the stream is being given up */ }
    }

    companion object {
        /** No byte for this long: the document provider is stuck. */
        const val STALL_MILLIS = 60_000L
    }
}
