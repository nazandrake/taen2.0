// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.media.MediaFormat
import helium314.keyboard.latin.media.MediaInserter
import helium314.keyboard.latin.media.MediaItem
import helium314.keyboard.latin.media.MediaSource
import helium314.keyboard.latin.media.MediaVault
import helium314.keyboard.latin.media.StarterPack
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import java.util.concurrent.Executors

/**
 * The GIF and sticker panel (toolbar key MEDIA), a child of the keyboard view like the quick settings panel. It lists the imported pictures of [MediaVault]
 * (and the starter stickers when that setting is on), narrows them by tag, and a tap sends the picture into the field through [MediaInserter]. While the tier is
 * locked it says so and shows nothing. When the field does not accept images, it shows "This field does not accept images" and sends nothing. Thumbnails are decoded
 * from the decrypted bytes on a background thread and kept in memory only; they are dropped when the panel closes. Nothing here is stored or logged.
 */
class MediaPanelView(context: Context, private val host: Host) : LinearLayout(context) {
    /** What the panel needs from the running keyboard: the connection and the editor info of the focused field. */
    interface Host {
        fun connection(): InputConnection?
        fun editorInfo(): EditorInfo?
    }

    /** One tile: where the bytes are, its type and length, and its tags (empty for a starter sticker). */
    class Cell(val key: String, val source: MediaSource, val format: MediaFormat, val size: Int, val tags: List<String>)

