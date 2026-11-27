// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.os.Handler
import android.os.Message
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.*
import androidx.core.content.edit
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowInputMethodService.Companion.composingEnd
import helium314.keyboard.ShadowInputMethodService.Companion.composingStart
import helium314.keyboard.ShadowInputMethodService.Companion.selectedText
import helium314.keyboard.ShadowInputMethodService.Companion.selectionEnd
import helium314.keyboard.ShadowInputMethodService.Companion.selectionStart
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.ShadowFacilitator2.Companion.lastAddedWord
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.common.StringUtils
import helium314.keyboard.latin.inputlogic.InputLogic
import helium314.keyboard.latin.inputlogic.SpaceState
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ScriptUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getTimestampFormatter
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLog
import java.util.*
import kotlin.math.min
import kotlin.streams.asSequence
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowLocaleManagerCompat::class,
    ShadowInputMethodManager2::class,
    ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class,
    ShadowHandler::class,
    ShadowFacilitator2::class,
])
class InputLogicTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val settingsValues get() = Settings.getValues()
    private val inputLogic get() = latinIME.mInputLogic
    private val connection: RichInputConnection get() = inputLogic.mConnection
    private val composerReader = InputLogic::class.java.getDeclaredField("mWordComposer").apply { isAccessible = true }
    private val composer get() = composerReader.get(inputLogic) as WordComposer
    private val spaceStateReader = InputLogic::class.java.getDeclaredField("mSpaceState").apply { isAccessible = true }
    private val spaceState get() = spaceStateReader.get(inputLogic) as Int
    private val beforeComposingReader = RichInputConnection::class.java.getDeclaredField("mCommittedTextBeforeComposingText").apply { isAccessible = true }
    private val connectionTextBeforeComposingText get() = (beforeComposingReader.get(connection) as CharSequence).toString()
    private val composingReader = RichInputConnection::class.java.getDeclaredField("mComposingText").apply { isAccessible = true }
    private val connectionComposingText get() = (composingReader.get(connection) as CharSequence).toString()

    private val textBeforeCursor get() = ShadowInputMethodService.textBeforeCursor
    private val textAfterCursor get() = ShadowInputMethodService.textAfterCursor
    private val composingText get() = ShadowInputMethodService.composingText
    private val text get() = ShadowInputMethodService.text
    private val cursor get() = ShadowInputMethodService.cursor
    private var currentInputType get() = ShadowInputMethodService.currentInputType
        set(value) { ShadowInputMethodService.currentInputType = value }

    init {
        ShadowLog.setupLogging()
        ShadowLog.stream = System.out
    }

    @Test fun inputCode() {
        input('c')
        assertEquals("c", textBeforeCursor)
        assertEquals("c", getTextFromConnection())
        assertEquals("", textAfterCursor)
        assertEquals("c", composingText)
        latinIME.mHandler.onFinishInput()
        assertEquals("", composingText)
    }

    @Test fun delete() {
        setText("hello there ")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello there", text)
        assertEquals("there", composingText)
    }

    @Test fun deleteMultiCodepointText() {
        setText("hello there \uD83E\uDF00")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello there ", text)
    }

    @Test fun deleteCombinedText() {
        setText("hello there э́")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello there ", text)

        setText("hello there H̵̛͕̞̦̰̜͍̰̥̟͆̏͂̌͑́ͅ")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello there ", text)
    }

    @Test fun deleteInsideWord() {
        setText("hello you there")
        setCursorPosition(8) // after o in you
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello yu there", text)
        assertEquals("yu", composingText)
    }

    @Test fun insertLetterIntoWord() {
        setText("hello")
        setCursorPosition(3) // after first l
        input('i')
        assertEquals("helilo", getWordAtCursor())
        assertEquals("helilo", getTextFromConnection())
        assertEquals(4, getCursorPosition())
        assertEquals(4, cursor)
        assertEquals("", composingText)
    }

    @Test fun insertLetterIntoWordWithWeirdEditor() {
        currentInputType = 180225 // should not change much, but just to be sure
        setText("hello")
        setCursorPosition(3, weirdTextField = true) // after first l
        input('i')
        assertEquals("helilo", getWordAtCursor())
        assertEquals("helilo", getTextFromConnection())
        assertEquals(4, getCursorPosition())
        assertEquals(4, cursor)
    }

    @Test fun insertLetterIntoOneOfSeveralWords() {
        setText("hello my friend")
        setCursorPosition(7) // between m and y
        input('a')
        assertEquals("may", getWordAtCursor())
        assertEquals("hello may friend", getTextFromConnection())
        assertEquals(8, getCursorPosition())
        assertEquals(8, cursor)
    }

    @Test fun combineHangul() {
        val ko = SubtypeSettings.getResourceSubtypesForLocale("ko".constructLocale()).first()
        latinIME.switchToSubtype(ko)
        chainInput("ㅂㄱㅑ")
        assertEquals("ㅂ갸", text)
    }

    @Test fun emojiHangul() {
        val ko = SubtypeSettings.getResourceSubtypesForLocale("ko".constructLocale()).first()
        latinIME.switchToSubtype(ko)
        input(0x1F970)
        assertEquals("\uD83E\uDD70", text)
    }

    @Test fun insertLetterIntoWordHangulFails() {
        if (BuildConfig.BUILD_TYPE == "runTests") return
        latinIME.switchToSubtype(SubtypeSettings.getResourceSubtypesForLocale("ko".constructLocale()).first())
        chainInput("ㅛㅎㄹㅎㅕㅛ")
        setCursorPosition(3)
        input('ㄲ') // fails, as expected from the hangul issue when processing the event in onCodeInput
        assertEquals("ㅛㅎㄹㄲ혀ㅛ", getWordAtCursor())
        assertEquals("ㅛㅎㄹㄲ혀ㅛ", getTextFromConnection())
        assertEquals("ㅛㅎㄹㄲ혀ㅛ", textBeforeCursor + textAfterCursor)
        assertEquals(4, getCursorPosition())
        assertEquals(4, cursor)
    }

    // see issue 1447
    @Test fun separatorAfterHangul() {
        latinIME.switchToSubtype(SubtypeSettings.getResourceSubtypesForLocale("ko".constructLocale()).first())
        chainInput("ㅛ.")
        assertEquals("ㅛ.", text)
    }

    @Test fun deleteHangulInDebugMode() { // issue 1551, later only happened on phone
        latinIME.switchToSubtype(SubtypeSettings.getResourceSubtypesForLocale("ko".constructLocale()).first())
        setText("ㅛㅛ ")
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE)
    }

    @Test fun separatorUnselectsWord() {
        setText("hello")
        assertEquals("hello", composingText)
        input('.')
        assertEquals("", composingText)
    }

    @Test fun autospace() {
        setText("hello")
        input('.')
        input('a')
        assertEquals("hello.a", textBeforeCursor)
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        setText("hello")
        input('.')
        input('a')
        assertEquals("hello. a", textBeforeCursor)
    }

    // ---- smart punctuation ---------------------------------------------------------------------------------

    private fun typeRaw(char: Char) {
        latinIME.onEvent(Event.createEventForCodePointFromUnknownSource(char.code))
        handleMessages()
        checkConnectionConsistency()
    }

    private fun typeBulgarian(text: String) = text.forEach { if (it == '"') typeRaw(it) else input(it.code) }

    private fun switchToBulgarian() = latinIME.switchToSubtype(SubtypeSettings.getResourceSubtypesForLocale("bg".constructLocale()).first())

    // starting a text field resets the subtype of the test: switch after it
    private fun startBulgarian(initialText: String = "") { setText(initialText); switchToBulgarian() }

    @Test fun bulgarianQuotesOpenAndCloseWhileTyping() {
        startBulgarian()
        typeBulgarian("Каза \"здравей\" на всички")
        assertEquals("Каза „здравей“ на всички", text)
        startBulgarian()
        typeBulgarian("\"Добре,\" рече той (\"може\").")
        assertEquals("„Добре,“ рече той („може“).", text)
    }

    @Test fun bulgarianQuotesAtTheStartAfterDigitsAndAsAnEmptyPair() {
        startBulgarian()
        typeRaw('"')
        assertEquals("„", text)
        typeRaw('"')
        assertEquals("„“", text) // an empty pair
        startBulgarian()
        typeBulgarian("екран 5\"")
        assertEquals("екран 5\"", text) // inches stay plain
    }

    @Test fun bulgarianQuotesWrapASelection() {
        startBulgarian("дума")
        setCursorPosition(0, 4)
        typeRaw('"')
        assertEquals("„дума“", text)
    }

    @Test fun otherLanguagesKeepThePlainQuote() {
        typeBulgarian("said \"hello\" to me") // the default subtype is English
        assertEquals("said \"hello\" to me", text)
    }

    @Test fun smartQuotesCanBeTurnedOffAndAreOffInPasswordUrlAndEmailFields() {
        startBulgarian()
        typeBulgarian("a \"b\"")
        assertEquals("a „b“", text) // positive control: on by default
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)) {
            setInputType(InputType.TYPE_CLASS_TEXT or variation)
            startBulgarian()
            assertEquals("bg", settingsValues.mLocale.language, "variation $variation: the test types Bulgarian")
            typeBulgarian("a \"b\"")
            assertEquals("a \"b\"", text, "variation $variation")
        }
        setInputType(InputType.TYPE_CLASS_TEXT)
        startBulgarian()
        typeBulgarian("a \"b\"")
        assertEquals("a „b“", text) // control: the general text field works again
        latinIME.prefs().edit { putBoolean(Settings.PREF_SMART_QUOTES, false) }
        startBulgarian()
        typeBulgarian("a \"b\"")
        assertEquals("a \"b\"", text)
    }

    @Test fun apostropheAndOtherSymbolsAreNotChangedInBulgarian() {
        startBulgarian()
        assertEquals("bg", settingsValues.mLocale.language)
        typeBulgarian("а 'б' (в) «г»")
        assertEquals("а 'б' (в) «г»", text)
    }

    @Test fun doubleSpaceGivesAPeriodInBulgarianToo() {
        startBulgarian()
        assertEquals("bg", settingsValues.mLocale.language)
        chainInput("Здравей")
        input(' ')
        input(' ')
        assertEquals("Здравей. ", text)
    }

    @Test fun autospaceButWithTextAfter() {
        setText("hello there")
        setCursorPosition(5) // after hello
        input('.')
        input('a')
        assertEquals("hello.a", textBeforeCursor)
        assertEquals("hello.a there", text)
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        setText("hello there")
        setCursorPosition(5) // after hello
        input('.')
        input('a')
        assertEquals("hello. a", textBeforeCursor)
        assertEquals("hello. a there", text)
    }

    @Test fun noAutospaceInUrlField() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("example.net")
        assertEquals("example. net", text)
        lastAddedWord = ""
        setText("")
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("example.net")
        assertEquals("", lastAddedWord)
        assertEquals("example.net", text)
        assertEquals("example.net", composingText)
    }

    @Test fun noAutospaceInUrlFieldWhenPickingSuggestion() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("exam")
        pickSuggestion("example")
        assertEquals("example", text)
        input('.')
        assertEquals("example.", text)
    }

    @Test fun noAutospaceForDetectedUrl() { // "light" version, should work without url detection
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("http://example.net")
        assertEquals("http://example.net", text)
        assertEquals("http", lastAddedWord)
        assertEquals("example.net", composingText)
    }

    @Test fun noAutospaceForDetectedEmail() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("mail@example.com")
        assertEquals("mail@example.com", text)
        assertEquals("mail@example", lastAddedWord)
        assertEquals("com", composingText)
        setText("")
        lastAddedWord = ""
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("mail@example.com")
        assertEquals("", lastAddedWord)
        assertEquals("mail@example.com", composingText)
    }

    @Test fun urlDetectionThings() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("...h")
        assertEquals("...h", text)
        assertEquals("h", composingText)
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("bla..")
        assertEquals("bla..", text)
        assertEquals("", composingText)
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("bla.c")
        assertEquals("bla.c", text)
        assertEquals("bla.c", composingText)
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        latinIME.prefs().edit { putBoolean(Settings.PREF_SHIFT_REMOVES_AUTOSPACE, true) }
        input("bla")
        input('.')
        functionalKeyPress(KeyCode.SHIFT) // should remove the phantom space (in addition to normal effect)
        input('c')
        assertEquals("bla.c", text)
        assertEquals("bla.c", composingText)
    }

    @Test fun stripSeparatorsBeforeAddingToHistoryWithURLDetection() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("example.com.")
        assertEquals("example.com.", composingText)
        input(' ')
        assertEquals("example.com", lastAddedWord)
    }

    @Test fun dontSelectConsecutiveSeparatorsWithURLDetection() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("bla..")
        assertEquals("", composingText)
        assertEquals("bla..", text)
    }

    @Test fun selectDoesSelect() {
        setText("this is some text")
        setCursorPosition(3, 8)
        assertEquals("s is ", text.substring(3, 8))
    }

    @Test fun noComposingForPasswordFields() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        input('a')
        input('b')
        assertEquals("", composingText)
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        input('.')
        input('c')
        assertEquals("", composingText)
    }

    @Test fun `don't select whole thing as composing word if URL detection disabled`() {
        setText("http://example.com")
        setCursorPosition(13) // between l and e
        assertEquals("example", composingText)
    }

    @Test fun `select whole thing except http(s) as composing word if URL detection enabled and selecting`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setText("http://example.com")
        setCursorPosition(13) // between l and e
        assertEquals("example.com", composingText)
        setText("http://bla.com http://example.com ")
        setCursorPosition(29) // between l and e
        assertEquals("example.com", composingText)
    }

    @Test fun `select whole thing except http(s) as composing word if URL detection enabled and typing`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("http://example.com")
        assertEquals("example.com", composingText)
    }

    @Test fun `don't add partial URL to history`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setText("http:/") // just so lastAddedWord isn't set to http
        chainInput("/bla.com")
        assertEquals("", lastAddedWord)
    }

    @Test fun urlProperlySelected() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        setText("http://example.com/here")
        setCursorPosition(18) // after .com
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE) // delete com
        assertEquals("", composingText)
        chainInput("net")
        assertEquals("example.net", composingText)
    }

    @Test fun urlProperlySelectedWhenNotDeletingFullTld() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setText("http://example.com/here")
        setCursorPosition(18) // after .com
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE) // delete om
        assertEquals("example.c/here", composingText)
        chainInput("z")
        assertEquals("", composingText)
    }

    @Test fun dontCommitPartialUrlBeforeFirstPeriod() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        // type http://bla. -> bla not selected, but clearly url, also means http://bla is committed which we probably don't want
        chainInput("http://bla.")
        assertEquals("bla.", composingText)
    }

    @Test fun `intermediate commits in text field without protocol`() {
        chainInput("bla.")
        assertEquals("bla", lastAddedWord)
        chainInput("com/")
        assertEquals("com", lastAddedWord)
        chainInput("img.jpg")
        assertEquals("img", lastAddedWord)
        assertEquals("jpg", composingText)
    }

    @Test fun `intermediate commit in text field without protocol and with URL detection`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("bla.com/img.jpg")
        assertEquals("bla", lastAddedWord)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `only protocol commit in text field with protocol and URL detection`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("http://bla.com/img.jpg")
        assertEquals("http", lastAddedWord)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `no intermediate commit in URL field with protocol`() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("http://bla.com/img.jpg")
        assertEquals("http", lastAddedWord)
        assertEquals("http://bla.com/img.jpg", text)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `no intermediate commit in URL field with protocol and URL detection`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("http://bla.com/img.jpg")
        assertEquals("http", lastAddedWord)
        assertEquals("http://bla.com/img.jpg", text)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `no intermediate commit in URL field without protocol`() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("bla.com/img.jpg")
        assertEquals("", lastAddedWord)
        assertEquals("bla.com/img.jpg", text)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `no intermediate commit in URL field without protocol and with URL detection`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("bla.com/img.jpg")
        assertEquals("", lastAddedWord)
        assertEquals("bla.com/img.jpg", text)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `don't accidentally detect some other text fields as URI`() {
        // see comment in InputLogic.textBeforeCursorMayBeUrlOrSimilar
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE)
        chainInput("Hey,why")
        assertEquals("Hey, why", text)
    }

    @Test fun `URL detection does not trigger on non-words`() {
        // first make sure it works without URL detection
        chainInput("15:50-17")
        assertEquals("15:50-17", text)
        assertEquals("", composingText)
        // then with URL detection
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("15:50-17")
        assertEquals("15:50-17", text)
        assertEquals("", composingText)
    }

    @Test fun `autospace after selecting a suggestion`() {
        pickSuggestion("this")
        input('b')
        assertEquals("this b", text)
        assertEquals("b", composingText)
    }

    @Test fun `autospace works in URL field when input isn't URL`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        pickSuggestion("this")
        input('b')
        assertEquals("this b", text)
        assertEquals("b", composingText)
    }

    // https://github.com/HeliBorg/HeliBoard/issues/215
    // https://github.com/HeliBorg/HeliBoard/issues/229
    @Test fun `autospace works in URL field when input isn't URL, also for multiple suggestions`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        pickSuggestion("this")
        pickSuggestion("is")
        assertEquals("this is", text)
        pickSuggestion("not")
        assertEquals("this is not", text)
        input('c')
        assertEquals("this is not c", text)
        assertEquals("c", composingText)
    }

    @Test fun `emoji is added to dictionary`() {
        // check both text and codepoint input
        chainInput("hello ")
        input(0x1F36D)
        assertEquals(StringUtils.newSingleCodePointString(0x1F36D), lastAddedWord)
        reset()
        chainInput("hello ")
        input("🤗")
        assertEquals("\uD83E\uDD17", lastAddedWord)

        reset()
        chainInput("hello ")
        input("why 🤗 ") // not added because it's not only emoji (input can come from pasting)
        assertEquals("hello", lastAddedWord)
    }

    @Test fun `emoji uses phantom space`() {
        // check both text and codepoint input
        pickSuggestion("hi")
        input("🤗")
        assertEquals("\uD83E\uDD17", lastAddedWord)
        assertEquals("hi \uD83E\uDD17", text)
        reset()
        pickSuggestion("hi")
        input(0x1F36D)
        assertEquals(StringUtils.newSingleCodePointString(0x1F36D), lastAddedWord)
        assertEquals("hi ${StringUtils.newSingleCodePointString(0x1F36D)}", text)
    }

    // https://github.com/HeliBorg/HeliBoard/issues/230
    @Test fun `no autospace after opening quotes`() {
        chainInput("\"Hi\" \"h")
        assertEquals("\"Hi\" \"h", text)
        assertEquals("h", composingText)
        reset()
        chainInput("\"Hi\", \"h")
        assertEquals("\"Hi\", \"h", text)
        assertEquals("h", composingText)
    }

    @Test fun `autospace works in URL field when starting with quotes`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        input("\"")
        pickSuggestion("this")
        input("i")
        assertEquals("\"this i", text)
    }

    @Test fun `double space results in period and space, and delete removes the period`() {
        chainInput("hello")
        input(' ')
        input(' ')
        assertEquals("hello. ", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello ", text)
    }

    @Test fun `no weird space inside multi-"`() {
        chainInput("\"\"\"")
        assertEquals("\"\"\"", text)

        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("\"\"\"")
        assertEquals("\"\"\"", text)
    }

    @Test fun `autospace still happens after "`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("\"hello\"you")
        assertEquals("\"hello\" you", text)
    }

    @Test fun `autospace still happens after " if next word is in quotes`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("\"hello\"\"you\"")
        assertEquals("\"hello\" \"you\"", text)
    }

    @Test fun `autospace propagates over "`() {
        input('"')
        pickSuggestion("hello")
        assertEquals(spaceState, SpaceState.PHANTOM) // picking a suggestion sets phantom space state
        chainInput("\"you")
        assertEquals("\"hello\" you", text)
    }

    @Test fun `autospace still happens after " if nex word is in " and after comma`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("\"hello\",\"you\"")
        assertEquals("\"hello\", \"you\"", text)
    }

    @Test fun `autospace in json editor`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("{\"label\":\"")
        assertEquals("{\"label\": \"", text)
        input('c')
        assertEquals("{\"label\": \"c", text)
    }

    @Test fun `text input and delete`() {
        input("hello")
        assertEquals("hello", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hell", text)

        reset()
        input("hello ")
        assertEquals("hello ", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello", text)
    }

    @Test fun `emoji text input and delete`() {
        input("🕵🏼")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("", text)

        reset()
        input("\uD83D\uDD75\uD83C\uDFFC")
        input(' ')
        assertEquals("🕵🏼 ", text)
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("", text)
    }

    // emoRegex update to unicode 16.0 was required, https://github.com/HeliBorg/HeliBoard/issues/1760
    @Test fun `emojis deleted one by one`() {
        chainInput("\uD83E\uDEC6\uD83E\uDEC6\uD83E\uDEC6")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("\uD83E\uDEC6\uD83E\uDEC6", text)
    }

    @Test fun `revert autocorrect on delete`() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        chainInput("hullo")
        getAutocorrectedWithSpaceAfter("hello", "hullo")
        assertEquals("hello ", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hullo", text)

        reset()
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        latinIME.prefs().edit { putBoolean(Settings.PREF_BACKSPACE_REVERTS_AUTOCORRECT, false) }
        chainInput("hullo")
        getAutocorrectedWithSpaceAfter("hello", "hullo")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello", text)
    }

    // ---- taking an autocorrection back teaches the keyboard the word the user typed ------------------------------------------

    @Test fun `reverting an autocorrect learns the word the user typed and unlearns the correction`() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        chainInput("hullo")
        getAutocorrectedWithSpaceAfter("hello", "hullo")
        assertEquals("hello ", text)
        assertEquals("hello", lastAddedWord) // the corrected word was learned when it was committed
        assertEquals(true, ShadowFacilitator2.mayPromote.last(), "positive control: an ordinary commit may be promoted to the personal dictionary")
        lastAddedWord = ""
        ShadowFacilitator2.unlearned.clear()
        ShadowFacilitator2.added.clear()
        ShadowFacilitator2.mayPromote.clear()
        functionalKeyPress(KeyCode.DELETE) // the user takes the correction back
        assertEquals("hullo", text)
        assertEquals(listOf("hullo", "hullo"), ShadowFacilitator2.added, "an explicit revert counts as two uses: one was measured not to stop the correction")
        assertEquals("hullo", lastAddedWord, "the typed word must be learned, or it is corrected again next time")
        assertEquals(listOf("hello"), ShadowFacilitator2.unlearned)
        // an undone correction only teaches the decaying user history; two undos of one word must never reach the personal dictionary
        assertEquals(listOf(false, false), ShadowFacilitator2.mayPromote)
    }

    @Test fun `nothing is learned in a password, no-learning, incognito or locked field`() {
        val privacy = helium314.keyboard.latin.privacy.Privacy
        typeAndCommit("hello", Constants.CODE_SPACE) // positive control: the same keys learn in a normal field
        assertEquals("hello", lastAddedWord)
        val states = listOf<Pair<String, () -> Unit>>(
            "password field" to { setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) },
            "no-learning field" to { ShadowInputMethodService.currentImeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING; setText("") },
            "force incognito" to { latinIME.prefs().edit { putBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, true) }; setText("") },
            "locked" to { privacy.lock(latinIME); setText("") },
        )
        try {
            for ((name, enter) in states) {
                reset()
                privacy.unlock(latinIME)
                enter()
                lastAddedWord = ""
                val before = ShadowFacilitator2.added.size
                typeAndCommit("hello", Constants.CODE_SPACE)
                typeAndCommit("world")
                assertEquals("", lastAddedWord, name)
                assertEquals(before, ShadowFacilitator2.added.size, name)
            }
        } finally {
            privacy.unlock(latinIME)
        }
    }

    @Test fun `a reverted autocorrect is not learned in incognito mode`() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        latinIME.prefs().edit { putBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, true) }
        chainInput("hullo")
        getAutocorrectedWithSpaceAfter("hello", "hullo")
        lastAddedWord = ""
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hullo", text) // positive control: the revert happened
        assertEquals("", lastAddedWord)
    }

    @Test fun `a reverted autocorrect is not learned in a field that asks for no personalized learning`() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        ShadowInputMethodService.currentImeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        setText(text)
        chainInput("hullo")
        getAutocorrectedWithSpaceAfter("hello", "hullo")
        lastAddedWord = ""
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hullo", text)
        assertEquals("", lastAddedWord)
    }

    @Test fun `a revert that learns nothing else changes the word is learned once per revert`() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        for (round in 1..2) {
            chainInput("hullo")
            getAutocorrectedWithSpaceAfter("hello", "hullo")
            lastAddedWord = ""
            functionalKeyPress(KeyCode.DELETE)
            assertEquals("hullo", lastAddedWord, "round $round")
            setText("")
        }
    }

    @Test fun `remove glide typing word on delete`() {
        glideTypingInput("hello")
        assertEquals("hello", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("", text)

    }

    @Test fun timestamp() {
        chainInput("hello")
        functionalKeyPress(KeyCode.TIMESTAMP)
        assertEquals(Calendar.getInstance().time.time.toDouble(),
            getTimestampFormatter(latinIME).parse(text.substring(5))!!.time.toDouble(), 1000.0)
    }

    @Test fun inlineEmojiSearchStart() {
        assertEquals(true, InputLogic.isStartOfInlineEmojiSearch('t'.code, ':'.code, ' '.code, settingsValues))
        assertEquals(false, InputLogic.isStartOfInlineEmojiSearch(' '.code, ':'.code, ' '.code, settingsValues))
        assertEquals(true, InputLogic.isStartOfInlineEmojiSearch('t'.code, ':'.code, '.'.code, settingsValues))
        assertEquals(true, InputLogic.isStartOfInlineEmojiSearch('t'.code, ':'.code, "🌍".codePoints().asSequence().last(), settingsValues))
        assertEquals(false, InputLogic.isStartOfInlineEmojiSearch('t'.code, ':'.code, 't'.code, settingsValues))
        assertEquals(false, InputLogic.isStartOfInlineEmojiSearch('t'.code, ':'.code, '3'.code, settingsValues))
    }

    @Test fun inlineEmojiSearchString() {
        assertEquals("test", InputLogic.getInlineEmojiSearchString(":test"))
        assertEquals(null, InputLogic.getInlineEmojiSearchString("test"))
        assertEquals("test", InputLogic.getInlineEmojiSearchString(" :test"))
        assertEquals(null, InputLogic.getInlineEmojiSearchString("t:test"))
        assertEquals(null, InputLogic.getInlineEmojiSearchString("6:test"))
        assertEquals("test", InputLogic.getInlineEmojiSearchString("🌍:test"))
        assertEquals("test", InputLogic.getInlineEmojiSearchString(",:test"))
        assertEquals(null, InputLogic.getInlineEmojiSearchString(":test\nt"))
        assertEquals("/48", InputLogic.getInlineEmojiSearchString("2606:127.0.0.1::/48")) // do we want this?
    }

    @Test fun moveCursorHorizontally() {
        chainInput("hello")
        assertEquals(5, cursor)
        latinIME.mKeyboardActionListener.onHorizontalSpaceSwipe(-2)
        assertEquals(3, cursor)
        latinIME.mKeyboardActionListener.onHorizontalSpaceSwipe(-5)
        assertEquals(0, cursor)
        latinIME.mKeyboardActionListener.onHorizontalSpaceSwipe(-1)
        assertEquals(0, cursor)
        latinIME.mKeyboardActionListener.onHorizontalSpaceSwipe(3)
        assertEquals(3, cursor)
        latinIME.mKeyboardActionListener.onHorizontalSpaceSwipe(3)
        assertEquals(5, cursor)
        latinIME.mKeyboardActionListener.onHorizontalSpaceSwipe(1)
        assertEquals(5, cursor)
    }

    // ---- number fields: paste, select all, clear all -------------------------------------------------

    private val clipboard get() = latinIME.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    private fun setClip(text: String) = clipboard.setPrimaryClip(android.content.ClipData.newPlainText("test", text))
    private fun press(keyCode: Int) = latinIME.onEvent(Event.createSoftwareKeypressEvent(Event.NOT_A_CODE_POINT, keyCode, 0, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false))
    private val flagSigned = InputType.TYPE_NUMBER_FLAG_SIGNED
    private val flagDecimal = InputType.TYPE_NUMBER_FLAG_DECIMAL

    @Test fun pasteInANumberFieldTakesTheFirstNumber() {
        currentInputType = InputType.TYPE_CLASS_NUMBER
        setText("")
        setClip("12abc3")
        functionalKeyPress(KeyCode.CLIPBOARD_PASTE)
        assertEquals("12", text)
        assertEquals(2, cursor)
    }

    @Test fun pasteInATextFieldKeepsEverything() { // positive control: the filter belongs to number fields only
        currentInputType = InputType.TYPE_CLASS_TEXT
        setText("")
        setClip("12abc3")
        functionalKeyPress(KeyCode.CLIPBOARD_PASTE)
        assertEquals("12abc3", text)
    }

    @Test fun pasteReplacesTheSelectionOfANumberField() {
        currentInputType = InputType.TYPE_CLASS_NUMBER
        setText("1599")
        setCursorPosition(1, 3) // "59" is selected
        setClip("23-4")
        functionalKeyPress(KeyCode.CLIPBOARD_PASTE)
        assertEquals("1239", text)
        assertEquals(3, cursor)
    }

    @Test fun pasteOfTextTheFieldDoesNotTakeChangesNothing() {
        currentInputType = InputType.TYPE_CLASS_NUMBER
        setText("42")
        setClip("no digits here")
        functionalKeyPress(KeyCode.CLIPBOARD_PASTE)
        assertEquals("42", text)
        setClip("7") // positive control: the same key pastes what the field takes
        functionalKeyPress(KeyCode.CLIPBOARD_PASTE)
        assertEquals("427", text)
    }

    /**
     * The paste rule of number fields: the FIRST number of the clip, read with its common separators (1,234.50 / 1.234,50 / 1 234,50); a signed
     * field keeps a "-" directly before it; an integer field takes the integer part only, a decimal field keeps the decimal part with the field's
     * own separator ("." unless the field names a locale); an ambiguous number pastes nothing. Phone fields keep digits, +, space, -, ( and ).
     */
    @Test fun pasteFollowsTheTypeOfTheField() {
        val cases = listOf(
            Triple(InputType.TYPE_CLASS_NUMBER or flagSigned or flagDecimal, "-1,234.50 EUR", "-1234.50"),
            Triple(InputType.TYPE_CLASS_NUMBER or flagDecimal, "-1,234.50 EUR", "1234.50"),
            Triple(InputType.TYPE_CLASS_NUMBER or flagSigned, "-1,234.50 EUR", "-1234"),
            Triple(InputType.TYPE_CLASS_NUMBER, "-1,234.50 EUR", "1234"),
            Triple(InputType.TYPE_CLASS_NUMBER or flagDecimal, "1.234,50", "1234.50"),
            Triple(InputType.TYPE_CLASS_NUMBER or flagDecimal, "1 234,50", "1234.50"),
            Triple(InputType.TYPE_CLASS_NUMBER or flagSigned, "Total: -5", "-5"),
            Triple(InputType.TYPE_CLASS_NUMBER, "12.50", "12"),
            Triple(InputType.TYPE_CLASS_NUMBER, "from 3 to 7", "3"),
            Triple(InputType.TYPE_CLASS_NUMBER, "1,234", ""), // a thousand or one point two three four: nothing
            Triple(InputType.TYPE_CLASS_PHONE, "ab+359 88 12", "+359 88 12"),
            Triple(InputType.TYPE_CLASS_PHONE, "call +1 (555) 123-4567#", "+1 (555) 123-4567"),
            Triple(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE, "17.11.2026 r", "17.11.2026"),
            Triple(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME, "10:30 PM!", "10:30 PM"),
        )
        for ((type, clip, expected) in cases) {
            reset()
            currentInputType = type
            setText("")
            setClip(clip)
            functionalKeyPress(KeyCode.CLIPBOARD_PASTE)
            assertEquals(expected, text, "type $type, clip $clip")
        }
    }

    @Test fun anImageClipIsNotPastedIntoANumberFieldButIsIntoATextField() {
        clipboard.setPrimaryClip(android.content.ClipData("image", arrayOf("image/png"), android.content.ClipData.Item(android.net.Uri.parse("content://example/1"))))
        currentInputType = InputType.TYPE_CLASS_NUMBER
        setText("5")
        functionalKeyPress(KeyCode.CLIPBOARD_PASTE)
        assertEquals("5", text)
        assertEquals(emptyList(), ShadowInputMethodService.keyDownCodes)
        reset() // positive control: a text field gets the paste key event
        functionalKeyPress(KeyCode.CLIPBOARD_PASTE)
        assertEquals(listOf(KeyEvent.KEYCODE_PASTE), ShadowInputMethodService.keyDownCodes)
    }

    @Test fun pasteInAPasswordNumberFieldLearnsAndRecordsNothing() {
        currentInputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        setText("")
        setClip("PIN: 1234")
        val history = helium314.keyboard.latin.database.ClipboardDao.getInstance(latinIME)?.count()
        val learned = ShadowFacilitator2.added.size
        functionalKeyPress(KeyCode.CLIPBOARD_PASTE)
        assertEquals("1234", text)
        assertEquals(history, helium314.keyboard.latin.database.ClipboardDao.getInstance(latinIME)?.count())
        assertEquals(learned, ShadowFacilitator2.added.size)
        assertEquals("PIN: 1234", clipboard.primaryClip?.getItemAt(0)?.text?.toString()) // the clipboard itself is not touched
    }

    @Test fun selectAllSelectsTheWholeNumberField() {
        currentInputType = InputType.TYPE_CLASS_NUMBER
        setText("12345")
        setCursorPosition(2)
        press(KeyCode.CLIPBOARD_SELECT_ALL)
        assertEquals(listOf(android.R.id.selectAll), ShadowInputMethodService.contextMenuActions)
        assertEquals("12345", selectedText)
        latinIME.onUpdateSelection(2, 2, selectionStart, selectionEnd, -1, -1) // the field reports its new selection
        handleMessages()
        assertEquals(0, connection.expectedSelectionStart)
        assertEquals(5, connection.expectedSelectionEnd)
    }

    @Test fun clearAllEmptiesTheField() {
        currentInputType = InputType.TYPE_CLASS_NUMBER
        setText("12345")
        functionalKeyPress(KeyCode.CLEAR_ALL)
        assertEquals("", text)
        assertEquals(0, cursor)
        assertEquals(0, connection.expectedSelectionStart)
        assertEquals(listOf(android.R.id.selectAll), ShadowInputMethodService.contextMenuActions)
    }

    @Test fun clearAllEmptiesAFieldWhateverTheCursorAndSelectionAre() {
        currentInputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setText("one\ntwo three")
        setCursorPosition(5) // in the middle
        functionalKeyPress(KeyCode.CLEAR_ALL)
        assertEquals("", text)
        setText("abc")
        setCursorPosition(0, 2) // a part is selected
        functionalKeyPress(KeyCode.CLEAR_ALL)
        assertEquals("", text)
        functionalKeyPress(KeyCode.CLEAR_ALL) // already empty: nothing breaks
        assertEquals("", text)
        input('x') // typing goes on from the empty field
        assertEquals("x", text)
    }

    @Test fun clearAllReadsNothingWritesNothingAndLearnsNothingInAPasswordField() {
        currentInputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setText("s3cret")
        setClip("keep me")
        val history = helium314.keyboard.latin.database.ClipboardDao.getInstance(latinIME)?.count()
        val learned = ShadowFacilitator2.added.size
        val lastLearned = lastAddedWord
        val readsBefore = ShadowInputMethodService.textReads
        latinIME.currentInputConnection.getTextBeforeCursor(1, 0)
        assertEquals(readsBefore + 1, ShadowInputMethodService.textReads) // positive control: a read is counted
        press(KeyCode.CLEAR_ALL)
        assertEquals("", text)
        assertEquals(readsBefore + 1, ShadowInputMethodService.textReads, "the clear read no text")
        assertEquals(0, ShadowInputMethodService.extractedTextCalls)
        handleMessages()
        assertEquals(readsBefore + 1, ShadowInputMethodService.textReads, "the strip update after it read no text either")
        assertEquals("keep me", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals(history, helium314.keyboard.latin.database.ClipboardDao.getInstance(latinIME)?.count())
        assertEquals(learned, ShadowFacilitator2.added.size)
        assertEquals(lastLearned, lastAddedWord)
    }

    @Test fun clearAllEndsTheWordBeingTyped() {
        setText("")
        chainInput("hello")
        assertEquals("hello", composingText) // positive control: the word is still being composed
        functionalKeyPress(KeyCode.CLEAR_ALL)
        assertEquals("", text)
        assertEquals("", composingText)
        input('x') // a new word, not a continuation of the cleared one
        assertEquals("x", text)
    }

    @Test fun clearAllDoesNotLearnTheWordItRemoves() {
        setText("")
        chainInput("hello")
        input(' ')
        assertEquals("hello", lastAddedWord) // positive control: the space learned the word
        lastAddedWord = ""
        val learned = ShadowFacilitator2.added.size
        functionalKeyPress(KeyCode.CLEAR_ALL)
        handleMessages()
        assertEquals("", text)
        assertEquals("", lastAddedWord)
        assertEquals(learned, ShadowFacilitator2.added.size)
    }

    // ------- helper functions ---------

    // should be called before every test, so the same state is guaranteed
    @BeforeTest
    fun reset() {
        // reset input connection & facilitator
        currentScript = ScriptUtils.SCRIPT_LATIN
        ShadowInputMethodService.reset()
        lastAddedWord = ""

        // reset settings
        latinIME.prefs().edit { clear() }

        setText("") // (re)sets selection and composing word
    }

    private fun chainInput(text: String) = text.forEach { input(it.code) }

    private fun input(char: Char) = input(char.code)

    private fun input(codePoint: Int) {
        require(codePoint > 0) { "not a codePoint: $codePoint" }
        val oldBefore = textBeforeCursor
        val oldAfter = textAfterCursor
        val insert = StringUtils.newSingleCodePointString(codePoint)
        val phantomSpaceToInsert = if (spaceState == SpaceState.PHANTOM) " " else ""
        val oldIsAtEnd = !composer.isCursorFrontOrMiddleOfComposingWord

        latinIME.onEvent(Event.createEventForCodePointFromUnknownSource(codePoint))
        handleMessages()

        if (!latinIME.prefs().getString(Settings.PREF_SELECTED_SUBTYPE, "")!!.contains("CombiningRules") // check fails if combiner merges symbols
            && !(codePoint == Constants.CODE_SPACE && oldBefore.lastOrNull() == ' ') // check fails when 2 spaces are converted into a period
            && !latinIME.mInputLogic.mSuggestedWords.mWillAutoCorrect // autocorrect obviously creates inconsistencies
            ) {
            if (phantomSpaceToInsert.isEmpty())
                assertEquals(oldBefore + insert, textBeforeCursor)
            else // in some cases autospace might be suppressed
                assert(oldBefore + phantomSpaceToInsert + insert == textBeforeCursor || oldBefore + insert == textBeforeCursor)
        }
        assertEquals(oldAfter, textAfterCursor)
        assertEquals(textBeforeCursor + textAfterCursor, getTextFromConnection())
        if (composer.isComposingWord) // if we're not composing any more cursor is always at the end
            assertEquals(oldIsAtEnd, !composer.isCursorFrontOrMiddleOfComposingWord)
        checkConnectionConsistency()
    }

    private fun functionalKeyPress(keyCode: Int) {
        require(keyCode < 0) { "not a functional key code: $keyCode" }
        latinIME.onEvent(Event.createSoftwareKeypressEvent(Event.NOT_A_CODE_POINT, keyCode, 0, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false))
        handleMessages()
        checkConnectionConsistency()
    }

    // almost the same as codePoint input, but calls different latinIME function
    private fun input(insert: String) {
        val oldBefore = textBeforeCursor
        val oldAfter = textAfterCursor
        val phantomSpaceToInsert = if (spaceState == SpaceState.PHANTOM) " " else ""

        latinIME.onTextInput(insert)
        handleMessages()

        if (phantomSpaceToInsert.isEmpty())
            assertEquals(oldBefore + insert, textBeforeCursor)
        else // in some cases autospace might be suppressed
            assert(oldBefore + phantomSpaceToInsert + insert == textBeforeCursor || oldBefore + insert == textBeforeCursor)
        assert(oldBefore + insert == textBeforeCursor || "$oldBefore $insert" == textBeforeCursor)
        assertEquals(oldAfter, textAfterCursor)
        assertEquals(textBeforeCursor + textAfterCursor, getTextFromConnection())
        checkConnectionConsistency()
    }

    private fun getWordAtCursor() = connection.getWordRangeAtCursor(settingsValues.mSpacingAndPunctuations, currentScript)?.mWord

    private fun setCursorPosition(start: Int, end: Int = start, weirdTextField: Boolean = false) {
        val ei = EditorInfo()
        ei.inputType = currentInputType
        ei.initialSelStart = start
        ei.initialSelEnd = end
        // imeOptions should not matter

        // adjust text in inputConnection first, otherwise fixLyingCursorPosition will move cursor
        // to the end of the text
        val fullText = textBeforeCursor + selectedText + textAfterCursor
        assertEquals(fullText, getTextFromConnection())

        // need to update ic before, otherwise when reloading text cache from ic, ric will load wrong text before cursor
        val oldStart = selectionStart
        val oldEnd = selectionEnd
        selectionStart = start
        selectionEnd = end
        assertEquals(fullText, textBeforeCursor + selectedText + textAfterCursor)

        latinIME.onUpdateSelection(oldStart, oldEnd, start, end, composingStart, composingEnd)
        handleMessages()

        if (weirdTextField) {
            latinIME.mHandler.onStartInput(ei, true) // essentially does nothing
            latinIME.mHandler.onStartInputView(ei, true) // does the thing
            handleMessages()
        }

        assertEquals(fullText, getTextFromConnection())
        assertEquals(start, selectionStart)
        assertEquals(end, selectionEnd)
        checkConnectionConsistency()
    }

    // assumes we have nothing selected
    private fun getCursorPosition(): Int {
        assertEquals(cursor, connection.expectedSelectionStart)
        assertEquals(cursor, connection.expectedSelectionEnd)
        return cursor
    }

    // just sets the text and starts input so connection it set up correctly
    private fun setText(newText: String) {
        ShadowInputMethodService.text = newText
        selectionStart = newText.length
        selectionEnd = selectionStart
        composingStart = -1
        composingStart = -1

        // we need to start input to notify that something changed
        // restarting is false, so this is seen as a new text field
        val ei = EditorInfo()
        ei.inputType = currentInputType
        latinIME.mHandler.onStartInput(ei, false)
        latinIME.mHandler.onStartInputView(ei, false)
        handleMessages() // this is important so the composing span is set correctly
        checkConnectionConsistency()
    }

    // like selecting a suggestion from strip
    private fun pickSuggestion(suggestion: String) {
        val info = SuggestedWordInfo(suggestion, "", 0, 0, null, 0, 0)
        latinIME.pickSuggestionManually(info)
        checkConnectionConsistency()
    }

    // only works when autocorrect is on, separator after word is required
    private fun getAutocorrectedWithSpaceAfter(suggestion: String, typedWord: String?) {
        val info = SuggestedWordInfo(suggestion, "", 0, 0, null, 0, 0)
        val typedInfo = SuggestedWordInfo(typedWord, "", 0, 0, null, 0, 0)
        val sw = SuggestedWords(ArrayList(listOf(typedInfo, info)), null, typedInfo, false, true, false, 0, 0)
        latinIME.mInputLogic.setSuggestedWords(sw) // this prepares for autocorrect
        input(' ')
        checkConnectionConsistency()
    }

    private fun glideTypingInput(word: String) {
        val info = SuggestedWordInfo(word, "", 0, 0, null, 0, 0)
        val sw = SuggestedWords(ArrayList(listOf(info)), null, info, true, false, false, 0, 0)
        latinIME.mInputLogic.onUpdateTailBatchInputCompleted(settingsValues, sw, KeyboardSwitcher.getInstance())
    }

    private fun checkConnectionConsistency() {
        // RichInputConnection only has composing text up to cursor, but InputConnection has full composing text
        val expectedConnectionComposingText = if (composingStart == -1 || composingEnd == -1) ""
        else text.substring(composingStart, min(composingEnd, selectionEnd))
        assert(composingText.startsWith(expectedConnectionComposingText))
        // RichInputConnection only returns text up to cursor
        val textBeforeComposingText = if (composingStart == -1) textBeforeCursor else text.substring(0, composingStart)

        println("consistency: $selectionStart, ${connection.expectedSelectionStart}, $selectionEnd, ${connection.expectedSelectionEnd}, $textBeforeComposingText, " +
                "$connectionTextBeforeComposingText, $composingText, $connectionComposingText, $textBeforeCursor, ${connection.getTextBeforeCursor(textBeforeCursor.length, 0)}" +
                ", $textAfterCursor, ${connection.getTextAfterCursor(textAfterCursor.length, 0)}")
        assertEquals(selectionStart, connection.expectedSelectionStart)
        assertEquals(selectionEnd, connection.expectedSelectionEnd)
        assertEquals(textBeforeComposingText, connectionTextBeforeComposingText)
        assertEquals(expectedConnectionComposingText, connectionComposingText)
        assertEquals(textBeforeCursor, connection.getTextBeforeCursor(textBeforeCursor.length, 0).toString())
        assertEquals(textAfterCursor, connection.getTextAfterCursor(textAfterCursor.length, 0).toString())
    }

    private fun getTextFromConnection() =
        connection.getTextBeforeCursor(100, 0).toString() + (connection.getSelectedText(0) ?: "") + connection.getTextAfterCursor(100, 0)

    private fun setInputType(inputType: Int) {
        // set text to actually apply input type
        currentInputType = inputType
        setText(text)
    }

    // ---- self-destruct: the hidden trigger word on the commit key ----------------------------------

    private val wordCtx get() = latinIME.applicationContext
    private var wipes = 0
    private val triggerWord = "Blue#Horse-4711"

    private fun armTriggerWord(configure: (helium314.keyboard.latin.privacy.SelfDestructSettings) -> Unit = {}) {
        helium314.keyboard.latin.privacy.Privacy.unlock(wordCtx)
        helium314.keyboard.latin.privacy.Privacy.forgetCachedState()
        helium314.keyboard.latin.privacy.Privacy.endWipe()
        wipes = 0
        helium314.keyboard.latin.privacy.SelfDestruct.restartHook = { wipes++ }
        helium314.keyboard.latin.privacy.SelfDestruct.executor = java.util.concurrent.Executor { it.run() }
        val settings = helium314.keyboard.latin.privacy.SelfDestruct.settings(wordCtx)
        assertEquals(helium314.keyboard.latin.privacy.SelfDestructSettings.Change.OK, settings.setTriggerWord(triggerWord.toCharArray()) { false })
        configure(settings)
    }

    private fun disarm() {
        helium314.keyboard.latin.privacy.SelfDestruct.restartHook = helium314.keyboard.latin.privacy.SelfDestruct.defaultRestart
        helium314.keyboard.latin.privacy.SelfDestruct.executor = helium314.keyboard.latin.privacy.SelfDestruct.defaultExecutor
        helium314.keyboard.latin.privacy.Privacy.endWipe()
        helium314.keyboard.latin.privacy.Privacy.forgetCachedState()
    }

    private fun typeAndCommit(text: String, key: Int = Constants.CODE_ENTER) {
        chainInput(text)
        latinIME.onEvent(Event.createEventForCodePointFromUnknownSource(key))
        handleMessages()
    }

    @Test fun theTriggerWordOnEnterWipesRemovesTheWordFromTheFieldAndSendsNoEditorAction() {
        armTriggerWord()
        try {
            typeAndCommit(triggerWord)
            assertEquals(1, wipes)
            assertEquals("", text) // the word never reaches the app
            assertEquals(emptyList(), ShadowInputMethodService.editorActions) // and the action is not sent
        } finally { disarm() }
    }

    @Test fun aWordThatIsNotTheTriggerLetsEnterThroughAndWipesNothing() {
        armTriggerWord()
        try {
            typeAndCommit("Blue#Horse-4712")
            assertEquals(0, wipes)
            assertEquals(listOf(0), ShadowInputMethodService.editorActions) // the action went ahead
            assertEquals("Blue#Horse-4712", text)
        } finally { disarm() }
    }

    @Test fun theWordAfterOtherTextOnTheLineStillMatchesAndTheOtherTextStays() {
        armTriggerWord()
        try {
            typeAndCommit("hello $triggerWord")
            assertEquals(1, wipes)
            assertEquals("hello ", text)
        } finally { disarm() }
    }

    @Test fun theSpaceKeyChecksOnlyWithTheOptionOn() {
        armTriggerWord()
        try {
            typeAndCommit(triggerWord, Constants.CODE_SPACE)
            assertEquals(0, wipes)
        } finally { disarm() }
        reset()
        armTriggerWord { it.setCommitOnSpace(true) }
        try {
            typeAndCommit(triggerWord, Constants.CODE_SPACE)
            assertEquals(1, wipes)
            assertEquals("", text)
        } finally { disarm() }
    }

    @Test fun passwordFieldsAndTheKeyboardsOwnFieldsAreNotChecked() {
        armTriggerWord()
        try {
            currentInputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText("")
            typeAndCommit(triggerWord)
            assertEquals(0, wipes)
            reset()
            ShadowInputMethodService.currentPackage = wordCtx.packageName
            setText("")
            typeAndCommit(triggerWord)
            assertEquals(0, wipes)
        } finally { disarm() }
        reset()
        armTriggerWord { it.setCheckInPasswordFields(true) }
        try {
            currentInputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText("")
            typeAndCommit(triggerWord)
            assertEquals(1, wipes) // positive control: the option makes the same input fire
        } finally { disarm() }
    }

    @Test fun withHoldEnterOffTheActionGoesAheadAndTheWipeStillFollows() {
        armTriggerWord { it.setHoldEnter(false) }
        try {
            typeAndCommit(triggerWord)
            assertEquals(1, wipes)
            assertEquals(listOf(0), ShadowInputMethodService.editorActions)
        } finally { disarm() }
    }

    @Test fun inRehearsalTheWordIsRemovedAndNothingIsWiped() {
        armTriggerWord { it.startRehearsal() }
        try {
            typeAndCommit(triggerWord)
            assertEquals(0, wipes)
            assertEquals("", text)
            assertEquals(emptyList(), ShadowInputMethodService.editorActions)
        } finally { disarm() }
    }

    @Test fun aDueCheckNeverLetsTheTokenIntoTheLearnedWordsWhateverTheCheckFinds() {
        typeAndCommit("hello", Constants.CODE_SPACE) // positive control: an ordinary word is learned on the same key (no word armed yet)
        assertEquals("hello", lastAddedWord)
        reset()
        val cases = listOf<Triple<String, String, (helium314.keyboard.latin.privacy.SelfDestructSettings) -> Unit>>(
            Triple("rehearsal, hold off", triggerWord) { it.setHoldEnter(false); it.startRehearsal() },
            Triple("armed, hold off", triggerWord) { it.setHoldEnter(false) },
            Triple("rehearsal, hold on", triggerWord) { it.startRehearsal() },
            Triple("armed, hold on", triggerWord) { },
            Triple("another token, hold off", "Blue#Horse-4712") { it.setHoldEnter(false) },
            Triple("another token, hold on", "Blue#Horse-4712") { },
        )
        for ((name, typed, configure) in cases) {
            armTriggerWord { it.setCommitOnSpace(true); configure(it) }
            try {
                lastAddedWord = ""
                typeAndCommit(typed, Constants.CODE_SPACE)
                assertEquals("", lastAddedWord, name) // nothing is added for this commit, not even a part of the token
                assertEquals(false, composer.isComposingWord, name)
                assertTrue(text.contains(typed) || text.isEmpty(), name) // the text is the app's: it stays unless the word matched and is removed
            } finally { disarm() }
            reset()
        }
        armTriggerWord { it.setCommitOnSpace(true) }
        try {
            typeAndCommit("hello", Constants.CODE_SPACE) // no check is due for a short word: learned as ever
            assertEquals("hello", lastAddedWord)
        } finally { disarm() }
    }

    /** Leaves [word] in the field (the commit key found no match), moves the cursor into it and commits it with punctuation; returns what was learned. */
    private fun learnedWhenCommittedLater(word: String): String {
        typeAndCommit(word, Constants.CODE_SPACE)
        assertEquals("$word ", text)
        lastAddedWord = ""
        setCursorPosition(word.length) // back to the end of the word: it is the word at the cursor again
        input('.') // punctuation commits it, this time not through the commit key
        handleMessages()
        return lastAddedWord
    }

    @Test fun aTokenLeftInTheFieldIsNotLearnedWhenCommittedLaterByCursorMovesAndPunctuation() {
        armTriggerWord { it.setCommitOnSpace(true) }
        try {
            assertEquals("", learnedWhenCommittedLater("Horse-battery-staple"))
        } finally { disarm() }
        reset()
        // positive controls: the same route learns an ordinary word, and the same token once no trigger word is set
        assertEquals("hello", learnedWhenCommittedLater("hello"))
        reset()
        helium314.keyboard.latin.privacy.SelfDestruct.settings(wordCtx).removeTriggerWord()
        assertEquals("Horse-battery-staple", learnedWhenCommittedLater("Horse-battery-staple"))
    }

    @Test fun aLongOrdinaryWordKeepsLearningWhileATriggerWordIsSet() {
        armTriggerWord { it.setCommitOnSpace(true) }
        try {
            typeAndCommit("internationalization", Constants.CODE_SPACE) // 20 letters: never the word, so no check and learned as ever
            assertEquals("internationalization", lastAddedWord)
            assertEquals(0, wipes)
        } finally { disarm() }
    }

    // ---- voice typing: dictated text is committed text, not composing, never learned, never autocorrected ----

    /** The dictated text goes in through the production path of the voice key (VoiceTyping.commit -> LatinIME.commitVoiceText). */
    private fun dictate(spoken: String) {
        latinIME.mVoiceTyping.commit(spoken)
        handleMessages()
        checkConnectionConsistency()
    }

    @Test fun dictatedTextIsNotLearnedOrAutocorrectedWhenASeparatorFollows() {
        // positive control: the same word typed by hand is composed, autocorrected at the space and learned
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        chainInput("wrold")
        assertEquals("wrold", composingText)
        getAutocorrectedWithSpaceAfter("world", "wrold")
        assertEquals("world ", text)
        assertEquals("world", lastAddedWord)
        reset()
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        // the dictated word is plain text: nothing composing, nothing prepared to be corrected or learned
        dictate("wrold")
        assertEquals("wrold", text)
        assertEquals("", composingText)
        assertEquals("", lastAddedWord)
        getAutocorrectedWithSpaceAfter("world", "wrold") // the strip offers the same correction
        assertEquals("wrold ", text)
        assertEquals("", lastAddedWord)
        input('.')
        assertEquals("wrold .", text) // an ordinary separator is typed as ever (swap of the weak space is not applicable)
        assertEquals("", lastAddedWord)
    }

    @Test fun dictatedTextAfterTypedTextKeepsTheTypedWordsLearningAndNothingElse() {
        chainInput("hello")
        dictate("wrold") // the typed word is committed (learned) by the insertion, the dictated one is not
        assertEquals("hello wrold", text)
        assertEquals("hello", lastAddedWord)
        assertEquals("", composingText)
        input(' ')
        assertEquals("hello", lastAddedWord)
    }

    @Test fun twoDictationsInARowStayOutOfLearning() {
        dictate("one")
        dictate("two")
        assertEquals("one two", text)
        assertEquals("", composingText)
        input(' ')
        assertEquals("", lastAddedWord)
    }

    // ---- voice typing: the user's words and the capitals (VoiceWords) ----

    /** The real facilitator over fake dictionaries (main: hello, world, word; learned: zork; personal: quibble) in place of the service's own. */
    private fun useWordKit() {
        val kit = helium314.keyboard.latin.gesture.GlideFacilitatorKit(latinIME)
        LatinIME::class.java.getDeclaredField("mDictionaryFacilitator").apply { isAccessible = true }.set(latinIME, kit.facilitator)
    }

    @Test fun `the user's words fix dictated text only where personalised suggestions are allowed`() {
        val privacy = helium314.keyboard.latin.privacy.Privacy
        val chat = "com.example.chat"
        setInputType(InputType.TYPE_CLASS_TEXT)
        useWordKit() // after the field started: loading the settings of a new field resets the facilitator's dictionaries
        dictate("zorc quible") // learned "zork" one letter changed, personal "quibble" one letter missing
        assertEquals("zork quibble", text) // positive control: a normal field
        val states = listOf<Pair<String, () -> Unit>>(
            "password field" to { setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) },
            "no-learning field" to { ShadowInputMethodService.currentImeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING; setInputType(InputType.TYPE_CLASS_TEXT) },
            "incognito mode" to { latinIME.prefs().edit { putBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, true) }; setInputType(InputType.TYPE_CLASS_TEXT) },
            "app on the incognito list" to { Settings.setIncognitoApps(latinIME, setOf(chat)); ShadowInputMethodService.currentPackage = chat; setInputType(InputType.TYPE_CLASS_TEXT) },
            "locked after the field started" to { setInputType(InputType.TYPE_CLASS_TEXT); privacy.lock(latinIME) },
        )
        try {
            for ((name, enter) in states) {
                reset()
                privacy.unlock(latinIME)
                enter()
                useWordKit()
                dictate("zorc quible")
                assertEquals("zorc quible", text, name)
            }
            // positive control: the same app off the list, unlocked
            reset()
            privacy.unlock(latinIME)
            Settings.setIncognitoApps(latinIME, emptySet())
            ShadowInputMethodService.currentPackage = chat
            setInputType(InputType.TYPE_CLASS_TEXT)
            useWordKit()
            dictate("zorc quible")
            assertEquals("zork quibble", text)
        } finally {
            privacy.unlock(latinIME)
            Settings.setIncognitoApps(latinIME, emptySet())
        }
    }

    @Test fun `dictated text keeps dictionary words and is not learned after the user's words were applied`() {
        setInputType(InputType.TYPE_CLASS_TEXT)
        useWordKit()
        dictate("hello wrd zorc") // "wrd" is one letter from the dictionary word "word" but no user word: unchanged
        assertEquals("hello wrd zork", text)
        input(' ')
        assertEquals("", lastAddedWord) // nothing of it is learned
        assertEquals("", composingText)
    }

    @Test fun `dictated text gets a capital at a sentence start where the field asks for capitals`() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        dictate("hello there. how are you")
        assertEquals("Hello there. How are you", text)
        dictate("fine") // not at a sentence start
        assertEquals("Hello there. How are you fine", text)
        setText("Done. ")
        dictate("next")
        assertEquals("Done. Next", text)
        // a field without automatic capitals, and the setting off: the engine's text as it is
        reset()
        setInputType(InputType.TYPE_CLASS_TEXT)
        dictate("hello there. how are you")
        assertEquals("hello there. how are you", text)
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTO_CAP, false) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        dictate("hello there. how are you")
        assertEquals("hello there. how are you", text)
    }

    // always need to handle messages for proper simulation
    private fun handleMessages() {
        while (messages.isNotEmpty()) {
            latinIME.mHandler.handleMessage(messages.first())
            messages.removeAt(0)
        }
        while (delayedMessages.isNotEmpty()) {
            val msg = delayedMessages.first()
            if (msg.what != 2) // MSG_UPDATE_SUGGESTION_STRIP, we want to ignore it because it's irrelevant and has a 500 ms timeout
                latinIME.mHandler.handleMessage(delayedMessages.first())
            delayedMessages.removeAt(0)
            // delayed messages may post further messages, handle before next delayed message
            while (messages.isNotEmpty()) {
                latinIME.mHandler.handleMessage(messages.first())
                messages.removeAt(0)
            }
        }
        assertEquals(0, messages.size)
        assertEquals(0, delayedMessages.size)
    }

}

