// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R

/**
 * The "What's new" box (see [WhatsNew]): the version in the title, the notes as plain text that scrolls, one OK button.
 * Both ways out (OK, back or a tap outside) end in [onDismiss], which stores the version.
 */
@Composable
fun WhatsNewDialog(notes: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.whats_new_title, BuildConfig.VERSION_NAME)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { Text(notes) } }, // the button stays on screen whatever the length
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.whats_new_ok)) } },
    )
}
