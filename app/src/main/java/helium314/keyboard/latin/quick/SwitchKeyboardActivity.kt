// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.quick

import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import helium314.keyboard.latin.privacy.SecureWindow

/**
 * Started by [SwitchKeyboardTileService] only (not exported). Invisible: it asks for the system's keyboard picker once its window has kept the
 * focus for [SETTLE_MS]. The system ignores the request of a window that has not the focus, and closes a picker that opens while the
 * notification shade still collapses (measured on Android 12), so the request waits until the focus has settled. The activity ends when
 * the focus comes back after the picker had it; ending it earlier closes the picker. Shows, reads and stores nothing.
 */
class SwitchKeyboardActivity : ComponentActivity() {
    private var waiting = false
    private var asked = false
    private var pickerHasFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecureWindow.apply(window)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
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
    }

    companion object {
        const val SETTLE_MS = 800L
    }
}
