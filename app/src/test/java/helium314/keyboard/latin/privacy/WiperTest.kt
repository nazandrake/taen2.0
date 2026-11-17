// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The process ends here: an Error, so no step of the wiper catches it (the wiper only handles exceptions). */
class ProcessKilled : Error("killed")

/** Ports whose steps take time on a fake clock and can be made to fail, and that remember what they were given. */
class ScriptedPorts(private val clock: () -> Long, private val advance: (Long) -> Unit) : WipePorts {
    val calls = ArrayList<String>()
    val pendings = ArrayList<Pending>()
    var armed: List<ArmedFile> = emptyList()
    var restored: List<ArmedFile>? = null
    var keysOk = true
    var keysThrow = false
    var filesOk = true
    var filesThrow = false
    var clipboardThrows = false
    var languageThrows = false
    var restoreOk = true
    var pendingWriteOk = true
    var lastScope: WipeScope? = null
    var lastKeep: Set<String>? = null
    var lastCeOnly: Boolean? = null
    /** The files of the two tiers and whether each Keystore key exists: a tiny model of what the real ports delete. */
    val ceFiles = mutableSetOf("learned_en.enc", "prefs.xml")
    val deFiles = mutableSetOf("dek_de.bin", "security.enc", "selfdestruct.enc", "activity.enc")
    var ceKey = true
    var deKey = true
    /** The language-only subtype registered with the system: present until a wipe clears it. */
    var systemLanguage = true
    var keyMillis = 40L
    var fileMillis = 300L
    /** The process ends when this call (0-based index in [calls]) starts: nothing of it happens. */
    var killAtCall = -1
    private fun call(name: String) { if (calls.size == killAtCall) throw ProcessKilled(); calls += name }
    override fun beginWipe() { call("beginWipe") }
    override fun readArmed(): List<ArmedFile> { call("readArmed"); return armed }
    override fun writePending(pending: Pending): Boolean { call("writePending"); pendings += pending; return pendingWriteOk }
    override fun deleteKeys(): Boolean { call("deleteKeys"); ceKey = false; deKey = false; advance(keyMillis); if (keysThrow) throw IllegalStateException("keystore"); return keysOk }
    override fun deleteFiles(scope: WipeScope, keep: Set<String>, ceOnly: Boolean): Boolean {
        call("deleteFiles"); lastScope = scope; lastKeep = keep; lastCeOnly = ceOnly
        ceFiles.removeAll { it !in keep }
        if (!ceOnly) deFiles.removeAll { it !in keep }
        advance(fileMillis); if (filesThrow) throw IllegalStateException("files"); return filesOk
    }
    override fun clearClipboard() { call("clearClipboard"); if (clipboardThrows) throw IllegalStateException("clip") }
    override fun clearSystemLanguage() { call("clearSystemLanguage"); systemLanguage = false; if (languageThrows) throw IllegalStateException("imm") }
    override fun restoreArmed(files: List<ArmedFile>): Boolean { call("restoreArmed"); restored = files; deFiles += files.map { it.name + ".enc" }; return restoreOk }
    override fun clearPending() { call("clearPending") }
    override fun restart() { call("restart") }
}

/** The wipe procedure: order, scope, failure handling, idempotence, the marker of an unfinished wipe. */
class WiperTest {
    private var now = 1_000L
    private fun ports() = ScriptedPorts({ now }, { now += it })
    private fun wiper(p: ScriptedPorts) = Wiper(p) { now }

    @Test fun aFullWipeRunsInTheDesignedOrder() {
        val p = ports()
        val report = wiper(p).run(WipeScope.ALL)
        assertEquals(listOf("beginWipe", "writePending", "deleteKeys", "writePending", "deleteFiles", "clearClipboard", "clearSystemLanguage", "clearPending", "restart"), p.calls)
        assertTrue(report.complete)
        assertEquals(WipeScope.ALL, p.lastScope)
        assertEquals(false, p.lastCeOnly)
        assertEquals(setOf(Wiper.PENDING_NAME), p.lastKeep)
    }

