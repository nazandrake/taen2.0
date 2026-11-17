package helium314.keyboard.event

import android.view.HapticFeedbackConstants

enum class HapticEvent(@JvmField val feedbackConstant: Int, @JvmField val allowCustomDuration: Boolean) {
    NO_HAPTICS(HapticFeedbackConstants.NO_HAPTICS, false),
    KEY_PRESS(HapticFeedbackConstants.KEYBOARD_TAP, true),
    KEY_LONG_PRESS(HapticFeedbackConstants.LONG_PRESS, true),
    KEY_REPEAT(HapticFeedbackConstants.KEYBOARD_TAP, allowCustomDuration = false),
    GESTURE_MOVE(HapticFeedbackConstants.CLOCK_TICK, false),
}
