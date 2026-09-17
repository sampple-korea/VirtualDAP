package com.virtualdap.fixture.music;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Local fixture only: no accounts, credentials, files or network access. */
public final class FixtureContentProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("deny".equals(method)) throw new SecurityException("fixture provider denied");
        if (!"probe".equals(method)) throw new IllegalArgumentException("Unknown fixture method");
        Bundle result = new Bundle();
        result.putString("package", getContext().getPackageName());
        result.putString("arg", arg);
        result.putString("nonce", extras == null ? null : extras.getString("nonce"));
        result.putInt("pid", android.os.Process.myPid());
        result.putInt("callerUid", android.os.Binder.getCallingUid());
        result.putInt("processUid", MusicFixtureActivity.kernelUid());
        result.putBoolean("mainProviderInitialized", FixtureMainProvider.initialized);
        return result;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        throw new UnsupportedOperationException("Fixture uses call only");
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
