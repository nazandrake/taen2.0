/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.dictionary;

import android.content.Context;
import helium314.keyboard.latin.NgramContext;
import helium314.keyboard.latin.utils.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.inputmethod.latin.BinaryDictionary;

import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo;
import helium314.keyboard.latin.common.ComposedData;
import helium314.keyboard.latin.common.FileUtils;
import helium314.keyboard.latin.define.DecoderSpecificConstants;
import helium314.keyboard.latin.makedict.DictionaryHeader;
import helium314.keyboard.latin.makedict.FormatSpec;
import helium314.keyboard.latin.makedict.UnsupportedFormatException;
import helium314.keyboard.latin.makedict.WordProperty;
import helium314.keyboard.latin.privacy.LearnedLoad;
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion;
import helium314.keyboard.latin.utils.AsyncResultHolder;
import helium314.keyboard.latin.utils.CombinedFormatUtils;
import helium314.keyboard.latin.utils.ExecutorUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Abstract base class for an expandable dictionary that can be created and updated dynamically
 * during runtime. When updated it automatically generates a new binary dictionary to handle future
 * queries in native code. This binary dictionary is written to internal storage.
 * <p>
 * A class that extends this abstract class must have a static factory method named
 *   getDictionary(Context context, Locale locale, File dictFile, String dictNamePrefix)
 */
abstract public class ExpandableBinaryDictionary extends Dictionary {
    private static final boolean DEBUG = false;

    /** Used for Log actions from this class */
    private static final String TAG = ExpandableBinaryDictionary.class.getSimpleName();

    /** Whether to print debug output to log */
    private static final boolean DBG_STRESS_TEST = false;

    private static final int TIMEOUT_FOR_READ_OPS_IN_MILLISECONDS = 100;

    /**
     * The maximum length of a word in this dictionary.
     */
    protected static final int MAX_WORD_LENGTH =
            DecoderSpecificConstants.DICTIONARY_MAX_WORD_LENGTH;

    private static final int DICTIONARY_FORMAT_VERSION = FormatSpec.VERSION4;

    private static final WordProperty[] DEFAULT_WORD_PROPERTIES_FOR_SYNC =
            new WordProperty[0] /* default */;

    /** The application context. */
    public final Context mContext;

    /**
     * The binary dictionary generated dynamically from the fusion dictionary. This is used to
     * answer unigram and bigram queries.
     */
    private BinaryDictionary mBinaryDictionary;

    /**
     * The name of this dictionary, used as a part of the filename for storing the binary
     * dictionary.
     */
    private final String mDictName;

    /**
     * Where an earlier version kept this dictionary as a readable native file. Nothing is written there any more;
     * it is only deleted. The dictionary lives in memory and subclasses that keep it use the storage hooks below.
     */
    private final File mDictFile;

    /**
     * Whether the in-memory dictionary reflects the state of the storage (it was loaded from it, or the storage is
     * empty). Only then may it be saved: a dictionary made while the storage could not be read must never replace
     * the stored one.
     */
    private volatile boolean mStorageReady = false;

    /** Whether the in-memory dictionary has changes that are not saved yet. */
    private boolean mDirty = false;

    /** Indicates whether a task for reloading the dictionary has been scheduled. */
    private final AtomicBoolean mIsReloading;

    /** Indicates whether the current dictionary needs to be recreated. */
    private volatile boolean mNeedsToRecreate;

    private final ReentrantReadWriteLock mLock;

    /* A extension for a binary dictionary file. */
    protected static final String DICT_FILE_EXTENSION = ".dict";

    /**
     * Abstract method for loading initial contents of a given dictionary.
     */
    protected abstract void loadInitialContentsLocked();

    // ---- storage hooks: the dictionary is saved as encrypted bytes by the subclass, never as a native file --------

    /** Whether this dictionary is saved between runs (through the hooks below). Default: memory only. */
    protected boolean usesStorage() {
        return false;
    }

    /** The saved dictionary bytes (null bytes: nothing saved); "not available" while storage cannot be read. */
    @NonNull
    protected LearnedLoad readStorage() {
        return new LearnedLoad(true, null);
    }

