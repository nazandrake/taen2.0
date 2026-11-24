// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import com.android.inputmethod.latin.utils.BinaryDictionaryUtils
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.DictionaryFacilitatorImpl
import helium314.keyboard.latin.common.StringUtils
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowInputMethodManager
import java.util.*

@Implements(LocaleManagerCompat::class)
object ShadowLocaleManagerCompat {
    @Implementation
    @JvmStatic
    fun getSystemLocales(context: Context) = LocaleListCompat.create(Locale.ENGLISH)
}

// why doesn't the original ShadowInputMethodManager simply work?
@Implements(InputMethodManager::class)
class ShadowInputMethodManager2 : ShadowInputMethodManager() {
    @Implementation
    override fun getInputMethodList() = listOf(InputMethodInfo(BuildConfig.APPLICATION_ID, "LatinIME", "Taen", null)) // the application's own package, as the system lists it
    @Implementation
    fun getShortcutInputMethodsAndSubtypes() = emptyMap<InputMethodInfo, List<InputMethodSubtype>>()
    @Implementation
    fun getEnabledInputMethodSubtypeList(imi: InputMethodInfo?, allowsImplicitlySelectedSubtypes: Boolean): List<InputMethodSubtype> = emptyList() // other keyboards: no subtypes
    /** The system's current subtype: the last one the keyboard switched the system to, else an English one. */
    @Implementation
    override fun getCurrentInputMethodSubtype(): InputMethodSubtype = ShadowInputMethodService.systemCurrent
        ?: InputMethodSubtype.InputMethodSubtypeBuilder().setSubtypeLocale("en").build()
    @Implementation
    fun setAdditionalInputMethodSubtypes(imiId: String, subtypes: Array<InputMethodSubtype>) {
        ShadowInputMethodService.additionalSubtypeCalls.add(imiId to subtypes.toList())
    }
    @Implementation
    fun showInputMethodPicker() { pickersShown++ }

    companion object {
        /** How often the system's keyboard picker was asked for. */
        var pickersShown = 0
    }
}

@Implements(BinaryDictionaryUtils::class)
object ShadowBinaryDictionaryUtils {
    @Implementation
    @JvmStatic
    fun calcNormalizedScore(beforeString: String, afterString: String, score: Int): Float {
        val before = StringUtils.toCodePointArray(beforeString)
        val after = StringUtils.toCodePointArray(afterString)
        val distance = editDistance(beforeString, afterString)
        val beforeLength = before.size
        val afterLength = after.size
        if (0 == beforeLength || 0 == afterLength) return 0.0f
        var spaceCount = 0
        for (j: Int in after) {
            if (j == KeyEvent.KEYCODE_SPACE) ++spaceCount
        }
        if (spaceCount == afterLength) return 0.0f
        if (score <= 0 || distance >= afterLength) {
            // normalizedScore must be 0.0f (the minimum value) if the score is less than or equal to 0,
            // or if the edit distance is larger than or equal to afterLength.
            return 0.0f
        }
        // add a weight based on edit distance.
        val weight = 1.0f - distance.toFloat() / afterLength.toFloat()
        return score.toFloat() / 1000000.0f * weight
    }

    private fun editDistance(x: String, y: String): Int {
        val dp = Array(x.length + 1) {
            IntArray(
                y.length + 1
            )
        }
        for (i in 0..x.length) {
            for (j in 0..y.length) {
                if (i == 0) {
                    dp[i][j] = j
                } else if (j == 0) {
                    dp[i][j] = i
                } else {
                    dp[i][j] = min(
                        dp[i - 1][j - 1]
                                + costOfSubstitution(x[i - 1], y[j - 1]),
                        dp[i - 1][j] + 1,
                        dp[i][j - 1] + 1
                    )
                }
            }
        }
        return dp[x.length][y.length]
    }

    private fun min(vararg numbers: Int): Int {
        var min = Int.MAX_VALUE
        for (n: Int in numbers) {
            if (n < min) min = n
        }
        return min
    }

    private fun costOfSubstitution(a: Char, b: Char): Int {
        return if (a == b) 0 else 1
    }
}

@Implements(DictionaryFacilitatorImpl::class)
class ShadowDictionaryFacilitatorImpl {
    @Implementation
    fun hasAtLeastOneInitializedMainDictionary() = true
}

