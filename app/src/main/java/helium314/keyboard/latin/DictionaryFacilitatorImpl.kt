/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.latin

import android.content.Context
import android.util.LruCache
import androidx.annotation.VisibleForTesting
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.latin.DictionaryFacilitator.DictionaryInitializationListener
import helium314.keyboard.latin.NgramContext.WordInfo
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.StringUtils
import helium314.keyboard.latin.common.decapitalize
import helium314.keyboard.latin.common.mightBeEmoji
import helium314.keyboard.latin.common.splitOnWhitespace
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.DictionaryFactory
import helium314.keyboard.latin.dictionary.DictionaryStats
import helium314.keyboard.latin.dictionary.ExpandableBinaryDictionary
import helium314.keyboard.latin.dictionary.UserBinaryDictionary
import helium314.keyboard.latin.gesture.GlideSuggester
import helium314.keyboard.latin.privacy.PersonalDictionary
import helium314.keyboard.latin.privacy.PersonalWord
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.privacy.RemovedWords
import helium314.keyboard.latin.personalization.UserHistoryDictionary
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SuggestionResults
import helium314.keyboard.latin.utils.getSecondaryLocales
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Facilitates interaction with different kinds of dictionaries. Provides APIs
 * to instantiate and select the correct dictionaries (based on language and settings),
 * update entries and fetch suggestions.
 *
 *
 * Currently AndroidSpellCheckerService and LatinIME both use DictionaryFacilitator as
 * a client for interacting with dictionaries.
 */
