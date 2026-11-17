// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.compat.isDeviceLocked
import helium314.keyboard.compat.isUserLocked
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.profiles.Profile
import helium314.keyboard.latin.profiles.ProfileKeys
import helium314.keyboard.latin.profiles.ProfileList
import helium314.keyboard.latin.profiles.ProfileOutcome
import helium314.keyboard.latin.profiles.ProfileStore
import helium314.keyboard.latin.profiles.SettingChange
import helium314.keyboard.latin.profiles.Template
import helium314.keyboard.latin.profiles.TemplateApply
import helium314.keyboard.latin.profiles.TemplateCatalog
import helium314.keyboard.latin.profiles.TemplateEntry
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory

/** The store of the screen: the saved profiles in the credential-encrypted preferences, the settings in the device-protected ones (P21). */
private fun profileStore(ctx: Context) = ProfileStore(
    ce = ctx.protectedPrefs(),
    de = ctx.prefs(),
    locked = { Privacy.isLocked || isUserLocked(ctx) || isDeviceLocked(ctx) },
    extraKeys = { listOf(ProfileKeys.floatingKey(ctx.resources.displayMetrics.widthPixels)) },
)

private sealed interface Dialog {
    class ApplyTemplate(val template: Template, val changes: List<SettingChange>) : Dialog
    class ApplyProfile(val name: String, val changes: List<SettingChange>) : Dialog
    object Save : Dialog
    class Rename(val name: String) : Dialog
    class Delete(val name: String) : Dialog
    object DeleteAll : Dialog
}

private fun outcomeText(ctx: Context, outcome: ProfileOutcome, success: Int): String = ctx.getString(when (outcome) {
    ProfileOutcome.DONE -> success
    ProfileOutcome.LOCKED -> R.string.profiles_error_locked
    ProfileOutcome.DAMAGED -> R.string.profiles_error_damaged
    ProfileOutcome.PROFILE_DAMAGED -> R.string.profiles_error_profile_damaged
    ProfileOutcome.INVALID_NAME -> R.string.profiles_error_name
    ProfileOutcome.DUPLICATE_NAME -> R.string.profiles_error_duplicate
    ProfileOutcome.FULL -> R.string.profiles_error_full
    ProfileOutcome.TOO_LARGE -> R.string.profiles_error_too_large
    ProfileOutcome.NOT_FOUND -> R.string.profiles_error_not_found
    ProfileOutcome.WRITE_FAILED -> R.string.profiles_error_write
})

@SuppressLint("DiscouragedApi") // the template files name their strings; TemplateCatalogTest checks that every name resolves
private fun templateString(ctx: Context, name: String): String =
    ctx.resources.getIdentifier(name, "string", ctx.packageName).takeIf { it != 0 }?.let { ctx.getString(it) } ?: ""

private fun show(value: Any?, default: String) = value?.toString() ?: default

