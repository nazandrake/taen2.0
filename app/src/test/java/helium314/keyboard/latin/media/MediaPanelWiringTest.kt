// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.media

import android.content.Context
import android.os.Looper
import android.view.View
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.KeyboardWrapperView
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ShadowFacilitator2
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.getCodeForToolbarKey
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The picture panel through the running keyboard (the production entry point): the MEDIA key code reaches the panel, the panel is a child of the keyboard view, a tap on a picture
 * calls commitContent once in a field that accepts images, and in a field that does not it shows the message and sends nothing. Remove the branch in
 * KeyboardActionListenerImpl, the panel in KeyboardSwitcher.onCreateInputView or MediaInserter and a test here fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class, ShadowFacilitator2::class])
class MediaPanelWiringTest {
    private fun withKeyboard(test: (LatinIME, Context) -> Unit) {
        Privacy.forgetCachedState()
        ClipboardDao.forgetInstance()
        ShadowInputMethodService.reset()
        val ime = Robolectric.setupService(LatinIME::class.java)
        Settings.init(ime)
        KeyboardSwitcher.getInstance().onCreateInputView(ime, true)
        KeyboardSwitcher.getInstance().reloadMainKeyboard()
        try {
            test(ime, ime.applicationContext)
        } finally {
            MediaTokens.revokeAll()
            Privacy.unlock(ime)
            Privacy.forgetCachedState()
            ClipboardDao.forgetInstance()
            ShadowInputMethodService.reset()
        }
    }

