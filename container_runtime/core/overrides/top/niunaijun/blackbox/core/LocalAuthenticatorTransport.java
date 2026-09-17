package top.niunaijun.blackbox.core;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.accounts.IAccountAuthenticator;
import android.accounts.IAccountAuthenticatorResponse;
import android.accounts.NetworkErrorException;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;

/**
 * Private music-space IPC for a locally instantiated authenticator. Android's system account
 * service/permission and host accounts are never involved. All results come from the imported
 * authenticator's public implementation; the container does not invent credentials or tokens.
 */
public final class LocalAuthenticatorTransport extends IAccountAuthenticator.Stub {
    private final AbstractAccountAuthenticator target;
    private final String packageName;
    private final int userId;

    private LocalAuthenticatorTransport(AbstractAccountAuthenticator target, String packageName, int userId) {
        this.target = target;
        this.packageName = packageName;
        this.userId = userId;
    }

    public static IBinder wrap(IBinder binder, Intent intent, ServiceInfo info, int userId) {
        if (binder == null || intent == null || info == null ||
                !AccountManager.ACTION_AUTHENTICATOR_INTENT.equals(intent.getAction()) ||
                userId != BActivityThread.getUserId() ||
                !info.packageName.equals(BActivityThread.getAppPackageName()) ||
                !BlackBoxCore.get().isInstalled(info.packageName, userId)) return binder;
        ServiceInfo declared = BlackBoxCore.getBPackageManager().getServiceInfo(
                new ComponentName(info.packageName, info.name), PackageManager.GET_META_DATA, userId);
        if (declared == null || declared.metaData == null ||
                !declared.metaData.containsKey(AccountManager.AUTHENTICATOR_META_DATA_NAME)) return binder;
        try {
            AbstractAccountAuthenticator owner = findLocalAuthenticator(binder);
            if (owner != null) {
                return new LocalAuthenticatorTransport(owner, info.packageName, userId);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            // Unsupported platform layout must retain the original failure, not grant permission.
            android.util.Log.w("VirtualDAP-Accounts", "Local authenticator adapter unavailable", error);
        }
        return binder;
    }

    private static AbstractAccountAuthenticator findLocalAuthenticator(IBinder root)
            throws ReflectiveOperationException {
        // Modular services may return local forwarding Binder layers. Inspect only Binder
        // references, never arbitrary object graphs, remote proxies, or static singletons.
        // An ambiguous or oversized graph retains the original transport unchanged.
        ArrayDeque<Binder> pending = new ArrayDeque<>();
        Set<Binder> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!(root instanceof Binder)) return null;
        pending.add((Binder) root);
        AbstractAccountAuthenticator found = null;
        while (!pending.isEmpty()) {
            Binder current = pending.removeFirst();
            if (!seen.add(current)) continue;
            if (seen.size() > 32) return null;
            Class<?> type = current.getClass();
            boolean transport = "android.accounts.AbstractAccountAuthenticator$Transport".equals(type.getName()) &&
                    type.getClassLoader() == AbstractAccountAuthenticator.class.getClassLoader();
            for (Class<?> cursor = type; cursor != Binder.class && cursor != null; cursor = cursor.getSuperclass()) {
                for (Field field : cursor.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    field.setAccessible(true);
                    Object value = field.get(current);
                    if (transport && field.getType() == AbstractAccountAuthenticator.class) {
                        AbstractAccountAuthenticator owner = (AbstractAccountAuthenticator) value;
                        if (owner == null || owner.getIBinder() != current) continue;
                        if (found != null && found != owner) return null;
                        found = owner;
                    } else if (value instanceof Binder) {
                        pending.add((Binder) value);
                    }
                }
            }
        }
        return found;
    }

    private void checkCaller() {
        if (Binder.getCallingUid() != BlackBoxCore.getHostUid() ||
                userId != BActivityThread.getUserId() ||
                !packageName.equals(BActivityThread.getAppPackageName()) ||
                !BlackBoxCore.get().isInstalled(packageName, userId)) {
            throw new SecurityException("Authenticator transport is private to this music space");
        }
    }

