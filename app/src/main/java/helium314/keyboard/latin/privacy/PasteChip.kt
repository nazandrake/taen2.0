// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

/**
 * P10: the Paste chip, offered first on the suggestion strip after a fresh copy. The only place where it is decided whether the chip is offered and
 * for how long. The chip shows no text (a label and an icon only) and nothing of the clip is read, stored or logged: the clipboard is read when
 * the chip is tapped, by the same paste as the paste key. So the clip's sensitive flag and the field kind (password, incognito) do not matter here;
 * a locked device does (PrivacyPolicy.Decision.pasteSuggestion).
 *
 * The main use is to copy in one app and paste in a field of another, so a field change does not end the chip. It is offered in every field
 * that starts from a copy until it is used, [LIFETIME_MILLIS] have passed, the clipboard no longer holds text (an image, cleared), or the screen
 * locks. A new copy starts the time again. Time is injected.
 */
class PasteChip(private val clock: () -> Long) {
    private var copiedAt = 0L
    private var armed = false

    private var lastClipTimestamp = 0L

    /**
     * A new clip replaces the previous one. [decision] is the one of the window the copy was made in. [clipTimestamp] is the time stamp the system
     * gave the clip (0 when it has none): the system may announce the clip of one copy again (measured on the reference phone: exactly 60 s after the
     * copy, same time stamp), and that is no new copy, so it neither brings the chip back nor starts the minute again.
     */
    fun onCopy(enabled: Boolean, decision: PrivacyPolicy.Decision, hasText: Boolean, clipTimestamp: Long) {
        if (clipTimestamp > 0 && clipTimestamp == lastClipTimestamp) return
        lastClipTimestamp = clipTimestamp
        armed = allowed(enabled, decision, hasText)
        copiedAt = clock()
    }

    /** True when the chip is to be shown in the current field, whose [decision] and the setting [enabled] are asked for each time. */
    fun visible(enabled: Boolean, decision: PrivacyPolicy.Decision): Boolean {
        if (!armed) return false
        if (clock() - copiedAt >= LIFETIME_MILLIS) {
            clear()
            return false
        }
        return allowed(enabled, decision, true)
    }

    /** The chip was tapped. */
    fun onUsed() = clear()

    /** Time is up, the screen locked, the clipboard was cleared. */
    fun clear() {
        armed = false
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
