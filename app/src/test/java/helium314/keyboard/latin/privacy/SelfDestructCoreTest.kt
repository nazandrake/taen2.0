// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Self-destruct, pass 1: the core behaviour of each trigger and of the wipe order. */
class SelfDestructCoreTest {
    private val fastKdf = ExportCodec.Kdf(memoryKiB = 64, iterations = 1, lanes = 1) // real parameters are measured on the device
    private val hasher = PinHasher(fastKdf)
    private val word = "Blue#Horse-4711".toCharArray()

    private fun record(secret: CharArray, salt: Byte = 1): SecretRecord {
        val s = ByteArray(16) { salt }
        return SecretRecord(s, hasher.hash(secret, s), fastKdf)
    }

    @Test fun theTriggerWordTypedBeforeTheCursorMatchesItsRecord() {
        val matcher = TriggerMatcher(hasher)
        val token = assertNotNull(matcher.tokenOf("hello Blue#Horse-4711", noSeparators))
        assertTrue(matcher.matches(token, record(word)))
    }

    @Test fun anotherWordDoesNotMatch() {
        val matcher = TriggerMatcher(hasher)
        val token = assertNotNull(matcher.tokenOf("hello Blue#Horse-4712", noSeparators))
        assertFalse(matcher.matches(token, record(word)))
    }

    @Test fun theDuressPinGivesTheDuressResultAndTheRealPinGivesOk() {
        val storage = MemoryLockStorage()
        val duress = record("9999".toCharArray())
        val lock = AppLock(storage, { 1_000L }, hasher, duress = { duress }, onMainThread = notMainThread)
        assertEquals(AppLock.SetResult.OK, lock.enable("1234".toCharArray()))
        assertEquals(AppLock.Verify.Duress, lock.verify("9999".toCharArray()))
        assertEquals(AppLock.Verify.Ok, lock.verify("1234".toCharArray()))
    }

    @Test fun theDuressPinWipesAndTheTenthWrongPinWipes() {
        val policy = SelfDestructPolicy { 5_000L }
        val config = SelfDestructConfig.DEFAULT.copy(duress = record("9999".toCharArray()), wrongPins = 10)
        assertEquals(Decision.Wipe(Trigger.DURESS_PIN), policy.onPin(AppLock.Verify.Duress, config))
        assertEquals(Decision.None, policy.onPin(AppLock.Verify.Wrong(9), config))
        assertEquals(Decision.Wipe(Trigger.WRONG_PINS), policy.onPin(AppLock.Verify.Wrong(10), config))
    }

    @Test fun theKeysAreDestroyedBeforeAnyFileIsDeleted() {
        val ports = RecordingPorts()
        Wiper(ports) { 0L }.run(WipeScope.ALL)
        val keys = ports.calls.indexOf("deleteKeys")
        val files = ports.calls.indexOf("deleteFiles")
        assertTrue(keys in 0 until files, "calls: ${ports.calls}")
        assertEquals("beginWipe", ports.calls.first())
        assertEquals("restart", ports.calls.last())
    }
}

/** A wipe port that only records what was called, in order. */
class RecordingPorts : WipePorts {
    val calls = ArrayList<String>()
    override fun beginWipe() { calls += "beginWipe" }
    override fun readArmed(): List<ArmedFile> { calls += "readArmed"; return emptyList() }
    override fun writePending(pending: Pending): Boolean { calls += "writePending"; return true }
    override fun deleteKeys(): Boolean { calls += "deleteKeys"; return true }
    override fun deleteFiles(scope: WipeScope, keep: Set<String>, ceOnly: Boolean): Boolean { calls += "deleteFiles"; return true }
    override fun clearClipboard() { calls += "clearClipboard" }
    override fun restoreArmed(files: List<ArmedFile>): Boolean { calls += "restoreArmed"; return true }
    override fun clearPending() { calls += "clearPending" }
    override fun restart() { calls += "restart" }
}
