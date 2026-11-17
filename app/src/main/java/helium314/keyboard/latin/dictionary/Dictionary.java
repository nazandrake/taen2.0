/*
 * Copyright (C) 2008 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.dictionary;

import java.util.ArrayList;
import java.util.Locale;

import helium314.keyboard.latin.NgramContext;
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo;
import helium314.keyboard.latin.common.ComposedData;
import helium314.keyboard.latin.makedict.WordProperty;
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion;

/**
 * Abstract base class for a dictionary that can do a fuzzy search for words based on a set of key
 * strokes.
 */
public abstract class Dictionary {
    public static final int NOT_A_PROBABILITY = -1;
    public static final float NOT_A_WEIGHT_OF_LANG_MODEL_VS_SPATIAL_MODEL = -1.0f;

    // The following types do not actually come from real dictionary instances, so we create
    // corresponding instances.
    public static final String TYPE_USER_TYPED = "user_typed";
    public static final PhonyDictionary DICTIONARY_USER_TYPED = new PhonyDictionary(TYPE_USER_TYPED);

    public static final String TYPE_USER_SHORTCUT = "user_shortcut";
    public static final PhonyDictionary DICTIONARY_USER_SHORTCUT =
            new PhonyDictionary(TYPE_USER_SHORTCUT);

    public static final String TYPE_APPLICATION_DEFINED = "application_defined";
    public static final PhonyDictionary DICTIONARY_APPLICATION_DEFINED =
            new PhonyDictionary(TYPE_APPLICATION_DEFINED);

    public static final String TYPE_HARDCODED = "hardcoded"; // punctuation signs and such
    public static final PhonyDictionary DICTIONARY_HARDCODED =
            new PhonyDictionary(TYPE_HARDCODED);

    // Spawned by resuming suggestions. Comes from a span that was in the TextView.
    public static final String TYPE_RESUMED = "resumed";
    public static final PhonyDictionary DICTIONARY_RESUMED = new PhonyDictionary(TYPE_RESUMED);

    // A next word the on-device model added to the prediction strip (always a word of the active dictionaries; never learned when picked).
    public static final String TYPE_NEXTWORD = "nextword";
    public static final PhonyDictionary DICTIONARY_NEXTWORD = new PhonyDictionary(TYPE_NEXTWORD);

    // The following types of dictionary have actual functional instances. We don't need final
    // phony dictionary instances for them.
    public static final String TYPE_MAIN = "main";
    // User dictionary, the system-managed one.
    public static final String TYPE_USER = "user";
    // User history dictionary internal to LatinIME.
    public static final String TYPE_USER_HISTORY = "history";
    public static final String TYPE_EMOJI = "emoji";
    public final String mDictType;
    // The locale for this dictionary. May be null if unknown (phony dictionary for example).
    public final Locale mLocale;

    public Dictionary(final String dictType, final Locale locale) {
        mDictType = dictType;
        mLocale = locale;
    }

    /**
     * Searches for suggestions for a given context.
     * @param composedData the key sequence to match with coordinate info
     * @param ngramContext the context for n-gram.
     * @param proximityInfoHandle the handle for key proximity. Is ignored by some implementations.
     * @param settingsValuesForSuggestion the settings values used for the suggestion.
     * @param sessionId the session id.
     * @param weightForLocale the weight given to this locale, to multiply the output scores for
     * multilingual input.
     * @param inOutWeightOfLangModelVsSpatialModel the weight of the language model as a ratio of
     * the spatial model, used for generating suggestions. inOutWeightOfLangModelVsSpatialModel is
     * a float array that has only one element. This can be updated when a different value is used.
     * @return the list of suggestions (possibly null if none)
     */
    abstract public ArrayList<SuggestedWordInfo> getSuggestions(final ComposedData composedData,
            final NgramContext ngramContext, final long proximityInfoHandle,
            final SettingsValuesForSuggestion settingsValuesForSuggestion,
            final int sessionId, final float weightForLocale,
            final float[] inOutWeightOfLangModelVsSpatialModel);

