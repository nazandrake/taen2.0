package clean;

import android.app.Activity;

/** The control: no violation. selftest.py runs the same checks on it and they must pass. */
public class Clean extends Activity {
    String upper(String s) { return s.toUpperCase(); }

    /** The one microphone API the app may use: samples are read into memory; S6 lists it and passes. */
    int mic() { return android.media.AudioRecord.getMinBufferSize(16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT); }
}
