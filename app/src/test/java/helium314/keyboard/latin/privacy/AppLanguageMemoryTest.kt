// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The map of "Remember language per app": format, order, bound, damaged input, rename, move of the earlier format. */
@RunWith(RobolectricTestRunner::class)
class AppLanguageMemoryTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun prefs(name: String): SharedPreferences = ctx.getSharedPreferences("alm-$name-${System.nanoTime()}", Context.MODE_PRIVATE)

    // ---- the core behaviour -------------------------------------------------------------------------------------

    @Test fun aRememberedLanguageIsRecalledPerApp() {
        val p = prefs("basic")
        assertTrue(AppLanguageMemory.remember(p, "org.a", "en_US:qwerty"))
        assertTrue(AppLanguageMemory.remember(p, "org.b", "bg:bg_phonetic"))
        assertEquals("en_US:qwerty", AppLanguageMemory.recall(p, "org.a"))
        assertEquals("bg:bg_phonetic", AppLanguageMemory.recall(p, "org.b"))
        assertNull(AppLanguageMemory.recall(p, "org.c"))
    }

    // ---- the whole contract -------------------------------------------------------------------------------------

    @Test fun anAppRememberedAgainReplacesItsValueAndBecomesTheNewest() {
        val p = prefs("again")
        AppLanguageMemory.remember(p, "org.a", "x"); AppLanguageMemory.remember(p, "org.b", "y"); AppLanguageMemory.remember(p, "org.a", "z")
        assertEquals(listOf("org.b", "org.a"), AppLanguageMemory.parse(p.getString(AppLanguageMemory.KEY, null)).keys.toList())
        assertEquals("z", AppLanguageMemory.recall(p, "org.a"))
        val writesBefore = p.getString(AppLanguageMemory.KEY, null)
        AppLanguageMemory.remember(p, "org.a", "z") // same value, already the newest: nothing to write
        assertEquals(writesBefore, p.getString(AppLanguageMemory.KEY, null))
    }

    @Test fun onlyTheNewestTwoHundredAppsAreKept() {
        val p = prefs("bound")
        for (i in 1..AppLanguageMemory.MAX_APPS + 1) AppLanguageMemory.remember(p, "org.app$i", "en")
        val map = AppLanguageMemory.parse(p.getString(AppLanguageMemory.KEY, null))
        assertEquals(AppLanguageMemory.MAX_APPS, map.size)
        assertNull(AppLanguageMemory.recall(p, "org.app1")) // the oldest went
        assertEquals("en", AppLanguageMemory.recall(p, "org.app2"))
        assertEquals("en", AppLanguageMemory.recall(p, "org.app${AppLanguageMemory.MAX_APPS + 1}"))
        // an app used again is not the oldest any more
        AppLanguageMemory.remember(p, "org.app2", "bg")
        AppLanguageMemory.remember(p, "org.new", "en")
        assertEquals("bg", AppLanguageMemory.recall(p, "org.app2"))
        assertNull(AppLanguageMemory.recall(p, "org.app3"))
        assertEquals(200, AppLanguageMemory.parse(p.getString(AppLanguageMemory.KEY, null)).size)
    }

    @Test fun anOversizedStoredValueIsCutToTheBoundWhenRead() {
        val raw = (1..300).joinToString("\n") { "org.app$it\ten" }
        val map = AppLanguageMemory.parse(raw)
        assertEquals(200, map.size)
        assertEquals("org.app101", map.keys.first())
        assertEquals("org.app300", map.keys.last())
    }

    @Test fun damagedLinesAreIgnoredAndTheRestIsKept() {
        val raw = "org.a\ten\nno tab here\n\torg.empty-name\ntwo\ttabs\there\norg.b\tbg\n" + "x".repeat(300) + "\ten"
        assertEquals(mapOf("org.a" to "en", "org.b" to "bg"), AppLanguageMemory.parse(raw))
        assertEquals(emptyMap(), AppLanguageMemory.parse(null))
        assertEquals(emptyMap(), AppLanguageMemory.parse(""))
    }

    @Test fun valuesThatCouldBreakTheFormatAreRefusedAndNothingIsWritten() {
        val p = prefs("refuse")
        for ((pkg, value) in listOf("org.a" to "x\ny", "org.a\tb" to "x", "org.a\nb" to "x", "" to "x", "org.a" to "", "org.a" to "x\ty", "org.a" to "y".repeat(513), "a".repeat(256) to "x"))
            assertFalse(AppLanguageMemory.remember(p, pkg, value), "$pkg / $value")
        assertNull(p.getString(AppLanguageMemory.KEY, null))
        assertTrue(AppLanguageMemory.remember(p, "a".repeat(255), "y".repeat(512))) // the boundary itself is fine
    }

    @Test fun aRenamedSubtypeIsRenamedInEveryApp() {
        val p = prefs("rename")
        AppLanguageMemory.remember(p, "org.a", "old"); AppLanguageMemory.remember(p, "org.b", "other"); AppLanguageMemory.remember(p, "org.c", "old")
        AppLanguageMemory.replace(p, "old", "new")
        assertEquals(listOf("new", "other", "new"), listOf("org.a", "org.b", "org.c").map { AppLanguageMemory.recall(p, it) })
        assertEquals(listOf("org.a", "org.b", "org.c"), AppLanguageMemory.parse(p.getString(AppLanguageMemory.KEY, null)).keys.toList()) // the order stays
        AppLanguageMemory.replace(p, "absent", "x") // no entry: nothing changes
        assertEquals("new", AppLanguageMemory.recall(p, "org.a"))
        AppLanguageMemory.replace(p, "new", "bad\tvalue") // not storable: refused
        assertEquals("new", AppLanguageMemory.recall(p, "org.a"))
    }

    @Test fun theEarlierFormatIsMovedWithoutOverwritingNewerEntriesAndRemovedFromTheOldPlace() {
        val legacy = prefs("legacy")
        val memory = prefs("memory")
        legacy.edit().putString(AppLanguageMemory.LEGACY_PREFIX + "org.old", "bg").putString(AppLanguageMemory.LEGACY_PREFIX + "org.both", "bg")
            .putString("other_setting", "keep").putBoolean(AppLanguageMemory.LEGACY_PREFIX + "org.notastring", true).commit()
        AppLanguageMemory.remember(memory, "org.both", "en")
        assertTrue(AppLanguageMemory.settleLegacy(legacy, memory, true, emptySet()))
        assertEquals("bg", AppLanguageMemory.recall(memory, "org.old"))
        assertEquals("en", AppLanguageMemory.recall(memory, "org.both")) // the newer entry wins
        assertEquals(setOf("other_setting"), legacy.all.keys) // the moved keys and the junk of the old prefix are gone, other settings stay
        assertEquals("keep", legacy.getString("other_setting", null))
        assertTrue(AppLanguageMemory.settleLegacy(legacy, memory, true, emptySet())) // again: nothing to do
    }

    @Test fun theEarlierFormatStaysWhereItIsWhenTheNewPlaceCannotBeWritten() {
        val legacy = prefs("legacy2")
        legacy.edit().putString(AppLanguageMemory.LEGACY_PREFIX + "org.old", "bg").commit()
        val readOnly = object : SharedPreferences by prefs("ro") {
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by prefs("ro2").edit() {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
                override fun commit(): Boolean = false
            }
        }
        assertFalse(AppLanguageMemory.settleLegacy(legacy, readOnly, true, emptySet()))
        assertEquals("bg", legacy.getString(AppLanguageMemory.LEGACY_PREFIX + "org.old", null)) // nothing was removed
    }

    @Test fun moreThanTwoHundredEarlierEntriesAreBoundedToo() {
        val legacy = prefs("legacy3")
        val memory = prefs("memory3")
        val e = legacy.edit()
        for (i in 1..250) e.putString(AppLanguageMemory.LEGACY_PREFIX + "org.app$i", "en")
        e.commit()
        assertTrue(AppLanguageMemory.settleLegacy(legacy, memory, true, emptySet()))
        assertEquals(200, AppLanguageMemory.parse(memory.getString(AppLanguageMemory.KEY, null)).size)
        assertTrue(legacy.all.isEmpty())
    }

    private val bg = "bg:bg_phonetic"
    private fun oldKey(pkg: String) = AppLanguageMemory.LEGACY_PREFIX + pkg

    @Test fun theEarlierFormatIsDeletedAndNotMovedWhenTheSettingIsOff() {
        val legacy = prefs("legacy4")
        val memory = prefs("memory4")
        legacy.edit().putString(oldKey("org.a"), bg).putString(oldKey("org.b"), "en").putString("other_setting", "keep").commit()
        assertTrue(AppLanguageMemory.settleLegacy(legacy, memory, false, emptySet()))
        assertEquals(setOf("other_setting"), legacy.all.keys)
        assertNull(memory.getString(AppLanguageMemory.KEY, null), "nothing is moved when the setting is off")
        // positive control: the same input with the setting on moves both
        legacy.edit().putString(oldKey("org.a"), bg).putString(oldKey("org.b"), "en").commit()
        assertTrue(AppLanguageMemory.settleLegacy(legacy, memory, true, emptySet()))
        assertEquals(bg, AppLanguageMemory.recall(memory, "org.a"))
        assertEquals("en", AppLanguageMemory.recall(memory, "org.b"))
    }

    @Test fun anEarlierEntryOfAnIncognitoAppIsDeletedAndNotMoved() {
        val legacy = prefs("legacy5")
        val memory = prefs("memory5")
        legacy.edit().putString(oldKey("org.secret"), bg).putString(oldKey("org.plain"), "en").commit()
        assertTrue(AppLanguageMemory.settleLegacy(legacy, memory, true, setOf("org.secret")))
        assertNull(AppLanguageMemory.recall(memory, "org.secret"))
        assertEquals("en", AppLanguageMemory.recall(memory, "org.plain"))
        assertTrue(legacy.all.isEmpty())
        // only incognito apps: nothing is written at all, the old entries are still deleted
        legacy.edit().putString(oldKey("org.secret"), bg).commit()
        val untouched = memory.getString(AppLanguageMemory.KEY, null)
        assertTrue(AppLanguageMemory.settleLegacy(legacy, memory, true, setOf("org.secret")))
        assertEquals(untouched, memory.getString(AppLanguageMemory.KEY, null))
        assertTrue(legacy.all.isEmpty())
    }

    @Test fun theListAndTheEarlierEntriesCanBeCleared() {
        val legacy = prefs("legacy6")
        val memory = prefs("memory6")
        AppLanguageMemory.remember(memory, "org.a", bg)
        memory.edit().putString("other", "keep").commit()
        legacy.edit().putString(oldKey("org.old"), bg).putString("other_setting", "keep").commit()
        AppLanguageMemory.clear(memory)
        AppLanguageMemory.clearLegacy(legacy)
        assertNull(AppLanguageMemory.recall(memory, "org.a"))
        assertNull(memory.getString(AppLanguageMemory.KEY, null))
        assertEquals("keep", memory.getString("other", null))
        assertEquals(setOf("other_setting"), legacy.all.keys)
    }

    @Test fun appsAreRemovedFromTheListByName() {
        val memory = prefs("memory7")
        for (a in listOf("org.a", "org.b", "org.c")) AppLanguageMemory.remember(memory, a, bg)
        AppLanguageMemory.removeApps(memory, setOf("org.b", "org.not.there"))
        assertEquals(listOf("org.a", "org.c"), AppLanguageMemory.parse(memory.getString(AppLanguageMemory.KEY, null)).keys.toList())
        AppLanguageMemory.removeApps(memory, emptySet())
        assertEquals(listOf("org.a", "org.c"), AppLanguageMemory.parse(memory.getString(AppLanguageMemory.KEY, null)).keys.toList())
    }
}