    /** Saves the serialized dictionary; false if that failed (it is tried again at the next flush). */
    protected boolean writeStorage(@NonNull final byte[] bytes) {
        return false;
    }

    /** Deletes the saved dictionary. */
    protected void deleteStorage() {}

    /** Whether the storage can be read now (it cannot while the device is locked). */
    protected boolean isStorageAvailable() {
        return true;
    }

    // Also false while the storage is closed again (screen lock, import): nothing is learned or saved then, whatever was loaded before.
    private boolean isWritable() {
        return !usesStorage() || (mStorageReady && isStorageAvailable());
    }

    static boolean matchesExpectedBinaryDictFormatVersionForThisType(final int formatVersion) {
        return formatVersion == FormatSpec.VERSION4;
    }

    public boolean isValidDictionaryLocked() {
        return mBinaryDictionary.isValidDictionary();
    }

    /**
     * Creates a new expandable binary dictionary.
     *
     * @param context The application context of the parent.
     * @param dictName The name of the dictionary. Multiple instances with the same
     *        name is supported.
     * @param locale the dictionary locale.
     * @param dictType the dictionary type, as a human-readable string
     * @param dictFile dictionary file path. if null, use default dictionary path based on
     *        dictionary type.
     */
    public ExpandableBinaryDictionary(final Context context, final String dictName,
            final Locale locale, final String dictType, final File dictFile) {
        super(dictType, locale);
        mDictName = dictName;
        mContext = context;
        mDictFile = getDictFile(context, dictName, dictFile);
        mBinaryDictionary = null;
        mIsReloading = new AtomicBoolean();
        mNeedsToRecreate = false;
        mLock = new ReentrantReadWriteLock();
    }

    public static File getDictFile(final Context context, final String dictName,
            final File dictFile) {
        return (dictFile != null) ? dictFile
                : new File(context.getFilesDir(), dictName + DICT_FILE_EXTENSION);
    }

    public static String getDictName(final String name, final Locale locale,
            final File dictFile) {
        return dictFile != null ? dictFile.getName() : name + "." + locale.toLanguageTag();
    }

    private void asyncExecuteTaskWithWriteLock(final Runnable task) {
        asyncExecuteTaskWithLock(mLock.writeLock(), () -> {
            try {
                task.run();
            } finally {
                mContentVersion.incrementAndGet(); // after the task: a reader that saw the old number reads again
            }
        });
    }

    // changes whenever a write task has run (words added, removed, saved, evicted, cleared)
    private final java.util.concurrent.atomic.AtomicLong mContentVersion = new java.util.concurrent.atomic.AtomicLong();

    @Override
    public long contentVersion() {
        return mContentVersion.get();
    }

