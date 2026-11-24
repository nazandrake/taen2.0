// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.manual

/**
 * The fixed format of a Vim example (a fenced block of the language `example`), one owner of it for the page renderer and for the test that runs
 * every example through the engine:
 *
 *     Before
 *     one |two        <- the text before; | stands before the character the cursor is on
 *     Keys
 *     dw              <- the keys, on one line (<Esc> <CR> <BS> <C-r> and the other control keys as in the Vim tests)
 *     After
 *     one |           <- the text after, with the cursor marked the same way
 *
 * Each text is one or more lines; the label lines are exactly Before, Keys and After, each once, in this order.
 */
object ManualExample {
    /** A text without its cursor marker, and the index of the character the cursor stood before (the length of the text at its end). */
    data class Placed(val text: String, val cursor: Int)

    private const val MARK = '|'

    fun parse(body: String): Block.Example? {
        val lines = body.split("\n")
        val before = lines.indexOf("Before")
        val keys = lines.indexOf("Keys")
        val after = lines.indexOf("After")
        if (before != 0 || keys < 0 || after < 0 || !(before < keys && keys < after)) return null
        if (lines.count { it == "Before" } != 1 || lines.count { it == "Keys" } != 1 || lines.count { it == "After" } != 1) return null
        val beforeText = lines.subList(before + 1, keys).joinToString("\n")
        val keyLines = lines.subList(keys + 1, after)
        val afterText = lines.subList(after + 1, lines.size).joinToString("\n")
        if (keys - before < 2 || after - keys != 2 || after + 1 >= lines.size) return null
        val keyText = keyLines.single().trim()
        if (keyText.isEmpty() || place(beforeText) == null || place(afterText) == null) return null
        return Block.Example(beforeText, keyText, afterText)
    }

    /** The text with its one marker taken out, or null when the text has no marker or more than one. */
    fun place(marked: String): Placed? {
        val at = marked.indexOf(MARK)
        if (at < 0 || marked.indexOf(MARK, at + 1) >= 0) return null
        return Placed(marked.removeRange(at, at + 1), at)
    }
}
