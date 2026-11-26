// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.privacy.A11yWatch
import helium314.keyboard.latin.privacy.AppLock
import helium314.keyboard.latin.privacy.OverlayMode
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.privacy.PrivacyPolicy
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.latin.utils.protectedPrefs
import helium314.keyboard.latin.personalization.LearnedWords
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.settings.PinPad
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory
import helium314.keyboard.settings.preferences.SwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import helium314.keyboard.latin.utils.prefs

/** Privacy settings (design 7.4): incognito, screen capture, snooping warnings, app lock, and the status of what the keyboard can and cannot protect. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onClickBack: () -> Unit, onClickSelfDestruct: () -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_screen_privacy)) },
                windowInsets = WindowInsets(0),
                navigationIcon = { BackButton { onClickBack() } },
            )
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LearningSection()
                IncognitoSection()
                ScreenSection()
                ClipboardSection()
                AppLockSection()
                Preference(name = stringResource(R.string.self_destruct_title), description = stringResource(R.string.self_destruct_intro_short), onClick = onClickSelfDestruct)
                StatusSection()
            }
        }
    }
}

/**
 * What the keyboard learns from typing: the switch (the one key SettingsValues reads; turning it off asks first and deletes the learned words), the
 * list of learned and removed words, and the two delete actions. The settings are behind the app lock and kept off screenshots (SettingsActivity, SecureWindow).
 */
@Composable
private fun LearningSection() {
    PreferenceCategory(stringResource(R.string.privacy_category_learning))
    for (key in listOf(Settings.PREF_KEY_USE_PERSONALIZED_DICTS, SettingsWithoutKey.LEARNED_WORDS, SettingsWithoutKey.FORGET_LEARNED_DATA,
        SettingsWithoutKey.CLEAR_PERSONAL_DICTIONARY))
        SettingsActivity.settingsContainer[key]?.Preference()
}

/** The settings of the Learning section, registered in SettingsContainer so that the search finds them. */
fun createPrivacySettings(context: Context) = listOf(
    Setting(context, Settings.PREF_KEY_USE_PERSONALIZED_DICTS,
        R.string.use_personalized_dicts, R.string.use_personalized_dicts_summary
    ) { setting ->
        var showConfirmDialog by rememberSaveable { mutableStateOf(false) }
        SwitchPreference(setting, Defaults.PREF_KEY_USE_PERSONALIZED_DICTS,
            allowCheckedChange = {
                showConfirmDialog = !it
                it
            }
        )
        if (showConfirmDialog) {
            val prefs = LocalContext.current.prefs()
            ConfirmationDialog(
                onDismissRequest = { showConfirmDialog = false },
                onConfirmed = {
                    prefs.edit { putBoolean(setting.key, false) } // the keyboard deletes the learned words when it reads the setting
                },
                content = { Text(stringResource(R.string.disable_personalized_dicts_message)) }
            )
        }
    },
    Setting(context, SettingsWithoutKey.LEARNED_WORDS, R.string.learned_words_title, R.string.learned_words_summary) {
        Preference(name = it.title, description = it.description, onClick = { SettingsDestination.navigateTo(SettingsDestination.LearnedWords) }) { NextScreenIcon() }
    },
    Setting(context, SettingsWithoutKey.FORGET_LEARNED_DATA, R.string.forget_learned_title, R.string.forget_learned_summary) { setting ->
        DeleteAction(setting, stringResource(R.string.forget_learned_confirm), stringResource(R.string.forget_learned_done),
            stringResource(R.string.forget_learned_failed)) { ctx -> LearnedWords.forgetAll(ctx) }
    },
    Setting(context, SettingsWithoutKey.CLEAR_PERSONAL_DICTIONARY, R.string.clear_personal_title, R.string.clear_personal_summary) { setting ->
        DeleteAction(setting, stringResource(R.string.clear_personal_confirm), stringResource(R.string.clear_personal_done),
            stringResource(R.string.clear_personal_failed)) { ctx -> !Privacy.isLocked && Privacy.personalDictionary(ctx)?.replaceAll(emptyList()) == true }
    },
)

/** A row that deletes something after a confirmation; [delete] runs off the main thread and says whether everything was deleted. */
@Composable
private fun DeleteAction(setting: Setting, confirm: String, done: String, failed: String, delete: (Context) -> Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showConfirm by rememberSaveable { mutableStateOf(false) }
    Preference(name = setting.title, description = setting.description, onClick = { showConfirm = true })
    if (showConfirm) {
        ConfirmationDialog(
            onDismissRequest = { showConfirm = false },
            onConfirmed = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { delete(ctx) }
                    Toast.makeText(ctx, if (ok) done else failed, Toast.LENGTH_LONG).show()
                }
            },
            content = { Text(confirm) },
        )
    }
}

