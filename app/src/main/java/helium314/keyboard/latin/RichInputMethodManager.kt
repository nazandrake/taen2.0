/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.latin

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import helium314.keyboard.compat.locale
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.LocaleUtils.getBestMatch
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.LanguageOnSpacebarUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ScriptUtils.script
import helium314.keyboard.latin.utils.SubtypeLocaleUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getSecondaryLocales
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/** Enrichment class for InputMethodManager to simplify interaction and add functionality. */
class RichInputMethodManager private constructor() {
    private lateinit var context: Context
    private lateinit var imm: InputMethodManager
    private lateinit var inputMethodInfoCache: InputMethodInfoCache
    private lateinit var currentRichInputMethodSubtype: RichInputMethodSubtype
    private val scope = CoroutineScope(Dispatchers.Default)

    private val isInitializedInternal get() = this::imm.isInitialized

    val currentSubtypeLocale get() = forcedSubtypeForTesting?.locale ?: currentSubtype.locale

    val currentSubtype get() = forcedSubtypeForTesting ?: currentRichInputMethodSubtype

    val combiningRulesExtraValueOfCurrentSubtype get() =
        SubtypeLocaleUtils.getCombiningRulesExtraValue(currentSubtype.rawSubtype)

    val inputMethodInfoOfThisIme get() = inputMethodInfoCache.inputMethodOfThisIme

    val inputMethodManager: InputMethodManager get() {
        checkInitialized()
        return imm
    }

    private var shortcuts = listOf<Shortcut>()

    val isShortcutImeReady get() = shortcuts.isNotEmpty()

    fun getEnabledInputMethodSubtypes(imi: InputMethodInfo, allowsImplicitlySelectedSubtypes: Boolean) =
        inputMethodInfoCache.getEnabledInputMethodSubtypeList(imi, allowsImplicitlySelectedSubtypes)

    fun hasMultipleEnabledIMEsOrSubtypes(shouldIncludeAuxiliarySubtypes: Boolean) =
        hasMultipleEnabledSubtypes(shouldIncludeAuxiliarySubtypes, imm.enabledInputMethodList)

    fun hasMultipleEnabledSubtypesInThisIme(shouldIncludeAuxiliarySubtypes: Boolean) =
        SubtypeSettings.getEnabledSubtypes(shouldIncludeAuxiliarySubtypes).size > 1

    fun getNextSubtypeInThisIme(onlyCurrentIme: Boolean): InputMethodSubtype? {
        val currentSubtype = currentSubtype.rawSubtype
        val enabledSubtypes = SubtypeSettings.getEnabledSubtypes(true)
        val currentIndex = enabledSubtypes.indexOf(currentSubtype)
        if (currentIndex == -1) {
            Log.w(TAG, "Can't find current subtype in enabled subtypes: subtype")
            return if (onlyCurrentIme) enabledSubtypes[0] // just return first enabled subtype
            else null
        }
        val nextIndex = (currentIndex + 1) % enabledSubtypes.size
        if (nextIndex <= currentIndex && !onlyCurrentIme) {
            // The current subtype is the last or only enabled one and it needs to switch to next IME.
            return null
        }
        return enabledSubtypes[nextIndex]
    }

    fun findSubtypeForHintLocale(locale: Locale): InputMethodSubtype? {
        // Find the best subtype based on a locale matching
        val subtypes = SubtypeSettings.getEnabledSubtypes(true)
        var bestMatch = getBestMatch(locale, subtypes) { it.locale() }
        if (bestMatch != null) return bestMatch

        // search for first secondary language & script match
        val language = locale.language
        val script = locale.script()
        for (subtype in subtypes) {
            val subtypeLocale = subtype.locale()
            if (subtypeLocale.script() != script) continue  // need compatible script

            bestMatch = subtype
            val secondaryLocales = getSecondaryLocales(subtype.extraValue)
            for (secondaryLocale in secondaryLocales) {
                if (secondaryLocale.language == language) {
                    return bestMatch
                }
            }
        }
        // if wanted script is not compatible to current subtype, return a subtype with compatible script if available
        if (script != currentSubtypeLocale.script()) {
            return bestMatch
        }
        return null
    }

    fun onSubtypeChanged(newSubtype: InputMethodSubtype) {
        SubtypeSettings.setSelectedSubtype(context.prefs(), newSubtype)
        currentRichInputMethodSubtype = RichInputMethodSubtype.get(newSubtype)
        scope.launch { updateShortcutIme() }
        if (DEBUG) {
            Log.w(TAG, "onSubtypeChanged")
        }
    }

    fun refreshSubtypeCaches() {
        inputMethodInfoCache.clear()
        currentRichInputMethodSubtype = RichInputMethodSubtype.get(SubtypeSettings.getSelectedSubtype(context.prefs()))
        scope.launch { updateShortcutIme() }
    }

