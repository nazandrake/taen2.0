// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.quick

import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import helium314.keyboard.latin.privacy.SecureWindow

/**
 * Started by [SwitchKeyboardTileService] only (not exported), in a task of its own (taskAffinity ""): in the task of the app it would bring
 * the settings to the front without the PIN and leave the user there. Invisible: it asks for the system's keyboard picker once its window has
 * kept the focus for [SETTLE_MS]. The system ignores the request of a window that has not the focus, and closes a picker that opens while the
 * notification shade still collapses (measured on Android 12), so the request waits until the focus has settled. The activity ends when
 * the focus comes back after the picker had it; ending it earlier closes the picker. It also ends when the focus has not settled
 * after [GIVE_UP_MS] from its creation, and when no picker has taken the focus [PICKER_MS] after the request, so that an invisible window
 * never stays over the screen. A recreated activity (process death) asks for nothing and ends.
 * Shows, reads and stores nothing.
 */
class SwitchKeyboardActivity : ComponentActivity() {
    private var waiting = false
    private var asked = false
    private var pickerHasFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecureWindow.apply(window)
        if (savedInstanceState != null) { finish(); return }
        window.decorView.postDelayed({ if (!asked) finish() }, GIVE_UP_MS) // the focus never settled; once asked, the picker has its own timeout
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (isFinishing) return
        if (asked) { // the picker took the focus; when it comes back the picker is gone
            if (!hasFocus) pickerHasFocus = true else if (pickerHasFocus) finish()
            return
        }
        if (!hasFocus || waiting) return
        waiting = true
        window.decorView.postDelayed({ ask() }, SETTLE_MS)
    }

    private fun ask() {
        waiting = false
        if (asked || isFinishing || !hasWindowFocus()) return // the focus moved on: the next focus gain tries again
        asked = true
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        window.decorView.postDelayed({ if (!pickerHasFocus) finish() }, PICKER_MS) // from the request: the picker is not up yet at the moment of asking
    }

    companion object {
        const val SETTLE_MS = 800L
        const val GIVE_UP_MS = 5000L
        const val PICKER_MS = 3000L
    }
}
