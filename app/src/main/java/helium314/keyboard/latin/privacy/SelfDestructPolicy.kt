// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

/** What to do about a trigger: nothing, only show that it was recognised (rehearsal), ask first, or wipe. */
sealed interface Decision {
    object None : Decision
    data class Rehearse(val trigger: Trigger) : Decision
    data class Confirm(val trigger: Trigger) : Decision
    data class Wipe(val trigger: Trigger) : Decision
}

/**
 * The one place that decides whether a trigger wipes. Pure: the configuration, the state and the clock come in, a
 * [Decision] goes out. A damaged value never switches a wipe off (rehearsal ends by itself) and a damaged counter counts as exhausted.
 */
class SelfDestructPolicy(private val clock: () -> Long) {
    /** The outcome of a trigger that was recognised elsewhere (the matcher, the PIN check, the panic button). */
    fun decide(trigger: Trigger, config: SelfDestructConfig): Decision {
        if (rehearsing(config)) return Decision.Rehearse(trigger)
        return when (trigger) {
            Trigger.DURESS_PIN -> byMode(trigger, config.duressMode)
            Trigger.TRIGGER_WORD -> byMode(trigger, config.wordMode)
            Trigger.WRONG_PINS, Trigger.INACTIVITY -> Decision.Wipe(trigger) // no screen to ask on
            Trigger.PANIC -> Decision.Confirm(trigger) // manual, so it always asks (the caller says when it was already confirmed)
        }
    }

    private fun byMode(trigger: Trigger, mode: TriggerMode) = if (mode == TriggerMode.CONFIRM) Decision.Confirm(trigger) else Decision.Wipe(trigger)

    /** The rehearsal lasts at most [REHEARSAL_MILLIS] from now: a far-future end (damage, or a clock set back) is not a rehearsal. */
    private fun rehearsing(config: SelfDestructConfig): Boolean {
        val left = config.rehearsalUntil - clock()
        return left > 0 && left <= REHEARSAL_MILLIS
    }

    /** The outcome of an app-lock PIN attempt: the duress PIN, or the count of consecutive wrong PINs reaching the limit. */
    fun onPin(result: AppLock.Verify, config: SelfDestructConfig): Decision = when (result) {
        is AppLock.Verify.Duress -> if (config.duress != null) decide(Trigger.DURESS_PIN, config) else Decision.None
        is AppLock.Verify.Wrong -> if (config.wrongPins > 0 && result.failedAttempts >= config.wrongPins) decide(Trigger.WRONG_PINS, config) else Decision.None
        // a lock record that cannot be decoded is a corrupt counter: it counts as exhausted (design A5)
        is AppLock.Verify.Corrupt -> if (config.wrongPins > 0) decide(Trigger.WRONG_PINS, config) else Decision.None
        else -> Decision.None
    }

    /** The outcome of the inactivity check. A missing time starts the clock (the caller writes it); an unreadable one fires nothing; a corrupt one counts as exhausted. */
    fun inactivity(state: ActivityState, config: SelfDestructConfig): Decision {
        if (config.inactivityDays == 0) return Decision.None
        return when (state) {
            ActivityState.Missing, ActivityState.Unavailable -> Decision.None
            ActivityState.Corrupt -> decide(Trigger.INACTIVITY, config)
            is ActivityState.Present ->
                // a clock set back gives a negative elapsed time: no wipe (known gap, needs an unlocked phone)
                if (clock() - state.lastActive >= config.inactivityDays * DAY_MILLIS) decide(Trigger.INACTIVITY, config) else Decision.None
        }
    }

    companion object {
        const val REHEARSAL_MILLIS = 15 * 60_000L
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}
