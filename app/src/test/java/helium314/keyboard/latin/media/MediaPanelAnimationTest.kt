// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.media

import android.content.Context
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.MediaPanelView
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.ShadowFacilitator2
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Animated tiles of the picture panel, through the running keyboard (platform decoder, native graphics): a GIF tile that is on screen gets an AnimatedImageDrawable that runs,
 * at tile size; a tile that scrolls off, the panel closing and the screen lock stop and release it; a still picture is never animated.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class, ShadowFacilitator2::class])
class MediaPanelAnimationTest {
    private fun withKeyboard(test: (LatinIME, Context, MediaPanelView) -> Unit) {
        Privacy.forgetCachedState()
        ClipboardDao.forgetInstance()
        ShadowInputMethodService.reset()
        val ime = Robolectric.setupService(LatinIME::class.java)
        Settings.init(ime)
        KeyboardSwitcher.getInstance().onCreateInputView(ime, true)
        KeyboardSwitcher.getInstance().reloadMainKeyboard()
        try {
            test(ime, ime.applicationContext, assertNotNull(KeyboardSwitcher.getInstance().mediaPanelView))
        } finally {
            MediaTokens.revokeAll()
            Privacy.unlock(ime)
            Privacy.forgetCachedState()
            ClipboardDao.forgetInstance()
            ShadowInputMethodService.reset()
        }
    }

    private fun press(ime: LatinIME, code: Int) = ime.mKeyboardActionListener.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)

    private fun settle(until: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!until() && System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Gives the panel a size so that its list lays out tiles (a tile is 72 px high here, four to a row): [height] 440 shows about four rows. */
    private fun layout(panel: MediaPanelView, height: Int = 440) {
        panel.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        panel.layout(0, 0, 1000, height)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun grid(panel: MediaPanelView) = panel.getChildAt(panel.childCount - 1) as RecyclerView

    private fun open(ime: LatinIME, panel: MediaPanelView, starter: Boolean, ctx: Context) {
        ctx.prefs().edit().putBoolean(Settings.PREF_MEDIA_STARTER_PACK, starter).commit()
        press(ime, KeyCode.MEDIA_PANEL)
        settle { panel.shown.isNotEmpty() }
        layout(panel)
    }

    @Test fun starterGifTilesOnScreenAnimateAndStillTilesDoNot() = withKeyboard { ime, ctx, panel ->
        open(ime, panel, true, ctx)
        settle { panel.animating.isNotEmpty() }
        val animating = panel.animating
        assertTrue(animating.isNotEmpty(), "the visible GIF tiles animate")
        for ((key, drawable) in animating) {
            assertTrue(key.endsWith("_animated.gif"), key)
            val d = assertIs<AnimatedImageDrawable>(drawable)
            assertTrue(d.isRunning, "$key runs")
            assertEquals(128, d.intrinsicWidth, key)
        }
        // positive control: the list also holds still stickers (PNG) among the first tiles, and none of them is animated
        assertTrue(panel.shown.take(16).any { it.format == MediaFormat.PNG })
        assertTrue(animating.keys.none { it.endsWith(".png") })
        // bounded: only what the screen shows (about 16 tiles of the 74), not the whole list
        assertTrue(animating.size < panel.shown.count { it.format == MediaFormat.GIF }, "${animating.size} animated tiles")
    }

    @Test fun aTileThatScrollsOffStopsAndAnotherOneStartsUnderIt() = withKeyboard { ime, ctx, panel ->
        open(ime, panel, true, ctx)
        settle { panel.animating.isNotEmpty() }
        val before = panel.animating.toMap()
        assertTrue(before.isNotEmpty())
        grid(panel).scrollBy(0, 100_000) // to the end of the list
        layout(panel)
        settle { panel.animating.isNotEmpty() && panel.animating.keys.none { it in before.keys } }
        assertTrue(panel.animating.keys.none { it in before.keys }, "the first tiles are off screen now")
        for ((key, d) in before) assertFalse((d as AnimatedImageDrawable).isRunning, "$key was stopped when it scrolled off")
        assertTrue(panel.animating.values.all { (it as AnimatedImageDrawable).isRunning })
    }

    @Test fun closingThePanelStopsAndReleasesEveryAnimation() = withKeyboard { ime, ctx, panel ->
        open(ime, panel, true, ctx)
        settle { panel.animating.isNotEmpty() }
        val before = panel.animating.values.toList()
        assertTrue(before.isNotEmpty() && before.all { (it as AnimatedImageDrawable).isRunning })
        press(ime, KeyCode.MEDIA_PANEL) // closes
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyMap(), panel.animating)
        assertTrue(before.none { (it as AnimatedImageDrawable).isRunning })
    }

    @Test fun theScreenLockStopsAndReleasesEveryAnimation() = withKeyboard { ime, ctx, panel ->
        open(ime, panel, true, ctx)
        settle { panel.animating.isNotEmpty() }
        val before = panel.animating.values.toList()
        assertTrue(before.isNotEmpty())
        Privacy.lock(ctx)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyMap(), panel.animating)
        assertTrue(before.none { (it as AnimatedImageDrawable).isRunning })
    }

    @Test fun aLargeGifOfTheUserAnimatesAtTileSizeAndAStillOneDoesNot() = withKeyboard { ime, ctx, panel ->
        val big = javaClass.classLoader!!.getResourceAsStream("media/big_animated.gif")!!.use { it.readBytes() }
        val gif = assertIs<MediaVault.ImportResult.Added>(Privacy.mediaVault(ctx).import(big, listOf("big"))).item
        val png = assertIs<MediaVault.ImportResult.Added>(Privacy.mediaVault(ctx).import(MediaFixtures.pngDirty)).item
        open(ime, panel, false, ctx)
        settle { panel.animating.isNotEmpty() }
        assertEquals(setOf(gif.id), panel.animating.keys, "the GIF animates, the PNG (${png.id}) does not")
        val d = assertIs<AnimatedImageDrawable>(panel.animating.getValue(gif.id))
        assertTrue(d.isRunning)
        assertEquals(160, d.intrinsicWidth)
        assertEquals(120, d.intrinsicHeight)
    }
}
