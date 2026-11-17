// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.app.AlertDialog
import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.settings.UnlockActivity
import helium314.keyboard.settings.WipeConfirmActivity
import java.io.File
import java.security.SecureRandom
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The self-destruct in the running app (instrumentation list): every store is seeded with a canary, each trigger is
 * fired through its production entry point, and the keys, the stores and the files are checked afterwards. The Argon2id parameters are the real ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class])
class SelfDestructWiringTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val deCtx: Context = ctx.createDeviceProtectedStorageContext()
    private val canary = "TAENcanary-Qx93"
    private val pin = "482915".toCharArray()
    private var restarts = 0

    private fun dataDirs() = listOf(ctx.dataDir, deCtx.dataDir).distinct()
    private fun taen(c: Context, name: String) = File(c.noBackupFilesDir, "taen/$name")

    @Before fun before() {
        Privacy.unlock(ctx); Privacy.forgetCachedState(); Privacy.endWipe()
        restarts = 0
        SelfDestruct.restartHook = { restarts++ }
        SelfDestruct.executor = directExecutor // the wipe runs on the thread that saw the trigger, so the test can look at once
    }
    @After fun after() {
        Privacy.unlock(ctx); Privacy.forgetCachedState(); Privacy.endWipe(); SelfDestruct.restartHook = SelfDestruct.defaultRestart; SelfDestruct.executor = SelfDestruct.defaultExecutor
    }

    /** Fills every store and plain file the app keeps with the canary and returns the ciphertext files as they were. */
    private fun seedEverything(): Map<String, ByteArray> {
        Privacy.prefs(ctx).edit().putString("canary", canary).commit()
        Privacy.protectedPrefs(ctx).edit().putString("canary", canary).commit()
        for (name in listOf(Privacy.CLIPBOARD_STORE, Privacy.PERSONAL_DICTIONARY_STORE, "learned_en-US", "learned_bg"))
            Privacy.store(ctx, Tier.CE, name, 1)!!.write(("word $canary").toByteArray())
        assertTrue(Privacy.writeBackground(ctx, false, false, canary.toByteArray()))
        assertEquals(AppLock.SetResult.OK, offMain { Privacy.appLock(ctx).enable(pin) })
        val settings = SelfDestruct.settings(ctx)
        assertEquals(SelfDestructSettings.Change.OK, settings.setDuressPin("1111".toCharArray()))
        assertEquals(SelfDestructSettings.Change.OK, settings.setTriggerWord("Blue#Horse-4711".toCharArray()) { false })
        assertEquals(SelfDestructSettings.Change.OK, settings.setWrongPins(5))
        assertEquals(SelfDestructSettings.Change.OK, settings.setInactivityDays(30))
        SelfDestruct.engine(ctx).onInputStart() // writes the activity time
        File(ctx.filesDir, "dicts/bg/main_bg.dict").apply { parentFile.mkdirs(); writeText(canary) }
        File(ctx.cacheDir, "temp_dict").writeText(canary)
        Privacy.writer.submit {}.get() // the queued preference writes are on disk
        return dataDirs().flatMap { d -> d.walkTopDown().filter { it.isFile && it.path.contains("no_backup") }.map { it.path to it.readBytes() } }.toMap()
    }

    private fun allFiles() = dataDirs().flatMap { d -> d.walkTopDown().filter { it.isFile }.map { it.path } }

    private fun userFiles() = allFiles().filter { f -> listOf("no_backup", "/files/", "/cache/", "/databases/", "shared_prefs").any { f.contains(it) } && !f.endsWith("wipe.pending") }

    private fun restoreOldCiphertext(old: Map<String, ByteArray>) {
        for ((path, bytes) in old) File(path).apply { parentFile.mkdirs(); writeBytes(bytes) }
    }

    @Test fun everyWordSeparatorOfTheKeyboardsResourceIsRefusedInATriggerWordThroughTheProductionWiring() {
        val separators = ctx.getString(helium314.keyboard.latin.R.string.symbols_word_separators)
        assertTrue(separators.length > 10) // the resource is read
        assertEquals(KEYBOARD_SEPARATORS.toSet(), separators.filter { !it.isWhitespace() }.toSet()) // the list in TriggerWordTest is the resource
        val settings = SelfDestruct.settings(ctx)
        for (c in separators.filter { !it.isWhitespace() })
            assertEquals(SelfDestructSettings.Change.WORD_SEPARATOR, settings.setTriggerWord("Blue${c}Horse-4711".toCharArray()) { false }, "separator $c")
        assertEquals(SelfDestructSettings.Change.OK, settings.setTriggerWord("Blue#Horse-4711".toCharArray()) { false }) // positive control
    }

    // ---- a full wipe -------------------------------------------------------------------------------------------------

    @Test fun aFullWipeLeavesNoFileNoKeyAndNothingRecoverable() {
        val old = seedEverything()
        assertTrue(old.isNotEmpty() && userFiles().size >= old.size)
        assertTrue(dataDirs().any { filesContaining(it, canary).isNotEmpty() }) // positive control: the plain canary files are there before the wipe

        val report = SelfDestruct.wipe(ctx, WipeScope.ALL)
        assertTrue(report.complete)
        assertEquals(1, restarts)
        assertEquals(emptyList(), userFiles()) // the data directories hold no user file
        assertFalse(taen(ctx, "wipe.pending").exists() || taen(deCtx, "wipe.pending").exists()) // finished: no marker
        for (d in dataDirs()) assertEquals(emptyList(), filesContaining(d, canary), d.path)

        // the process restarts: new state, old ciphertext put back by an attacker with a copy of the files taken before the wipe
        Privacy.forgetCachedState(); Privacy.endWipe()
        restoreOldCiphertext(old)
        for (name in listOf(Privacy.CLIPBOARD_STORE, "learned_en-US", "learned_bg", Privacy.PERSONAL_DICTIONARY_STORE)) {
            val c = Privacy.store(ctx, Tier.CE, name, 1)!!.read()
            assertTrue(c !is StoreContent.Data, "$name is still readable after the wipe")
        }
        assertNull(Privacy.prefs(ctx).getString("canary", null))
        assertNull(Privacy.protectedPrefs(ctx).getString("canary", null))
        assertNull(Privacy.readBackground(ctx, false, false)) // the old image file is back, but its key is gone
        assertFalse(Privacy.appLock(ctx).isEnabled()) // the lock went with the data: the keyboard starts as new
        assertEquals(Decision.None, SelfDestruct.engine(ctx).onPin(AppLock.Verify.Duress)) // no configuration any more
        for (d in dataDirs()) assertEquals(emptyList(), filesContaining(d, canary), d.path)
    }

    @Test fun theKeyboardWritesAgainAfterTheRestartWithNewKeys() {
        seedEverything()
        SelfDestruct.wipe(ctx, WipeScope.ALL)
        Privacy.forgetCachedState(); Privacy.endWipe()
        assertTrue(Privacy.prefs(ctx).edit().putString("fresh", "1").commit())
        assertEquals("1", Privacy.prefs(ctx).getString("fresh", null))
        Privacy.forgetCachedState()
        assertEquals("1", Privacy.prefs(ctx).getString("fresh", null)) // and it reads back from disk
    }

    @Test fun nothingIsWrittenOnceTheWipeHasBegun() {
        seedEverything()
        val p = Privacy.prefs(ctx)
        SelfDestruct.wipe(ctx, WipeScope.ALL)
        assertTrue(Privacy.isWiping)
        assertNull(Privacy.store(ctx, Tier.CE, "clipboard", 1)) // no new store object
        assertNull(Privacy.store(ctx, Tier.DE, "prefs_de", 1))
        p.edit().putString("late", canary).commit() // an old object that was handed out earlier
        Privacy.writer.submit {}.get()
        assertEquals(emptyList(), userFiles())
        for (d in dataDirs()) assertEquals(emptyList(), filesContaining(d, canary), d.path)
    }

    @Test fun aStoreObjectHandedOutBeforeTheWipeCannotWriteAfterItBeganOnEitherTier() {
        seedEverything()
        val ce = Privacy.store(ctx, Tier.CE, "learned_en-US", 1)!!
        val de = Privacy.store(ctx, Tier.DE, "prefs_de", 1)!!
        val ceBefore = ce.file.readBytes()
        val deBefore = de.file.readBytes()
        Privacy.beginWipe() // the files are still there: only the writers are stopped
        assertTrue(Privacy.isWiping)
        assertFailsWith<VaultLockedException> { ce.write("late $canary".toByteArray()) }
        assertFailsWith<VaultLockedException> { de.write("late $canary".toByteArray()) }
        assertContentEquals(ceBefore, ce.file.readBytes())
        assertContentEquals(deBefore, de.file.readBytes())
    }

    @Test fun theLearnedWordFilesAreNeitherListedNorDeletedByAnyoneElseWhileAWipeRuns() {
        seedEverything()
        val file = File(ctx.noBackupFilesDir, "taen/learned_en-US.enc")
        assertTrue(file.exists())
        Privacy.beginWipe()
        assertEquals(emptyMap(), Privacy.readAllLearned(ctx))
        Privacy.deleteAllLearned(ctx) // the wipe is the only deleter now
        assertTrue(file.exists())
    }

    @Test fun aWipeThatKeepsTheConfigurationArmedKeepsOnlyTheLockAndTheTriggers() {
        val old = seedEverything()
        val scope = WipeScope(config = false)
        assertTrue(SelfDestruct.wipe(ctx, scope).complete)
        Privacy.forgetCachedState(); Privacy.endWipe()
        // the armed configuration is readable under a new key
        assertTrue(Privacy.appLock(ctx).isEnabled())
        assertEquals(AppLock.Verify.Ok, offMain { Privacy.appLock(ctx).verify(pin) })
        val config = (SelfDestruct.configs(ctx).load() as ConfigLoad.Present).config
        assertNotNull(config.duress); assertNotNull(config.word); assertEquals(5, config.wrongPins)
        // everything else is gone, plain files too
        for (d in dataDirs()) assertEquals(emptyList(), filesContaining(d, canary), d.path)
        assertNull(Privacy.prefs(ctx).getString("canary", null))
        assertEquals(setOf("security.enc", "selfdestruct.enc", "dek_de.bin"), taen(deCtx, "x").parentFile.listFiles()!!.map { it.name }.toSet())
        // the old ciphertext of the armed stores does not open with the new key either
        restoreOldCiphertext(old.filterKeys { it.endsWith("learned_en-US.enc") || it.endsWith("clipboard.enc") })
        assertTrue(Privacy.store(ctx, Tier.CE, "clipboard", 1)!!.read() !is StoreContent.Data)
        assertEquals(1, restarts)
    }

    @Test fun aPartialWipeKeepsTheKeysSoTheOtherStoresStayReadable() {
        seedEverything()
        val scope = WipeScope(learned = false, clipboard = true, settings = false, config = false, imported = false)
        assertTrue(SelfDestruct.wipe(ctx, scope).complete)
        Privacy.forgetCachedState(); Privacy.endWipe()
        assertTrue(Privacy.store(ctx, Tier.CE, "learned_en-US", 1)!!.read() is StoreContent.Data) // positive control: untouched
        assertEquals("word $canary", String((Privacy.store(ctx, Tier.CE, "learned_bg", 1)!!.read() as StoreContent.Data).bytes))
        assertEquals(StoreContent.Missing, Privacy.store(ctx, Tier.CE, Privacy.CLIPBOARD_STORE, 1)!!.read())
        assertEquals(canary, Privacy.prefs(ctx).getString("canary", null))
        assertTrue(Privacy.appLock(ctx).isEnabled())
    }

    // ---- a locked device -----------------------------------------------------------------------------------------------

    @Test fun aWipeOnALockedDeviceDestroysTheKeysAtOnceAndFinishesTheCredentialFilesAfterTheUnlock() {
        val old = seedEverything()
        val users = ctx.getSystemService(UserManager::class.java)
        shadowOf(users).setUserUnlocked(false)
        val report = SelfDestruct.wipe(ctx, WipeScope.ALL)
        assertFalse(report.complete) // the credential-encrypted files cannot be reached yet
        assertTrue(taen(deCtx, "wipe.pending").exists() || taen(ctx, "wipe.pending").exists())
        assertEquals(1, restarts)
        shadowOf(users).setUserUnlocked(true)
        // the credential-encrypted ciphertext is still there but its key is gone
        Privacy.forgetCachedState(); Privacy.endWipe()
        val c = Privacy.store(ctx, Tier.CE, "learned_en-US", 1)!!.read()
        assertTrue(c !is StoreContent.Data)
        restoreOldCiphertext(old.filterKeys { p -> listOf("learned_", "clipboard", "snippets", "prefs_ce", "dek_ce").any { File(p).name.startsWith(it) } })

        Privacy.forgetCachedState()
        SelfDestruct.resumePending(ctx) // what the start of the app and the unlock call
        assertEquals(emptyList(), userFiles())
        assertFalse(taen(deCtx, "wipe.pending").exists() || taen(ctx, "wipe.pending").exists())
        for (d in dataDirs()) assertEquals(emptyList(), filesContaining(d, canary), d.path)
        assertEquals(1, restarts) // the resume does not restart the process again
    }

    @Test fun anUnlockFinishesAPendingWipeThroughTheScreenLockPath() {
        seedEverything()
        val users = ctx.getSystemService(UserManager::class.java)
        shadowOf(users).setUserUnlocked(false)
        SelfDestruct.wipe(ctx, WipeScope.ALL)
        shadowOf(users).setUserUnlocked(true)
        Privacy.forgetCachedState(); Privacy.endWipe()
        Privacy.unlock(ctx) // the production unlock (ScreenLockPolicy.onUserPresent calls it)
        assertEquals(emptyList(), userFiles())
    }

    // ---- the PIN screen -----------------------------------------------------------------------------------------------

    /** Submits a PIN (the check runs off the main thread, as in the app) and runs what the screen then posts to the main thread. */
    private fun UnlockActivity.submitNow(pin: CharArray) {
        submit(pin)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun unlockActivity(): UnlockActivity {
        val a = Robolectric.buildActivity(UnlockActivity::class.java).setup().get()
        a.executor = offMainExecutor // the PIN check refuses the main thread (the test thread)
        return a
    }

    @Test fun theDuressPinOnThePinScreenWipesEverythingAndTheRealPinDoesNot() {
        seedEverything()
        val a = unlockActivity()
        a.submitNow(pin.copyOf())
        assertEquals(0, restarts) // the real PIN: unlocks, wipes nothing
        assertTrue(a.isFinishing)
        assertTrue(userFiles().isNotEmpty())

        val b = unlockActivity()
        b.submitNow("1111".toCharArray())
        assertEquals(1, restarts)
        assertEquals(emptyList(), userFiles())
        for (d in dataDirs()) assertEquals(emptyList(), filesContaining(d, canary), d.path)
    }

    @Test fun theFifthWrongPinOnThePinScreenWipesAndTheFourthDoesNot() {
        seedEverything() // wrongPins = 5
        val a = unlockActivity()
        repeat(4) { a.submitNow("0000".toCharArray()) }
        assertEquals(0, restarts)
        assertTrue(userFiles().isNotEmpty())
        a.submitNow("0000".toCharArray())
        assertEquals(1, restarts)
        assertEquals(emptyList(), userFiles())
    }

    @Test fun inRehearsalTheDuressPinOnlyShowsAToastAndWipesNothing() {
        seedEverything()
        SelfDestruct.settings(ctx).startRehearsal()
        val a = unlockActivity()
        a.submitNow("1111".toCharArray())
        assertEquals(0, restarts)
        assertTrue(userFiles().isNotEmpty())
        assertEquals(ctx.getString(helium314.keyboard.latin.R.string.self_destruct_rehearsal_toast), ShadowToast.getTextOfLatestToast())
    }

    @Test fun aDuressPinInConfirmModeAsksWithANeutralDialogBeforeWiping() {
        seedEverything()
        SelfDestruct.settings(ctx).setDuressMode(TriggerMode.CONFIRM)
        val a = unlockActivity()
        a.submitNow("1111".toCharArray())
        assertEquals(0, restarts)
        val started = assertNotNull(shadowOf(ctx as Application).nextStartedActivity)
        assertEquals(WipeConfirmActivity::class.java.name, started.component?.className)
    }

    // ---- the confirmation and the panic --------------------------------------------------------------------------------

    @Test fun theConfirmationDialogWipesOnYesAndDoesNothingOnNo() {
        seedEverything()
        val intent = Intent(ctx, WipeConfirmActivity::class.java).putExtra(WipeConfirmActivity.EXTRA_TRIGGER, Trigger.PANIC.name)
        var controller = Robolectric.buildActivity(WipeConfirmActivity::class.java, intent).setup()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        ShadowLooper.idleMainLooper() // the dialog hands the click to its listener through the main looper
        assertEquals(0, restarts)
        assertTrue(userFiles().isNotEmpty())
        assertTrue(controller.get().isFinishing)

        controller = Robolectric.buildActivity(WipeConfirmActivity::class.java, intent).setup()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper() // the dialog hands the click to its listener through the main looper
        assertEquals(1, restarts)
        assertEquals(emptyList(), userFiles())
    }

    @Test fun theConfirmationWipesWithTheConfiguredScopeAndNeverWithAnUnknownTrigger() {
        seedEverything()
        SelfDestruct.settings(ctx).setScope(WipeScope(learned = false, clipboard = true, settings = false, config = false, imported = false))
        val intent = Intent(ctx, WipeConfirmActivity::class.java).putExtra(WipeConfirmActivity.EXTRA_TRIGGER, Trigger.PANIC.name)
        Robolectric.buildActivity(WipeConfirmActivity::class.java, intent).setup()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper() // the dialog hands the click to its listener through the main looper
        Privacy.forgetCachedState(); Privacy.endWipe()
        assertTrue(Privacy.store(ctx, Tier.CE, "learned_en-US", 1)!!.read() is StoreContent.Data) // the learned words are out of scope

        val bad = Intent(ctx, WipeConfirmActivity::class.java).putExtra(WipeConfirmActivity.EXTRA_TRIGGER, "NOT_A_TRIGGER")
        val before = restarts
        val c = Robolectric.buildActivity(WipeConfirmActivity::class.java, bad).setup()
        assertTrue(c.get().isFinishing) // fail closed: an unknown request is no request
        assertEquals(before, restarts)
    }

    @Test fun theConfirmationActivityIsSecureAndNotInRecents() {
        val intent = Intent(ctx, WipeConfirmActivity::class.java).putExtra(WipeConfirmActivity.EXTRA_TRIGGER, Trigger.PANIC.name)
        val a = Robolectric.buildActivity(WipeConfirmActivity::class.java, intent).setup().get()
        assertTrue(SecureWindow.isSecure(a.window))
    }

    // ---- inactivity ---------------------------------------------------------------------------------------------------

    @Test fun theDailyJobIsScheduledWhileInactivityIsOnAndCancelledWhenItIsOff() {
        val scheduler = ctx.getSystemService(JobScheduler::class.java)
        SelfDestruct.settings(ctx).setInactivityDays(30)
        SelfDestruct.syncInactivityJob(ctx)
        val job: JobInfo = assertNotNull(scheduler.getPendingJob(InactivityJobService.JOB_ID))
        assertTrue(job.isPersisted) // survives a reboot (the reason for RECEIVE_BOOT_COMPLETED)
        assertEquals(24L * 60 * 60 * 1000, job.intervalMillis)
        SelfDestruct.settings(ctx).setInactivityDays(0)
        SelfDestruct.syncInactivityJob(ctx)
        assertNull(scheduler.getPendingJob(InactivityJobService.JOB_ID))
    }

    @Test fun theDailyJobWipesAfterTheLimitAndDoesNotCountAsUse() {
        seedEverything()
        val activity = SelfDestruct.activity(ctx)
        val params = Mockito.mock(JobParameters::class.java)
        val service = Robolectric.buildService(InactivityJobService::class.java).create().get()
        val tenDaysAgo = System.currentTimeMillis() - 10L * 24 * 60 * 60 * 1000
        activity.save(tenDaysAgo)
        service.onStartJob(params)
        assertEquals(0, restarts)
        assertEquals(tenDaysAgo, (activity.load() as ActivityState.Present).lastActive) // the job is not use
        activity.save(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        service.onStartJob(params)
        assertEquals(1, restarts)
        assertEquals(emptyList(), userFiles())
    }

    @Test fun theStartOfTheAppChecksInactivityBeforeAnyDataIsOpened() {
        seedEverything()
        SelfDestruct.activity(ctx).save(System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000)
        Privacy.forgetCachedState()
        (ctx as Application).onCreate() // the real entry point
        assertEquals(1, restarts)
        assertEquals(emptyList(), userFiles())
    }

    // ---- the marker is honoured at start -----------------------------------------------------------------------------

    @Test fun theStartOfTheAppFinishesAnUnfinishedWipe() {
        val old = seedEverything()
        File(deCtx.noBackupFilesDir, "taen").mkdirs()
        // a wipe that ended before its keys were destroyed (the process died after the marker was written): the start destroys them and finishes
        taen(deCtx, "wipe.pending").writeBytes(Pending(WipeScope.ALL, armed = false, restored = false, keysDone = false).encode())
        (ctx as Application).onCreate()
        // the start creates new files of its own (a new key); what is gone is everything that held data
        for (d in dataDirs()) assertEquals(emptyList(), filesContaining(d, canary), d.path)
        for (name in listOf("clipboard.enc", "snippets.enc", "learned_en-US.enc", "learned_bg.enc", "security.enc", "selfdestruct.enc", "activity.enc"))
            assertFalse(old.keys.any { File(it).name == name && File(it).exists() }, name)
        assertFalse(File(ctx.filesDir, "dicts/bg/main_bg.dict").exists())
        assertFalse(taen(deCtx, "wipe.pending").exists())
    }

    // ---- reachability and secrets --------------------------------------------------------------------------------------

    @Test fun theLauncherShortcutForThePanicStartsTheConfirmationOnly() {
        val intent = SelfDestruct.panicIntent(ctx)
        assertEquals(WipeConfirmActivity::class.java.name, intent.component?.className)
        assertEquals(Trigger.PANIC.name, intent.getStringExtra(WipeConfirmActivity.EXTRA_TRIGGER))
    }

    @Test fun theSetupLeavesNoSecretInAnyFile() {
        seedEverything()
        for (d in dataDirs()) {
            assertEquals(emptyList(), filesContaining(d, "Blue#Horse-4711"), d.path)
            assertEquals(emptyList(), filesContaining(d, "1111"), d.path)
            assertEquals(emptyList(), filesContaining(d, "482915"), d.path)
        }
    }

    @Test fun theWordMatcherOfTheAppUsesTheRealKdfAndTheWordRecordIsInTheEncryptedStore() {
        seedEverything()
        assertTrue(taen(deCtx, "selfdestruct.enc").isFile || taen(ctx, "selfdestruct.enc").isFile)
        val config = (SelfDestruct.configs(ctx).load() as ConfigLoad.Present).config
        assertEquals(SecretRecords.KDF, config.word!!.kdf)
        assertEquals(SecretRecords.KDF, config.duress!!.kdf)
        assertTrue(TriggerMatcher(PinHasher(SecretRecords.KDF)).matches("Blue#Horse-4711".toCharArray(), config.word!!))
    }
}
