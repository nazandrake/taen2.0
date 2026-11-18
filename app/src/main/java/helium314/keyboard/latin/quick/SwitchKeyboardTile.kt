// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.quick

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * The "Switch keyboard" Quick Settings tile: a tap opens the system's keyboard picker (some apps only start with a system keyboard).
 * The tile holds no state and no data, needs no permission and starts one activity of this app ([SwitchKeyboardActivity]), which asks for
 * the picker: the platform shows the picker only on behalf of a window that has focus, which a tile service has not.
 */
object SwitchKeyboardTile {
    /** A locked phone is unlocked first (the picker lists every installed keyboard); an unlocked one opens at once. */
    fun click(locked: Boolean, unlockAndRun: (Runnable) -> Unit, open: () -> Unit) {
        if (locked) unlockAndRun(Runnable { open() }) else open()
    }

    /** Collapsing the shade with an Intent is deprecated and refused from Android 14, which takes a PendingIntent. */
    fun usesPendingIntent(sdk: Int) = sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
}

class SwitchKeyboardTileService : TileService() {
    override fun onStartListening() {
        qsTile?.let { it.state = Tile.STATE_INACTIVE; it.updateTile() } // an action, never on or off
    }

    override fun onClick() {
        val intent = Intent(this, SwitchKeyboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        SwitchKeyboardTile.click(isLocked, ::unlockAndRun) {
            if (SwitchKeyboardTile.usesPendingIntent(Build.VERSION.SDK_INT))
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }
}
