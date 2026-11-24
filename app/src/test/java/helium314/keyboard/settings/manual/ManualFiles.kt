// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.manual

import java.io.File

/** The page files of the manual as they are in the sources (the tests of the app run in the app folder). */
object ManualFiles {
    val root = File("src/main/assets/manual")

    /**
     * The languages whose pages the tests check: parity with English, links, examples, keys. The folders of assets/manual are exactly this list
     * (ManualNavigationTest), so a new language is added here in the same change as its pages and is then checked like English.
     */
    val checkedLanguages = listOf("en", "bg")

    /** The language folders that exist. */
    fun languages(): List<String> = root.listFiles { f -> f.isDirectory }.orEmpty().map { it.name }.sorted()

    /** The ids of the page files of a language (the file names without .md). */
    fun ids(language: String): List<String> =
        File(root, language).listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty().map { it.name.removeSuffix(".md") }.sorted()

    /** The text of a page file. */
    fun text(language: String, id: String): String = File(File(root, language), "$id.md").readText(Charsets.UTF_8)

    /** The blocks of a page file. */
    fun blocks(language: String, id: String): List<Block> = ManualMarkdown.parse(text(language, id))

    /** The page ids that a list of spans links to, in order, inside bold text too. */
    fun spanLinks(spans: List<Span>): List<String> = spans.flatMap {
        when (it) {
            is Span.Link -> listOf(it.page)
            is Span.Bold -> spanLinks(it.children)
            else -> emptyList()
        }
    }

    /** The page ids that the blocks link to, in order. */
    fun links(blocks: List<Block>): List<String> = blocks.flatMap { b ->
        when (b) {
            is Block.Heading -> spanLinks(b.spans)
            is Block.Paragraph -> spanLinks(b.spans)
            is Block.Bullets -> b.items.flatMap { spanLinks(it) }
            is Block.Numbered -> b.items.flatMap { spanLinks(it) }
            is Block.Code, is Block.Example -> emptyList()
        }
    }

    /** The keys of the settings entries of the blocks (the hidden `{#key}` of a heading), in order. */
    fun keys(blocks: List<Block>): List<String> = blocks.mapNotNull { (it as? Block.Heading)?.key }

    fun examples(blocks: List<Block>): List<Block.Example> = blocks.filterIsInstance<Block.Example>()
}
