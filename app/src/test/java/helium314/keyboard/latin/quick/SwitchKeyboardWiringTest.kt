// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.quick

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.service.quicksettings.TileService
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.latin.privacy.SecureWindow
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Production wiring of the Switch keyboard tile: the manifest declares the tile service and the activity it starts, and that activity asks for
 * the system picker once. Removing the manifest entry or the call fails these tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowTileService2::class])
class SwitchKeyboardWiringTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @AfterTest fun after() { ShadowInputMethodManager2.pickersShown = 0 }

    @Test fun theTileServiceIsDeclaredForTheSystemOnly() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, SwitchKeyboardTileService::class.java), PackageManager.GET_META_DATA)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", info.permission)
        assertTrue(info.exported, "the system binds it from outside the app")
        assertFalse(info.metaData?.getBoolean(TileService.META_DATA_TOGGLEABLE_TILE) ?: false, "an action tile, not a switch")
        val found = context.packageManager.queryIntentServices(Intent(TileService.ACTION_QS_TILE).setPackage(context.packageName), 0)
        assertEquals(listOf(SwitchKeyboardTileService::class.java.name), found.map { it.serviceInfo.name })
        assertEquals(context.getString(helium314.keyboard.latin.R.string.tile_switch_keyboard_label), info.loadLabel(context.packageManager).toString())
    }

    @Test fun theActivityItStartsIsNotExportedAndLeavesNoTrace() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, SwitchKeyboardActivity::class.java), 0)
        assertFalse(info.exported)
        assertTrue(info.flags and android.content.pm.ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0, "not in the recent apps")
        assertTrue(info.flags and android.content.pm.ActivityInfo.FLAG_NO_HISTORY != 0)
        // its own task: with the affinity of the app it would join the task of the settings and bring them to the front without the PIN
        val settings = context.packageManager.getActivityInfo(ComponentName(context, helium314.keyboard.settings.SettingsActivity::class.java), 0)
        assertEquals(context.packageName, settings.taskAffinity, "positive control: an ordinary activity has the affinity of the app")
        assertTrue(info.taskAffinity.isNullOrEmpty(), "the empty affinity (Robolectric reads it as null), got ${info.taskAffinity}")
        val rotation = android.content.pm.ActivityInfo.CONFIG_ORIENTATION or android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE
        assertEquals(rotation, info.configChanges and rotation, "a rotation does not recreate it (a second picker)")
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SwitchKeyboardActivity.SETTLE_MS))

    @Test fun theActivityAsksForThePickerOnceItsFocusHasSettledAndEndsWhenThePickerIsGone() {
        val controller = Robolectric.buildActivity(SwitchKeyboardActivity::class.java).setup()
        val activity = controller.get()
        assertTrue(SecureWindow.isSecure(activity.window), "no screenshots (P12)")
        settle()
        assertEquals(0, ShadowInputMethodManager2.pickersShown, "no picker before the window has focus")
        controller.windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SwitchKeyboardActivity.SETTLE_MS - 1))
        assertEquals(0, ShadowInputMethodManager2.pickersShown, "the focus has not settled yet")
        settle()
        assertEquals(1, ShadowInputMethodManager2.pickersShown)
        assertFalse(activity.isFinishing, "finishing here would close the picker")
        controller.windowFocusChanged(true) // not yet taken by the picker: nothing to end
        settle()
        assertFalse(activity.isFinishing)
        assertEquals(1, ShadowInputMethodManager2.pickersShown, "one picker per tap")
        controller.windowFocusChanged(false) // the picker has the focus
        assertFalse(activity.isFinishing)
        controller.windowFocusChanged(true) // the picker is gone
        assertTrue(activity.isFinishing)
        settle()
        assertEquals(1, ShadowInputMethodManager2.pickersShown)
    }

    @Test fun aFocusThatMovesOnBeforeItSettlesAsksNothingAndTheNextGainAsks() {
        val controller = Robolectric.buildActivity(SwitchKeyboardActivity::class.java).setup()
        controller.windowFocusChanged(true)
        controller.windowFocusChanged(false) // the shade takes the focus back while it collapses
        settle()
        assertEquals(0, ShadowInputMethodManager2.pickersShown)
        assertFalse(controller.get().isFinishing, "it waits for the focus to come back")
        controller.windowFocusChanged(true)
        settle()
        assertEquals(1, ShadowInputMethodManager2.pickersShown, "positive control: the picker opens when the focus has settled")
    }

    @Test fun anActivityWhoseWindowNeverGetsTheFocusEndsAfterTheTimeout() {
        val controller = Robolectric.buildActivity(SwitchKeyboardActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SwitchKeyboardActivity.GIVE_UP_MS - 1))
        assertFalse(controller.get().isFinishing, "not before the timeout")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue(controller.get().isFinishing, "an invisible window must not stay and absorb touches")
        assertEquals(0, ShadowInputMethodManager2.pickersShown)
    }

    @Test fun anActivityWhosePickerNeverTakesTheFocusEndsAfterTheTimeout() {
        val controller = Robolectric.buildActivity(SwitchKeyboardActivity::class.java).setup()
        controller.windowFocusChanged(true)
        settle()
        assertEquals(1, ShadowInputMethodManager2.pickersShown, "the picker was asked for")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SwitchKeyboardActivity.PICKER_MS - 1))
        assertFalse(controller.get().isFinishing, "the picker gets its time from the request")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue(controller.get().isFinishing)
    }

    @Test fun aFocusThatSettlesLateDoesNotLetTheOverallTimeoutCloseThePicker() {
        val controller = Robolectric.buildActivity(SwitchKeyboardActivity::class.java).setup()
        val late = SwitchKeyboardActivity.GIVE_UP_MS - 900
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(late)) // focus comes at 4.1 s
        controller.windowFocusChanged(true)
        settle() // the picker is asked for at 4.9 s
        assertEquals(1, ShadowInputMethodManager2.pickersShown)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300)) // 5.2 s since creation, 0.3 s since the request: SystemUI has not taken the focus yet
        assertFalse(controller.get().isFinishing, "the timeout of a picker runs from its request, not from the creation")
        controller.windowFocusChanged(false) // the picker has the focus
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SwitchKeyboardActivity.PICKER_MS * 2))
        assertFalse(controller.get().isFinishing)
    }

    @Test fun aPickerThatHasTheFocusIsNotEndedByTheTimeout() {
        val controller = Robolectric.buildActivity(SwitchKeyboardActivity::class.java).setup()
        controller.windowFocusChanged(true)
        settle()
        controller.windowFocusChanged(false) // the picker has the focus
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SwitchKeyboardActivity.GIVE_UP_MS * 3))
        assertFalse(controller.get().isFinishing, "the user may read the list as long as they want")
    }

    @Test fun aRecreatedActivityEndsAndAsksForNoSecondPicker() {
        val first = Robolectric.buildActivity(SwitchKeyboardActivity::class.java).setup()
        first.windowFocusChanged(true)
        settle()
        assertEquals(1, ShadowInputMethodManager2.pickersShown)
        val second = first.recreate()
        second.windowFocusChanged(true)
        settle()
        assertEquals(1, ShadowInputMethodManager2.pickersShown, "process death or a recreation does not open another picker")
        assertTrue(second.get().isFinishing)
    }

    // ---- the tile service: what a tap does (the platform's TileService is a recording shadow: a tile is bound by the system only) ----
    object Android { var sdk = 0 }

    class Tile : SwitchKeyboardTileService() {
        override val sdk get() = Android.sdk
    }

    private fun tap(sdk: Int, locked: Boolean): Tile {
        Android.sdk = sdk
        ShadowTileService2.reset(locked)
        val tile = Robolectric.setupService(Tile::class.java)
        tile.onClick()
        return tile
    }

    private fun assertStartsTheActivity(intent: Intent) {
        assertEquals(ComponentName(context, SwitchKeyboardActivity::class.java), intent.component)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0, "a service starts an activity in a new task")
    }

    @Test fun anUnlockedTapFromAndroid14CollapsesTheShadeWithAPendingIntent() {
        for (sdk in listOf(34, 36, 37)) {
            tap(sdk, locked = false)
            assertEquals(listOf("pending"), ShadowTileService2.calls, "sdk $sdk")
            val saved = shadowOf(ShadowTileService2.pending!!)
            assertTrue(saved.isActivityIntent)
            assertStartsTheActivity(saved.savedIntent)
            assertTrue(saved.flags and PendingIntent.FLAG_IMMUTABLE != 0, "the system cannot fill in the intent")
        }
    }

    @Test fun anUnlockedTapBelowAndroid14CollapsesTheShadeWithTheIntent() {
        for (sdk in listOf(30, 33)) {
            tap(sdk, locked = false)
            assertEquals(listOf("intent"), ShadowTileService2.calls, "sdk $sdk")
            assertStartsTheActivity(ShadowTileService2.intent!!)
        }
    }

    @Test fun aLockedTapAsksForTheUnlockFirstAndStartsOnlyAfterIt() {
        for ((sdk, expected) in listOf(33 to "intent", 34 to "pending")) {
            tap(sdk, locked = true)
            assertEquals(listOf("unlock"), ShadowTileService2.calls, "sdk $sdk: nothing starts before the unlock")
            ShadowTileService2.afterUnlock!!.run() // the user unlocked
            assertEquals(listOf("unlock", expected), ShadowTileService2.calls, "sdk $sdk")
        }
    }

    // ---- strings: the tile label, the banking note and the About text, English and Bulgarian ----
    private fun strings(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$dir/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent.trim().removeSurrounding("\"") }
    }

    @Test fun theBankingNoteAndTheTileLabelExistInBothLanguages() {
        val en = strings("values")
        val bg = strings("values-bg")
        for (name in listOf("tile_switch_keyboard_label", "banking_apps_title", "banking_apps_text", "about_slogan")) {
            assertTrue(en[name]?.isNotBlank() == true, "$name in English")
            assertTrue(bg[name]?.any { it in 'а'..'я' || it in 'А'..'Я' } == true, "$name in Bulgarian")
        }
        assertEquals("Switch keyboard", en["tile_switch_keyboard_label"])
        assertTrue(en.getValue("banking_apps_text").contains("tile"), "the English note names the tile")
        assertTrue(en.getValue("banking_apps_text").contains("cannot be bypassed"), "and says it cannot be bypassed")
        assertTrue(bg.getValue("banking_apps_text").contains(bg.getValue("tile_switch_keyboard_label")), "the Bulgarian note names the tile by its Bulgarian label")
    }

    @Test fun theBankingNoteIsAnAboutItemThatTheSettingsSearchFinds() {
        val c = helium314.keyboard.settings.SettingsContainer(context)
        assertTrue(helium314.keyboard.settings.SettingsWithoutKey.GITHUB in c.filter("github").map { it.key }, "positive control: the About items are searched")
        assertEquals(listOf(helium314.keyboard.settings.SettingsWithoutKey.BANKING_APPS), c.filter("banking").map { it.key })
    }

    @Test fun theAboutSloganDescribesTaenAndKeepsTheOrigin() {
        val en = strings("values").getValue("about_slogan")
        assertFalse(en.contains("100% FOSS"))
        assertTrue(en.contains("Taen") && en.contains("HeliBoard") && en.contains("AOSP"), en)
        val bg = strings("values-bg").getValue("about_slogan")
        assertTrue(bg.contains("Taen") && bg.contains("HeliBoard") && bg.contains("AOSP"), bg)
        assertFalse(File("src/main/res/values/donottranslate.xml").readText().contains("100% FOSS"), "the old text is gone")
        val c = helium314.keyboard.settings.SettingsContainer(context)
        assertTrue(helium314.keyboard.settings.SettingsWithoutKey.APP in c.filter("HeliBoard").map { it.key }, "the About screen shows the new text")
    }
}

/** The platform's tile service as the system leaves it to the app: which of its calls a tap makes, in order. */
@org.robolectric.annotation.Implements(TileService::class)
class ShadowTileService2 {
    @org.robolectric.annotation.Implementation
    fun isLocked() = locked

    @org.robolectric.annotation.Implementation
    fun unlockAndRun(then: Runnable) { calls.add("unlock"); afterUnlock = then }

    @org.robolectric.annotation.Implementation
    fun startActivityAndCollapse(started: Intent) { calls.add("intent"); intent = started }

    @org.robolectric.annotation.Implementation
    fun startActivityAndCollapse(started: PendingIntent) { calls.add("pending"); pending = started }

    companion object {
        var locked = false
        val calls = mutableListOf<String>()
        var intent: Intent? = null
        var pending: PendingIntent? = null
        var afterUnlock: Runnable? = null
        fun reset(locked: Boolean) { this.locked = locked; calls.clear(); intent = null; pending = null; afterUnlock = null }
    }
}
