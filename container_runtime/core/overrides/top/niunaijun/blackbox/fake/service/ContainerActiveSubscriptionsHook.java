package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;
import top.niunaijun.blackbox.fake.hook.MethodHook;

/** A music space imports apps, not the device owner's physical SIM subscriptions. */
public final class ContainerActiveSubscriptionsHook extends MethodHook {
    @Override public String getMethodName() {
        return "getActiveSubIdList";
    }

    @Override protected Object hook(Object who, Method method, Object[] args) {
        if (method.getReturnType() != int[].class || args == null || args.length != 1
                || !(args[0] instanceof Boolean)) {
            throw new IllegalArgumentException("Unsupported active-subscription query layout");
        }
        // Both visible and hidden subscriptions are absent. Do not query host telephony,
        // invent subscription IDs, grant phone permissions, or intercept SIM writes.
        return new int[0];
    }
}
