// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.latin.R
import helium314.keyboard.latin.checkVersionUpgrade
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.personalization.PersonalizationHelper
import helium314.keyboard.latin.privacy.ExportBundle
import helium314.keyboard.latin.privacy.ExportCodec
import helium314.keyboard.latin.privacy.ExportException
import helium314.keyboard.latin.privacy.ExportService
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.IntentUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.ProviderJobs
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import helium314.keyboard.settings.filePicker
import helium314.keyboard.settings.rememberProviderJobs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Encrypted export and import (P16): the file is Argon2id + AES-256-GCM under a passphrase of at least
 * 12 characters that is asked for here and never stored. There is no plain option. The payload is built in memory.
 */
@Composable
fun BackupRestorePreference(setting: Setting) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    var error: String? by rememberSaveable { mutableStateOf(null) }
    // the chosen file and the stage; the passphrase is never kept in saved state
    var pendingUri: Uri? by remember { mutableStateOf(null) }
    var stage by remember { mutableStateOf(Stage.NONE) }
    val jobs = rememberProviderJobs()
    val backupLauncher = filePicker { uri -> pendingUri = uri; stage = Stage.BACKUP_PASSPHRASE }
    val restoreLauncher = filePicker { uri -> pendingUri = uri; stage = Stage.RESTORE_PASSPHRASE }
    Preference(name = setting.title, onClick = { showDialog = true })
    if (showDialog) {
        ConfirmationDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.backup_restore_title)) },
            content = { Text(stringResource(R.string.backup_restore_message)) },
            confirmButtonText = stringResource(R.string.button_backup),
            neutralButtonText = stringResource(R.string.button_restore),
            onNeutral = {
                showDialog = false
                restoreLauncher.launch(pickerIntent().setAction(Intent.ACTION_OPEN_DOCUMENT))
            },
            onConfirmed = {
                val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time)
                backupLauncher.launch(
                    pickerIntent().setAction(Intent.ACTION_CREATE_DOCUMENT).putExtra(
                        Intent.EXTRA_TITLE, ctx.getString(R.string.english_ime_name).replace(" ", "_") + "_backup_$currentDate.taenbk"
                    )
                )
            }
        )
    }
    val uri = pendingUri
    if (uri != null && stage == Stage.BACKUP_PASSPHRASE) {
        PassphraseDialog(repeat = true, onDismiss = { stage = Stage.NONE; pendingUri = null }) { pass ->
            stage = Stage.NONE; pendingUri = null
            // the key derivation runs on the export thread; the file is then written by ProviderJobs (off the main thread, time limit, cancel)
            runInBackground(ctx, { error = "b$it" }, onDone = { file: ByteArray ->
                jobs.write(uri, ctx, file) { failure ->
                    if (failure == null) Toast.makeText(ctx, ctx.getString(R.string.backup_done), Toast.LENGTH_LONG).show()
                    else error = "b" + failure.javaClass.simpleName
                }
            }) { buildBackup(ctx, pass) }
        }
    }
    if (uri != null && stage == Stage.RESTORE_PASSPHRASE) {
        PassphraseDialog(repeat = false, onDismiss = { stage = Stage.NONE; pendingUri = null }) { pass ->
            stage = Stage.NONE; pendingUri = null
            // the file is read by ProviderJobs (off the main thread, size cap, time limit, cancel), then decrypted and applied on the export thread
            readBackupFile(jobs, uri, ctx, pass, onError = { error = "r$it" }) { bytes ->
                runInBackground(ctx, { error = "r$it" }, onDone = { _: Unit -> }) { restoreBackup(ctx, bytes, pass) }
            }
        }
    }
    if (error != null) {
        InfoDialog(
            if (error!!.startsWith("b"))
                stringResource(R.string.backup_error, error!!.drop(1))
            else stringResource(R.string.restore_error, error!!.drop(1))
        ) { error = null }
    }
}

/**
 * Reads the backup file through [jobs]. The [passphrase] is wiped here when the read fails or is given up; on success [onBytes] owns it
 * (restoreBackup wipes it in its finally).
 */
internal fun readBackupFile(
    jobs: ProviderJobs, uri: Uri, ctx: Context, passphrase: CharArray, timeoutMillis: Long = FileUtils.COPY_TIMEOUT_MILLIS,
    onError: (String) -> Unit, onBytes: (ByteArray) -> Unit,
) {
    // a cancel, a screen that leaves or a newer job gives the read up without any callback: the passphrase is wiped then, too
    jobs.read(uri, ctx, ExportCodec.MAX_FILE_BYTES.toLong(), timeoutMillis, onGiveUp = { passphrase.fill('\u0000') }) { failure, bytes, _ ->
        if (failure != null || bytes == null) {
            passphrase.fill('\u0000')
            onError(failure?.javaClass?.simpleName ?: "")
        } else {
            onBytes(bytes)
        }
    }
}

