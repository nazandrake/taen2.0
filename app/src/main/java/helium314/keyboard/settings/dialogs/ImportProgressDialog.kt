// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R

/** Shown while FileUtils.importContentUri copies a file chosen in another app; Cancel (or Back) gives the import up. */
@Composable
fun ImportProgressDialog(onCancel: () -> Unit) {
    ThreeButtonAlertDialog(
        onDismissRequest = onCancel,
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.file_import_reading))
            }
        },
        confirmButtonText = null,
        onConfirmed = { },
    )
}
