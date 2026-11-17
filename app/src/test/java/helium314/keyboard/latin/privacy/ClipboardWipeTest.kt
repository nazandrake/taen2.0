// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.io.File
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P10 with P15: a pinned entry kept Forever leaves the clipboard history with every self-destruct trigger (the engine's wipe deletes its
 * store) and with a screen lock (memory only; the store stays for the unlock).
 */
class ClipboardWipeTest {
    private var now = 1_800_000_000_000L
    private val day = 24 * 60 * 60_000L
    private val hasher = CountingHasher(ExportCodec.Kdf(memoryKiB = 64, iterations = 1, lanes = 1))
    private val word = "Blue#Horse-4711"
    private val config = SelfDestructConfig(
        duress = SecretRecords.create("9999".toCharArray(), hasher, SecureRandom()),
        word = SecretRecords.create(word.toCharArray(), hasher, SecureRandom()), wrongPins = 10, inactivityDays = 30,
    )
    private val forever = ClipboardLimits.of(ClipboardLimits.FOREVER_MINUTES, 50)

    /** A history with a pinned and an unpinned entry, kept Forever, as it is stored. */
    private fun keptEntries(): List<ClipboardEntry> {
        val h = ClipboardHistory(emptyList(), { now }, { forever }) { }
        h.add("pinned forever", now - 2000)
        h.togglePinned(h.entries.first { it.text == "pinned forever" }.id)
        h.add("unpinned forever", now - 1000)
        return h.entries.toList()
    }

    /** The scope each trigger wipes with, through the engine (the panic as confirmed). */
    private fun scopeOf(trigger: Trigger): WipeScope {
        val configs = MemoryConfigStorage().also { it.save(config) }
        val activity = MemoryActivityStorage()
        val effects = RecordingEffects()
        val engine = SelfDestructEngine(configs, activity, SelfDestructPolicy { now }, { now }, TriggerMatcher(hasher), effects)
        when (trigger) {
            Trigger.DURESS_PIN -> engine.onPin(AppLock.Verify.Duress)
            Trigger.WRONG_PINS -> engine.onPin(AppLock.Verify.Wrong(10))
            Trigger.TRIGGER_WORD -> assertNotNull(engine.prepareCheck(CommitKey.ENTER, FieldContext(false, false, noSeparators)) { "text $word" }).run()
            Trigger.INACTIVITY -> { activity.save(now - 31 * day); engine.checkInactivity() }
            Trigger.PANIC -> engine.panic(confirmed = true)
        }
        assertEquals(listOf("wipe:$trigger"), effects.events, "$trigger wipes")
        return assertNotNull(effects.wipedScope)
    }

    @Test fun everyTriggerDeletesTheStoreOfAPinnedEntryKeptForever() {
        for (trigger in Trigger.entries) {
            val ce = tempDir()
            val store = File(ce, "no_backup/taen/clipboard.enc").apply { parentFile.mkdirs(); writeBytes(ClipboardCodec.encode(keptEntries())) }
            val stored = ClipboardCodec.decode(store.readBytes())
            assertEquals(listOf(true, false), stored.map { it.pinned }, "positive control: the store holds the pinned entry ($trigger)")
            FileWiper(ce, tempDir()).delete(scopeOf(trigger), setOf(Wiper.PENDING_NAME), ceOnly = false)
            assertFalse(store.exists(), "$trigger left the clipboard store")
        }
    }

    @Test fun aScreenLockDropsPinnedAndForeverEntriesFromMemoryAndLeavesTheStore() {
        var saves = 0
        val h = ClipboardHistory(keptEntries(), { now }, { forever }) { saves++ }
        assertEquals(2, h.size, "positive control: both entries are loaded")
        h.evict()
        assertEquals(0, h.size)
        h.add("after the lock", now)
        assertEquals(0, h.size, "nothing is taken while locked")
        assertEquals(0, saves, "the store is not written: the unlock reads it again")
    }

    @Test fun clearingTheHistoryRemovesPinnedAndForeverEntries() {
        val saved = ArrayList<List<ClipboardEntry>>()
        val h = ClipboardHistory(keptEntries(), { now }, { forever }) { saved += it.toList() }
        assertTrue(h.entries.any { it.pinned }, "positive control: a pinned entry is there")
        h.clearAll()
        assertEquals(0, h.size)
        assertEquals(emptyList(), saved.last())
    }
}