    @Test fun theKeysGoFirstAndTheTimesAreMeasured() {
        val p = ports()
        val report = wiper(p).run(WipeScope.ALL)
        assertEquals(40L, report.keysMillis) // from the start of the wipe to the end of the key deletion
        assertEquals(340L, report.totalMillis)
        assertTrue(report.keysMillis <= Wiper.KEY_TARGET_MILLIS && report.totalMillis <= Wiper.TOTAL_TARGET_MILLIS)
        assertEquals(200L, Wiper.KEY_TARGET_MILLIS)
        assertEquals(2000L, Wiper.TOTAL_TARGET_MILLIS)
    }

    @Test fun theMarkerIsWrittenBeforeTheKeysAreTouchedAndUpdatedOnceTheyAreGone() {
        val p = ports()
        wiper(p).run(WipeScope.ALL)
        assertEquals(listOf(Pending(WipeScope.ALL, armed = false, restored = false, keysDone = false), Pending(WipeScope.ALL, armed = false, restored = false, keysDone = true)), p.pendings)
    }

    @Test fun aPartialScopeKeepsTheKeysAndOnlyClearsWhatIsInScope() {
        val scope = WipeScope(learned = false, clipboard = true, settings = false, config = false, imported = false)
        val p = ports()
        wiper(p).run(scope)
        assertFalse("deleteKeys" in p.calls) // no crypto-shred: other stores must stay readable
        assertTrue("clearClipboard" in p.calls)
        assertEquals(scope, p.lastScope)
        val noClip = ports()
        wiper(noClip).run(WipeScope(learned = true, clipboard = false, settings = false, config = false, imported = false))
        assertFalse("clearClipboard" in noClip.calls)
        assertFalse("deleteKeys" in noClip.calls)
    }

    @Test fun everyScopeThatLeavesAStoreOutKeepsTheKeys() {
        for (scope in listOf(WipeScope(learned = false), WipeScope(clipboard = false), WipeScope(settings = false), WipeScope(imported = false))) {
            val p = ports()
            wiper(p).run(scope)
            assertFalse("deleteKeys" in p.calls, "$scope")
        }
    }

    @Test fun keepingTheConfigurationArmedSavesItBeforeTheKeysAndWritesItBackAfterTheFiles() {
        val p = ports()
        p.armed = listOf(ArmedFile("security", byteArrayOf(1, 2, 3)), ArmedFile("selfdestruct", byteArrayOf(4, 5)))
        val scope = WipeScope(config = false)
        val report = wiper(p).run(scope)
        assertEquals(listOf("beginWipe", "writePending", "readArmed", "deleteKeys", "writePending", "deleteFiles", "clearClipboard", "clearSystemLanguage", "restoreArmed", "writePending", "clearPending", "restart"), p.calls)
        assertEquals(listOf("security", "selfdestruct"), p.restored!!.map { it.name })
        assertContentEquals(byteArrayOf(4, 5), p.restored!![1].bytes)
        assertTrue(p.pendings.last().restored)
        assertTrue(p.pendings.all { it.armed })
        assertTrue(report.complete)
    }

    /** The language-only subtype Taen registered with the system names the language in use: every wipe clears it, after the files, before the restart. */
    @Test fun everyWipeClearsTheLanguageTheSystemShowsAfterTheFilesAndBeforeTheRestart() {
        for (scope in listOf(WipeScope.ALL, WipeScope(config = false), WipeScope(learned = true, clipboard = false, settings = false, config = false, imported = false))) {
            val p = ports()
            wiper(p).run(scope)
            val at = p.calls.indexOf("clearSystemLanguage")
            assertTrue(at > p.calls.indexOf("deleteFiles") && p.calls.indexOf("deleteFiles") >= 0, "$scope: ${p.calls}")
            assertTrue(at < p.calls.indexOf("restart"), "$scope: ${p.calls}")
        }
    }

    @Test fun aFailingClearOfTheSystemLanguageDoesNotStopTheWipe() {
        val p = ports()
        p.languageThrows = true
        val report = wiper(p).run(WipeScope.ALL)
        assertTrue("clearSystemLanguage" in p.calls)
        assertEquals(listOf("clearPending", "restart"), p.calls.takeLast(2)) // the user data is gone: the wipe finished
        assertTrue(report.complete)
    }