    /**
     * Tells the system the language in use. Taen's languages are its own (the system only knows the subtype of method_dummy.xml, which has no
     * language), but the system spell checker and apps (translation offers) take the typing language from the current subtype, so a language-only
     * subtype stands for the current one. The system knows exactly one such subtype: it overrides the implicitly enabled ones, so the system's
     * switcher and next-keyboard order keep one Taen entry, and that entry is always the current subtype.
     * Nothing is called while the system already shows that language: registering rewrites the system's subtype file.
     */
    fun reportLanguageToSystem(inputMethodService: InputMethodService) {
        val subtype = systemSubtypeFor(currentSubtype.rawSubtype) ?: return
        if (inputMethodManager.currentInputMethodSubtype == subtype) return
        val id = inputMethodInfoOfThisIme.id
        imm.setAdditionalInputMethodSubtypes(id, arrayOf(subtype))
        inputMethodService.switchInputMethod(id, subtype)
    }

    fun switchToShortcutIme(inputMethodService: InputMethodService) = scope.launch {
        val imiId = shortcuts.firstOrNull()?.imi?.id ?: return@launch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            inputMethodService.switchInputMethod(imiId, shortcuts.first().subtype)
        } else {
            val token = inputMethodService.window.window?.attributes?.token ?: return@launch
            @Suppress("Deprecation") imm.setInputMethodAndSubtype(token, imiId, shortcuts.first().subtype)
        }
    }

    private fun updateShortcutIme() {
        if (DEBUG) {
            val old = shortcuts.joinToString("; ") { "${it.imi.id}: ${it.subtype.locale()}, ${it.subtype.mode}" }
            Log.d(TAG, "Update shortcut IMEs from")
        }
        val richSubtype = currentRichInputMethodSubtype
        val implicitlyEnabledSubtype = SubtypeSettings.isEnabled(richSubtype.rawSubtype)
                && !SubtypeSettings.getEnabledSubtypes(false).contains(richSubtype.rawSubtype)
        val systemLocale = context.resources.configuration.locale()
        LanguageOnSpacebarUtils.onSubtypeChanged(richSubtype, implicitlyEnabledSubtype, systemLocale)
        LanguageOnSpacebarUtils.setEnabledSubtypes(SubtypeSettings.getEnabledSubtypes(true))

        shortcuts = inputMethodManager.shortcutInputMethodsAndSubtypes.entries.flatMap { (imi, subtypes) ->
            subtypes.map { Shortcut(imi, it) }
        }
        if (DEBUG) {
            val new = shortcuts.joinToString("; ") { "${it.imi.id}: ${it.subtype.locale()}, ${it.subtype.mode}" }
            Log.d(TAG, "Update shortcut IMEs to")
        }
    }

    private fun hasMultipleEnabledSubtypes(shouldIncludeAuxiliarySubtypes: Boolean, imiList: List<InputMethodInfo>): Boolean {
        // Number of the filtered IMEs
        var filteredImisCount = 0

        imiList.forEach { imi ->
            // We can return true immediately after we find two or more filtered IMEs.
            if (filteredImisCount > 1) return true
            val subtypes = getEnabledInputMethodSubtypes(imi, true)
            // IMEs that have no subtypes should be counted.
            if (subtypes.isEmpty()) {
                ++filteredImisCount
                return@forEach
            }

            var auxCount = 0
            for (subtype in subtypes) {
                if (!subtype.isAuxiliary) {
                    // IMEs that have one or more non-auxiliary subtypes should be counted.
                    ++filteredImisCount
                    return@forEach
                }
                ++auxCount
            }

            // If shouldIncludeAuxiliarySubtypes is true, IMEs that have two or more auxiliary
            // subtypes should be counted as well.
            if (shouldIncludeAuxiliarySubtypes && auxCount > 1) {
                ++filteredImisCount
            }
        }

        if (filteredImisCount > 1) {
            return true
        }
        val subtypes = SubtypeSettings.getEnabledSubtypes(true)
        // imm.getEnabledInputMethodSubtypeList(null, true) will return the current IME's
        // both explicitly and implicitly enabled input method subtype.
        // (The current IME should be LatinIME.)
        return subtypes.count { it.mode == Constants.Subtype.KEYBOARD_MODE } > 1
    }

    private fun checkInitialized() {
        if (!isInitializedInternal) {
            throw RuntimeException("$TAG is used before initialization")
        }
    }

    private fun initInternal(ctx: Context) {
        if (isInitializedInternal) {
            return
        }
        imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        context = ctx
        inputMethodInfoCache = InputMethodInfoCache(imm, ctx.packageName)

        // Initialize the current input method subtype and the shortcut IME.
        refreshSubtypeCaches()
    }

    companion object {
        private val TAG = RichInputMethodManager::class.java.simpleName
        private const val DEBUG = false

        private val instance = RichInputMethodManager()

        @JvmStatic
        fun getInstance(): RichInputMethodManager {
            instance.checkInitialized()
            return instance
        }

        fun init(ctx: Context) {
            instance.initInternal(ctx)
        }

        @JvmStatic
        fun isInitialized() = instance.isInitializedInternal

        /** Marks the language-only subtype that stands for Taen's current one in the system (see reportLanguageToSystem). */
        const val SYSTEM_LANGUAGE_EXTRA_VALUE = "SystemLanguage"

        /** The language-only subtype the system is told about while [subtype] is in use, null for a subtype without language. */
        fun systemSubtypeFor(subtype: InputMethodSubtype): InputMethodSubtype? {
            val locale = subtype.locale()
            if (locale.language.isEmpty() || locale.language == SubtypeLocaleUtils.NO_LANGUAGE) return null
            return InputMethodSubtype.InputMethodSubtypeBuilder()
                .setSubtypeNameResId(R.string.subtype_generic)
                .setSubtypeIconResId(R.drawable.ic_ime_switcher)
                .setSubtypeLocale(locale.toString())
                .setLanguageTag(locale.toLanguageTag())
                .setSubtypeMode(Constants.Subtype.KEYBOARD_MODE)
                .setSubtypeExtraValue(SYSTEM_LANGUAGE_EXTRA_VALUE)
                .setIsAuxiliary(false)
                .setOverridesImplicitlyEnabledSubtype(true)
                .setIsAsciiCapable(subtype.isAsciiCapable)
                .build()
        }

        /**
         * Removes the language-only subtype [reportLanguageToSystem] registered: the self-destruct leaves the system no subtype that names the
         * language in use. The next input start registers the language of the fresh settings again.
         */
        @JvmStatic
        fun forgetSystemLanguage(context: Context) {
            val imm = context.getSystemService(InputMethodManager::class.java) ?: return
            val id = imm.inputMethodList.firstOrNull { it.packageName == context.packageName }?.id ?: return
            imm.setAdditionalInputMethodSubtypes(id, emptyArray())
        }

        /** Whether the system hands back a subtype of its own rather than one of Taen's languages: the dummy or the language-only one. */
        @JvmStatic
        fun isSystemOnlySubtype(subtype: InputMethodSubtype) =
            subtype.hashCode() == DUMMY_SUBTYPE_ID || subtype.extraValue == SYSTEM_LANGUAGE_EXTRA_VALUE

        /** The only subtype in method_dummy.xml. */
        private const val DUMMY_SUBTYPE_ID = 0x7000000f

        private var forcedSubtypeForTesting: RichInputMethodSubtype? = null

        fun forceSubtype(subtype: InputMethodSubtype) {
            forcedSubtypeForTesting = RichInputMethodSubtype.get(subtype)
        }

        fun canSwitchLanguage(): Boolean {
            if (!isInitialized()) return false
            if (Settings.getValues().mLanguageSwitchKeyToOtherSubtypes && instance.hasMultipleEnabledSubtypesInThisIme(false)) return true
            if (Settings.getValues().mLanguageSwitchKeyToOtherImes && instance.imm.enabledInputMethodList.size > 1) return true
            return false
        }
    }
}

