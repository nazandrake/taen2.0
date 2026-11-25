// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.utils.InputTypeUtils

/**
 * P9: the only place that decides what the keyboard may remember in the current field. Learning, clipboard capture,
 * recent emoji, key previews, clipboard suggestions and the suggestion strip of password fields all follow from [decide];
 * no other code looks at the password variations, the no-personalized-learning flag or the per-app list (privacyCheck).
 */
object PrivacyPolicy {
    /** The most apps on the per-app incognito list (bounds the preference and the match). */
    const val MAX_APPS = 200

    class Input(
        val inputType: Int,
        val imeOptions: Int,
        val packageName: String?,
        val forceIncognito: Boolean,
        val incognitoApps: Set<String>,
        /** The device or the screen is locked (before the first unlock, or while the screen is off). */
        val locked: Boolean,
        /** The user allowed the clipboard keys in password fields (Settings.PREF_CLIPBOARD_IN_SECURE_FIELDS, off by default). */
        val secureClipboard: Boolean = false,
    )

    /**
     * @property passwordField one of the four password variations: the secure mode (no suggestion strip, key previews or clipboard suggestion)
     * @property secureClipboard the strip keys (paste, select all, clear all, the pinned keys) are allowed in a password field; nothing else changes:
     * the field stays incognito, a copy made in it is flagged sensitive, and it is never stored, suggested or learned
     * @property noPersonalizedLearning the field asks for no personalized learning (any input class)
     * @property incognito nothing is learned, no clipboard entry or recent emoji is kept: password field, no-learning flag,
     * force-incognito, an app on the list, or a locked device
     * @property locked the device or the screen is locked (the field is shown over the keyguard)
     */
    class Decision(
        val passwordField: Boolean,
        val noPersonalizedLearning: Boolean,
        val incognito: Boolean,
        val secureClipboard: Boolean = false,
        val locked: Boolean = false,
    ) {
        val learn: Boolean get() = !incognito
        val storeClipboard: Boolean get() = !incognito
        val recordRecents: Boolean get() = !incognito
        /** The learned words and the personal dictionary are no source of suggestions (typing, next word, glide) in an incognito field. */
        val personalizedSuggestions: Boolean get() = !incognito
        val keyPreviews: Boolean get() = !passwordField
        val clipboardSuggestion: Boolean get() = !passwordField
        /**
         * The Paste chip of the strip (PasteChip) may be offered: also in a password or incognito field, because the chip shows no text and the
         * clipboard is read only when it is tapped; never while the device or the screen is locked.
         */
        val pasteSuggestion: Boolean get() = !locked
        /** The strip shows its keys (the word suggestions of a password field are off anyway). */
        val suggestionStrip: Boolean get() = !passwordField || secureClipboard
        /** A copy made in this field is flagged EXTRA_IS_SENSITIVE: the system hides its preview and the keyboard never stores or shows it. */
        val sensitiveCopy: Boolean get() = passwordField
        /** The space bar may select, and the whole field text may be read for several cursors: not in a password or incognito field. */
        val fieldEditing: Boolean get() = !passwordField && !incognito
        /**
         * The Vim layer (Normal and Visual mode: it reads and edits the field text) is not offered in a password field or while the device is
         * locked (a field shown over the keyguard). Its register is filled only where [storeClipboard] holds.
         */
        val vimMode: Boolean get() = !passwordField && !locked
    }

    /** The field asks for no personalized learning. Read in every input class: a number or phone field can ask for it too. */
    fun noLearning(imeOptions: Int): Boolean = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

    fun decide(input: Input): Decision {
        val password = InputTypeUtils.isAnyPasswordInputType(input.inputType)
        val noLearning = noLearning(input.imeOptions)
        val listed = input.packageName != null && input.packageName in input.incognitoApps
        return Decision(password, noLearning, password || noLearning || input.forceIncognito || listed || input.locked, input.secureClipboard, input.locked)
    }

    fun decide(
        inputType: Int, imeOptions: Int, packageName: String?, forceIncognito: Boolean, incognitoApps: Set<String>, locked: Boolean,
        secureClipboard: Boolean = false,
    ): Decision = decide(Input(inputType, imeOptions, packageName, forceIncognito, incognitoApps, locked, secureClipboard))

    private val packageName = Regex("""[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+""")
    private val separators = Regex("""[\s,;]+""")

    /** The package names in [raw] (separated by white space, commas or semicolons), valid ones only, at most [MAX_APPS]. */
    fun parseApps(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val result = LinkedHashSet<String>()
        for (token in raw.split(separators)) {
            if (result.size >= MAX_APPS) break
            if (packageName.matches(token)) result.add(token)
        }
        return result
    }

    fun formatApps(apps: Set<String>): String = apps.sorted().joinToString("\n")
}