class DictionaryFacilitatorImpl @JvmOverloads constructor(
    /** The glide engine; a test passes one that reads its word lists on the calling thread. */
    private val glide: GlideSuggester = GlideSuggester(),
    /** The spell checker's facilitator: main (and emoji) dictionaries only, it never loads or asks a learned or personal word list. */
    private val forSpellChecking: Boolean = false,
) : DictionaryFacilitator {
    private var dictionaryGroups = listOf(DictionaryGroup())

    /** Test seam: one dictionary group made from the given dictionaries (the real ones need the native library). */
    @VisibleForTesting
    internal fun installDictionariesForTest(locale: Locale, mainDict: Dictionary, subDicts: Map<String, ExpandableBinaryDictionary>) =
        installGroupsForTest(listOf(Triple(locale, mainDict, subDicts)))

    /** Test seam: several languages (locale, main dictionary, user dictionaries), the first one is the main locale. */
    @VisibleForTesting
    internal fun installGroupsForTest(groups: List<Triple<Locale, Dictionary, Map<String, ExpandableBinaryDictionary>>>) {
        dictionaryGroups = groups.map { (locale, mainDict, subDicts) -> DictionaryGroup(locale, mainDict, subDicts) }
    }

    /** D15: main dictionaries of the other enabled languages, loaded in the background so the first language switch is instant. */
    private val warmDictionaries = WarmDictionaries()

    @Volatile
    private var mLatchForWaitingLoadingMainDictionaries = CountDownLatch(0)

    // The library does not deal well with ngram history for auto-capitalized words, so we adjust
    // the ngram context to store next word suggestions for such cases.
    private var tryChangingWords = false
    private var changeFrom = ""
    private var changeTo = ""

    private var mValidSpellingWordReadCache: LruCache<String, Boolean>? = null
    private var mValidSpellingWordWriteCache: LruCache<String, Boolean>? = null

    private val scope = CoroutineScope(Dispatchers.Default)

    override fun setValidSpellingWordReadCache(cache: LruCache<String, Boolean>) {
        mValidSpellingWordReadCache = cache
    }

    override fun setValidSpellingWordWriteCache(cache: LruCache<String, Boolean>) {
        mValidSpellingWordWriteCache = cache
    }

    // judging by usage before adding multilingual typing, this should check primary group locale only
    override fun isForLocale(locale: Locale?): Boolean {
        return locale != null && locale == dictionaryGroups[0].locale
    }

    override fun onStartInput() {
    }

    override fun onFinishInput() {
        for (dictGroup in dictionaryGroups) {
            DictionaryFacilitator.ALL_DICTIONARY_TYPES.forEach { dictGroup.getDict(it)?.onFinishInput() }
        }
    }

    override fun isActive(): Boolean {
        return dictionaryGroups[0].locale.language.isNotEmpty()
    }

    override fun getMainLocale(): Locale {
        return dictionaryGroups[0].locale
    }

    override fun getCurrentLocale(): Locale {
        return currentlyPreferredDictionaryGroup.locale
    }

    override fun usesSameSettings(locales: List<Locale>, personalization: Boolean): Boolean {
        val dictGroup = dictionaryGroups[0] // settings are the same for all groups
        return personalization == dictGroup.hasDict(Dictionary.TYPE_USER_HISTORY)
                && locales.size == dictionaryGroups.size
                && locales.none { findDictionaryGroupWithLocale(dictionaryGroups, it) == null }
    }

    // -------------- managing (loading & closing) dictionaries ------------

    override fun resetDictionaries(
        context: Context,
        newLocale: Locale,
        usePersonalizedDicts: Boolean,
        forceReloadMainDictionary: Boolean,
        dictNamePrefix: String,
        listener: DictionaryInitializationListener?
    ) {
        Log.i(TAG, "resetDictionaries, force reloading main dictionary")
        if (forceReloadMainDictionary) warmDictionaries.clear() // new or removed dictionary files, or the emoji setting changed

        val locales = getUsedLocales(newLocale, context)

        // the spell checker (own facilitator, no field) never loads the personal dictionary or the learned words
        val subDictTypesToUse = if (forSpellChecking) emptyList() else listOfNotNull(
            Dictionary.TYPE_USER,
            if (usePersonalizedDicts) Dictionary.TYPE_USER_HISTORY else null
        )

        val (newDictionaryGroups, existingDictsToCleanup) =
            getNewDictGroupsAndDictsToCleanup(locales, subDictTypesToUse, forceReloadMainDictionary, dictNamePrefix, context)

        // Replace Dictionaries.
        val oldDictionaryGroups: List<DictionaryGroup>
        synchronized(this) {
            oldDictionaryGroups = dictionaryGroups
            dictionaryGroups = newDictionaryGroups
            if (hasAtLeastOneUninitializedMainDictionary()) {
                asyncReloadUninitializedMainDictionaries(context, locales, listener)
            }
        }

        listener?.onUpdateMainDictionaryAvailability(hasAtLeastOneInitializedMainDictionary())

        // Clean up old dictionaries.
        existingDictsToCleanup.forEach { (locale, dictTypes) ->
            val dictGroupToCleanup = findDictionaryGroupWithLocale(oldDictionaryGroups, locale) ?: return@forEach
            for (dictType in dictTypes) {
                dictGroupToCleanup.closeDict(dictType)
            }
        }

        mValidSpellingWordWriteCache?.evictAll()
        mValidSpellingWordReadCache?.evictAll()
    }

    /** creates dictionaryGroups for [newLocales] with given [newSubDictTypes], trying to re-use existing dictionaries.
     * returns the new dictionaryGroups and unused dictionary types by locale */
    private fun getNewDictGroupsAndDictsToCleanup(
        newLocales: Collection<Locale>,
        newSubDictTypes: Collection<String>,
        forceReload: Boolean,
        dictNamePrefix: String,
        context: Context
    ): Pair<List<DictionaryGroup>, Map<Locale, List<String>>> {
        // Gather all dictionaries by locale. We may remove some from the list later.
        val existingDictsToCleanup = HashMap<Locale, MutableList<String>>()
        for (dictGroup in dictionaryGroups) {
            existingDictsToCleanup[dictGroup.locale] = DictionaryFacilitator.ALL_DICTIONARY_TYPES
                .filterTo(mutableListOf()) { dictGroup.hasDict(it) }
        }

        // create new dictionary groups and remove dictionaries to re-use from existingDictsToCleanup
        val newDictionaryGroups = mutableListOf<DictionaryGroup>()
        for (locale in newLocales) {
            // get existing dictionary group for new locale
            val oldDictGroupForLocale = findDictionaryGroupWithLocale(dictionaryGroups, locale)
            val dictTypesToCleanupForLocale = existingDictsToCleanup[locale]

            // create new or re-use already loaded main dict
            val mainDict: Dictionary?
            if (forceReload || oldDictGroupForLocale == null
                || !oldDictGroupForLocale.hasDict(Dictionary.TYPE_MAIN)
            ) {
                mainDict = null // null main dicts will be loaded later in asyncReloadUninitializedMainDictionaries
            } else {
                mainDict = oldDictGroupForLocale.getDict(Dictionary.TYPE_MAIN)
                dictTypesToCleanupForLocale?.remove(Dictionary.TYPE_MAIN)
            }

            // create new or re-use already loaded sub-dicts
            val subDicts: MutableMap<String, ExpandableBinaryDictionary> = HashMap()
            for (subDictType in newSubDictTypes) {
                val subDict: ExpandableBinaryDictionary
                if (forceReload || oldDictGroupForLocale == null
                    || !oldDictGroupForLocale.hasDict(subDictType)
                ) {
                    // Create a new dictionary.
                    subDict = createSubDict(subDictType, context, locale, null, dictNamePrefix) ?: continue
                } else {
                    // Reuse the existing dictionary.
                    subDict = oldDictGroupForLocale.getSubDict(subDictType) ?: continue
                    dictTypesToCleanupForLocale?.remove(subDictType)
                }
                subDicts[subDictType] = subDict
            }
            val newDictGroup = DictionaryGroup(locale, mainDict, subDicts, context)
            newDictionaryGroups.add(newDictGroup)
        }
        return newDictionaryGroups to existingDictsToCleanup
    }

    private fun asyncReloadUninitializedMainDictionaries(
        context: Context, locales: Collection<Locale>, listener: DictionaryInitializationListener?
    ) {
        val latchForWaitingLoadingMainDictionary = CountDownLatch(1)
        mLatchForWaitingLoadingMainDictionaries = latchForWaitingLoadingMainDictionary
        scope.launch {
            try {
                val useEmojiDict = Settings.getValues().mSuggestEmojis
                val dictGroupsWithNewMainDict = locales.mapNotNull {
                    val dictionaryGroup = findDictionaryGroupWithLocale(dictionaryGroups, it)
                    if (dictionaryGroup == null) {
                        Log.w(TAG, "Expected a dictionary group for but none found")
                        return@mapNotNull null // This should never happen
                    }
                    if (dictionaryGroup.getDict(Dictionary.TYPE_MAIN)?.isInitialized == true) null
                    else dictionaryGroup to (warmDictionaries.take(it) ?: DictionaryFactory.createMainDictionaryCollection(context, it, useEmojiDict))
                }
                synchronized(this) {
                    dictGroupsWithNewMainDict.forEach { (dictGroup, mainDict) ->
                        dictGroup.setMainDict(mainDict)
                        // read the word list before the first glide; the preference is read directly, the settings values may not be loaded yet when a dictionary arrives
                        if (JniUtils.sHaveGestureLib && context.prefs().getBoolean(Settings.PREF_GESTURE_INPUT, Defaults.PREF_GESTURE_INPUT)) glide.warm(mainDict)
                    }
                }

                listener?.onUpdateMainDictionaryAvailability(hasAtLeastOneInitializedMainDictionary())
                latchForWaitingLoadingMainDictionary.countDown()
                if (!forSpellChecking) warmOtherLanguages(context, locales, useEmojiDict) // after the keyboard is usable: D15
            } catch (e: Throwable) {
                Log.e(TAG, "could not initialize main dictionaries for")
            }
        }
    }

    /** D15: loads the main dictionaries (and the glide word lists) of the other enabled languages, so that the first switch to one of them is instant. */
    private fun warmOtherLanguages(context: Context, inUse: Collection<Locale>, useEmojiDict: Boolean) {
        val enabled = SubtypeSettings.getEnabledSubtypes(true).map { it.locale() }
        val glideOn = JniUtils.sHaveGestureLib && context.prefs().getBoolean(Settings.PREF_GESTURE_INPUT, Defaults.PREF_GESTURE_INPUT)
        warmDictionaries.warm(enabled, inUse, { DictionaryFactory.createMainDictionaryCollection(context, it, useEmojiDict) }) { if (glideOn) glide.warm(it) }
    }

    override fun closeDictionaries() {
        warmDictionaries.clear()
        onFinishInput() // the dictionaries will save updates to file
        val dictionaryGroupsToClose: List<DictionaryGroup>
        synchronized(this) {
            dictionaryGroupsToClose = dictionaryGroups
            dictionaryGroups = listOf(DictionaryGroup())
        }
        for (dictionaryGroup in dictionaryGroupsToClose) {
            for (dictType in DictionaryFacilitator.ALL_DICTIONARY_TYPES) {
                dictionaryGroup.closeDict(dictType)
            }
        }
    }

    // The main dictionaries are loaded asynchronously. Don't cache the return value of these methods.
    override fun hasAtLeastOneInitializedMainDictionary(): Boolean =
        dictionaryGroups.any { it.getDict(Dictionary.TYPE_MAIN)?.isInitialized == true }

    override fun hasAtLeastOneUninitializedMainDictionary(): Boolean =
        dictionaryGroups.any { it.getDict(Dictionary.TYPE_MAIN)?.isInitialized != true }

    @Throws(InterruptedException::class)
    override fun waitForLoadingMainDictionaries(timeout: Long, unit: TimeUnit) {
        mLatchForWaitingLoadingMainDictionaries.await(timeout, unit)
    }

    // -------------- actual dictionary stuff like getting suggestions ------------

    override fun addToUserHistory(
        suggestion: String, wasAutoCapitalized: Boolean, ngramContext: NgramContext,
        timeStampInSeconds: Long, blockPotentiallyOffensive: Boolean, mayPromoteToPersonalDictionary: Boolean
    ) {
        // Update the spelling cache before learning. Words that are not yet added to user history
        // and appear in no other language model are not considered valid.
        putWordIntoValidSpellingWordCache("addToUserHistory", suggestion)

        val words = suggestion.splitOnWhitespace().dropLastWhile { it.isEmpty() }

        // increase / decrease confidence
        if (words.size == 1) // ignore if more than a single word, which only happens with (badly working) spaceAwareGesture
            adjustConfidences(suggestion, wasAutoCapitalized)

        // Add word to user dictionary if it is in no other dictionary except user history dictionary (i.e. typed again).
        val sv = Settings.getValues()
        if (sv.mAddToPersonalDictionary // require the opt-in
            && mayPromoteToPersonalDictionary // not for a word learned from an undone autocorrection
            && sv.mAutoCorrectEnabled == sv.mAutoCorrectionEnabledPerUserSettings // don't add if user wants autocorrect but input field does not, see https://github.com/HeliBorg/HeliBoard/issues/427#issuecomment-1905438000
            && dictionaryGroups[0].hasDict(Dictionary.TYPE_USER_HISTORY) // require personalized suggestions
            && !wasAutoCapitalized // we can't be 100% sure about what the user intended to type, so better don't add it
            && words.size == 1 // only single words
        ) {
            addToPersonalDictionaryIfInvalidButInHistory(suggestion)
        }

        var ngramContextForCurrentWord = ngramContext
        val preferredGroup = currentlyPreferredDictionaryGroup
        for (i in words.indices) {
            val currentWord = words[i]
            val wasCurrentWordAutoCapitalized = (i == 0) && wasAutoCapitalized
            // add to history for preferred dictionary group, to avoid mixing languages in history
            addWordToUserHistory(
                preferredGroup, ngramContextForCurrentWord, currentWord,
                wasCurrentWordAutoCapitalized, timeStampInSeconds.toInt(), blockPotentiallyOffensive
            )
            ngramContextForCurrentWord = ngramContextForCurrentWord.getNextNgramContext(WordInfo(currentWord))

            // remove manually entered blacklisted words from blacklist for likely matching languages
            dictionaryGroups.filter { it.confidence == preferredGroup.confidence }.forEach {
                it.removeFromBlacklist(currentWord)
            }
        }
    }

    private fun addWordToUserHistory(
        dictionaryGroup: DictionaryGroup, ngramContext: NgramContext, word: String, wasAutoCapitalized: Boolean,
        timeStampInSeconds: Int, blockPotentiallyOffensive: Boolean
    ) {
        val userHistoryDictionary = dictionaryGroup.getSubDict(Dictionary.TYPE_USER_HISTORY) ?: return

        val mainFreq = dictionaryGroup.getDict(Dictionary.TYPE_MAIN)?.getFrequency(word) ?: Dictionary.NOT_A_PROBABILITY
        if (mainFreq == 0 && blockPotentiallyOffensive)
            return
        if (tryChangingWords)
            tryChangingWords = ngramContext.changeWordIfAfterBeginningOfSentence(changeFrom, changeTo)

        val wordToUse: String
        // Check for isBeginningOfSentenceContext too, because not all text fields auto-capitalize in this case.
        // Even if the user capitalizes manually, they most likely don't want the capitalized form suggested.
        if (wasAutoCapitalized || ngramContext.isBeginningOfSentenceContext) {
            val decapitalizedWord = word.decapitalize(dictionaryGroup.locale) // try undoing auto-capitalization
            if (isValidWord(word, DictionaryFacilitator.ALL_DICTIONARY_TYPES, dictionaryGroup)
                && !isValidWord(decapitalizedWord, DictionaryFacilitator.ALL_DICTIONARY_TYPES, dictionaryGroup)
            ) {
                // If the word was auto-capitalized and exists only as a capitalized word in the
                // dictionary, then we must not downcase it before registering it. For example,
                // the name of the contacts in start-of-sentence position would come here with the
                // wasAutoCapitalized flag: if we downcase it, we'd register a lower-case version
                // of that contact's name which would end up popping in suggestions.
                wordToUse = word
            } else {
                // If however the word is not in the dictionary, or exists as a de-capitalized word
                // only, then we consider that was a lower-case word that had been auto-capitalized.
                wordToUse = decapitalizedWord
                tryChangingWords = true
                changeFrom = word
                changeTo = wordToUse
            }
        } else {
            // We'd like to avoid adding the capitalized form of common words to the User
            // History dictionary in order to avoid suggesting them until the dictionary
            // consolidation is done.
            val lowerCasedWord = word.lowercase(dictionaryGroup.locale)
            val lowerCaseFreqInMainDict = dictionaryGroup.getDict(Dictionary.TYPE_MAIN)?.getFrequency(lowerCasedWord)
                ?: Dictionary.NOT_A_PROBABILITY
            wordToUse = if (mainFreq < lowerCaseFreqInMainDict
                && lowerCaseFreqInMainDict >= CAPITALIZED_FORM_MAX_PROBABILITY_FOR_INSERT
            ) {
                // Use lower cased word as the word can be a distracter of the popular word.
                lowerCasedWord
            } else {
                word
            }
        }
        // We demote unrecognized words (frequency <= 0) by specifying them as "invalid".
        // We don't add words with 0-frequency (assuming they would be profanity etc.).
        val isValid = mainFreq > 0
        UserHistoryDictionary.addToDictionary(userHistoryDictionary, ngramContext, wordToUse, isValid, timeStampInSeconds)
    }

    private fun addToPersonalDictionaryIfInvalidButInHistory(word: String) {
        if (word.length <= 1) return
        val dictionaryGroup = clearlyPreferredDictionaryGroup ?: return
        val userDict = dictionaryGroup.getSubDict(Dictionary.TYPE_USER) ?: return
        val userHistoryDict = dictionaryGroup.getSubDict(Dictionary.TYPE_USER_HISTORY) ?: return
        if (isValidWord(word, DictionaryFacilitator.ALL_DICTIONARY_TYPES, dictionaryGroup))
            return // valid word, no reason to auto-add it to personal dict
        if (userDict.isInDictionary(word))
            return // should never happen, but better be safe

        // User history always reports words as invalid, so we check the frequency instead.
        // Testing shows that after 2 times adding, the frequency is 111, and then rises slowly with usage (values vary slightly).
        // 120 is after 3 uses of the word, so we simply require more than that.
        // Words added to dictionaries (user and history) seem to be found only after some delay.
        // This is not too bad, but it delays adding in case a user wants to fill a dictionary using this functionality
        if (userHistoryDict.getFrequency(word) > 120) {
            scope.launch {
                // the personal dictionary is encrypted (snippets.enc); null while the device is locked
                runCatching {
                    Privacy.personalDictionary(userDict.mContext)
                        ?.add(PersonalWord(word, null, PersonalDictionary.DEFAULT_FREQUENCY, dictionaryGroup.locale.toString()))
                }
            }
        }
    }

    private fun putWordIntoValidSpellingWordCache(caller: String, originalWord: String) {
        if (mValidSpellingWordWriteCache == null)
            return

        val lowerCaseWord = originalWord.lowercase(currentLocale)
        val lowerCaseValid = isValidSpellingWord(lowerCaseWord)
        mValidSpellingWordWriteCache?.put(lowerCaseWord, lowerCaseValid)

        val capitalWord = StringUtils.capitalizeFirstAndDowncaseRest(originalWord, currentLocale)
        val capitalValid = if (lowerCaseValid) {
            true // The lower case form of the word is valid, so the upper case must be valid.
        } else {
            isValidSpellingWord(capitalWord)
        }
        mValidSpellingWordWriteCache?.put(capitalWord, capitalValid)
    }

    override fun adjustConfidences(word: String, wasAutoCapitalized: Boolean) {
        if (dictionaryGroups.size == 1 || word.contains(Constants.WORD_SEPARATOR))
            return

        // if suggestion was auto-capitalized, check against both the suggestion and the de-capitalized suggestion
        val decapitalizedSuggestion = if (wasAutoCapitalized) word.decapitalize(currentLocale) else word
        val validWord = dictionaryGroups.map { isValidWord(word, DictionaryFacilitator.ALL_DICTIONARY_TYPES, it) }
        dictionaryGroups.forEach {
            if (isValidWord(word, DictionaryFacilitator.ALL_DICTIONARY_TYPES, it)) {
                it.increaseConfidence()
                return@forEach
            }
            // also increase confidence if suggestion was auto-capitalized and the lowercase variant it valid
            if (wasAutoCapitalized && isValidWord(decapitalizedSuggestion, DictionaryFacilitator.ALL_DICTIONARY_TYPES, it)) {
                it.increaseConfidence()
            } else {
                it.decreaseConfidence()
                if (validWord.any { it })
                    it.decreaseConfidence() // if the word is valid in another language decrease confidence even more
            }
        }
    }

    /** the dictionaryGroup with most confidence, first group when tied  */
    private val currentlyPreferredDictionaryGroup: DictionaryGroup get() = dictionaryGroups.maxBy { it.confidence }

    /** the only dictionary group, or the dictionaryGroup confidence >= DictionaryGroup.MAX_CONFIDENCE if all others have 0 */
    private val clearlyPreferredDictionaryGroup: DictionaryGroup? get() {
        if (dictionaryGroups.size == 1) return dictionaryGroups.first() // confidence not used if we only have a single group

        val preferred = currentlyPreferredDictionaryGroup
        if (preferred.confidence < DictionaryGroup.MAX_CONFIDENCE) return null
        if (dictionaryGroups.any { it.confidence > 0 && it !== preferred })
            return null
        return preferred
    }

    override fun unlearnFromUserHistory(word: String, ngramContext: NgramContext,
                    timeStampInSeconds: Long, event: DictionaryFacilitator.UnlearnEvent) {
        if (event != DictionaryFacilitator.UnlearnEvent.BACKSPACE) {
            currentlyPreferredDictionaryGroup.getSubDict(Dictionary.TYPE_USER_HISTORY)?.removeUnigramEntryDynamically(word)
        }

        // Update the spelling cache after unlearning. Words that are removed from user history
        // and appear in no other language model are not considered valid.
        putWordIntoValidSpellingWordCache("unlearnFromUserHistory", word.lowercase(Locale.getDefault()))
    }

    override fun getSuggestionResults(
        composedData: ComposedData, ngramContext: NgramContext, keyboard: Keyboard,
        settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, inputStyle: Int
    ): SuggestionResults {
        val proximityInfoHandle = keyboard.proximityInfo.nativeProximityInfo
        val weightOfLangModelVsSpatialModel = floatArrayOf(Dictionary.NOT_A_WEIGHT_OF_LANG_MODEL_VS_SPATIAL_MODEL)

        val waitForOtherDicts = if (dictionaryGroups.size == 1) null else CountDownLatch(dictionaryGroups.size - 1)
        val suggestionsArray = Array<List<SuggestedWordInfo>?>(dictionaryGroups.size) { null }
        for (i in 1..dictionaryGroups.lastIndex) {
            scope.launch {
                suggestionsArray[i] = getSuggestions(composedData, ngramContext, keyboard, settingsValuesForSuggestion, sessionId,
                    proximityInfoHandle, weightOfLangModelVsSpatialModel, dictionaryGroups[i])
                waitForOtherDicts?.countDown()
            }
        }
        suggestionsArray[0] = getSuggestions(composedData, ngramContext, keyboard, settingsValuesForSuggestion, sessionId,
            proximityInfoHandle, weightOfLangModelVsSpatialModel, dictionaryGroups[0])
        val suggestionResults = SuggestionResults(
            SuggestedWords.MAX_SUGGESTIONS, ngramContext.isBeginningOfSentenceContext, false
        )
        waitForOtherDicts?.await()

        suggestionsArray.forEach {
            if (it == null) return@forEach
            suggestionResults.addAll(it)
            suggestionResults.mRawSuggestions?.addAll(it)
        }

        includeAtLeastTwoWordSuggestions(suggestionResults, suggestionsArray, composedData.mTypedWord)

        return suggestionResults
    }

    private fun getSuggestions(
        composedData: ComposedData, ngramContext: NgramContext, keyboard: Keyboard,
        settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int,
        proximityInfoHandle: Long, weightOfLangModelVsSpatialModel: FloatArray, dictGroup: DictionaryGroup
    ): List<SuggestedWordInfo> {
        val suggestions = ArrayList<SuggestedWordInfo>()
        val weightForLocale = dictGroup.getWeightForLocale(dictionaryGroups, composedData.mIsBatchMode)
        val userWords = userWordsAllowed(settingsValuesForSuggestion)
        for (dictType in DictionaryFacilitator.ALL_DICTIONARY_TYPES) {
            if (!userWords && dictType != Dictionary.TYPE_MAIN) continue // learned words and personal dictionary: no source here
            val dictionary = dictGroup.getDict(dictType) ?: continue
            // glide typing: the open-source engine (gesture/) over the main dictionary and the learned and personal words
            // (memory copies, see GlideSuggester); the native engine has no gesture decoder here
            if (composedData.mIsBatchMode) {
                if (dictType == Dictionary.TYPE_MAIN) {
                    val userDictionaries = if (!userWords) emptyList() else listOfNotNull(
                        dictGroup.getSubDict(Dictionary.TYPE_USER_HISTORY), dictGroup.getSubDict(Dictionary.TYPE_USER)
                    ).onEach { it.reloadDictionaryIfRequired() } // glide never asks them for suggestions, which is what reloads a dictionary
                    suggestions.addAll(glide.suggest(dictionary, keyboard, composedData.mInputPointers, weightForLocale,
                        settingsValuesForSuggestion.mBlockPotentiallyOffensive, userDictionaries,
                        userWordsStillAllowed = { userWordsAllowed(settingsValuesForSuggestion) } // looked at again by glide at its start and end: a lock may come in between
                    ).filter { !isBlacklisted(it.word) })
                }
                continue
            }
            val dictionarySuggestions = dictionary.getSuggestions(composedData, ngramContext, proximityInfoHandle,
                settingsValuesForSuggestion, sessionId, weightForLocale, weightOfLangModelVsSpatialModel
            ) ?: continue

            for (info in dictionarySuggestions) {
                val word = info.word
                if (isBlacklisted(word) || SupportedEmojis.isUnsupported(word)) // don't add blacklisted words and unsupported emojis
                    continue

                if (word.length == 1 && info.mSourceDict.mDictType == Dictionary.TYPE_EMOJI && !StringUtils.mightBeEmoji(word[0].code))
                    continue

                suggestions.add(info)
            }
        }
        return suggestions
    }

    /**
     * P9 and P7: the learned words and the personal dictionary are a source of suggestions (typing, next word, glide) only
     * where the decision of PrivacyPolicy says so for the field ([SettingsValuesForSuggestion.mPersonalized]: not in an incognito field)
     * and never while the credential tier is locked, whatever the settings of the field said when they were loaded. The
     * native dictionaries keep their words after a lock, so nothing but this check keeps them out.
     */
    private fun userWordsAllowed(settingsValuesForSuggestion: SettingsValuesForSuggestion) =
        settingsValuesForSuggestion.mPersonalized && !forSpellChecking && !Privacy.isLocked

    /**
     * The validity checks (spell checker, is-this-word-known) have no field: the learned words and the personal dictionary count
     * only for the keyboard's own facilitator and only while the tier is open (the native dictionaries keep their words after a
     * lock). The system spell checker never uses them, whatever was loaded.
     */
    private fun userWordsInValidityChecks() = !forSpellChecking && !Privacy.isLocked

    // Spell checker is using this, and has its own instance of DictionaryFacilitatorImpl,
    // meaning that it always has default mConfidence. So we cannot choose to only check preferred
    // locale, and instead simply return true if word is in any of the available dictionaries
    override fun isValidSpellingWord(word: String): Boolean {
        mValidSpellingWordReadCache?.get(word)?.let { return it }
        val result = dictionaryGroups.any { isValidWord(word, DictionaryFacilitator.ALL_DICTIONARY_TYPES, it) }
        mValidSpellingWordReadCache?.put(word, result)
        return result
    }

    // this is unused, so leave it for now (redirecting to isValidWord seems to defeat the purpose...)
    override fun isValidSuggestionWord(word: String): Boolean {
        return isValidWord(word, DictionaryFacilitator.ALL_DICTIONARY_TYPES, dictionaryGroups[0])
    }

    override fun isValidPredictionWord(word: String, blockPotentiallyOffensive: Boolean): Boolean =
        isValidSuggestionWord(word) && !(blockPotentiallyOffensive && dictionaryGroups[0].getDict(Dictionary.TYPE_MAIN)?.isPossiblyOffensive(word) == true)

    private fun isValidWord(word: String, dictionariesToCheck: Array<String>, dictionaryGroup: DictionaryGroup): Boolean {
        if (word.isEmpty() || dictionaryGroup.isBlacklisted(word)) return false
        val userWords = userWordsInValidityChecks() // before the loop: one decision per check
        return dictionariesToCheck.any {
            (userWords || (it != Dictionary.TYPE_USER && it != Dictionary.TYPE_USER_HISTORY)) && dictionaryGroup.getDict(it)?.isValidWord(word) == true
        }
    }

    private fun isBlacklisted(word: String): Boolean = dictionaryGroups.any { it.isBlacklisted(word) }

    override fun removeWord(word: String) {
        for (dictionaryGroup in dictionaryGroups) {
            dictionaryGroup.removeWord(word)
        }
    }

    override fun clearUserHistoryDictionary(context: Context) {
        for (dictionaryGroup in dictionaryGroups) {
            dictionaryGroup.getSubDict(Dictionary.TYPE_USER_HISTORY)?.clear()
        }
    }

    override fun localesAndConfidences(): String? {
        if (dictionaryGroups.size < 2) return null
        return dictionaryGroups.joinToString(", ") { "${it.locale} ${it.confidence}" }
    }

    override fun dumpDictionaryForDebug(dictName: String) {
        val dictToDump = dictionaryGroups[0].getSubDict(dictName)
        if (dictToDump == null) {
            Log.e(TAG, "Cannot dump . The dictionary is not being used for suggestion or cannot be dumped.")
            return
        }
        dictToDump.dumpAllWordsForDebug()
    }

    override fun getDictionaryStats(context: Context): List<DictionaryStats> =
        DictionaryFacilitator.DYNAMIC_DICTIONARY_TYPES.flatMap { dictType ->
            dictionaryGroups.mapNotNull { it.getSubDict(dictType)?.dictionaryStats }
        }

    override fun dump(context: Context) = getDictionaryStats(context).joinToString("\n")

    companion object {
        private val TAG = DictionaryFacilitatorImpl::class.java.simpleName

        init {
            // the glide word list keeps memory copies of the learned and personal words: empty them when the tier locks
            Privacy.onLock("glide") { GlideSuggester.forgetUserWords() }
        }

        // This threshold is being used when adding a capitalized entry in the User History dictionary.
        private const val CAPITALIZED_FORM_MAX_PROBABILITY_FOR_INSERT = 140

        private fun createSubDict(
            dictType: String, context: Context, locale: Locale, dictFile: File?, dictNamePrefix: String
        ): ExpandableBinaryDictionary? {
            try {
                return when (dictType) {
                    Dictionary.TYPE_USER_HISTORY -> UserHistoryDictionary.getDictionary(context, locale, dictFile, dictNamePrefix)
                    Dictionary.TYPE_USER -> UserBinaryDictionary.getDictionary(context, locale, dictFile, dictNamePrefix)
                    else -> throw IllegalArgumentException("unknown dictionary type $dictType")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Cannot create dictionary")
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Cannot create dictionary")
            }
            return null
        }

        private fun findDictionaryGroupWithLocale(dictGroups: List<DictionaryGroup>?, locale: Locale): DictionaryGroup? {
            return dictGroups?.firstOrNull { it.locale == locale }
        }

        private fun getUsedLocales(mainLocale: Locale, context: Context): Collection<Locale> {
            val locales = hashSetOf(mainLocale)
            // adding secondary locales is a bit tricky since they depend on the subtype
            // but usually this is called with the selected subtype locale
            val selectedSubtype = SubtypeSettings.getSelectedSubtype(context.prefs())
            if (selectedSubtype.locale() == mainLocale) {
                locales.addAll(getSecondaryLocales(selectedSubtype.extraValue))
            } else {
                // probably we're called from the spell checker when using a different app as keyboard
                // so best bet is adding all secondary locales for matching main locale
                SubtypeSettings.getEnabledSubtypes(false).forEach {
                    if (it.locale() == mainLocale)
                        locales.addAll(getSecondaryLocales(it.extraValue))
                }
            }
            return locales
        }

        /** Include at least two non-emoji, non-typed word results if possible, so that the first two shown suggestions can be non-emoji */
        private fun includeAtLeastTwoWordSuggestions(
            suggestionResults: SuggestionResults,
            suggestionsArray: Array<List<SuggestedWordInfo>?>,
            typedWord: String
        ) {
            if (suggestionResults.size <= 2) return
            var nonEmojiNonTypedWordCount = 0
            suggestionResults.forEach {
                if (isEmojiOrTypedWord(it, typedWord)) return@forEach
                ++nonEmojiNonTypedWordCount
                if (nonEmojiNonTypedWordCount >= 2) return
            }
            val allResults = SuggestionResults(Int.MAX_VALUE, false, false)
            suggestionsArray.forEach {
                if (it == null) return@forEach
                allResults.addAll(it)
            }
            var addedWord: String? = null
            for (i in 0 until 2 - nonEmojiNonTypedWordCount) {
                val firstNonEmojiNonTypedWord = allResults.firstOrNull {
                    !suggestionResults.contains(it) && !isEmojiOrTypedWord(it, typedWord)
                        && addedWord?.compareTo(it.word, true) != 0
                } ?: continue
                // The conditions above guarantee that there are at least two EmojiOrTypedWord items
                val lastEmojiOrTypedWord = suggestionResults.last { isEmojiOrTypedWord(it, typedWord) }
                suggestionResults.remove(lastEmojiOrTypedWord)
                suggestionResults.add(firstNonEmojiNonTypedWord)
                addedWord = firstNonEmojiNonTypedWord.word
            }
        }

        private fun isEmojiOrTypedWord(info: SuggestedWordInfo, typedWord: String): Boolean =
            info.isEmoji || info.word.compareTo(typedWord, true) == 0
    }
}

/** A group of dictionaries that work together for a single language. */
private class DictionaryGroup(
    val locale: Locale = Locale(""),
    private var mainDict: Dictionary? = null,
    subDicts: Map<String, ExpandableBinaryDictionary> = emptyMap(),
    context: Context? = null
) {
    private val subDicts: ConcurrentHashMap<String, ExpandableBinaryDictionary> = ConcurrentHashMap(subDicts)

    /** Removes a word from all dictionaries in this group. If the word is in a read-only dictionary, it is blacklisted. */
    fun removeWord(word: String) {
        // remove from user history
        getSubDict(Dictionary.TYPE_USER_HISTORY)?.removeUnigramEntryDynamically(word)

        // and from personal dictionary
        getSubDict(Dictionary.TYPE_USER)?.removeUnigramEntryDynamically(word)

        val mainDict = mainDict ?: return
        if (mainDict.isValidWord(word)) {
            addToBlacklist(word)
            return
        }

        val lowercase = word.lowercase(locale)
        if (getDict(Dictionary.TYPE_MAIN)!!.isValidWord(lowercase)) {
            addToBlacklist(lowercase)
        }
    }

    // --------------- Confidence for multilingual typing -------------------

    // Confidence that the most probable language is actually the language the user is
    // typing in. For now, this is simply the number of times a word from this language
    // has been committed in a row, with an exception when typing a single word not contained
    // in this language.
    var confidence = 1

    // allow to go above max confidence, for better determination of currently preferred language
    // when decreasing confidence or getting weight factor, limit to maximum
    fun increaseConfidence() {
        confidence += 1
    }

    // If confidence is above max, drop to max confidence. This does not change weights and
    // allows conveniently typing single words not in the dictionary without affecting suggestions
    // however, when the word is valid in a different active language, this is called twice to allow for better switching
    fun decreaseConfidence() {
        if (confidence > MAX_CONFIDENCE) confidence = MAX_CONFIDENCE
        else if (confidence > 0) {
            confidence -= 1
        }
    }

    fun getWeightForLocale(groups: List<DictionaryGroup>, isGesturing: Boolean) =
        getWeightForLocale(groups, if (isGesturing) 0.05f else 0.15f)

    // might need some more tuning
    fun getWeightForLocale(groups: List<DictionaryGroup>, step: Float): Float {
        if (groups.size == 1) return 1f
        if (confidence < 2) return 1f - step * (MAX_CONFIDENCE - confidence)
        for (group in groups) {
            if (group !== this && group.confidence >= confidence) return 1f - step / 2f
        }
        return 1f
    }

    // --------------- Blacklist -------------------

    private val scope = CoroutineScope(Dispatchers.IO)

    // Words cannot be (permanently) removed from some dictionaries, so a list of removed words hides them. It is the encrypted store of the
    // language (Privacy.removedWords, P7): one object per language shared with the settings, which list and restore the words.
    private val removedWords: RemovedWords? = context?.let { Privacy.removedWords(it, locale.toLanguageTag()) }

    fun isBlacklisted(word: String) = removedWords?.contains(word) == true

    fun addToBlacklist(word: String) {
        val removed = removedWords ?: return
        removed.hideNow(word) // the strip stops showing it at once; stored in the background (refused while the store is locked)
        scope.launch { removed.add(word) }
    }

    fun removeFromBlacklist(word: String) {
        val removed = removedWords ?: return
        if (!removed.contains(word)) return
        scope.launch { removed.remove(word) }
    }

    // --------------- Dictionary handling -------------------

    fun setMainDict(newMainDict: Dictionary?) {
        // Close old dictionary if exists. Main dictionary can be assigned multiple times.
        val oldDict = mainDict
        mainDict = newMainDict
        if (oldDict != null && newMainDict !== oldDict)
            oldDict.close()
    }

    fun getDict(dictType: String): Dictionary? {
        if (dictType == Dictionary.TYPE_MAIN) {
            return mainDict
        }
        return getSubDict(dictType)
    }

    fun getSubDict(dictType: String): ExpandableBinaryDictionary? {
        return subDicts[dictType]
    }

    fun hasDict(dictType: String): Boolean {
        if (dictType == Dictionary.TYPE_MAIN) {
            return mainDict != null
        }
        return subDicts.containsKey(dictType)
    }

    fun closeDict(dictType: String) {
        val dict = if (Dictionary.TYPE_MAIN == dictType) {
            mainDict
        } else {
            subDicts.remove(dictType)
        }
        dict?.close()
    }

    companion object {
        private val TAG = DictionaryGroup::class.java.simpleName
        const val MAX_CONFIDENCE = 2
    }
}