    @Test fun theConfigurationIsNotReadWhenItIsInScopeOrTheScopeIsPartial() {
        val a = ports(); wiper(a).run(WipeScope.ALL)
        assertFalse("readArmed" in a.calls || "restoreArmed" in a.calls)
        val b = ports(); wiper(b).run(WipeScope(config = false, learned = false)) // partial: nothing is destroyed that needs saving
        assertFalse("readArmed" in b.calls || "restoreArmed" in b.calls)
    }

    // ---- failures: whatever goes wrong, the keys and the files are still tried and the marker stays ---------------

    @Test fun aKeyDeletionThatFailsStillDeletesTheFilesAndKeepsTheMarker() {
        for (mode in listOf("false", "throws")) {
            val p = ports()
            if (mode == "false") p.keysOk = false else p.keysThrow = true
            val report = wiper(p).run(WipeScope.ALL)
            assertTrue("deleteFiles" in p.calls, mode)
            assertFalse("clearPending" in p.calls, mode)
            assertEquals("restart", p.calls.last(), mode)
            assertFalse(report.complete, mode)
            assertFalse(p.pendings.any { it.keysDone }, mode) // the marker never claims keys that are not gone
        }
    }

    @Test fun filesThatCouldNotBeDeletedKeepTheMarkerSoTheNextStartFinishesTheJob() {
        for (mode in listOf("false", "throws")) {
            val p = ports()
            if (mode == "false") p.filesOk = false else p.filesThrow = true
            val report = wiper(p).run(WipeScope.ALL)
            assertFalse("clearPending" in p.calls, mode)
            assertTrue("clearClipboard" in p.calls, mode) // the later steps still run
            assertEquals("restart", p.calls.last(), mode)
            assertFalse(report.complete, mode)
            assertTrue(p.pendings.last().keysDone, mode)
        }
    }

    @Test fun aFailingClipboardOrMarkerWriteDoesNotStopTheWipe() {
        val p = ports()
        p.clipboardThrows = true
        p.pendingWriteOk = false
        val report = wiper(p).run(WipeScope.ALL)
        assertTrue(p.calls.indexOf("deleteKeys") >= 0 && p.calls.indexOf("deleteFiles") > p.calls.indexOf("deleteKeys"))
        assertEquals("restart", p.calls.last())
        assertFalse(report.complete) // not being able to write the marker is not a clean finish
    }

    @Test fun aFailedRestoreOfTheConfigurationKeepsTheMarker() {
        val p = ports()
        p.armed = listOf(ArmedFile("security", byteArrayOf(1)))
        p.restoreOk = false
        val report = wiper(p).run(WipeScope(config = false))
        assertFalse("clearPending" in p.calls)
        assertFalse(p.pendings.last().restored)
        assertFalse(report.complete)
    }

    @Test fun aWipeRunTwiceIsTheSameAndNeverThrows() {
        val a = ports(); val b = ports()
        val w = wiper(a)
        w.run(WipeScope.ALL)
        a.calls.clear()
        w.run(WipeScope.ALL)
        wiper(b).run(WipeScope.ALL)
        assertEquals(b.calls, a.calls)
    }

    @Test fun nothingIsWrittenBeforeTheWritersAreStopped() {
        val p = ports()
        wiper(p).run(WipeScope.ALL)
        assertEquals("beginWipe", p.calls.first())
        val p2 = ports(); p2.armed = listOf(ArmedFile("security", byteArrayOf(1)))
        wiper(p2).run(WipeScope(config = false))
        assertEquals("beginWipe", p2.calls.first())
    }

    // ---- finishing an unfinished wipe at the next start ----------------------------------------------------------------

    /** Contract: a resumed wipe always deletes the files of BOTH tiers, whatever the marker says about the keys (a kill between the marker update and the deletion left the device-protected files). */
    @Test fun anUnfinishedWipeIsCompletedInBothTiersEvenWhenTheKeysAreGone() {
        val p = ports()
        val ok = wiper(p).resume(Pending(WipeScope.ALL, armed = false, restored = false, keysDone = true))
        assertEquals(listOf("deleteFiles", "clearSystemLanguage", "clearPending"), p.calls)
        assertEquals(false, p.lastCeOnly)
        assertEquals(WipeScope.ALL, p.lastScope)
        assertTrue(ok)
        assertEquals(emptySet(), p.ceFiles)
        assertEquals(emptySet(), p.deFiles)
        assertFalse("deleteKeys" in p.calls) // new keys may exist by now: they must never be destroyed again
    }

