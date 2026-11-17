// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.latin.voice.VoiceModelSpec
import helium314.keyboard.latin.voice.VoiceModelStore
import helium314.keyboard.settings.InstallControl
import helium314.keyboard.settings.VoiceArchiveInstaller
import helium314.keyboard.settings.VoiceModelAccess
import helium314.keyboard.settings.deleteVoiceModel
import helium314.keyboard.settings.VoiceModelInstaller
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory
import helium314.keyboard.settings.preferences.SwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voice typing settings: the voice key switch and the voice model. The app has no network: "Download the model" hands the pinned address of the one
 * archive to the browser (each missing single file has its own "Download this file" as the fallback), and "Install downloaded model" takes whatever was
 * downloaded (the archive and/or any of the four files, in any round); every file is hashed against VoiceModelSpec before it is installed, the rows show which
 * of the four are in place, and the model is ready as soon as all are. An install shows its progress, can be cancelled and is stopped when the file provider
 * stalls. No microphone permission is asked for here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(onClickBack: () -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_screen_voice)) },
                windowInsets = WindowInsets(0),
                navigationIcon = { BackButton { onClickBack() } },
            )
            Column(Modifier.verticalScroll(rememberScrollState())) { VoiceContent() }
        }
    }
}

@Composable
private fun VoiceContent() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { VoiceModelStore(Privacy.voiceModelDir(ctx)) }
    var version by remember { mutableIntStateOf(0) }
    var control by remember { mutableStateOf<InstallControl?>(null) }
    var progress by remember { mutableStateOf<InstallControl.Progress?>(null) }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = remember(version) { store.status() }
    val missing = rows.count { !it.installed }
    val total = rows.size
    val archive = VoiceModelSpec.archive
    var showDownload by rememberSaveable { mutableStateOf(false) }
    var deleteCopies by rememberSaveable { mutableStateOf(true) }

    // progress for the screen and the stall watch: no byte for a minute stops the install (a document provider can block a read for ever)
    LaunchedEffect(control) {
        val c = control ?: return@LaunchedEffect
        while (true) {
            progress = c.progress
            if (!c.cancelled && c.stalledFor() > InstallControl.STALL_MILLIS) withContext(Dispatchers.IO) { c.stall() }
            delay(250)
        }
    }
    DisposableEffect(Unit) { onDispose { control?.cancel() } } // leaving the page ends a running install

    val installPicker = rememberLauncherForActivityResult(VoiceModelAccess.PickDownloads()) { uris ->
        if (uris.isEmpty() || control != null) return@rememberLauncherForActivityResult
        val c = InstallControl()
        control = c
        result = null
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                // a provider query and every read of the documents run here, never on the main thread
                VoiceModelInstaller(store).install(uris.map { VoiceModelAccess.source(ctx.contentResolver, it) }, deleteCopies, c)
            }
            val message = installMessage(ctx, report, c, deleteCopies)
            result = message
            control = null
            progress = null
            version++
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
        }
    }

    fun open(intent: Intent) {
        try {
            ctx.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, R.string.voice_download_no_browser, Toast.LENGTH_LONG).show()
        }
    }

    if (showDownload) {
        AlertDialog(
            onDismissRequest = { showDownload = false },
            title = { Text(stringResource(R.string.voice_download_title)) },
            text = { Text(stringResource(R.string.voice_download_body, archive.name, Formatter.formatShortFileSize(ctx, archive.size))) },
            confirmButton = {
                TextButton(onClick = {
                    showDownload = false
                    open(VoiceModelAccess.archiveIntent())
                }) { Text(stringResource(R.string.voice_download_open)) }
            },
            dismissButton = { TextButton(onClick = { showDownload = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    val running = control != null
    Text(stringResource(R.string.voice_intro), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    SwitchPreference(
        name = stringResource(R.string.voice_key_title),
        key = Settings.PREF_VOICE_TYPING,
        default = Defaults.PREF_VOICE_TYPING,
        description = stringResource(R.string.voice_key_summary),
        onCheckedChange = { KeyboardSwitcher.getInstance().setThemeNeedsReload() },
    )
    PreferenceCategory(stringResource(R.string.voice_model_title))
    Text(
        if (missing == 0) stringResource(R.string.voice_model_complete, total, total) else stringResource(R.string.voice_model_incomplete, total - missing, total),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    progress?.let { p -> InstallProgress(ctx, p, onCancel = { c -> scope.launch(Dispatchers.IO) { c.cancel() } }, control = control) }
    result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
    Preference(
        name = stringResource(R.string.voice_model_download),
        description = stringResource(R.string.voice_model_download_summary, Formatter.formatShortFileSize(ctx, archive.size)),
        onClick = { if (!running) showDownload = true },
    )
    Preference(
        name = stringResource(R.string.voice_model_install),
        description = stringResource(R.string.voice_model_install_summary),
        onClick = { if (!running) installPicker.launch(arrayOf("*/*")) },
    )
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.voice_install_delete_downloaded), modifier = Modifier.weight(1f))
        Switch(checked = deleteCopies, onCheckedChange = { deleteCopies = it })
    }
    PreferenceCategory(stringResource(R.string.voice_model_files_title))
    for (row in rows) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(
                    R.string.voice_file_row, row.entry.name, Formatter.formatShortFileSize(ctx, row.entry.size),
                    stringResource(if (row.installed) R.string.voice_file_installed else R.string.voice_file_missing),
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (!row.installed) TextButton(onClick = { if (!running) open(VoiceModelAccess.downloadIntent(row.entry)) }) { Text(stringResource(R.string.voice_file_download)) }
        }
    }
    if (rows.any { !it.installed && it.entry.name == VoiceModelSpec.TOKENS })
        Text(stringResource(R.string.voice_file_tokens_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    Preference(
        name = stringResource(R.string.voice_model_delete),
        description = stringResource(R.string.voice_model_delete_summary),
        onClick = {
            if (!running) scope.launch {
                deleteVoiceModel(store, Dispatchers.IO, ctx.getString(R.string.voice_model_delete_failed), showResult = { result = it }, reloadRows = { version++ })
            }
        },
    )
    Text(stringResource(R.string.voice_license_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
}

@Composable
private fun InstallProgress(ctx: Context, p: InstallControl.Progress, onCancel: (InstallControl) -> Unit, control: InstallControl?) {
    val label = when (p.label) {
        VoiceArchiveInstaller.STEP_INSTALLING -> stringResource(R.string.voice_install_from_archive)
        else -> stringResource(R.string.voice_model_importing, p.label)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (p.total > 0) {
            LinearProgressIndicator(progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            Text("${Formatter.formatShortFileSize(ctx, p.done)} / ${Formatter.formatShortFileSize(ctx, p.total)}", style = MaterialTheme.typography.bodySmall)
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }
        TextButton(onClick = { control?.let(onCancel) }) { Text(stringResource(R.string.voice_install_cancel)) }
    }
}

/** The text under the model rows after an install: per-document problems first, then what is installed and what is still missing. */
private fun installMessage(ctx: Context, report: VoiceModelInstaller.Report, control: InstallControl, deleted: Boolean): String {
    val lines = ArrayList<String>()
    if (control.stalled) lines.add(ctx.getString(R.string.voice_install_stalled))
    else if (report.outcomes.any { it.kind == VoiceModelInstaller.Kind.CANCELLED }) lines.add(ctx.getString(R.string.voice_install_cancelled))
    for (o in report.outcomes) {
        when (o.kind) {
            VoiceModelInstaller.Kind.NOT_A_MODEL_FILE -> lines.add(ctx.getString(R.string.voice_model_not_a_model, o.source))
            VoiceModelInstaller.Kind.NOT_THE_ARCHIVE -> lines.add(ctx.getString(R.string.voice_install_not_archive, o.source))
            VoiceModelInstaller.Kind.BAD_ARCHIVE -> lines.add(ctx.getString(R.string.voice_install_bad_archive, o.source, o.detail ?: "?"))
            VoiceModelInstaller.Kind.ENTRY_REJECTED -> lines.add(ctx.getString(R.string.voice_install_entry_rejected, o.source, o.detail ?: "?"))
            VoiceModelInstaller.Kind.NO_SPACE -> lines.add(ctx.getString(R.string.voice_model_no_space, o.source))
            VoiceModelInstaller.Kind.FAILED -> lines.add(ctx.getString(R.string.voice_model_failed, o.source))
            else -> Unit
        }
    }
    val installed = report.installed.distinct()
    val deletion = when {
        report.notDeleted.isNotEmpty() -> ctx.getString(R.string.voice_install_not_deleted, report.notDeleted.joinToString())
        deleted && installed.isNotEmpty() -> ctx.getString(R.string.voice_install_deleted)
        else -> ""
    }
    lines.add(
        when {
            report.complete -> ctx.getString(R.string.voice_install_complete, deletion).trim()
            installed.isNotEmpty() -> ctx.getString(R.string.voice_install_partial, installed.joinToString(), report.missing.joinToString()) + (if (deletion.isEmpty()) "" else " $deletion")
            else -> ctx.getString(R.string.voice_install_nothing, report.missing.joinToString())
        },
    )
    return lines.joinToString("\n")
}
