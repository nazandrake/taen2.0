// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncryptedPrefsTest {
    private fun prefs(dir: File, key: ByteArray = fixedKey(), executor: Executor = directExecutor, files: StoreFiles = DefaultStoreFiles) =
        EncryptedPrefs({ testStore(dir, "prefs", 1, key, files) }, executor, directNotify)

    // ---- core behaviour -------------------------------------------------------------------------------

    @Test fun valuesSurviveReopen() {
        val dir = tempDir()
        prefs(dir).edit().putString("s", "abc").putInt("i", 7).putBoolean("b", true).commit()
        val reopened = prefs(dir)
        assertEquals("abc", reopened.getString("s", null))
        assertEquals(7, reopened.getInt("i", 0))
        assertTrue(reopened.getBoolean("b", false))
    }

    @Test fun defaultsForMissingKeys() {
        val p = prefs(tempDir())
        assertEquals("d", p.getString("x", "d"))
        assertEquals(5, p.getInt("x", 5))
        assertFalse(p.contains("x"))
    }

    // ---- the SharedPreferences contract, listeners, failures, locked device ---------------------------

    @Test fun everyTypeRoundTrips() {
        val dir = tempDir()
        val set = setOf("a", "б", "")
        prefs(dir).edit().putString("s", "ünï").putInt("i", Int.MIN_VALUE).putLong("l", Long.MAX_VALUE)
            .putFloat("f", 1.25f).putBoolean("b", false).putStringSet("set", set.toMutableSet()).commit()
        val p = prefs(dir)
        assertEquals("ünï", p.getString("s", null))
        assertEquals(Int.MIN_VALUE, p.getInt("i", 0))
        assertEquals(Long.MAX_VALUE, p.getLong("l", 0))
        assertEquals(1.25f, p.getFloat("f", 0f))
        assertFalse(p.getBoolean("b", true))
        assertEquals<Set<String>?>(set, p.getStringSet("set", null))
        assertEquals(mapOf("s" to "ünï", "i" to Int.MIN_VALUE, "l" to Long.MAX_VALUE, "f" to 1.25f, "b" to false, "set" to set), p.map())
        assertTrue(p.contains("set"))
    }

    @Test fun readingTheWrongTypeThrowsLikeThePlatform() {
        val p = prefs(tempDir())
        p.edit().putString("s", "x").putInt("i", 1).commit()
        assertFailsWith<ClassCastException> { p.getInt("s", 0) }
        assertFailsWith<ClassCastException> { p.getString("i", null) }
    }

    @Test fun removeClearAndNullValues() {
        val p = prefs(tempDir())
        p.edit().putString("a", "1").putString("b", "2").putStringSet("c", mutableSetOf("x")).commit()
        p.edit().remove("a").commit()
        assertFalse(p.contains("a"))
        p.edit().putString("b", null).putStringSet("c", null).commit() // null removes
        assertEquals(emptyMap<String, Any?>(), p.map())
        p.edit().putString("d", "4").commit()
        p.edit().clear().putString("e", "5").commit() // clear is applied first, then the puts
        assertEquals(mapOf("e" to "5"), p.map())
    }

    @Test fun stringSetsAreCopiedOnTheWayInAndOut() {
        val p = prefs(tempDir())
        val mine = mutableSetOf("a")
        p.edit().putStringSet("k", mine).commit()
        mine.add("changed")
        assertEquals<Set<String>?>(setOf("a"), p.getStringSet("k", null))
        p.getStringSet("k", null)!!.add("changed")
        (p.all["k"] as MutableSet<*>).clear() // the copy from getAll is not the stored set
        assertEquals<Set<String>?>(setOf("a"), p.getStringSet("k", null))
    }

    @Test fun getAllReturnsACopy() {
        val p = prefs(tempDir())
        p.edit().putInt("a", 1).commit()
        (p.all as MutableMap<String, Any?>).clear()
        assertEquals(1, p.getInt("a", 0))
    }

    private class Recorder : SharedPreferences.OnSharedPreferenceChangeListener {
        val keys = ArrayList<String?>()
        override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) { keys.add(key) }
    }

    @Test fun listenersHearAboutChangedKeysOnly() {
        val p = prefs(tempDir())
        val r = Recorder()
        p.registerOnSharedPreferenceChangeListener(r)
        p.edit().putInt("a", 1).putString("b", "x").commit()
        assertEquals(listOf<String?>("a", "b"), r.keys)
        r.keys.clear()
        p.edit().putInt("a", 1).commit() // same value: no callback
        p.edit().remove("missing").commit() // nothing removed: no callback
        assertEquals(emptyList<String?>(), r.keys)
        p.edit().putInt("a", 2).commit() // positive control: a real change is heard
        assertEquals(listOf<String?>("a"), r.keys)
        r.keys.clear()
        p.edit().remove("a").commit()
        assertEquals(listOf<String?>("a"), r.keys)
    }

    @Test fun clearNotifiesWithANullKey() {
        val p = prefs(tempDir())
        p.edit().putInt("a", 1).commit()
        val r = Recorder()
        p.registerOnSharedPreferenceChangeListener(r)
        p.edit().clear().commit()
        assertEquals(listOf<String?>(null), r.keys)
    }

    @Test fun unregisteredListenerIsSilent() {
        val p = prefs(tempDir())
        val r = Recorder()
        p.registerOnSharedPreferenceChangeListener(r)
        p.unregisterOnSharedPreferenceChangeListener(r)
        p.edit().putInt("a", 1).commit()
        assertEquals(emptyList<String?>(), r.keys)
    }

    @Test fun applyWritesLaterButIsVisibleAtOnce() {
        val dir = tempDir()
        val queue = ArrayList<Runnable>()
        val p = prefs(dir, executor = { queue.add(it) })
        p.edit().putString("k", "v").apply()
        assertEquals("v", p.getString("k", null)) // visible in memory
        assertFalse(File(dir, "prefs.enc").exists()) // not on disk yet
        assertEquals(1, queue.size)
        queue.forEach { it.run() }
        assertEquals("v", prefs(dir).getString("k", null))
    }

    @Test fun failedWriteReturnsFalseFromCommitAndKeepsMemoryState() {
        val dir = tempDir()
        val failing = object : StoreFiles {
            override fun writeSynced(file: File, bytes: ByteArray) = throw IOException("disk full")
            override fun replace(from: File, to: File) = false
        }
        val p = prefs(dir, files = failing)
        assertFalse(p.edit().putString("k", "v").commit())
        assertEquals("v", p.getString("k", null))
        assertFalse(File(dir, "prefs.enc").exists()) // nothing partial on disk
    }

    @Test fun garbageFileStartsEmptyAndIsDeleted() {
        val dir = tempDir()
        File(dir, "prefs.enc").writeBytes(ByteArray(100) { it.toByte() })
        val p = prefs(dir)
        assertEquals(emptyMap<String, Any?>(), p.map())
        assertFalse(File(dir, "prefs.enc").exists())
        assertTrue(p.edit().putInt("a", 1).commit()) // usable again
        assertEquals(1, prefs(dir).getInt("a", 0))
    }

    @Test fun wrongKeyShowsNoValues() {
        val dir = tempDir()
        prefs(dir, key = fixedKey(1)).edit().putString("secret", "x").commit()
        val other = prefs(dir, key = fixedKey(2))
        assertNull(other.getString("secret", null))
        assertEquals(emptyMap<String, Any?>(), other.map())
    }

    @Test fun authenticatedButMalformedPayloadStartsEmpty() {
        val dir = tempDir()
        testStore(dir, "prefs").write(byteArrayOf(0, 0, 0, 1, 0x7f, 0x7f, 0x7f, 0x7f)) // valid ciphertext, bogus content
        val p = prefs(dir)
        assertEquals(emptyMap<String, Any?>(), p.map())
        assertFalse(File(dir, "prefs.enc").exists())
    }

    @Test fun lockedStoreIsEmptyAndReadOnlyThenOpensLater() {
        val dir = tempDir()
        prefs(dir).edit().putString("k", "v").commit()
        var locked = true
        val p = EncryptedPrefs({ if (locked) null else testStore(dir, "prefs") }, directExecutor, directNotify)
        assertFalse(p.isAvailable())
        assertEquals("d", p.getString("k", "d")) // defaults, not the saved value
        assertFalse(p.edit().putString("lost", "x").commit()) // dropped, nothing written anywhere
        locked = false
        assertTrue(p.isAvailable())
        assertEquals("v", p.getString("k", null))
        assertFalse(p.contains("lost"))
    }

    @Test fun unavailableKeyBehavesLikeLocked() {
        val dir = tempDir()
        prefs(dir).edit().putString("k", "v").commit()
        val p = EncryptedPrefs({ SecureStore(dir, "prefs", 1, { throw VaultLockedException("locked") }) }, directExecutor, directNotify)
        assertFalse(p.isAvailable())
        assertEquals("d", p.getString("k", "d"))
        assertFalse(p.edit().putString("x", "y").commit())
        assertTrue(File(dir, "prefs.enc").exists()) // the stored data is untouched
    }

    @Test fun fileHoldsNoPlaintext() {
        val dir = tempDir()
        prefs(dir).edit().putString("canary-key-Zq7", "canary-value-Zq7").putStringSet("set", mutableSetOf("canary-in-set-Zq7")).commit()
        for (canary in listOf("canary-key-Zq7", "canary-value-Zq7", "canary-in-set-Zq7"))
            assertEquals(emptyList(), filesContaining(dir, canary))
    }

    @Test fun codecRejectsMalformedInput() {
        val good = PrefsCodec.encode(mapOf("a" to 1, "b" to "text"))
        assertEquals<Map<String, Any>>(mapOf("a" to 1, "b" to "text"), PrefsCodec.decode(good))
        assertFailsWith<CorruptStoreException> { PrefsCodec.decode(good.copyOf(good.size - 1)) } // truncated
        assertFailsWith<CorruptStoreException> { PrefsCodec.decode(good + byteArrayOf(0)) } // trailing byte
        assertFailsWith<CorruptStoreException> { PrefsCodec.decode(byteArrayOf(0, 0, 0, 1, 0x7f, 0x7f, 0x7f, 0x7f)) } // count bomb
        assertFailsWith<CorruptStoreException> { PrefsCodec.decode(byteArrayOf(0, 0, 0, 2, 0, 0, 0, 0)) } // unknown version
        assertFailsWith<CorruptStoreException> { PrefsCodec.decode(ByteArray(0)) }
        val one = PrefsCodec.encode(mapOf("a" to 1)) // version 4 bytes, count 4, key (length 4 + 1 byte), then the type tag
        assertEquals(1, one[13].toInt())
        assertFailsWith<CorruptStoreException> { PrefsCodec.decode(one.copyOf().also { it[13] = 9 }) } // unknown type tag
    }

    @Test fun codecKeepsAllValueTypes() {
        val map = mapOf<String, Any>("s" to "x", "i" to 1, "l" to 2L, "f" to 3f, "b" to true, "set" to setOf("q"))
        assertEquals(map, PrefsCodec.decode(PrefsCodec.encode(map)))
        assertFailsWith<IllegalArgumentException> { PrefsCodec.encode(mapOf("d" to 1.0)) }
    }

    // ---- an apply() that has not run yet is written before the lock wipes the preferences ----------------

    @Test fun flushAndEvictWritesTheEditsOfApplyCallsThatAreStillQueued() {
        val dir = tempDir()
        val queue = ArrayList<Runnable>()
        val p = prefs(dir, executor = { queue.add(it) }) // apply() queues its write and nothing runs it
        p.edit().putString("k", "v1").apply()
        p.edit().putString("k", "v2").putString("other", "o").apply()
        assertFalse(File(dir, "prefs.enc").exists()) // nothing is on disk yet (the write is still queued)

        assertTrue(p.flushAndEvict())
        assertEquals("v2", p.getString("k", "d")) // the wipe forgot the memory copy; this read opened the flushed file again

        queue.first().run() // the late run of the older snapshot (k=v1) must not overwrite the flushed newer one
        val reopened = prefs(dir)
        assertEquals("v2", reopened.getString("k", null))
        assertEquals("o", reopened.getString("other", null))
    }

    @Test fun flushAndEvictWithNothingPendingDoesNotWriteAndReportsSuccess() {
        val dir = tempDir()
        val p = prefs(dir)
        p.edit().putString("k", "v").commit()
        val before = File(dir, "prefs.enc").readBytes()
        assertTrue(p.flushAndEvict())
        assertEquals(before.toList(), File(dir, "prefs.enc").readBytes().toList()) // same ciphertext: no new write (a new write has a new nonce)
    }

    @Test fun flushAndEvictReportsAWriteThatFailsAndStillWipes() {
        val dir = tempDir()
        val queue = ArrayList<Runnable>()
        var failing = false
        val files = object : StoreFiles by DefaultStoreFiles {
            override fun writeSynced(file: File, bytes: ByteArray) = if (failing) throw IOException("disk full") else DefaultStoreFiles.writeSynced(file, bytes)
        }
        val p = prefs(dir, executor = { queue.add(it) }, files = files)
        p.edit().putString("k", "v").apply()
        failing = true
        assertFalse(p.flushAndEvict()) // the caller learns the edit could not be saved
        failing = false
        assertEquals("d", prefs(dir).getString("k", "d"))
    }
}