    private fun press(ime: LatinIME, code: Int) = ime.mKeyboardActionListener.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)

    /** Lets the panel's worker thread finish and runs what it posted to the main thread. */
    private fun settle(until: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!until() && System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun theMediaKeyOpensAndClosesThePanelThroughTheKeyboardsListener() = withKeyboard { ime, _ ->
        val switcher = KeyboardSwitcher.getInstance()
        val panel = assertNotNull(switcher.mediaPanelView)
        assertTrue(panel.parent is KeyboardWrapperView, "the panel is a child of the keyboard view wrapper")
        assertFalse(switcher.isShowingMediaPanel())
        press(ime, KeyCode.MEDIA_PANEL)
        assertTrue(switcher.isShowingMediaPanel())
        assertEquals(View.VISIBLE, panel.visibility)
        press(ime, KeyCode.MEDIA_PANEL)
        assertFalse(switcher.isShowingMediaPanel())
    }

    @Test fun theToolbarKeyIsMappedToTheCodeAndIsOffByDefault() {
        assertEquals(KeyCode.MEDIA_PANEL, getCodeForToolbarKey(ToolbarKey.MEDIA))
        assertEquals(-10055, KeyCode.MEDIA_PANEL)
    }

    @Test fun aTapSendsThePictureWhereImagesAreAcceptedAndShowsTheMessageWhereTheyAreNot() = withKeyboard { ime, ctx ->
        val item = assertIs<MediaVault.ImportResult.Added>(Privacy.mediaVault(ctx).import(MediaFixtures.gifDirty, listOf("cat"))).item
        val panel = assertNotNull(KeyboardSwitcher.getInstance().mediaPanelView)
        press(ime, KeyCode.MEDIA_PANEL)
        settle { panel.shown.size == 1 }
        assertEquals(listOf(item.id), panel.shown.map { it.key })
        assertEquals("", panel.messageText.toString())

        // a field that does not take images: the message, no commit, no token
        ShadowInputMethodService.currentContentMimeTypes = arrayOf("text/plain")
        panel.send(panel.shown.single())
        assertEquals(ctx.getString(R.string.media_not_accepted), panel.messageText.toString())
        assertEquals(emptyList(), ShadowInputMethodService.contentCommits)
        assertEquals(0, MediaTokens.liveCount)
        assertTrue(KeyboardSwitcher.getInstance().isShowingMediaPanel(), "the panel stays open after a refusal")

        // positive control: the same tap in a field that takes images commits once, read-only, with a token URI that serves the stored bytes
        ShadowInputMethodService.currentContentMimeTypes = arrayOf("image/*")
        panel.send(panel.shown.single())
        val (uri, mime, flags) = ShadowInputMethodService.contentCommits.single()
        assertEquals("image/gif", mime)
        assertEquals(android.view.inputmethod.InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, flags)
        assertEquals(MediaShareProvider.authority(ctx.packageName), uri.authority)
        val token = assertNotNull(MediaShareProvider.tokenOf(uri))
        assertEquals(MediaFixtures.gifClean.toList(), MediaTokens.bytes(token)?.toList(), "the production reader gives the stored picture")
        assertFalse(KeyboardSwitcher.getInstance().isShowingMediaPanel(), "a sent picture closes the panel")
    }

    @Test fun whileLockedThePanelSaysSoAndListsNothing() = withKeyboard { ime, ctx ->
        Privacy.mediaVault(ctx).import(MediaFixtures.gifClean)
        val panel = assertNotNull(KeyboardSwitcher.getInstance().mediaPanelView)
        Privacy.lock(ctx)
        shadowOf(Looper.getMainLooper()).idle() // the lock hook closes the panel
        press(ime, KeyCode.MEDIA_PANEL)
        settle { panel.messageText.isNotEmpty() }
        assertEquals(ctx.getString(R.string.media_panel_locked), panel.messageText.toString())
        assertEquals(emptyList(), panel.shown)
    }

    @Test fun theEmojiPanelHasAPicturesTabThatOpensTheSamePanelAndACategoryTabLeadsBack() = withKeyboard { ime, _ ->
        val switcher = KeyboardSwitcher.getInstance()
        switcher.setEmojiKeyboard()
        shadowOf(Looper.getMainLooper()).idle()
        val strip = assertIs<android.widget.LinearLayout>(switcher.emojiTabStrip)
        val tab = assertNotNull(strip.findViewWithTag<View>("taen_media"), "the emoji tab strip has the pictures tab")
        assertTrue(strip.childCount > 2, "next to the category tabs")
        assertFalse(switcher.isShowingMediaPanel())
        tab.performClick()
        assertTrue(switcher.isShowingMediaPanel(), "the tab opens the picture panel, the one the toolbar key opens")
        assertNotNull(switcher.mediaPanelView)
        strip.getChildAt(0).performClick() // a category tab: the emoji pages again
        assertFalse(switcher.isShowingMediaPanel())
        tab.performClick()
        assertTrue(switcher.isShowingMediaPanel())
    }

    @Test fun theStarterPackAppearsOnlyWhenItsSettingIsOn() = withKeyboard { ime, ctx ->
        val panel = assertNotNull(KeyboardSwitcher.getInstance().mediaPanelView)
        press(ime, KeyCode.MEDIA_PANEL)
        settle { panel.messageText.isNotEmpty() }
        assertEquals(emptyList(), panel.shown, "off by default: nothing is shown")
        press(ime, KeyCode.MEDIA_PANEL)
        ctx.prefs().edit().putBoolean(Settings.PREF_MEDIA_STARTER_PACK, true).commit()
        press(ime, KeyCode.MEDIA_PANEL)
        settle { panel.shown.isNotEmpty() }
        assertTrue(panel.shown.isNotEmpty())
        assertTrue(panel.shown.all { it.source is MediaSource.Asset })
    }

    @Test fun theAnimatedStarterPicturesAreTaggedGifAndAreSentAsGifThroughTheInserter() = withKeyboard { ime, ctx ->
        ctx.prefs().edit().putBoolean(Settings.PREF_MEDIA_STARTER_PACK, true).commit()
        val panel = assertNotNull(KeyboardSwitcher.getInstance().mediaPanelView)
        press(ime, KeyCode.MEDIA_PANEL)
        settle { panel.shown.isNotEmpty() }
        val gifs = panel.shown.filter { it.format == MediaFormat.GIF }
        assertEquals(36, gifs.size, "the animated starter set is listed")
        assertTrue(gifs.all { it.tags == listOf("gif") && it.source is MediaSource.Asset }, "tagged for the tag chips")
        val stills = panel.shown.filter { it.format == MediaFormat.PNG }
        assertEquals(38, stills.size)
        assertTrue(stills.all { it.tags == listOf("sticker") })

        // positive control: a field that announces only image/gif takes a starter GIF, once, read-only, and the token serves exactly the asset bytes
        val gif = gifs.first { it.key == "stickers/fire_animated.gif" }
        ShadowInputMethodService.currentContentMimeTypes = arrayOf("image/gif")
        panel.send(gif)
        val (uri, mime, _) = ShadowInputMethodService.contentCommits.single()
        assertEquals("image/gif", mime)
        val token = assertNotNull(MediaShareProvider.tokenOf(uri))
        assertEquals(StarterPack.read(ctx, gif.key)!!.toList(), MediaTokens.bytes(token)?.toList())
        // the same field refuses a starter still (PNG is not announced): nothing is sent
        panel.send(stills.first())
        assertEquals(1, ShadowInputMethodService.contentCommits.size)
    }
}
