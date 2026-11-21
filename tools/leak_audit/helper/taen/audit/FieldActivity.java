package taen.audit;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputContentInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Test target of the Taen leak audit (tools/leak_audit). Three text fields, the one named by --es focus gets the keyboard:
 * normal (plain text), password (textPassword), nolearn (plain text with IME_FLAG_NO_PERSONALIZED_LEARNING).
 * --es copy TEXT [--ez sensitive true] [--ei delay MS]  puts TEXT on the clipboard MS (default 2000) after the field has focus (what the keyboard's clipboard capture sees)
 * focus=multiline: a multi-line plain text field (the space-bar line moves, selection and several cursors are checked in it)
 * focus=image: a fourth field that announces image/gif, image/png, image/jpeg and image/webp (EditorInfo.contentMimeTypes) and, when the keyboard commits a picture,
 * reads the content URI and logs "image received: type=... bytes=N sha256=..." (the receiver of the picture check)
 * focus=number: a number field; focus=numberpassword: a number field with the password variation (the keyboard's strip has no paste, select all or clear all there)
 * --ez read true                      reads the primary clip while in front and logs it, and whether it is flagged sensitive (what another app can read)
 * --ez keyboard false                  no focus and no keyboard: a plain white screen (the control for the screenshot and recording checks)
 * --ez overlay true                     draws a small touchable overlay over a corner of the keyboard (tapjacking)
 * Logs only with tag AuditHelper. It has no network permission either.
 */
public class FieldActivity extends Activity {
    private View overlay;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 120, 24, 24);
        TextView title = new TextView(this);
        title.setText("Taen audit target");
        root.addView(title);
        EditText normal = field("normal", InputType.TYPE_CLASS_TEXT, 0);
        EditText password = field("password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 0);
        EditText nolearn = field("nolearn", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        EditText autocorrect = field("autocorrect", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT, 0);
        EditText multiline = field("multiline", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0);
        multiline.setSingleLine(false);
        multiline.setMinLines(4);
        EditText number = field("number", InputType.TYPE_CLASS_NUMBER, 0);
        EditText numberPassword = field("numberpassword", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0);
        EditText image = new EditText(this) {
            @Override public InputConnection onCreateInputConnection(EditorInfo info) {
                InputConnection ic = super.onCreateInputConnection(info);
                info.contentMimeTypes = new String[] {"image/gif", "image/png", "image/jpeg", "image/webp"};
                return new InputConnectionWrapper(ic, false) {
                    @Override public boolean commitContent(InputContentInfo content, int flags, Bundle opts) {
                        receive(content, flags);
                        return true;
                    }
                };
            }
        };
        image.setContentDescription("field_image");
        image.setHint("image");
        image.setSingleLine(true);
        root.addView(normal);
        root.addView(autocorrect);
        root.addView(image);
        root.addView(password);
        root.addView(nolearn);
        root.addView(multiline);
        root.addView(number);
        root.addView(numberPassword);
        setContentView(root);
        String focus = getIntent().getStringExtra("focus");
        EditText target = "number".equals(focus) ? number : "numberpassword".equals(focus) ? numberPassword : "image".equals(focus) ? image :"password".equals(focus) ? password : "nolearn".equals(focus) ? nolearn : "autocorrect".equals(focus) ? autocorrect : "multiline".equals(focus) ? multiline : normal;
        if (getIntent().getBooleanExtra("keyboard", true)) {
            target.requestFocus();
        } else {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            root.setBackgroundColor(Color.WHITE);
        }
        final String copy = getIntent().getStringExtra("copy");
        if (copy != null) {
            final boolean sensitive = getIntent().getBooleanExtra("sensitive", false);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("audit", copy);
                if (sensitive) {
                    PersistableBundle extras = new PersistableBundle();
                    extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                    clip.getDescription().setExtras(extras);
                }
                cm.setPrimaryClip(clip);
                Log.i("AuditHelper", "clip set, sensitive=" + sensitive);
            }, getIntent().getIntExtra("delay", 2000));
        }
        if (getIntent().getBooleanExtra("overlay", false)) addOverlay();
    }

    /** Reads the picture that the keyboard handed over (the read grant of the commit) and logs its type, length and SHA-256; never its content. */
    private void receive(InputContentInfo content, int flags) {
        try {
            if ((flags & InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0) content.requestPermission();
            java.io.InputStream in = getContentResolver().openInputStream(content.getContentUri());
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            long total = 0;
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) { digest.update(buffer, 0, n); total += n; }
            in.close();
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            Log.i("AuditHelper", "image received: type=" + content.getDescription().getMimeType(0) + " bytes=" + total + " sha256=" + hex + " uri=" + content.getContentUri());
        } catch (Exception e) {
            Log.i("AuditHelper", "image read failed: " + e.getClass().getSimpleName());
        }
    }

    private EditText field(String name, int inputType, int imeFlags) {
        EditText e = new EditText(this);
        e.setContentDescription("field_" + name);
        e.setHint(name);
        e.setInputType(inputType);
        if (imeFlags != 0) e.setImeOptions(imeFlags);
        e.setSingleLine(true);
        return e;
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && getIntent().getBooleanExtra("read", false)) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData c = cm.getPrimaryClip();
            Log.i("AuditHelper", "primary clip read: " + (c == null || c.getItemCount() == 0 ? "none" : "text=" + c.getItemAt(0).getText()));
            PersistableBundle extras = c == null ? null : c.getDescription().getExtras();
            Log.i("AuditHelper", "primary clip sensitive: " + (extras != null && extras.getBoolean("android.content.extra.IS_SENSITIVE")));
        }
    }

    private void addOverlay() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        overlay = new View(this);
        overlay.setBackgroundColor(Color.argb(40, 255, 0, 0));
        // a small touchable window over a corner of the keyboard: a tap elsewhere on the keyboard arrives marked FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        // (a NOT_TOUCHABLE window does not count as covering)
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(300, 150,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.x = 0;
        p.y = 1300;
        try {
            wm.addView(overlay, p);
            Log.i("AuditHelper", "overlay added");
        } catch (RuntimeException e) {
            Log.i("AuditHelper", "overlay refused: " + e.getClass().getSimpleName());
            overlay = null;
        }
    }

    @Override protected void onDestroy() {
        if (overlay != null) ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).removeView(overlay);
        super.onDestroy();
    }
}
