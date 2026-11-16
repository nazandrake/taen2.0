// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import helium314.keyboard.compat.isDeviceLocked
import helium314.keyboard.compat.isUserLocked
import helium314.keyboard.latin.media.MediaSource
import helium314.keyboard.latin.media.MediaStorage
import helium314.keyboard.latin.media.MediaTokens
import helium314.keyboard.latin.media.MediaVault
import helium314.keyboard.latin.media.StarterPack
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.util.EnumMap
import java.util.concurrent.Executors

/**
 * Entry point to the encrypted storage: the data keys of both tiers, the preferences, and the
 * stores for clipboard history, personal dictionary and background images. Everything that used to be a plain file,
 * SharedPreferences file or SQLite table of user data is reached only through here (the privacy gate checks that).
 * Files live in `no_backup/taen` of the CE or DE storage; the DE directory is usable before the first unlock, the CE one is not.
 * (The app's default storage is DE because of android:defaultToDeviceProtectedStorage.)
 */
object Privacy {
    private const val TAG = "Privacy"
    private const val DIR = "taen"
    const val CLIPBOARD_STORE = "clipboard"
    const val PERSONAL_DICTIONARY_STORE = "snippets"
    private const val PREFS_DE = "prefs_de"
    private const val PREFS_CE = "prefs_ce"
    private const val LEGACY_CLIPBOARD_DB = "heliboard.db"

