// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipboardHistoryTest {
    private var now = 1_000_000_000L
    private var saved: List<ClipboardEntry> = emptyList()
    private var saves = 0
    private var pinnedFirst = true
    private val limits = ClipboardLimits.of(retentionMinutes = 60, maxItems = 50)
    private val hour = 60 * 60_000L

    private fun history(initial: List<ClipboardEntry> = emptyList(), limits: ClipboardLimits = this.limits) =
        ClipboardHistory(initial, { now }, { limits }, { pinnedFirst }) { saved = it.toList(); saves++ }

    // ---- core behaviour -------------------------------------------------------------------------------

    @Test fun addedTextIsStoredNewestFirst() {
        val h = history()
        h.add("first", now - 2000)
        h.add("second", now - 1000)
        assertEquals(listOf("second", "first"), h.entries.map { it.text })
        assertEquals(listOf("second", "first"), saved.map { it.text })
    }

    @Test fun entryExpiresAfterRetention() {
        val h = history()
        h.add("old", now)
        now += 60 * 60_000L - 1
        assertEquals(0, h.expire())
        now += 2
        assertEquals(1, h.expire())
        assertEquals(emptyList(), saved)
    }

    // ---- boundaries, limits, pinning, listener positions, capture policy, codec -----------------------

    @Test fun expiryBoundaryIsExactToTheMillisecond() { // P10: -1 ms kept, the retention time itself and +1 ms expired
        for ((offset, expired) in listOf(-1L to false, 0L to true, 1L to true)) {
            val h = history()
            h.add("x", now)
            now += hour + offset
            assertEquals(if (expired) 1 else 0, h.expire(), "offset $offset")
            assertEquals(if (expired) 0 else 1, h.size, "offset $offset")
        }
    }

    @Test fun pinnedEntriesNeverExpire() {
        val h = history()
        h.add("keep", now)
        h.togglePinned(h[0].id)
        h.add("drop", now)
        now += 100 * hour
        assertEquals(1, h.expire())
        assertEquals(listOf("keep"), h.entries.map { it.text })
    }

    @Test fun unpinningRestartsTheRetentionTime() {
        val h = history()
        h.add("x", now)
        h.togglePinned(h[0].id)
        now += 10 * hour
        h.togglePinned(h[0].id) // unpinned now
        now += hour - 1
        assertEquals(0, h.expire())
        now += 1
        assertEquals(1, h.expire())
    }

    @Test fun maxItemsDropsTheOldestUnpinnedEntries() {
        val h = history(limits = ClipboardLimits.of(60, 3))
        for (i in 1..5) h.add("t$i", now - 10_000L + i)
        assertEquals(listOf("t5", "t4", "t3"), h.entries.map { it.text })
        assertEquals(listOf("t5", "t4", "t3"), saved.map { it.text })
    }

    @Test fun pinnedEntriesDoNotCountAgainstMaxItems() {
        val h = history(limits = ClipboardLimits.of(60, 2))
        h.add("pinned", now - 5000)
        h.togglePinned(h[0].id)
        h.add("a", now - 3000); h.add("b", now - 2000); h.add("c", now - 1000)
        assertEquals(listOf("pinned", "c", "b"), h.entries.map { it.text })
    }

    @Test fun sameTextMovesToTheTopInsteadOfDuplicating() {
        val h = history()
        h.add("a", now - 3000); h.add("b", now - 2000)
        h.add("a", now - 1000)
        assertEquals(listOf("a", "b"), h.entries.map { it.text })
        assertEquals(now - 1000, h[0].timestamp)
        val savesBefore = saves
        h.add("a", now - 1000) // identical copy event: nothing changes, nothing is written
        assertEquals(savesBefore, saves)
        assertEquals(2, h.size)
    }

    @Test fun futureTimestampCannotExtendRetention() {
        val h = history()
        h.add("x", now + 100 * hour)
        assertEquals(now, h[0].timestamp)
        now += hour
        assertEquals(1, h.expire())
    }

    @Test fun clipCopiedBeforeTheRetentionTimeIsNotStored() {
        val h = history()
        h.add("old", now - hour)
        assertEquals(0, h.size)
        h.add("fresh", now - hour + 1) // positive control
        assertEquals(1, h.size)
    }

    @Test fun emptyAndOverlongTextIsIgnored() {
        val h = history()
        h.add("", now)
        h.add("x".repeat(ClipboardCapture.MAX_TEXT_LENGTH + 1), now)
        assertEquals(0, h.size)
        assertEquals(0, saves)
        h.add("x".repeat(ClipboardCapture.MAX_TEXT_LENGTH), now) // positive control: the limit itself is allowed
        assertEquals(1, h.size)
    }

    @Test fun pinnedLastWhenTheSettingSaysSo() {
        val h = history()
        h.add("p", now - 3000); h.togglePinned(h[0].id)
        h.add("n", now - 2000)
        assertEquals(listOf("p", "n"), h.entries.map { it.text })
        pinnedFirst = false
        h.sort()
        assertEquals(listOf("n", "p"), h.entries.map { it.text })
    }

    @Test fun clearNonPinnedKeepsPinnedAndClearAllRemovesEverything() {
        val h = history()
        h.add("p", now - 3000); h.togglePinned(h[0].id)
        h.add("n", now - 2000)
        h.clearNonPinned()
        assertEquals(listOf("p"), saved.map { it.text })
        h.clearAll()
        assertEquals(0, h.size)
        assertEquals(emptyList(), saved)
    }

    @Test fun removeAtPersists() {
        val h = history()
        h.add("a", now - 2000); h.add("b", now - 1000)
        h.removeAt(0)
        assertEquals(listOf("a"), saved.map { it.text })
    }

    private class Events : ClipboardHistory.Listener {
        val log = ArrayList<String>()
        override fun onInserted(position: Int) { log.add("ins $position") }
        override fun onRemoved(position: Int, count: Int) { log.add("rem $position $count") }
        override fun onMoved(oldPosition: Int, newPosition: Int) { log.add("mov $oldPosition $newPosition") }
    }

    @Test fun listenerSeesPositionsOfTheSortedList() {
        val h = history()
        val events = Events().also { h.listener = it }
        h.add("a", now - 3000)
        h.add("b", now - 2000)
        assertEquals(listOf("ins 0", "ins 0"), events.log) // newest goes to the top
        events.log.clear()
        h.add("a", now - 1000) // moves from position 1 to 0
        assertEquals(listOf("mov 1 0"), events.log)
        events.log.clear()
        h.togglePinned(h[1].id) // "b" pinned: moves to the top
        assertEquals(listOf("mov 1 0"), events.log)
    }

    @Test fun expiryRemovesFromTheEndSoPositionsStayValid() {
        val h = history(listOf(
            ClipboardEntry(1, now - 3, false, "a"), ClipboardEntry(2, now - 2, false, "b"), ClipboardEntry(3, now - 1, false, "c"),
        ), ClipboardLimits.of(5, 50))
        val events = Events().also { h.listener = it }
        now += 5 * 60_000L - 2 // a (age retention+1) and b (age == retention) expire, c (age retention-1) stays
        assertEquals(2, h.expire())
        assertEquals(listOf("rem 2 1", "rem 1 1"), events.log) // list is c, b, a: positions 2 then 1
        assertEquals(listOf("c"), h.entries.map { it.text })
    }

    @Test fun nextExpiryIsTheEarliestUnpinned() {
        val h = history()
        assertNull(h.nextExpiry())
        h.add("p", now - 5000); h.togglePinned(h[0].id)
        assertNull(h.nextExpiry()) // only pinned
        h.add("a", now - 4000); h.add("b", now - 1000)
        assertEquals(now - 4000 + hour, h.nextExpiry())
    }

    @Test fun limitsAreClampedToTheDesignRange() {
        val low = ClipboardLimits.of(retentionMinutes = 1, maxItems = 0)
        assertEquals(5 * 60_000L, low.retentionMillis)
        assertEquals(1, low.maxItems)
        val high = ClipboardLimits.of(retentionMinutes = Int.MAX_VALUE, maxItems = 9999)
        assertEquals(7 * 24 * 60 * 60_000L, high.retentionMillis)
        assertEquals(500, high.maxItems)
        val ok = ClipboardLimits.of(90, 50)
        assertEquals(90 * 60_000L, ok.retentionMillis)
        assertEquals(50, ok.maxItems)
    }

    @Test fun idsStayUniqueAfterReload() {
        val h = history(listOf(ClipboardEntry(7, now, false, "a")))
        h.add("b", now)
        assertEquals(listOf(7L, 8L), h.entries.map { it.id }.sorted())
    }

    // ---- capture policy -----------------------------------------------------------------------------------------

    private fun capture(
        enabled: Boolean = true, available: Boolean = true, sensitive: Boolean? = null, secure: Boolean = false, text: CharSequence? = "text",
    ) = ClipboardCapture.shouldStore(enabled, available, sensitive, secure, text)

    @Test fun captureDecisionTable() {
        assertTrue(capture()) // positive control: a normal copy is stored
        assertTrue(capture(sensitive = false))
        assertFalse(capture(sensitive = true)) // EXTRA_IS_SENSITIVE
        assertFalse(capture(secure = true)) // password or no-learning field focused
        assertFalse(capture(enabled = false))
        assertFalse(capture(available = false)) // locked device: no store
        assertFalse(capture(text = null))
        assertFalse(capture(text = ""))
        assertTrue(capture(text = "x".repeat(ClipboardCapture.MAX_TEXT_LENGTH)))
        assertFalse(capture(text = "x".repeat(ClipboardCapture.MAX_TEXT_LENGTH + 1)))
        assertFalse(capture(sensitive = true, secure = true, enabled = false)) // all reasons together
    }

    // the decision of PrivacyPolicy, the single owner (these cases were once ClipboardCapture's own)
    private fun secureField(inputType: Int, imeOptions: Int) =
        !PrivacyPolicy.decide(PrivacyPolicy.Input(inputType, imeOptions, null, false, emptySet(), false)).storeClipboard

    @Test fun secureFieldDetection() { // input types from android.text.InputType: class | variation
        val text = 0x1
        val passwordVariations = mapOf("text password" to (text or 0x80), "visible password" to (text or 0x90), "web password" to (text or 0xe0), "number password" to (0x2 or 0x10))
        for ((name, type) in passwordVariations) assertTrue(secureField(type, 0), name)
        assertTrue(secureField(text, 0x1000000)) // IME_FLAG_NO_PERSONALIZED_LEARNING
        assertTrue(secureField(text or 0x80, 0x1000000 or 0x6)) // flags combined with an action id
        // normal fields (positive controls for the checks above)
        for (type in listOf(text, text or 0x20 /* email */, text or 0x40 /* subject */, text or 0x2000 /* multi line flag */, 0x2 /* number */, 0x3 /* phone */, 0))
            assertFalse(secureField(type, 0), "type $type")
        assertFalse(secureField(text, 0x6 /* send action */ or 0x10000000 /* other flag */))
    }

    // ---- codec ----------------------------------------------------------------------------------------------------

    @Test fun codecRoundTripAndRejection() {
        val entries = listOf(ClipboardEntry(1, 5, true, "ünï ключ\n"), ClipboardEntry(2, 9, false, ""))
        val back = ClipboardCodec.decode(ClipboardCodec.encode(entries))
        assertEquals(entries.map { listOf(it.id, it.timestamp, it.pinned, it.text) }, back.map { listOf(it.id, it.timestamp, it.pinned, it.text) })
        val bytes = ClipboardCodec.encode(entries)
        assertFailsWith<CorruptStoreException> { ClipboardCodec.decode(bytes.copyOf(bytes.size - 1)) }
        assertFailsWith<CorruptStoreException> { ClipboardCodec.decode(bytes + byteArrayOf(1)) }
        assertFailsWith<CorruptStoreException> { ClipboardCodec.decode(byteArrayOf(0, 0, 0, 1, 0x7f, 0, 0, 0)) }
        assertFailsWith<CorruptStoreException> { ClipboardCodec.decode(byteArrayOf(0, 0, 0, 9, 0, 0, 0, 0)) }
    }

    // ---- the lock wipes the list from memory ---------------------------------------------------------------

    @Test fun evictedHistoryIsEmptyTellsTheListenerWritesNothingAndRefusesNewTexts() {
        val removed = ArrayList<Pair<Int, Int>>()
        val h = history()
        h.listener = object : ClipboardHistory.Listener {
            override fun onInserted(position: Int) {}
            override fun onRemoved(position: Int, count: Int) { removed.add(position to count) }
            override fun onMoved(oldPosition: Int, newPosition: Int) {}
        }
        h.add("one", now); h.add("two", now - 1)
        val savesBefore = saves
        h.evict()
        assertEquals(emptyList(), h.entries.map { it.text })
        assertEquals(listOf(0 to 2), removed)
        assertEquals(savesBefore, saves) // the stored copy is not touched by the wipe
        assertEquals(2, saved.size)
        h.add("while locked", now)
        h.expire(); h.clearNonPinned(); h.clearAll()
        assertEquals(emptyList(), h.entries.map { it.text })
        assertEquals(savesBefore, saves)
        h.evict() // twice is harmless
        assertEquals(listOf(0 to 2), removed)
    }
}
