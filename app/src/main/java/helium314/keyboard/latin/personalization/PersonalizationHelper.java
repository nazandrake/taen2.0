/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.personalization;

import android.content.Context;
import helium314.keyboard.latin.utils.Log;

import androidx.annotation.NonNull;

import helium314.keyboard.latin.common.FileUtils;
import helium314.keyboard.latin.privacy.Privacy;
import helium314.keyboard.latin.utils.ExecutorUtils;

import java.io.File;
import java.io.FilenameFilter;
import java.lang.ref.SoftReference;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Helps handle and manage personalized dictionaries such as {@link UserHistoryDictionary}.
 */
public class PersonalizationHelper {
    private static final String TAG = PersonalizationHelper.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final ConcurrentHashMap<String, SoftReference<UserHistoryDictionary>>
            sLangUserHistoryDictCache = new ConcurrentHashMap<>();

    @NonNull
    public static UserHistoryDictionary getUserHistoryDictionary(final Context context, final Locale locale) {
        String lookupStr = locale.toString();
        synchronized (sLangUserHistoryDictCache) {
            if (sLangUserHistoryDictCache.containsKey(lookupStr)) {
                final SoftReference<UserHistoryDictionary> ref =
                        sLangUserHistoryDictCache.get(lookupStr);
                final UserHistoryDictionary dict = ref == null ? null : ref.get();
                if (dict != null) {
                    if (DEBUG) {
                        Log.d(TAG, "Use cached UserHistoryDictionary with lookup");
                    }
                    dict.reloadDictionaryIfRequired();
                    return dict;
                }
            }
            final UserHistoryDictionary dict = new UserHistoryDictionary(context, locale);
            sLangUserHistoryDictCache.put(lookupStr, new SoftReference<>(dict));
            moveLegacyDictionariesOnce(context);
            return dict;
        }
    }

    // true once no readable learned-word directory of an earlier version is left (it is tried again while any remains)
    private static boolean sLegacyMoved = false;

    private static void moveLegacyDictionariesOnce(final Context context) {
        if (sLegacyMoved) return;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute(() -> {
            if (UserHistoryDictionary.moveAllLegacyIntoStorage(app)) sLegacyMoved = true;
        });
    }

    /** After an import replaced the stored learned words: the running dictionaries forget their unsaved state and reload. */
    public static void discardAndReloadAll() {
        synchronized (sLangUserHistoryDictCache) {
            for (final ConcurrentHashMap.Entry<String, SoftReference<UserHistoryDictionary>> entry
                    : sLangUserHistoryDictCache.entrySet()) {
                final UserHistoryDictionary dict = entry.getValue() == null ? null : entry.getValue().get();
                if (dict != null) {
                    dict.discardUnsavedAndReload();
                }
            }
        }
    }

    /**
     * Screen lock: every running learned-word dictionary saves what it learned and drops its words from memory. Waits (at most
     * [timeoutMillis]) for the saves, so that they are done before the storage is closed; a save that is not done by then
     * finds the storage closed and is dropped.
     */
    public static void saveAndEvictAll(final long timeoutMillis) {
        final java.util.ArrayList<UserHistoryDictionary> dicts = new java.util.ArrayList<>();
        synchronized (sLangUserHistoryDictCache) {
            for (final SoftReference<UserHistoryDictionary> ref : sLangUserHistoryDictCache.values()) {
                final UserHistoryDictionary dict = ref == null ? null : ref.get();
                if (dict != null) dicts.add(dict);
            }
        }
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(dicts.size());
        for (final UserHistoryDictionary dict : dicts) dict.saveAndEvict(done);
        try {
            done.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void removeAllUserHistoryDictionaries(final Context context) {
        synchronized (sLangUserHistoryDictCache) {
            for (final ConcurrentHashMap.Entry<String, SoftReference<UserHistoryDictionary>> entry
                    : sLangUserHistoryDictCache.entrySet()) {
                if (entry.getValue() != null) {
                    final UserHistoryDictionary dict = entry.getValue().get();
                    if (dict != null) {
                        dict.clear();
                    }
                }
            }
            sLangUserHistoryDictCache.clear();
            Privacy.INSTANCE.deleteAllLearned(context);
            final File filesDir = context.getFilesDir();
            if (filesDir == null) {
                Log.e(TAG, "context.getFilesDir() returned null.");
                return;
            }
            final boolean filesDeleted = FileUtils.deleteFilteredFiles(
                    filesDir, new DictFilter(UserHistoryDictionary.NAME));
            if (!filesDeleted) {
                Log.e(TAG, "Cannot remove dictionary files. filesDir: , dictNamePrefix");
            }
        }
    }

    private static class DictFilter implements FilenameFilter {
        private final String mName;

        DictFilter(final String name) {
            mName = name;
        }

        @Override
        public boolean accept(final File dir, final String name) {
            return name.startsWith(mName);
        }
    }
}
