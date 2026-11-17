// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

/** What can start a self-destruct. */
enum class Trigger { DURESS_PIN, TRIGGER_WORD, WRONG_PINS, INACTIVITY, PANIC }

/** What happens when a trigger fires: wipe at once, or ask a neutral "Clear typing data?" first. Only the duress PIN and the trigger word can choose. */
enum class TriggerMode { SILENT, CONFIRM }

/** A salted slow hash of a secret (duress PIN or trigger word) with the Argon2id parameters it was made with. Never the secret itself. */
class SecretRecord(val salt: ByteArray, val hash: ByteArray, val kdf: ExportCodec.Kdf)

/**
 * What a wipe removes (design W1-W5; W6, the memory caches, the clipboard-independent caches and the process restart, always happen).
 * [learned] W1 learned words, personal dictionary, snippets. [clipboard] W2 clipboard history and the system clip. [settings] W3 preferences,
 * background images, custom layouts and fonts. [config] W4 the app lock and the self-destruct configuration itself (off = it stays armed).
 * [imported] W5 imported dictionaries.
 */
data class WipeScope(
    val learned: Boolean = true,
    val clipboard: Boolean = true,
    val settings: Boolean = true,
    val config: Boolean = true,
    val imported: Boolean = true,
) {
    /** True when every user store is in scope except possibly the configuration: then the keys are destroyed (crypto-shred), not only the files. */
    val shreds: Boolean get() = learned && clipboard && settings && imported

    companion object {
        val ALL = WipeScope()
    }
}

/**
 * The self-destruct settings. Everything is off by default. [wrongPins] 0 = off, else 5..20 consecutive wrong
 * app-lock PINs. [inactivityDays] 0 = off, else 3..365. [rehearsalUntil] is the end (wall clock) of the 15-minute rehearsal, 0 = none.
 */
data class SelfDestructConfig(
    val duress: SecretRecord? = null,
    val duressMode: TriggerMode = TriggerMode.SILENT,
    val word: SecretRecord? = null,
    val wordMode: TriggerMode = TriggerMode.SILENT,
    val commitOnSpace: Boolean = false,
    val holdEnter: Boolean = true,
    val checkInPasswordFields: Boolean = false,
    val wrongPins: Int = 0,
    val inactivityDays: Int = 0,
    val scope: WipeScope = WipeScope.ALL,
    val rehearsalUntil: Long = 0L,
) {
    companion object {
        val DEFAULT = SelfDestructConfig()
    }
}
