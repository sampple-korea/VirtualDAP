package top.niunaijun.blackbox.utils.compat;

import android.content.Intent;
import top.niunaijun.blackbox.proxy.record.ProxyBroadcastRecord;

/** Map a private music-space broadcast to its own real Android user, never other users. */
public final class ContainerBroadcastRouting {
    private ContainerBroadcastRouting() {}

    public static int intentIndex(String method, Object[] args) {
        // API 34–36 IActivityManager AIDL. Reject an unknown layout rather than changing a payload.
        if ("broadcastIntent".equals(method) && args != null && args.length == 13) return 1;
        if ("broadcastIntentWithFeature".equals(method) && args != null && args.length == 16) return 2;
        throw new IllegalArgumentException("Unsupported broadcast IPC layout: " + method);
    }

    public static Object[] prepare(String method, Object[] args, Intent shadow,
                                   int guestUser, int hostUser, String hostPackage) {
        int index = intentIndex(method, args);
        if (!(args[index] instanceof Intent) || !(args[args.length - 1] instanceof Integer)) {
            throw new IllegalArgumentException("Missing broadcast intent or user");
        }
        // No container shadow means no narrowing is possible: retain the original OS checks.
        if (shadow == null) return args;
        if (hostPackage == null || !hostPackage.equals(shadow.getPackage())
                || shadow.getComponent() != null || guestUser < 0 || hostUser < 0) {
            throw new SecurityException("Broadcast transport must remain private to the host");
        }
        int requestedUser = (Integer) args[args.length - 1];
        // ALL/CURRENT/CURRENT_OR_SELF refer only to the active, isolated music space here.
        // A different explicit virtual user is not this space and must not be silently remapped.
        if (requestedUser != guestUser && requestedUser != -1 && requestedUser != -2 && requestedUser != -3) {
            throw new SecurityException("Broadcast targets a different music-space user");
        }
        Intent transport = new Intent(shadow);
        ProxyBroadcastRecord.saveStub(transport, new Intent((Intent) args[index]), guestUser);
        Object[] forwarded = args.clone();
        forwarded[index] = transport;
        forwarded[forwarded.length - 1] = hostUser;
        // Required/excluded permissions, packages, app-op, result callback and options remain intact.
        return forwarded;
    }
}
