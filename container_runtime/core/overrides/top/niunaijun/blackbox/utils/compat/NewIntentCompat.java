package top.niunaijun.blackbox.utils.compat;

import android.content.Intent;
import android.os.IBinder;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import black.android.app.BRActivityThread;

/** Deliver to an existing activity in this process using the current framework signature. */
public final class NewIntentCompat {
    private NewIntentCompat() {}

    public static void deliver(Object thread, IBinder token, List<? extends Intent> intents) {
        Map<IBinder, Object> activities = BRActivityThread.get(thread).mActivities();
        Object record = activities == null ? null : activities.get(token);
        if (record == null) throw new IllegalStateException("New intent target is no longer alive");
        try {
            // Android 12+ takes ActivityClientRecord, not IBinder. Do not report success when
            // a reflection wrapper silently fails to find the obsolete signature.
            Class<?> recordClass = Class.forName("android.app.ActivityThread$ActivityClientRecord");
            Method method = thread.getClass().getDeclaredMethod("handleNewIntent", recordClass, List.class);
            method.setAccessible(true);
            method.invoke(thread, record, intents);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Activity rejected new intent", cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Unsupported Android new-intent dispatch", error);
        }
    }
}
