package com.virtualdap.fixture.music;

import android.app.Service;
import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.content.Intent;
import android.os.IBinder;
import android.os.Bundle;

/** Test-only Binder round trip. Never creates accounts, accepts credentials or issues tokens. */
public final class FixtureAuthenticatorService extends Service {
    private AbstractAccountAuthenticator authenticator;

    @Override public void onCreate() {
        super.onCreate();
        authenticator = new AbstractAccountAuthenticator(this) {
            @Override public Bundle editProperties(AccountAuthenticatorResponse response, String type) {
                Bundle result = new Bundle();
                result.putBoolean("virtualdap.fixture.authenticator.reached",
                        "com.virtualdap.fixture.discovery".equals(type));
                return result;
            }
            @Override public Bundle addAccount(AccountAuthenticatorResponse r, String t, String token,
                    String[] features, Bundle options) {
                if ("fixture.async".equals(token)) {
                    new android.os.Handler(getMainLooper()).post(() -> {
                        Bundle result = new Bundle();
                        result.putBoolean("virtualdap.fixture.authenticator.async", true);
                        r.onResult(result);
                    });
                    return null;
                }
                if ("fixture.error".equals(token)) {
                    throw new IllegalStateException("fixture-sensitive-error-must-not-escape");
                }
                return unsupported();
            }
            @Override public Bundle confirmCredentials(AccountAuthenticatorResponse r, Account a, Bundle b) { return unsupported(); }
            @Override public Bundle getAuthToken(AccountAuthenticatorResponse r, Account a, String t, Bundle b) { return unsupported(); }
            @Override public String getAuthTokenLabel(String type) { return null; }
            @Override public Bundle updateCredentials(AccountAuthenticatorResponse r, Account a, String t, Bundle b) { return unsupported(); }
            @Override public Bundle hasFeatures(AccountAuthenticatorResponse r, Account a, String[] f) { return unsupported(); }
        };
    }

    private static Bundle unsupported() {
        Bundle result = new Bundle();
        result.putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION);
        result.putString(AccountManager.KEY_ERROR_MESSAGE, "The fixture does not support accounts or tokens");
        return result;
    }

    @Override public IBinder onBind(Intent intent) {
        return AccountManager.ACTION_AUTHENTICATOR_INTENT.equals(intent.getAction())
                ? new ForwardingBinder(new ForwardingBinder(authenticator.getIBinder())) : null;
    }

    /** Exercise modular services' local Binder forwarding without credentials or tokens. */
    private static final class ForwardingBinder extends android.os.Binder {
        private final IBinder delegate;
        private final android.os.Binder cycle = this;
        ForwardingBinder(IBinder delegate) { this.delegate = delegate; }
        @Override protected boolean onTransact(int code, android.os.Parcel data,
                android.os.Parcel reply, int flags) throws android.os.RemoteException {
            return delegate.transact(code, data, reply, flags);
        }
    }
}
