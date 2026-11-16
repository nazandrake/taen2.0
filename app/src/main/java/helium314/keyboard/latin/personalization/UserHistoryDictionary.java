/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.personalization;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.inputmethod.latin.BinaryDictionary;
import helium314.keyboard.latin.dictionary.Dictionary;
import helium314.keyboard.latin.dictionary.ExpandableBinaryDictionary;
import helium314.keyboard.latin.NgramContext;
import helium314.keyboard.latin.common.FileUtils;
import helium314.keyboard.latin.makedict.DictionaryHeader;
import helium314.keyboard.latin.privacy.LearnedLoad;
import helium314.keyboard.latin.privacy.LearnedStorage;
import helium314.keyboard.latin.privacy.LegacyLearned;
import helium314.keyboard.latin.privacy.Privacy;

import java.io.File;
import java.util.Locale;
import java.util.Map;

/**
 * Locally gathers statistics about the words user types and various other signals like
 * auto-correction cancellation or manual picks. This allows the keyboard to adapt to the
 * typist over time.
 */
public class UserHistoryDictionary extends ExpandableBinaryDictionary {
    static final String NAME = UserHistoryDictionary.class.getSimpleName();

    // The learned words are kept only as an encrypted store of the serialized dictionary (see LearnedStorage); the
    // native dictionary lives in memory and is never written to a file.
    private final LearnedStorage mStorage;

    // TODO: Make this constructor private
    UserHistoryDictionary(final Context context, final Locale locale) {
        super(context, getUserHistoryDictName(NAME, locale, null), locale, Dictionary.TYPE_USER_HISTORY, null);
        final File legacyFile = getDictFile(context, getUserHistoryDictName(NAME, locale, null), null);
        mStorage = Privacy.INSTANCE.learnedStorage(context, locale.toLanguageTag(),
                new LegacyHistoryFile(legacyFile, locale));
        if (mLocale != null && mLocale.toString().length() > 1) {
            reloadDictionaryIfRequired();
        }
    }

    /**
     * Moves the readable dictionary directories of earlier versions for every language into the encrypted stores, so
     * that a language that is not in use does not keep plaintext. Returns true when none is left (false while the device
     * is locked or a move failed; the caller tries again later).
     */
    static boolean moveAllLegacyIntoStorage(final Context context) {
        final File filesDir = context.getFilesDir();
        final File[] legacy = filesDir == null ? null : filesDir.listFiles((d, n) -> n.startsWith(NAME + ".") && n.endsWith(".dict"));
        if (legacy == null) return true;
        boolean allDone = true;
        for (final File f : legacy) {
            if (!f.isDirectory()) continue;
            final String tag = f.getName().substring(NAME.length() + 1, f.getName().length() - ".dict".length());
            final Locale locale = Locale.forLanguageTag(tag);
            Privacy.INSTANCE.learnedStorage(context, locale.toLanguageTag(), new LegacyHistoryFile(f, locale)).load();
            if (f.exists()) allDone = false;
        }
        return allDone;
    }

    @Override
    protected boolean usesStorage() {
        return true;
    }

    @NonNull
    @Override
    protected LearnedLoad readStorage() {
        return mStorage.load();
    }

    @Override
    protected boolean writeStorage(@NonNull final byte[] bytes) {
        return mStorage.save(bytes);
    }

    @Override
    protected void deleteStorage() {
        mStorage.delete();
    }

    @Override
    protected boolean isStorageAvailable() {
        return mStorage.isAvailable();
    }

    /**
     * The readable native dictionary directory (and the temporary directories next to it) that earlier versions wrote.
     * It is read once to move the learned words into the encrypted store, then deleted.
     */
    private static final class LegacyHistoryFile implements LegacyLearned {
        private final File mFile;
        private final Locale mLocale;

        LegacyHistoryFile(final File file, final Locale locale) {
            mFile = file;
            mLocale = locale;
        }

        @Override
        public boolean exists() {
            final File[] files = siblings();
            return files != null && files.length > 0;
        }

        @Nullable
        private File[] siblings() {
            final File dir = mFile.getParentFile();
            if (dir == null) return null;
            final String name = mFile.getName();
            return dir.listFiles((d, n) -> n.equals(name) || n.startsWith(name + "."));
        }

        @Nullable
        @Override
        public byte[] toSerialized() {
            if (!mFile.isDirectory()) return null;
            final BinaryDictionary dictionary = new BinaryDictionary(mFile.getAbsolutePath(), 0 /* offset */,
                    mFile.length(), true /* useFullEditDistance */, mLocale, Dictionary.TYPE_USER_HISTORY,
                    true /* isUpdatable */);
            try {
                // an old format (402) cannot be serialized: null, and the old words are dropped
                return dictionary.isValidDictionary() ? dictionary.serialize(true /* withGC */) : null;
            } finally {
                dictionary.close();
            }
        }

        @Override
        public void delete() {
            final File[] files = siblings();
            if (files == null) return;
            for (final File f : files) {
                FileUtils.deleteRecursively(f);
            }
        }
    }

    /**
     * @returns the name of the {@link UserHistoryDictionary}.
     */
    static String getUserHistoryDictName(final String name, final Locale locale, @Nullable final File dictFile) {
        return getDictName(name, locale, dictFile);
    }

    public static UserHistoryDictionary getDictionary(final Context context, final Locale locale,
            final File dictFile, final String dictNamePrefix) {
        return PersonalizationHelper.getUserHistoryDictionary(context, locale);
    }

    /**
     * Add a word to the user history dictionary.
     *
     * @param userHistoryDictionary the user history dictionary
     * @param ngramContext the n-gram context
     * @param word the word the user inputted
     * @param isValid whether the word is valid or not
     * @param timestamp the timestamp when the word has been inputted
     */
    public static void addToDictionary(final ExpandableBinaryDictionary userHistoryDictionary,
            @NonNull final NgramContext ngramContext, final String word, final boolean isValid,
            final int timestamp) {
        if (word.length() > BinaryDictionary.DICTIONARY_MAX_WORD_LENGTH) {
            return;
        }
        userHistoryDictionary.updateEntriesForWord(ngramContext, word,
                isValid, 1 /* count */, timestamp);
    }

    @Override
    protected Map<String, String> getHeaderAttributeMap() {
        final Map<String, String> attributeMap = super.getHeaderAttributeMap();
        attributeMap.put(DictionaryHeader.USES_FORGETTING_CURVE_KEY,
                DictionaryHeader.ATTRIBUTE_VALUE_TRUE);
        attributeMap.put(DictionaryHeader.HAS_HISTORICAL_INFO_KEY,
                DictionaryHeader.ATTRIBUTE_VALUE_TRUE);
        return attributeMap;
    }

    @Override
    protected void loadInitialContentsLocked() {
        // No initial contents.
    }

    @Override
    public boolean isValidWord(final String word) {
        // Strings out of this dictionary should not be considered existing words.
        return false;
    }
}
