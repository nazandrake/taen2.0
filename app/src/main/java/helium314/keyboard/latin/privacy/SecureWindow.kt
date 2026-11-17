// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager

/**
 * P12: FLAG_SECURE keeps a window out of screenshots, screen recordings and the recent-apps thumbnail. Every activity of
 * the app calls [apply] (privacyCheck fails otherwise); the keyboard window calls [applyToKeyboard] with the user's setting.
 * The target app's own window and the system's screen capture of other windows are not affected (PRIVACY.md).
 */
object SecureWindow {
    fun isSecure(window: Window?): Boolean = window != null && window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    /** Activities: always secure. */
    fun apply(window: Window?) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** The keyboard window: secure when [enabled], plain otherwise. */
    fun applyToKeyboard(window: Window?, enabled: Boolean) {
        if (window == null) return
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /**
     * P13: from API 34 an accessibility service that does not declare itself an accessibility tool does not see this view and its
     * descendants. Earlier versions have no such control (a recorded gap on the reference phone, which runs API 32).
     */
    fun markAccessibilitySensitive(view: View?) {
        if (view != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            view.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
    }
}
