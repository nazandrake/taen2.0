package helium314.keyboard.latin.utils

import android.content.Context
import android.view.ContextThemeWrapper
import helium314.keyboard.latin.R

fun getPlatformDialogThemeContext(context: Context): Context {
    // Because {@link AlertDialog.Builder.create()} doesn't honor the specified theme with
    // createThemeContextWrapper=false, the result dialog box has unneeded paddings around it.
    return ContextThemeWrapper(context, R.style.platformActivityTheme)
}
