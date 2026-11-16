// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.ActivityNotFoundException
import android.content.Context
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.latin.downloads.DownloadEntry
import helium314.keyboard.latin.downloads.DownloadsInstaller
import helium314.keyboard.latin.downloads.DownloadsStore
import helium314.keyboard.latin.downloads.PinnedInstaller
import helium314.keyboard.latin.nextword.NextWordGate
import helium314.keyboard.latin.nextword.NextWordModels
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.DownloadAccess
import helium314.keyboard.settings.DownloadsWiring
import helium314.keyboard.settings.InstallControl
import helium314.keyboard.settings.VoiceModelAccess
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Settings > Downloads: one row per optional file of the catalogue (assets/catalog/downloads.tsv). The keyboard has no network: Download hands the pinned
 * address to the browser, "Install downloaded" lets the user pick the downloaded files (any name: the content decides, by the pinned size and SHA-256), and
 * Remove deletes the row's own file. The page shows each state, the size on disk, the total and the free space.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onClickBack: () -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_screen_downloads)) },
                windowInsets = WindowInsets(0),
                navigationIcon = { BackButton { onClickBack() } },
            )
            Column(Modifier.verticalScroll(rememberScrollState())) { DownloadsContent() }
        }
    }
}

@Composable
private fun DownloadsContent() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val wiring = remember { runCatching { DownloadsWiring(ctx) }.getOrNull() }
    if (wiring == null) { // a damaged catalogue offers nothing
        Text(stringResource(R.string.downloads_catalogue_damaged), modifier = Modifier.padding(16.dp))
        return
    }
    var version by remember { mutableIntStateOf(0) }
    var control by remember { mutableStateOf<InstallControl?>(null) }
    var progress by remember { mutableStateOf<InstallControl.Progress?>(null) }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    var refused by remember { mutableStateOf(emptySet<String>()) }
    var runningRow by remember { mutableStateOf(emptyMap<String, Int>()) }
    var overview by remember { mutableStateOf<DownloadsStore.Overview?>(null) }
    var deleteCopies by rememberSaveable { mutableStateOf(true) }
    val running = control != null

    // the states read the files and hash them: off the main thread
    LaunchedEffect(version, refused) {
        overview = withContext(Dispatchers.IO) { wiring.store.overview(emptyMap(), refused) }
    }
    LaunchedEffect(control) {
        val c = control ?: return@LaunchedEffect
        while (true) {
            progress = c.progress
            val p = c.progress
            runningRow = if (wiring.entries.any { it.id == p.label }) mapOf(p.label to if (p.total > 0) (p.done * 100 / p.total).toInt().coerceIn(0, 100) else 0) else emptyMap()
            if (!c.cancelled && c.stalledFor() > InstallControl.STALL_MILLIS) withContext(Dispatchers.IO) { c.stall() }
            delay(250)
        }
    }
    DisposableEffect(Unit) { onDispose { control?.cancel() } }

    val picker = rememberLauncherForActivityResult(VoiceModelAccess.PickDownloads()) { uris ->
        if (uris.isEmpty() || control != null) return@rememberLauncherForActivityResult
        val c = InstallControl()
        control = c
        result = null
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                val sources = uris.map { uri ->
                    val doc = VoiceModelAccess.source(ctx.contentResolver, uri)
                    object : DownloadsInstaller.Source {
                        override val name = doc.name
                        override fun open() = doc.open()?.also { c.reading(it) }
                        override fun delete() = doc.delete()
                    }
                }
                c.step(sources.firstOrNull()?.name ?: "", wiring.entries.maxOf { it.size } * 2)
                wiring.installer().install(sources, deleteCopies, PinnedInstaller.Hooks(c.hooks.cancelled, c.hooks.onBytes)) { entry -> c.step(entry.id, entry.size) }
            }
            c.reading(null)
            runningRow = emptyMap()
            refused = report.outcomes.filter { it.kind == DownloadsInstaller.Kind.WRONG_FILE }.mapNotNull { it.entry?.id }.toSet()
            val message = installMessage(ctx, report, c, deleteCopies)
            result = message
            control = null
            progress = null
            version++
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
        }
    }

    fun open(entry: DownloadEntry) {
        try {
            ctx.startActivity(DownloadAccess.browserIntent(entry.url))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, R.string.voice_download_no_browser, Toast.LENGTH_LONG).show()
        }
    }

    Text(stringResource(R.string.downloads_intro), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    overview?.let { o ->
        Text(
            stringResource(R.string.downloads_totals, Formatter.formatShortFileSize(ctx, o.totalBytes), Formatter.formatShortFileSize(ctx, o.freeBytes)),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    progress?.let { p ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (p.total > 0) LinearProgressIndicator(progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            TextButton(onClick = { control?.let { c -> scope.launch(Dispatchers.IO) { c.cancel() } } }) { Text(stringResource(R.string.voice_install_cancel)) }
        }
    }
    result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
    Preference(
        name = stringResource(R.string.downloads_install),
        description = stringResource(R.string.downloads_install_summary),
        onClick = { if (!running) picker.launch(arrayOf("*/*")) },
    )
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.voice_install_delete_downloaded), modifier = Modifier.weight(1f))
        Switch(checked = deleteCopies, onCheckedChange = { deleteCopies = it })
    }
    PreferenceCategory(stringResource(R.string.downloads_files_title))
    for (disk in overview?.rows.orEmpty()) {
        val row = runningRow[disk.entry.id]?.let { DownloadsStore.Row(disk.entry, DownloadsStore.State.INSTALLING, it, disk.bytesOnDisk) } ?: disk
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(rowName(ctx, row.entry), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.downloads_licence, row.entry.licence), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.downloads_sizes, Formatter.formatShortFileSize(ctx, row.entry.size), Formatter.formatShortFileSize(ctx, row.bytesOnDisk)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(stateText(row), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { if (!running) open(row.entry) }) { Text(stringResource(R.string.downloads_button_download)) }
                if (row.state == DownloadsStore.State.VERIFIED || row.state == DownloadsStore.State.DAMAGED)
                    TextButton(onClick = {
                        if (!running) scope.launch {
                            val ok = withContext(Dispatchers.IO) { wiring.remove(row.entry) }
                            result = if (ok) null else ctx.getString(R.string.downloads_remove_failed)
                            version++
                        }
                    }) { Text(stringResource(R.string.downloads_button_remove)) }
            }
        }
    }
    NextWordSection(wiring, overview, running) { version++ }
    Preference(
        name = stringResource(R.string.downloads_remove_all),
        description = stringResource(R.string.downloads_remove_all_summary),
        onClick = {
            if (!running) scope.launch {
                val failed = withContext(Dispatchers.IO) { wiring.removeAll() }
                result = if (failed.isEmpty()) null else ctx.getString(R.string.downloads_remove_failed)
                version++
            }
        },
    )
}

