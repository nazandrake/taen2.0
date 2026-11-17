// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.security.MessageDigest

/**
 * Matches the hidden trigger word. It only sees the text before the cursor when a commit key is pressed, looks at
 * the last [WINDOW] characters at most, keeps the token as a char array that is cleared after the hash, and compares only a salted
 * Argon2id hash (one hash per check, 8 MiB). Nothing is logged or stored. A String cannot be wiped on the JVM, so the text given in
 * stays alive as long as its owner keeps it; this class copies out only the token and clears the copy.
 */
class TriggerMatcher(private val hasher: PinHasher) {
    /**
     * The token (the text after the last whitespace) of the end of [before], or null when no hash is due: it is not 12 to 64 characters
     * long, or it could not be a trigger word under the setup rules ([couldBeWord]: all letters, or a separator in it), so ordinary long
     * words cost no hash and keep autocorrect and learning.
     */
    fun tokenOf(before: CharSequence, isWordSeparator: (Int) -> Boolean): CharArray? {
        val end = before.length
        val limit = maxOf(0, end - WINDOW)
        var start = end
        while (start > limit && !isBreak(before[start - 1])) start--
        val length = end - start
        if (length < MIN_LENGTH || length > MAX_LENGTH) return null
        val token = CharArray(length) { before[start + it] }
        if (!couldBeWord(java.nio.CharBuffer.wrap(token), isWordSeparator)) { token.fill('\u0000'); return null }
        return token
    }

    /** True when [token] (NFC-normalised first) is the word of [record]. The token is cleared. */
    fun matches(token: CharArray, record: SecretRecord): Boolean {
        val normalized = TriggerWordRules.normalize(token)
        try {
            return MessageDigest.isEqual(hasher.hash(normalized, record.salt, record.kdf), record.hash)
        } finally {
            token.fill('\u0000')
            normalized.fill('\u0000')
        }
    }

    companion object {
        const val MIN_LENGTH = 12
        const val MAX_LENGTH = 64
        /** The text asked from the field: a token of 64 characters and the character before it. */
        const val WINDOW = MAX_LENGTH + 1

        /**
         * True when [text] passes the rules a trigger word has to pass when it is set (length 12 to 64, not only letters, no white space,
         * separator or emoji in it, see [TriggerWordRules.check]): only such a text can be the word, so only it is checked and kept out of
         * learning and autocorrect. No hash, no dictionary look-up.
         */
        fun couldBeWord(text: CharSequence, isWordSeparator: (Int) -> Boolean): Boolean {
            if (text.length < MIN_LENGTH || text.length > MAX_LENGTH) return false
            var allLetters = true // per char, as TriggerWordRules.check counts it
            for (c in text) if (!c.isLetter()) { allLetters = false; break }
            var i = 0
            while (i < text.length) {
                val cp = Character.codePointAt(text, i)
                if (TriggerWordRules.splitsInput(cp, isWordSeparator)) return false
                i += Character.charCount(cp)
            }
            return !allLetters
        }

        /** Where a token ends: white space of every kind (Kotlin's test includes the no-break space, checked in TriggerWordTest). */
        fun isBreak(c: Char): Boolean = c.isWhitespace()
    }
}
