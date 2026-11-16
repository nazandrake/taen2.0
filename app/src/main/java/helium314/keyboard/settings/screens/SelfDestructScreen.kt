// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import helium314.keyboard.latin.DictionaryFacilitatorProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.privacy.ConfigLoad
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.privacy.SelfDestruct
import helium314.keyboard.latin.privacy.SelfDestructConfig
import helium314.keyboard.latin.privacy.SelfDestructPolicy
import helium314.keyboard.latin.privacy.SelfDestructSettings.Change
import helium314.keyboard.latin.privacy.TriggerMode
import helium314.keyboard.latin.privacy.TriggerWordRules
import helium314.keyboard.latin.privacy.WipeScope
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.settings.PinPad
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HOLD_MILLIS = 3000L
private val WRONG_PIN_CHOICES = listOf(0) + (5..20).toList()
private val INACTIVITY_CHOICES = listOf(0, 3, 7, 14, 30, 60, 90, 180, 365)

/**
 * Self-destruct settings: what starts a wipe, what a wipe removes and how it is guarded against false triggers.
 * Reached only through the app lock like every settings screen. Everything is off by default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfDestructScreen(onClickBack: () -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            TopAppBar(
                title = { Text(stringResource(R.string.self_destruct_title)) },
                windowInsets = WindowInsets(0),
                navigationIcon = { BackButton { onClickBack() } },
            )
            Column(Modifier.verticalScroll(rememberScrollState())) { SelfDestructContent() }
        }
    }
}

@Composable
private fun SelfDestructContent() {
    val ctx = LocalContext.current
    val settings = remember { SelfDestruct.settings(ctx) }
    val lock = remember { Privacy.appLock(ctx) }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val load = remember(version) { SelfDestruct.configs(ctx).load() }
    val config = (load as? ConfigLoad.Present)?.config ?: if (load is ConfigLoad.None) SelfDestructConfig.DEFAULT else null
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }

    fun messageFor(c: Change): Int = when (c) {
        Change.OK -> 0
        Change.BAD_PIN -> R.string.self_destruct_err_pin
        Change.NEEDS_APP_LOCK -> R.string.self_destruct_needs_lock
        Change.SAME_AS_APP_PIN -> R.string.self_destruct_err_same_pin
        Change.OUT_OF_RANGE -> R.string.self_destruct_err_range
        Change.NOT_SAVED -> R.string.self_destruct_err_not_saved
        Change.WORD_TOO_SHORT -> R.string.self_destruct_err_word_short
        Change.WORD_TOO_LONG -> R.string.self_destruct_err_word_long
        Change.WORD_WHITESPACE -> R.string.self_destruct_err_word_space
        Change.WORD_SEPARATOR -> R.string.self_destruct_err_word_separator
        Change.WORD_NEEDS_DIGIT_OR_SYMBOL -> R.string.self_destruct_err_word_digit
        Change.WORD_KNOWN -> R.string.self_destruct_err_word_known
    }

    /** Runs a change (Argon2id takes a few hundred milliseconds: not on the main thread), tells the user when it was refused, refreshes the screen. */
    fun change(block: () -> Change) {
        scope.launch {
            val result = withContext(Dispatchers.Default) { block() }
            if (result != Change.OK) Toast.makeText(ctx, messageFor(result), Toast.LENGTH_LONG).show()
            SelfDestruct.syncInactivityJob(ctx)
            version++
        }
    }

    if (config == null) {
        Text(stringResource(R.string.self_destruct_unreadable), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        return
    }
    val lockOn = lock.isEnabled()
    val rehearsing = config.rehearsalUntil - System.currentTimeMillis() in 1..SelfDestructPolicy.REHEARSAL_MILLIS

    Text(stringResource(R.string.self_destruct_intro), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

    // ---- rehearsal ---------------------------------------------------------------------------------------------------
    PreferenceCategory(stringResource(R.string.self_destruct_rehearsal_title))
    Preference(
        name = stringResource(if (rehearsing) R.string.self_destruct_rehearsal_stop else R.string.self_destruct_rehearsal_start),
        description = stringResource(R.string.self_destruct_rehearsal_summary),
        onClick = { change { if (rehearsing) settings.stopRehearsal() else settings.startRehearsal() } },
    )

    // ---- duress PIN --------------------------------------------------------------------------------------------------
    PreferenceCategory(stringResource(R.string.self_destruct_duress_title))
    if (!lockOn) {
        Preference(name = stringResource(R.string.self_destruct_duress_set), description = stringResource(R.string.self_destruct_needs_lock),
            onClick = { Toast.makeText(ctx, R.string.self_destruct_needs_lock, Toast.LENGTH_LONG).show() })
    } else {
        Preference(
            name = stringResource(if (config.duress == null) R.string.self_destruct_duress_set else R.string.self_destruct_duress_change),
            description = stringResource(R.string.self_destruct_duress_summary),
            onClick = { dialog = "duress" },
        )
        if (config.duress != null) {
            Preference(name = stringResource(R.string.self_destruct_duress_remove), onClick = { change { settings.removeDuressPin() } })
            ModePreference(R.string.self_destruct_duress_mode, config.duressMode) { m -> change { settings.setDuressMode(m) } }
        }
    }

    // ---- trigger word ------------------------------------------------------------------------------------------------
    PreferenceCategory(stringResource(R.string.self_destruct_word_title))
    Preference(
        name = stringResource(if (config.word == null) R.string.self_destruct_word_set else R.string.self_destruct_word_change),
        description = stringResource(R.string.self_destruct_word_summary),
        onClick = { dialog = "word" },
    )
    if (config.word != null) {
        Preference(name = stringResource(R.string.self_destruct_word_remove), onClick = { change { settings.removeTriggerWord() } })
        ModePreference(R.string.self_destruct_word_mode, config.wordMode) { m -> change { settings.setWordMode(m) } }
        OnOffPreference(R.string.self_destruct_space_key, R.string.self_destruct_space_key_summary, config.commitOnSpace) { on -> change { settings.setCommitOnSpace(on) } }
        OnOffPreference(R.string.self_destruct_hold_enter, R.string.self_destruct_hold_enter_summary, config.holdEnter) { on -> change { settings.setHoldEnter(on) } }
        OnOffPreference(R.string.self_destruct_password_fields, R.string.self_destruct_password_fields_summary, config.checkInPasswordFields) { on -> change { settings.setCheckInPasswordFields(on) } }
    }

    // ---- automatic wipes -----------------------------------------------------------------------------------------------
    PreferenceCategory(stringResource(R.string.self_destruct_auto_title))
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    Preference(
        name = stringResource(R.string.self_destruct_wrong_pins),
        description = if (config.wrongPins == 0) stringResource(R.string.self_destruct_off) else stringResource(R.string.self_destruct_wrong_pins_on, config.wrongPins),
        onClick = { if (lockOn) picker = "pins" else Toast.makeText(ctx, R.string.self_destruct_needs_lock, Toast.LENGTH_LONG).show() },
    )
    Preference(
        name = stringResource(R.string.self_destruct_inactivity),
        description = if (config.inactivityDays == 0) stringResource(R.string.self_destruct_off) else stringResource(R.string.self_destruct_inactivity_on, config.inactivityDays),
        onClick = { picker = "days" },
    )
    if (picker == "pins") ListPickerDialog(
        onDismissRequest = { picker = null }, items = WRONG_PIN_CHOICES, selectedItem = config.wrongPins,
        title = { Text(stringResource(R.string.self_destruct_wrong_pins)) },
        getItemName = { if (it == 0) stringResource(R.string.self_destruct_off) else stringResource(R.string.self_destruct_wrong_pins_on, it) },
        onItemSelected = { n -> change { settings.setWrongPins(n) } },
    )
    if (picker == "days") ListPickerDialog(
        onDismissRequest = { picker = null }, items = INACTIVITY_CHOICES, selectedItem = config.inactivityDays,
        title = { Text(stringResource(R.string.self_destruct_inactivity)) },
        getItemName = { if (it == 0) stringResource(R.string.self_destruct_off) else stringResource(R.string.self_destruct_inactivity_on, it) },
        onItemSelected = { d -> change { settings.setInactivityDays(d) } },
    )

    // ---- what is wiped -------------------------------------------------------------------------------------------------
    PreferenceCategory(stringResource(R.string.self_destruct_scope_title))
    val s = config.scope
    OnOffPreference(R.string.self_destruct_scope_learned, R.string.self_destruct_scope_learned_summary, s.learned) { change { settings.setScope(s.copy(learned = it)) } }
    OnOffPreference(R.string.self_destruct_scope_clipboard, R.string.self_destruct_scope_clipboard_summary, s.clipboard) { change { settings.setScope(s.copy(clipboard = it)) } }
    OnOffPreference(R.string.self_destruct_scope_settings, R.string.self_destruct_scope_settings_summary, s.settings) { change { settings.setScope(s.copy(settings = it)) } }
    OnOffPreference(R.string.self_destruct_scope_imported, R.string.self_destruct_scope_imported_summary, s.imported) { change { settings.setScope(s.copy(imported = it)) } }
    OnOffPreference(R.string.self_destruct_scope_config, R.string.self_destruct_scope_config_summary, s.config) { change { settings.setScope(s.copy(config = it)) } }
    Text(
        stringResource(if (s.shreds) R.string.self_destruct_scope_shred else R.string.self_destruct_scope_partial),
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )

    // ---- panic ---------------------------------------------------------------------------------------------------------
    PreferenceCategory(stringResource(R.string.self_destruct_panic_title))
    var shortcut by remember { mutableStateOf(Privacy.prefs(ctx).getBoolean(SelfDestruct.PREF_PANIC_SHORTCUT, false)) }
    OnOffPreference(R.string.self_destruct_panic_shortcut, R.string.self_destruct_panic_shortcut_summary, shortcut) { on ->
        shortcut = on
        Privacy.prefs(ctx).edit().putBoolean(SelfDestruct.PREF_PANIC_SHORTCUT, on).apply()
        SelfDestruct.syncPanicShortcut(ctx)
    }
    HoldButton(stringResource(R.string.self_destruct_panic_hold)) {
        scope.launch(Dispatchers.Default) { SelfDestruct.engine(ctx).panic(confirmed = true) }
    }

    Text(stringResource(R.string.self_destruct_limits), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))

    // ---- dialogs ----------------------------------------------------------------------------------------------------------
    if (dialog == "duress") DuressPinDialog(
        onDismiss = { dialog = null },
        onPin = { pin -> dialog = null; change { settings.setDuressPin(pin) } },
    )
    if (dialog == "word") TriggerWordDialog(
        onDismiss = { dialog = null },
        onWord = { word ->
            dialog = null
            change { settings.setTriggerWord(word) { w -> isKnownWord(w.toString()) } }
        },
    )
}

