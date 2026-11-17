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

/**
 * Where the model may run: the test on each phone (p95 within the model's limit, the free memory covers what the model added plus 1 GB), the same memory
 * rule before every load, the readiness of a model on this phone and the field gate.
 */
class NextWordGateTest {
    private fun model(maxP95Ms: Long = 250, dictionary: Int = 100, withModel: Int = 200) =
        NextWordModel("model-x", "xx", "nextword/x", PreSplit.QWEN2, maxP95Ms, Reference(dictionary, withModel, 90, 250))

    @Test fun eachModelHasItsOwnStepLimitAndBothNeedTheModelPlus1GbFree() {
        assertEquals(150L, NextWordModels.ENGLISH.maxP95Ms)
        assertEquals(250L, NextWordModels.BULGARIAN.maxP95Ms)
        assertEquals(1024L, NextWordGate.MARGIN_MB)
        assertTrue(Speed(150, 340, 340 + 1024).passes(NextWordModels.ENGLISH), "both limits are inclusive")
        assertFalse(Speed(151, 340, 4000).passes(NextWordModels.ENGLISH), "English: too slow at 151 ms")
        assertTrue(Speed(250, 860, 860 + 1024).passes(NextWordModels.BULGARIAN))
        assertFalse(Speed(251, 860, 4000).passes(NextWordModels.BULGARIAN), "Bulgarian: too slow at 251 ms")
        assertFalse(Speed(100, 860, 860 + 1023).passes(NextWordModels.BULGARIAN), "1 MB short of the margin")
    }

    @Test fun beforeEveryLoadTheMemoryFreeNowMustCoverWhatTheModelAddedPlus1Gb() {
        val stored = Speed(120, 400, 3000) // passed the test when 3000 MB were free
        assertTrue(NextWordGate.mayLoadNow(stored, 400 + 1024), "positive control: exactly enough free now")
        assertFalse(NextWordGate.mayLoadNow(stored, 500), "a stored pass with 500 MB free now: not loaded")
        assertFalse(NextWordGate.mayLoadNow(stored, -1), "free memory that cannot be read: not loaded")
        assertFalse(NextWordGate.mayLoadNow(null, 99_999), "no speed result: not loaded")
    }

    @Test fun theLoadRuleHasNoWayAroundIt() {
        assertFalse(NextWordGate.mayLoadNow(Speed(120, NextWordSpeedTest.UNKNOWN_MB, 3000), 999_999_999), "added memory that was never measured")
        assertFalse(NextWordGate.mayLoadNow(Speed(120, 400, 3000), 400 + 1023), "1 MB short")
        assertTrue(NextWordGate.mayLoadNow(Speed(120, 0, 3000), 1024), "positive control: nothing added, 1 GB free")
        assertFalse(NextWordGate.mayLoadNow(Speed(120, 0, 3000), 1023))
    }

    @Test fun eachPartOfThePhoneTestFailsOnItsOwnAndUnmeasuredValuesFail() {
        val bg = NextWordModels.BULGARIAN
        assertTrue(Speed(0, 0, 1024).passes(bg), "positive control: the smallest passing values")
        assertEquals(true to false, Speed(250, 0, 1023).let { it.fastEnough(bg) to it.enoughMemory })
        assertEquals(false to true, Speed(251, 0, 1024).let { it.fastEnough(bg) to it.enoughMemory })
        assertFalse(Speed(-1, 10, 4000).passes(bg), "a negative time is not a measurement")
        assertFalse(Speed(10, -1, 4000).enoughMemory, "a negative memory figure is not a measurement")
        assertFalse(Speed(10, 0, -1).enoughMemory, "free memory that could not be read")
        assertFalse(Speed(10, NextWordSpeedTest.UNKNOWN_MB, 999_999_999).enoughMemory, "added memory that could not be read never passes")
    }

    @Test fun aLoadThatStoppedTheKeyboardNeedsANewSpeedTest() {
        val m = model()
        assertEquals(Readiness.READY, NextWordGate.readiness(m, true, Speed(100, 400, 3000), loadCrashed = false), "positive control")
        assertEquals(Readiness.LOAD_CRASHED, NextWordGate.readiness(m, true, Speed(100, 400, 3000), loadCrashed = true), "even with a stored pass")
        assertEquals(Readiness.LOAD_CRASHED, NextWordGate.readiness(m, true, null, loadCrashed = true))
        assertEquals(Readiness.NOT_INSTALLED, NextWordGate.readiness(m, false, null, loadCrashed = true), "removed: nothing to load")
    }

    @Test fun adoptedIsTheAccuracyPartOfTheReferenceCheck() {
        assertTrue(model(dictionary = 100, withModel = 150).reference.adopted, "50 per mille (5 points) better")
        assertFalse(model(dictionary = 100, withModel = 149).reference.adopted)
    }

