// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.app.KeyguardManager
import android.content.Context
import helium314.keyboard.latin.personalization.PersonalizationHelper
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log

/**
 * P10/P19: while the screen is locked the keyboard stores, learns and loads nothing of the credential-encrypted tier
 * (clipboard history, learned words, personal dictionary, protected preferences). The screen-off broadcast locks, the
 * keyguard being dismissed (or a screen-on without a keyguard) unlocks. Driven by the receiver in [LatinIME].
 */
object ScreenLockPolicy {
    private const val TAG = "ScreenLockPolicy"
    private const val SAVE_WAIT_MILLIS = 2000L

    /** Screen off: the learned words are saved first, then the tier is closed and wiped from memory. */
    @JvmStatic
    fun onScreenOff(context: Context) {
        PersonalizationHelper.saveAndEvictAll(SAVE_WAIT_MILLIS)
        Privacy.lock(context)
        Log.i(TAG, "screen off: the credential-encrypted storage is closed")
    }

    /** The user is present (keyguard dismissed). */
    @JvmStatic
    fun onUserPresent(context: Context) {
        Privacy.unlock(context)
        Settings.getInstance().reloadForCurrentField() // a field that started before this broadcast was decided as locked
        Log.i(TAG, "user present: the credential-encrypted storage is open")
    }

    /** Screen on: without a keyguard there is no lock to wait for. */
    @JvmStatic
    fun onScreenOn(context: Context) {
        if (isKeyguardLocked(context)) return
        Privacy.unlock(context)
        Settings.getInstance().reloadForCurrentField()
        Log.i(TAG, "screen on without keyguard: the credential-encrypted storage is open")
    }

    /** At service start: begin locked when the keyguard is up (typing a password into it must not be stored either). */
    @JvmStatic
    fun sync(context: Context) {
        if (isKeyguardLocked(context)) Privacy.lock(context)
    }

    private fun isKeyguardLocked(context: Context): Boolean =
        runCatching { (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked }.getOrDefault(false)
}
