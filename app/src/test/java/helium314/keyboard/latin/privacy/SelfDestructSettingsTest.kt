// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What can be configured, with which values, and what is refused (configuration and false-trigger rules). */
class SelfDestructSettingsTest {
    private var now = 1_800_000_000_000L
    private val fastKdf = ExportCodec.Kdf(memoryKiB = 64, iterations = 1, lanes = 1)
    private val hasher = CountingHasher(fastKdf)
    private val lockStorage = MemoryLockStorage()
    private val appLock = AppLock(lockStorage, { now }, hasher, onMainThread = notMainThread)
    private val configs = MemoryConfigStorage()
    private val activity = MemoryActivityStorage()
    private val settings = SelfDestructSettings(configs, activity, appLock, hasher, { now }, SecureRandom(), { it.toChar() in KEYBOARD_SEPARATORS })
    private val real = "482915".toCharArray()

    private fun withLock() { assertEquals(AppLock.SetResult.OK, appLock.enable(real)) }

    @Test fun theDuressPinNeedsAnAppLockAndMustDifferFromThePinAndFollowThePinRules() {
        assertEquals(SelfDestructSettings.Change.NEEDS_APP_LOCK, settings.setDuressPin("1111".toCharArray()))
        assertNull(configs.config)
        withLock()
        assertEquals(SelfDestructSettings.Change.SAME_AS_APP_PIN, settings.setDuressPin(real.copyOf()))
        for (bad in listOf("", "123", "12a4", "1".repeat(17))) assertEquals(SelfDestructSettings.Change.BAD_PIN, settings.setDuressPin(bad.toCharArray()), bad)
        assertNull(configs.config) // nothing stored for any refusal
        assertEquals(SelfDestructSettings.Change.OK, settings.setDuressPin("1111".toCharArray()))
        val record = assertNotNull(configs.config!!.duress)
        assertEquals(16, record.salt.size)
        assertEquals(32, record.hash.size)
        assertFalse(String(record.hash + record.salt, Charsets.ISO_8859_1).contains("1111"))
    }

    @Test fun theDuressPinIsRefusedWhenTheAppLockCannotBeRead() {
        withLock()
        lockStorage.content = LockLoad.Unavailable
        assertEquals(SelfDestructSettings.Change.NOT_SAVED, settings.setDuressPin("1111".toCharArray()))
        assertNull(configs.config)
    }

    @Test fun theDuressPinCanBeRemovedAndRemovingItAgainIsFine() {
        withLock()
        settings.setDuressPin("1111".toCharArray())
        assertEquals(SelfDestructSettings.Change.OK, settings.removeDuressPin())
        assertNull(configs.config!!.duress)
        assertEquals(SelfDestructSettings.Change.OK, settings.removeDuressPin())
    }

    @Test fun theTriggerWordIsCheckedAgainstTheRulesAndStoredOnlyAsAHash() {
        assertEquals(SelfDestructSettings.Change.WORD_TOO_SHORT, settings.setTriggerWord("short1".toCharArray()) { false })
        assertEquals(SelfDestructSettings.Change.WORD_TOO_LONG, settings.setTriggerWord(("a".repeat(64) + "1").toCharArray()) { false })
        assertEquals(SelfDestructSettings.Change.WORD_WHITESPACE, settings.setTriggerWord("abcdef ghijkl1".toCharArray()) { false })
        assertEquals(SelfDestructSettings.Change.WORD_NEEDS_DIGIT_OR_SYMBOL, settings.setTriggerWord("abcdefghijklmn".toCharArray()) { false })
        assertEquals(SelfDestructSettings.Change.WORD_KNOWN, settings.setTriggerWord("abcdefghijk#1".toCharArray()) { true })
        assertNull(configs.config)
        val word = "Blue#Horse-4711".toCharArray()
        assertEquals(SelfDestructSettings.Change.OK, settings.setTriggerWord(word) { false })
        val record = assertNotNull(configs.config!!.word)
        assertTrue(TriggerMatcher(hasher).matches("Blue#Horse-4711".toCharArray(), record))
        assertFalse(TriggerMatcher(hasher).matches("Blue#Horse-4712".toCharArray(), record))
        assertEquals(SelfDestructSettings.Change.OK, settings.removeTriggerWord())
        assertNull(configs.config!!.word)
    }

    @Test fun aTriggerWordWithASeparatorIsRefusedAndNothingIsStored() {
        for (w in listOf("Blue.Horse-4711", "Blue#Horse,4711", "Blue#Horse-4711!", "Blue#Horse-47😀11"))
            assertEquals(SelfDestructSettings.Change.WORD_SEPARATOR, settings.setTriggerWord(w.toCharArray()) { false }, w)
        assertNull(configs.config)
        assertEquals(SelfDestructSettings.Change.OK, settings.setTriggerWord("Blue#Horse-4711".toCharArray()) { false }) // positive control
    }

