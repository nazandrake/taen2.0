// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.manual

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The renderer of the manual: every element of the fixed subset, unknown syntax kept as text, links, hidden key markers. */
class ManualMarkdownTest {
    private fun t(s: String) = Span.Text(s)
    private fun parse(s: String) = ManualMarkdown.parse(s)

    // ---- pass 1: headings, paragraphs, the hidden key ----

    @Test fun aHeadingAndAParagraph() {
        assertEquals(
            listOf(Block.Heading(1, listOf(t("Title")), null), Block.Paragraph(listOf(t("one two")))),
            parse("# Title\n\none two\n"),
        )
    }

    @Test fun aKeyMarkerIsHiddenAndKept() {
        assertEquals(
            listOf(Block.Heading(3, emptyList(), "vibrate_on")),
            parse("### {#vibrate_on}\n"),
        )
    }

    // ---- pass 2: the rest of the subset ----

    @Test fun threeHeadingLevelsAreRecognised() {
        assertEquals(
            listOf(Block.Heading(1, listOf(t("A")), null), Block.Heading(2, listOf(t("B")), null), Block.Heading(3, listOf(t("C")), null)),
            parse("# A\n## B\n### C"),
        )
    }

    @Test fun aKeyMarkerNextToTextKeepsTheTextAndTheKey() {
        assertEquals(listOf(Block.Heading(2, listOf(t("Fallback")), "auto_cap")), parse("## Fallback {#auto_cap}"))
    }

    @Test fun aMarkerThatIsNotAtTheEndOfAHeadingIsPlainText() {
        assertEquals(listOf(Block.Paragraph(listOf(t("see {#auto_cap} here")))), parse("see {#auto_cap} here"))
        assertEquals(listOf(Block.Heading(2, listOf(t("A {#x} b")), null)), parse("## A {#x} b"))
        assertEquals(listOf(Block.Heading(2, listOf(t("A {#bad key}")), null)), parse("## A {#bad key}"))
    }

    @Test fun linesOfOneParagraphJoinAndABlankLineSeparates() {
        assertEquals(
            listOf(Block.Paragraph(listOf(t("one two three"))), Block.Paragraph(listOf(t("four")))),
            parse("one\ntwo\nthree\n\n\nfour"),
        )
    }

    @Test fun bulletListsAndNumberedLists() {
        assertEquals(
            listOf(
                Block.Bullets(listOf(listOf(t("one")), listOf(t("two")))),
                Block.Numbered(listOf(listOf(t("first")), listOf(t("second")), listOf(t("tenth")))),
            ),
            parse("- one\n- two\n\n1. first\n2. second\n10. tenth"),
        )
    }

    @Test fun anIndentedLineContinuesTheItemAndAListEndsAtABlankLine() {
        assertEquals(
            listOf(
                Block.Bullets(listOf(listOf(t("one two")), listOf(t("three")))),
                Block.Paragraph(listOf(t("after"))),
            ),
            parse("- one\n  two\n- three\n\nafter"),
        )
    }

    @Test fun aListEndsAtTheFirstLineThatIsNeitherAnItemNorIndented() {
        assertEquals(
            listOf(Block.Bullets(listOf(listOf(t("one")))), Block.Paragraph(listOf(t("text")))),
            parse("- one\ntext"),
        )
    }

    @Test fun boldAndInlineCode() {
        assertEquals(
            listOf(Block.Paragraph(listOf(t("press "), Span.Bold(listOf(t("dw"))), t(" or "), Span.Code("x"), t(" now")))),
            parse("press **dw** or `x` now"),
        )
    }

    @Test fun codeInsideBoldAndMarkersInsideCodeAreKeptAsTheyAre() {
        assertEquals(
            listOf(Block.Paragraph(listOf(Span.Bold(listOf(t("use "), Span.Code("dd")))))),
            parse("**use `dd`**"),
        )
        assertEquals(listOf(Block.Paragraph(listOf(Span.Code("**not bold** [a](page:b)")))), parse("`**not bold** [a](page:b)`"))
    }

    @Test fun anEmptyPairOfMarkersIsPlainText() {
        assertEquals(listOf(Block.Paragraph(listOf(t("a **** b")))), parse("a **** b"))
        assertEquals(listOf(Block.Paragraph(listOf(t("a `` b")))), parse("a `` b"))
        assertEquals(listOf(Block.Paragraph(listOf(t("[](page:vim)")))), parse("[](page:vim)"))
    }

    @Test fun aPictureIsNotAPageLinkEvenWhenItsTargetLooksLikeOne() {
        assertEquals(listOf(Block.Paragraph(listOf(t("see ![the page](page:vim) here")))), parse("see ![the page](page:vim) here"))
        // positive control: without the exclamation mark it is a link
        assertEquals(listOf(Block.Paragraph(listOf(t("see "), Span.Link("the page", "vim"), t(" here")))), parse("see [the page](page:vim) here"))
    }

    @Test fun aSingleSpaceDoesNotContinueAListItem() {
        assertEquals(listOf(Block.Bullets(listOf(listOf(t("one")))), Block.Paragraph(listOf(t("two")))), parse("- one\n two"))
        // positive control: two spaces do
        assertEquals(listOf(Block.Bullets(listOf(listOf(t("one two"))))), parse("- one\n  two"))
    }

