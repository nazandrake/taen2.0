// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.KeyboardState
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.keyboard.internal.ShiftMode
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.UnlockActivity
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The native dictionary library is not loadable on the host: a dictionary link that reaches the dialog reads "not a dictionary". */
@Implements(com.android.inputmethod.latin.utils.BinaryDictionaryUtils::class)
object ShadowNoDictionaryHeader {
    @Implementation
    @JvmStatic
    fun getHeaderWithOffsetAndLength(dictFile: File, offset: Long, length: Long): helium314.keyboard.latin.makedict.DictionaryHeader = throw java.io.IOException()
}

/**
 * The app lock in the running app (design 7.4 item 7): the record is an encrypted store of the device-protected tier, a screen
 * lock closes the unlock, the settings activity and the keyboard's clipboard key ask for the PIN, and the PIN screen is secure.
 * The Argon2id parameters are the real ones (AppLock.PIN_KDF).
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowNoDictionaryHeader::class])
class AppLockWiringTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val pin = "482915".toCharArray()
    private val deStore get() = File(ctx.createDeviceProtectedStorageContext().noBackupFilesDir, "taen/security.enc")

    @Before fun before() { Privacy.unlock(ctx); Privacy.forgetCachedState() }
    @After fun after() { Privacy.unlock(ctx); Privacy.forgetCachedState() }

    private fun enableAndUnlock() {
        val lock = Privacy.appLock(ctx)
        assertEquals(AppLock.SetResult.OK, offMain { lock.enable(pin) })
        assertEquals(AppLock.Verify.Ok, offMain { lock.verify(pin) })
    }

    @Test fun theRecordIsAnEncryptedFileWithoutThePinAndSurvivesARestartOfTheApp() {
        val lock = Privacy.appLock(ctx)
        assertFalse(lock.needsUnlock()) // no lock set yet
        assertFalse(deStore.exists())
        assertEquals(AppLock.SetResult.OK, offMain { lock.enable(pin) })
        assertTrue(deStore.isFile)
        for (needle in listOf("482915", "TAENbad")) assertEquals(emptyList(), filesContaining(ctx.dataDir, needle) + filesContaining(ctx.createDeviceProtectedStorageContext().dataDir, needle))
        // the control: the scanner does find a plain file with the digits
        val control = File(ctx.noBackupFilesDir, "control.txt").apply { writeText("482915") }
        assertEquals(listOf("control.txt"), filesContaining(ctx.dataDir, "482915"))
        control.delete()

        Privacy.forgetCachedState() // the process starts again: new objects, same files
        val again = Privacy.appLock(ctx)
        assertTrue(again.isEnabled())
        assertTrue(again.needsUnlock()) // an unlock never survives a restart
        assertEquals(AppLock.Verify.Ok, offMain { again.verify(pin) })
        assertFalse(again.needsUnlock())
    }

    @Test fun aScreenLockClosesTheUnlock() {
        enableAndUnlock()
        val lock = Privacy.appLock(ctx)
        assertFalse(lock.needsUnlock())
        Privacy.lock(ctx) // what the screen-off receiver of the keyboard service calls
        assertTrue(lock.needsUnlock())
        Privacy.unlock(ctx)
        assertTrue(lock.needsUnlock()) // the user is back, but the PIN is still needed
    }

    @Test fun theGateAsksForThePinOnlyWhenALockIsSetAndClosed() {
        assertTrue(AppLockGate.openOrAskPin(ctx)) // no lock: nothing to ask
        assertNull(shadowOf(ctx as android.app.Application).nextStartedActivity)
        enableAndUnlock()
        assertTrue(AppLockGate.openOrAskPin(ctx)) // unlocked
        assertNull(shadowOf(ctx as android.app.Application).nextStartedActivity)
        Privacy.appLock(ctx).relock()
        assertFalse(AppLockGate.openOrAskPin(ctx))
        val started = assertNotNull(shadowOf(ctx as android.app.Application).nextStartedActivity)
        assertEquals(UnlockActivity::class.java.name, started.component?.className)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertFalse(AppLockGate.openOrAskPin(null)) // no context: closed
    }

    // ---- the clipboard key of the keyboard ---------------------------------------------------------------------------

    private class Actions : KeyboardState.SwitchActions {
        var clipboard = 0
        override fun setAlphabetKeyboard(shiftMode: ShiftMode) {}
        override fun setEmojiKeyboard() {}
        override fun setClipboardKeyboard() { clipboard++ }
        override fun setNumpadKeyboard() {}
        override fun setDpadKeyboard() {}
        override fun setSymbolsKeyboard() {}
        override fun setSymbolsShiftedKeyboard() {}
        override fun startDoubleTapShiftKeyTimer() {}
        override fun popDoubleTapShiftKeyTimer() = false
        override fun cancelDoubleTapShiftKeyTimer() {}
        override fun setOneHandedModeEnabled(enabled: Boolean) {}
        override fun switchOneHandedMode() {}
        override fun setFloatingKeyboardEnabled(enabled: Boolean) {}
    }

    private fun pressClipboardKey(state: KeyboardState) =
        state.onEvent(Event.createSoftwareKeypressEvent(KeyCode.CLIPBOARD, 0, 0, 0, false), 0, null)

    @Test fun theClipboardHistoryOpensOnlyWithTheLockOpenAndClosesAlways() {
        Settings.init(ctx)
        Settings.getInstance().loadSettings(ctx, java.util.Locale.ENGLISH, helium314.keyboard.latin.InputAttributes(android.view.inputmethod.EditorInfo(), false, ctx.packageName))
        assertTrue(Settings.getValues().mClipboardHistoryEnabled)
        val actions = Actions()
        val state = KeyboardState(actions)
        pressClipboardKey(state) // no lock: opens (positive control for the cases below)
        assertEquals(1, actions.clipboard)
        pressClipboardKey(state) // the same key closes it again
        assertEquals(1, actions.clipboard)

        enableAndUnlock()
        Privacy.appLock(ctx).relock()
        while (shadowOf(ctx as android.app.Application).nextStartedActivity != null) { /* drop the earlier ones */ }
        val locked = Actions()
        val lockedState = KeyboardState(locked)
        pressClipboardKey(lockedState) // closed: the PIN screen starts and the history does not open
        assertEquals(0, locked.clipboard)
        assertEquals(UnlockActivity::class.java.name, shadowOf(ctx as android.app.Application).nextStartedActivity?.component?.className)

        offMain { Privacy.appLock(ctx).verify(pin) }
        pressClipboardKey(lockedState) // unlocked: opens
        assertEquals(1, locked.clipboard)
        Privacy.appLock(ctx).relock() // the lock closes while the history is shown: closing the view is still possible
        pressClipboardKey(lockedState)
        assertEquals(1, locked.clipboard)
        assertNull(shadowOf(ctx as android.app.Application).nextStartedActivity)
    }

    // ---- the activities ---------------------------------------------------------------------------------------------

    @Test fun thePinScreenIsSecureAndSurvivesNothing() {
        val activity = Robolectric.buildActivity(UnlockActivity::class.java).setup().get()
        assertTrue(SecureWindow.isSecure(activity.window))
    }

    // ---- the exported SettingsActivity2 copies an incoming dictionary link only after the unlock -------------

    private val dictUri = android.net.Uri.parse("content://taen.test/words.dict")
    private fun viewIntent() = Intent(Intent.ACTION_VIEW, dictUri).setClass(ctx, helium314.keyboard.settings.SettingsActivity2::class.java)

    /** The link's stream; counts what was read from it (the dictionary dialog deletes the copy again, so the stream shows it). */
    private class CountingStream(private val size: Long) : java.io.InputStream() {
        var read = 0L
        override fun read(): Int = if (read < size) { read++; 7 } else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (read >= size) return -1
            val n = minOf(len.toLong(), size - read).toInt()
            java.util.Arrays.fill(b, off, off + n, 7.toByte())
            read += n
            return n
        }
    }

    private fun link(size: Long) = CountingStream(size).also { shadowOf(ctx.contentResolver).registerInputStream(dictUri, it) }

    /** The import runs in FileUtils on its own thread and answers on the main looper: run the looper until the import has ended. */
    private fun importEnds() {
        val end = System.nanoTime() + 20_000_000_000L
        do {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (!helium314.keyboard.latin.common.FileUtils.isImportRunning()) break
            Thread.sleep(5)
        } while (System.nanoTime() < end)
        assertFalse(helium314.keyboard.latin.common.FileUtils.isImportRunning(), "the import did not end")
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test fun anIncomingDictionaryLinkIsNotReadBeforeTheUnlock() {
        enableAndUnlock()
        Privacy.appLock(ctx).relock()
        val stream = link(100)
        val controller = Robolectric.buildActivity(helium314.keyboard.settings.SettingsActivity2::class.java, viewIntent()).setup()
        assertEquals(UnlockActivity::class.java.name, shadowOf(controller.get()).nextStartedActivity?.component?.className) // the PIN is asked
        assertEquals(0L, stream.read, "nothing may be copied while the lock is closed")
        assertFalse(File(ctx.cacheDir, "temp_dict").exists())
        controller.pause().stop().destroy()
        importEnds() // an import that had been started would have ended here
        assertEquals(0L, stream.read) // a cancelled PIN screen copies nothing either
    }

    @Test fun anIncomingDictionaryLinkIsReadAfterTheUnlock() { // positive control: the import still works behind the lock
        enableAndUnlock()
        Privacy.appLock(ctx).relock()
        val stream = link(100)
        val controller = Robolectric.buildActivity(helium314.keyboard.settings.SettingsActivity2::class.java, viewIntent()).setup()
        assertEquals(0L, stream.read)
        assertEquals(AppLock.Verify.Ok, offMain { Privacy.appLock(ctx).verify(pin) }) // the PIN screen finished
        controller.pause().resume()
        importEnds()
        assertEquals(100L, stream.read)
        controller.pause().stop().destroy()
    }

    @Test fun anIncomingDictionaryLinkWithoutALockIsRead() {
        val stream = link(100)
        val controller = Robolectric.buildActivity(helium314.keyboard.settings.SettingsActivity2::class.java, viewIntent()).setup()
        importEnds()
        assertEquals(100L, stream.read)
        controller.pause().stop().destroy()
    }

    @Test fun anIncomingDictionaryLinkIsCappedAndLeavesNothingBehind() {
        val cap = helium314.keyboard.latin.common.FileUtils.MAX_EXTERNAL_IMPORT_BYTES
        val over = cap + 1024 * 1024
        val stream = link(over)
        val controller = Robolectric.buildActivity(helium314.keyboard.settings.SettingsActivity2::class.java, viewIntent()).setup()
        importEnds()
        assertTrue(stream.read > cap && stream.read < over, "read ${stream.read}") // the copy ran up to the cap (positive control) and stopped there, not at the end of the stream
        assertFalse(File(ctx.cacheDir, "temp_dict").exists())
        controller.pause().stop().destroy()
    }

    @Test fun theSettingsAskForThePinWhenTheLockIsClosedAndNotOtherwise() {
        // no lock: the settings open and no PIN screen starts
        var controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        assertTrue(SecureWindow.isSecure(controller.get().window)) // P12
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        controller.pause().stop().destroy()

        enableAndUnlock()
        Privacy.appLock(ctx).relock()
        controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val started = assertNotNull(shadowOf(controller.get()).nextStartedActivity, "the settings must start the PIN screen")
        assertEquals(UnlockActivity::class.java.name, started.component?.className)
        controller.pause().stop().destroy()

        // unlocked: opens without asking
        offMain { Privacy.appLock(ctx).verify(pin) }
        controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    // ---- Settings > Profiles asks for the PIN first ---------------------------------------------------------------

    private fun profilesIntent() = Intent(ctx, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PROFILES_SETTINGS, true)

    @Test fun profilesAskForThePinFirstWhenTheLockIsClosedAndOpenWithoutItOtherwise() {
        enableAndUnlock()
        Privacy.appLock(ctx).relock()
        var controller = Robolectric.buildActivity(SettingsActivity::class.java, profilesIntent()).setup()
        val started = assertNotNull(shadowOf(controller.get()).nextStartedActivity, "Profiles must start the PIN screen")
        assertEquals(UnlockActivity::class.java.name, started.component?.className)
        controller.pause().stop().destroy()

        offMain { Privacy.appLock(ctx).verify(pin) } // positive control: with the lock open the same intent starts no PIN screen
        controller = Robolectric.buildActivity(SettingsActivity::class.java, profilesIntent()).setup()
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        controller.pause().stop().destroy()
    }
}
