// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context

/** Returns the word before the cursor, or an empty string. */
fun wordBeforeCursor(text: CharSequence, cursor: Int): String {
    var start = cursor
    while (start > 0 && text[start - 1].isLetter()) start--
    return text.substring(start, cursor).toString()
}

class SuggestionCache(private val context: Context) {
    private val recent = ArrayDeque<String>()

    fun remember(word: String) {
        if (recent.size == 32) recent.removeFirst()
        recent.addLast(word)
    }
}
