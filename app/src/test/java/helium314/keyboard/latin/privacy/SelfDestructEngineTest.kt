// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY = 24 * 60 * 60 * 1000L
private const val MINUTE = 60_000L

/** What the engine asked the outside world to do. */
class RecordingEffects : WipeEffects {
    val events = ArrayList<String>()
    var wipedScope: WipeScope? = null
    override fun wipe(trigger: Trigger, scope: WipeScope) { events += "wipe:$trigger"; wipedScope = scope }
    override fun confirm(trigger: Trigger) { events += "confirm:$trigger" }
    override fun rehearsed(trigger: Trigger) { events += "rehearsed:$trigger" }
}

/** The decision rules: modes, rehearsal, the limits, and the engine that applies them. */
class SelfDestructEngineTest {
    private var now = 1_800_000_000_000L
    private val fastKdf = ExportCodec.Kdf(memoryKiB = 64, iterations = 1, lanes = 1)
    private val hasher = CountingHasher(fastKdf)
    private val policy = SelfDestructPolicy { now }
    private val duressRecord = SecretRecords.create("9999".toCharArray(), hasher, SecureRandom())
    private val wordRecord = SecretRecords.create("Blue#Horse-4711".toCharArray(), hasher, SecureRandom())
    private val base = SelfDestructConfig(duress = duressRecord, word = wordRecord, wrongPins = 10, inactivityDays = 30)

    init { hasher.calls = 0 } // making the records is not a check

    // ---- the policy -------------------------------------------------------------------------------------------------

    @Test fun theModeOfTheDuressPinAndTheWordDecidesBetweenWipeAndConfirm() {
        for ((trigger, silent, confirm) in listOf(
            Triple(Trigger.DURESS_PIN, base.copy(duressMode = TriggerMode.SILENT), base.copy(duressMode = TriggerMode.CONFIRM)),
            Triple(Trigger.TRIGGER_WORD, base.copy(wordMode = TriggerMode.SILENT), base.copy(wordMode = TriggerMode.CONFIRM)),
        )) {
            assertEquals(Decision.Wipe(trigger), policy.decide(trigger, silent), "$trigger silent")
            assertEquals(Decision.Confirm(trigger), policy.decide(trigger, confirm), "$trigger confirm")
        }
        // the mode of the other trigger does not leak across
        assertEquals(Decision.Wipe(Trigger.DURESS_PIN), policy.decide(Trigger.DURESS_PIN, base.copy(wordMode = TriggerMode.CONFIRM)))
    }

    @Test fun theCountAndTheInactivityWipeWithoutAskingAndThePanicAlwaysAsks() {
        val allConfirm = base.copy(duressMode = TriggerMode.CONFIRM, wordMode = TriggerMode.CONFIRM)
        assertEquals(Decision.Wipe(Trigger.WRONG_PINS), policy.decide(Trigger.WRONG_PINS, allConfirm))
        assertEquals(Decision.Wipe(Trigger.INACTIVITY), policy.decide(Trigger.INACTIVITY, allConfirm))
        assertEquals(Decision.Confirm(Trigger.PANIC), policy.decide(Trigger.PANIC, base.copy(duressMode = TriggerMode.SILENT)))
    }

    @Test fun rehearsalRecognisesEveryTriggerAndWipesNothingForFifteenMinutes() {
        for (t in Trigger.entries) {
            now = 1_800_000_000_000L
            assertEquals(Decision.Rehearse(t), policy.decide(t, base.copy(rehearsalUntil = now + 1)), "$t at +1 ms")
            assertEquals(Decision.Rehearse(t), policy.decide(t, base.copy(rehearsalUntil = now + SelfDestructPolicy.REHEARSAL_MILLIS)), "$t at the full length")
            assertNotEquals(Decision.Rehearse(t), policy.decide(t, base.copy(rehearsalUntil = now)), "$t ended exactly now") // the end is exclusive
            assertNotEquals(Decision.Rehearse(t), policy.decide(t, base.copy(rehearsalUntil = now - 1)), "$t ended")
            assertNotEquals(Decision.Rehearse(t), policy.decide(t, base.copy(rehearsalUntil = 0)), "$t no rehearsal")
            // a damaged or far future end (or a clock set back) must not switch the wipes off for good
            assertNotEquals(Decision.Rehearse(t), policy.decide(t, base.copy(rehearsalUntil = now + SelfDestructPolicy.REHEARSAL_MILLIS + 1)), "$t too far")
            assertNotEquals(Decision.Rehearse(t), policy.decide(t, base.copy(rehearsalUntil = Long.MAX_VALUE)), "$t max")
        }
    }

