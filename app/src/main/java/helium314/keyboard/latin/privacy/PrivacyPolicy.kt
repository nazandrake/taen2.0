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
    )

    /**
     * @property passwordField one of the four password variations: the secure mode (no suggestion strip, key previews or clipboard suggestion)
     * @property noPersonalizedLearning the field asks for no personalized learning (any input class)
     * @property incognito nothing is learned, no clipboard entry or recent emoji is kept: password field, no-learning flag,
     * force-incognito, an app on the list, or a locked device
     */
    class Decision(val passwordField: Boolean, val noPersonalizedLearning: Boolean, val incognito: Boolean) {
        val learn: Boolean get() = !incognito
        val storeClipboard: Boolean get() = !incognito
        val recordRecents: Boolean get() = !incognito
        /** The learned words and the personal dictionary are no source of suggestions (typing, next word, glide) in an incognito field. */
        val personalizedSuggestions: Boolean get() = !incognito
        val keyPreviews: Boolean get() = !passwordField
        val clipboardSuggestion: Boolean get() = !passwordField
        val suggestionStrip: Boolean get() = !passwordField
    }

    /** The field asks for no personalized learning. Read in every input class: a number or phone field can ask for it too. */
    fun noLearning(imeOptions: Int): Boolean = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

    fun decide(input: Input): Decision {
        val password = InputTypeUtils.isAnyPasswordInputType(input.inputType)
        val noLearning = noLearning(input.imeOptions)
        val listed = input.packageName != null && input.packageName in input.incognitoApps
        return Decision(password, noLearning, password || noLearning || input.forceIncognito || listed || input.locked)
    }

    fun decide(inputType: Int, imeOptions: Int, packageName: String?, forceIncognito: Boolean, incognitoApps: Set<String>, locked: Boolean): Decision =
        decide(Input(inputType, imeOptions, packageName, forceIncognito, incognitoApps, locked))

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
