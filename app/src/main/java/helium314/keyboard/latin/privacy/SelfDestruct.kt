// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import helium314.keyboard.compat.ClipboardManagerCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.SpacingAndPunctuations
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.settings.WipeConfirmActivity
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executor
import java.util.function.IntPredicate
import java.util.function.Supplier

/**
 * The self-destruct on the phone: wires the triggers (duress PIN at the app-lock prompt, the trigger word on the commit
 * key, wrong PINs, inactivity, panic) to [SelfDestructEngine] and the wipe to [Wiper]. The rules live in the engine, the policy and the
 * wiper; this object only knows where things are stored on the device and how to restart the process.
 */
object SelfDestruct {
    private const val TAG = "SelfDestruct"
    private const val CONFIG_STORE = "selfdestruct"
    private const val ACTIVITY_STORE = "activity"
    private const val INPUT_START_INTERVAL_MILLIS = 60_000L
    const val PREF_PANIC_SHORTCUT = "self_destruct_panic_shortcut"

    /** The process ends and the system starts the keyboard again (step 6 of the wipe). A test replaces it. */
    internal val defaultRestart: () -> Unit = { Process.killProcess(Process.myPid()) }
    @Volatile internal var restartHook: () -> Unit = defaultRestart

    /** Where a triggered wipe runs: off the thread that saw the trigger. A test runs it inline. */
    internal val defaultExecutor = Executor { r -> Thread(r, "taen-wipe").start() }
    @Volatile internal var executor: Executor = defaultExecutor

    private val random = SecureRandom()

    // ---- storage -------------------------------------------------------------------------------------------------

    /** The configuration is read on every commit key: kept in memory after the first read, dropped on every save. Never caches "unavailable". */
    private class CachingConfigStorage(private val inner: ConfigStorage) : ConfigStorage {
        @Volatile private var cached: ConfigLoad? = null
        override fun load(): ConfigLoad = cached ?: inner.load().also { if (it !is ConfigLoad.Unavailable) cached = it }
        override fun save(config: SelfDestructConfig): Boolean = inner.save(config).also { cached = null }
    }

    @Volatile private var configStorage: ConfigStorage? = null

    /** The configuration of this process (the keyboard and the settings share it). */
    fun configs(context: Context): ConfigStorage {
        val app = context.applicationContext ?: context
        return configStorage ?: synchronized(this) {
            configStorage ?: CachingConfigStorage(StoreConfigStorage { Privacy.store(app, Tier.DE, CONFIG_STORE, 1) }).also { configStorage = it }
        }
    }

    fun activity(context: Context): ActivityStorage {
        val app = context.applicationContext ?: context
        return StoreActivityStorage { Privacy.store(app, Tier.DE, ACTIVITY_STORE, 1) }
    }

    /** Called by [Privacy.forgetCachedState]: the next access opens everything again. */
    internal fun forget() {
        configStorage = null
        lastInputStart = 0L
    }

    /** The duress PIN record for the app lock (the lock asks on every attempt). */
    internal fun duressRecord(context: Context): SecretRecord? = (configs(context).load() as? ConfigLoad.Present)?.config?.duress

    fun settings(context: Context): SelfDestructSettings {
        val app = context.applicationContext ?: context
        return SelfDestructSettings(configs(app), activity(app), Privacy.appLock(app), PinHasher(SecretRecords.KDF), System::currentTimeMillis, random, wordSeparators(app))
    }

    /** The word separators the typing code uses (its spacing and punctuation settings): a trigger word may not contain one. */
    fun wordSeparators(context: Context): (Int) -> Boolean {
        val spacing = SpacingAndPunctuations((context.applicationContext ?: context).resources, false)
        return spacing::isWordSeparator
    }

    fun engine(context: Context): SelfDestructEngine {
        val app = context.applicationContext ?: context
        return SelfDestructEngine(configs(app), activity(app), SelfDestructPolicy(System::currentTimeMillis), System::currentTimeMillis,
            TriggerMatcher(PinHasher(SecretRecords.KDF)), AndroidEffects(app))
    }

    // ---- effects ---------------------------------------------------------------------------------------------------