    /**
     * The words of the in-memory dictionary with their probability (glide typing word list of the learned and
     * personal words). Nothing, and false, when the dictionary is not loaded, waits to be recreated (evicted at the screen
     * lock) or has its storage closed: this never loads or creates the dictionary. Gives up (false) when a write holds the
     * lock for more than a short time.
     */
    @Override
    public boolean forEachWord(final WordListener listener) {
        boolean lockAcquired = false;
        try {
            lockAcquired = mLock.readLock().tryLock(TIMEOUT_FOR_READ_OPS_IN_MILLISECONDS, TimeUnit.MILLISECONDS);
            // A dictionary waiting to be recreated (the personal dictionary was wiped at the screen lock, an edit is queued) or
            // with closed storage (locked) is not the truth: its native copy may still hold words that must not be listed.
            if (lockAcquired && mBinaryDictionary != null && !mNeedsToRecreate && (!usesStorage() || isStorageAvailable())) {
                return mBinaryDictionary.forEachWord(listener);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (lockAcquired) {
                mLock.readLock().unlock();
            }
        }
        return false;
    }

    private static void asyncExecuteTaskWithLock(final Lock lock, final Runnable task) {
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute(() -> {
            lock.lock();
            try {
                task.run();
            } finally {
                lock.unlock();
            }
        });
    }

    @Nullable
    BinaryDictionary getBinaryDictionary() {
        return mBinaryDictionary;
    }

    @Override
    public int getFrequency(final String word) {
        if (mLock.readLock().tryLock()) {
            try {
                return mBinaryDictionary.getFrequency(word);
            } finally {
                mLock.readLock().unlock();
            }
        }
        return NOT_A_PROBABILITY;
    }

    void closeBinaryDictionary() {
        if (mBinaryDictionary != null) {
            mBinaryDictionary.close();
            mBinaryDictionary = null;
        }
    }

    /**
     * Closes and cleans up the binary dictionary.
     */
    @Override
    public void close() {
        // a language switch closes the dictionary of the old language without a finish-input: save what it learned
        asyncExecuteTaskWithWriteLock(() -> {
            flushToStorageLocked(false /* withGC */);
            closeBinaryDictionary();
        });
    }

    protected Map<String, String> getHeaderAttributeMap() {
        HashMap<String, String> attributeMap = new HashMap<>();
        attributeMap.put(DictionaryHeader.DICTIONARY_ID_KEY, mDictName);
        attributeMap.put(DictionaryHeader.DICTIONARY_LOCALE_KEY, mLocale.toString());
        attributeMap.put(DictionaryHeader.DICTIONARY_VERSION_KEY,
                String.valueOf(TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())));
        return attributeMap;
    }

    private void removeBinaryDictionary() {
        asyncExecuteTaskWithWriteLock(this::removeBinaryDictionaryLocked);
    }

    void removeBinaryDictionaryLocked() {
        closeBinaryDictionary();
        // a dictionary that never saw the storage must not delete what is stored
        if (isWritable()) deleteStorage();
        mDirty = false;
        removeBinaryDictionaryFile();
    }

    /** Deletes the readable file of an earlier version; the open dictionary is not touched. */
    protected void removeBinaryDictionaryFile() {
        if (mDictFile.exists() && !FileUtils.deleteRecursively(mDictFile)) {
            Log.e(TAG, "Can't remove a file");
        }
    }

    // memory only: the empty file name makes every flush to a file fail
    private BinaryDictionary newInMemoryDictionary() {
        return new BinaryDictionary("" /* no file */, true /* useFullEditDistance */, mLocale, mDictType,
                DICTIONARY_FORMAT_VERSION, getHeaderAttributeMap());
    }

    void createOnMemoryBinaryDictionaryLocked() {
        mBinaryDictionary = newInMemoryDictionary();
    }

