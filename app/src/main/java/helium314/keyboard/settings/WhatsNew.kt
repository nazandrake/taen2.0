// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import java.io.IOException

/**
 * The "What's new" box: shown once, the first time the settings open after the app version changed. The text is the asset [ASSET], which the build writes
 * from the CHANGELOG.md entry of the running versionName (buildSrc taen.WhatsNew; it is empty when there is none: no box). The one stored value is the
 * version code of the box that was shown ([Settings.PREF_WHATS_NEW_SHOWN], an int in the device-protected preferences, never part of a profile).
 *
 * A stored version below the running one shows the box; an equal or higher one (a downgrade) shows nothing. With nothing stored, an update shows the box
 * (2.0.2 stored no key) and a fresh install stores the running version and shows nothing; the two are told apart by the install times of our own package,
 * which need no permission. A dismissal that cannot be stored shows the box again next time.
 */
object WhatsNew {
    /** Name of the asset; the same constant as taen.WhatsNew.ASSET in buildSrc (WhatsNewTest reads the asset that the build made). */
    const val ASSET = "whats_new.txt"

    /** The notes to show now, or null. Stores the running version silently for a fresh install and when there is nothing to show. */
    fun pending(prefs: SharedPreferences, versionCode: Int, isUpdate: Boolean, notes: String?): String? {
        val stored = if (prefs.contains(Settings.PREF_WHATS_NEW_SHOWN)) prefs.getInt(Settings.PREF_WHATS_NEW_SHOWN, 0) else null
        val show = if (stored == null) isUpdate else stored < versionCode
        if (show && !notes.isNullOrBlank()) return notes
        if (stored == null || show) markShown(prefs, versionCode) // a fresh install, or no text for this version: remembered, no box
        return null
    }

    /** Stores the version of the box that was shown. False when it could not be stored: the box then shows again next time. */
    fun markShown(prefs: SharedPreferences, versionCode: Int): Boolean = prefs.edit().putInt(Settings.PREF_WHATS_NEW_SHOWN, versionCode).commit()

    /** The notes asset, trimmed; null when it is missing, unreadable or empty. */
    fun readNotes(assets: AssetManager, name: String = ASSET): String? =
        try { assets.open(name).use { String(it.readBytes(), Charsets.UTF_8) }.trim().takeIf { it.isNotEmpty() } } catch (_: IOException) { null }

    /** An update has been installed over an earlier version; a fresh install has equal times. */
    fun isUpdate(info: PackageInfo): Boolean = info.firstInstallTime != info.lastUpdateTime

    /** What the settings screen asks when it opens: the notes to show, or null. */
    @Suppress("DEPRECATION") // the flags overload is the one that works on every supported SDK; no flags are needed
    fun check(ctx: Context): String? {
        val info = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0) } catch (_: PackageManager.NameNotFoundException) { null }
        return pending(ctx.prefs(), BuildConfig.VERSION_CODE, info != null && isUpdate(info), readNotes(ctx.assets))
    }
}
