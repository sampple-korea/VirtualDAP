package top.niunaijun.blackbox.utils;

import android.content.Intent;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Forward application payloads opaquely; only the receiving application owns their classes. */
public final class IntentSanitizer {
    private IntentSanitizer() {}

    public static void sanitizeClassExtrasForIpc(Intent intent) {
        // Intentionally do not enumerate Bundle values in the control process. It cannot load
        // guest parcelables/serializables (including modular login controllers). Android's
        // parcel transport can forward them without constructing or deleting their contents.
    }

    public static void restoreSanitizedClassExtras(Intent intent, ClassLoader classLoader) {
        if (intent == null || classLoader == null) return;
        Set<Intent> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Intent current = intent; current != null && seen.add(current); current = current.getSelector()) {
            current.setExtrasClassLoader(classLoader);
        }
    }
}