@Composable
private fun IncognitoSection() {
    val ctx = LocalContext.current
    PreferenceCategory(stringResource(R.string.privacy_category_incognito))
    SwitchPreference(
        name = stringResource(R.string.incognito),
        key = Settings.PREF_ALWAYS_INCOGNITO_MODE,
        default = Defaults.PREF_ALWAYS_INCOGNITO_MODE,
        description = stringResource(R.string.prefs_force_incognito_mode_summary),
        onCheckedChange = { KeyboardSwitcher.getInstance().setThemeNeedsReload() },
    )
    // the list is in the credential-encrypted preferences
    val appPrefs = ctx.protectedPrefs()
    var apps by remember { mutableStateOf(PrivacyPolicy.parseApps(appPrefs.getString(Settings.PREF_INCOGNITO_APPS, Defaults.PREF_INCOGNITO_APPS))) }
    var showDialog by rememberSaveable { mutableStateOf(false) }
    Preference(
        name = stringResource(R.string.incognito_apps_title),
        description = if (apps.isEmpty()) stringResource(R.string.incognito_apps_empty) else stringResource(R.string.incognito_apps_count, apps.size),
        onClick = { showDialog = true },
    )
    if (showDialog) {
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = { text ->
                apps = PrivacyPolicy.parseApps(text)
                Settings.setIncognitoApps(ctx, apps) // also forgets the per-app languages of these apps
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            },
            title = { Text(stringResource(R.string.incognito_apps_title)) },
            description = { Text(stringResource(R.string.incognito_apps_info, PrivacyPolicy.MAX_APPS)) },
            initialText = PrivacyPolicy.formatApps(apps),
            singleLine = false,
            checkTextValid = { true },
        )
    }
}

@Composable
private fun ClipboardSection() {
    PreferenceCategory(stringResource(R.string.privacy_category_clipboard))
    SwitchPreference(
        name = stringResource(R.string.clipboard_in_secure_fields_title),
        key = Settings.PREF_CLIPBOARD_IN_SECURE_FIELDS,
        default = Defaults.PREF_CLIPBOARD_IN_SECURE_FIELDS,
        description = stringResource(R.string.clipboard_in_secure_fields_summary),
        onCheckedChange = { KeyboardSwitcher.getInstance().setThemeNeedsReload() },
    )
}

@Composable
private fun ScreenSection() {
    val ctx = LocalContext.current
    PreferenceCategory(stringResource(R.string.privacy_category_screen))
    SwitchPreference(
        name = stringResource(R.string.secure_keyboard_window_title),
        key = Settings.PREF_SECURE_KEYBOARD_WINDOW,
        default = Defaults.PREF_SECURE_KEYBOARD_WINDOW,
        description = stringResource(R.string.secure_keyboard_window_summary),
    )
    SwitchPreference(
        name = stringResource(R.string.a11y_warning_title),
        key = Settings.PREF_A11Y_WARNING,
        default = Defaults.PREF_A11Y_WARNING,
        description = stringResource(R.string.a11y_warning_summary),
    )
    val prefs = ctx.prefs()
    var mode by remember { mutableStateOf(OverlayMode.parse(prefs.getString(Settings.PREF_OVERLAY_MODE, Defaults.PREF_OVERLAY_MODE))) }
    var showDialog by rememberSaveable { mutableStateOf(false) }
    @Composable fun label(m: OverlayMode) = stringResource(when (m) {
        OverlayMode.OFF -> R.string.overlay_mode_off
        OverlayMode.WARN -> R.string.overlay_mode_warn
        OverlayMode.BLOCK -> R.string.overlay_mode_block
    })
    Preference(name = stringResource(R.string.overlay_mode_title), description = label(mode), onClick = { showDialog = true })
    if (showDialog) {
        ListPickerDialog(
            onDismissRequest = { showDialog = false },
            items = OverlayMode.entries,
            selectedItem = mode,
            title = { Text(stringResource(R.string.overlay_mode_title)) },
            getItemName = { label(it) },
            onItemSelected = {
                mode = it
                prefs.edit { putString(Settings.PREF_OVERLAY_MODE, it.value) }
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            },
        )
    }
}

private enum class PinMode { SET, CHANGE, REMOVE }

