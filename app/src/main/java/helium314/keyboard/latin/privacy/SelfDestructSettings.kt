// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.security.SecureRandom

/**
 * Changes to the self-destruct configuration: what can be set, to which values, and what is refused. Each change is
 * one read-modify-write of the stored configuration; an unreadable configuration is never overwritten. Secrets handed in are cleared.
 */
class SelfDestructSettings(
    private val configs: ConfigStorage,
    private val activity: ActivityStorage,
    private val appLock: AppLock,
    private val hasher: PinHasher,
    private val clock: () -> Long,
    private val random: SecureRandom,
    /** Whether the keyboard treats a code point as a word separator (its spacing and punctuation settings): such a word could be changed before the commit key. */
    private val isWordSeparator: (Int) -> Boolean,
) {
    enum class Change {
        OK, BAD_PIN, NEEDS_APP_LOCK, SAME_AS_APP_PIN, OUT_OF_RANGE, NOT_SAVED,
        WORD_TOO_SHORT, WORD_TOO_LONG, WORD_WHITESPACE, WORD_SEPARATOR, WORD_NEEDS_DIGIT_OR_SYMBOL, WORD_KNOWN,
    }

    private fun update(block: (SelfDestructConfig) -> SelfDestructConfig): Change {
        val current = when (val l = configs.load()) {
            is ConfigLoad.None -> SelfDestructConfig.DEFAULT
            is ConfigLoad.Present -> l.config
            is ConfigLoad.Unavailable -> return Change.NOT_SAVED
        }
        return if (configs.save(block(current))) Change.OK else Change.NOT_SAVED
    }

    /** The duress PIN is entered at the app-lock prompt, so it needs an app lock, and it must differ from the PIN of that lock. */
    fun setDuressPin(pin: CharArray): Change {
        try {
            if (!AppLock.isValidPin(pin)) return Change.BAD_PIN
            if (!appLock.isEnabled()) return Change.NEEDS_APP_LOCK
            val collides = appLock.collides(pin) ?: return Change.NOT_SAVED // the lock cannot be read: refuse rather than guess
            if (collides) return Change.SAME_AS_APP_PIN
            val record = SecretRecords.create(pin, hasher, random)
            return update { it.copy(duress = record) }
        } finally {
            pin.fill('\u0000')
        }
    }

    fun removeDuressPin(): Change = update { it.copy(duress = null) }

    /** Stores the hash of [word] when it follows the rules; [isKnown] tells whether it is in a loaded dictionary or the learned words. */
    fun setTriggerWord(word: CharArray, isKnown: (CharSequence) -> Boolean): Change {
        try {
            when (TriggerWordRules.check(word, isWordSeparator, isKnown)) {
                TriggerWordRules.Result.OK -> Unit
                TriggerWordRules.Result.TOO_SHORT -> return Change.WORD_TOO_SHORT
                TriggerWordRules.Result.TOO_LONG -> return Change.WORD_TOO_LONG
                TriggerWordRules.Result.WHITESPACE -> return Change.WORD_WHITESPACE
                TriggerWordRules.Result.SEPARATOR -> return Change.WORD_SEPARATOR
                TriggerWordRules.Result.NEEDS_DIGIT_OR_SYMBOL -> return Change.WORD_NEEDS_DIGIT_OR_SYMBOL
                TriggerWordRules.Result.KNOWN_WORD -> return Change.WORD_KNOWN
            }
            val record = SecretRecords.create(word, hasher, random)
            return update { it.copy(word = record) }
        } finally {
            word.fill('\u0000')
        }
    }

    fun removeTriggerWord(): Change = update { it.copy(word = null) }

    /** Wipe after [count] consecutive wrong app-lock PINs: 0 (off) or 5..20. Needs an app lock to be switched on. */
    fun setWrongPins(count: Int): Change {
        if (count != 0 && count !in 5..20) return Change.OUT_OF_RANGE
        if (count != 0 && !appLock.isEnabled()) return Change.NEEDS_APP_LOCK
        return update { it.copy(wrongPins = count) }
    }

    /** Wipe after [days] days without an input session: 0 (off) or 3..365. The clock starts now, so an old time cannot wipe at once. */
    fun setInactivityDays(days: Int): Change {
        if (days != 0 && days !in 3..365) return Change.OUT_OF_RANGE
        val result = update { it.copy(inactivityDays = days) }
        if (result == Change.OK && days != 0 && !activity.save(clock())) return Change.NOT_SAVED
        return result
    }

    fun setDuressMode(mode: TriggerMode) = update { it.copy(duressMode = mode) }
    fun setWordMode(mode: TriggerMode) = update { it.copy(wordMode = mode) }
    fun setCommitOnSpace(on: Boolean) = update { it.copy(commitOnSpace = on) }
    fun setHoldEnter(on: Boolean) = update { it.copy(holdEnter = on) }
    fun setCheckInPasswordFields(on: Boolean) = update { it.copy(checkInPasswordFields = on) }
    fun setScope(scope: WipeScope) = update { it.copy(scope = scope) }

    /** Triggers only show that they were recognised for [SelfDestructPolicy.REHEARSAL_MILLIS] from now. */
    fun startRehearsal(): Change = update { it.copy(rehearsalUntil = clock() + SelfDestructPolicy.REHEARSAL_MILLIS) }
    fun stopRehearsal(): Change = update { it.copy(rehearsalUntil = 0L) }
}
