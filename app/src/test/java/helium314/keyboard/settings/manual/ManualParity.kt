// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.manual

/**
 * What a page of another language must share with the English page: the same settings entries in the same order, the same page links in the same
 * order, the same Vim examples word for word (they are language-neutral and are run through the engine), the same number of headings of each
 * level, lists, code blocks and paragraphs. The words are the translation; the structure is the contract.
 */
object ManualParity {
    fun differences(english: List<Block>, other: List<Block>): List<String> {
        val out = ArrayList<String>()
        if (ManualFiles.keys(english) != ManualFiles.keys(other)) out.add("settings entries ${ManualFiles.keys(other)} instead of ${ManualFiles.keys(english)}")
        if (ManualFiles.links(english) != ManualFiles.links(other)) out.add("links ${ManualFiles.links(other)} instead of ${ManualFiles.links(english)}")
        if (ManualFiles.examples(english) != ManualFiles.examples(other)) out.add("the Vim examples differ")
        val shapeEnglish = shape(english)
        val shapeOther = shape(other)
        if (shapeEnglish != shapeOther) out.add("structure $shapeOther instead of $shapeEnglish")
        return out
    }

    /** The kind of each block, with the level of a heading, the number of items of a list. */
    private fun shape(blocks: List<Block>): List<String> = blocks.map {
        when (it) {
            is Block.Heading -> "h${it.level}"
            is Block.Paragraph -> "p"
            is Block.Bullets -> "ul${it.items.size}"
            is Block.Numbered -> "ol${it.items.size}"
            is Block.Code -> "code"
            is Block.Example -> "example"
        }
    }
}