// could also extend LatinIME, it's not final anyway
@Implements(InputMethodService::class)
class ShadowInputMethodService {
    companion object {
        var batchEdit = 0
        var text = ""
        var selectionStart = 0
        var selectionEnd = 0
        var composingStart = -1
        var composingEnd = -1
        var currentInputType = InputType.TYPE_CLASS_TEXT
        var currentImeOptions = 0
        var currentPackage: String? = null
        /** The content types the focused field announces (EditorInfo.contentMimeTypes), and every commitContent call it received (uri, type, flags). */
        var currentContentMimeTypes: Array<String>? = null
        /** The locales the field names for its input (EditorInfo.hintLocales). */
        var currentHintLocales: android.os.LocaleList? = null
        val contentCommits = ArrayList<Triple<android.net.Uri, String, Int>>()
        /** The editor actions (Enter, Go, Search...) the keyboard asked the field to perform. */
        val editorActions = ArrayList<Int>()
        /** The key codes (KeyEvent.KEYCODE_*) of every key-down the keyboard sent to the field. */
        val keyDownCodes = ArrayList<Int>()
        /** Every getExtractedText call the keyboard made, and whether the fake field answers with its text. */
        var extractedTextCalls = 0
        var extractedTextWithText = false
        /** The offset the fake field reports for the extracted text (0: the text is the whole field; otherwise it is a part of it). */
        var extractedStartOffset = 0
        /** Changes the text the fake field gives as extracted text (null: the whole text); the selection stays the field's own. An editor that cuts its answer short does this. */
        var extractedTextTransform: ((String) -> String)? = null
        /** How the fake field answers the text before and after the cursor for a reversed selection: false as reported (the text up to selectionStart, from selectionEnd), true as the platform editors do (up to the lower end, from the higher end). */
        var cursorReadsOrdered = false
        /** How often the keyboard read the text of the field (before the cursor, after it, the selection or the extracted text). */
        var textReads = 0
        /** The context menu actions (select all) the keyboard asked the field to perform. */
        val contextMenuActions = ArrayList<Int>()
        /** How often the keyboard asked the system to go to the next input method (switchToNextInputMethod). */
        var switchedToNextKeyboard = 0
        /** The system's answer to shouldOfferSwitchingToNextInputMethod: it knows every enabled keyboard, also those the app cannot see. */
        var offersNextKeyboard = false
        /** Every subtype the keyboard switched the system to (switchInputMethod(id, subtype)), and every set of additional subtypes it registered. */
        val systemSubtypes = ArrayList<InputMethodSubtype>()
        /** The subtype the system shows now: set by switchInputMethod, or by a test for a change the system made by itself. */
        var systemCurrent: InputMethodSubtype? = null
        val additionalSubtypeCalls = ArrayList<Pair<String, List<InputMethodSubtype>>>()

        // convenience for access
        val textBeforeCursor get() = text.substring(0, selectionStart)
        val textAfterCursor get() = text.substring(selectionEnd)
        val selectedText get() = text.substring(selectionStart, selectionEnd)
        val cursor get() = if (selectionStart == selectionEnd) selectionStart else -1

        // composingText should return everything, but RichInputConnection.mComposingText only returns up to cursor
        val composingText get() = if (composingStart == -1 || composingEnd == -1) ""
        else text.substring(composingStart, composingEnd)

        fun reset() {
            batchEdit = 0
            text = ""
            selectionStart = 0
            selectionEnd = 0
            composingStart = -1
            composingEnd = -1
            currentInputType = InputType.TYPE_CLASS_TEXT
            currentImeOptions = 0
            currentPackage = null
            currentContentMimeTypes = null
            currentHintLocales = null
            contentCommits.clear()
            editorActions.clear()
            keyDownCodes.clear()
            contextMenuActions.clear()
            textReads = 0
            extractedTextCalls = 0
            extractedTextWithText = false
            extractedStartOffset = 0
            extractedTextTransform = null
            cursorReadsOrdered = false
            switchedToNextKeyboard = 0
            offersNextKeyboard = false
            systemSubtypes.clear()
            systemCurrent = null
            additionalSubtypeCalls.clear()
        }
    }

    @Implementation
    fun switchToNextInputMethod(onlyCurrentIme: Boolean): Boolean {
        switchedToNextKeyboard++
        return true
    }

    @Implementation
    fun shouldOfferSwitchingToNextInputMethod(): Boolean = offersNextKeyboard