    /**
     * Drops the in-memory dictionary without saving it; the next access loads the stored one. Waits until the tasks
     * queued before (which may save) have run, so that nothing they save lands after what the caller stores next.
     * Used after an import replaced the stored learned words.
     */
    public void discardUnsavedAndReload() {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        asyncExecuteTaskWithWriteLock(() -> {
            try {
                closeBinaryDictionary();
                mStorageReady = false;
                mDirty = false;
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(5, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Screen lock: saves what was learned and drops the in-memory dictionary; the next access loads the stored one, or an
     * empty one that is never saved while the storage is closed. Counts [done] down when the task has run.
     */
    public void saveAndEvict(final java.util.concurrent.CountDownLatch done) {
        asyncExecuteTaskWithWriteLock(() -> {
            try {
                flushToStorageLocked(false /* withGC */);
                closeBinaryDictionary();
                mStorageReady = false;
                mDirty = false;
            } finally {
                done.countDown();
            }
        });
    }

    public void clear() {
        asyncExecuteTaskWithWriteLock(() -> {
            removeBinaryDictionaryLocked();
            createOnMemoryBinaryDictionaryLocked();
        });
    }

    /**
     * Check whether GC is needed and run GC if required.
     */
    public void runGCIfRequired(final boolean mindsBlockByGC) {
        asyncExecuteTaskWithWriteLock(() -> {
            if (getBinaryDictionary() == null) {
                return;
            }
            runGCIfRequiredLocked(mindsBlockByGC);
        });
    }

    protected void runGCIfRequiredLocked(final boolean mindsBlockByGC) {
        if (mBinaryDictionary.needsToRunGC(mindsBlockByGC)) {
            flushToStorageLocked(true /* withGC */);
        }
    }

    /**
     * Saves the dictionary through the storage hooks (after GC if [withGC]). Memory-only dictionaries and ones that
     * never saw the storage are not saved. The GC changes the dictionary in memory, so it continues from the result.
     */
    private void flushToStorageLocked(final boolean withGC) {
        final BinaryDictionary dictionary = mBinaryDictionary;
        if (dictionary == null || !isWritable()) return;
        if (!withGC && !mDirty) return;
        final byte[] bytes = dictionary.serialize(withGC);
        if (bytes == null) {
            Log.e(TAG, "Cannot serialize the dictionary");
            return;
        }
        if (withGC) {
            final BinaryDictionary gced = new BinaryDictionary(bytes, true /* useFullEditDistance */, mLocale, mDictType);
            if (gced.isValidDictionary()) {
                mBinaryDictionary = gced;
            } else {
                // should not happen; the saved copy is reloaded at the next access
                gced.close();
                mBinaryDictionary = null;
            }
            dictionary.close();
        }
        if (writeStorage(bytes)) {
            mDirty = false;
        } else {
            Log.w(TAG, "Cannot save the dictionary");
        }
    }

    private void updateDictionaryWithWriteLock(@NonNull final Runnable updateTask) {
        reloadDictionaryIfRequired();
        asyncExecuteTaskWithWriteLock(() -> {
            // no learning while the storage cannot be read (device locked)
            if (getBinaryDictionary() == null || !isWritable()) {
                return;
            }
            runGCIfRequiredLocked(true /* mindsBlockByGC */);
            if (getBinaryDictionary() == null) {
                return;
            }
            mDirty = true; // after the GC, which saves what was there before
            updateTask.run();
        });
    }

    /**
     * Adds unigram information of a word to the dictionary. May overwrite an existing entry.
     */
    public void addUnigramEntry(final String word, final int frequency,
            final String shortcutTarget, final int shortcutFreq, final boolean isNotAWord,
            final boolean isPossiblyOffensive, final int timestamp) {
        updateDictionaryWithWriteLock(() -> addUnigramLocked(word, frequency, shortcutTarget,
                shortcutFreq, isNotAWord, isPossiblyOffensive, timestamp));
    }

    protected void addUnigramLocked(final String word, final int frequency,
            final String shortcutTarget, final int shortcutFreq, final boolean isNotAWord,
            final boolean isPossiblyOffensive, final int timestamp) {
        if (!mBinaryDictionary.addUnigramEntry(word, frequency, shortcutTarget, shortcutFreq,
                false /* isBeginningOfSentence */, isNotAWord, isPossiblyOffensive, timestamp)) {
            Log.e(TAG, "Cannot add unigram entry.");
        }
    }

    /**
     * Dynamically remove the unigram entry from the dictionary.
     */
    public void removeUnigramEntryDynamically(final String word) {
        reloadDictionaryIfRequired();
        asyncExecuteTaskWithWriteLock(() -> {
            if (getBinaryDictionary() == null || !isWritable()) {
                return;
            }
            runGCIfRequiredLocked(true /* mindsBlockByGC */);
            final BinaryDictionary binaryDictionary = getBinaryDictionary(); // the GC may have replaced it
            if (binaryDictionary == null) {
                return;
            }
            mDirty = true;
            if (!binaryDictionary.removeUnigramEntry(word)) {
                if (DEBUG) {
                    Log.i(TAG, "Cannot remove unigram entry");
                }
            }
        });
    }

    /**
     * Adds n-gram information of a word to the dictionary. May overwrite an existing entry.
     */
    public void addNgramEntry(@NonNull final NgramContext ngramContext, final String word,
            final int frequency, final int timestamp) {
        reloadDictionaryIfRequired();
        asyncExecuteTaskWithWriteLock(() -> {
            if (getBinaryDictionary() == null || !isWritable()) {
                return;
            }
            runGCIfRequiredLocked(true /* mindsBlockByGC */);
            if (getBinaryDictionary() == null) {
                return;
            }
            mDirty = true;
            addNgramEntryLocked(ngramContext, word, frequency, timestamp);
        });
    }

    protected void addNgramEntryLocked(@NonNull final NgramContext ngramContext, final String word,
            final int frequency, final int timestamp) {
        if (!mBinaryDictionary.addNgramEntry(ngramContext, word, frequency, timestamp)) {
            if (DEBUG) {
                Log.i(TAG, "Cannot add n-gram entry.");
                Log.i(TAG, "  NgramContext");
            }
        }
    }

    /**
     * Update dictionary for the word with the ngramContext.
     */
    public void updateEntriesForWord(@NonNull final NgramContext ngramContext,
            final String word, final boolean isValidWord, final int count, final int timestamp) {
        updateDictionaryWithWriteLock(() -> {
            final BinaryDictionary binaryDictionary = getBinaryDictionary();
            if (binaryDictionary == null) {
                return;
            }
            if (!binaryDictionary.updateEntriesForWordWithNgramContext(ngramContext, word,
                    isValidWord, count, timestamp)) {
                if (DEBUG) {
                    Log.e(TAG, "Cannot update counter.");
                }
            }
        });
    }

    @Override
    public ArrayList<SuggestedWordInfo> getSuggestions(final ComposedData composedData,
            final NgramContext ngramContext, final long proximityInfoHandle,
            final SettingsValuesForSuggestion settingsValuesForSuggestion, final int sessionId,
            final float weightForLocale, final float[] inOutWeightOfLangModelVsSpatialModel) {
        reloadDictionaryIfRequired();
        boolean lockAcquired = false;
        try {
            lockAcquired = mLock.readLock().tryLock(
                    TIMEOUT_FOR_READ_OPS_IN_MILLISECONDS, TimeUnit.MILLISECONDS);
            if (lockAcquired) {
                if (mBinaryDictionary == null) {
                    return null;
                }
                final ArrayList<SuggestedWordInfo> suggestions =
                        mBinaryDictionary.getSuggestions(composedData, ngramContext,
                                proximityInfoHandle, settingsValuesForSuggestion, sessionId,
                                weightForLocale, inOutWeightOfLangModelVsSpatialModel);
                if (mBinaryDictionary.isCorrupted()) {
                    Log.i(TAG, "Dictionary is corrupted. Remove and regenerate it.");
                    removeBinaryDictionary();
                }
                return suggestions;
            }
        } catch (final InterruptedException e) {
            Log.e(TAG, "Interrupted tryLock in getSuggestionsWithSessionId.");
        } finally {
            if (lockAcquired) {
                mLock.readLock().unlock();
            }
        }
        return null;
    }

    @Override
    public boolean isInDictionary(final String word) {
        reloadDictionaryIfRequired();
        boolean lockAcquired = false;
        try {
            lockAcquired = mLock.readLock().tryLock(
                    TIMEOUT_FOR_READ_OPS_IN_MILLISECONDS, TimeUnit.MILLISECONDS);
            if (lockAcquired) {
                if (mBinaryDictionary == null) {
                    return false;
                }
                return isInDictionaryLocked(word);
            }
        } catch (final InterruptedException e) {
            Log.e(TAG, "Interrupted tryLock in isInDictionary.");
        } finally {
            if (lockAcquired) {
                mLock.readLock().unlock();
            }
        }
        return false;
    }

    protected boolean isInDictionaryLocked(final String word) {
        if (mBinaryDictionary == null) return false;
        return mBinaryDictionary.isInDictionary(word);
    }

    @Override
    public int getMaxFrequencyOfExactMatches(final String word) {
        reloadDictionaryIfRequired();
        boolean lockAcquired = false;
        try {
            lockAcquired = mLock.readLock().tryLock(
                    TIMEOUT_FOR_READ_OPS_IN_MILLISECONDS, TimeUnit.MILLISECONDS);
            if (lockAcquired) {
                if (mBinaryDictionary == null) {
                    return NOT_A_PROBABILITY;
                }
                return mBinaryDictionary.getMaxFrequencyOfExactMatches(word);
            }
        } catch (final InterruptedException e) {
            Log.e(TAG, "Interrupted tryLock in getMaxFrequencyOfExactMatches.");
        } finally {
            if (lockAcquired) {
                mLock.readLock().unlock();
            }
        }
        return NOT_A_PROBABILITY;
    }


    /**
     * Loads the current binary dictionary from internal storage. Assumes the dictionary file
     * exists.
     */
    void loadBinaryDictionaryLocked() {
        if (DBG_STRESS_TEST) {
            // Test if this class does not cause problems when it takes long time to load binary
            // dictionary.
            try {
                Log.w(TAG, "Start stress in loading");
                Thread.sleep(15000);
                Log.w(TAG, "End stress in loading");
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while loading");
            }
        }
        if (!usesStorage()) {
            createNewDictionaryLocked();
            return;
        }
        final LearnedLoad loaded = readStorage();
        final BinaryDictionary oldBinaryDictionary = mBinaryDictionary;
        if (!loaded.getAvailable()) {
            // The storage cannot be read now (device locked). Work on an empty dictionary that is never saved, and load
            // the real one when the storage can be read (isReloadRequired).
            mBinaryDictionary = newInMemoryDictionary();
            mStorageReady = false;
            mDirty = false;
            if (oldBinaryDictionary != null) oldBinaryDictionary.close();
            return;
        }
        BinaryDictionary dictionary = null;
        final byte[] bytes = loaded.getBytes();
        if (bytes != null) {
            dictionary = new BinaryDictionary(bytes, true /* useFullEditDistance */, mLocale, mDictType);
            if (!dictionary.isValidDictionary()
                    || !matchesExpectedBinaryDictFormatVersionForThisType(dictionary.getFormatVersion())) {
                // Decrypted but not a dictionary of this version: the learned words are lost (fail closed).
                Log.e(TAG, "Saved dictionary is not valid, starting empty");
                dictionary.close();
                dictionary = null;
                deleteStorage();
            }
        }
        mBinaryDictionary = dictionary != null ? dictionary : newInMemoryDictionary();
        mStorageReady = true;
        mDirty = false;
        if (oldBinaryDictionary != null) oldBinaryDictionary.close();
    }

    /**
     * Create a new binary dictionary and load initial contents.
     */
    void createNewDictionaryLocked() {
        removeBinaryDictionaryLocked();
        createOnMemoryBinaryDictionaryLocked();
        loadInitialContentsLocked();
        if (usesStorage()) {
            mStorageReady = isStorageAvailable();
            mDirty = true;
            flushToStorageLocked(true /* withGC */);
        }
    }

    /**
     * Marks that the dictionary needs to be recreated.
     *
     */
    protected void setNeedsToRecreate() {
        mNeedsToRecreate = true;
        mContentVersion.incrementAndGet(); // a copy of the words (glide) made before is stale from now on, not only after the recreation
    }

    void clearNeedsToRecreate() {
        mNeedsToRecreate = false;
    }

    boolean isNeededToRecreate() {
        return mNeedsToRecreate;
    }

    /**
     * Load the current binary dictionary from internal storage. If the dictionary file doesn't
     * exists or needs to be regenerated, the new dictionary file will be asynchronously generated.
     * However, the dictionary itself is accessible even before the new dictionary file is actually
     * generated. It may return a null result for getSuggestions() in that case by design.
     */
    public final void reloadDictionaryIfRequired() {
        if (!isReloadRequired()) return;
        asyncReloadDictionary();
    }

    /**
     * Returns whether a dictionary reload is required.
     */
    private boolean isReloadRequired() {
        return mBinaryDictionary == null || mNeedsToRecreate
                || (usesStorage() && !mStorageReady && isStorageAvailable());
    }

    /**
     * Reloads the dictionary. Access is controlled on a per dictionary file basis.
     */
    private void asyncReloadDictionary() {
        final AtomicBoolean isReloading = mIsReloading;
        if (!isReloading.compareAndSet(false, true)) {
            return;
        }
        asyncExecuteTaskWithWriteLock(() -> {
            try {
                if (isNeededToRecreate()) {
                    // contents have been updated, generate a new dictionary
                    createNewDictionaryLocked();
                } else if (getBinaryDictionary() == null || (usesStorage() && !mStorageReady)) {
                    // load the saved dictionary (or an empty one)
                    loadBinaryDictionaryLocked();
                }
                clearNeedsToRecreate();
            } finally {
                isReloading.set(false);
            }
        });
    }

    /**
     * Saves the dictionary through the storage hooks (after GC if it is due).
     */
    @Override
    public void onFinishInput() {
        asyncExecuteTaskWithWriteLock(() -> {
            final BinaryDictionary binaryDictionary = getBinaryDictionary();
            if (binaryDictionary == null) {
                return;
            }
            flushToStorageLocked(binaryDictionary.needsToRunGC(false /* mindsBlockByGC */));
        });
    }

    public DictionaryStats getDictionaryStats() {
        reloadDictionaryIfRequired();
        final String dictName = mDictName;
        final AsyncResultHolder<DictionaryStats> result =
                new AsyncResultHolder<>("DictionaryStats");
        asyncExecuteTaskWithLock(mLock.readLock(), new Runnable() {
            @Override
            public void run() {
                result.set(new DictionaryStats(mLocale, dictName, dictName, null /* no file */, 0));
            }
        });
        return result.get(null /* defaultValue */, TIMEOUT_FOR_READ_OPS_IN_MILLISECONDS);
    }

    public void dumpAllWordsForDebug() {
        reloadDictionaryIfRequired();
        final String tag = TAG;
        final String dictName = mDictName;
        asyncExecuteTaskWithLock(mLock.readLock(), () -> {
            Log.d(tag, "Dump dictionary: for");
            final BinaryDictionary binaryDictionary = getBinaryDictionary();
            if (binaryDictionary == null) {
                return;
            }
            try {
                final DictionaryHeader header = binaryDictionary.getHeader();
                Log.d(tag, "Format version");
                Log.d(tag, "dictionary attribute map");
            } catch (final UnsupportedFormatException e) {
                Log.d(tag, "Cannot fetch header information.");
            }
            int token = 0;
            do {
                final BinaryDictionary.GetNextWordPropertyResult result =
                        binaryDictionary.getNextWordProperty(token);
                final WordProperty wordProperty = result.mWordProperty;
                if (wordProperty == null) {
                    Log.d(tag, " dictionary is empty.");
                    break;
                }
                Log.d(tag, "word property");
                token = result.mNextToken;
            } while (token != 0);
        });
    }

    /**
     * Returns dictionary content required for syncing.
     */
    public WordProperty[] getWordPropertiesForSyncing() {
        reloadDictionaryIfRequired();
        final AsyncResultHolder<WordProperty[]> result =
                new AsyncResultHolder<>("WordPropertiesForSync");
        asyncExecuteTaskWithLock(mLock.readLock(), () -> {
            final ArrayList<WordProperty> wordPropertyList = new ArrayList<>();
            final BinaryDictionary binaryDictionary = getBinaryDictionary();
            if (binaryDictionary == null) {
                return;
            }
            int token = 0;
            do {
                // TODO: We need a new API that returns *new* un-synced data.
                final BinaryDictionary.GetNextWordPropertyResult nextWordPropertyResult =
                        binaryDictionary.getNextWordProperty(token);
                final WordProperty wordProperty = nextWordPropertyResult.mWordProperty;
                if (wordProperty == null) {
                    break;
                }
                wordPropertyList.add(wordProperty);
                token = nextWordPropertyResult.mNextToken;
            } while (token != 0);
            result.set(wordPropertyList.toArray(new WordProperty[0]));
        });
        // TODO: Figure out the best timeout duration for this API.
        return result.get(DEFAULT_WORD_PROPERTIES_FOR_SYNC, TIMEOUT_FOR_READ_OPS_IN_MILLISECONDS);
    }
}
