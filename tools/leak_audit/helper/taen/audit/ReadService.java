package taen.audit;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/** An accessibility service that may read window content: what a spying app would be. Logs the event class and its text (tag AuditHelper). */
public class ReadService extends AccessibilityService {
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        Log.i("AuditHelper", "a11y type=" + e.getEventType() + " pkg=" + e.getPackageName() + " class=" + e.getClassName() + " text=" + e.getText());
    }

    @Override public void onInterrupt() { }
}