    @Test fun aWipeThatDiedBeforeTheKeysWereDeletedDeletesThemAndEverythingOnResume() {
        val p = ports()
        wiper(p).resume(Pending(WipeScope.ALL, armed = false, restored = false, keysDone = false))
        assertEquals(listOf("deleteKeys", "deleteFiles", "clearSystemLanguage", "clearPending"), p.calls)
        assertEquals(false, p.lastCeOnly)
    }

    @Test fun aPartialWipeThatDiedResumesWithoutKeys() {
        val p = ports()
        wiper(p).resume(Pending(WipeScope(learned = false), armed = false, restored = false, keysDone = false))
        assertEquals(listOf("deleteFiles", "clearSystemLanguage", "clearPending"), p.calls)
    }

    /** The language subtype is cleared by a resumed wipe too, after the files and before the marker goes (the order of the normal wipe), whether or not the wipe cleared it before it ended. */
    @Test fun aWipeThatEndedAfterTheFilesAreDeletedLeavesNoSubtypeOnceResumed() {
        val reference = ports()
        wiper(reference).run(WipeScope.ALL)
        val killAt = reference.calls.indexOf("clearSystemLanguage")
        assertTrue(killAt > reference.calls.indexOf("deleteFiles"), "positive control: the normal wipe clears the subtype after the files")
        val p = ports().also { it.killAtCall = killAt }
        try { wiper(p).run(WipeScope.ALL); throw AssertionError("not killed") } catch (_: ProcessKilled) { }
        assertTrue(p.systemLanguage, "the subtype is still registered when the process ends")
        assertTrue("deleteFiles" in p.calls && p.pendings.isNotEmpty())
        val next = ports().also { it.systemLanguage = p.systemLanguage }
        assertTrue(wiper(next).resume(p.pendings.last()))
        assertFalse(next.systemLanguage)
        assertTrue(next.calls.indexOf("clearSystemLanguage") > next.calls.indexOf("deleteFiles"))
        assertTrue(next.calls.indexOf("clearSystemLanguage") < next.calls.indexOf("clearPending"))
        val failing = ports().also { it.languageThrows = true }
        assertTrue(wiper(failing).resume(p.pendings.last())) // a failing clear does not keep the marker (as in the normal wipe)
        assertTrue("clearPending" in failing.calls)
    }

    @Test fun resumeKeepsTheRestoredConfigurationFilesAndTheMarkerKeepsWhenItCannotFinish() {
        val p = ports()
        wiper(p).resume(Pending(WipeScope(config = false), armed = true, restored = true, keysDone = true))
        assertEquals(Wiper.ARMED_KEEP + Wiper.PENDING_NAME, p.lastKeep)
        val stuck = ports(); stuck.filesOk = false
        assertFalse(wiper(stuck).resume(Pending(WipeScope.ALL, armed = false, restored = false, keysDone = true)))
        assertFalse("clearPending" in stuck.calls)
        val lost = ports()
        wiper(lost).resume(Pending(WipeScope(config = false), armed = true, restored = false, keysDone = true)) // the saved copy is gone with the process
        assertEquals(setOf(Wiper.PENDING_NAME), lost.lastKeep)
    }

    @Test fun theMarkerHasAFixedFormAndADamagedOneMeansEverything() {
        val p = Pending(WipeScope(learned = true, clipboard = false, settings = true, config = false, imported = true), armed = true, restored = false, keysDone = true)
        assertEquals(p, Pending.decode(p.encode()))
        assertEquals(Pending(WipeScope.ALL, armed = false, restored = false, keysDone = true), Pending.decode(WipeScope.ALL.let { Pending(it, false, false, true) }.encode()))
        assertEquals(Pending(WipeScope.ALL, armed = false, restored = false, keysDone = false), Pending.decode(Pending(WipeScope.ALL, false, false, false).encode())) // a valid marker with the keys not gone
        assertNotNull(Pending.decode(p.encode()))
    }

