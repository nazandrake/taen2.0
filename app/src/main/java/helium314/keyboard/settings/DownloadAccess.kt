// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Intent
import android.net.Uri

/**
 * The one place that turns a pinned download address into a browser intent. The app has no network: it never opens the address itself. The system
 * browser downloads the file, and the user installs it with the picker (VoiceModelAccess.PickDownloads, which is also the picker of the voice model).
 * privacyCheck allows ACTION_VIEW with an address here and in the About page links only.
 */
object DownloadAccess {
    /** A plain ACTION_VIEW, no component: the system chooses the browser. */
    fun browserIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
