// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.Context
import android.content.Intent
import helium314.keyboard.settings.UnlockActivity

/** The places that need the app lock open (the settings activity, the clipboard history in the keyboard) ask here. */
object AppLockGate {
    /**
     * True when no lock is set or it is unlocked (the unlock window is extended). Otherwise opens the PIN screen and returns false;
     * the caller does nothing and the user repeats the action after entering the PIN. No context: closed (fail closed).
     */
    @JvmStatic
    fun openOrAskPin(context: Context?): Boolean {
        if (context == null) return false
        val lock = Privacy.appLock(context)
        if (!lock.needsUnlock()) {
            lock.touch()
            return true
        }
        context.startActivity(Intent(context, UnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return false
    }
}
