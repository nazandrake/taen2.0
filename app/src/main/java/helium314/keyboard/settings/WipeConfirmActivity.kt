// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import helium314.keyboard.latin.R
import helium314.keyboard.latin.privacy.SecureWindow
import helium314.keyboard.latin.privacy.SelfDestruct
import helium314.keyboard.latin.privacy.Trigger

/**
 * The neutral "Clear typing data?" question: started by a trigger in confirm mode, by the launcher shortcut "Panic" and
 * by the settings button. "Clear" wipes with the configured scope; "Cancel" or an unknown request does nothing. Not shown in recents, not captured (P12).
 */
class WipeConfirmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecureWindow.apply(window)
        // fail closed: a request that names no trigger is no request
        if (Trigger.entries.none { it.name == intent?.getStringExtra(EXTRA_TRIGGER) }) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.self_destruct_confirm_title)
            .setMessage(R.string.self_destruct_confirm_message)
            .setPositiveButton(R.string.self_destruct_confirm_yes) { _, _ ->
                SelfDestruct.wipeConfirmed(this)
                finish()
            }
            .setNegativeButton(R.string.self_destruct_confirm_no) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    companion object {
        const val EXTRA_TRIGGER = "trigger"
    }
}