/** Dictionaries loaded in this process and the learned words (the facilitator only knows words when the keyboard has run). */
private fun isKnownWord(word: String): Boolean = runCatching {
    val facilitator = DictionaryFacilitatorProvider.getDictionaryFacilitator(false)
    facilitator.isValidSpellingWord(word) || facilitator.isValidSpellingWord(word.lowercase())
}.getOrDefault(false)

@Composable
private fun ModePreference(nameRes: Int, mode: TriggerMode, onChange: (TriggerMode) -> Unit) {
    var show by rememberSaveable { mutableStateOf(false) }
    @Composable fun label(m: TriggerMode) = stringResource(if (m == TriggerMode.SILENT) R.string.self_destruct_mode_silent else R.string.self_destruct_mode_confirm)
    Preference(name = stringResource(nameRes), description = label(mode), onClick = { show = true })
    if (show) ListPickerDialog(
        onDismissRequest = { show = false }, items = TriggerMode.entries, selectedItem = mode,
        title = { Text(stringResource(nameRes)) }, getItemName = { label(it) }, onItemSelected = onChange,
    )
}

@Composable
private fun OnOffPreference(nameRes: Int, summaryRes: Int, on: Boolean, onChange: (Boolean) -> Unit) {
    Preference(
        name = stringResource(nameRes),
        description = stringResource(summaryRes),
        onClick = { onChange(!on) },
        value = { Text(stringResource(if (on) R.string.self_destruct_on else R.string.self_destruct_off), color = MaterialTheme.colorScheme.primary) },
    )
}

