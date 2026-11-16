package helium314.keyboard.latin.utils

/**
 * Debug logging with a tag and a fixed message. It keeps nothing, takes no exception and writes no file: a retained log line
 * or stack trace could hold typed text (privacy invariant P11). privacyCheck requires every call to pass one string literal.
 * The release build removes the platform calls with R8 (app/proguard-release.pro).
 */
object Log {
    @JvmStatic
    fun wtf(tag: String?, message: String) {
        android.util.Log.wtf(tag, message)
    }

    @JvmStatic
    fun e(tag: String?, message: String) {
        android.util.Log.e(tag, message)
    }

    @JvmStatic
    fun w(tag: String?, message: String) {
        android.util.Log.w(tag, message)
    }

    @JvmStatic
    fun i(tag: String?, message: String) {
        android.util.Log.i(tag, message)
    }

    @JvmStatic
    fun d(tag: String?, message: String) {
        android.util.Log.d(tag, message)
    }

    @JvmStatic
    fun v(tag: String?, message: String) {
        android.util.Log.v(tag, message)
    }
}
