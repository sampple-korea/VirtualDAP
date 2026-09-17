package com.virtualdap.host;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/** Tests only local Binder discovery; no accounts, credentials, network or OS privileges. */
@RunWith(AndroidJUnit4.class)
public final class LocalAuthenticatorTransportInstrumentedTest {
    private AbstractAccountAuthenticator discover(IBinder binder) throws Exception {
        Class<?> adapter = Class.forName("top.niunaijun.blackbox.core.LocalAuthenticatorTransport");
        Method method = adapter.getDeclaredMethod("findLocalAuthenticator", IBinder.class);
        method.setAccessible(true);
        return (AbstractAccountAuthenticator) method.invoke(null, binder);
    }

    @Test public void directAndCyclicForwardingKeepExactOwner() throws Exception {
        Authenticator owner = new Authenticator();
        assertSame(owner, discover(owner.getIBinder()));
        Forwarder outer = new Forwarder(new Forwarder(owner.getIBinder()));
        outer.other = outer;
        assertSame(owner, discover(outer));
    }

    @Test public void ambiguityIsNotResolvedByPickingFirstAccountService() throws Exception {
        Forwarder outer = new Forwarder(new Authenticator().getIBinder());
        outer.other = new Authenticator().getIBinder();
        assertNull(discover(outer));
    }

    @Test public void oversizedAndUnrelatedGraphsAreRejected() throws Exception {
        IBinder large = new Authenticator().getIBinder();
        for (int i = 0; i < 33; i++) large = new Forwarder(large);
        assertNull(discover(large));
        assertNull(discover(new Binder()));
        // The owner behind a non-Binder holder must not cause arbitrary object traversal.
        assertNull(discover(new Forwarder(new Object[] { new Authenticator().getIBinder() })));
    }

    @Test public void staticReferencesAreNeverFollowed() throws Exception {
        StaticReference.reference = new Authenticator().getIBinder();
        try { assertNull(discover(new StaticReference())); }
        finally { StaticReference.reference = null; }
    }

    private static final class Forwarder extends Binder {
        private final Object delegate;
        private Object other;
        Forwarder(Object delegate) { this.delegate = delegate; }
    }

    private static final class StaticReference extends Binder {
        private static IBinder reference;
    }

    private static final class Authenticator extends AbstractAccountAuthenticator {
        Authenticator() { super(context()); }
        private static Context context() {
            return InstrumentationRegistry.getInstrumentation().getTargetContext();
        }
        @Override public Bundle editProperties(AccountAuthenticatorResponse r, String t) { return null; }
        @Override public Bundle addAccount(AccountAuthenticatorResponse r, String t, String token,
                String[] features, Bundle options) { return null; }
        @Override public Bundle confirmCredentials(AccountAuthenticatorResponse r, Account a, Bundle b) { return null; }
        @Override public Bundle getAuthToken(AccountAuthenticatorResponse r, Account a, String t, Bundle b) { return null; }
        @Override public String getAuthTokenLabel(String t) { return null; }
        @Override public Bundle updateCredentials(AccountAuthenticatorResponse r, Account a, String t, Bundle b) { return null; }
        @Override public Bundle hasFeatures(AccountAuthenticatorResponse r, Account a, String[] f) { return null; }
    }
}
