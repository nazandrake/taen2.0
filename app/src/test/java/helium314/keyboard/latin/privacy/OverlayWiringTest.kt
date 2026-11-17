// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.InputView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P14 with real MotionEvents: OverlayGuard.dispatch, and the keyboard's own root view (InputView) routing every touch through it. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class])
class OverlayWiringTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val shown = ArrayList<String>()
    private var now = 1_000_000L

    @Before fun before() {
        Privacy.forgetCachedState()
        PrivacyWarnings.reset()
        shown.clear()
        PrivacyWarnings.show = { _, message -> shown.add(message) }
        PrivacyWarnings.clock = { now }
    }

    @After fun after() {
        PrivacyWarnings.show = { context, message -> android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show() }
        PrivacyWarnings.clock = System::currentTimeMillis
        Privacy.forgetCachedState()
    }

    private fun event(action: Int, flags: Int): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 10f; y = 10f })
        return MotionEvent.obtain(0L, 0L, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, flags)
    }

    private class Delivered { val actions = ArrayList<Int>(); val deliver: (MotionEvent) -> Boolean = { actions.add(it.action); true } }

    private val covered = MotionEvent.FLAG_WINDOW_IS_OBSCURED

    @Test fun dispatchDeliversDropsAndCancelsAsTheGateSays() {
        val gate = OverlayGuard.Gate { now }
        val d = Delivered()
        assertTrue(OverlayGuard.dispatch(ctx, event(MotionEvent.ACTION_DOWN, 0), gate, OverlayMode.BLOCK, d.deliver))
        assertEquals(listOf(MotionEvent.ACTION_DOWN), d.actions) // positive control: an uncovered touch is delivered
        assertTrue(OverlayGuard.dispatch(ctx, event(MotionEvent.ACTION_MOVE, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED), gate, OverlayMode.BLOCK, d.deliver))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), d.actions) // the gesture was cancelled, the move not delivered
        assertTrue(OverlayGuard.dispatch(ctx, event(MotionEvent.ACTION_MOVE, covered), gate, OverlayMode.BLOCK, d.deliver))
        assertTrue(OverlayGuard.dispatch(ctx, event(MotionEvent.ACTION_UP, covered), gate, OverlayMode.BLOCK, d.deliver))
        assertEquals(2, d.actions.size) // dropped
        assertEquals(listOf(ctx.getString(R.string.privacy_overlay_blocked)), shown) // one notice, rate limited
        now += OverlayGuard.WARN_INTERVAL_MILLIS
        OverlayGuard.dispatch(ctx, event(MotionEvent.ACTION_DOWN, covered), gate, OverlayMode.BLOCK, d.deliver)
        assertEquals(2, shown.size)
    }

    @Test fun warnModeDeliversAndTellsTheUserOncePerInterval() {
        val gate = OverlayGuard.Gate { now }
        val d = Delivered()
        OverlayGuard.dispatch(ctx, event(MotionEvent.ACTION_DOWN, covered), gate, OverlayMode.WARN, d.deliver)
        OverlayGuard.dispatch(ctx, event(MotionEvent.ACTION_UP, covered), gate, OverlayMode.WARN, d.deliver)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), d.actions)
        assertEquals(listOf(ctx.getString(R.string.privacy_overlay_warning)), shown)
    }

    @Test fun offModeNeverWarns() {
        val d = Delivered()
        OverlayGuard.dispatch(ctx, event(MotionEvent.ACTION_DOWN, covered), OverlayGuard.Gate { now }, OverlayMode.OFF, d.deliver)
        assertEquals(1, d.actions.size)
        assertEquals(emptyList(), shown)
    }

    // ---- the keyboard's root view ---------------------------------------------------------------------------------

    private fun inputView(): InputView {
        Settings.init(ctx)
        Settings.getInstance().loadSettings(ctx, Locale.ENGLISH, InputAttributes(EditorInfo(), false, ctx.packageName))
        val themed = ContextThemeWrapper(ctx, KeyboardTheme.getKeyboardTheme(ctx).mStyleId)
        KeyboardIconsSet.instance.loadIcons(themed) // the toolbar keys need their icons
        return LayoutInflater.from(themed).inflate(R.layout.input_view, null) as InputView
    }

    @Test fun theKeyboardViewDropsCoveredTouchesInBlockModeAndPassesOthers() {
        val view = inputView()
        ctx.prefs().edit().putString(Settings.PREF_OVERLAY_MODE, "block").commit()
        Settings.getInstance().loadSettings(ctx, Locale.ENGLISH, InputAttributes(EditorInfo(), false, ctx.packageName))
        assertEquals(OverlayMode.BLOCK, Settings.getValues().mOverlayMode)
        val dropped = view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, covered))
        assertTrue(dropped) // handled: it does not fall through to the app below
        assertEquals(listOf(ctx.getString(R.string.privacy_overlay_blocked)), shown) // the gate saw the event: the production path is wired
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, covered))
        // a plain touch is not blocked or announced (positive control: the view still works)
        shown.clear()
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 0))
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 0))
        assertEquals(emptyList(), shown)
    }

    @Test fun theKeyboardViewIsMarkedSensitiveForAccessibilityServices() {
        assertTrue(inputView().isAccessibilityDataSensitive)
    }

    @Test fun theModeSettingDefaultsToWarn() {
        inputView()
        assertEquals(OverlayMode.WARN, Settings.getValues().mOverlayMode)
        assertFalse(shown.isNotEmpty())
    }
}