private class InputMethodInfoCache(private val imm: InputMethodManager, private val imePackageName: String) {
    private var cachedThisImeInfo: InputMethodInfo? = null
    private val cachedSubtypeListWithImplicitlySelected = HashMap<InputMethodInfo, List<InputMethodSubtype>>()

    private val cachedSubtypeListOnlyExplicitlySelected = HashMap<InputMethodInfo, List<InputMethodSubtype>>()

    @get:Synchronized
    val inputMethodOfThisIme: InputMethodInfo get() {
        if (cachedThisImeInfo == null)
            cachedThisImeInfo = imm.inputMethodList.firstOrNull { it.packageName == imePackageName }
        cachedThisImeInfo?.let { return it }
        throw RuntimeException("Input method id for $imePackageName not found, only found " +
                imm.inputMethodList.map { it.packageName })
    }

    @Synchronized
    fun getEnabledInputMethodSubtypeList(imi: InputMethodInfo, allowsImplicitlySelectedSubtypes: Boolean): List<InputMethodSubtype> {
        val cache = if (allowsImplicitlySelectedSubtypes) cachedSubtypeListWithImplicitlySelected
            else cachedSubtypeListOnlyExplicitlySelected
        cache[imi]?.let { return it }
        val result = if (imi == inputMethodOfThisIme) {
            // allowsImplicitlySelectedSubtypes means system should choose if nothing is enabled,
            // use it to fall back to system locales or en_US to avoid returning an empty list
            SubtypeSettings.getEnabledSubtypes(allowsImplicitlySelectedSubtypes)
        } else {
            imm.getEnabledInputMethodSubtypeList(imi, allowsImplicitlySelectedSubtypes)
        }
        cache[imi] = result
        return result
    }

    @Synchronized
    fun clear() {
        cachedThisImeInfo = null
        cachedSubtypeListWithImplicitlySelected.clear()
        cachedSubtypeListOnlyExplicitlySelected.clear()
    }
}

private class Shortcut(val imi: InputMethodInfo, val subtype: InputMethodSubtype)