@Composable
private fun AppLockSection() {
    val ctx = LocalContext.current
    val lock = remember { Privacy.appLock(ctx) }
    val scope = rememberCoroutineScope()
    // the settings are only open while the lock is, so reading the lock here shows its real state
    var version by remember { mutableIntStateOf(0) }
    var flow by rememberSaveable { mutableStateOf<String?>(null) }
    val enabled = version >= 0 && lock.isEnabled()
    PreferenceCategory(stringResource(R.string.app_lock_title))
    if (!enabled) {
        Preference(name = stringResource(R.string.app_lock_set), description = stringResource(R.string.app_lock_summary), onClick = { flow = PinMode.SET.name })
    } else {
        Preference(name = stringResource(R.string.app_lock_change), onClick = { flow = PinMode.CHANGE.name })
        Preference(name = stringResource(R.string.app_lock_remove), onClick = { flow = PinMode.REMOVE.name })
        Preference(
            name = stringResource(R.string.app_lock_timeout, lock.timeoutMinutes()),
            description = stringResource(R.string.app_lock_timeout_summary),
            onClick = {
                // the change waits for the lock of the record (a check of a PIN may hold it for a moment): AppLock refuses the main thread
                scope.launch {
                    withContext(Dispatchers.Default) { lock.cycleTimeoutMinutes() } // next value chosen and stored in one step
                    version++
                }
            },
        )
    }
    flow?.let {
        PinFlow(PinMode.valueOf(it), onDismiss = { flow = null }, onDone = { flow = null; version++ })
    }
}

private enum class PinStep { CURRENT, NEW, CONFIRM }

@Composable
private fun PinFlow(mode: PinMode, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val lock = remember { Privacy.appLock(ctx) }
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(if (mode == PinMode.SET) PinStep.NEW else PinStep.CURRENT) }
    var current by remember { mutableStateOf<CharArray?>(null) }
    var candidate by remember { mutableStateOf<CharArray?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val mismatch = stringResource(R.string.app_lock_mismatch)
    val wrong = stringResource(R.string.app_lock_wrong_current)
    val wait = stringResource(R.string.app_lock_wait_generic)
    val unavailable = stringResource(R.string.app_lock_unavailable)

    fun finish(result: AppLock.SetResult, pin: CharArray) {
        when (result) {
            AppLock.SetResult.OK -> onDone()
            AppLock.SetResult.WRONG_CURRENT -> { message = wrong; current = null; step = PinStep.CURRENT }
            AppLock.SetResult.WAIT -> { message = wait; current = null; step = PinStep.CURRENT }
            AppLock.SetResult.BAD_PIN, AppLock.SetResult.NOT_SAVED -> { message = unavailable; step = PinStep.NEW }
        }
        pin.fill('\u0000')
    }

    fun submit(pin: CharArray) {
        message = null
        when (step) {
            PinStep.CURRENT ->
                if (mode == PinMode.REMOVE) {
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.Default) { lock.disable(pin) }
                        busy = false
                        finish(result, pin)
                    }
                } else {
                    current = pin // checked together with the change
                    step = PinStep.NEW
                }
            PinStep.NEW -> { candidate = pin; step = PinStep.CONFIRM }
            PinStep.CONFIRM -> {
                if (!pin.contentEquals(candidate)) {
                    message = mismatch
                    pin.fill('\u0000')
                    candidate?.fill('\u0000')
                    step = PinStep.NEW
                    return
                }
                busy = true
                val old = current
                scope.launch {
                    val result = withContext(Dispatchers.Default) {
                        if (mode == PinMode.SET) lock.enable(pin).also { if (it == AppLock.SetResult.OK) lock.verify(pin) } // verify: the user stays unlocked
                        else lock.changePin(old ?: CharArray(0), pin)
                    }
                    busy = false
                    old?.fill('\u0000')
                    candidate?.fill('\u0000')
                    finish(result, pin)
                }
            }
        }
    }

    val title = stringResource(when (step) {
        PinStep.CURRENT -> R.string.app_lock_current_pin
        PinStep.NEW -> R.string.app_lock_new_pin
        PinStep.CONFIRM -> R.string.app_lock_confirm_pin
    })
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) { // no screenshot of the PIN pad
        Surface(shape = MaterialTheme.shapes.medium) {
            PinPad(title = title, message = message, enabled = !busy, onSubmit = ::submit, onCancel = onDismiss)
        }
    }
}

@Composable
private fun StatusSection() {
    val ctx = LocalContext.current
    PreferenceCategory(stringResource(R.string.privacy_category_status))
    StatusLine(stringResource(R.string.privacy_status_network))
    StatusLine(stringResource(R.string.privacy_status_backup))
    val risky = remember { A11yWatch.risky(A11yWatch.enabledServices(ctx)) }
    StatusLine(
        if (risky.isEmpty()) stringResource(R.string.privacy_status_a11y_none)
        else stringResource(R.string.privacy_status_a11y_some, risky.joinToString(", ") { it.label })
    )
    PreferenceCategory(stringResource(R.string.privacy_limits_title))
    StatusLine(stringResource(R.string.privacy_limits_text))
}

@Composable
private fun StatusLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}