    @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        checkCaller();
        return super.onTransact(code, data, reply, flags);
    }

    private interface Request { Bundle run(AccountAuthenticatorResponse response) throws NetworkErrorException; }

    private void dispatch(IAccountAuthenticatorResponse response, Request request) throws RemoteException {
        checkCaller();
        if (response == null) throw new IllegalArgumentException("Missing authenticator response");
        // The public Parcelable factory retains the exact response binder. No hidden constructor
        // or replacement callback is required, and deferred login-activity responses remain live.
        Parcel parcel = Parcel.obtain();
        AccountAuthenticatorResponse callback;
        try {
            parcel.writeStrongBinder(response.asBinder());
            parcel.setDataPosition(0);
            callback = AccountAuthenticatorResponse.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
        Bundle result;
        try {
            result = request.run(callback);
        } catch (NetworkErrorException error) {
            response.onError(AccountManager.ERROR_CODE_NETWORK_ERROR, "Authenticator network error");
            return;
        } catch (UnsupportedOperationException error) {
            response.onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION, "Authenticator operation unsupported");
            return;
        } catch (IllegalArgumentException error) {
            response.onError(AccountManager.ERROR_CODE_BAD_ARGUMENTS, "Invalid authenticator arguments");
            return;
        } catch (RuntimeException error) {
            // Report a real failure without leaking credentials from exception messages or
            // crashing the music-space account service on its callback thread.
            response.onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "Authenticator request failed");
            return;
        }
        if (result != null) response.onResult(result);
        // A null result is asynchronous. Existing session deadlines still report missing callbacks.
    }

    @Override public void addAccount(IAccountAuthenticatorResponse r, String type, String token,
            String[] features, Bundle options) throws RemoteException {
        dispatch(r, callback -> target.addAccount(callback, type, token, features, options));
    }
    @Override public void confirmCredentials(IAccountAuthenticatorResponse r, Account a, Bundle options) throws RemoteException {
        dispatch(r, callback -> target.confirmCredentials(callback, a, options));
    }
    @Override public void getAuthToken(IAccountAuthenticatorResponse r, Account a, String type, Bundle options) throws RemoteException {
        dispatch(r, callback -> target.getAuthToken(callback, a, type, options));
    }
    @Override public void getAuthTokenLabel(IAccountAuthenticatorResponse r, String type) throws RemoteException {
        dispatch(r, callback -> {
            Bundle result = new Bundle();
            result.putString(AccountManager.KEY_AUTH_TOKEN_LABEL, target.getAuthTokenLabel(type));
            return result;
        });
    }
    @Override public void updateCredentials(IAccountAuthenticatorResponse r, Account a, String type, Bundle options) throws RemoteException {
        dispatch(r, callback -> target.updateCredentials(callback, a, type, options));
    }
    @Override public void editProperties(IAccountAuthenticatorResponse r, String type) throws RemoteException {
        dispatch(r, callback -> target.editProperties(callback, type));
    }
    @Override public void hasFeatures(IAccountAuthenticatorResponse r, Account a, String[] features) throws RemoteException {
        dispatch(r, callback -> target.hasFeatures(callback, a, features));
    }
    @Override public void getAccountRemovalAllowed(IAccountAuthenticatorResponse r, Account a) throws RemoteException {
        dispatch(r, callback -> target.getAccountRemovalAllowed(callback, a));
    }
    @Override public void getAccountCredentialsForCloning(IAccountAuthenticatorResponse r, Account a) throws RemoteException {
        dispatch(r, callback -> target.getAccountCredentialsForCloning(callback, a));
    }
    @Override public void addAccountFromCredentials(IAccountAuthenticatorResponse r, Account a, Bundle credentials) throws RemoteException {
        dispatch(r, callback -> target.addAccountFromCredentials(callback, a, credentials));
    }
}
