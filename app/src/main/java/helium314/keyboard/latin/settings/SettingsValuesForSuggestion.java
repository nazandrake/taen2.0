/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.settings;

public class SettingsValuesForSuggestion {
    public final boolean mBlockPotentiallyOffensive;
    /**
     * Whether the learned words and the personal dictionary may be a source of suggestions (typing and glide) in this
     * field. It is the decision of PrivacyPolicy ({@code Decision.getPersonalizedSuggestions}), not a setting of its own.
     */
    public final boolean mPersonalized;

    public SettingsValuesForSuggestion(
            final boolean blockPotentiallyOffensive,
            final boolean spaceAwareGesture,
            final boolean personalized
            ) {
        mBlockPotentiallyOffensive = blockPotentiallyOffensive;
        mSpaceAwareGesture = spaceAwareGesture;
        mPersonalized = personalized;
    }

    public final boolean mSpaceAwareGesture;
}