    // created lazily so a test can shadow AndroidKeyVault; never replaced at runtime
    private val vault: KeyVault by lazy { AndroidKeyVault() }
    internal val writer by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "taen-prefs").apply { isDaemon = true } } }

    // Cached objects, created by the first caller and kept for the life of the process (like the static preferences object
    // of the earlier DeviceProtectedUtils): the app has one application object and every caller must see the same preferences.
    private class State {
        val keys = EnumMap<Tier, DataKeys>(Tier::class.java)
        var dePrefs: EncryptedPrefs? = null
        var cePrefs: EncryptedPrefs? = null
        @Volatile var cePrefsMigrated = false
        var personal: PersonalDictionary? = null
        var media: MediaVault? = null
    }

    private var cached = State()

    // ---- app lock -------------------------------------------------------------------------------------------------
    private const val SECURITY_STORE = "security"
    private var appLockInstance: AppLock? = null

    /**
     * The app lock (PIN in front of the settings and the clipboard history). Its record is the `security` store of the
     * device-protected tier (readable on the lock screen, never exported). The unlock lasts in memory only: a screen lock
     * ([lock]) or the end of the process closes it.
     */
    @Synchronized
    fun appLock(context: Context): AppLock {
        appLockInstance?.let { return it }
        val app = context.applicationContext ?: context
        val lock = AppLock(StoreLockStorage { store(app, Tier.DE, SECURITY_STORE, 1) }, System::currentTimeMillis, PinHasher(AppLock.PIN_KDF),
            duress = { SelfDestruct.duressRecord(app) })
        onLock("applock") { lock.relock() }
        appLockInstance = lock
        return lock
    }

    // ---- screen lock (P10, P19) ---------------------------------------------------------------------------------
    // While the screen is locked nothing of the credential-encrypted tier is stored, learned, loaded or served from memory:
    // the data key of the tier is forgotten, its gate is closed (no store of the tier can be read or written, not even
    // through an object that was handed out earlier) and the in-memory copies are wiped. Unlock opens everything again from
    // the encrypted files. A process-wide flag, deliberately not part of [State]: forgetting the cached objects keeps the lock.

    /** The gate of the data key: closed only after the in-flight edits were written and the memory copies wiped (see [lock]). */
    @Volatile private var screenLocked = false

    /** Set at the start of [lock]: nothing new of the tier is opened or loaded from then on, but the key still works for edits in flight. */
    @Volatile private var locking = false

    /** True from the start of [lock] until [unlock]; the credential-encrypted tier is unavailable then. */
    val isLocked: Boolean get() = screenLocked || locking

    private val lockHooks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    /** Registers [hook] (replacing an earlier one with the same [name]) to wipe an in-memory copy when the tier locks. */
    fun onLock(name: String, hook: () -> Unit) { lockHooks[name] = hook }

    /** Test seam: runs after the memory copies were wiped and before the data key is forgotten (the window of an edit that races the lock). */
    @Volatile internal var duringLockForTest: (() -> Unit)? = null

    /**
     * Locks the credential-encrypted tier until [unlock]. Order matters, so that an edit is never reported as saved and then
     * lost: (1) from now on nothing new of the tier is opened; (2) the preferences are written once more if an apply() is still
     * queued, then wiped; (3) the personal dictionary and the registered copies (clipboard history) are wiped: an edit in
     * flight finishes its write first, a later one is refused (returns false); (4) only then the data key is forgotten and
     * the gate closed.
     */
    fun lock(context: Context) {
        // Lock order: an EncryptedPrefs holds its own lock while it asks [store] (which takes this object's lock), so this
        // object's lock must never be held while taking the lock of a preferences object that was handed out. Hence the
        // objects are only collected here and wiped after the lock is released.
        val (prefs, personal) = synchronized(this) {
            locking = true
            cached.cePrefs to cached.personal
        }
        if (prefs != null && !prefs.flushAndEvict()) Log.w(TAG, "preferences edits could not be saved before the lock")
        personal?.evict()
        for (hook in lockHooks.values) runCatching(hook).onFailure { Log.w(TAG, "a lock hook failed") }
        duringLockForTest?.invoke()
        val keys = synchronized(this) {
            screenLocked = true
            locking = false
            cached.keys[Tier.CE]
        }
        keys?.evict()
    }

    /** Opens the credential-encrypted tier again (the user is present) and restores the personal dictionary in memory. */
    fun unlock(context: Context) {
        val app = context.applicationContext ?: context
        synchronized(this) {
            screenLocked = false
            locking = false
            if (cached.personal != null) personalDictionary(app) // reads the store again when it was wiped
        }
        protectedPrefs(app) // moves the plain preferences file of an earlier version if that was not possible before
        settleAppLanguages(app)
        SelfDestruct.resumePending(app) // a wipe that had to wait for the unlock finishes now
    }

    /** Called once when the application starts: opens the credential-encrypted preferences, which moves their plain file of earlier versions. */
    fun openAtStart(context: Context) {
        protectedPrefs(context)
        settleAppLanguages(context.applicationContext ?: context)
    }

    /**
     * The per-app language entries of the earlier format in the device-protected preferences are deleted (and moved only for apps that may be remembered) as soon as
     * the credential-encrypted tier is open: at the start of the app, or at the unlock when it was closed then (Settings.settleAppLanguages).
     */
    private fun settleAppLanguages(app: Context) {
        if (isLocked || isUserLocked(app) || isDeviceLocked(app)) return
        runCatching { Settings.settleAppLanguages(app) }.onFailure { Log.w(TAG, "settling the per-app languages failed") }
    }

    /** Drops the cached keys and store objects, so the next access opens everything again (files are not touched). */
    @Synchronized
    internal fun forgetCachedState() { cached = State(); appLockInstance = null; SelfDestruct.forget() }

    // ---- self-destruct ---------------------------------------------------------------------------------------
    // The wipe stops every writer first: from beginWipe on no store of either tier can be opened, read or written, not even through an
    // object that was handed out earlier (the data keys of both tiers refuse), and the memory copies are wiped. The flag lives as long as
    // the process: the wipe ends with a restart.

    @Volatile private var wiping = false

    /** True from the start of a wipe until the process ends. */
    val isWiping: Boolean get() = wiping

    internal fun beginWipe() {
        val (keys, prefs, personal) = synchronized(this) {
            wiping = true
            Triple(cached.keys.values.toList(), listOfNotNull(cached.dePrefs, cached.cePrefs), cached.personal)
        }
        prefs.forEach { it.evict() } // outside this object's lock, see [lock]
        personal?.evict()
        for (hook in lockHooks.values) runCatching(hook) // clipboard history and the app lock in memory
        keys.forEach { it.evict() }
    }

    /** Test seam: a test process lives on after a wipe. The real process ends. */
    internal fun endWipe() { wiping = false }

    internal fun keyVault(): KeyVault = vault

    /**
     * Where the voice model files live: `no_backup/taen-voice` of the device-protected storage, a sibling of the stores. The model is
     * public data, not user data, so it is not encrypted (VoiceModelStore). A partial wipe keeps it, a shred removes it (FileWiper).
     */
    fun voiceModelDir(context: Context): File = File(deviceProtected(context.applicationContext ?: context).noBackupFilesDir, VOICE_DIR)

    const val VOICE_DIR = "taen-voice"

    /**
     * Where an install of a downloaded dictionary is staged: `no_backup/taen-downloads` of the same storage as the dictionary folder (files/dicts),
     * so that the final rename stays on one file system. Only `.part` files of a running install live here; a partial wipe removes them (IMPORTED).
     */
    fun downloadsStagingDir(context: Context): File = File((context.applicationContext ?: context).noBackupFilesDir, DOWNLOADS_DIR)

    const val DOWNLOADS_DIR = "taen-downloads"

    /**
     * Where the downloaded next-word models live: `no_backup/taen-models`, next to the staging folder (one file system for the final rename). A model is
     * public data, so it is not encrypted, but which ones are there shows the languages in use: a partial wipe removes them with the imported files.
     * The model only runs while the device is unlocked, so the credential-encrypted storage is enough.
     */
    fun nextWordModelDir(context: Context): File = File((context.applicationContext ?: context).noBackupFilesDir, MODELS_DIR)

    const val MODELS_DIR = "taen-models"

    /** The data directories of both tiers for a wipe: the credential-encrypted one is null while the user is locked. Not gated by the wipe flag. */
    internal fun wipeRoots(context: Context): Pair<File?, File> {
        val app = context.applicationContext ?: context
        return (if (isUserLocked(app)) null else ceRoot(app)) to deviceProtected(app).dataDir
    }

    /**
     * A store of the device-protected tier with its own data key object that is not gated by the wipe flag: reads the configuration before the
     * keys are destroyed and writes it back under a new key afterwards (a wipe that keeps the configuration armed). Only the wipe calls it.
     */
    internal fun wipeStore(context: Context, name: String): SecureStore {
        val app = context.applicationContext ?: context
        val dir = File(deviceProtected(app).noBackupFilesDir, DIR)
        val keys = DataKeys(vault, Tier.DE, File(dir, "dek_de.bin"))
        return SecureStore(dir, name, 1, keys::key)
    }

    /** Preferences that are also readable before the first unlock (device-protected tier). Never put sensitive data here. */
    @Synchronized
    fun prefs(context: Context): SharedPreferences {
        val app = context.applicationContext ?: context
        cached.dePrefs?.let { return it }
        val p = EncryptedPrefs({ store(app, Tier.DE, PREFS_DE, 1) }, writer, ::notifyOnMain)
        cached.dePrefs = p
        val plainName = plainPrefsName(app)
        val deCtx = deviceProtected(app)
        runCatching {
            LegacyMigration.migratePrefs(p, PlatformPlainPrefs({ deCtx.getSharedPreferences(plainName, Context.MODE_PRIVATE) }) {
                deCtx.deleteSharedPreferences(plainName)
            })
        }.onFailure { Log.w(TAG, "preferences migration failed") }
        return p
    }

    /**
     * Preferences of the credential-encrypted tier: empty and read-only while the device or the screen is locked. The plain
     * preferences file of earlier versions (the old protectedPrefs) is moved in and deleted at the first call after the
     * unlock, and tried again at every later call until that worked.
     */
    fun protectedPrefs(context: Context): SharedPreferences {
        val app = context.applicationContext ?: context
        val (state, p) = synchronized(this) {
            val st = cached
            st to (st.cePrefs ?: EncryptedPrefs({ store(app, Tier.CE, PREFS_CE, 1) }, writer, ::notifyOnMain).also { st.cePrefs = it })
        }
        // outside this object's lock (see [lock]); one migration at a time
        if (!state.cePrefsMigrated) synchronized(migrationLock) {
            if (!state.cePrefsMigrated) state.cePrefsMigrated = migrateCredentialPrefs(app, p)
        }
        return p
    }

    private val migrationLock = Any()

    // The old protectedPrefs() was the default preferences file of the credential-encrypted storage. If this process has a
    // device-protected default context (the manifest asks for one), the credential-encrypted storage is reached through a
    // package context; if that is not possible nothing can be migrated here and the file is left alone.
    private fun migrateCredentialPrefs(app: Context, target: EncryptedPrefs): Boolean {
        val source = credentialProtected(app) ?: return true
        val name = plainPrefsName(app)
        return runCatching {
            LegacyMigration.migratePrefs(target, PlatformPlainPrefs({ source.getSharedPreferences(name, Context.MODE_PRIVATE) }) {
                source.deleteSharedPreferences(name)
            }, mergeMissing = true) // the previous version already started this store empty
        }.onFailure { Log.w(TAG, "credential-encrypted preferences migration failed") }.getOrDefault(false)
    }

    private fun credentialProtected(app: Context): Context? =
        if (!app.isDeviceProtectedStorage) app
        else runCatching { app.createPackageContext(app.packageName, 0) }.getOrNull()?.takeIf { !it.isDeviceProtectedStorage }

    /** The store [name] of [tier], or null while it cannot be opened (CE tier before the first unlock). */
    @Synchronized
    fun store(context: Context, tier: Tier, name: String, schemaVersion: Int): SecureStore? {
        if (wiping) return null // a wipe is running: nothing is opened any more
        val app = context.applicationContext ?: context
        // noBackupFilesDir: the backup and restore function of the settings never moves or deletes the stores
        val dir = when (tier) {
            Tier.DE -> File(deviceProtected(app).noBackupFilesDir, DIR)
            Tier.CE -> File(File(ceDataDir(app) ?: return null, "no_backup"), DIR)
        }
        val dataKeys = cached.keys.getOrPut(tier) {
            DataKeys(vault, tier, File(dir, "dek_${tier.name.lowercase()}.bin"), allowed = if (tier == Tier.CE) ({ !screenLocked && !wiping }) else ({ !wiping }))
        }
        return SecureStore(dir, name, schemaVersion, dataKeys::key)
    }

    // ---- clipboard -------------------------------------------------------------------------------------------

    /** Loads the clipboard entries (moving the old SQLite table in the first time). Null while the store is unavailable. */
    @Synchronized
    fun loadClipboard(context: Context): Pair<SecureStore, List<ClipboardEntry>>? {
        val app = context.applicationContext ?: context
        val store = store(app, Tier.CE, CLIPBOARD_STORE, 1) ?: return null
        return when (val content = store.read()) {
            is StoreContent.Unavailable -> null
            is StoreContent.Data -> try {
                store to ClipboardCodec.decode(content.bytes)
            } catch (_: CorruptStoreException) {
                store.delete()
                store to emptyList()
            }
            is StoreContent.Reset -> store to emptyList()
            is StoreContent.Missing -> {
                val legacy = app.getDatabasePath(LEGACY_CLIPBOARD_DB)
                val migrated = if (legacy.exists()) migrateLegacyClipboard(app, store, legacy) else null
                store to (migrated ?: emptyList())
            }
        }
    }

    private fun migrateLegacyClipboard(app: Context, store: SecureStore, db: File): List<ClipboardEntry>? {
        val rows = try {
            val rows = ArrayList<LegacyMigration.LegacyClip>()
            SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                // rows with a FILE are image or file clips (their TEXT is only a label); they are not kept
                val cursor = try {
                    database.rawQuery("SELECT TIMESTAMP, PINNED, TEXT FROM CLIPBOARD WHERE TEXT IS NOT NULL AND FILE IS NULL", null)
                } catch (_: SQLiteException) { // a table of the first versions has no FILE column
                    database.rawQuery("SELECT TIMESTAMP, PINNED, TEXT FROM CLIPBOARD WHERE TEXT IS NOT NULL", null)
                }
                cursor.use {
                    while (it.moveToNext()) rows.add(LegacyMigration.LegacyClip(it.getLong(0), it.getInt(1) != 0, it.getString(2)))
                }
            }
            rows
        } catch (e: Exception) {
            Log.w(TAG, "could not read the old clipboard database")
            return null
        }
        return LegacyMigration.migrateClipboard(store, rows) {
            app.deleteDatabase(LEGACY_CLIPBOARD_DB)
            File(app.filesDir, "clipboard").deleteRecursively() // image clips: not kept, they would be plaintext
            File(app.filesDir, "temp_clip").delete()
        }
    }

    // ---- personal dictionary and snippets --------------------------------------------------------------------

    /**
     * The personal dictionary, or null while its store is unavailable (device or screen locked; the caller retries later). The
     * object is the same for the life of the process: a screen lock wipes its entries ([PersonalDictionary.evict]) and the
     * next call after the unlock reads them back, so dictionaries that listen to it stay attached.
     */
    @Synchronized
    fun personalDictionary(context: Context): PersonalDictionary? {
        val app = context.applicationContext ?: context
        val st = cached
        val existing = st.personal
        if (existing != null && !existing.isEvicted()) return if (ceDataDir(app) == null) null else existing
        val store = store(app, Tier.CE, PERSONAL_DICTIONARY_STORE, 1) ?: return null
        val words = when (val content = store.read()) {
            is StoreContent.Unavailable -> return null
            is StoreContent.Data -> try {
                PersonalDictionaryCodec.decode(content.bytes)
            } catch (_: CorruptStoreException) {
                store.delete()
                emptyList()
            }
            else -> emptyList()
        }
        if (existing != null) {
            existing.restore(words)
            return existing
        }
        // a failed write is not swallowed here: PersonalDictionary refuses the edit, so its caller knows it was not saved
        val dictionary = PersonalDictionary(words) { list -> store.write(PersonalDictionaryCodec.encode(list)) }
        st.personal = dictionary
        return dictionary
    }

    // ---- learned words ---------------------------------------------------------------------------------------

    private const val LEARNED_PREFIX = "learned_"

    // the store name is a file name: only letters, digits and '-' (a language tag), everything else becomes '_'
    private fun learnedName(localeTag: String) = LEARNED_PREFIX + localeTag.map { if (it.isLetterOrDigit() && it.code < 128 || it == '-') it else '_' }.joinToString("")

    /** The encrypted storage of the learned-word dictionary of one language (credential-encrypted tier). */
    fun learnedStorage(context: Context, localeTag: String, legacy: LegacyLearned?): LearnedStorage {
        val app = context.applicationContext ?: context
        val name = learnedName(localeTag)
        // closed while an import replaces the stored words (see ImportGuard)
        return LearnedStorage({ if (ImportGuard.active) null else store(app, Tier.CE, name, 1) }, legacy)
    }

    private fun learnedFiles(app: Context): Array<File>? {
        val dir = File(File(ceDataDir(app) ?: return null, "no_backup"), DIR)
        return dir.listFiles { f -> f.name.startsWith(LEARNED_PREFIX) && f.name.endsWith(".enc") }
    }

    /** The serialized learned-word dictionaries of every language, by language tag (for the export). */
    @Synchronized
    fun readAllLearned(context: Context): Map<String, ByteArray> {
        val app = context.applicationContext ?: context
        val result = HashMap<String, ByteArray>()
        for (f in learnedFiles(app).orEmpty()) {
            val name = f.name.removeSuffix(".enc")
            val bytes = (store(app, Tier.CE, name, 1)?.read() as? StoreContent.Data)?.bytes ?: continue
            result[name.removePrefix(LEARNED_PREFIX)] = bytes
        }
        return result
    }

    /** Replaces the learned-word stores of every language by [byTag] (an import). False if one could not be written. */
    @Synchronized
    fun replaceAllLearned(context: Context, byTag: Map<String, ByteArray>): Boolean {
        val app = context.applicationContext ?: context
        deleteAllLearned(app)
        for ((tag, bytes) in byTag) {
            val ok = try {
                store(app, Tier.CE, learnedName(tag), 1)?.write(bytes) != null
            } catch (_: StoreWriteException) {
                false
            } catch (_: VaultLockedException) {
                false
            }
            if (!ok) return false
        }
        return true
    }

    /** Deletes the learned-word stores of every language ("forget all learned data"). Nothing to do while locked. */
    @Synchronized
    fun deleteAllLearned(context: Context) {
        val app = context.applicationContext ?: context
        val dir = File(File(ceDataDir(app) ?: return, "no_backup"), DIR)
        dir.listFiles { f -> f.name.startsWith(LEARNED_PREFIX) }?.forEach { it.delete() }
    }

    // ---- background images -----------------------------------------------------------------------------------

    private fun backgroundName(night: Boolean, landscape: Boolean) =
        "background_" + (if (night) "night" else "day") + "_" + (if (landscape) "landscape" else "portrait")

    private fun legacyBackgroundFile(app: Context, night: Boolean, landscape: Boolean) =
        File(deviceProtected(app).filesDir, "custom_background_image" + (if (landscape) "_landscape" else "") + (if (night) "_night" else ""))

    private fun backgroundStore(context: Context, night: Boolean, landscape: Boolean): SecureStore? {
        val app = context.applicationContext ?: context
        val store = store(app, Tier.DE, backgroundName(night, landscape), 1) ?: return null
        val legacy = legacyBackgroundFile(app, night, landscape)
        if (legacy.isFile) LegacyMigration.migrateFile(legacy, store) // the plain image never stays next to the encrypted one
        return store
    }

    /** The decrypted image bytes, or null when there is no (readable) background image. */
    fun readBackground(context: Context, night: Boolean, landscape: Boolean): ByteArray? =
        (backgroundStore(context, night, landscape)?.read() as? StoreContent.Data)?.bytes

    fun hasBackground(context: Context, night: Boolean, landscape: Boolean): Boolean =
        backgroundStore(context, night, landscape)?.exists() == true

    fun writeBackground(context: Context, night: Boolean, landscape: Boolean, bytes: ByteArray): Boolean = try {
        backgroundStore(context, night, landscape)?.write(bytes) != null
    } catch (_: StoreWriteException) {
        false
    } catch (_: VaultLockedException) {
        false
    }

    fun deleteBackground(context: Context, night: Boolean, landscape: Boolean) {
        backgroundStore(context, night, landscape)?.delete()
        legacyBackgroundFile(context.applicationContext ?: context, night, landscape).delete()
    }

    // ---- pictures --------------------------------------------------------------------------------------

    /**
     * The collection of imported pictures (GIFs and stickers): `media_<id>.enc` stores of the credential-encrypted tier, listed by `media_index.enc`
     * ([MediaVault]). One object for the life of the process; nothing is readable while the tier is locked. The first call also tells the content provider how to
     * read a picture or a starter-pack asset behind a token ([MediaTokens.reader]).
     */
    @Synchronized
    fun mediaVault(context: Context): MediaVault {
        val app = context.applicationContext ?: context
        cached.media?.let { return it }
        val vault = MediaVault(object : MediaStorage {
            override fun open(name: String): SecureStore? = store(app, Tier.CE, name, 1)
            override fun names(): List<String>? {
                val dir = File(File(ceDataDir(app) ?: return null, "no_backup"), DIR)
                return dir.listFiles { f -> f.name.startsWith(MediaVault.PREFIX) && f.name.endsWith(".enc") }?.map { it.name.removeSuffix(".enc") } ?: emptyList()
            }
        })
        MediaTokens.reader = { source ->
            when (source) {
                is MediaSource.Vault -> vault.read(source.id)
                is MediaSource.Asset -> StarterPack.read(app, source.path)
            }
        }
        cached.media = vault
        return vault
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    /**
     * The credential-encrypted data directory, or null while the user is locked or the directory is not found (fail
     * closed: the CE tier is then unavailable). Normally the default Context is credential encrypted (checked on the
     * reference phone, although the manifest asks for defaultToDeviceProtectedStorage); if a default Context is device protected,
     * the directory is addressed by its path.
     */
    private fun ceDataDir(app: Context): File? {
        if (isLocked || wiping || isUserLocked(app)) return null
        return ceRoot(app)
    }

    /** The credential-encrypted data directory without any lock or wipe check (see [ceDataDir]). */
    private fun ceRoot(app: Context): File? {
        if (!app.isDeviceProtectedStorage) return app.dataDir
        val dir = File("/data/user/${Process.myUserHandle().hashCode()}/${app.packageName}")
        return dir.takeIf { it.isDirectory }
    }

    private fun plainPrefsName(app: Context) = app.packageName + "_preferences"

    private fun deviceProtected(app: Context): Context =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || app.isDeviceProtectedStorage) app
        else app.createDeviceProtectedStorageContext() ?: app

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private fun notifyOnMain(r: Runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run() else mainHandler.post(r)
    }
}