    private class AndroidEffects(private val app: Context) : WipeEffects {
        override fun wipe(trigger: Trigger, scope: WipeScope) {
            // an inactivity wipe happens at the start of the app or in the job, before data is opened: it must be finished before anything else runs
            if (trigger == Trigger.INACTIVITY) wipe(app, scope) else executor.execute { wipe(app, scope) }
        }

        override fun confirm(trigger: Trigger) {
            app.startActivity(Intent(app, WipeConfirmActivity::class.java).putExtra(WipeConfirmActivity.EXTRA_TRIGGER, trigger.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        override fun rehearsed(trigger: Trigger) {
            // the text never says which trigger fired
            val show = { Toast.makeText(app, R.string.self_destruct_rehearsal_toast, Toast.LENGTH_SHORT).show() }
            if (Looper.myLooper() == Looper.getMainLooper()) show() else Handler(Looper.getMainLooper()).post(show)
        }
    }

    // ---- the wipe ---------------------------------------------------------------------------------------------------

    /** Wipes [scope] now (step 1 to 6 of the procedure) on the calling thread and restarts the process at the end. */
    fun wipe(context: Context, scope: WipeScope): WipeReport {
        val app = context.applicationContext ?: context
        return Wiper(AndroidWipePorts(app), System::currentTimeMillis).run(scope)
    }

    /** The answer "yes" of the confirmation: wipes with the configured scope (everything when there is no configuration). */
    fun wipeConfirmed(context: Context) {
        val app = context.applicationContext ?: context
        val scope = (configs(app).load() as? ConfigLoad.Present)?.config?.scope ?: WipeScope.ALL
        executor.execute { wipe(app, scope) }
    }

    /** Finishes a wipe that did not finish (locked device, process ended early). Cheap when nothing is pending. Never restarts the process. */
    fun resumePending(context: Context) {
        val app = context.applicationContext ?: context
        val marker = AndroidWipePorts.marker(app)
        if (!marker.exists()) return
        val pending = try { Pending.decode(marker.readBytes()) } catch (_: java.io.IOException) { return }
        Wiper(AndroidWipePorts(app), System::currentTimeMillis).resume(pending)
    }

    // ---- entry points ---------------------------------------------------------------------------------------------

    /** The start of the application: finishes an unfinished wipe, then checks the inactivity limit (before any data is opened), and keeps the daily job in step. */
    fun onAppStart(context: Context) {
        val app = context.applicationContext ?: context
        runCatching { resumePending(app) }
        runCatching { engine(app).checkInactivity() }.onFailure { Log.w(TAG, "inactivity check failed") }
        runCatching { syncInactivityJob(app) }
        runCatching { syncPanicShortcut(app) }
    }

    @Volatile private var lastInputStart = 0L

    /** A new input session: checks the inactivity limit and records that the keyboard is in use. Throttled to once a minute. */
    @JvmStatic
    fun onInputStart(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (lastInputStart != 0L && now - lastInputStart < INPUT_START_INTERVAL_MILLIS) return
        lastInputStart = now
        runCatching { engine(context).onInputStart() }.onFailure { Log.w(TAG, "inactivity check failed") }
    }

    /**
     * The commit key was pressed: the check that is due, or null. A failure here never blocks typing. [text] is the text before the
     * cursor, asked for only when a check can be due.
     */
    @JvmStatic
    fun wordCheck(context: Context, key: CommitKey, passwordField: Boolean, ownApp: Boolean, isWordSeparator: IntPredicate, text: Supplier<CharSequence?>): WordCheck? =
        runCatching { engine(context).prepareCheck(key, FieldContext(passwordField, ownApp, isWordSeparator::test)) { text.get() } }.getOrNull()

    /** [word] is about to be learned: true when it could be the trigger word (one is set), so it is not. A failure here never blocks typing and learns as before. */
    @JvmStatic
    fun keepsOutOfLearning(context: Context, word: CharSequence, isWordSeparator: IntPredicate): Boolean =
        runCatching { engine(context).keepsOutOfLearning(word, isWordSeparator::test) }.getOrDefault(false)

    /** "Hold Enter" is off: the key goes ahead and the check runs beside it. */
    @JvmStatic
    fun runAsync(check: WordCheck) {
        executor.execute { runCatching { check.run() } }
    }

    // ---- inactivity job -------------------------------------------------------------------------------------------

    /** The daily persisted job exists exactly while the inactivity limit is on (it survives a reboot: the reason for RECEIVE_BOOT_COMPLETED). */
    fun syncInactivityJob(context: Context) {
        val app = context.applicationContext ?: context
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
        val enabled = ((configs(app).load() as? ConfigLoad.Present)?.config?.inactivityDays ?: 0) > 0
        if (!enabled) {
            scheduler.cancel(InactivityJobService.JOB_ID)
            return
        }
        if (scheduler.getPendingJob(InactivityJobService.JOB_ID) != null) return
        scheduler.schedule(
            JobInfo.Builder(InactivityJobService.JOB_ID, ComponentName(app, InactivityJobService::class.java))
                .setPeriodic(SelfDestructPolicy.DAY_MILLIS).setPersisted(true).build()
        )
    }

    // ---- panic ------------------------------------------------------------------------------------------------------

    /** The intent of the launcher shortcut "Panic" and of the settings button: it opens the confirmation, nothing else. */
    fun panicIntent(context: Context): Intent =
        Intent(context, WipeConfirmActivity::class.java).putExtra(WipeConfirmActivity.EXTRA_TRIGGER, Trigger.PANIC.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The launcher shortcut "Panic" (off by default: a shortcut on the icon shows that the feature exists). It opens the confirmation only. */
    fun syncPanicShortcut(context: Context) {
        val app = context.applicationContext ?: context
        val shortcuts = app.getSystemService(ShortcutManager::class.java) ?: return
        if (!Privacy.prefs(app).getBoolean(PREF_PANIC_SHORTCUT, false)) {
            shortcuts.removeAllDynamicShortcuts()
            return
        }
        shortcuts.dynamicShortcuts = listOf(
            ShortcutInfo.Builder(app, "panic").setShortLabel(app.getString(R.string.self_destruct_panic_label))
                .setIcon(Icon.createWithResource(app, R.mipmap.ic_launcher)).setIntent(panicIntent(app).setAction(Intent.ACTION_VIEW)).build()
        )
    }

    // ---- the Android side of the wipe ---------------------------------------------------------------------------------

    private class AndroidWipePorts(private val app: Context) : WipePorts {
        override fun beginWipe() {
            Log.i(TAG, "wipe begins")
            Privacy.beginWipe()
        }

        override fun readArmed(): List<ArmedFile> = ARMED_STORES.mapNotNull { name ->
            (Privacy.wipeStore(app, name).read() as? StoreContent.Data)?.let { ArmedFile(name, it.bytes) }
        }

        override fun writePending(pending: Pending): Boolean = DefaultStoreFiles.atomicWrite(marker(app), pending.encode())

        override fun deleteKeys(): Boolean {
            var ok = true
            for (tier in Tier.entries) {
                try { Privacy.keyVault().deleteKey(tier) } catch (_: Exception) { ok = false }
            }
            Log.i(TAG, "keys destroyed")
            return ok
        }

        override fun deleteFiles(scope: WipeScope, keep: Set<String>, ceOnly: Boolean): Boolean {
            val (ce, de) = Privacy.wipeRoots(app)
            val done = FileWiper(ce, de).delete(scope, keep, ceOnly)
            Log.i(TAG, "files deleted")
            return done
        }

        override fun clearClipboard() {
            (app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.let { ClipboardManagerCompat.clearPrimaryClip(it) }
        }

        override fun clearSystemLanguage() { helium314.keyboard.latin.RichInputMethodManager.forgetSystemLanguage(app) }

        override fun restoreArmed(files: List<ArmedFile>): Boolean {
            var ok = true
            for (f in files) {
                try { Privacy.wipeStore(app, f.name).write(f.bytes) } catch (_: Exception) { ok = false }
            }
            return ok
        }

        override fun clearPending() { marker(app).delete() }

        override fun restart() { restartHook() }

        companion object {
            private val ARMED_STORES = listOf("security", CONFIG_STORE)
            fun marker(app: Context): File = File(Privacy.wipeRoots(app).second, "no_backup/taen/${Wiper.PENDING_NAME}")
        }
    }
}

/** The daily check of the inactivity limit. It does not count as use of the keyboard. */
class InactivityJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        runCatching { SelfDestruct.engine(this).checkInactivity() }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        const val JOB_ID = 4601
    }
}
