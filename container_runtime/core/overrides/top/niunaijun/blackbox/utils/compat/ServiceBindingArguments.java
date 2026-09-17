package top.niunaijun.blackbox.utils.compat;

/** API 34/36 ActivityManager bind contracts, without Android dependencies for regression tests. */
public final class ServiceBindingArguments {
    private ServiceBindingArguments() {}

    public static int callerIndex(String method, Object[] args) {
        int caller;
        if ("bindService".equals(method)) caller = 6;
        else if ("bindServiceInstance".equals(method) || "bindIsolatedService".equals(method)) caller = 7;
        else return -1;
        if (args == null || args.length != caller + 2 ||
                !(args[5] instanceof Integer || args[5] instanceof Long) ||
                !(args[caller + 1] instanceof Integer) ||
                !(args[caller] == null || args[caller] instanceof String) ||
                (caller == 7 && !(args[6] == null || args[6] instanceof String))) return -1;
        return caller;
    }

    public static Object[] prepare(String method, Object[] args, boolean containerProxy,
                                   String guest, String host) {
        int caller = callerIndex(method, args);
        if (caller < 0) return args;
        Object[] forwarded = args.clone();
        if (host != null && !host.isEmpty() &&
                (containerProxy || (guest != null && !guest.isEmpty() && guest.equals(args[caller])))) {
            forwarded[caller] = host;
        }
        // A real isolated/external service (notably the WebView renderer) needs its instance
        // name and original flags. Only the container's non-isolated stub is multiplexed by extras.
        if (containerProxy) {
            if (caller == 7) forwarded[6] = null;
            if (args[5] instanceof Long) forwarded[5] = (Long) args[5] & ~0x80000000L;
            else forwarded[5] = (Integer) args[5] & ~0x80000000;
        }
        return forwarded;
    }
}
