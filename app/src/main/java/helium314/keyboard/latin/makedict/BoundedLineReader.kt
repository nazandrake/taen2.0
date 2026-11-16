// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.makedict

import java.io.BufferedReader
import java.io.IOException

/**
 * [BufferedReader.readLine] with a bound. A dictionary file without a newline (a 60 MiB binary or text file picked by mistake) made readLine build one
 * string of the whole file and end the settings with an OutOfMemoryError; a line longer than [MAX_LINE_CHARS] is an IOException here, which the
 * dictionary import reports as "not a dictionary" (a line of the combined or XML word-list format is a few hundred characters).
 */
object BoundedLineReader {
    const val MAX_LINE_CHARS = 1 shl 20

    /** The next line without its terminator (\n, \r or \r\n), or null at the end of the input. */
    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun readLine(reader: BufferedReader, maxChars: Int = MAX_LINE_CHARS): String? {
        val line = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString()
            if (c == '\r'.code) {
                reader.mark(1)
                if (reader.read() != '\n'.code) reader.reset()
                return line.toString()
            }
            if (line.length >= maxChars) throw IOException("line longer than $maxChars characters")
            line.append(c.toChar())
        }
    }
}
