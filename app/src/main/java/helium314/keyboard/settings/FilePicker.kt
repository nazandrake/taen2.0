// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.dialogs.NewDictionaryDialog
import java.io.File
import java.util.Locale

val layoutIntent = Intent(Intent.ACTION_OPEN_DOCUMENT)
    .addCategory(Intent.CATEGORY_OPENABLE)
    .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "application/octet-stream", "application/json"))
    .setType("*/*")

/** A keyboard layout or colour file is text of a few KB; this is far above any real one and far below memory trouble. */
const val MAX_LAYOUT_FILE_BYTES = 4L * 1024 * 1024

@Composable
fun filePicker(onUri: (Uri) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = it.data?.data ?: return@rememberLauncherForActivityResult
        onUri(uri)
    }

@Composable
fun layoutFilePicker(
    onSuccess: (content: String, name: String?) -> Unit
): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    var errorDialog by remember { mutableStateOf(false) }
    val jobs = rememberProviderJobs()
    val loadFilePicker = filePicker { uri ->
        // the name and the text come from the provider off the main thread (size cap, time limit, cancel: ProviderJobs)
        jobs.read(uri, ctx, MAX_LAYOUT_FILE_BYTES) { failure, bytes, name ->
            if (failure != null || bytes == null) {
                errorDialog = true
                return@read
            }
            val content = String(bytes, Charsets.UTF_8)
            errorDialog = !LayoutUtilsCustom.checkLayout(content, ctx)
            if (!errorDialog)
                onSuccess(content, name)
        }
    }
    if (errorDialog)
        InfoDialog(stringResource(R.string.file_read_error)) { errorDialog = false }
    return loadFilePicker
}

@Composable
fun dictionaryFilePicker(mainLocale: Locale?): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    val cachedDictionaryFile = File(ctx.cacheDir?.path + File.separator + "temp_dict")
    var done by remember { mutableStateOf(false) }
    var errorDialog by remember { mutableStateOf(false) }
    val jobs = rememberProviderJobs()
    val picker = filePicker { uri ->
        cachedDictionaryFile.delete()
        // the copy runs in FileUtils (background, size cap, time limit, nothing left on failure); the result comes back here
        jobs.importFile(uri, ctx, cachedDictionaryFile) { failure -> if (failure == null) done = true else errorDialog = true }
    }
    if (errorDialog)
        InfoDialog(stringResource(R.string.file_read_error)) { errorDialog = false }
    if (done)
        NewDictionaryDialog(
            onDismissRequest = { done = false },
            cachedDictionaryFile,
            mainLocale
        )

    return picker
}
