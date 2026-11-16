// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.profiles.ProfileOutcome
import helium314.keyboard.latin.profiles.TemplateApply
import helium314.keyboard.latin.profiles.TemplateCatalog
import helium314.keyboard.latin.profiles.TemplateEntry
import helium314.keyboard.latin.quick.LanguageSwitch
import helium314.keyboard.latin.quick.SetupGuide
import helium314.keyboard.latin.utils.prefs

/**
 * The first-run guide (E3), shown once: pick a template, enable English and Bulgarian, choose how to switch language, and a checklist. Skip and Done both
 * set the device-protected "done" flag ([SetupGuide.markDone]). Static strings only; every choice is written through [SetupGuide] or [TemplateApply],
 * which write INCLUDED keys of the device-protected preferences and nothing else.
 */
@SuppressLint("DiscouragedApi") // the template files name their strings (TemplateCatalogTest checks that they resolve)
@Composable
fun SetupGuideDialog(close: () -> Unit) {
    val ctx = LocalContext.current
    val de = ctx.prefs()
    val templates = remember { TemplateCatalog.fromAssets(ctx.assets).load().filterIsInstance<TemplateEntry.Ok>() }
    var status by remember { mutableStateOf<String?>(null) }
    fun text(name: String) = ctx.resources.getIdentifier(name, "string", ctx.packageName).takeIf { it != 0 }?.let { ctx.getString(it) } ?: ""
    fun finish() { SetupGuide.markDone(de); close() }
    fun result(ok: Boolean) {
        status = ctx.getString(if (ok) R.string.profiles_applied else R.string.profiles_error_write)
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }

    Dialog(
        onDismissRequest = { finish() },
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(20.dp)) {
              Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { // the buttons below stay on screen whatever the size of the text
                Text(stringResource(R.string.guide_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.guide_intro), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))

                Text(stringResource(R.string.guide_step_template), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                for (entry in templates) {
                    OutlinedButton(onClick = { result(TemplateApply.apply(entry.template, de) == ProfileOutcome.DONE) }, modifier = Modifier.fillMaxWidth()) {
                        Text(text(entry.template.titleRes))
                    }
                    Text(text(entry.template.summaryRes), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
                }

                Text(stringResource(R.string.guide_step_languages), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                OutlinedButton(onClick = { result(SetupGuide.enableEnglishAndBulgarian(de)) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.guide_enable_en_bg)) }

                Text(stringResource(R.string.guide_step_switch), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                for ((how, label) in listOf(
                    LanguageSwitch.KEY to R.string.guide_switch_key, LanguageSwitch.SPACE_SWIPE to R.string.guide_switch_swipe, LanguageSwitch.LONG_PRESS_SPACE to R.string.guide_switch_longpress,
                )) OutlinedButton(onClick = { result(SetupGuide.chooseLanguageSwitch(de, how)) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }

                Text(stringResource(R.string.guide_step_checklist), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                Text("• " + stringResource(R.string.guide_check_play_protect), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
                Text("• " + stringResource(R.string.guide_check_app_lock), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))

              }
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 4.dp)) }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    TextButton(onClick = { finish() }) { Text(stringResource(R.string.guide_skip)) }
                    Button(onClick = { finish() }, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.guide_done)) }
                }
            }
        }
    }
}
