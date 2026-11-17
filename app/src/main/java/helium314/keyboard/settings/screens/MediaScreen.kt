// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.media.MediaItem
import helium314.keyboard.latin.media.MediaVault
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.rememberProviderJobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > GIFs and stickers: the offline collection of your own pictures. Import takes GIF, PNG, JPEG and WebP files from the picker (each document is read
 * through ProviderJobs, one at a time, 8 MiB at most) and stores them encrypted without metadata; each picture has up to 8 tags, the search narrows by tag, Delete
 * removes one, Delete all removes everything. While the phone is locked the page says so and does nothing; a damaged list offers Delete all only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaScreen(onClickBack: () -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_screen_media)) },
                windowInsets = WindowInsets(0),
                navigationIcon = { BackButton { onClickBack() } },
            )
            MediaContent()
        }
    }
}

@Composable
private fun MediaContent() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = remember { Privacy.mediaVault(ctx) }
    val jobs = rememberProviderJobs()
    var version by remember { mutableIntStateOf(0) }
    var listing by remember { mutableStateOf<MediaVault.Listing?>(null) }
    var query by remember { mutableStateOf("") } // never saved: a search text stays in memory only
    var tagEdit by remember { mutableStateOf<MediaItem?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(version) { listing = withContext(Dispatchers.IO) { vault.list() } }

    fun finishImport(added: Int, notes: List<String>) {
        result = (listOf(ctx.getString(R.string.media_import_done, added)) + notes).joinToString("\n")
        version++
    }

    fun reason(r: MediaVault.ImportResult): String = ctx.getString(when (r) {
        MediaVault.ImportResult.TooBig -> R.string.media_reason_too_big
        MediaVault.ImportResult.TooMany -> R.string.media_reason_too_many
        MediaVault.ImportResult.TotalTooBig -> R.string.media_reason_total
        MediaVault.ImportResult.UnknownFormat -> R.string.media_reason_format
        MediaVault.ImportResult.ParseError -> R.string.media_reason_damaged
        MediaVault.ImportResult.Locked -> R.string.media_reason_locked
        MediaVault.ImportResult.Damaged -> R.string.media_reason_list_damaged
        else -> R.string.media_reason_failed
    })

    fun importFrom(uris: List<Uri>, index: Int, added: Int, notes: List<String>) {
        if (index >= uris.size) { finishImport(added, notes); return }
        // one document at a time, read off the main thread with a size cap and a time limit (ProviderJobs); one more byte than the cap makes the vault refuse it
        jobs.read(uris[index], ctx, MediaVault.MAX_ITEM_BYTES.toLong() + 1) { failure, bytes, name ->
            val label = name ?: "?"
            if (failure != null || bytes == null) {
                importFrom(uris, index + 1, added, notes + ctx.getString(R.string.media_import_refused, label, ctx.getString(R.string.media_reason_read)))
            } else scope.launch {
                val outcome = withContext(Dispatchers.IO) { vault.import(bytes).also { bytes.fill(0) } }
                if (outcome is MediaVault.ImportResult.Added) importFrom(uris, index + 1, added + 1, notes)
                else importFrom(uris, index + 1, added, notes + ctx.getString(R.string.media_import_refused, label, reason(outcome)))
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { result = null; importFrom(uris, 0, 0, emptyList()) }
    }

    val current = listing
    val items = (current as? MediaVault.Listing.Items)?.items.orEmpty()
    val shown = MediaVault.search(items, query)

    LazyColumn(Modifier.fillMaxWidth()) {
        item { Text(stringResource(R.string.media_intro), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        item {
            SwitchPreference(
                name = stringResource(R.string.media_starter_title),
                key = Settings.PREF_MEDIA_STARTER_PACK,
                default = Defaults.PREF_MEDIA_STARTER_PACK,
                description = stringResource(R.string.media_starter_summary) + " " + stringResource(R.string.media_starter_gif_summary),
            )
        }
        when (current) {
            null -> Unit
            MediaVault.Listing.Locked -> item { Text(stringResource(R.string.media_locked), modifier = Modifier.padding(16.dp)) }
            MediaVault.Listing.Damaged -> {
                item { Text(stringResource(R.string.media_damaged), modifier = Modifier.padding(16.dp)) }
                item { Preference(name = stringResource(R.string.media_delete_all), description = stringResource(R.string.media_delete_all_summary), onClick = { confirmDeleteAll = true }) }
            }
            is MediaVault.Listing.Items -> {
                item { Preference(name = stringResource(R.string.media_import), description = stringResource(R.string.media_import_summary), onClick = { picker.launch(arrayOf("image/*")) }) }
                result?.let { text -> item { Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) } }
                item {
                    Text(
                        stringResource(R.string.media_usage, items.size, Formatter.formatShortFileSize(ctx, items.sumOf { it.size.toLong() }), Formatter.formatShortFileSize(ctx, MediaVault.MAX_TOTAL_BYTES)),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                item {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text(stringResource(R.string.media_search_hint)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
                items(shown, key = { it.id }) { item ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Thumbnail(vault, item)
                        Column(Modifier.weight(1f)) {
                            Text(if (item.tags.isEmpty()) stringResource(R.string.media_no_tags) else item.tags.joinToString(" "), style = MaterialTheme.typography.bodyMedium)
                            Text(Formatter.formatShortFileSize(ctx, item.size.toLong()), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { tagEdit = item }) { Text(stringResource(R.string.media_tags_edit)) }
                        TextButton(onClick = {
                            scope.launch { withContext(Dispatchers.IO) { vault.delete(item.id) }; version++ }
                        }) { Text(stringResource(R.string.delete)) }
                    }
                }
                if (items.isNotEmpty()) item { Preference(name = stringResource(R.string.media_delete_all), description = stringResource(R.string.media_delete_all_summary), onClick = { confirmDeleteAll = true }) }
            }
        }
    }

    tagEdit?.let { item ->
        var text by remember(item.id) { mutableStateOf(item.tags.joinToString(" ")) }
        AlertDialog(
            onDismissRequest = { tagEdit = null },
            title = { Text(stringResource(R.string.media_tags_edit)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(stringResource(R.string.media_tags_hint)) }) },
            confirmButton = {
                TextButton(onClick = {
                    val tags = text.split(' ', ',', '\n')
                    tagEdit = null
                    scope.launch { withContext(Dispatchers.IO) { vault.setTags(item.id, tags) }; version++ }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { tagEdit = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (confirmDeleteAll) {
        ConfirmationDialog(
            onDismissRequest = { confirmDeleteAll = false },
            onConfirmed = {
                confirmDeleteAll = false
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { vault.deleteAll() }
                    if (!ok) Toast.makeText(ctx, R.string.media_delete_failed, Toast.LENGTH_LONG).show()
                    version++
                }
            },
            title = { Text(stringResource(R.string.media_delete_all)) },
            content = { Text(stringResource(R.string.media_delete_all_confirm)) },
        )
    }
}

@Composable
private fun Thumbnail(vault: MediaVault, item: MediaItem) {
    val bitmap by produceState<ImageBitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) {
            val bytes = vault.read(item.id) ?: return@withContext null
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 160 && bounds.outHeight / (sample * 2) >= 160) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            } finally {
                bytes.fill(0)
            }
        }
    }
    val shown = bitmap
    if (shown != null) Image(shown, contentDescription = item.tags.joinToString(" "), modifier = Modifier.size(56.dp)) else Row(Modifier.size(56.dp)) {}
}
