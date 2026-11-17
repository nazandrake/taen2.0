// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ShadowFacilitator2
import helium314.keyboard.latin.ShadowKeyboardSwitcher
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.privacy.Privacy
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** An entry of the clipboard history panel shows a pin button: a tap on it pins the entry (kept until unpinned). */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class, ShadowFacilitator2::class])
class ClipboardPinTest {
    private var ime: LatinIME? = null

    @AfterTest fun after() {
        ime?.let { Privacy.unlock(it) }
        Privacy.forgetCachedState()
        ClipboardDao.forgetInstance()
    }

    /** The service with one copied text in the history, and an adapter row bound to it. */
    private fun row(): Triple<ClipboardDao, ClipboardAdapter, ClipboardAdapter.ViewHolder> {
        Privacy.forgetCachedState()
        ClipboardDao.forgetInstance()
        val service = Robolectric.setupService(LatinIME::class.java)
        ime = service
        SystemClock.setCurrentTimeMillis(System.currentTimeMillis()) // see CanaryAtRestTest.clip
        service.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("label", "an address to keep"))
        shadowOf(Looper.getMainLooper()).idle()
        val dao = assertNotNull(ClipboardDao.getInstance(service))
        assertEquals(listOf("an address to keep"), dao.getAll().map { it.text }, "positive control: the copy is in the history")
        val adapter = ClipboardAdapter(ClipboardLayoutParams(service), object : OnKeyEventListener {
            override fun onKeyDown(clipId: Long) {}
            override fun onKeyUp(clipId: Long) {}
        })
        adapter.clipboardHistoryManager = ClipboardHistoryManager(service)
        val list = RecyclerView(service).apply { layoutManager = LinearLayoutManager(service) } // the panel's list, as ClipboardHistoryView sets it up
        val holder = adapter.onCreateViewHolder(list, 0)
        adapter.onBindViewHolder(holder, 0)
        return Triple(dao, adapter, holder)
    }

    private fun pinButton(holder: ClipboardAdapter.ViewHolder) = holder.itemView.findViewById<ImageView>(R.id.clipboard_entry_pinned_icon)

    @Test fun anUnpinnedEntryShowsAPinButtonThatPinsIt() {
        val (dao, _, holder) = row()
        assertEquals(View.VISIBLE, pinButton(holder).visibility, "the pin button is shown on an entry that is not pinned")
        pinButton(holder).performClick()
        assertEquals(listOf(true), dao.getAll().map { it.pinned })
    }


    @Test fun thePinButtonSaysWhatItDoesAndASecondTapUnpins() {
        val (dao, adapter, holder) = row()
        val pin = ime!!.getString(R.string.clipboard_entry_pin)
        val unpin = ime!!.getString(R.string.clipboard_entry_unpin)
        assertEquals(pin, pinButton(holder).contentDescription)
        assertTrue(pinButton(holder).alpha < 1f, "not pinned: the button is dimmed")
        pinButton(holder).performClick()
        adapter.onBindViewHolder(holder, 0)
        assertEquals(unpin, pinButton(holder).contentDescription)
        assertEquals(1f, pinButton(holder).alpha)
        pinButton(holder).performClick()
        adapter.onBindViewHolder(holder, 0)
        assertEquals(listOf(false), dao.getAll().map { it.pinned })
        assertEquals(pin, pinButton(holder).contentDescription)
    }

    @Test fun theClearClipboardKeyRemovesPinnedEntriesToo() {
        val (dao, _, holder) = row()
        pinButton(holder).performClick()
        assertEquals(listOf(true), dao.getAll().map { it.pinned }, "positive control: the entry is pinned")
        ime!!.getClipboardHistoryManager().clearHistory() // what the "Clear clipboard" key runs (KeyCode.CLIPBOARD_CLEAR_HISTORY)
        assertEquals(emptyList(), dao.getAll().map { it.text })
    }

    @Test fun aLongPressOnTheEntryStillPins() {
        val (dao, _, holder) = row()
        holder.itemView.performLongClick()
        assertEquals(listOf(true), dao.getAll().map { it.pinned })
    }
}