private fun rowName(ctx: Context, entry: DownloadEntry): String {
    val language = Locale.forLanguageTag(entry.locale).localizedDisplayName(ctx.resources)
    return ctx.getString(if (entry.kind == DownloadEntry.MODEL) R.string.downloads_model_name else R.string.downloads_dictionary_name, language)
}

/**
 * The next-word models: the model switch, and per model what NextWordGate decides on this phone with the measured numbers, the speed test (every model
 * is checked on each phone), whether there is memory for it right now, and, when it fails here, Remove.
 */
@Composable
private fun NextWordSection(wiring: DownloadsWiring, overview: DownloadsStore.Overview?, busy: Boolean, changed: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val models = wiring.entries.filter { it.kind == DownloadEntry.MODEL }
    if (models.isEmpty()) return
    var on by remember { mutableStateOf(ctx.prefs().getBoolean(Settings.PREF_NEXTWORD_MODEL, Defaults.PREF_NEXTWORD_MODEL)) }
    var testing by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf(0 to 0) }
    var failed by remember { mutableStateOf(false) }
    var cancel by remember { mutableStateOf(false) }
    var speeds by remember { mutableStateOf(emptyMap<String, NextWordGate.Speed?>()) }
    var crashed by remember { mutableStateOf(emptySet<String>()) }
    var freeNow by remember { mutableStateOf(-1L) }
    LaunchedEffect(overview, testing) {
        withContext(Dispatchers.IO) {
            speeds = models.associate { it.id to wiring.models.speed(it.id) }
            crashed = models.filter { wiring.models.loadCrashed(it.id) }.map { it.id }.toSet()
            freeNow = wiring.freeMbNow()
        }
    }
    PreferenceCategory(stringResource(R.string.nextword_title))
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.nextword_switch), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.nextword_switch_summary), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = on, onCheckedChange = { value ->
            on = value
            ctx.prefs().edit { putBoolean(Settings.PREF_NEXTWORD_MODEL, value) }
        })
    }
    for (entry in models) {
        val model = NextWordModels.byId(entry.id) ?: continue
        val installed = overview?.rows?.firstOrNull { it.entry.id == entry.id }?.state == DownloadsStore.State.VERIFIED
        val speed = speeds[entry.id]
        val readiness = NextWordGate.readiness(model, installed, speed, entry.id in crashed)
        val name = rowName(ctx, entry)
        val ref = model.reference
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            val text = when (readiness) {
                NextWordGate.Readiness.NOT_INSTALLED -> stringResource(R.string.nextword_state_not_installed, name, percent(ref.modelPerMille), percent(ref.dictionaryPerMille),
                    ref.referenceP95Ms.toString(), ref.referenceExtraMb.toString(), model.maxP95Ms.toString(), NextWordGate.MARGIN_MB.toString())
                NextWordGate.Readiness.DISABLED_BY_GATE -> stringResource(R.string.nextword_state_disabled, name, percent(ref.modelPerMille), percent(ref.dictionaryPerMille))
                NextWordGate.Readiness.NEEDS_SPEED_TEST -> stringResource(R.string.nextword_state_needs_test, name, model.maxP95Ms.toString(), NextWordGate.MARGIN_MB.toString())
                NextWordGate.Readiness.LOAD_CRASHED -> stringResource(R.string.nextword_state_crashed, name)
                NextWordGate.Readiness.TOO_SLOW -> if (speed!!.enoughMemory)
                    stringResource(R.string.nextword_state_too_slow, name, speed.p95Ms.toString(), model.maxP95Ms.toString())
                else stringResource(R.string.nextword_state_low_memory, name, speed.extraMb.toString(), NextWordGate.MARGIN_MB.toString(), speed.freeMb.toString())
                NextWordGate.Readiness.READY -> stringResource(R.string.nextword_state_ready, name, percent(ref.modelPerMille), percent(ref.dictionaryPerMille))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (speed != null && readiness == NextWordGate.Readiness.READY) {
                Text(stringResource(R.string.nextword_state_speed, speed.p95Ms.toString(), speed.extraMb.toString()), style = MaterialTheme.typography.bodySmall)
                if (!NextWordGate.mayLoadNow(speed, freeNow))
                    Text(stringResource(R.string.nextword_state_low_memory_now, speed.extraMb.toString(), NextWordGate.MARGIN_MB.toString(), freeNow.coerceAtLeast(0).toString()),
                        style = MaterialTheme.typography.bodySmall)
            }
            if (testing == entry.id) {
                Text(stringResource(R.string.nextword_testing, step.first, step.second), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { if (step.second > 0) step.first.toFloat() / step.second else 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                TextButton(onClick = { cancel = true }) { Text(stringResource(R.string.voice_install_cancel)) }
            }
            if (failed && testing == null && readiness == NextWordGate.Readiness.NEEDS_SPEED_TEST) Text(stringResource(R.string.nextword_test_failed), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (installed && testing == null && readiness != NextWordGate.Readiness.DISABLED_BY_GATE)
                    TextButton(onClick = {
                        if (!busy) {
                            testing = entry.id
                            cancel = false
                            failed = false
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { wiring.speedTest(model, { cancel }) { done, all -> step = done to all } }
                                failed = !ok && !cancel
                                testing = null
                                changed()
                            }
                        }
                    }) { Text(stringResource(R.string.nextword_run_test)) }
                if (readiness == NextWordGate.Readiness.TOO_SLOW)
                    TextButton(onClick = {
                        if (!busy && testing == null) scope.launch {
                            withContext(Dispatchers.IO) { wiring.remove(entry) }
                            changed()
                        }
                    }) { Text(stringResource(R.string.downloads_button_remove)) }
            }
        }
    }
}

