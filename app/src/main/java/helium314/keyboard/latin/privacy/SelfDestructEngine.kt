// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

/** What the engine asks the outside world to do (the Android side shows the dialog or toast and runs the wipe). */
interface WipeEffects {
    fun wipe(trigger: Trigger, scope: WipeScope)
    fun confirm(trigger: Trigger)
    fun rehearsed(trigger: Trigger)
}

/** The key that ends a word or a line. The trigger word is only checked on these. */
enum class CommitKey { ENTER, SPACE }

/**
 * What the engine needs to know about the field: a password field, one of the keyboard's own settings fields, and which characters
 * the input logic treats as word separators (a trigger word has none, see [TriggerMatcher.couldBeWord]).
 */
class FieldContext(val password: Boolean, val ownApp: Boolean, val isWordSeparator: (Int) -> Boolean)

/**
 * A check that is due: the token before the cursor is 12 to 64 characters long and the configuration asks for a check on this key in this field.
 * [run] hashes once and acts on a match; it can run only once (the token is cleared by it).
 */
class WordCheck internal constructor(
    val holdEnter: Boolean,
    val tokenLength: Int,
    private var token: CharArray?,
    private val record: SecretRecord,
    private val matcher: TriggerMatcher,
    private val onMatch: () -> Unit,
) {
    /** True when the token is the trigger word. Throws [IllegalStateException] when it already ran: its token is gone. */
    fun run(): Boolean {
        val t = token ?: throw IllegalStateException("the check already ran")
        token = null
        val matched = matcher.matches(t, record)
        if (matched) onMatch()
        return matched
    }
}

/**
 * The triggers of the self-destruct over the stored configuration: the PIN attempt, the commit key, the start of an
 * input session (inactivity), and the panic. It reads the configuration through [configs] (an unreadable one fires nothing), asks
 * [policy] what to do and tells [effects]. The wipe itself is not here.
 */
class SelfDestructEngine(
    private val configs: ConfigStorage,
    private val activity: ActivityStorage,
    private val policy: SelfDestructPolicy,
    private val clock: () -> Long,
    private val matcher: TriggerMatcher,
    private val effects: WipeEffects,
) {
    /** The configuration, the defaults when none was saved, or null when it cannot be read. */
    private fun load(): SelfDestructConfig? = when (val l = configs.load()) {
        is ConfigLoad.None -> SelfDestructConfig.DEFAULT
        is ConfigLoad.Present -> l.config
        is ConfigLoad.Unavailable -> null
    }

    private fun execute(decision: Decision, config: SelfDestructConfig) {
        when (decision) {
            Decision.None -> Unit
            is Decision.Rehearse -> effects.rehearsed(decision.trigger)
            is Decision.Confirm -> effects.confirm(decision.trigger)
            is Decision.Wipe -> effects.wipe(decision.trigger, config.scope)
        }
    }

    /** An app-lock PIN attempt ended with [result]: the duress PIN, or too many wrong PINs, may start a wipe. */
    fun onPin(result: AppLock.Verify): Decision {
        val config = load() ?: return Decision.None
        return policy.onPin(result, config).also { execute(it, config) }
    }

    /**
     * Called when [key] is pressed. Returns the check when one is due, else null; [text] (the text before the cursor, a call into the
     * app) is only asked for when the configuration wants a check on this key in this field, and nothing is hashed here.
     */
    fun prepareCheck(key: CommitKey, field: FieldContext, text: () -> CharSequence?): WordCheck? {
        val config = load() ?: return null
        val record = config.word ?: return null
        if (key == CommitKey.SPACE && !config.commitOnSpace) return null
        if (field.ownApp) return null // never in the keyboard's own settings fields
        if (field.password && !config.checkInPasswordFields) return null
        val before = text() ?: return null
        val token = matcher.tokenOf(before, field.isWordSeparator) ?: return null
        return WordCheck(config.holdEnter, token.size, token, record, matcher) {
            execute(policy.decide(Trigger.TRIGGER_WORD, config), config)
        }
    }

    /**
     * True when [word] (about to be learned) could be the trigger word and one is set: it is kept out of the learned words, like a token
     * of a due check, so a word left in the field and committed later (cursor moved, then punctuation) is not learned either. No hash.
     */
    fun keepsOutOfLearning(word: CharSequence, isWordSeparator: (Int) -> Boolean): Boolean =
        TriggerMatcher.couldBeWord(word, isWordSeparator) && load()?.word != null

    /** The daily job and the start check the inactivity limit without counting as use. */
    fun checkInactivity(): Decision {
        val config = load() ?: return Decision.None
        return policy.inactivity(activity.load(), config).also { execute(it, config) }
    }

    /** An input session starts: checks the inactivity limit first, then records that the keyboard is in use (at most once per [TOUCH_INTERVAL_MILLIS]). */
    fun onInputStart(): Decision {
        val decision = checkInactivity()
        if (decision != Decision.None) return decision // wiping or rehearsing: the time is not renewed
        val now = clock()
        when (val state = activity.load()) {
            ActivityState.Missing, ActivityState.Corrupt -> activity.save(now)
            ActivityState.Unavailable -> Unit
            is ActivityState.Present -> if (now < state.lastActive || now - state.lastActive >= TOUCH_INTERVAL_MILLIS) activity.save(now)
        }
        return decision
    }

    /** The panic button: asks first unless [confirmed]. It works without any configuration (everything is wiped then). */
    fun panic(confirmed: Boolean): Decision {
        val config = load() ?: SelfDestructConfig.DEFAULT
        val decision = policy.decide(Trigger.PANIC, config).let { if (it is Decision.Confirm && confirmed) Decision.Wipe(Trigger.PANIC) else it }
        execute(decision, config)
        return decision
    }

    companion object {
        const val TOUCH_INTERVAL_MILLIS = 60 * 60_000L
    }
}
