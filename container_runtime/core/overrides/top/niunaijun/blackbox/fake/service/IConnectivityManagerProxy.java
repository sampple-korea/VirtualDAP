package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import black.android.net.BRIConnectivityManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;

/** Preserve Android's real networks, DNS, metering, callbacks and permission failures. */
public final class IConnectivityManagerProxy extends BinderInvocationStub {
    public IConnectivityManagerProxy() {
        super(BRServiceManager.get().getService(Context.CONNECTIVITY_SERVICE));
    }

    @Override protected Object getWho() {
        return BRIConnectivityManagerStub.get().asInterface(
                BRServiceManager.get().getService(Context.CONNECTIVITY_SERVICE));
    }

    @Override protected void inject(Object base, Object proxy) {
        replaceSystemService(Context.CONNECTIVITY_SERVICE);
    }

    @Override public boolean isBadEnv() { return false; }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // API 34+ IConnectivityManager caller fields, not arbitrary String arguments.
        // Attribution tags, network requests, UID arguments and remote results stay unchanged.
        int caller;
        switch (method.getName()) {
            case "getNetworkCapabilities":
            case "getDefaultNetworkCapabilitiesForUser": caller = 1; break;
            case "getRedactedNetworkCapabilitiesForPackage":
            case "getRedactedLinkPropertiesForPackage":
            case "requestRouteToHostAddress":
            case "pendingRequestForNetwork":
            case "pendingListenForNetwork":
            case "registerConnectivityDiagnosticsCallback": caller = 2; break;
            case "listenForNetwork": caller = 4; break;
            case "requestNetwork": caller = 8; break;
            default: caller = -1;
        }
        Object[] forwarded = args;
        String guest = BActivityThread.getAppPackageName();
        if (caller >= 0 && args != null && args.length > caller
                && guest != null && guest.equals(args[caller])) {
            forwarded = args.clone();
            forwarded[caller] = BlackBoxCore.getHostPkg();
        }
        try {
            return method.invoke(getBase(), forwarded);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }
}
