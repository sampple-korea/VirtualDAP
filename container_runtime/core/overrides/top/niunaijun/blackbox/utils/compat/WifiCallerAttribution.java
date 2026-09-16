package top.niunaijun.blackbox.utils.compat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Narrow API 34+ IWifiManager caller mapping; never changes network data or permissions. */
public final class WifiCallerAttribution {
    private WifiCallerAttribution() {}

    public static Object invoke(Object target, Method method, Object[] args,
                                String guestPackage, String hostPackage) throws Throwable {
        Object[] forwarded = args;
        Class<?>[] parameters = method.getParameterTypes();
        // Android 14/16: getConnectionInfo(String callingPackage, String callingFeatureId).
        // A feature/attribution tag can also equal the package name; it is NOT a caller field.
        if ("getConnectionInfo".equals(method.getName()) && parameters.length == 2
                && parameters[0] == String.class && parameters[1] == String.class
                && args != null && args.length == 2
                && guestPackage != null && !guestPackage.isEmpty()
                && hostPackage != null && !hostPackage.isEmpty()
                && guestPackage.equals(args[0])) {
            forwarded = args.clone();
            forwarded[0] = hostPackage;
        }
        try {
            return method.invoke(target, forwarded);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
