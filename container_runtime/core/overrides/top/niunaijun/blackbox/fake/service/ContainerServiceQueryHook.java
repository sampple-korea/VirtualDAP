package top.niunaijun.blackbox.fake.service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.compat.ParceledListSliceCompat;

/** Return real parsed container service records and normally visible host services. */
public final class ContainerServiceQueryHook extends MethodHook {
    @Override protected String getMethodName() { return "queryIntentServices"; }

    @Override @SuppressWarnings("unchecked")
    protected Object hook(Object who, Method method, Object[] args) throws Throwable {
        Intent intent = (Intent) args[0];
        int flags = MethodParameterUtils.toInt(args[2]);
        int user = BlackBoxCore.getUserId();
        List<ResolveInfo> local = BlackBoxCore.getBPackageManager().getServiceWithFallback()
                .queryIntentServices(intent, flags, user);
        Intent target = intent.getComponent() == null && intent.getSelector() != null
                ? intent.getSelector() : intent;
        String packageName = target.getComponent() != null
                ? target.getComponent().getPackageName() : target.getPackage();
        boolean containerTarget = packageName != null && BlackBoxCore.get().isInstalled(packageName, user);
        LinkedHashMap<ComponentName, ResolveInfo> matches = new LinkedHashMap<>();
        if (!containerTarget) {
            // Keep Android's actual package visibility and caller permissions for host queries.
            MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
            Object host = method.invoke(who, args);
            List<ResolveInfo> visible = host instanceof List ? (List<ResolveInfo>) host
                    : host == null ? null : Reflector.with(host).method("getList").call();
            add(matches, visible);
        }
        add(matches, local); // An imported package owns its own component identity.
        List<ResolveInfo> result = new java.util.ArrayList<>(matches.values());
        return ParceledListSliceCompat.isReturnParceledListSlice(method)
                ? ParceledListSliceCompat.create(result) : result;
    }

    private static void add(LinkedHashMap<ComponentName, ResolveInfo> matches, List<ResolveInfo> records) {
        if (records == null) return;
        for (ResolveInfo record : records) {
            if (record != null && record.serviceInfo != null) {
                matches.put(new ComponentName(record.serviceInfo.packageName, record.serviceInfo.name), record);
            }
        }
    }
}