private fun percent(perMille: Int) = String.format(Locale.ROOT, "%.1f", perMille / 10.0)

@Composable
private fun stateText(row: DownloadsStore.Row): String = when (row.state) {
    DownloadsStore.State.NOT_INSTALLED -> stringResource(R.string.downloads_state_not_installed)
    DownloadsStore.State.INSTALLING -> stringResource(R.string.downloads_state_installing, row.percent)
    DownloadsStore.State.VERIFIED -> stringResource(R.string.downloads_state_verified)
    DownloadsStore.State.WRONG_FILE -> stringResource(R.string.downloads_state_wrong)
    DownloadsStore.State.DAMAGED -> stringResource(R.string.downloads_state_damaged)
}

/** One line per document that did not install, then what was installed. */
private fun installMessage(ctx: Context, report: DownloadsInstaller.Report, control: InstallControl, deleted: Boolean): String {
    val lines = ArrayList<String>()
    if (control.stalled) lines.add(ctx.getString(R.string.voice_install_stalled))
    for (o in report.outcomes) {
        when (o.kind) {
            DownloadsInstaller.Kind.INSTALLED -> lines.add(ctx.getString(R.string.downloads_result_installed, o.source))
            DownloadsInstaller.Kind.NOT_A_DOWNLOAD -> lines.add(ctx.getString(R.string.downloads_result_not_listed, o.source))
            DownloadsInstaller.Kind.WRONG_FILE -> lines.add(ctx.getString(R.string.downloads_result_wrong, o.source))
            DownloadsInstaller.Kind.REJECTED -> lines.add(ctx.getString(R.string.downloads_result_rejected, o.source))
            DownloadsInstaller.Kind.CONFLICT -> lines.add(ctx.getString(R.string.downloads_result_conflict, o.source))
            DownloadsInstaller.Kind.NO_SPACE -> lines.add(ctx.getString(R.string.voice_model_no_space, o.source))
            DownloadsInstaller.Kind.FAILED, DownloadsInstaller.Kind.BUSY -> lines.add(ctx.getString(R.string.voice_model_failed, o.source))
            DownloadsInstaller.Kind.CANCELLED -> if (!control.stalled) lines.add(ctx.getString(R.string.voice_install_cancelled))
        }
    }
    if (report.notDeleted.isNotEmpty()) lines.add(ctx.getString(R.string.voice_install_not_deleted, report.notDeleted.joinToString()))
    else if (deleted && report.outcomes.any { it.kind == DownloadsInstaller.Kind.INSTALLED }) lines.add(ctx.getString(R.string.voice_install_deleted))
    return lines.joinToString("\n")
}