    /** What a damaged or unknown marker (anyone with file access can write one) makes the next start DO: destroy both keys and delete both tiers. */
    private val damagedMarkers = listOf(ByteArray(0), byteArrayOf(1, 2, 3), "TWP1xxxxxxxx".toByteArray(), "TWP2" .toByteArray() + ByteArray(8) { '1'.code.toByte() },
        Pending(WipeScope.ALL, false, false, true).encode().let { it.copyOf(it.size - 1) }, Pending(WipeScope.ALL, false, false, true).encode() + byteArrayOf(0))

    @Test fun aDamagedMarkerIsResumedAsEverythingIncludingBothKeysAndBothTiers() {
        for (bad in damagedMarkers) {
            val p = ports()
            assertTrue(wiper(p).resume(Pending.decode(bad)))
            assertEquals(listOf("deleteKeys", "deleteFiles", "clearSystemLanguage", "clearPending"), p.calls) // the keys are destroyed too: it is not known that they are gone
            assertEquals(WipeScope.ALL, p.lastScope)
            assertEquals(false, p.lastCeOnly) // device-protected files go as well
            assertEquals(setOf(Wiper.PENDING_NAME), p.lastKeep)
        }
        val control = ports() // positive control: the same marker undamaged, keys gone, both tiers are still deleted
        wiper(control).resume(Pending.decode(Pending(WipeScope.ALL, false, false, true).encode()))
        assertEquals(listOf("deleteFiles", "clearSystemLanguage", "clearPending"), control.calls)
        assertEquals(false, control.lastCeOnly) // both tiers, also when the keys are gone
    }

    // ---- the window between the trigger and the marker --------------------------------------------------------------

    @Test fun theMarkerIsTheFirstThingWrittenBeforeTheConfigurationIsReadOrAnyKeyTouched() {
        val p = ports()
        p.armed = listOf(ArmedFile("security", byteArrayOf(1)))
        wiper(p).run(WipeScope(config = false))
        assertEquals(listOf("beginWipe", "writePending"), p.calls.take(2))
        assertTrue(p.calls.indexOf("readArmed") > p.calls.indexOf("writePending"))
    }

    @Test fun aKillAtAnyPointAfterTheMarkerLeavesAWipeTheNextStartFinishes() {
        for (scope in listOf(WipeScope.ALL, WipeScope(config = false))) {
            val reference = ports().also { it.armed = listOf(ArmedFile("security", byteArrayOf(1))) }
            wiper(reference).run(scope)
            val firstMarker = reference.calls.indexOf("writePending")
            for (killAt in firstMarker + 1 until reference.calls.size) {
                val p = ports().also { it.armed = reference.armed; it.killAtCall = killAt }
                try { wiper(p).run(scope); throw AssertionError("not killed at $killAt") } catch (_: ProcessKilled) { }
                val marker = p.pendings.lastOrNull()
                assertNotNull(marker, "scope $scope, killed at call $killAt (${reference.calls[killAt]}): no marker yet")
                val next = ports()
                next.ceFiles.clear(); next.ceFiles += p.ceFiles // the disk state the killed process left
                next.deFiles.clear(); next.deFiles += p.deFiles
                next.ceKey = p.ceKey; next.deKey = p.deKey
                val where = "scope $scope, killed at $killAt (${reference.calls[killAt]})"
                assertTrue(wiper(next).resume(marker), where)
                // whatever was done before the kill, the keys are gone after the resume; keys the marker says are gone are not deleted again
                assertTrue("deleteKeys" in p.calls || "deleteKeys" in next.calls, where)
                if (marker.keysDone) assertFalse("deleteKeys" in next.calls, where)
                assertTrue("deleteFiles" in next.calls, where)
                assertEquals(false, next.lastCeOnly, where)
                // no file of either tier is left, except the written-back configuration the marker says is restored
                assertEquals(emptySet(), next.ceFiles, where)
                assertEquals(if (marker.armed && marker.restored) setOf("security.enc") else emptySet(), next.deFiles, where)
                assertFalse(next.ceKey || next.deKey, where)
            }
        }
    }
}
