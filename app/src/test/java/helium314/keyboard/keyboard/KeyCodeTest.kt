// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Two keys with one code are one key: the listener takes the first branch that matches. */
class KeyCodeTest {
    private val codes = KeyCode::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && it.name != "INSTANCE" && !it.name.startsWith("$") } // $stable is a Compose compiler field, not a key
        .map { it.isAccessible = true; it.name to it.getInt(null) }

    @Test fun everyKeyCodeConstantHasItsOwnValue() {
        assertTrue(codes.size > 100, "the reflection found the constants, ${codes.size}")
        val clashes = codes.groupBy { it.second }.filter { it.value.size > 1 }.mapValues { e -> e.value.map { it.first } }
        assertEquals(emptyMap(), clashes)
    }

    @Test fun theQuickPanelAndF12AreDifferentCodesInTheAppRange() {
        assertTrue(KeyCode.QUICK_SETTINGS != KeyCode.F12)
        assertTrue(KeyCode.QUICK_SETTINGS in KeyCode.Spec.INTERNAL_HELI)
    }
}
