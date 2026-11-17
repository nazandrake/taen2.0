// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * P20: the one place that decides what a tap on the voice key does. The microphone permission is asked for only when everything else
 * allows a recording (an enabled setting, a field that may be dictated into, a model on the device), so a user who never taps the key,
 * or taps it without a model, is never asked.
 */
object VoiceGate {
    enum class Action {
        /** Nothing happens: voice typing is off, the field is a password field, or the device is locked. */
        IGNORE,
        /** The model files are not (completely) on the device: say so and open the voice settings. */
        NEED_MODEL,
        /** The microphone permission is missing: ask for it (VoicePermissionActivity). */
        NEED_PERMISSION,
        START,
    }

    fun decide(enabled: Boolean, passwordField: Boolean, locked: Boolean, modelReady: Boolean, permissionGranted: Boolean): Action = when {
        !enabled || passwordField || locked -> Action.IGNORE
        !modelReady -> Action.NEED_MODEL
        !permissionGranted -> Action.NEED_PERMISSION
        else -> Action.START
    }
}

/** How recognised text is put into the field. */
object VoiceText {
    private const val NO_SPACE_AFTER = "([{\"'„“«‘-/\n\t "

    /** [text] with one leading space when the character before the cursor ([before], at most one character is read) is not a space, an opening mark or a separator. */
    fun forInsertion(before: CharSequence?, text: String): String {
        if (text.isEmpty()) return text
        val last = before?.lastOrNull() ?: return text
        return if (last in NO_SPACE_AFTER || last.isWhitespace() || text.first().isWhitespace()) text else " $text"
    }
}
