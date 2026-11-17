// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

/** An enabled accessibility service, reduced to what the warning needs. [isTool] is null where the platform cannot tell (before API 34). */
class A11yService(val id: String, val label: String, val readsContent: Boolean, val isTool: Boolean?)

/**
 * P13: warns about accessibility services that can read the screen or the typed text. The keyboard cannot stop them (PRIVACY.md);
 * it can only say that one is on. A service that declares itself an accessibility tool (API 34+) is not warned about.
 */
object A11yWatch {
    /** In a password field the warning repeats after this time, because that is where the user is about to type a secret. */
    const val PASSWORD_REPEAT_MILLIS = 60_000L

    fun risky(services: List<A11yService>): List<A11yService> = services.filter { it.readsContent && it.isTool != true }

    /** The enabled services. A service reads content when it may retrieve window content or is told when text changes. */
    fun enabledServices(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): List<A11yService> {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return emptyList()
        val enabled = try {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        } catch (_: RuntimeException) {
            return emptyList() // nothing readable: the status screen says so, there is nothing to warn about
        }
        return enabled.map { info ->
            val reads = info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0 ||
                info.eventTypes and AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED != 0
            val tool = if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) info.isAccessibilityTool else null
            A11yService(info.id, label(context, info), reads, tool)
        }
    }

    private fun label(context: Context, info: AccessibilityServiceInfo): String =
        runCatching { info.resolveInfo.loadLabel(context.packageManager).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: info.id

    /** Decides when the warning is shown: when the set of risky services changes, and again in password fields after [PASSWORD_REPEAT_MILLIS]. */
    class Warner(private val clock: () -> Long) {
        private var warned: Set<String> = emptySet()
        private var lastWarning = Long.MIN_VALUE

        fun shouldWarn(risky: List<A11yService>, passwordField: Boolean): Boolean {
            val ids = risky.map { it.id }.toSet()
            if (ids.isEmpty()) {
                warned = emptySet()
                return false
            }
            val now = clock()
            val news = !warned.containsAll(ids)
            val repeat = passwordField && now - lastWarning >= PASSWORD_REPEAT_MILLIS
            warned = ids
            if (!news && !repeat) return false
            lastWarning = now
            return true
        }
    }
}
