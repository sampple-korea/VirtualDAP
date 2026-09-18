package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;
import top.niunaijun.blackbox.fake.hook.MethodHook;

/** Music spaces do not contain a SIM identity; never read or invent the host's ICCID/IMSI. */
public final class ContainerSubscriberIdentityHook extends MethodHook {
    private final String name;
    public ContainerSubscriberIdentityHook(String name) { this.name = name; }
    @Override protected String getMethodName() { return name; }
    @Override protected Object hook(Object who, Method method, Object[] args) {
        return null;
    }
}
