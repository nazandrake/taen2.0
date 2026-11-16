// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import helium314.keyboard.latin.downloads.DownloadCatalog
import helium314.keyboard.latin.downloads.DownloadEntry
import helium314.keyboard.latin.downloads.DownloadsStore
import helium314.keyboard.latin.downloads.ModelFiles
import helium314.keyboard.latin.nextword.NextWordAssist
import helium314.keyboard.latin.nextword.NextWordEngineHolder
import helium314.keyboard.latin.nextword.NextWordGate
import helium314.keyboard.latin.nextword.NextWordMerge
import helium314.keyboard.latin.nextword.NextWordModel
import helium314.keyboard.latin.nextword.NextWordModels
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsValues
import helium314.keyboard.latin.utils.prefs
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The downloaded next-word model in the keyboard service: when the strip shows predictions and [NextWordGate.runs] allows it for this field, the text
 * before the cursor that the connection already holds goes to the model of the language on its own thread, and the strip is reordered if the answer is
 * in time (NextWordAssist). Nothing the model reads or returns is stored; its native state is dropped when the field changes, and the engine is freed at
 * screen lock, two minutes after the last use and when the service ends.
 */
class NextWordTyping(private val ime: LatinIME) {
    private val handler = Handler(Looper.getMainLooper())
    private val main = Executor { handler.post(it) }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "taen-nextword").apply { isDaemon = true } }
    private val assist = NextWordAssist(worker, main, SystemClock::uptimeMillis)
    private val store by lazy { ModelFiles(Privacy.nextWordModelDir(ime)) }
    private val entries: Map<String, DownloadEntry> by lazy {
        runCatching { DownloadCatalog.load(ime) }.getOrDefault(emptyList()).filter { it.kind == DownloadEntry.MODEL }.associateBy { it.id }
    }
    private val files by lazy { DownloadsStore(entries.values.toList(), { store.modelFile(it.id) }, { 0L }) }
    private val holders = ConcurrentHashMap<String, NextWordEngineHolder>() // made on the main thread, unloaded on the model thread
    private var readiness = HashMap<String, NextWordGate.Readiness>()
    private val unloadAll = Runnable { worker.execute { holders.values.forEach { it.unload() } } }

    fun onCreate() {
        Privacy.onLock(LOCK_HOOK) { handler.post { onLock() } } // screen off, and the start of a self-destruct wipe
    }

    fun onDestroy() {
        handler.removeCallbacks(unloadAll)
        assist.cancel(holders.values.toList())
        worker.execute { holders.values.forEach { it.unload() } }
        worker.shutdown()
    }

    /** Another field (or the window closed): a running step is dropped, the model forgets the text, and the installed models are looked at again. */
    fun onFieldChange() {
        assist.cancel(holders.values.toList())
        readiness = HashMap()
    }

    private fun onLock() {
        assist.cancel(holders.values.toList())
        handler.removeCallbacks(unloadAll)
        unloadAll.run()
    }

    /** The settings of the current field and language allow the model: then it is loaded ahead of the first prediction. */
    fun prepare(settings: SettingsValues, locale: Locale) {
        val model = modelFor(settings, locale) ?: return
        assist.prepare(holder(model))
    }

    /** Called on the main thread right after [words] went onto the strip. */
    fun offer(words: SuggestedWords, settings: SettingsValues, locale: Locale) {
        if (!words.isPrediction || words.isEmpty) return
        val model = modelFor(settings, locale) ?: return
        val logic = ime.mInputLogic
        val context = logic.mConnection.cachedTextBeforeCursor
        if (context.isBlank()) return
        val dictionary = (0 until words.size()).map { words.getInfo(it).mWord }
        val facilitator = logic.mDictionaryFacilitator
        val block = settings.mSettingsValuesForSuggestion.mBlockPotentiallyOffensive
        handler.removeCallbacks(unloadAll)
        handler.postDelayed(unloadAll, IDLE_MILLIS) // two minutes after the last request
        assist.request(context, dictionary, { facilitator.isValidPredictionWord(it, block) }, holder(model)) { ranked ->
            if (logic.mSuggestedWords !== words) return@request // the strip changed meanwhile: the answer is for an old state
            ime.setSuggestions(NextWordMerge.reorder(words, ranked))
        }
    }

    private fun modelFor(settings: SettingsValues, locale: Locale): NextWordModel? {
        val model = NextWordModels.forLocale(locale) ?: return null
        val ready = readiness.getOrPut(model.id) { readinessOf(model) }
        val on = ime.prefs().getBoolean(Settings.PREF_NEXTWORD_MODEL, Defaults.PREF_NEXTWORD_MODEL)
        val runs = NextWordGate.runs(on, settings.mSuggestionsEnabled, settings.mBigramPredictionEnabled, settings.mSettingsValuesForSuggestion.mPersonalized, ready)
        return if (runs) model else null
    }

    // the file is hashed once before its first load (the holder); here only its presence and pinned size are looked at
    private fun readinessOf(model: NextWordModel): NextWordGate.Readiness {
        val entry = entries[model.id] ?: return NextWordGate.Readiness.NOT_INSTALLED
        val file = store.modelFile(model.id)
        return NextWordGate.readiness(model, file.isFile && file.length() == entry.size, store.speed(model.id))
    }

    private fun holder(model: NextWordModel): NextWordEngineHolder = holders.getOrPut(model.id) {
        NextWordEngineHolder(
            verify = { entries[model.id]?.let { files.diskState(it) == DownloadsStore.State.VERIFIED } == true },
            load = { model.load(ime.assets, store.modelFile(model.id).path, THREADS) },
        )
    }

    private companion object {
        const val LOCK_HOOK = "nextword"
        const val THREADS = 4 // as voice typing: 4 of the 8 cores of the reference phone, the fast group of the Pixel 8 Pro
        const val IDLE_MILLIS = 120_000L
    }
}