    private fun assertNotEquals(a: Any?, b: Any?, message: String) = assertTrue(a != b, message)

    @Test fun thePinResultsMapToTheTriggersAndEveryOtherResultDoesNothing() {
        val c = base.copy(wrongPins = 10)
        assertEquals(Decision.Wipe(Trigger.DURESS_PIN), policy.onPin(AppLock.Verify.Duress, c))
        assertEquals(Decision.None, policy.onPin(AppLock.Verify.Duress, c.copy(duress = null))) // a stale result without a record
        for (n in 1..9) assertEquals(Decision.None, policy.onPin(AppLock.Verify.Wrong(n), c), "wrong $n")
        for (n in listOf(10, 11, 500, Int.MAX_VALUE)) assertEquals(Decision.Wipe(Trigger.WRONG_PINS), policy.onPin(AppLock.Verify.Wrong(n), c), "wrong $n")
        assertEquals(Decision.None, policy.onPin(AppLock.Verify.Wrong(500), c.copy(wrongPins = 0))) // off
        assertEquals(Decision.Wipe(Trigger.WRONG_PINS), policy.onPin(AppLock.Verify.Wrong(5), c.copy(wrongPins = 5)))
        assertEquals(Decision.None, policy.onPin(AppLock.Verify.Wrong(4), c.copy(wrongPins = 5)))
        for (other in listOf(AppLock.Verify.Ok, AppLock.Verify.Wait(1000), AppLock.Verify.Unavailable))
            assertEquals(Decision.None, policy.onPin(other, c), "$other")
    }

    @Test fun aCorruptLockRecordCountsAsExhaustedOnlyWhenTheCountLimitIsOn() {
        assertEquals(Decision.Wipe(Trigger.WRONG_PINS), policy.onPin(AppLock.Verify.Corrupt, base.copy(wrongPins = 10)))
        assertEquals(Decision.None, policy.onPin(AppLock.Verify.Corrupt, base.copy(wrongPins = 0)))
    }

    @Test fun inactivityWipesAtTheDayLimitAndNotOneMillisecondBefore() {
        val c = base.copy(inactivityDays = 30)
        assertEquals(Decision.None, policy.inactivity(ActivityState.Present(now - 30 * DAY + 1), c)) // 30 days minus 1 ms
        assertEquals(Decision.Wipe(Trigger.INACTIVITY), policy.inactivity(ActivityState.Present(now - 30 * DAY), c))
        assertEquals(Decision.Wipe(Trigger.INACTIVITY), policy.inactivity(ActivityState.Present(now - 30 * DAY - 1), c)) // plus 1 ms
        assertEquals(Decision.None, policy.inactivity(ActivityState.Present(now - 3 * DAY), c))
        assertEquals(Decision.Wipe(Trigger.INACTIVITY), policy.inactivity(ActivityState.Present(now - 3 * DAY), c.copy(inactivityDays = 3)))
        assertEquals(Decision.None, policy.inactivity(ActivityState.Present(now - 3 * DAY + 1), c.copy(inactivityDays = 3)))
        assertEquals(Decision.None, policy.inactivity(ActivityState.Present(0L), c.copy(inactivityDays = 0))) // off
        assertEquals(Decision.None, policy.inactivity(ActivityState.Present(now + DAY), c)) // a clock set back: no wipe (known gap)
    }

    @Test fun missingOrUnreadableActivityStartsTheClockAndACorruptOneCountsAsExhausted() {
        val c = base.copy(inactivityDays = 30)
        assertEquals(Decision.None, policy.inactivity(ActivityState.Missing, c)) // a new install or a fresh start after a wipe
        assertEquals(Decision.None, policy.inactivity(ActivityState.Unavailable, c)) // cannot be read now: never a reason to wipe
        assertEquals(Decision.Wipe(Trigger.INACTIVITY), policy.inactivity(ActivityState.Corrupt, c))
        assertEquals(Decision.None, policy.inactivity(ActivityState.Corrupt, c.copy(inactivityDays = 0)))
    }

    // ---- the engine ------------------------------------------------------------------------------------------------

    private class Fixture(var config: SelfDestructConfig?, val now: () -> Long, hasher: PinHasher) {
        val configs = MemoryConfigStorage().also { s -> config?.let { s.save(it) }; s.writes = 0 }
        val activity = MemoryActivityStorage()
        val effects = RecordingEffects()
        val engine = SelfDestructEngine(configs, activity, SelfDestructPolicy(now), now, TriggerMatcher(hasher), effects)
    }

