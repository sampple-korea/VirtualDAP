package com.virtualdap.fixture.music;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Metadata-discovery fixture only: does not create accounts, accept credentials or issue tokens. */
public final class FixtureAuthenticatorService extends Service {
    @Override public IBinder onBind(Intent intent) { return null; }
}