/** A button that has to be held for [HOLD_MILLIS]: releasing early does nothing. */
@Composable
private fun HoldButton(text: String, onHeld: () -> Unit) {
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch { delay(HOLD_MILLIS); onHeld() }
                    tryAwaitRelease()
                    job.cancel()
                })
            },
        ) { Text(text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp), color = MaterialTheme.colorScheme.onErrorContainer) }
    }
}

@Composable
private fun DuressPinDialog(onDismiss: () -> Unit, onPin: (CharArray) -> Unit) {
    var first by remember { mutableStateOf<CharArray?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val mismatch = stringResource(R.string.app_lock_mismatch)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(shape = MaterialTheme.shapes.medium) {
            PinPad(
                title = stringResource(if (first == null) R.string.self_destruct_duress_enter else R.string.app_lock_confirm_pin),
                message = message, enabled = true, onCancel = onDismiss,
                onSubmit = { pin ->
                    val candidate = first
                    if (candidate == null) { first = pin; message = null }
                    else if (pin.contentEquals(candidate)) { candidate.fill('\u0000'); onPin(pin) }
                    else { pin.fill('\u0000'); candidate.fill('\u0000'); first = null; message = mismatch }
                },
            )
        }
    }
}

@Composable
private fun TriggerWordDialog(onDismiss: () -> Unit, onWord: (CharArray) -> Unit) {
    var word by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    // a password field: this keyboard does not learn from it or show suggestions for it, and it is never checked as a trigger here
    val options = KeyboardOptions(keyboardType = KeyboardType.Password)
    val ctx = LocalContext.current
    val separators = remember { SelfDestruct.wordSeparators(ctx) }
    val rule = remember(word) { if (word.isEmpty()) null else TriggerWordRules.check(word.toCharArray(), separators) { false } }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.self_destruct_word_set)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.self_destruct_word_rules))
                OutlinedTextField(word, { word = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = options,
                    label = { Text(stringResource(R.string.self_destruct_word_label)) })
                OutlinedTextField(again, { again = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = options,
                    label = { Text(stringResource(R.string.self_destruct_word_repeat)) })
                if (word.isNotEmpty() && again.isNotEmpty() && word != again)
                    Text(stringResource(R.string.app_lock_mismatch), color = MaterialTheme.colorScheme.error)
                else if (rule != null && rule != TriggerWordRules.Result.OK && rule != TriggerWordRules.Result.KNOWN_WORD)
                    Text(stringResource(R.string.self_destruct_word_rules_short), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = word.isNotEmpty() && word == again,
                onClick = { val w = word.toCharArray(); word = ""; again = ""; onWord(w) },
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
