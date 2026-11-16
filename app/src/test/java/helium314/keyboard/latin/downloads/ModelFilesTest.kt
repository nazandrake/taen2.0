// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.downloads

import helium314.keyboard.latin.nextword.NextWordGate
import java.io.File
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

    @Test fun theModelFileIsNamedByItsRow() {
        assertEquals(File(dir, "taen-models/model-en.onnx"), files.modelFile("model-en"))
        assertFalse(files.modelFile("model-en").exists())
    }
}