/**
 * Settings > Profiles and templates. Behind the app lock like every settings screen (SettingsActivity composes nothing until it is open). Templates come from
 * the APK; profiles are the user's own and live in the credential-encrypted preferences, so they are unavailable while the device is locked. Applying shows
 * the list of changes first. The names are user text: they are kept in memory only, never in saved state, and never logged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { profileStore(ctx) }
    var version by remember { mutableIntStateOf(0) }
    val list = remember(version) { store.list() }
    val templates = remember { TemplateCatalog.fromAssets(ctx.assets).load() }
    var message by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    fun done(text: String) { message = text; version++ }
    fun applied() { KeyboardSwitcher.getInstance().setThemeNeedsReload() }

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            TopAppBar(
                title = { Text(stringResource(R.string.profiles_title)) },
                windowInsets = WindowInsets(0),
                navigationIcon = { BackButton { onClickBack() } },
            )
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.profiles_intro), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }

                PreferenceCategory(stringResource(R.string.profiles_templates))
                for (entry in templates) when (entry) {
                    is TemplateEntry.Ok -> Preference(
                        name = templateString(ctx, entry.template.titleRes),
                        description = templateString(ctx, entry.template.summaryRes),
                        onClick = { dialog = Dialog.ApplyTemplate(entry.template, TemplateApply.plan(entry.template, ctx.prefs())) },
                    )
                    is TemplateEntry.Unavailable -> Text(stringResource(R.string.profiles_template_unavailable), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                }

                PreferenceCategory(stringResource(R.string.profiles_mine))
                when (list) {
                    ProfileList.Locked -> Text(stringResource(R.string.profiles_locked), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                    ProfileList.Damaged -> {
                        Text(stringResource(R.string.profiles_damaged), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                        Preference(name = stringResource(R.string.profiles_delete_all), onClick = { dialog = Dialog.DeleteAll })
                    }
                    is ProfileList.Ok -> {
                        if (list.profiles.isEmpty()) Text(stringResource(R.string.profiles_none), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                        for (p in list.profiles) ProfileRow(
                            p,
                            onApply = { dialog = store.plan(p.name)?.let { Dialog.ApplyProfile(p.name, it) } ?: run { done(ctx.getString(R.string.profiles_error_profile_damaged)); null } },
                            onRename = { dialog = Dialog.Rename(p.name) },
                            onDelete = { dialog = Dialog.Delete(p.name) },
                        )
                        Preference(name = stringResource(R.string.profiles_save_current), onClick = { dialog = Dialog.Save })
                        if (list.profiles.isNotEmpty()) Preference(name = stringResource(R.string.profiles_delete_all), onClick = { dialog = Dialog.DeleteAll })
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        is Dialog.ApplyTemplate -> ApplyDialog(
            title = templateString(ctx, d.template.titleRes), changes = d.changes, onDismiss = { dialog = null },
            onConfirm = { done(outcomeText(ctx, TemplateApply.apply(d.template, ctx.prefs()), R.string.profiles_applied)); applied() },
        )
        is Dialog.ApplyProfile -> ApplyDialog(
            title = d.name, changes = d.changes, onDismiss = { dialog = null },
            onConfirm = { done(outcomeText(ctx, store.apply(d.name), R.string.profiles_applied)); applied() },
        )
        Dialog.Save -> TextInputDialog(
            onDismissRequest = { dialog = null },
            onConfirmed = { done(outcomeText(ctx, store.saveCurrent(it), R.string.profiles_saved)) },
            title = { Text(stringResource(R.string.profiles_name_title)) },
            description = { Text(stringResource(R.string.profiles_name_info)) },
            keepInSavedState = false, // a profile name is never kept in saved state
            checkTextValid = { ProfileStore.nameOf(it) != null },
        )
        is Dialog.Rename -> TextInputDialog(
            onDismissRequest = { dialog = null },
            onConfirmed = { done(outcomeText(ctx, store.rename(d.name, it), R.string.profiles_renamed)) },
            title = { Text(stringResource(R.string.profiles_rename)) },
            description = { Text(stringResource(R.string.profiles_name_info)) },
            initialText = d.name,
            keepInSavedState = false, // a profile name is never kept in saved state
            checkTextValid = { ProfileStore.nameOf(it) != null },
        )
        is Dialog.Delete -> ConfirmationDialog(
            onDismissRequest = { dialog = null },
            onConfirmed = { done(outcomeText(ctx, store.delete(d.name), R.string.profiles_deleted)) },
            title = { Text(d.name) },
            confirmButtonText = stringResource(R.string.profiles_delete),
        )
        Dialog.DeleteAll -> ConfirmationDialog(
            onDismissRequest = { dialog = null },
            onConfirmed = { done(outcomeText(ctx, store.deleteAll(), R.string.profiles_deleted)) },
            title = { Text(stringResource(R.string.profiles_delete_all)) },
            content = { Text(stringResource(R.string.profiles_delete_all_confirm)) },
            confirmButtonText = stringResource(R.string.profiles_delete),
        )
    }
}

@Composable
private fun ProfileRow(profile: Profile, onApply: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            if (profile.damaged) "${profile.name} (${stringResource(R.string.profiles_profile_damaged)})" else profile.name,
            style = MaterialTheme.typography.bodyLarge,
        )
        Row {
            TextButton(onClick = onApply, enabled = !profile.damaged) { Text(stringResource(R.string.profiles_apply)) }
            TextButton(onClick = onRename) { Text(stringResource(R.string.profiles_rename)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.profiles_delete)) }
        }
    }
}

/** "Apply X?" with the list of settings that would change, shown before anything is written. */
@Composable
private fun ApplyDialog(title: String, changes: List<SettingChange>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val default = stringResource(R.string.profiles_default_value)
    ConfirmationDialog(
        onDismissRequest = onDismiss,
        onConfirmed = onConfirm,
        title = { Text(stringResource(R.string.profiles_changes_title, title)) },
        confirmButtonText = stringResource(R.string.profiles_apply),
        content = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(if (changes.isEmpty()) stringResource(R.string.profiles_changes_none) else stringResource(R.string.profiles_changes_count, changes.size), style = MaterialTheme.typography.bodyMedium)
                for (c in changes.take(MAX_LINES)) Text("${c.key}: ${show(c.old, default)} → ${show(c.new, default)}", style = MaterialTheme.typography.bodySmall)
                if (changes.size > MAX_LINES) Text("…", style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.profiles_privacy_kept), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
    )
}

private const val MAX_LINES = 60
