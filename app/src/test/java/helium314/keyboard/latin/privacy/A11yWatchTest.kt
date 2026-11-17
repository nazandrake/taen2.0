// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P13: a warning when an enabled accessibility service can read what is on the screen and is not a declared accessibility tool. */
@RunWith(RobolectricTestRunner::class)
class A11yWatchTest {
    private fun service(id: String, reads: Boolean = true, tool: Boolean? = null) = A11yService(id, id, reads, tool)

    // ---- the core behaviour -----------------------------------------------------------------------------------

    @Test fun aServiceThatReadsTheScreenIsRiskyAndOneThatCannotIsNot() {
        val watcher = service("a/Watcher")
        val blind = service("b/Blind", reads = false)
        assertEquals(listOf(watcher), A11yWatch.risky(listOf(watcher, blind)))
    }

    // ---- the whole contract -----------------------------------------------------------------------------------

    @Test fun aDeclaredAccessibilityToolIsNotWarnedAboutButAnUnknownOrOrdinaryServiceIs() {
        val tool = service("t/Tool", tool = true)
        val ordinary = service("o/Ordinary", tool = false)
        val unknown = service("u/Unknown", tool = null) // before API 34 the platform cannot say: treated as risky
        assertEquals(listOf(ordinary, unknown), A11yWatch.risky(listOf(tool, ordinary, unknown)))
        assertEquals(emptyList(), A11yWatch.risky(emptyList()))
        // a tool that cannot read anything is no risk either way
        assertEquals(emptyList(), A11yWatch.risky(listOf(service("t/Blind", reads = false, tool = false))))
    }

    @Test fun theWarnerWarnsWhenTheSetOfRiskyServicesChanges() {
        var now = 1_000_000L
        val warner = A11yWatch.Warner { now }
        val a = service("a/A")
        val b = service("b/B")
        assertFalse(warner.shouldWarn(emptyList(), passwordField = false)) // nothing risky: nothing to say
        assertFalse(warner.shouldWarn(emptyList(), passwordField = true))
        assertTrue(warner.shouldWarn(listOf(a), passwordField = false)) // first sighting
        assertFalse(warner.shouldWarn(listOf(a), passwordField = false)) // same set in the next field: quiet
        assertTrue(warner.shouldWarn(listOf(a, b), passwordField = false)) // a new service
        assertFalse(warner.shouldWarn(listOf(b, a), passwordField = false)) // the order does not matter
        assertFalse(warner.shouldWarn(listOf(a), passwordField = false)) // a service was switched off: nothing new
        assertFalse(warner.shouldWarn(emptyList(), passwordField = false))
        now += 5
        assertTrue(warner.shouldWarn(listOf(a), passwordField = false)) // switched on again after a quiet period
    }

    @Test fun aPasswordFieldWarnsAgainAfterTheRepeatInterval() {
        var now = 5_000_000L
        val warner = A11yWatch.Warner { now }
        val a = service("a/A")
        assertTrue(warner.shouldWarn(listOf(a), passwordField = true))
        now += A11yWatch.PASSWORD_REPEAT_MILLIS - 1
        assertFalse(warner.shouldWarn(listOf(a), passwordField = true))
        assertFalse(warner.shouldWarn(listOf(a), passwordField = false)) // an ordinary field never repeats
        now += 1
        assertFalse(warner.shouldWarn(listOf(a), passwordField = false))
        assertTrue(warner.shouldWarn(listOf(a), passwordField = true)) // the password field the user is about to type into
        assertFalse(warner.shouldWarn(listOf(a), passwordField = true)) // and the interval starts again
    }

    // the platform side: AccessibilityManager's enabled list is turned into A11yService values
    // AccessibilityServiceInfo is built by the system from the service's XML; the fields are set directly
    private fun info(pkg: String, name: String, capabilities: Int, events: Int = 0, tool: Boolean = false): AccessibilityServiceInfo {
        val si = ServiceInfo().apply { packageName = pkg; this.name = name; applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg } }
        val info = AccessibilityServiceInfo()
        info.eventTypes = events
        fun set(field: String, value: Any) = AccessibilityServiceInfo::class.java.getDeclaredField(field).apply { isAccessible = true }.set(info, value)
        set("mCapabilities", capabilities)
        set("mComponentName", ComponentName(pkg, name))
        if (tool) set("mIsAccessibilityTool", true)
        set("mResolveInfo", ResolveInfo().apply { serviceInfo = si })
        return info
    }

    @Test fun theEnabledServicesAreReadFromTheSystemWithoutToolInformationBeforeApi34() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val manager = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        shadowOf(manager).setEnabledAccessibilityServiceList(listOf(
            info("x.reader", "svc.Reader", AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT),
            info("x.blind", "svc.Blind", 0),
            info("x.typed", "svc.Typed", 0, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED), // sees typed text through events
        ))
        val services = A11yWatch.enabledServices(ctx, sdkInt = 32)
        assertEquals(listOf("x.reader/svc.Reader", "x.blind/svc.Blind", "x.typed/svc.Typed"), services.map { it.id })
        assertEquals(listOf(true, false, true), services.map { it.readsContent })
        assertEquals(listOf(null, null, null), services.map { it.isTool }) // API 32 has no isAccessibilityTool
        assertEquals(listOf("x.reader/svc.Reader", "x.typed/svc.Typed"), A11yWatch.risky(services).map { it.id })
    }

    @Test fun fromApi34TheServicesToolDeclarationIsRead() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val manager = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val reads = AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT
        shadowOf(manager).setEnabledAccessibilityServiceList(listOf(
            info("x.screenreader", "svc.Tool", reads, tool = true),
            info("x.watcher", "svc.Watcher", reads),
        ))
        val services = A11yWatch.enabledServices(ctx, sdkInt = 36)
        assertEquals(listOf(true, false), services.map { it.isTool })
        assertEquals(listOf("x.watcher/svc.Watcher"), A11yWatch.risky(services).map { it.id })
    }

    @Test fun noEnabledServiceMeansNoRisk() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val manager = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        shadowOf(manager).setEnabledAccessibilityServiceList(emptyList())
        assertEquals(emptyList(), A11yWatch.risky(A11yWatch.enabledServices(ctx, sdkInt = 36)))
    }
}
