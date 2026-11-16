// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonalDictionaryTest {
    private var saved: List<PersonalWord> = emptyList()
    private var saves = 0
    private fun dictionary(initial: List<PersonalWord> = emptyList()) = PersonalDictionary(initial) { saved = it; saves++ }

    // ---- core behaviour -------------------------------------------------------------------------------

    @Test fun addedWordIsFoundAndPersisted() {
        val d = dictionary()
        assertTrue(d.add(PersonalWord("Taen", null, 250, "en_US")))
        assertEquals(listOf(PersonalWord("Taen", null, 250, "en_US")), d.forLocale("en_US"))
        assertEquals(1, saved.size)
    }

    @Test fun snippetKeepsItsShortcut() {
        val d = dictionary()
        d.add(PersonalWord("my.address@example.com", "@@", 250, null))
        assertEquals("@@", d.forLocale(null).single().shortcut)
    }

    // ---- identity, locale matching, validation, listeners, codec -----------------------------------------

    @Test fun sameWordAndLocaleIsReplaced() {
        val d = dictionary()
        d.add(PersonalWord("Taen", null, 100, "en"))
        d.add(PersonalWord("Taen", "tn", 200, "en"))
        assertEquals(listOf(PersonalWord("Taen", "tn", 200, "en")), d.all())
        assertTrue(d.exists("Taen", "en"))
        assertFalse(d.exists("Taen", "de"))
        assertFalse(d.exists("Taen", null))
    }

    @Test fun sameWordInAnotherLocaleIsAnotherEntry() {
        val d = dictionary()
        d.add(PersonalWord("Taen", null, 100, "en"))
        d.add(PersonalWord("Taen", null, 100, "bg"))
        d.add(PersonalWord("Taen", null, 100, null))
        assertEquals(3, d.all().size)
    }

    @Test fun deleteRemovesExactlyThatEntry() {
        val d = dictionary(listOf(PersonalWord("a", null, 1, "en"), PersonalWord("b", "x", 2, "en")))
        assertFalse(d.delete(PersonalWord("a", null, 2, "en"))) // other frequency: not the same entry
        assertEquals(2, d.all().size)
        assertTrue(d.delete(PersonalWord("a", null, 1, "en")))
        assertEquals(listOf(PersonalWord("b", "x", 2, "en")), saved)
    }

    @Test fun matchingFollowsTheLocaleChain() {
        val all = listOf("en", "en_US", "en_US_POSIX", "en_GB", "de", "enx", "en_US_POSIX_more").mapIndexed { i, l -> PersonalWord("w$i", null, 10, l) } +
            PersonalWord("every", null, 10, null)
        val d = dictionary(all)
        fun words(locale: String?) = d.matching(locale).map { it.word }.sorted()
        assertEquals(listOf("every", "w0"), words("en")) // en only: not en_US, not en_GB
        assertEquals(listOf("every", "w0", "w1"), words("en_US"))
        assertEquals(listOf("every", "w0", "w1", "w2"), words("en_US_POSIX"))
        assertEquals(listOf("every"), words("fr"))
        assertEquals(listOf("every"), words(null)) // no language: all-language entries only
        assertEquals(listOf("every"), words(""))
        assertEquals(listOf("every", "w4"), words("de"))
    }

    @Test fun matchingTreatsScriptVariantsLikeTheOldQuery() { // "sr__#Latn" splits into sr, "", #Latn
        val d = dictionary(listOf(PersonalWord("a", null, 1, "sr"), PersonalWord("b", null, 1, "sr_"), PersonalWord("c", null, 1, "sr__#Latn"), PersonalWord("d", null, 1, "sr_RS")))
        assertEquals(listOf("a", "b", "c"), d.matching("sr__#Latn").map { it.word }.sorted())
    }

    @Test fun forLocaleIsExactAndSortedIgnoringCase() {
        val d = dictionary(listOf(PersonalWord("banana", null, 1, "en"), PersonalWord("Apple", null, 1, "en"), PersonalWord("cherry", null, 1, "en"),
            PersonalWord("zzz", null, 1, "en_US"), PersonalWord("global", null, 1, null)))
        assertEquals(listOf("Apple", "banana", "cherry"), d.forLocale("en").map { it.word })
        assertEquals(listOf("global"), d.forLocale(null).map { it.word })
    }

    @Test fun invalidEntriesAreRejectedAndChangeNothing() {
        val d = dictionary()
        val bad = listOf(
            PersonalWord("", null, 100, null), PersonalWord("  ", null, 100, null),
            PersonalWord("x".repeat(PersonalDictionary.MAX_WORD_LENGTH + 1), null, 100, null),
            PersonalWord("w", "", 100, null), PersonalWord("w", " ", 100, null),
            PersonalWord("w", "s".repeat(PersonalDictionary.MAX_SHORTCUT_LENGTH + 1), 100, null),
            PersonalWord("w", null, -1, null), PersonalWord("w", null, 256, null),
        )
        for (entry in bad) assertFalse(d.add(entry), entry.toString())
        assertEquals(0, saves)
        assertEquals(emptyList(), d.all())
        // positive controls: the limits themselves are valid
        assertTrue(d.add(PersonalWord("x".repeat(PersonalDictionary.MAX_WORD_LENGTH), "s".repeat(PersonalDictionary.MAX_SHORTCUT_LENGTH), 255, null)))
        assertTrue(d.add(PersonalWord("zero", null, 0, null)))
    }

    @Test fun entryLimitRejectsNewWordsButStillAllowsReplacing() {
        val d = dictionary((0 until PersonalDictionary.MAX_ENTRIES).map { PersonalWord("w$it", null, 1, null) })
        assertFalse(d.add(PersonalWord("one more", null, 1, null)))
        assertTrue(d.add(PersonalWord("w5", "s", 9, null)))
        assertEquals(PersonalDictionary.MAX_ENTRIES, d.all().size)
    }

    @Test fun listenersHearAcceptedChangesOnly() {
        val d = dictionary()
        var calls = 0
        val l = PersonalDictionary.ChangeListener { calls++ }
        d.addListener(l)
        d.add(PersonalWord("a", null, 1, null))
        assertEquals(1, calls)
        d.add(PersonalWord("", null, 1, null)) // rejected
        d.delete(PersonalWord("nope", null, 1, null)) // not there
        assertEquals(1, calls)
        d.delete(PersonalWord("a", null, 1, null))
        d.clear() // already empty: nothing to announce
        assertEquals(2, calls)
        d.add(PersonalWord("b", null, 1, null))
        d.clear()
        assertEquals(4, calls)
        assertEquals(emptyList(), saved)
        d.removeListener(l)
        d.add(PersonalWord("c", null, 1, null))
        assertEquals(4, calls)
    }

    @Test fun replaceAllSwapsTheWholeListAndPersistsOnce() {
        val d = dictionary(listOf(PersonalWord("old", null, 1, "en")))
        var changed = 0
        d.addListener { changed++ }
        val saves0 = saves
        assertTrue(d.replaceAll(listOf(PersonalWord("a", null, 10, "en"), PersonalWord("a", "x", 20, "bg"))))
        assertEquals(listOf(PersonalWord("a", null, 10, "en"), PersonalWord("a", "x", 20, "bg")), d.all())
        assertEquals(saves0 + 1, saves)
        assertEquals(1, changed)
    }

    @Test fun replaceAllRejectsAnInvalidListWithoutChangingAnything() {
        val old = PersonalWord("old", null, 1, "en")
        val d = dictionary(listOf(old))
        val saves0 = saves
        assertFalse(d.replaceAll(listOf(PersonalWord("ok", null, 1, "en"), PersonalWord(" ", null, 1, "en")))) // blank word
        assertFalse(d.replaceAll(listOf(PersonalWord("ok", null, 256, "en")))) // frequency out of range
        assertFalse(d.replaceAll(listOf(PersonalWord("ok", "", 1, "en")))) // blank shortcut
        assertFalse(d.replaceAll(listOf(PersonalWord("dup", null, 1, "en"), PersonalWord("dup", "s", 2, "en")))) // same word and locale
        assertFalse(d.replaceAll(List(PersonalDictionary.MAX_ENTRIES + 1) { PersonalWord("w$it", null, 1, null) }))
        assertEquals(listOf(old), d.all())
        assertEquals(saves0, saves)
        assertTrue(d.replaceAll(emptyList())) // control: an empty list is valid
        assertEquals(emptyList(), d.all())
    }

    @Test fun codecRoundTripAndRejection() {
        val words = listOf(PersonalWord("кирилица", "кр", 250, "bg"), PersonalWord("a", null, 0, null), PersonalWord("b", "s", 255, "en_US"))
        assertEquals(words, PersonalDictionaryCodec.decode(PersonalDictionaryCodec.encode(words)))
        val bytes = PersonalDictionaryCodec.encode(words)
        assertFailsWith<CorruptStoreException> { PersonalDictionaryCodec.decode(bytes.copyOf(bytes.size - 2)) }
        assertFailsWith<CorruptStoreException> { PersonalDictionaryCodec.decode(bytes + byteArrayOf(0)) }
        assertFailsWith<CorruptStoreException> { PersonalDictionaryCodec.decode(byteArrayOf(0, 0, 0, 1, 0x7f, 0, 0, 0)) }
        assertFailsWith<CorruptStoreException> { PersonalDictionaryCodec.decode(byteArrayOf(0, 0, 0, 5, 0, 0, 0, 0)) }
        // a text length far beyond the data is rejected before any allocation (would be a 2 GB array)
        val oneWord = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 0x7f, 0, 0, 0)
        assertFailsWith<CorruptStoreException> { PersonalDictionaryCodec.decode(oneWord) }
    }

    // ---- an edit that cannot be written is refused, never kept only in memory ----------------------------

    @Test fun editsThatCannotBeWrittenAreRefusedAndChangeNothing() {
        var failing = false
        val d = PersonalDictionary(emptyList()) { if (failing) throw VaultLockedException("locked") else saved = it }
        val first = PersonalWord("first", null, 100, "en")
        assertTrue(d.add(first)) // positive control: the same calls succeed while writing works
        assertEquals(listOf(first), saved)

        failing = true
        assertFalse(d.add(PersonalWord("second", null, 100, "en")))
        assertFalse(d.add(first.copy(frequency = 7))) // a replacement is refused too
        assertFalse(d.delete(first))
        assertFalse(d.replaceAll(listOf(PersonalWord("third", null, 1, null))))
        d.clear()
        assertEquals(listOf(first), d.all()) // memory still equals what is on disk
        assertEquals(listOf(first), saved)

        failing = false
        assertTrue(d.add(PersonalWord("second", null, 100, "en")))
        assertEquals(2, saved.size)
    }

    @Test fun anyWriteErrorIsARefusalNotAnException() {
        for (error in listOf<Exception>(StoreWriteException("disk"), VaultUnavailableException("keystore"), java.io.IOException("io"))) {
            val d = PersonalDictionary(emptyList()) { throw error }
            assertFalse(d.add(PersonalWord("w", null, 1, null)), error.javaClass.simpleName)
            assertEquals(emptyList(), d.all())
        }
    }
}