    @Test fun aStoredSpeedReadsBackAndAnythingElseIsNoResult() {
        val s = Speed.decode(Speed(123, 456, 2000).encode())!!
        assertEquals(Triple(123L, 456L, 2000L), Triple(s.p95Ms, s.extraMb, s.freeMb))
        // "p95=1 mb=2" is the format before the free memory was recorded: no result, so the test runs again
        for (bad in listOf(null, "", "p95=1", "p95=x mb=2", "p95=1 mb=2", "p95=1 mb=2 free=3 extra", "p95=-1 mb=2 free=3", "p95=1 mb=2 free=-3",
                "p95=1234567890 mb=1 free=1", "mb=2 p95=1 free=3"))
            assertNull(Speed.decode(bad), "$bad")
    }

    @Test fun readinessFollowsInstallAccuracyAndThisPhonesTest() {
        val m = model()
        assertEquals(Readiness.NOT_INSTALLED, NextWordGate.readiness(m, installed = false, speed = Speed(10, 10, 4000)))
        assertEquals(Readiness.NEEDS_SPEED_TEST, NextWordGate.readiness(m, true, null))
        assertEquals(Readiness.READY, NextWordGate.readiness(m, true, Speed(250, 860, 1884)))
        assertEquals(Readiness.TOO_SLOW, NextWordGate.readiness(m, true, Speed(251, 860, 4000)))
        assertEquals(Readiness.TOO_SLOW, NextWordGate.readiness(m, true, Speed(100, 860, 1883)))
        assertEquals(Readiness.DISABLED_BY_GATE, NextWordGate.readiness(model(withModel = 149), true, Speed(1, 1, 4000)), "not 5 points better: never ready")
    }

    @Test fun theModelRunsOnlyWhenEveryConditionHolds() {
        assertTrue(NextWordGate.runs(true, true, true, true, Readiness.READY), "positive control: a normal field with a ready model")
        assertFalse(NextWordGate.runs(false, true, true, true, Readiness.READY), "model switch off")
        assertFalse(NextWordGate.runs(true, false, true, true, Readiness.READY), "no suggestion strip")
        assertFalse(NextWordGate.runs(true, true, false, true, Readiness.READY), "next-word predictions off")
        assertFalse(NextWordGate.runs(true, true, true, false, Readiness.READY), "password, incognito, no-learning or locked field")
        for (r in Readiness.entries - Readiness.READY) assertFalse(NextWordGate.runs(true, true, true, true, r), "$r")
    }

    @Test fun theShippedReferenceNumbersAreTheMeasurements() {
        // tools/nextword-eval.sh and NextWordBench on the reference phone (PRIVACY.md, "Measured"): English 1000 contexts on the phone, Bulgarian accuracy on
        // the build machine (1000 contexts) and its phone time and memory on the first 100 contexts
        val en = NextWordModels.ENGLISH.reference
        assertEquals(listOf(121L, 409L, 272L, 337L), listOf(en.dictionaryPerMille.toLong(), en.modelPerMille.toLong(), en.referenceP95Ms, en.referenceExtraMb))
        assertTrue(en.adopted, "English: 28.8 points better; time and memory are decided on each phone")
        assertFalse(Speed(en.referenceP95Ms, en.referenceExtraMb, 1570).passes(NextWordModels.ENGLISH), "the reference phone itself fails English's test (272 ms)")
        val bg = NextWordModels.BULGARIAN.reference
        assertEquals(listOf(69L, 205L, 1403L, 1395L), listOf(bg.dictionaryPerMille.toLong(), bg.modelPerMille.toLong(), bg.referenceP95Ms, bg.referenceExtraMb))
        assertTrue(bg.adopted, "Bulgarian: 13.6 points better")
        assertFalse(Speed(bg.referenceP95Ms, bg.referenceExtraMb, 1570).passes(NextWordModels.BULGARIAN), "the reference phone itself fails Bulgarian's test")
    }

    @Test fun theShippedModelsAreTheCatalogueRowsWithTheirTokenizers() {
        assertEquals(listOf("model-en", "model-bg"), NextWordModels.ALL.map { it.id })
        assertEquals(NextWordModels.ENGLISH, NextWordModels.forLocale(java.util.Locale.UK))
        assertEquals(NextWordModels.ENGLISH, NextWordModels.forLocale(java.util.Locale.forLanguageTag("en-US")))
        assertEquals(NextWordModels.BULGARIAN, NextWordModels.forLocale(java.util.Locale.forLanguageTag("bg")))
        assertNull(NextWordModels.forLocale(java.util.Locale.GERMAN))
    }
}