    @Test fun anUnclosedMarkerIsPlainText() {
        assertEquals(listOf(Block.Paragraph(listOf(t("a **b and `c")))), parse("a **b and `c"))
        assertEquals(listOf(Block.Paragraph(listOf(t("a ``  b")))), parse("a ``  b"))
    }

    @Test fun aLinkToAnotherPage() {
        assertEquals(
            listOf(Block.Paragraph(listOf(t("see "), Span.Link("the Vim page", "vim-mode"), t(".")))),
            parse("see [the Vim page](page:vim-mode)."),
        )
    }

    @Test fun aLinkThatIsNotAPageLinkIsKeptAsText() {
        for (s in listOf("[x](https://example.org)", "[x](page:)", "[x](page:Bad Id)", "[x]", "![img](a.png)", "[x](page:ok"))
            assertEquals(listOf(Block.Paragraph(listOf(t(s)))), parse(s), s)
    }

    @Test fun aFencedCodeBlockKeepsEveryLineAndSpace() {
        assertEquals(
            listOf(Block.Paragraph(listOf(t("before"))), Block.Code("  a **b**\n\n# c"), Block.Paragraph(listOf(t("after")))),
            parse("before\n```\n  a **b**\n\n# c\n```\nafter"),
        )
    }

    @Test fun anUnclosedFenceRunsToTheEndOfThePageAndNothingIsDropped() {
        assertEquals(listOf(Block.Code("a\nb")), parse("```\na\nb"))
    }

    @Test fun anExampleFenceBecomesAnExample() {
        assertEquals(
            listOf(Block.Example("one |two", "dw", "one |")),
            parse("```example\nBefore\none |two\nKeys\ndw\nAfter\none |\n```"),
        )
    }

    @Test fun anExampleThatDoesNotFollowTheFormatIsShownAsCode() {
        val body = "Before\none two\nKeys\ndw\nAfter\none |"
        assertEquals(listOf(Block.Code(body)), parse("```example\n$body\n```"), "no cursor marker in the before text")
    }

    @Test fun syntaxOutsideTheSubsetStaysVisibleAsText() {
        assertEquals(listOf(Block.Paragraph(listOf(t("*italic* and ~~gone~~")))), parse("*italic* and ~~gone~~"))
        assertEquals(listOf(Block.Paragraph(listOf(t("#### four hashes")))), parse("#### four hashes"))
        assertEquals(listOf(Block.Paragraph(listOf(t("#nospace")))), parse("#nospace"))
        assertEquals(listOf(Block.Paragraph(listOf(t("* star item\n* another")))), parse("* star item\n* another"))
        assertEquals(listOf(Block.Paragraph(listOf(t("<b>html</b>")))), parse("<b>html</b>"))
    }

    @Test fun tablesAndQuotesKeepTheirLineBreaks() {
        assertEquals(listOf(Block.Paragraph(listOf(t("| a | b |\n|---|---|\n| 1 | 2 |")))), parse("| a | b |\n|---|---|\n| 1 | 2 |"))
        assertEquals(listOf(Block.Paragraph(listOf(t("> quoted\n> more")))), parse("> quoted\n> more"))
        assertEquals(listOf(Block.Paragraph(listOf(t("---")))), parse("---"))
    }

    @Test fun everyNonBlankLineOfAnyInputSurvivesAsVisibleText() {
        val input = "# H {#k}\ntext *x* ~~y~~ [a](b)\n- i\n  c\n1. n\n```\ncode\n```\n> q\n| t |\n#### z\n"
        fun spanText(s: Span): String = when (s) {
            is Span.Text -> s.text
            is Span.Bold -> s.children.joinToString("") { spanText(it) }
            is Span.Code -> s.text
            is Span.Link -> s.text
        }
        fun blockText(b: Block): String = when (b) {
            is Block.Heading -> b.spans.joinToString("") { spanText(it) }
            is Block.Paragraph -> b.spans.joinToString("") { spanText(it) }
            is Block.Bullets -> b.items.joinToString(" ") { it.joinToString("") { s -> spanText(s) } }
            is Block.Numbered -> b.items.joinToString(" ") { it.joinToString("") { s -> spanText(s) } }
            is Block.Code -> b.text
            is Block.Example -> b.before + b.keys + b.after
        }
        val shown = parse(input).joinToString(" ") { blockText(it) }
        for (word in listOf("H", "text", "*x*", "~~y~~", "[a](b)", "i", "c", "n", "code", "> q", "| t |", "#### z"))
            assertTrue(word in shown, "$word is missing from: $shown")
        assertTrue("{#k}" !in shown, "the marker is hidden")
    }

    @Test fun anEmptyPageHasNoBlocks() {
        assertEquals(emptyList(), parse(""))
        assertEquals(emptyList(), parse("\n \n"))
    }

    @Test fun windowsLineEndingsAreAccepted() {
        assertEquals(listOf(Block.Heading(1, listOf(t("A")), null), Block.Paragraph(listOf(t("b c")))), parse("# A\r\n\r\nb\r\nc\r\n"))
    }
}
