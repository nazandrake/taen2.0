// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import helium314.keyboard.settings.dialogs.rememberTextInputState
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ProfilesScreen says a profile name is never kept in saved state. The text field state of the name dialog is
 * the one place where typed text would reach it (rememberTextFieldState saves it): the profile dialogs ask for keepInSavedState = false, which
 * keeps the text out of what the registry saves for the instance state.
 */
@RunWith(RobolectricTestRunner::class)
class TextInputSavedStateTest {
    private val secret = "MyProfileName7q"

    private fun saved(keepInSavedState: Boolean): Pair<TextFieldState, List<Any?>> {
        val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) {}
            override fun insertBottomUp(index: Int, instance: Unit) {}
            override fun remove(index: Int, count: Int) {}
            override fun move(from: Int, to: Int, count: Int) {}
            override fun onClear() {}
        }, Recomposer(EmptyCoroutineContext))
        var state: TextFieldState? = null
        composition.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                state = rememberTextInputState("", singleLine = true, keepInSavedState = keepInSavedState)
            }
        }
        val field = assertNotNull(state)
        field.setTextAndPlaceCursorAtEnd(secret)
        val values = registry.performSave().values.flatten()
        composition.dispose()
        return field to values
    }

    private fun contains(values: List<Any?>) = values.any { v -> flatten(v).any { it.toString().contains(secret) } }
    private fun flatten(v: Any?): List<Any?> = if (v is List<*>) v.flatMap { flatten(it) } else listOf(v)

    @Test fun byDefaultTheTypedTextIsKeptInSavedState() { // positive control: the saver runs and sees the text
        val (field, values) = saved(keepInSavedState = true)
        assertEquals(secret, field.text.toString())
        assertTrue(contains(values), values.toString())
    }

    @Test fun withKeepInSavedStateOffTheTypedTextIsNotInSavedState() {
        val (field, values) = saved(keepInSavedState = false)
        assertEquals(secret, field.text.toString()) // the field itself works
        assertTrue(!contains(values), values.toString())
    }

    @Test fun everyNameDialogOfTheProfilesScreenAsksForIt() { // the wiring: the screen's TextInputDialog calls (save, rename) both pass the flag
        val source = File("src/main/java/helium314/keyboard/settings/screens/ProfilesScreen.kt").readText()
        val calls = Regex("""TextInputDialog\((.*?)\n        \)""", RegexOption.DOT_MATCHES_ALL).findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(2, calls.size, "the save and the rename dialog")
        calls.forEach { assertTrue("keepInSavedState = false" in it, it) }
    }
}
