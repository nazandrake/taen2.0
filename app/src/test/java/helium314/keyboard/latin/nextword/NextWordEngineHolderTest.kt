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

    @Test fun forgetReachesTheLoadedModelOnly() {
        val h = holder(good = true)
        h.forget() // nothing loaded: nothing to forget
        h.get()
        h.forget()
        assertEquals(1, models[0].forgets)
    }
}
