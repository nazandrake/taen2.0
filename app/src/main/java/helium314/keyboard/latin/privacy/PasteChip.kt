// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

/**
 * What tells two fields apart for the Paste chip. The system starts input in one field more than once (measured: two starts within 250 ms when a
 * window opens), so "another start" is not "another field".
 */
data class FieldId(val packageName: String?, val fieldId: Int, val inputType: Int, val imeOptions: Int)

/**
 * P10: the Paste chip, offered first on the suggestion strip after a fresh copy. The only place where it is decided whether the chip is offered and
 * for how long. The chip shows no text (a label and an icon only) and nothing of the clip is read, stored or logged: the clipboard is read when
 * the chip is tapped, by the same paste as the paste key. So the clip's sensitive flag and the field kind (password, incognito) do not matter here;
 * a locked device does (PrivacyPolicy.Decision.pasteSuggestion).
 *
 * The chip is offered from a copy until it is used, [LIFETIME_MILLIS] have passed, or another field starts after the chip was shown in one
 * (a copy made while no field was shown is offered in the first field that opens). Time is injected.
 */
class PasteChip(private val clock: () -> Long) {
    private var copiedAt = 0L
    private var armed = false
    private var shownIn: FieldId? = null

    /** A new clip replaces the previous one. [decision] is the one of the window the copy was made in. */
    fun onCopy(enabled: Boolean, decision: PrivacyPolicy.Decision, hasText: Boolean) {
        shownIn = null
        armed = allowed(enabled, decision, hasText)
        copiedAt = clock()
    }

    /**
     * True when the chip is to be shown in the current field. Asking does not count as showing: the strip is also laid out while the keyboard is
     * hidden, so the caller says with [markShown] when the strip of a field that has the keyboard is on screen.
     */
    fun visible(enabled: Boolean, decision: PrivacyPolicy.Decision): Boolean {
        if (!armed) return false
        if (clock() - copiedAt >= LIFETIME_MILLIS) {
            clear()
            return false
        }
        return allowed(enabled, decision, true)
    }

    /** The chip is on the strip of [field], which has the keyboard (call after [visible] said true). */
    fun markShown(field: FieldId) {
        if (armed) shownIn = field
    }

    /** Input starts in [field]: a chip that was shown in another field stays behind. The same field starting again keeps it. */
    fun onFieldStart(field: FieldId) {
        val was = shownIn ?: return
        if (was != field) clear()
    }

    /** The chip was tapped. */
    fun onUsed() = clear()

    /** Time is up, the screen locked, the clipboard was cleared. */
    fun clear() {
        armed = false
        shownIn = null
    }

    /** Milliseconds left of the chip's life (0 when it is not offered). */
    fun remainingMillis(): Long = if (!armed) 0L else (LIFETIME_MILLIS - (clock() - copiedAt)).coerceAtLeast(0L)

    companion object {
        const val LIFETIME_MILLIS = 60_000L

        /** The one rule: the setting is on, the clip is text, and the device is not locked. */
        fun allowed(enabled: Boolean, decision: PrivacyPolicy.Decision, hasText: Boolean): Boolean =
            enabled && hasText && decision.pasteSuggestion
    }
}
