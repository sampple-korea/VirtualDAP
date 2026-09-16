package top.niunaijun.blackbox.fake.service.context.providers;

import android.content.AttributionSource;
import android.os.IInterface;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import black.android.content.BRAttributionSource;
import black.android.content.BRAttributionSourceState;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;

/** Attribute provider IPC to the actual caller, without changing queries or inventing results. */
public final class ContentProviderStub extends ClassInvocationStub implements BContentProvider {
    private static final Method COPY_WITH_PACKAGE = copyMethod();
    private IInterface base;

    private static Method copyMethod() {
        try {
            // Unlike API 34's Builder(copy).build(), this retains PID, tag, token, renounced
            // permissions and the downstream chain (plus deviceId on newer Android releases).
            return AttributionSource.class.getDeclaredMethod("withPackageName", String.class);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Provider attribution copying is unavailable", error);
        }
    }

    @Override public IInterface wrapper(IInterface provider, String appPkg) {
        base = provider;
        injectHook();
        return (IInterface) getProxyInvocation();
    }

    @Override protected Object getWho() { return base; }
    @Override protected void inject(Object original, Object proxy) {}
    @Override public boolean isBadEnv() { return false; }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object[] forwarded = args;
        // API 34+ IContentProvider puts the IPC caller in the first AttributionSource field.
        // Do not rewrite payload strings, Bundles, URIs, or downstream attribution identities.
        if (args != null && args.length > 0 && args[0] instanceof AttributionSource) {
            AttributionSource original = (AttributionSource) args[0];
            AttributionSource caller = (AttributionSource) COPY_WITH_PACKAGE.invoke(original, BlackBoxCore.getHostPkg());
            Object state = BRAttributionSource.get(caller).mAttributionSourceState();
            if (state == null) throw new IllegalStateException("Missing provider caller attribution state");
            BRAttributionSourceState.get(state)._set_uid(BlackBoxCore.getHostUid());
            if (caller.getUid() != BlackBoxCore.getHostUid()) {
                throw new IllegalStateException("Provider caller UID attribution was not applied");
            }
            forwarded = args.clone();
            forwarded[0] = caller;
        }
        try {
            return method.invoke(base, forwarded);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }
}