    @Test fun theWordArrayHandedInIsClearedAfterUse() {
        val word = "Blue#Horse-4711".toCharArray()
        settings.setTriggerWord(word) { false }
        assertTrue(word.all { it == '\u0000' })
        val refused = "short".toCharArray()
        settings.setTriggerWord(refused) { false }
        assertTrue(refused.all { it == '\u0000' })
        val pin = "1111".toCharArray()
        settings.setDuressPin(pin)
        assertTrue(pin.all { it == '\u0000' })
    }

    @Test fun theWrongPinCountNeedsAnAppLockAndFiveToTwentyOrOff() {
        assertEquals(SelfDestructSettings.Change.NEEDS_APP_LOCK, settings.setWrongPins(10))
        assertEquals(SelfDestructSettings.Change.OK, settings.setWrongPins(0)) // switching off needs no lock
        withLock()
        for (bad in listOf(-1, 1, 4, 21, 100)) assertEquals(SelfDestructSettings.Change.OUT_OF_RANGE, settings.setWrongPins(bad), "$bad")
        for (ok in listOf(5, 10, 20)) {
            assertEquals(SelfDestructSettings.Change.OK, settings.setWrongPins(ok), "$ok")
            assertEquals(ok, configs.config!!.wrongPins)
        }
        assertEquals(SelfDestructSettings.Change.OK, settings.setWrongPins(0))
        assertEquals(0, configs.config!!.wrongPins)
    }

    @Test fun theInactivityLimitIsThreeToThreeHundredSixtyFiveDaysOrOffAndStartsTheClockNow() {
        for (bad in listOf(-1, 1, 2, 366, 1000)) assertEquals(SelfDestructSettings.Change.OUT_OF_RANGE, settings.setInactivityDays(bad), "$bad")
        assertNull(configs.config)
        assertEquals(SelfDestructSettings.Change.OK, settings.setInactivityDays(3))
        assertEquals(3, configs.config!!.inactivityDays)
        assertEquals(now, (activity.content as ActivityState.Present).lastActive) // an old time must not wipe at once
        assertEquals(SelfDestructSettings.Change.OK, settings.setInactivityDays(365))
        assertEquals(SelfDestructSettings.Change.OK, settings.setInactivityDays(0))
        assertEquals(0, configs.config!!.inactivityDays)
    }

    @Test fun modesCommitKeysAndTheScopeAreStoredAsGiven() {
        settings.setDuressMode(TriggerMode.CONFIRM)
        settings.setWordMode(TriggerMode.CONFIRM)
        settings.setCommitOnSpace(true)
        settings.setHoldEnter(false)
        settings.setCheckInPasswordFields(true)
        val scope = WipeScope(learned = false, clipboard = true, settings = false, config = true, imported = false)
        settings.setScope(scope)
        val c = configs.config!!
        assertEquals(TriggerMode.CONFIRM, c.duressMode); assertEquals(TriggerMode.CONFIRM, c.wordMode)
        assertTrue(c.commitOnSpace); assertFalse(c.holdEnter); assertTrue(c.checkInPasswordFields)
        assertEquals(scope, c.scope)
    }

    @Test fun anEditKeepsEveryOtherSettingAndAnUnreadableConfigurationIsNeverOverwritten() {
        withLock()
        settings.setDuressPin("1111".toCharArray())
        settings.setWrongPins(7)
        settings.setHoldEnter(false)
        val duress = configs.config!!.duress
        settings.setInactivityDays(10)
        assertEquals(7, configs.config!!.wrongPins)
        assertFalse(configs.config!!.holdEnter)
        assertEquals(duress, configs.config!!.duress)
        configs.content = ConfigLoad.Unavailable
        assertEquals(SelfDestructSettings.Change.NOT_SAVED, settings.setWrongPins(9))
        assertEquals(ConfigLoad.Unavailable, configs.content)
    }

    @Test fun aFailedWriteIsReportedAndNothingIsHalfStored() {
        configs.failWrites = true
        assertEquals(SelfDestructSettings.Change.NOT_SAVED, settings.setHoldEnter(false))
        assertEquals(SelfDestructSettings.Change.NOT_SAVED, settings.setInactivityDays(5))
        assertNull(configs.config)
    }

    @Test fun rehearsalLastsFifteenMinutesFromNowAndCanBeStopped() {
        assertEquals(SelfDestructSettings.Change.OK, settings.startRehearsal())
        assertEquals(now + 15 * 60_000L, configs.config!!.rehearsalUntil)
        assertEquals(SelfDestructPolicy.REHEARSAL_MILLIS, 15 * 60_000L)
        assertEquals(SelfDestructSettings.Change.OK, settings.stopRehearsal())
        assertEquals(0L, configs.config!!.rehearsalUntil)
    }
}
