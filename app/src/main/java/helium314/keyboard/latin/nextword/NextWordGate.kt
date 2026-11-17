// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword

/**
 * The decisions about the next-word model, in one place: whether a model may be used on this phone ([readiness]), whether it may be loaded now
 * ([mayLoadNow]) and whether it runs for the field the keyboard is in ([runs]). Unknown or unreadable state is the restrictive outcome: a speed result that
 * cannot be read counts as "not tested", memory that cannot be read means "not loaded".
 */
object NextWordGate {
    /** The accuracy part of the reference check: the model must put the next word among the first three at least 5 points more often. */
    const val MIN_GAIN_PER_MILLE = 50

    /** The memory left for the apps in use: the free memory must cover what the model adds plus this margin, at the test and before every load. */
    const val MARGIN_MB = 1024L

    /**
     * The result of the speed test on this phone: the 95th percentile of the step time, the memory the model added and the memory the phone reported free
     * (ActivityManager.MemoryInfo.availMem) before the load. When too little was free to try, the model is not loaded: [p95Ms] is 0 and [extraMb] is the
     * size of the model file, which it needs at least.
     */
    class Speed(val p95Ms: Long, val extraMb: Long, val freeMb: Long) {
        // added memory that could not be read (NextWordSpeedTest.UNKNOWN_MB) is not a measurement
        val measuredMemory: Boolean get() = extraMb in 0 until NextWordSpeedTest.UNKNOWN_MB

        fun fastEnough(model: NextWordModel): Boolean = p95Ms in 0..model.maxP95Ms

        // a negative free figure is below the margin
        val enoughMemory: Boolean get() = measuredMemory && freeMb - extraMb >= MARGIN_MB

        fun passes(model: NextWordModel): Boolean = fastEnough(model) && enoughMemory

        fun encode() = "p95=$p95Ms mb=$extraMb free=$freeMb"

        companion object {
            private val FORMAT = Regex("""p95=(\d{1,9}) mb=(\d{1,9}) free=(\d{1,9})""")

            /** Null for anything that is not exactly what [encode] writes. */
            fun decode(text: String?): Speed? {
                val m = FORMAT.matchEntire(text?.trim() ?: return null) ?: return null
                return Speed(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLong())
            }
        }
    }

    /**
     * Before every load of a ready model: the memory the phone reports free now ([freeMb], negative when it cannot be read) must cover what the model
     * added in this phone's test plus [MARGIN_MB]. A model that passed its test when the phone had memory to spare is not loaded while it has not.
     */
    fun mayLoadNow(speed: Speed?, freeMb: Long): Boolean = speed != null && speed.measuredMemory && freeMb - speed.extraMb >= MARGIN_MB

    enum class Readiness {
        /** No verified model file. */
        NOT_INSTALLED,
        /** The model is not enough better than the dictionary on the reference text: it is not offered. */
        DISABLED_BY_GATE,
        /** Installed, but this phone has not run the speed test yet. */
        NEEDS_SPEED_TEST,
        /** The keyboard stopped while it loaded the model (the system ended it, usually for memory): the speed test has to run again. */
        LOAD_CRASHED,
        /** The speed test failed on this phone (too slow or not enough memory): the user is told and offered Remove. */
        TOO_SLOW,
        READY,
    }

    fun readiness(model: NextWordModel, installed: Boolean, speed: Speed?, loadCrashed: Boolean = false): Readiness = when {
        !installed -> Readiness.NOT_INSTALLED
        !model.reference.adopted -> Readiness.DISABLED_BY_GATE
        loadCrashed -> Readiness.LOAD_CRASHED
        speed == null -> Readiness.NEEDS_SPEED_TEST
        speed.passes(model) -> Readiness.READY
        else -> Readiness.TOO_SLOW
    }

    /**
     * Whether the model reads the text of this field: the model switch is on, the strip shows suggestions and next-word predictions are on, the field
     * allows personalized suggestions (PrivacyPolicy: not a password, incognito, no-learning or locked field) and the model of the language is ready.
     */
    fun runs(modelSwitch: Boolean, suggestionsShown: Boolean, predictionsOn: Boolean, personalized: Boolean, readiness: Readiness): Boolean =
        modelSwitch && suggestionsShown && predictionsOn && personalized && readiness == Readiness.READY
}