private enum class Stage { NONE, BACKUP_PASSPHRASE, RESTORE_PASSPHRASE }

private fun pickerIntent() = Intent().addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")

@Composable
private fun PassphraseDialog(repeat: Boolean, onDismiss: () -> Unit, onConfirmed: (CharArray) -> Unit) {
    val first = rememberTextFieldState()
    val second = rememberTextFieldState()
    val tooShort = stringResource(R.string.backup_passphrase_hint, ExportCodec.MIN_PASSPHRASE_LENGTH)
    ThreeButtonAlertDialog(
        onDismissRequest = onDismiss,
        onConfirmed = { onConfirmed(first.text.toString().toCharArray()) },
        title = { Text(stringResource(if (repeat) R.string.backup_passphrase_title else R.string.restore_passphrase_title)) },
        checkOk = {
            val p = first.text
            if (repeat) p.length >= ExportCodec.MIN_PASSPHRASE_LENGTH && p.toString() == second.text.toString() else p.isNotEmpty()
        },
        content = {
            Column {
                if (repeat) {
                    Text(tooShort)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedSecureTextField(state = first, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.passphrase_label)) })
                if (repeat) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedSecureTextField(state = second, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.passphrase_repeat_label)) })
                }
            }
        },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn), // no screenshot of the passphrase
    )
}

// one thread for the slow key derivation, so neither the UI nor the dictionary executor waits for it
private val exportExecutor by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "taen-export").apply { isDaemon = true } } }

private fun <T> runInBackground(ctx: Context, onError: (String) -> Unit, onDone: (T) -> Unit, work: () -> T) {
    val main = Handler(Looper.getMainLooper())
    exportExecutor.execute {
        try {
            val result = work()
            main.post { onDone(result) }
        } catch (e: ExportException) {
            main.post { onError(e.message ?: "") }
        } catch (t: Throwable) {
            Log.w("BackupRestore", "export or import failed")
            main.post { onError(t.javaClass.simpleName) } // never the message: it could quote data
        }
    }
}

private fun buildBackup(ctx: Context, passphrase: CharArray): ByteArray {
    try {
        val parts = ExportService.collect(ctx, ::isExportedFile)
        // the pictures are left out as a whole when they are larger than 64 MiB; the result says so
        if (parts.mediaExcluded) Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, R.string.backup_media_excluded, Toast.LENGTH_LONG).show() }
        return ExportCodec.encrypt(passphrase, ExportBundle.pack(parts))
    } finally {
        passphrase.fill('\u0000')
    }
}

private fun restoreBackup(ctx: Context, file: ByteArray, passphrase: CharArray) {
    try {
        // everything is decrypted, authenticated and parsed before anything is replaced
        val parts = ExportBundle.unpack(ExportCodec.decrypt(passphrase, file))
        LayoutUtilsCustom.onLayoutFileChanged()
        Settings.getInstance().stopListener()
        try {
            // the learned-word storage is closed for the running dictionaries while the data is replaced (they must neither
            // reload nor write their old state over the imported one) and they drop their state before and after
            ExportService.applyImport(ctx, parts, ::isExportedFile) { PersonalizationHelper.discardAndReloadAll() }
        } finally {
            Settings.getInstance().startListener()
        }
        Handler(Looper.getMainLooper()).post {
            checkVersionUpgrade(ctx)
            SubtypeSettings.reloadEnabledSubtypes(ctx)
            ctx.getActivity()?.sendBroadcast(Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION))
            LayoutUtilsCustom.onLayoutFileChanged()
            LayoutUtilsCustom.removeMissingLayouts(ctx)
            (ctx.getActivity() as? SettingsActivity)?.prefChanged()
            SupportedEmojis.load(ctx)
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
            Toast.makeText(ctx, ctx.getString(R.string.backup_restored), Toast.LENGTH_LONG).show()
        }
    } finally {
        passphrase.fill('\u0000')
    }
}

private fun isExportedFile(path: String) = backupFilePatterns.any { path.matches(it) }

// Files that are exported besides the preferences, the personal dictionary and the learned words (those are
// encrypted stores). The learned-word dictionaries are no longer files.
private val backupFilePatterns by lazy { listOf(
    "blacklists${File.separator}.*\\.txt".toRegex(),
    "layouts${File.separator}.*${LayoutUtilsCustom.CUSTOM_LAYOUT_PREFIX}+\\..{0,4}".toRegex(), // can't expect a period at the end, as this would break restoring older backups
    "dicts${File.separator}.*${File.separator}.*user\\.dict".toRegex(),
    "custom_font".toRegex(),
    "custom_emoji_font".toRegex(),
) }
