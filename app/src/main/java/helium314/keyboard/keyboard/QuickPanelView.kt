// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.quick.QuickControl
import helium314.keyboard.latin.quick.QuickSettings
import helium314.keyboard.latin.quick.ScreenState
import helium314.keyboard.latin.settings.EmojiHold
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.FoldableUtils
import helium314.keyboard.latin.utils.prefs
import java.util.EnumMap

/**
 * The quick settings panel (toolbar key QUICK_SETTINGS): nine controls on top of the keyboard. What each control writes is decided by [QuickSettings]
 * (INCLUDED device-protected keys only); the three layout toggles go through the keyboard's own key codes, which write the same keys.
 * Built in code from static strings, nothing is stored or logged.
 */
class QuickPanelView(context: Context, private val listener: KeyboardActionListener) : LinearLayout(context) {
    private val switches = EnumMap<QuickControl, TextView>(QuickControl::class.java)
    private lateinit var heightLabel: TextView
    private lateinit var emojiHoldButton: TextView
    private val palette = Settings.getValues().mColors

    /** True while a control is being carried out: the keyboard reload it causes must not close the panel. */
    var keepOpen = false
        private set

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        visibility = GONE
        isClickable = true // touches on the panel never reach the keys below it
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setBackgroundColor(palette.get(ColorType.STRIP_BACKGROUND) or 0xFF000000.toInt())
        val titles = mapOf(
            QuickControl.NUMBER_ROW to R.string.quick_number_row, QuickControl.ONE_HANDED to R.string.quick_one_handed, QuickControl.SPLIT to R.string.quick_split,
            QuickControl.FLOATING to R.string.quick_floating, QuickControl.HAPTICS to R.string.quick_haptics, QuickControl.SOUND to R.string.quick_sound,
            QuickControl.GLIDE to R.string.quick_glide, QuickControl.EMOJI_KEY to R.string.quick_emoji_key,
        )
        val order = listOf(
            listOf(QuickControl.NUMBER_ROW, QuickControl.ONE_HANDED, QuickControl.SPLIT, QuickControl.FLOATING),
            listOf(QuickControl.HAPTICS, QuickControl.SOUND, QuickControl.GLIDE, QuickControl.EMOJI_KEY),
        )
        for (rowControls in order) {
            val row = newRow()
            for (c in rowControls) {
                val b = button(context.getString(titles.getValue(c))) { perform(c) }
                b.tag = c
                switches[c] = b
                row.addView(b)
            }
            addView(row)
        }
        val holdRow = newRow()
        emojiHoldButton = button("") { cycleEmojiHold() }
        holdRow.addView(emojiHoldButton)
        addView(holdRow)
        val heightRow = newRow()
        val down = button("−") { perform(QuickControl.HEIGHT, up = false) }
        heightLabel = TextView(context).apply { gravity = Gravity.CENTER; setTextColor(palette.get(ColorType.KEY_TEXT)); textSize = 14f }
        val up = button("+") { perform(QuickControl.HEIGHT, up = true) }
        down.contentDescription = context.getString(R.string.quick_height_down)
        up.contentDescription = context.getString(R.string.quick_height_up)
        heightRow.addView(down)
        heightRow.addView(heightLabel, LayoutParams(0, LayoutParams.MATCH_PARENT, 2f))
        heightRow.addView(up)
        heightRow.addView(button(context.getString(R.string.quick_close)) { hide() })
        addView(heightRow)
    }

    private fun newRow() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
    }

    private fun button(text: String, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 14f
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
    }

    private fun paint(b: TextView, active: Boolean) {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(palette.get(if (active) ColorType.ACTION_KEY_BACKGROUND else ColorType.KEY_BACKGROUND) or 0xFF000000.toInt())
        }
        b.background = shape
        b.setTextColor(palette.get(if (active) ColorType.ACTION_KEY_ICON else ColorType.KEY_TEXT))
        b.isActivated = active
    }

    /** The screen state that picks the keys (as the settings code does). */
    private fun screen() = ScreenState(
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        split = Settings.getValues().mIsSplitKeyboardEnabled,
        folded = FoldableUtils.isFolded,
        widthPixels = resources.displayMetrics.widthPixels,
    )

    /** Reads the state of every control from the preferences. */
    fun refresh() {
        val prefs = context.prefs()
        val s = screen()
        for ((c, b) in switches) paint(b, QuickSettings.isOn(prefs, c, s))
        heightLabel.text = context.getString(R.string.quick_height, (QuickSettings.height(prefs, s) * 100f + 0.5f).toInt())
        emojiHoldButton.text = context.getString(R.string.quick_emoji_hold, context.getString(emojiHoldName(QuickSettings.emojiHold(prefs))))
        paint(emojiHoldButton, QuickSettings.emojiHold(prefs) != EmojiHold.NONE)
    }

    private fun emojiHoldName(hold: EmojiHold) = when (hold) {
        EmojiHold.COMMA -> R.string.emoji_hold_comma
        EmojiHold.PERIOD -> R.string.emoji_hold_period
        EmojiHold.SPACE -> R.string.emoji_hold_space
        EmojiHold.LANGUAGE -> R.string.emoji_hold_language
        EmojiHold.NONE -> R.string.emoji_hold_none
    }

    /** Steps the key that opens emoji when held; the keyboard parses its keys again (the popup keys of that key change) and the panel stays open. Public for the wiring test. */
    fun cycleEmojiHold() {
        keepOpen = true
        try {
            QuickSettings.cycleEmojiHold(context.prefs())
            KeyboardSwitcher.getInstance().reloadKeyboard()
            refresh()
            visibility = VISIBLE
        } finally {
            keepOpen = false
        }
    }

    fun show() { refresh(); visibility = VISIBLE }
    fun hide() { visibility = GONE }

    private fun code(c: Int) = listener.onCodeInput(c, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)

    /** Carries out [control]; for HEIGHT [up] gives the direction. Public for the wiring test, which presses the same path as a tap. */
    fun perform(control: QuickControl, up: Boolean = true) {
        keepOpen = true
        try {
            val prefs = context.prefs()
            val s = screen()
            when (control) {
                QuickControl.ONE_HANDED -> code(KeyCode.TOGGLE_ONE_HANDED_MODE)
                QuickControl.SPLIT -> code(KeyCode.SPLIT_LAYOUT)
                QuickControl.FLOATING -> code(KeyCode.TOGGLE_FLOATING_WINDOW)
                QuickControl.NUMBER_ROW, QuickControl.EMOJI_KEY -> { QuickSettings.toggle(prefs, control, s); KeyboardSwitcher.getInstance().reloadKeyboard() }
                QuickControl.HAPTICS, QuickControl.SOUND, QuickControl.GLIDE -> QuickSettings.toggle(prefs, control, s)
                QuickControl.HEIGHT -> { QuickSettings.stepHeight(prefs, s, up); KeyboardSwitcher.getInstance().reloadKeyboard() }
            }
            refresh()
            visibility = VISIBLE
        } finally {
            keepOpen = false
        }
    }

    companion object {
        fun layoutParams() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.BOTTOM) // covers the keys below it (seen on the reference phone: with wrap_content the top row of keys peeked out)
    }
}
