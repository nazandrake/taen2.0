// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.Context
import android.widget.Toast
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.SettingsValues

/**
 * The two warnings of the anti-snooping controls (P13 accessibility services, P14 covered keyboard). They are short toasts that
 * name no typed text. [show] is replaceable so that tests see the message without a toast.
 */
object PrivacyWarnings {
    @Volatile var show: (Context, String) -> Unit = { context, text -> Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    @Volatile var clock: () -> Long = System::currentTimeMillis
    @Volatile var services: (Context) -> List<A11yService> = { A11yWatch.enabledServices(it) }

    private var warner = A11yWatch.Warner { clock() }
    private var lastOverlayToast = Long.MIN_VALUE

    /** Forgets what was warned about (tests). */
    internal fun reset() {
        warner = A11yWatch.Warner { clock() }
        lastOverlayToast = Long.MIN_VALUE
    }

    /** At the start of input in a field: warns when an accessibility service that can read the screen is on. Returns whether it warned. */
    @JvmStatic
    fun onStartInput(context: Context, settings: SettingsValues): Boolean {
        if (!settings.mA11yWarningEnabled) return false
        val risky = A11yWatch.risky(services(context))
        if (!warner.shouldWarn(risky, settings.mPrivacy.passwordField)) return false
        show(context, context.getString(R.string.privacy_a11y_warning, risky.joinToString(", ") { it.label }))
        return true
    }

    /** A touch was covered by another window; [blocked] says it was dropped. At most one toast per [OverlayGuard.WARN_INTERVAL_MILLIS]. */
    @JvmStatic
    fun onOverlay(context: Context, blocked: Boolean) {
        val now = clock()
        if (lastOverlayToast != Long.MIN_VALUE && now - lastOverlayToast < OverlayGuard.WARN_INTERVAL_MILLIS) return
        lastOverlayToast = now
        show(context, context.getString(if (blocked) R.string.privacy_overlay_blocked else R.string.privacy_overlay_warning))
    }
}
