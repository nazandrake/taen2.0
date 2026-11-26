// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.compat

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.UserManager

/**
 * Whether the device is locked. A keyguard that cannot be asked (no service, a system call that fails) counts as locked: the restrictive answer
 * (P19: nothing is learned, stored or shown then).
 */
fun isDeviceLocked(context: Context): Boolean = runCatching {
    val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) keyguardManager.isDeviceLocked else keyguardManager.isKeyguardLocked
}.getOrDefault(true)

fun isUserLocked(context: Context): Boolean {
    val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
        !userManager.isUserUnlocked
    else
        false
}
