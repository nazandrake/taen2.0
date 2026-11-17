// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.privacy.AppLock

/**
 * The digits typed so far, kept in one array that is zeroed when they are taken or dropped (a String of the PIN cannot be wiped, and
 * every prefix typed would leave another one).
 */
class PinDigits(private val max: Int) {
    private val chars = CharArray(max)
    var length = 0
        private set

    /** Adds [digit] unless the PIN has [max] digits already. */
    fun add(digit: Char): Boolean {
        if (length >= max) return false
        chars[length++] = digit
        return true
    }

    fun removeLast() {
        if (length > 0) chars[--length] = '\u0000'
    }

    /** The digits as a new array for the caller (who clears it); the pad is empty afterwards. */
    fun take(): CharArray = chars.copyOf(length).also { clear() }

    fun clear() {
        chars.fill('\u0000')
        length = 0
    }

    /** The backing array, for the test that it holds no digit after [clear] or [take]. */
    internal fun backing(): CharArray = chars
}

/**
 * A digit pad for the PIN: the digits are entered here and never reach an input method or another app, and the dots do not
 * show them. [message] (an error or hint) is shown under the dots. [onSubmit] gets the digits (a new array: the caller clears it), the
 * pad empties itself after a submit so a wrong PIN is typed again from the start.
 */
@Composable
fun PinPad(
    title: String,
    message: String?,
    enabled: Boolean,
    onSubmit: (CharArray) -> Unit,
    onCancel: () -> Unit,
    submitText: String = stringResource(android.R.string.ok),
) {
    val pad = remember { PinDigits(AppLock.MAX_PIN_LENGTH) }
    var count by remember { mutableIntStateOf(0) } // the digits are in [pad]; only their number is state
    DisposableEffect(pad) { onDispose { pad.clear() } }
    Column(
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        val shown = stringResource(R.string.app_lock_digits_entered, count)
        Text(
            "●".repeat(count).ifEmpty { " " },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { contentDescription = shown }, // the count, never the digits
            textAlign = TextAlign.Center,
        )
        Text(message ?: " ", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
        for (row in rows) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (d in row) DigitButton(d, enabled && count < AppLock.MAX_PIN_LENGTH) { pad.add(d[0]); count = pad.length }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pad.removeLast(); count = pad.length }, enabled = enabled && count > 0, modifier = Modifier.widthIn(min = 72.dp).height(56.dp)) { Text("⌫") }
            DigitButton("0", enabled && count < AppLock.MAX_PIN_LENGTH) { pad.add('0'); count = pad.length }
            Button(
                onClick = { val pin = pad.take(); count = 0; onSubmit(pin) },
                enabled = enabled && count >= AppLock.MIN_PIN_LENGTH,
                modifier = Modifier.widthIn(min = 72.dp).height(56.dp),
            ) { Text(submitText) }
        }
        TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
    }
}

@Composable
private fun DigitButton(digit: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.widthIn(min = 72.dp).height(56.dp)) {
        Text(digit, style = MaterialTheme.typography.titleLarge)
    }
}