    private fun fixture(config: SelfDestructConfig? = base) = Fixture(config, { now }, hasher)

    @Test fun aDuressPinWipesWithTheConfiguredScope() {
        val scope = WipeScope(learned = true, clipboard = false, settings = true, config = false, imported = false)
        val f = fixture(base.copy(scope = scope))
        assertEquals(Decision.Wipe(Trigger.DURESS_PIN), f.engine.onPin(AppLock.Verify.Duress))
        assertEquals(listOf("wipe:DURESS_PIN"), f.effects.events)
        assertEquals(scope, f.effects.wipedScope)
    }

    @Test fun theEngineAsksOrRehearsesAsTheConfigurationSays() {
        val ask = fixture(base.copy(duressMode = TriggerMode.CONFIRM))
        ask.engine.onPin(AppLock.Verify.Duress)
        assertEquals(listOf("confirm:DURESS_PIN"), ask.effects.events)
        val test = fixture(base.copy(rehearsalUntil = now + 5 * MINUTE))
        test.engine.onPin(AppLock.Verify.Duress)
        assertEquals(listOf("rehearsed:DURESS_PIN"), test.effects.events)
    }

    @Test fun anUnreadableOrMissingConfigurationNeverWipes() {
        val none = fixture(null)
        assertEquals(Decision.None, none.engine.onPin(AppLock.Verify.Duress))
        assertEquals(Decision.None, none.engine.onPin(AppLock.Verify.Corrupt))
        val unavailable = fixture(null).also { it.configs.content = ConfigLoad.Unavailable }
        assertEquals(Decision.None, unavailable.engine.onPin(AppLock.Verify.Wrong(99)))
        assertEquals(Decision.None, unavailable.engine.checkInactivity())
        assertEquals(emptyList(), none.effects.events + unavailable.effects.events)
    }

    @Test fun theTenthWrongPinWipesAndTheNinthDoesNot() {
        val f = fixture(base.copy(wrongPins = 10))
        f.engine.onPin(AppLock.Verify.Wrong(9))
        assertEquals(emptyList(), f.effects.events)
        f.engine.onPin(AppLock.Verify.Wrong(10))
        assertEquals(listOf("wipe:WRONG_PINS"), f.effects.events)
    }

    // ---- the commit key -----------------------------------------------------------------------------------------

    private val plainField = FieldContext(password = false, ownApp = false, isWordSeparator = noSeparators)
    private fun f(c: SelfDestructConfig? = base) = fixture(c)

    @Test fun aLongOrdinaryWordGetsNoCheckAndAFieldSeparatorIsAsked() {
        assertNull(f().engine.prepareCheck(CommitKey.ENTER, plainField) { "text internationalization" }) // all letters: never the word
        assertNotNull(f().engine.prepareCheck(CommitKey.ENTER, plainField) { "text internationalizatio1" }) // positive control
        val dot = FieldContext(password = false, ownApp = false, isWordSeparator = { it == '.'.code })
        assertNull(f().engine.prepareCheck(CommitKey.ENTER, dot) { "text Blue.Horse-4711" }) // the input logic splits on the dot
        assertNotNull(f().engine.prepareCheck(CommitKey.ENTER, plainField) { "text Blue.Horse-4711" })
    }

    @Test fun aWordThatCouldBeTheTriggerWordIsKeptOutOfLearningOnlyWhileOneIsSet() {
        val dot = { cp: Int -> cp == '.'.code }
        assertTrue(f().engine.keepsOutOfLearning("Blue#Horse-4711", dot)) // any candidate, not only the word itself
        assertTrue(f().engine.keepsOutOfLearning("Blue#Horse-4712", dot))
        assertFalse(f(base.copy(word = null)).engine.keepsOutOfLearning("Blue#Horse-4711", dot)) // no word set: learned as ever
        assertFalse(f(null).engine.keepsOutOfLearning("Blue#Horse-4711", dot))
        assertFalse(f().engine.keepsOutOfLearning("internationalization", dot)) // all letters
        assertFalse(f().engine.keepsOutOfLearning("hello1", dot)) // too short
        assertFalse(f().engine.keepsOutOfLearning("a".repeat(64) + "1", dot)) // too long
        assertFalse(f().engine.keepsOutOfLearning("Blue.Horse-4711", dot)) // a separator
        val unavailable = f().also { it.configs.content = ConfigLoad.Unavailable }
        assertFalse(unavailable.engine.keepsOutOfLearning("Blue#Horse-4711", dot)) // nothing can be checked: learned as ever
    }

