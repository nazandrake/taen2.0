// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import helium314.keyboard.compat.locale
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.privacy.SecureWindow
import helium314.keyboard.latin.quick.SetupGuide
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.latin.utils.cleanUnusedMainDicts
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.dialogs.ImportProgressDialog
import helium314.keyboard.settings.dialogs.NewDictionaryDialog
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

open class SettingsActivity : ComponentActivity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val prefs by lazy { this.prefs() }
    val prefChanged = MutableStateFlow(0) // simple counter, as the only relevant information is that something changed
    fun prefChanged() = prefChanged.value++
    private val dictUriFlow = MutableStateFlow<Uri?>(null)
    private val cachedDictionaryFile by lazy { File(this.cacheDir.path + File.separator + "temp_dict") }
    private var paused = true

    // App lock: the content is composed only while the lock is open (a PIN screen is started otherwise)
    private var unlocked by mutableStateOf(false)
    private var awaitingUnlock = false

    // The "What's new" box: the notes of an update, set when the screen opens (WhatsNew.check) and cleared by the dismissal, which stores the version
    var whatsNewNotes: String? by mutableStateOf(null)
        private set

    fun dismissWhatsNew() {
        WhatsNew.markShown(prefs, BuildConfig.VERSION_CODE)
        whatsNewNotes = null
    }

    // A .dict link from another app (the exported SettingsActivity2) is copied only after the unlock, and never above the size cap
    private var pendingDictUri: Uri? = null

    // the copy runs in FileUtils (background, size cap, time limit); the screen shows a progress dialog with Cancel meanwhile
    private val jobs = ProviderJobs()

    private fun importPendingDict() {
        val uri = pendingDictUri ?: return
        pendingDictUri = null
        cachedDictionaryFile.delete()
        jobs.importFile(uri, this, cachedDictionaryFile) { failure ->
            // too large, unreadable, timed out or refused (an earlier job still runs): FileUtils kept nothing, no dialog opens, the user is told
            if (failure == null) dictUriFlow.value = uri
            else Toast.makeText(this, R.string.file_read_error, Toast.LENGTH_LONG).show()
        }
    }

    // The PIN screen ends this activity's pause: coming back while still locked means it was cancelled, so the settings close.
    private fun checkAppLock() {
        val lock = Privacy.appLock(this)
        if (!lock.needsUnlock()) {
            lock.touch()
            awaitingUnlock = false
            unlocked = true
            importPendingDict()
            return
        }
        unlocked = false
        if (awaitingUnlock) {
            finish()
            return
        }
        awaitingUnlock = true
        startActivity(Intent(this, UnlockActivity::class.java))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecureWindow.apply(window) // P12: no screenshots or recordings of the settings
        unlocked = !Privacy.appLock(this).needsUnlock()
        if (Settings.getValues() == null) {
            val inputAttributes = InputAttributes(EditorInfo(), false, packageName)
            Settings.getInstance().loadSettings(this, resources.configuration.locale(), inputAttributes)
        }
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute { cleanUnusedMainDicts(this) }
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (!UncachedInputMethodManagerUtils.isThisImeCurrent(this, imm))
            KeyboardIconsSet.instance.loadIcons(this) // otherwise we may crash when displaying toolbar keys

        settingsContainer = SettingsContainer(this)

        val spellchecker = intent?.getBooleanExtra("spellchecker", false) ?: false
        val openVoice = intent?.getBooleanExtra(EXTRA_VOICE_SETTINGS, false) ?: false // the voice key without a model opens the Voice typing page
        val openProfiles = intent?.getBooleanExtra(EXTRA_PROFILES_SETTINGS, false) ?: false // opens Profiles and templates (still behind the app lock)
        whatsNewNotes = if (spellchecker) null else WhatsNew.check(this) // the small spell checker screen is not "the settings"

        val cv = ComposeView(context = this)
        setContentView(cv)
        cv.setContent {
            Theme {
                Surface {
                    if (!unlocked) return@Surface
                    val dictUri by dictUriFlow.collectAsState()
                    var showWelcomeWizard by rememberSaveable { mutableStateOf(
                        !UncachedInputMethodManagerUtils.isThisImeCurrent(this, imm)
                                || !UncachedInputMethodManagerUtils.isThisImeEnabled(this, imm)
                    ) }
                    var showGuide by rememberSaveable { mutableStateOf(!spellchecker && SetupGuide.shouldShow(prefs)) } // a boolean only: no user text in saved state
                    if (spellchecker)
                        Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
                            Column(Modifier.padding(innerPadding)) {
                                TopAppBar(
                                    title = { Text(stringResource(R.string.android_spell_checker_settings)) },
                                    windowInsets = WindowInsets(0),
                                    navigationIcon = {
                                        BackButton { this@SettingsActivity.finish() }
                                    },
                                )
                                settingsContainer[Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE]!!.Preference()
                                settingsContainer[Settings.PREF_SPELLCHECK_SUGGEST]!!.Preference()
                            }
                        }
                    else {
                        SettingsNavHost(onClickBack = { this.finish() }, startDestination = if (openVoice) SettingsDestination.Voice else if (openProfiles) SettingsDestination.Profiles else null)
                        if (showWelcomeWizard) {
                            WelcomeWizard(close = { showWelcomeWizard = false }, finish = this::finish)
                        } else if (showGuide) {
                            SetupGuideDialog(close = { showGuide = false }) // first start after an install or a wipe, once
                        } else {
                            whatsNewNotes?.let { WhatsNewDialog(notes = it, onDismiss = { dismissWhatsNew() }) } // the first opening after an update, once
                        }
                    }
                    if (jobs.running != null) ImportProgressDialog(onCancel = jobs::cancel)
                    if (dictUri != null) {
                        NewDictionaryDialog(
                            onDismissRequest = { dictUriFlow.value = null },
                            cachedFile = cachedDictionaryFile,
                            mainLocale = null
                        )
                    }
                }
            }
        }

        if (intent?.action == Intent.ACTION_VIEW) {
            pendingDictUri = intent?.data // copied in checkAppLock, once the lock is open
            intent = null
        }

        enableEdgeToEdge()
    }

    override fun onStart() {
        super.onStart()
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStop() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onStop()
    }

    override fun onDestroy() {
        jobs.cancel() // a copy that is still running is given up with no file left
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        Privacy.appLock(this).touch() // activity in the settings extends the unlock
        setForceTheme(null, null)
        paused = true
    }

    override fun onResume() {
        super.onResume()
        paused = false
        checkAppLock()
    }

    fun setForceTheme(theme: String?, night: Boolean?) {
        if (paused) return
        if (forceTheme == theme && forceNight == night)
            return
        forceTheme = theme
        forceNight = night
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }

    companion object {
        // public write so compose previews can show the screens
        // having it in a companion object is not ideal as it will stay in memory even after settings are closed
        // but it's small enough to not care
        lateinit var settingsContainer: SettingsContainer

        /** Boolean extra: open the Voice typing page (model download and install) instead of the main settings page. */
        const val EXTRA_VOICE_SETTINGS = "helium314.keyboard.settings.VOICE_SETTINGS"

        /** Boolean extra: open Profiles and templates instead of the main settings page. Like every settings page it is composed only after the app lock is open. */
        const val EXTRA_PROFILES_SETTINGS = "helium314.keyboard.settings.PROFILES_SETTINGS"

        var forceNight: Boolean? = null
        var forceTheme: String? = null
    }

    override fun onSharedPreferenceChanged(prefereces: SharedPreferences?, key: String?) {
        prefChanged()
    }
}

// duplicate of SettingsActivity so we can launch it when the app icon is disabled in Android 9 and older
class SettingsActivity2 : SettingsActivity()
