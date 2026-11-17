# Release only: remove every call of the platform logger (android.util.Log), also the ones inside libraries, so that no
# text typed in the keyboard can reach logcat. The apkScan rule "deny android/util/Log" (privacy/dex-rules.txt) fails the
# build if one is left. The app's own wrapper (helium314.keyboard.latin.utils.Log) keeps nothing: no buffer, no file.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static boolean isLoggable(...);
    public static java.lang.String getStackTraceString(...);
}

# Release only: the same for the other two platform routes to logcat. On Android System.out/System.err and
# java.util.logging end up in logcat; Compose (focus and key-event debug prints) and AndroidX (futures) call them. The audit
# (tools/leak_audit, check S5) fails when a call of either is left in the release dex.
-assumenosideeffects class java.io.PrintStream {
    public void print(...);
    public void println(...);
    public java.io.PrintStream printf(...);
    public java.io.PrintStream format(...);
}
-assumenosideeffects class java.util.logging.Logger {
    public void log(...);
    public void severe(...);
    public void warning(...);
    public void info(...);
    public void config(...);
    public void fine(...);
    public void finer(...);
    public void finest(...);
}
