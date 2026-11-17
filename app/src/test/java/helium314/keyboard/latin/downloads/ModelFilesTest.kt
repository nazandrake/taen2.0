// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.downloads

import helium314.keyboard.latin.nextword.NextWordGate
import helium314.keyboard.latin.nextword.NextWordModels
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The model folder: the model file name of a row, and the speed result that lives next to it (unreadable = no result, removed with the model). */
class ModelFilesTest {
    private val dir: File = Files.createTempDirectory(File("build").apply { mkdirs() }.toPath(), "models").toFile()
    private val files = ModelFiles(File(dir, "taen-models"))

    @Test fun theSpeedResultIsStoredReadAndForgotten() {
        assertNull(files.speed("model-bg"), "none before the test")
        assertTrue(files.storeSpeed("model-bg", NextWordGate.Speed(120, 860, 2100)))
        val read = files.speed("model-bg")!!
        assertEquals(Triple(120L, 860L, 2100L), Triple(read.p95Ms, read.extraMb, read.freeMb))
        assertEquals(listOf("model-bg.speed"), File(dir, "taen-models").list()!!.toList(), "no temporary file is left")
        assertNull(files.speed("model-en"), "the result belongs to its own row")
        assertTrue(files.forgetSpeed("model-bg"))
        assertNull(files.speed("model-bg"))
        assertTrue(files.forgetSpeed("model-bg"), "forgetting a result that is not there is fine")
    }

    @Test fun aDamagedOrOversizedResultIsNoResult() {
        File(dir, "taen-models").mkdirs()
        File(dir, "taen-models/model-bg.speed").writeText("p95=12 mb=")
        assertNull(files.speed("model-bg"))
        File(dir, "taen-models/model-bg.speed").writeText("p95=12 mb=3 free=2000" + " ".repeat(100))
        assertNull(files.speed("model-bg"), "longer than any real result")
        File(dir, "taen-models/model-bg.speed").writeText("p95=12 mb=3\n")
        assertNull(files.speed("model-bg"), "a result without the free memory is no result")
        File(dir, "taen-models/model-bg.speed").writeText("p95=12 mb=3 free=2000\n")
        assertEquals(12L, files.speed("model-bg")!!.p95Ms, "positive control: a trailing line end is fine")
    }

    @Test fun aLoadingMarkerOfAnEndedProcessMeansTheLoadCrashed() {
        assertTrue(files.storeSpeed("model-bg", NextWordGate.Speed(120, 860, 2100)))
        assertTrue(files.storeSpeed("model-en", NextWordGate.Speed(90, 340, 2100)))
        assertTrue(files.markLoading("model-bg", "pid=1 start=5"))
        files.settleCrashedLoads("pid=1 start=5")
        assertFalse(files.loadCrashed("model-bg"), "the marker of this process: a load in progress, not a crash")
        assertEquals(120L, files.speed("model-bg")!!.p95Ms)
        // the process ended during the load (a low-memory kill): the next process finds the marker
        files.settleCrashedLoads("pid=2 start=9")
        assertTrue(files.loadCrashed("model-bg"))
        assertNull(files.speed("model-bg"), "the stored pass is gone: a new speed test is needed")
        assertEquals(90L, files.speed("model-en")!!.p95Ms, "positive control: the other model is untouched")
        assertFalse(files.loadCrashed("model-en"))
        files.settleCrashedLoads("pid=3 start=1")
        assertTrue(files.loadCrashed("model-bg"), "it stays crashed until a new speed test is stored")
        assertTrue(files.storeSpeed("model-bg", NextWordGate.Speed(130, 860, 2100)))
        assertFalse(files.loadCrashed("model-bg"), "a new speed test clears it")
    }

    @Test fun aLoadThatReturnedClearsItsMarkerAndRemoveClearsEverything() {
        assertTrue(files.markLoading("model-bg", "pid=1 start=5"))
        files.clearLoading("model-bg")
        files.settleCrashedLoads("pid=2 start=9")
        assertFalse(files.loadCrashed("model-bg"), "a load that returned leaves no marker")
        assertTrue(files.markLoading("model-bg", "pid=1 start=5"))
        files.settleCrashedLoads("pid=2 start=9")
        assertTrue(files.loadCrashed("model-bg"), "positive control")
        assertTrue(files.forgetSpeed("model-bg"))
        assertFalse(files.loadCrashed("model-bg"), "Remove clears the crash record with the result")
        assertEquals(emptyList(), File(dir, "taen-models").list()!!.toList())
    }

    @Test fun twoLoadsOfOneModelNeverOverlapSoAKillDuringTheSecondIsACrash() {
        // the speed test (settings page) and the keyboard each have their own ModelFiles on the same folder
        val speedTest = ModelFiles(File(dir, "taen-models"))
        val keyboard = ModelFiles(File(dir, "taen-models"))
        assertTrue(speedTest.storeSpeed("model-bg", NextWordGate.Speed(120, 860, 2100)))
        val firstMarked = CountDownLatch(1)
        val firstMayEnd = CountDownLatch(1)
        val first = thread {
            speedTest.markLoading("model-bg", "pid=1 start=5")
            firstMarked.countDown()
            firstMayEnd.await()
            speedTest.clearLoading("model-bg") // the speed test ends
        }
        assertTrue(firstMarked.await(5, TimeUnit.SECONDS))
        val secondMarked = CountDownLatch(1)
        thread {
            keyboard.markLoading("model-bg", "pid=1 start=5")
            secondMarked.countDown() // and then the process is killed during this load: the marker is never cleared
        }
        assertFalse(secondMarked.await(300, TimeUnit.MILLISECONDS), "the second load waits while the first one runs")
        firstMayEnd.countDown()
        first.join(5_000)
        assertTrue(secondMarked.await(5, TimeUnit.SECONDS), "it loads once the first one has ended")
        // the next process finds the marker of the killed load
        val restarted = ModelFiles(File(dir, "taen-models"))
        restarted.settleCrashedLoads("pid=2 start=9")
        val model = NextWordModels.BULGARIAN
        assertEquals(NextWordGate.Readiness.LOAD_CRASHED, NextWordGate.readiness(model, true, restarted.speed(model.id), restarted.loadCrashed(model.id)))
    }

    @Test fun anUnwritableMarkerIsReported() {
        File(dir, "taen-models").writeText("a file where the folder should be")
        assertFalse(files.markLoading("model-bg", "pid=1 start=5"))
    }

    @Test fun theModelFileIsNamedByItsRow() {
        assertEquals(File(dir, "taen-models/model-en.onnx"), files.modelFile("model-en"))
        assertFalse(files.modelFile("model-en").exists())
    }
}
