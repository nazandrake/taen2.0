// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.Context
import android.view.MotionEvent

/** What to do with a touch that another window covers (design 7.4 item 12). */
enum class OverlayMode {
    OFF, WARN, BLOCK;

    val value: String get() = name.lowercase()

    companion object {
        const val DEFAULT_VALUE = "warn"

        /** An unknown stored value blocks (fail closed); a missing one is the default. */
        fun parse(value: String?): OverlayMode = when (value) {
            null -> WARN
            "off" -> OFF
            "warn" -> WARN
            "block" -> BLOCK
            else -> BLOCK
        }
    }
}

/**
 * P14: a window drawn over the keyboard can show a fake keyboard or watch taps, so touches that Android marks as obscured
 * (FLAG_WINDOW_IS_OBSCURED or FLAG_WINDOW_IS_PARTIALLY_OBSCURED) are warned about or dropped, as [OverlayMode] says.
 * Blocking works on whole gestures: a gesture that starts covered is dropped to its end, and one that becomes covered is cancelled once.
 */
object OverlayGuard {
    /** At most one warning per this time while touches stay covered. */
    const val WARN_INTERVAL_MILLIS = 10_000L

    private const val COVERED = MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED

    enum class Verdict {
        /** Handle the event as usual. */
        PASS,
        /** Handle the event as usual and show the warning. */
        WARN,
        /** Ignore the event. */
        DROP,
        /** Send the key tracker a cancel instead of the event: the gesture became covered after it had started. Later events are dropped. */
        CANCEL,
    }

    class Gate(private val clock: () -> Long) {
        private var passing = false // a gesture was passed on and has not ended
        private var dropping = false // the rest of the gesture is dropped
        private var lastWarning = Long.MIN_VALUE

        fun filter(action: Int, flags: Int, mode: OverlayMode): Verdict {
            val masked = action and MotionEvent.ACTION_MASK
            val covered = flags and COVERED != 0
            val ends = masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL
            if (mode == OverlayMode.BLOCK) {
                // a new uncovered gesture starts clean: the end of an earlier covered one may never have come (a window change takes it away)
                if (masked == MotionEvent.ACTION_DOWN && !covered) dropping = false
                if (covered || dropping) {
                    val wasPassing = passing
                    dropping = !ends
                    passing = false
                    if (masked == MotionEvent.ACTION_DOWN) dropping = true
                    return if (wasPassing && covered) Verdict.CANCEL else Verdict.DROP
                }
                if (masked == MotionEvent.ACTION_DOWN) passing = true
                if (ends) passing = false
                return Verdict.PASS
            }
            dropping = false
            passing = false
            if (mode == OverlayMode.OFF || !covered) return Verdict.PASS
            val now = clock()
            if (lastWarning != Long.MIN_VALUE && now - lastWarning < WARN_INTERVAL_MILLIS) return Verdict.PASS
            lastWarning = now
            return Verdict.WARN
        }
    }

    /**
     * The one touch entry of the keyboard window (InputView.dispatchTouchEvent): runs [event] through [gate] and hands it to
     * [deliver] (the normal dispatch) unless it is dropped. A cancelled gesture is delivered as ACTION_CANCEL. Returns what the
     * dispatch returns; a dropped touch counts as handled so it does not fall through to the app below.
     */
    @JvmStatic
    fun dispatch(context: Context, event: MotionEvent, gate: Gate, mode: OverlayMode, deliver: (MotionEvent) -> Boolean): Boolean =
        when (gate.filter(event.action, event.flags, mode)) {
            Verdict.PASS -> deliver(event)
            Verdict.WARN -> {
                PrivacyWarnings.onOverlay(context, blocked = false)
                deliver(event)
            }
            Verdict.DROP -> {
                PrivacyWarnings.onOverlay(context, blocked = true)
                true
            }
            Verdict.CANCEL -> {
                PrivacyWarnings.onOverlay(context, blocked = true)
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                try {
                    deliver(cancel)
                } finally {
                    cancel.recycle()
                }
                true
            }
        }
}
