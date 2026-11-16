// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword

import helium314.keyboard.latin.nextword.NextWordGate.Readiness
import helium314.keyboard.latin.nextword.NextWordGate.Speed
import helium314.keyboard.latin.nextword.engine.PreSplit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where the model may run: the speed limits of the contract (150 ms at p95, 300 MB), the readiness of a model on this phone and the field gate. */
class NextWordGateTest {
    private fun model(eachPhone: Boolean, adopted: Boolean) =
        NextWordModel("model-x", "xx", "nextword/x", PreSplit.QWEN2, eachPhone, Reference(100, 200, 90, 250, adopted))

    @Test fun thePhoneTestPassesWithin250MsAndWithTheModelPlus1GbFree() {
        assertEquals(250L, NextWordGate.PHONE_MAX_P95_MS)
        assertEquals(1024L, NextWordGate.PHONE_MARGIN_MB)
        assertTrue(Speed(250, 860, 860 + 1024).passes, "both limits are inclusive")
        assertFalse(Speed(251, 860, 4000).passes, "too slow")
        assertFalse(Speed(100, 860, 860 + 1023).passes, "1 MB short of the margin")
    }

    @Test fun eachPartOfThePhoneTestFailsOnItsOwnAndUnmeasuredValuesFail() {
        assertTrue(Speed(0, 0, 1024).passes, "positive control: the smallest passing values")
        assertEquals(true to false, Speed(250, 0, 1023).let { it.fastEnough to it.enoughMemory })
        assertEquals(false to true, Speed(251, 0, 1024).let { it.fastEnough to it.enoughMemory })
        assertFalse(Speed(-1, 10, 4000).passes, "a negative time is not a measurement")
        assertFalse(Speed(10, -1, 4000).enoughMemory, "a negative memory figure is not a measurement")
        assertFalse(Speed(10, 0, -1).enoughMemory, "free memory that could not be read")
        assertFalse(Speed(10, NextWordSpeedTest.UNKNOWN_MB, 999_999_999).enoughMemory, "added memory that could not be read never passes")
        // the 150 ms / 300 MB reference limits of the reference phone are not the phone test's: a step of 200 ms and 800 MB added pass with enough free
        assertTrue(Speed(200, 800, 1824).passes)
    }

    @Test fun aStoredSpeedReadsBackAndAnythingElseIsNoResult() {
        val s = Speed.decode(Speed(123, 456, 2000).encode())!!
        assertEquals(Triple(123L, 456L, 2000L), Triple(s.p95Ms, s.extraMb, s.freeMb))
        // "p95=1 mb=2" is the format before the free memory was recorded: no result, so the test runs again
        for (bad in listOf(null, "", "p95=1", "p95=x mb=2", "p95=1 mb=2", "p95=1 mb=2 free=3 extra", "p95=-1 mb=2 free=3", "p95=1 mb=2 free=-3",
                "p95=1234567890 mb=1 free=1", "mb=2 p95=1 free=3"))
            assertNull(Speed.decode(bad), "$bad")
    }

    @Test fun readinessFollowsInstallGateAndSpeed() {
        val perPhone = model(eachPhone = true, adopted = true)
        assertEquals(Readiness.NOT_INSTALLED, NextWordGate.readiness(perPhone, installed = false, speed = Speed(10, 10, 4000)))
        assertEquals(Readiness.NEEDS_SPEED_TEST, NextWordGate.readiness(perPhone, true, null))
        assertEquals(Readiness.READY, NextWordGate.readiness(perPhone, true, Speed(250, 860, 1884)))
        assertEquals(Readiness.TOO_SLOW, NextWordGate.readiness(perPhone, true, Speed(251, 860, 4000)))
        assertEquals(Readiness.TOO_SLOW, NextWordGate.readiness(perPhone, true, Speed(100, 860, 1883)))
        // a model that passed the reference phone needs no test here; one that failed the reference check is never ready, whatever this phone measured
        assertEquals(Readiness.READY, NextWordGate.readiness(model(eachPhone = false, adopted = true), true, null))
        assertEquals(Readiness.DISABLED_BY_GATE, NextWordGate.readiness(model(eachPhone = false, adopted = false), true, Speed(1, 1, 4000)))
        assertEquals(Readiness.DISABLED_BY_GATE, NextWordGate.readiness(model(eachPhone = true, adopted = false), true, Speed(1, 1, 4000)))
    }

    @Test fun theModelRunsOnlyWhenEveryConditionHolds() {
        assertTrue(NextWordGate.runs(true, true, true, true, Readiness.READY), "positive control: a normal field with a ready model")
        assertFalse(NextWordGate.runs(false, true, true, true, Readiness.READY), "model switch off")
        assertFalse(NextWordGate.runs(true, false, true, true, Readiness.READY), "no suggestion strip")
        assertFalse(NextWordGate.runs(true, true, false, true, Readiness.READY), "next-word predictions off")
        assertFalse(NextWordGate.runs(true, true, true, false, Readiness.READY), "password, incognito, no-learning or locked field")
        for (r in Readiness.entries - Readiness.READY) assertFalse(NextWordGate.runs(true, true, true, true, r), "$r")
    }

    @Test fun theShippedReferenceNumbersAreTheMeasurementsAndAdoptedFollowsTheGate() {
        // tools/nextword-eval.sh and NextWordBench on the reference phone (PRIVACY.md, "Measured"): English 1000 contexts on the phone, Bulgarian accuracy on
        // the build machine (1000 contexts) and its phone time and memory on the first 100 contexts
        val en = NextWordModels.ENGLISH.reference
        assertEquals(listOf(121L, 409L, 272L, 337L), listOf(en.dictionaryPerMille.toLong(), en.modelPerMille.toLong(), en.referenceP95Ms, en.referenceExtraMb))
        assertFalse(en.adopted, "English: 28.8 points better, but 272 ms and 337 MB on the reference phone are over 150 ms and 300 MB")
        val bg = NextWordModels.BULGARIAN.reference
        assertEquals(listOf(69L, 205L, 1403L, 1395L), listOf(bg.dictionaryPerMille.toLong(), bg.modelPerMille.toLong(), bg.referenceP95Ms, bg.referenceExtraMb))
        assertFalse(NextWordGate.Speed(bg.referenceP95Ms, bg.referenceExtraMb, 1570).passes, "the reference phone itself fails the phone test (1570 MB free)")
        assertTrue(bg.adopted, "Bulgarian: 13.6 points better; time and memory are decided on each phone")
        for (m in NextWordModels.ALL) {
            val r = m.reference
            val passes = r.gainPerMille >= 50 && (m.speedTestOnEachPhone || (r.referenceP95Ms <= NextWordGate.MAX_P95_MS && r.referenceExtraMb <= NextWordGate.MAX_EXTRA_MB))
            assertEquals(passes, r.adopted, m.id)
        }
    }

    @Test fun theShippedModelsAreTheCatalogueRowsWithTheirTokenizers() {
        assertEquals(listOf("model-en", "model-bg"), NextWordModels.ALL.map { it.id })
        assertEquals(NextWordModels.ENGLISH, NextWordModels.forLocale(java.util.Locale.UK))
        assertEquals(NextWordModels.ENGLISH, NextWordModels.forLocale(java.util.Locale.forLanguageTag("en-US")))
        assertEquals(NextWordModels.BULGARIAN, NextWordModels.forLocale(java.util.Locale.forLanguageTag("bg")))
        assertNull(NextWordModels.forLocale(java.util.Locale.GERMAN))
        assertTrue(NextWordModels.BULGARIAN.speedTestOnEachPhone, "the Bulgarian model is checked on each phone")
    }
}