private var currentScript = ScriptUtils.SCRIPT_LATIN
private val messages = mutableListOf<Message>() // for latinIME / ShadowInputMethodService
private val delayedMessages = mutableListOf<Message>() // for latinIME / ShadowInputMethodService

// Shadows are handled by Robolectric. @Implementation overrides built-in functionality.
// This is used for avoiding crashes (LocaleManagerCompat, InputMethodManager, KeyboardSwitcher)
// and for simulating system stuff (InputMethodService for controlling the InputConnection, which
// more or less is the contents of the text field), and for setting the current script in
// KeyboardSwitcher without having to care about InputMethodSubtypes

@Implements(Handler::class)
class ShadowHandler {
    @Implementation
    fun sendMessage(message: Message) {
        messages.add(message)
    }
    @Implementation
    fun sendMessageDelayed(message: Message, delay: Long) {
        delayedMessages.add(message)
    }
}

@Implements(KeyboardSwitcher::class)
class ShadowKeyboardSwitcher {
    @Implementation
    // basically only needed for null check
    fun getMainKeyboardView(): MainKeyboardView = Mockito.mock(MainKeyboardView::class.java)
    @Implementation
    // only affects view
    fun setKeyboard(keyboardId: Int, toggleState: KeyboardSwitcher.KeyboardSwitchState) = Unit
    @Implementation
    // only affects view
    fun setOneHandedModeEnabled(enabled: Boolean, force: Boolean) = Unit
    @Implementation
    fun getCurrentKeyboardScript() = currentScript
}

@Implements(DictionaryFacilitatorImpl::class)
class ShadowFacilitator2 {
    @Implementation
    fun addToUserHistory(suggestion: String, wasAutoCapitalized: Boolean,
                         ngramContext: NgramContext, timeStampInSeconds: Long,
                         blockPotentiallyOffensive: Boolean, mayPromoteToPersonalDictionary: Boolean) {
        lastAddedWord = suggestion
        added.add(suggestion)
        mayPromote.add(mayPromoteToPersonalDictionary)
    }
    @Implementation
    fun unlearnFromUserHistory(word: String, ngramContext: NgramContext, timeStampInSeconds: Long, event: DictionaryFacilitator.UnlearnEvent) {
        if (event == DictionaryFacilitator.UnlearnEvent.REVERT) unlearned.add(word)
    }
    companion object {
        var lastAddedWord = ""
        val unlearned = ArrayList<String>() // words unlearned because a correction was taken back
        val added = ArrayList<String>() // every word added to the user history, in order
        val mayPromote = ArrayList<Boolean>() // for each entry of [added]: may the facilitator add the word to the personal dictionary
    }
}