    private val palette = Settings.getValues().mColors
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "taen-media-panel").apply { isDaemon = true } }
    private val thumbs = object : LruCache<String, Bitmap>(THUMB_CACHE) {}
    private val message = TextView(context)
    private val chips = LinearLayout(context)
    private val grid = RecyclerView(context)
    private val adapter = TileAdapter()
    @Volatile private var generation = 0
    private var all: List<Cell> = emptyList()
    private val selectedTags = linkedSetOf<String>()

    /** The tiles shown now (the filter applied). Public for the wiring test. */
    var shown: List<Cell> = emptyList()
        private set

    /** The text of the message line; empty when there is none. Public for the wiring test. */
    val messageText: CharSequence get() = message.text

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        visibility = GONE
        isClickable = true // touches on the panel never reach the keys below it
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setBackgroundColor(palette.get(ColorType.STRIP_BACKGROUND) or 0xFF000000.toInt())
        val header = LinearLayout(context).apply { orientation = HORIZONTAL; layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)) }
        header.addView(label(context.getString(R.string.settings_screen_media), 16f).apply { layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f) })
        header.addView(button(context.getString(R.string.quick_close)) { hide() })
        addView(header)
        addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips); layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
        chips.orientation = HORIZONTAL
        message.apply { gravity = Gravity.CENTER; textSize = 14f; setTextColor(palette.get(ColorType.KEY_TEXT)); visibility = GONE }
        addView(message, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        grid.layoutManager = GridLayoutManager(context, COLUMNS)
        grid.adapter = adapter
        addView(grid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        Privacy.onLock("media-panel") { main.post { clear(); hide() } } // a lock closes the panel and drops the thumbnails
    }

    private fun label(text: String, size: Float) = TextView(context).apply {
        this.text = text; textSize = size; gravity = Gravity.CENTER_VERTICAL; setTextColor(palette.get(ColorType.KEY_TEXT))
    }

    private fun button(text: String, active: Boolean = false, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 14f
        isClickable = true
        setPadding(dp(12), dp(4), dp(12), dp(4))
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(palette.get(if (active) ColorType.ACTION_KEY_BACKGROUND else ColorType.KEY_BACKGROUND) or 0xFF000000.toInt())
        }
        setTextColor(palette.get(if (active) ColorType.ACTION_KEY_ICON else ColorType.KEY_TEXT))
        setOnClickListener { onClick() }
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
    }

    /** Told when the panel opens or closes (the pictures tab of the emoji panel follows it). */
    fun interface ShownListener { fun onShown(shown: Boolean) }

    private var shownListener: ShownListener? = null
    fun setShownListener(listener: ShownListener?) { shownListener = listener }

    fun show() { visibility = VISIBLE; refresh(); shownListener?.onShown(true) }

    fun hide() { visibility = GONE; clear(); shownListener?.onShown(false) }

    /** Drops the thumbnails and the list from memory. */
    fun clear() {
        generation++
        thumbs.evictAll()
        all = emptyList()
        selectedTags.clear()
        display(emptyList())
        chips.removeAllViews()
    }

    /** Reads the list again (off the main thread) and shows it. Public for the wiring test, which waits for it with [awaitIdle]. */
    fun refresh() {
        val mine = ++generation
        val ctx = context.applicationContext ?: context
        val starterOn = ctx.prefs().getBoolean(Settings.PREF_MEDIA_STARTER_PACK, Defaults.PREF_MEDIA_STARTER_PACK)
        worker.execute {
            val listing = Privacy.mediaVault(ctx).list()
            val cells = ArrayList<Cell>()
            if (listing is MediaVault.Listing.Items) listing.items.mapTo(cells) { it.toCell() }
            if (starterOn) for (path in StarterPack.list(ctx)) {
                val bytes = StarterPack.read(ctx, path) ?: continue
                val format = MediaFormat.sniff(bytes) ?: continue
                cells.add(Cell(path, MediaSource.Asset(path), format, bytes.size, emptyList()))
            }
            main.post {
                if (mine != generation) return@post
                all = cells
                setMessage(when {
                    listing is MediaVault.Listing.Locked -> R.string.media_panel_locked
                    listing is MediaVault.Listing.Damaged -> R.string.media_panel_damaged
                    cells.isEmpty() -> R.string.media_panel_empty
                    else -> null
                })
                rebuildChips()
                applyFilter()
            }
        }
    }

    /** Test seam: runs [block] on the worker thread after what was queued before it. */
    fun awaitIdle(block: () -> Unit) { worker.execute { main.post(block) } }

    private fun MediaItem.toCell() = Cell(id, MediaSource.Vault(id), format, size, tags)

    private fun rebuildChips() {
        chips.removeAllViews()
        for (tag in all.flatMap { it.tags }.distinct().sorted()) chips.addView(button(tag, active = tag in selectedTags) {
            if (!selectedTags.remove(tag)) selectedTags.add(tag)
            rebuildChips(); applyFilter()
        })
    }

    private fun applyFilter() = display(if (selectedTags.isEmpty()) all else all.filter { c -> selectedTags.all { it in c.tags } })

    private fun display(cells: List<Cell>) { shown = cells; adapter.notifyDataSetChanged() }

    private fun setMessage(res: Int?) {
        if (res == null) { message.text = ""; message.visibility = GONE } else { message.setText(res); message.visibility = VISIBLE }
    }

    /** Sends [cell] into the focused field, or says why not. Public for the wiring test, which presses the same path as a tap. */
    fun send(cell: Cell) {
        when (MediaInserter.insert(context.packageName, host.connection(), host.editorInfo(), cell.source, cell.format, cell.size)) {
            MediaInserter.Result.SENT -> { setMessage(null); hide() }
            MediaInserter.Result.NOT_ACCEPTED -> setMessage(R.string.media_not_accepted)
            MediaInserter.Result.REFUSED -> setMessage(R.string.media_send_failed)
        }
    }

    private fun decode(cell: Cell): Bitmap? {
        val ctx = context.applicationContext ?: context
        val bytes = when (val s = cell.source) {
            is MediaSource.Vault -> Privacy.mediaVault(ctx).read(s.id)
            is MediaSource.Asset -> StarterPack.read(ctx, s.path)
        } ?: return null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= THUMB_PIXELS && bounds.outHeight / (sample * 2) >= THUMB_PIXELS) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally {
            bytes.fill(0)
        }
    }

    private inner class TileAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val image = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72))
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            return object : RecyclerView.ViewHolder(image) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val cell = shown[position]
            val image = holder.itemView as ImageView
            image.tag = cell.key
            image.setImageBitmap(thumbs.get(cell.key))
            image.contentDescription = cell.tags.joinToString(" ")
            image.setOnClickListener { send(cell) }
            if (thumbs.get(cell.key) == null) {
                val mine = generation
                worker.execute {
                    val bitmap = decode(cell) ?: return@execute
                    main.post {
                        if (mine != generation) return@post
                        thumbs.put(cell.key, bitmap)
                        if (image.tag == cell.key) image.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    companion object {
        private const val COLUMNS = 4
        private const val THUMB_PIXELS = 160
        private const val THUMB_CACHE = 96
        fun layoutParams() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.BOTTOM)
    }
}
