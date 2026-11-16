// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import helium314.keyboard.latin.privacy.SecureWindow

/**
 * P20: the only place that asks for the microphone permission, started by a tap on the voice key when a model is on the device and
 * the permission is missing (VoiceGate). Invisible apart from the system dialog; ends as soon as the user has answered. The user taps the
 * voice key again to record: nothing starts by itself.
 */
class VoicePermissionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecureWindow.apply(window)
        if (savedInstanceState == null) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish() // granted or not: the user taps the voice key again
    }

    companion object {
        private const val REQUEST = 20

        fun granted(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context) {
            context.startActivity(Intent(context, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