    @Implementation
    fun switchInputMethod(id: String, subtype: InputMethodSubtype) {
        systemSubtypes.add(subtype)
        systemCurrent = subtype
    }

    @Implementation
    fun getCurrentInputEditorInfo() = EditorInfo().apply {
        inputType = currentInputType
        imeOptions = currentImeOptions
        packageName = currentPackage
        contentMimeTypes = currentContentMimeTypes
        hintLocales = currentHintLocales
        // anything else?
    }
    @Implementation
    fun getCurrentInputConnection() = ic
    @Implementation
    fun isInputViewShown() = true // otherwise selection updates will do nothing

    // essentially this is the text field we're editing in
    private val ic = object : InputConnection {
        // pretty clear (though this may be slow depending on the editor)
        // bad return value here is likely the cause for that weird bug improved/fixed by fixIncorrectLength
        override fun getTextBeforeCursor(p0: Int, p1: Int): CharSequence { textReads++; return (if (cursorReadsOrdered) text.substring(0, minOf(selectionStart, selectionEnd)) else textBeforeCursor).take(p0) }
        // pretty clear (though this may be slow depending on the editor)
        override fun getTextAfterCursor(p0: Int, p1: Int): CharSequence { textReads++; return (if (cursorReadsOrdered) text.substring(maxOf(selectionStart, selectionEnd)) else textAfterCursor).take(p0) }
        // pretty clear
        override fun getSelectedText(p0: Int): CharSequence? {
            textReads++
            return if (selectionStart == selectionEnd) null else text.substring(minOf(selectionStart, selectionEnd), maxOf(selectionStart, selectionEnd)) // as the platform editors: a reversed selection too
        }
        // inserts text at cursor (right?), and sets it as composing text
        // this REPLACES currently composing text (even if at a different position)
        // moves the cursor: positive means relative to composing text start, negative means relative to start
        override fun setComposingText(newText: CharSequence, cursor: Int): Boolean {
            // first remove the composing text if any
            if (composingStart != -1 && composingEnd != -1)
                text = text.substring(0, composingStart) + text.substring(composingEnd)
            else // no composing span active, we should remove selected text
                if (selectionStart != selectionEnd) {
                    text = textBeforeCursor + textAfterCursor
                    selectionEnd = selectionStart
                }
            // then set the new text at old composing start
            // if no composing start, set it at cursor position
            val insertStart = if (composingStart == -1) selectionStart else composingStart
            text = text.substring(0, insertStart) + newText + text.substring(insertStart)
            composingStart = insertStart
            composingEnd = insertStart + newText.length
            // the cursor -1 is not clear in documentation, but
            // "So a value of 1 will always advance you to the position after the full text being inserted"
            // means that 1 must be composingEnd
            selectionStart = if (cursor > 0) composingEnd + cursor - 1
            else -cursor
            selectionEnd = selectionStart
            return true
        }
        override fun setComposingRegion(p0: Int, p1: Int): Boolean {
            println("setComposingRegion, $p0, $p1")
            composingStart = p0
            composingEnd = p1
            return true // never checked
        }
        // sets composing text empty, but doesn't change actual text
        override fun finishComposingText(): Boolean {
            composingStart = -1
            composingEnd = -1
            return true // always true
        }
        // as per documentation: "This behaves like calling setComposingText(text, newCursorPosition) then finishComposingText()"
        override fun commitText(p0: CharSequence, p1: Int): Boolean {
            setComposingText(p0, p1)
            finishComposingText()
            return true // whether we added the text
        }
        // just tells the text field that we add many updated, and that the editor should not
        // send status updates until batch edit ended (not actually used for this simulation)
        override fun beginBatchEdit(): Boolean {
            ++batchEdit
            return true // always true
        }
        // end a batch edit, but maybe there are multiple batch edits happening
        override fun endBatchEdit(): Boolean {
            if (batchEdit > 0)
                return --batchEdit == 0
            return false // returns true if there is still a batch edit ongoing
        }
        // should notify about cursor info containing composing text, selection, ...
        override fun requestCursorUpdates(p0: Int): Boolean {
            // we call this, but don't have onUpdateCursorAnchorInfo overridden in latinIME, so it does nothing
            // also currently we don't care about the return value
            return false
        }
        override fun setSelection(p0: Int, p1: Int): Boolean {
            selectionStart = p0
            selectionEnd = p1
            return true
        }
        // delete beforeLength before cursor position, and afterLength after cursor position
        // chars, not codepoints or glyphs
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // delete only before or after selection
            text = textBeforeCursor.substring(0, textBeforeCursor.length - beforeLength) +
                text.substring(selectionStart, selectionEnd) +
                textAfterCursor.substring(afterLength)

            // if parts of the composing span are deleted, shorten the span (set end to shorter)
            if (selectionStart <= composingStart) {
                composingStart -= beforeLength // is this correct?
                composingEnd -= beforeLength
            } else if (selectionStart <= composingEnd) {
                composingEnd -= beforeLength // is this correct?
            }
            if (selectionEnd <= composingStart) {
                composingStart -= afterLength
                composingEnd -= afterLength
            } else if (selectionEnd <= composingEnd) {
                composingEnd -= afterLength
            }
            // update selection
            selectionStart -= beforeLength
            selectionEnd -= beforeLength
            return true
        }
        override fun sendKeyEvent(p0: KeyEvent): Boolean {
            if (p0.action != KeyEvent.ACTION_DOWN) return true // only change the text on key down, like RichInputConnection does
            keyDownCodes.add(p0.keyCode)
            if (p0.keyCode == KeyEvent.KEYCODE_DEL) {
                if (selectionEnd == 0) return true // nothing to delete
                if (selectedText.isEmpty()) {
                    text = text.substring(0, selectionStart - 1) + text.substring(selectionEnd)
                    selectionStart -= 1
                } else {
                    text = text.substring(0, selectionStart) + text.substring(selectionEnd)
                }
                selectionEnd = selectionStart
                return true
            }
            val textToAdd = when (p0.keyCode) {
                KeyEvent.KEYCODE_ENTER -> "\n"
                KeyEvent.KEYCODE_DEL -> null
                KeyEvent.KEYCODE_UNKNOWN -> p0.characters
                else -> StringUtils.newSingleCodePointString(p0.unicodeChar)
            }
            if (textToAdd != null && textToAdd.singleOrNull()?.code != 0) {
                text = text.substring(0, selectionStart) + textToAdd + text.substring(selectionEnd)
                selectionStart += textToAdd.length
                selectionEnd = selectionStart
                composingStart = -1
                composingEnd = -1
            }
            return true
        }
        // implementation is only to work with getTextBeforeCursorAndDetectLaggyConnection
        override fun getExtractedText(p0: ExtractedTextRequest?, p1: Int): ExtractedText {
            extractedTextCalls++
            textReads++
            return ExtractedText().also {
                if (extractedTextWithText) it.text = extractedTextTransform?.invoke(text) ?: text
                it.startOffset = extractedStartOffset
                it.selectionStart = selectionStart
                it.selectionEnd = selectionEnd
            }
        }
        // only effect is flashing, so whatever...
        override fun commitCorrection(p0: CorrectionInfo?): Boolean = true
        // implement only when necessary
        override fun getCursorCapsMode(p0: Int): Int = throw UnsupportedOperationException()
        override fun deleteSurroundingTextInCodePoints(p0: Int, p1: Int): Boolean = throw UnsupportedOperationException()
        override fun commitCompletion(p0: CompletionInfo?): Boolean = throw UnsupportedOperationException()
        override fun performEditorAction(p0: Int): Boolean { editorActions.add(p0); return true }
        /** Only "select all" is simulated; it is what the select all and clear all keys use. */
        override fun performContextMenuAction(p0: Int): Boolean {
            if (p0 != android.R.id.selectAll) throw UnsupportedOperationException()
            contextMenuActions.add(p0)
            selectionStart = 0
            selectionEnd = text.length
            return true
        }
        override fun clearMetaKeyStates(p0: Int): Boolean = throw UnsupportedOperationException()
        override fun reportFullscreenMode(p0: Boolean): Boolean = throw UnsupportedOperationException()
        override fun performPrivateCommand(p0: String?, p1: Bundle?): Boolean = throw UnsupportedOperationException()
        override fun getHandler(): Handler = throw UnsupportedOperationException()
        override fun closeConnection() = throw UnsupportedOperationException()
        // the picture panel sends through this; the calls are recorded (uri, type, flags)
        override fun commitContent(p0: InputContentInfo, p1: Int, p2: Bundle?): Boolean {
            contentCommits.add(Triple(p0.contentUri, p0.description.getMimeType(0), p1))
            return true
        }
    }
}