    @Test fun onlyTheCommitKeyWithAWordSetAndAFittingTokenStartsAHash() {
        val before = "text Blue#Horse-4711"
        assertNotNull(f().engine.prepareCheck(CommitKey.ENTER, plainField) { before })
        assertNull(f(base.copy(word = null)).engine.prepareCheck(CommitKey.ENTER, plainField) { before }) // no word set
        assertNull(f().engine.prepareCheck(CommitKey.ENTER, plainField) { "short" }) // token too short
        assertNull(f().engine.prepareCheck(CommitKey.ENTER, plainField) { "a".repeat(65) }) // too long
        assertNull(f().engine.prepareCheck(CommitKey.ENTER, plainField) { null })
        assertNull(f(null).engine.prepareCheck(CommitKey.ENTER, plainField) { before }) // no configuration
        assertEquals(0, hasher.calls) // preparing never hashes
        // the text of the field is read only when a check can be due at all (reading it is a call to the app)
        var reads = 0
        assertNull(f(base.copy(word = null)).engine.prepareCheck(CommitKey.ENTER, plainField) { reads++; before })
        assertNull(f().engine.prepareCheck(CommitKey.SPACE, plainField) { reads++; before })
        assertNull(f().engine.prepareCheck(CommitKey.ENTER, FieldContext(password = true, ownApp = false, isWordSeparator = noSeparators)) { reads++; before })
        assertEquals(0, reads)
        assertNotNull(f().engine.prepareCheck(CommitKey.ENTER, plainField) { reads++; before })
        assertEquals(1, reads)
    }

    @Test fun theSpaceKeyChecksOnlyWhenTheOptionIsOn() {
        val before = "text Blue#Horse-4711"
        assertNull(f().engine.prepareCheck(CommitKey.SPACE, plainField) { before })
        assertNotNull(f(base.copy(commitOnSpace = true)).engine.prepareCheck(CommitKey.SPACE, plainField) { before })
        assertNotNull(f(base.copy(commitOnSpace = true)).engine.prepareCheck(CommitKey.ENTER, plainField) { before }) // Enter always checks
    }

    @Test fun passwordFieldsAreSkippedUnlessTheOptionIsOnAndTheKeyboardsOwnFieldsNever() {
        val before = "text Blue#Horse-4711"
        val pw = FieldContext(password = true, ownApp = false, isWordSeparator = noSeparators)
        assertNull(f().engine.prepareCheck(CommitKey.ENTER, pw) { before })
        assertNotNull(f(base.copy(checkInPasswordFields = true)).engine.prepareCheck(CommitKey.ENTER, pw) { before })
        val own = FieldContext(password = false, ownApp = true, isWordSeparator = noSeparators)
        assertNull(f().engine.prepareCheck(CommitKey.ENTER, own) { before })
        assertNull(f(base.copy(checkInPasswordFields = true)).engine.prepareCheck(CommitKey.ENTER, FieldContext(password = true, ownApp = true, isWordSeparator = noSeparators)) { before })
    }

    @Test fun theMatchingWordWipesAndOneHashRuns() {
        val fx = f()
        val check = fx.engine.prepareCheck(CommitKey.ENTER, plainField) { "x Blue#Horse-4711" }!!
        assertTrue(check.holdEnter)
        assertEquals(15, check.tokenLength)
        assertTrue(check.run())
        assertEquals(listOf("wipe:TRIGGER_WORD"), fx.effects.events)
        assertEquals(1, hasher.calls)
    }

    @Test fun aWordThatDoesNotMatchDoesNothingAndTheCheckIsAskedOnlyOnce() {
        val fx = f()
        val check = fx.engine.prepareCheck(CommitKey.ENTER, plainField) { "x Blue#Horse-4712" }!!
        assertFalse(check.run())
        assertEquals(emptyList(), fx.effects.events)
        assertFailsWithIllegalState { check.run() } // the token was cleared: a second run is a bug, never a match
    }

    private fun assertFailsWithIllegalState(block: () -> Unit) {
        try { block() } catch (_: IllegalStateException) { return }
        throw AssertionError("expected IllegalStateException")
    }

