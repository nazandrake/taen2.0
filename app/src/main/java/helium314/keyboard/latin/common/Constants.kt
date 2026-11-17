// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.common

object Links {
    const val DICTIONARY_URL = "https://codeberg.org/Helium314/aosp-dictionaries"
    const val DICTIONARY_DOWNLOAD_SUFFIX = "/raw/branch/main/"
    const val DICTIONARY_NORMAL_SUFFIX = "dictionaries/"
    const val DICTIONARY_EMOJI_CLDR_SUFFIX = "emoji_cldr_signal_dictionaries/"
    /** Taen's own repository: the About screen's GitHub and licence links and the layout formats document. */
    const val GITHUB = "https://github.com/nazandrake/taen2.0"
    const val LICENSE = "$GITHUB/blob/main/LICENSE"
    const val LAYOUTS_DOC = "$GITHUB/blob/main/layouts.md"
    /** HeliBoard, which Taen is built from: its community pages share layouts and colour themes in the formats Taen reads. */
    const val HELIBOARD = "https://github.com/HeliBorg/HeliBoard"
    const val CUSTOM_LAYOUTS = "$HELIBOARD/discussions/categories/custom-layout"
    const val CUSTOM_COLORS = "$HELIBOARD/discussions/categories/custom-colors"
    const val COMMUNITY_LINKS = "$HELIBOARD#to-community"
}

val combiningRange = 0x300..0x35b
