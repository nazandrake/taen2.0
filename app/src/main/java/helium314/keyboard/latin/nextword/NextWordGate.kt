// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword

/**
 * The decisions about the next-word model, in one place: whether a model may be used on this phone ([readiness]) and whether it runs for the field the
 * keyboard is in ([runs]). Unknown or unreadable state is the restrictive outcome: a speed result that cannot be read counts as "not tested".
 */
object NextWordGate {
    /** The reference gate of a model measured on the reference phone: 95 % of the steps within 150 ms and at most 300 MB added. */
    const val MAX_P95_MS = 150L
    const val MAX_EXTRA_MB = 300L

    /**
     * The test on each phone, for a model that is too big for the reference gate ([NextWordModel.speedTestOnEachPhone]): 95 % of the steps within 250 ms,
     * and the memory the phone reported free before the load covers what the model added plus a 1 GB margin for the apps in use.
     */
    const val PHONE_MAX_P95_MS = 250L
    const val PHONE_MARGIN_MB = 1024L

    /**
     * The result of the speed test on this phone: the 95th percentile of the step time, the memory the model added and the memory the phone reported free
     * (ActivityManager.MemoryInfo.availMem) before the load. When too little was free to try, the model is not loaded: [p95Ms] is 0 and [extraMb] is the
     * size of the model file, which it needs at least.
     */
    class Speed(val p95Ms: Long, val extraMb: Long, val freeMb: Long) {
        val fastEnough: Boolean get() = p95Ms in 0..PHONE_MAX_P95_MS
        // added memory that could not be read (NextWordSpeedTest.UNKNOWN_MB) never passes, however much is free; a negative free figure is below the margin
        val enoughMemory: Boolean get() = extraMb in 0 until NextWordSpeedTest.UNKNOWN_MB && freeMb - extraMb >= PHONE_MARGIN_MB
        val passes: Boolean get() = fastEnough && enoughMemory

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

    enum class Readiness {
        /** No verified model file. */
        NOT_INSTALLED,
        /** The model failed the reference measurement of the project (accuracy, or speed and memory on the reference phone): it is not offered. */
        DISABLED_BY_GATE,
        /** Installed, but this phone has not run the speed test yet. */
        NEEDS_SPEED_TEST,
        /** The speed test failed on this phone: the user is told and offered Remove. */
        TOO_SLOW,
        READY,
    }

    fun readiness(model: NextWordModel, installed: Boolean, speed: Speed?): Readiness = when {
        !installed -> Readiness.NOT_INSTALLED
        !model.reference.adopted -> Readiness.DISABLED_BY_GATE
        !model.speedTestOnEachPhone -> Readiness.READY
        speed == null -> Readiness.NEEDS_SPEED_TEST
        speed.passes -> Readiness.READY
        else -> Readiness.TOO_SLOW
    }

    /**
     * Whether the model reads the text of this field: the model switch is on, the strip shows suggestions and next-word predictions are on, the field
     * allows personalized suggestions (PrivacyPolicy: not a password, incognito, no-learning or locked field) and the model of the language is ready.
     */
    fun runs(modelSwitch: Boolean, suggestionsShown: Boolean, predictionsOn: Boolean, personalized: Boolean, readiness: Readiness): Boolean =
        modelSwitch && suggestionsShown && predictionsOn && personalized && readiness == Readiness.READY
}
