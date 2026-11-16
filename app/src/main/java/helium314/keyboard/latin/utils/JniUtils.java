/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.utils;

/**
 * Loads the native library that is built from the sources in this repository (app/src/main/jni).
 * Taen never loads any other native code: no user-supplied library, no closed-source gesture library,
 * nothing outside the APK. The check for this is the dex scan of the release APK (privacy/dex-rules.txt).
 */
public final class JniUtils {
    private static final String TAG = JniUtils.class.getSimpleName();
    public static final String JNI_LIB_NAME = "jni_latinime";

    /**
     * Whether a gesture typing engine is available: the open-source glide engine in latin/gesture, which is
     * ordinary Kotlin code of this app (no native library, no model file). The gesture typing settings show
     * when it is true; whether glide typing is on is the user's choice (Defaults.PREF_GESTURE_INPUT).
     */
    public static boolean sHaveGestureLib = true;

    static {
        try {
            System.loadLibrary(JNI_LIB_NAME);
        } catch (UnsatisfiedLinkError ul) {
            Log.w(TAG, "Could not load native library");
        }
    }

    private JniUtils() {
        // This utility class is not publicly instantiable.
    }

    public static void loadNativeLibrary() {
        // Ensures the static initializer is called
    }
}
