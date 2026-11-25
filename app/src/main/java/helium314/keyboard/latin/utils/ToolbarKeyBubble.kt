// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.privacy.PrivacyPolicy
import helium314.keyboard.latin.settings.Settings
import kotlin.math.max
import kotlin.math.min

/**
 * The label over a pressed toolbar key (name, and what a long press does). The pattern is the one of the letter key preview: a view of the
 * keyboard's own window, placed above the key while it is pressed, taken away when the finger lifts. No window of our own (no platform tooltip,
 * which would also never show for a key whose long press does something). The touch listener never consumes: the tap and the long press
 * of the key run as before. The label holds only the fixed names of the keys.
 */
class ToolbarKeyBubble {
    private var view: TextView? = null
    private var anchor: View? = null
    private val detachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) = hide() // a key that goes away during the press never sees the lift
    }

    /** Shows the label when [button] is pressed and takes it away when the touch ends; [onTouch] sees every event too. */
    @SuppressLint("ClickableViewAccessibility") // the listener never consumes: the click and the long click still work
    fun attach(button: View, key: ToolbarKey, hold: () -> ToolbarHold, onTouch: ((MotionEvent) -> Unit)? = null) {
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (allowedNow()) show(v, toolbarKeyBubbleText(v.context, key, hold())) else hide()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> hide()
            }
            onTouch?.invoke(event)
            false
        }
    }

    private fun show(button: View, text: String) {
        hide()
        val host = (button.rootView.findViewById<View>(android.R.id.content) ?: button.rootView) as? ViewGroup ?: return
        val bubble = TextView(button.context)
        val colors = Settings.getValues().mColors
        val resources = button.resources
        bubble.tag = TAG
        bubble.text = text
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        bubble.setTextColor(colors.get(ColorType.KEY_TEXT))
        bubble.setPadding(12.dpToPx(resources), 6.dpToPx(resources), 12.dpToPx(resources), 6.dpToPx(resources))
        bubble.background = GradientDrawable().apply {
            setColor(colors.get(ColorType.KEY_PREVIEW_BACKGROUND))
            cornerRadius = 8.dpToPx(resources).toFloat()
        }
        bubble.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO // a fixed label, no accessibility event for it
        bubble.isClickable = false
        bubble.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = bubble.measuredWidth
        val h = bubble.measuredHeight
        val onScreen = IntArray(2)
        button.getLocationInWindow(onScreen)
        val hostOrigin = IntArray(2)
        host.getLocationInWindow(hostOrigin)
        val centerX = onScreen[0] - hostOrigin[0] + button.width / 2
        val left = max(0, min(centerX - w / 2, max(0, host.width - w)))
        val top = max(0, onScreen[1] - hostOrigin[1] - h - 4.dpToPx(resources))
        host.addView(bubble, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            leftMargin = left
            topMargin = top
        })
        view = bubble
        anchor = button
        button.addOnAttachStateChangeListener(detachListener)
    }

    fun hide() {
        anchor?.removeOnAttachStateChangeListener(detachListener)
        anchor = null
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        view = null
    }

    companion object {
        const val TAG = "toolbar_key_bubble"

        /**
         * The one rule: the setting is on and the device is not locked. The lock is asked twice, as the Paste button does: the field's [decision]
         * (locked when its input started) and [lockedNow], the live screen lock (the lock may come with no input start since).
         */
        fun allowed(enabled: Boolean, decision: PrivacyPolicy.Decision, lockedNow: Boolean): Boolean =
            enabled && decision.toolbarKeyNames && !lockedNow

        private fun allowedNow(): Boolean {
            val values = Settings.getValues()
            return allowed(values.mShowToolbarKeyNames, values.mPrivacy, Privacy.isLocked)
        }
    }
}
