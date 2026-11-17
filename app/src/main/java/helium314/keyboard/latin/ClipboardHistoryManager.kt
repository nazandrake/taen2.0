// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isGone
import androidx.core.view.isVisible
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.compat.ClipboardManagerCompat
import helium314.keyboard.compat.isDeviceLocked
import helium314.keyboard.event.Event
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.databinding.ClipboardSuggestionBinding
import helium314.keyboard.latin.privacy.ClipboardCapture
import helium314.keyboard.latin.privacy.PrivacyPolicy
import helium314.keyboard.latin.settings.SettingsValues
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.FieldText
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarKey

class ClipboardHistoryManager(
        private val latinIME: LatinIME
) : ClipboardManager.OnPrimaryClipChangedListener {

    private lateinit var clipboardManager: ClipboardManager
    private var clipboardSuggestionView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val expiryRunnable = Runnable {
        dao()?.clearOldClips()
        scheduleExpiry()
    }

    /** The encrypted history. Null while the device or the screen is locked (then nothing is stored); it is opened again on later calls. */
    private fun dao(): ClipboardDao? = ClipboardDao.getInstance(latinIME)

    fun onCreate() {
        clipboardManager = latinIME.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(this)
        dao()
        // F1: the clip on the clipboard at service start is not read. The field it was copied in is unknown (a password field copy is not
        // flagged sensitive), so only copies seen while the keyboard runs (onPrimaryClipChanged, with the field known) are stored.
        scheduleExpiry()
    }

    // A window with no text field starts an input (onStartInput with an empty editor of the new app) but no input view, so the settings,
    // and with them the PrivacyPolicy decision, still belong to the previous field (measured on the reference phone, Android 12). The decision of
    // the window that has the focus is kept here until settings are loaded for a field (new InputAttributes). A rebuild by a preference
    // write reuses the previous field's InputAttributes object, so it does not end the window's decision.
    private var startDecision: PrivacyPolicy.Decision? = null
    private var attributesAtStart: InputAttributes? = null

    fun onStartInput(editorInfo: EditorInfo?) {
        attributesAtStart = latinIME.mSettings.current.mInputAttributes
        startDecision = if (editorInfo == null) null else SettingsValues.decidePrivacy(
            latinIME, latinIME.prefs(), InputAttributes(editorInfo, false, latinIME.packageName), isDeviceLocked(latinIME))
    }

    /** What may be remembered for a copy made now: the window that started the last input, unless settings were loaded for a field since. */
    private fun privacyDecision(): PrivacyPolicy.Decision {
        val current = latinIME.mSettings.current
        return startDecision?.takeIf { current.mInputAttributes === attributesAtStart } ?: current.mPrivacy
    }

    fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(this)
        handler.removeCallbacks(expiryRunnable)
    }

    // entries leave the history when they reach the retention time, even if nothing is copied or shown meanwhile
    private fun scheduleExpiry() {
        handler.removeCallbacks(expiryRunnable)
        val expiresAt = dao()?.nextExpiry() ?: return
        val delay = (expiresAt - System.currentTimeMillis()).coerceIn(1_000L, MAX_EXPIRY_CHECK_DELAY_MILLIS)
        handler.postDelayed(expiryRunnable, delay)
    }

    override fun onPrimaryClipChanged() {
        // Make sure we read clipboard content only if history settings is set
        if (latinIME.mSettings.current.mClipboardHistoryEnabled) {
            fetchPrimaryClip()
            dontShowCurrentSuggestion = false
        }
    }

    // Only text is stored. Whether a copy may be stored is decided by ClipboardCapture (sensitive flag, password field, locked device).
    private fun fetchPrimaryClip() {
        val clipData = clipboardManager.primaryClip ?: return
        if (clipData.itemCount == 0) return
        val clipItem = clipData.getItemAt(0) ?: return
        val description = clipData.description ?: return
        if (!description.hasMimeType("text/*")) return
        val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)
        val content = clipItem.coerceToText(latinIME)
        val store = ClipboardCapture.shouldStore(
            historyEnabled = latinIME.mSettings.current.mClipboardHistoryEnabled,
            storeAvailable = dao() != null,
            sensitiveFlag = ClipboardManagerCompat.getClipSensitivity(description),
            incognito = !privacyDecision().storeClipboard, // PrivacyPolicy: password field, no-learning flag, force-incognito, listed app, locked device
            text = content,
        )
        if (!store) return
        dao()?.addClip(timeStamp, content.toString())
        scheduleExpiry()
    }

    fun getPrimaryClipIfText(): String? {
        val clipData = clipboardManager.primaryClip ?: return null
        if (clipData.itemCount == 0) return null
        val clipItem = clipData.getItemAt(0) ?: return null
        return if (clipData.description?.hasMimeType("text/*") == true)
            clipItem.coerceToText(latinIME).toString().takeIf { it.isNotEmpty() }
        else null
    }

    fun toggleClipPinned(id: Long) {
        dao()?.togglePinned(id)
    }

    fun clearHistory() {
        dao()?.clear() // every entry, pinned ones too
        ClipboardManagerCompat.clearPrimaryClip(clipboardManager)
        removeClipboardSuggestion()
    }

    fun canRemove(index: Int) = dao()?.isPinned(index) == false

    fun removeEntry(index: Int) {
        if (canRemove(index))
            dao()?.deleteClipAt(index)
    }

    fun sortHistoryEntries() {
        dao()?.sort()
    }

    // We do not want to update history while user is visualizing it, so we check retention only
    // when history is about to be shown
    fun prepareClipboardHistory() {
        dao()?.clearOldClips()
        scheduleExpiry()
    }

    fun getHistorySize() = dao()?.count() ?: 0

    fun getHistoryEntry(position: Int) = dao()?.getAt(position)

    fun getHistoryEntryContent(id: Long) = dao()?.get(id)

    fun setHistoryChangeListener(listener: ClipboardDao.Listener?) {
        dao()?.listener = listener
    }

    // password fields get no clipboard suggestion at all (PrivacyPolicy, SettingsValues.mSuggestClipboardContent), so only the clip's own flag matters here
    private fun isClipSensitive(): Boolean =
        ClipboardManagerCompat.getClipSensitivity(clipboardManager.primaryClip?.description) ?: false

    fun getClipboardSuggestionView(editorInfo: EditorInfo?, parent: ViewGroup?): View? {
        // maybe no need to create a new view
        // but a cache has to consider a few possible changes, so better don't implement without need
        clipboardSuggestionView = null

        // get the content, or return null
        if (!latinIME.mSettings.current.mSuggestClipboardContent) return null
        if (dontShowCurrentSuggestion) return null
        if (parent == null) return null
        val clipData = clipboardManager.primaryClip ?: return null
        if (clipData.itemCount == 0) return null
        val clipItem = clipData.getItemAt(0) ?: return null
        val hasText = clipData.description?.hasMimeType("text/*") == true
        val hasImage = clipData.description?.hasMimeType("image/*") == true && clipItem.uri != null
        if (!hasText && !hasImage) return null
        val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)
        if (System.currentTimeMillis() - timeStamp > RECENT_TIME_MILLIS) return null
        val content = clipItem.coerceToText(latinIME)

        // create the view
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(latinIME), parent, false)
        val textView = binding.clipboardSuggestionText
        val clipIcon = KeyboardIconsSet.instance.getIconDrawable(ToolbarKey.PASTE.name.lowercase())
        clipIcon?.setBounds(0, 0, textView.lineHeight, textView.lineHeight) // scale the icon to the text
        textView.setCompoundDrawablesRelative(clipIcon, null, null, null)
        val inputType = editorInfo?.inputType ?: InputType.TYPE_NULL
        // a number, phone or date field is offered, and pastes, what it accepts of the text (the same filter as the paste key)
        val pasteText = if (hasText) FieldText.forField(content, inputType) else ""
        if (hasText) {
            if (pasteText.isEmpty()) return null
            val shortenedContent = pasteText.take(200) // for display only
            KeyboardTypeface.applyToTextView(textView)
            textView.text = (if (isClipSensitive()) "*".repeat(shortenedContent.length) else shortenedContent)
        }
        val onClickListener = View.OnClickListener {
            dontShowCurrentSuggestion = true
            if (hasText) latinIME.onTextInput(pasteText)
            else latinIME.onEvent(Event.createSoftwareKeypressEvent(KeyCode.CLIPBOARD_PASTE, 0,
                Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false))
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
            binding.root.isGone = true
        }
        textView.setOnClickListener(onClickListener)

        if (hasImage) {
            if (FieldText.isNumericField(inputType)) return null
            val imageView = binding.clipboardSuggestionImage
            imageView.isVisible = true
            try {
                imageView.setImageURI(clipItem.uri)
            } catch (e: Exception) {
                Log.w(TAG, "error setting clipboard image") // happens with SecurityException: Permission Denial
                return null
            }
            imageView.setOnClickListener(onClickListener)
        }

        val closeButton = binding.clipboardSuggestionClose
        closeButton.setImageDrawable(KeyboardIconsSet.instance.getIconDrawable(ToolbarKey.CLOSE_HISTORY.name.lowercase()))
        closeButton.layoutParams.width = textView.lineHeight // scale the icon to the text
        closeButton.layoutParams.height = textView.lineHeight
        closeButton.setOnClickListener { removeClipboardSuggestion() }

        val colors = latinIME.mSettings.current.mColors
        textView.setTextColor(colors.get(ColorType.KEY_TEXT))
        clipIcon?.let { colors.setColor(it, ColorType.CLIPBOARD_SUGGESTION_ICON) }
        colors.setColor(closeButton, ColorType.REMOVE_SUGGESTION_ICON)
        colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)

        clipboardSuggestionView = binding.root
        return clipboardSuggestionView
    }

    private fun removeClipboardSuggestion() {
        dontShowCurrentSuggestion = true
        val csv = clipboardSuggestionView ?: return
        if (csv.parent != null && !csv.isGone) {
            // clipboard view is shown ->
            latinIME.setNeutralSuggestionStrip()
            latinIME.mHandler.postResumeSuggestions(false)
        }
        csv.isGone = true
    }

    companion object {
        private val TAG = "ClipboardHistoryManager"

        // avoid showing the current suggestion because it has been dismissed or pasted
        private var dontShowCurrentSuggestion: Boolean = false

        const val RECENT_TIME_MILLIS = 3 * 60 * 1000L // 3 minutes (for clipboard suggestions)
        private const val MAX_EXPIRY_CHECK_DELAY_MILLIS = 60 * 1000L
    }
}