    @Test fun theWordHonoursItsModeAndRehearsal() {
        val ask = f(base.copy(wordMode = TriggerMode.CONFIRM))
        assertTrue(ask.engine.prepareCheck(CommitKey.ENTER, plainField) { "Blue#Horse-4711" }!!.run())
        assertEquals(listOf("confirm:TRIGGER_WORD"), ask.effects.events)
        val test = f(base.copy(rehearsalUntil = now + MINUTE))
        assertTrue(test.engine.prepareCheck(CommitKey.ENTER, plainField) { "Blue#Horse-4711" }!!.run()) // matched: the word is removed, nothing wiped
        assertEquals(listOf("rehearsed:TRIGGER_WORD"), test.effects.events)
    }

    @Test fun holdEnterOffLetsTheActionGoAheadAndIsReportedToTheCaller() {
        val check = f(base.copy(holdEnter = false)).engine.prepareCheck(CommitKey.ENTER, plainField) { "Blue#Horse-4711" }!!
        assertFalse(check.holdEnter)
    }

    // ---- inactivity and the panic ----------------------------------------------------------------------------------

    @Test fun anInputStartWipesAfterTheLimitAndOtherwiseRecordsTheActivityAtMostOncePerHour() {
        val fx = f(base.copy(inactivityDays = 30))
        assertEquals(Decision.None, fx.engine.onInputStart()) // first start: no record yet
        assertEquals(now, (fx.activity.content as ActivityState.Present).lastActive)
        assertEquals(1, fx.activity.writes)
        now += SelfDestructEngine.TOUCH_INTERVAL_MILLIS - 1
        fx.engine.onInputStart()
        assertEquals(1, fx.activity.writes) // within the hour: no write
        now += 1
        fx.engine.onInputStart()
        assertEquals(2, fx.activity.writes)
        assertEquals(now, (fx.activity.content as ActivityState.Present).lastActive)
        assertEquals(emptyList(), fx.effects.events)
        now += 30 * DAY - 1 // no input session for 30 days minus 1 ms (the daily job looks, which is not use)
        assertEquals(Decision.None, fx.engine.checkInactivity())
        now += 1
        assertEquals(Decision.Wipe(Trigger.INACTIVITY), fx.engine.onInputStart())
        assertEquals(listOf("wipe:INACTIVITY"), fx.effects.events)
    }

    @Test fun aWipeFromInactivityDoesNotWriteTheActivityAgain() {
        val fx = f(base.copy(inactivityDays = 3))
        fx.activity.save(now - 3 * DAY)
        fx.activity.writes = 0
        fx.engine.onInputStart()
        assertEquals(0, fx.activity.writes)
        assertEquals(now - 3 * DAY, (fx.activity.content as ActivityState.Present).lastActive)
    }

    @Test fun theDailyJobChecksWithoutTouchingTheActivity() {
        val fx = f(base.copy(inactivityDays = 30))
        fx.activity.save(now - 10 * DAY)
        fx.activity.writes = 0
        assertEquals(Decision.None, fx.engine.checkInactivity())
        assertEquals(0, fx.activity.writes) // a job that ran would otherwise count as use
        fx.activity.content = ActivityState.Present(now - 30 * DAY)
        assertEquals(Decision.Wipe(Trigger.INACTIVITY), fx.engine.checkInactivity())
    }

    @Test fun aCorruptActivityTimeWipesWhenInactivityIsOn() {
        val fx = f(base.copy(inactivityDays = 30))
        fx.activity.content = ActivityState.Corrupt
        assertEquals(Decision.Wipe(Trigger.INACTIVITY), fx.engine.onInputStart())
        val off = f(base.copy(inactivityDays = 0))
        off.activity.content = ActivityState.Corrupt
        assertEquals(Decision.None, off.engine.onInputStart())
    }

    @Test fun thePanicAsksUnlessConfirmedAndRehearsalOnlyShowsIt() {
        val fx = f()
        assertEquals(Decision.Confirm(Trigger.PANIC), fx.engine.panic(confirmed = false))
        assertEquals(Decision.Wipe(Trigger.PANIC), fx.engine.panic(confirmed = true))
        assertEquals(listOf("confirm:PANIC", "wipe:PANIC"), fx.effects.events)
        val test = f(base.copy(rehearsalUntil = now + MINUTE))
        assertEquals(Decision.Rehearse(Trigger.PANIC), test.engine.panic(confirmed = true))
        assertEquals(listOf("rehearsed:PANIC"), test.effects.events)
    }

    @Test fun panicWorksWithoutAnyConfigurationBecauseItIsManual() {
        val fx = f(null)
        assertEquals(Decision.Wipe(Trigger.PANIC), fx.engine.panic(confirmed = true))
        assertEquals(WipeScope.ALL, fx.effects.wipedScope)
    }
}
