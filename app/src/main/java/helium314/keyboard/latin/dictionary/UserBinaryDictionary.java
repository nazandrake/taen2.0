/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.dictionary;

import android.content.Context;

import com.android.inputmethod.latin.BinaryDictionary;

import helium314.keyboard.latin.privacy.PersonalDictionary;
import helium314.keyboard.latin.privacy.PersonalWord;
import helium314.keyboard.latin.privacy.Privacy;
import helium314.keyboard.latin.utils.SubtypeLocaleUtils;

import java.io.File;
import java.util.Locale;

/**
 * An expandable dictionary that holds the words of the personal dictionary (stored encrypted in snippets.enc, see
 * {@link Privacy}) in a native dictionary. The native dictionary lives in memory only: it is never flushed to a file,
 * so the words are not written to disk in readable form, and it is rebuilt from the encrypted store when needed.
 */
public class UserBinaryDictionary extends ExpandableBinaryDictionary {
    private static final int HISTORICAL_DEFAULT_USER_DICTIONARY_FREQUENCY = 250;
    private static final int LATINIME_DEFAULT_USER_DICTIONARY_FREQUENCY = 160;
    // Shortcut frequency is 0~15, with 15 = whitelist. We don't want user dictionary entries
    // to auto-correct, so we set this to the highest frequency that won't, i.e. 14.
    private static final int USER_DICT_SHORTCUT_FREQUENCY = 14;

    private static final String NAME = "userunigram";

    // null while the encrypted store cannot be opened (device locked); the keyboard process restarts after unlock
    private PersonalDictionary mPersonal;
    private final PersonalDictionary.ChangeListener mChangeListener = this::setNeedsToRecreate;
    // the locale string, as used in the stored entries (empty for "no language")
    final private String mLocaleString;

    protected UserBinaryDictionary(final Context context, final Locale locale,
                                   final File dictFile, final String name) {
        super(context, getDictName(name, locale, dictFile), locale, TYPE_USER, dictFile);
        if (null == locale) throw new NullPointerException(); // Catch the error earlier
        final String localeStr = locale.toString();
        mLocaleString = SubtypeLocaleUtils.NO_LANGUAGE.equals(localeStr) ? "" : localeStr;
        attach();
        reloadDictionaryIfRequired();
    }

    public static UserBinaryDictionary getDictionary(
            final Context context, final Locale locale, final File dictFile, final String dictNamePrefix) {
        return new UserBinaryDictionary(context, locale, dictFile, dictNamePrefix + NAME);
    }

    private synchronized PersonalDictionary attach() {
        if (mPersonal == null) {
            mPersonal = Privacy.INSTANCE.personalDictionary(mContext);
            if (mPersonal != null) mPersonal.addListener(mChangeListener);
        }
        return mPersonal;
    }

    @Override
    public synchronized void close() {
        if (mPersonal != null) {
            mPersonal.removeListener(mChangeListener);
            mPersonal = null;
        }
        super.close();
        // an earlier version may have left a readable file; the native dictionary of this class is never written
        removeBinaryDictionaryFile();
    }

    // never write the native dictionary to a file (see class comment)
    @Override
    public void onFinishInput() {}

    @Override
    protected void runGCIfRequiredLocked(final boolean mindsBlockByGC) {}

    @Override
    void createNewDictionaryLocked() {
        removeBinaryDictionaryLocked();
        createOnMemoryBinaryDictionaryLocked();
        loadInitialContentsLocked();
    }

    @Override
    public void loadInitialContentsLocked() {
        final PersonalDictionary personal = attach();
        if (personal == null) return;
        for (final PersonalWord entry : personal.matching(mLocaleString)) {
            addWordLocked(entry.getWord(), entry.getShortcut(), entry.getFrequency());
        }
    }

    private static int scaleFrequencyFromDefaultToLatinIme(final int defaultFrequency) {
        // The default frequency for the user dictionary is 250 for historical reasons.
        // Latin IME considers a good value for the default user dictionary frequency
        // is about 160 considering the scale we use. So we are scaling down the values.
        if (defaultFrequency > Integer.MAX_VALUE / LATINIME_DEFAULT_USER_DICTIONARY_FREQUENCY) {
            return (defaultFrequency / HISTORICAL_DEFAULT_USER_DICTIONARY_FREQUENCY)
                    * LATINIME_DEFAULT_USER_DICTIONARY_FREQUENCY;
        }
        return (defaultFrequency * LATINIME_DEFAULT_USER_DICTIONARY_FREQUENCY)
                / HISTORICAL_DEFAULT_USER_DICTIONARY_FREQUENCY;
    }

    private void addWordLocked(final String word, final String shortcut, final int frequency) {
        final int adjustedFrequency = scaleFrequencyFromDefaultToLatinIme(frequency);
        // Safeguard against adding really long words.
        if (word.length() > MAX_WORD_LENGTH) return;
        addUnigramLocked(word, adjustedFrequency, null /* shortcutTarget */,
                0 /* shortcutFreq */, false /* isNotAWord */,
                false /* isPossiblyOffensive */,
                BinaryDictionary.NOT_A_VALID_TIMESTAMP);
        if (null != shortcut && shortcut.length() <= MAX_WORD_LENGTH) {
            addUnigramLocked(shortcut, adjustedFrequency, word,
                    USER_DICT_SHORTCUT_FREQUENCY, true /* isNotAWord */,
                    false /* isPossiblyOffensive */,
                    BinaryDictionary.NOT_A_VALID_TIMESTAMP);
        }
    }
}
