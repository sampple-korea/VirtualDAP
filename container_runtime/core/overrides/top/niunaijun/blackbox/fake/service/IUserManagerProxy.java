package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import black.android.content.pm.BRUserInfo;
import black.android.os.BRIUserManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.system.user.BUserInfo;
import top.niunaijun.blackbox.core.system.user.BUserStatus;
import top.niunaijun.blackbox.core.system.user.IBUserManagerService;
import top.niunaijun.blackbox.fake.frameworks.BUserManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;

/** Read-only user metadata for the active music space, never the device's other users. */
public class IUserManagerProxy extends BinderInvocationStub {
    public IUserManagerProxy() {
        super(BRServiceManager.get().getService(Context.USER_SERVICE));
    }

    @Override protected Object getWho() {
        return BRIUserManagerStub.get().asInterface(BRServiceManager.get().getService(Context.USER_SERVICE));
    }

    @Override protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.USER_SERVICE);
    }

    @Override public boolean isBadEnv() { return false; }

    private static BUserInfo currentUser(int requested) throws RemoteException {
        int active = BActivityThread.getUserId();
        if (active < 0 || requested != active) return null;
        IBUserManagerService users = BUserManager.get().getService();
        if (users == null) throw new RemoteException("Music space user service unavailable");
        BUserInfo user = users.getUserInfo(active);
        return user != null && user.id == active ? user : null;
    }

    private static Object frameworkUser(BUserInfo user) {
        if (user == null) return null;
        // AOSP UserInfo flags are persisted ABI values. These describe the virtual user;
        // they grant no Android permissions, admin capabilities, or system identity.
        int flags = 0x00000400 | 0x00000010; // FULL | INITIALIZED
        if (user.id == 0) flags |= 0x00004000; // MAIN, not ADMIN or SYSTEM
        if (user.status != BUserStatus.ENABLE) flags |= 0x00000040; // DISABLED
        Object result = BRUserInfo.get()._new(user.id,
                user.name == null ? "Music space" : user.name, flags);
        if (result == null) throw new IllegalStateException("Cannot represent music space user");
        return result;
    }

    @ProxyMethod("getUserInfo")
    public static class GetUserInfo extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return frameworkUser(currentUser((Integer) args[0]));
        }
    }

    @ProxyMethod("getUsers")
    public static class GetUsers extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ArrayList<Object> result = new ArrayList<>();
            Object user = frameworkUser(currentUser(BActivityThread.getUserId()));
            if (user != null) result.add(user);
            return result;
        }
    }

    @ProxyMethod("getProfiles")
    public static class GetProfiles extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ArrayList<Object> result = new ArrayList<>();
            BUserInfo user = currentUser((Integer) args[0]);
            if (user != null && (!(Boolean) args[1] || user.status == BUserStatus.ENABLE)) {
                result.add(frameworkUser(user));
            }
            return result;
        }
    }

    @ProxyMethod("getProfileIds")
    public static class GetProfileIds extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BUserInfo user = currentUser((Integer) args[0]);
            return user != null && (!(Boolean) args[1] || user.status == BUserStatus.ENABLE)
                    ? new int[]{user.id} : new int[0];
        }
    }

    @ProxyMethod("getProfileIdsExcludingHidden")
    public static class GetProfileIdsExcludingHidden extends GetProfileIds {}

    @ProxyMethod("getProfileParent")
    public static class GetProfileParent extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) {
            // Music spaces are independent full users, not managed profiles of the host.
            return null;
        }
    }

    @ProxyMethod("getProfileParentId")
    public static class GetProfileParentId extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BUserInfo user = currentUser((Integer) args[0]);
            return user == null ? -10000 : user.id; // USER_NULL for an absent user
        }
    }

    @ProxyMethod("getSeedAccountName")
    public static class GetSeedAccountName extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // Music spaces never provision an account during device/user creation. This is
            // absence of seed data, not an empty/fabricated signed-in account. Do not inspect
            // the device's setup accounts or change its protected seed-account write methods.
            currentUser((Integer) args[0]);
            return null;
        }
    }

    @ProxyMethod("getSeedAccountType")
    public static class GetSeedAccountType extends GetSeedAccountName {}

    @ProxyMethod("getSeedAccountOptions")
    public static class GetSeedAccountOptions extends GetSeedAccountName {}

    @ProxyMethod("getApplicationRestrictions")
    public static class GetApplicationRestrictions extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) {
            // This container has no enterprise restrictions; do not query host app policies.
            return new Bundle();
        }
    }

    @ProxyMethod("getApplicationRestrictionsForUser")
    public static class GetApplicationRestrictionsForUser extends GetApplicationRestrictions {}
}
