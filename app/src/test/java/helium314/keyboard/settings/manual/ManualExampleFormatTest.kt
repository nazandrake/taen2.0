// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.manual

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The fixed format of a Vim example: Before, Keys and After, each text with one cursor marker. */
class ManualExampleFormatTest {
    @Test fun aWellFormedExample() {
        val e = ManualExample.parse("Before\nfoo b|ar\nbaz\nKeys\ndd\nAfter\nfoo\n|baz")
        assertEquals(Block.Example("foo b|ar\nbaz", "dd", "foo\n|baz"), e)
    }

    @Test fun theMarkerIsTheIndexOfTheCharacterAfterIt() {
        assertEquals(ManualExample.Placed("foo bar", 4), ManualExample.place("foo |bar"))
        assertEquals(ManualExample.Placed("a\nbc", 2), ManualExample.place("a\n|bc"))
        assertEquals(ManualExample.Placed("", 0), ManualExample.place("|"))
        assertEquals(ManualExample.Placed("ab", 2), ManualExample.place("ab|"))
    }

    @Test fun noMarkerOrTwoMarkersGiveNothing() {
        assertNull(ManualExample.place("foo"))
        assertNull(ManualExample.place("a|b|c"))
    }

    @Test fun aWrongFormatGivesNothing() {
        val bad = listOf(
            "Keys\ndd\nBefore\na|b\nAfter\na|b", // wrong order
            "Before\na|b\nKeys\ndd", // no After
            "Before\nab\nKeys\ndd\nAfter\na|b", // no marker before
            "Before\na|b\nKeys\ndd\nAfter\nab", // no marker after
            "Before\na|b\nKeys\n\nAfter\na|b", // empty keys
            "Before\na|b\nKeys\ndd\nxx\nAfter\na|b", // two key lines
            "Before\nKeys\ndd\nAfter\na|b", // empty before
            "Before\na|b\nKeys\ndd\nAfter\na|b\nBefore\nx", // a section twice
            "",
        )
        for (b in bad) assertNull(ManualExample.parse(b), b)
        assertNotNull(ManualExample.parse("Before\na|b\nKeys\ndd\nAfter\na|b"), "positive control")
    }

    @Test fun theKeysLineIsTrimmed() {
        assertEquals("dw", (ManualExample.parse("Before\na|b\nKeys\n  dw  \nAfter\na|b") as Block.Example).keys)
    }
}
