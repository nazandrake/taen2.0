// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.Settings
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Select all and clear all are toolbar keys: on in the default set, with their own codes. */
@RunWith(RobolectricTestRunner::class)
class FieldKeysTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun selectAllAndClearAllAreOnInTheDefaultToolbar() {
        context.prefs().edit { clear() }
        val keys = getEnabledToolbarKeys(context.prefs())
        assertTrue(ToolbarKey.SELECT_ALL in keys, "$keys")
        assertTrue(ToolbarKey.CLEAR_ALL in keys, "$keys")
        assertTrue(ToolbarKey.PASTE in keys) // control: the keys that were there stay
        assertFalse(ToolbarKey.CLEAR_CLIPBOARD in keys) // the clipboard history key is another key
    }

    @Test fun theKeysSendTheirOwnCodes() {
        assertEquals(KeyCode.CLIPBOARD_SELECT_ALL, getCodeForToolbarKey(ToolbarKey.SELECT_ALL))
        assertEquals(KeyCode.CLEAR_ALL, getCodeForToolbarKey(ToolbarKey.CLEAR_ALL))
        assertEquals(KeyCode.CLIPBOARD_PASTE, getCodeForToolbarKey(ToolbarKey.PASTE))
    }

    @Test fun aKeyThatIsNewToAnExistingPreferenceStartsAsItsDefaultSays() {
        fun without(pref: String, key: String) = pref.split(Separators.ENTRY).filterNot { it.startsWith(key + Separators.KV) }.joinToString(Separators.ENTRY)
        val prefs = context.prefs()
        // an installation from before the key existed: no CLEAR_ALL anywhere, and select all stored as off
        prefs.edit {
            putString(Settings.PREF_TOOLBAR_KEYS, without(defaultToolbarPref, "CLEAR_ALL").replace("SELECT_ALL:true", "SELECT_ALL:false"))
            putString(Settings.PREF_PINNED_TOOLBAR_KEYS, without(defaultPinnedToolbarPref, "CLEAR_ALL"))
        }
        assertFalse(ToolbarKey.CLEAR_ALL in getEnabledToolbarKeys(prefs), "control: the key is missing before the upgrade")
        upgradeToolbarPrefs(prefs)
        assertTrue(ToolbarKey.CLEAR_ALL in getEnabledToolbarKeys(prefs), "on in the toolbar, as the toolbar default says")
        assertFalse(ToolbarKey.CLEAR_ALL in getPinnedToolbarKeys(prefs), "off among the pinned keys, as their default says")
        assertFalse(ToolbarKey.SELECT_ALL in getEnabledToolbarKeys(prefs), "a stored choice is kept")
        assertTrue(ToolbarKey.VOICE in getPinnedToolbarKeys(prefs))
    }
}
