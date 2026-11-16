/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.define

import android.content.Context
import helium314.keyboard.latin.settings.DebugSettings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.utils.prefs

/**
 * Debug switch only. There is no crash handler, crash report or log buffer: any retained diagnostic can capture typed text
 * (exception messages carry it), so crashes go to the system's default handler (privacy invariant P11, enforced by privacyCheck).
 */
object DebugFlags {
    @JvmField
    var DEBUG_ENABLED = false

    fun init(context: Context) {
        DEBUG_ENABLED = context.prefs().getBoolean(DebugSettings.PREF_DEBUG_MODE, Defaults.PREF_DEBUG_MODE)
    }
}
