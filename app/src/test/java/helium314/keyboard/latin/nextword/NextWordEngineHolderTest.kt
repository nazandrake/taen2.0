// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** LM4: a model whose file does not match its hash at the first use is damaged and never loaded; the engine is freed by unload and loaded again later. */
class NextWordEngineHolderTest {
    private var verifies = 0
    private var loads = 0
    private val models = ArrayList<FakeModel>()

    private fun holder(good: Boolean, loadFails: Boolean = false) = NextWordEngineHolder({ verifies++; good }) {
        loads++
        if (loadFails) throw IllegalStateException("cannot load")
        fakeRanker(FakeModel(emptyMap()).also { models.add(it) })
    }

    @Test fun aFileThatDoesNotMatchItsHashIsDamagedAndNeverLoaded() {
        val h = holder(good = false)
        assertNull(h.get())
        assertNull(h.get())
        assertEquals(NextWordEngineHolder.State.DAMAGED, h.state)
        assertEquals(0, loads)
        assertEquals(1, verifies, "checked once, then remembered as damaged")
        val ok = holder(good = true)
        assertNotNull(ok.get(), "positive control: a verified file loads")
        assertEquals(1, loads)
    }

    @Test fun theHashIsCheckedOncePerProcessAndUnloadFreesTheEngine() {
        val h = holder(good = true)
        val first = h.get()
        assertSame(first, h.get(), "loaded once")
        assertEquals(NextWordEngineHolder.State.LOADED, h.state)
        h.unload()
        assertTrue(models[0].closed, "unload closes the model (its native memory)")
        assertEquals(NextWordEngineHolder.State.UNLOADED, h.state)
        assertNotNull(h.get())
        assertEquals(2, loads, "loaded again after the unload")
        assertEquals(1, verifies, "without a second hash check")
    }

    @Test fun aLoadThatFailsIsNotRetriedAndAVerifyThatThrowsIsDamage() {
        val h = holder(good = true, loadFails = true)
        assertNull(h.get())
        assertNull(h.get())
        assertEquals(NextWordEngineHolder.State.FAILED, h.state)
        assertEquals(1, loads)
        val throwing = NextWordEngineHolder({ throw java.io.IOException("unreadable") }) { fakeRanker(FakeModel(emptyMap())) }
        assertNull(throwing.get())
        assertEquals(NextWordEngineHolder.State.DAMAGED, throwing.state)
    }

    @Test fun everyLoadAsksForMemoryFirstAndShortMemoryIsTriedAgainLater() {
        var enough = false
        var asked = 0
        val h = NextWordEngineHolder({ true }, mayLoad = { asked++; enough }) { loads++; fakeRanker(FakeModel(emptyMap()).also { models.add(it) }) }
        assertNull(h.get(), "not enough memory now: not loaded")
        assertEquals(0, loads)
        assertEquals(NextWordEngineHolder.State.NO_MEMORY, h.state)
        enough = true
        assertNotNull(h.get(), "positive control: loaded once memory is free")
        assertEquals(1, loads)
        h.unload()
        enough = false
        assertNull(h.get(), "asked again before the next load")
        assertEquals(3, asked)
        assertEquals(1, loads)
    }

    @Test fun aMarkerIsSetBeforeEachLoadAndClearedOnlyWhenTheLoadReturns() {
        val events = ArrayList<String>()
        var fail = false
        var markerWritable = true
        val h = NextWordEngineHolder({ true }, { true }, beginLoad = { events.add("begin"); markerWritable }, endLoad = { events.add("end") }) {
            events.add("load")
            if (fail) throw IllegalStateException("cannot load")
            fakeRanker(FakeModel(emptyMap()))
        }
        assertNotNull(h.get())
        assertEquals(listOf("begin", "load", "end"), events, "a crash during the load leaves the marker behind")
        h.unload()
        events.clear()
        fail = true
        assertNull(h.get())
        assertEquals(listOf("begin", "load", "end"), events, "a load that throws ends in this process: the marker is cleared")
        assertEquals(NextWordEngineHolder.State.FAILED, h.state)
        val unmarked = NextWordEngineHolder({ true }, { true }, beginLoad = { markerWritable }, endLoad = {}) { loads++; fakeRanker(FakeModel(emptyMap())) }
        markerWritable = false
        assertNull(unmarked.get(), "a marker that cannot be written: not loaded (a crash would go unnoticed)")
        assertEquals(0, loads)
        assertEquals(NextWordEngineHolder.State.FAILED, unmarked.state)
    }

    @Test fun aMemoryCheckThatThrowsMeansNoLoad() {
        val h = NextWordEngineHolder({ true }, mayLoad = { throw IllegalStateException("no memory figure") }) { loads++; fakeRanker(FakeModel(emptyMap())) }
        assertNull(h.get())
        assertEquals(0, loads)
        assertEquals(NextWordEngineHolder.State.NO_MEMORY, h.state)
    }

    @Test fun forgetReachesTheLoadedModelOnly() {
        val h = holder(good = true)
        h.forget() // nothing loaded: nothing to forget
        h.get()
        h.forget()
        assertEquals(1, models[0].forgets)
    }
}
