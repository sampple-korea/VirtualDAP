package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import java.lang.reflect.Method;
import black.android.net.wifi.BRIWifiManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.utils.compat.WifiCallerAttribution;

/** Real Android Wi-Fi service, including redacted identifiers and actual permission failures. */
public final class IWifiManagerProxy extends BinderInvocationStub {
    public IWifiManagerProxy() {
        super(BRServiceManager.get().getService(Context.WIFI_SERVICE));
    }

    @Override protected Object getWho() {
        return BRIWifiManagerStub.get().asInterface(
                BRServiceManager.get().getService(Context.WIFI_SERVICE));
    }

    @Override protected void inject(Object base, Object proxy) {
        replaceSystemService(Context.WIFI_SERVICE);
    }

    @Override public boolean isBadEnv() { return false; }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        return WifiCallerAttribution.invoke(getBase(), method, args,
                BActivityThread.getAppPackageName(), BlackBoxCore.getHostPkg());
    }
}