    /**
     * Checks if the given word has to be treated as a valid word. Please note that some
     * dictionaries have entries that should be treated as invalid words.
     * @param word the word to search for. The search should be case-insensitive.
     * @return true if the word is valid, false otherwise
     */
    public boolean isValidWord(final String word) {
        return isInDictionary(word);
    }

    /**
     * Checks if the given word is in the dictionary regardless of it being valid or not.
     */
    abstract public boolean isInDictionary(final String word);

    /**
     * Get the frequency of the word.
     * @param word the word to get the frequency of.
     */
    public int getFrequency(final String word) {
        return NOT_A_PROBABILITY;
    }

    /** Receives the words of {@link #forEachWord}. */
    public interface WordListener {
        void onWord(String word, int probability);
    }

    /**
     * Hands every word of a dictionary with its probability to the listener (the word list of the glide typing
     * engine). Returns whether the whole list was handed over: false when the dictionary cannot be read now (lock
     * timeout, not loaded, locked, damaged), so that a caller never keeps a short list as the current one. A dictionary
     * that has no word list (the emoji suggestions) hands over nothing, which is its complete list: true.
     */
    public boolean forEachWord(final WordListener listener) {
        return true;
    }

    /**
     * A number that changes whenever the content of the dictionary may have changed; copies of the content (the glide
     * typing word list of the learned and personal words) are read again when it differs. Read-only dictionaries never change.
     */
    public long contentVersion() {
        return 0;
    }

    /** Whether the word is marked possibly offensive in the dictionary. */
    public boolean isPossiblyOffensive(final String word) {
        return false;
    }

    /**
     * Get the maximum frequency of the word.
     * @param word the word to get the maximum frequency of.
     */
    public int getMaxFrequencyOfExactMatches(final String word) {
        return NOT_A_PROBABILITY;
    }

    /**
     * Compares the contents of the character array with the typed word and returns true if they
     * are the same.
     * @param word the array of characters that make up the word
     * @param length the number of valid characters in the character array
     * @param typedWord the word to compare with
     * @return true if they are the same, false otherwise.
     */
    protected boolean same(final char[] word, final int length, final String typedWord) {
        if (typedWord.length() != length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (word[i] != typedWord.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Override to clean up any resources.
     */
    public void close() {
        // empty base implementation
    }

    public void onFinishInput() {
        //empty base implementation
    }

    /**
     * Subclasses may override to indicate that this Dictionary is not yet properly initialized.
     */
    public boolean isInitialized() {
        return true;
    }

    /**
     * Whether we think this suggestion should trigger an auto-commit. prevWord is the word
     * before the suggestion, so that we can use n-gram frequencies.
     * @param candidate The candidate suggestion, in whole (not only the first part).
     * @return whether we should auto-commit or not.
     */
    public boolean shouldAutoCommit(final SuggestedWordInfo candidate) {
        // If we don't have support for auto-commit, or if we don't know, we return false to
        // avoid auto-committing stuff. Implementations of the Dictionary class that know to
        // determine whether we should auto-commit will override this.
        return false;
    }

    /**
     * Whether this dictionary is based on data specific to the user, e.g., the user's own words.
     * @return Whether this dictionary is specific to the user.
     */
    public boolean isUserSpecific() {
        return switch (mDictType) {
            case TYPE_USER_TYPED,
                 TYPE_USER,
                 TYPE_USER_HISTORY -> true;
            default -> false;
        };
    }

    public WordProperty getWordProperty(final String word, final boolean isBeginningOfSentence) {
        return null;
    }

    /**
     * Not a true dictionary. A placeholder used to indicate suggestions that don't come from any
     * real dictionary.
     */
    public static class PhonyDictionary extends Dictionary {
        public PhonyDictionary(final String type) {
            super(type, null);
        }

        @Override
        public ArrayList<SuggestedWordInfo> getSuggestions(final ComposedData composedData,
                final NgramContext ngramContext, final long proximityInfoHandle,
                final SettingsValuesForSuggestion settingsValuesForSuggestion,
                final int sessionId, final float weightForLocale,
                final float[] inOutWeightOfLangModelVsSpatialModel) {
            return null;
        }

        @Override
        public boolean isInDictionary(String word) {
            return false;
        }
    }
}
