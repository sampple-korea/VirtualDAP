package com.virtualdap.host;

import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Process;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import top.niunaijun.blackbox.utils.compat.ProcessInitializationReply;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ProcessInitializationReplyInstrumentedTest {
    @Test public void providerReplyRetainsClientAndPidAcrossParcel() {
        Binder client = new Binder();
        int pid = Process.myPid();
        Bundle reply = ProcessInitializationReply.create(client, pid);
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(reply);
            parcel.setDataPosition(0);
            Bundle copy = parcel.readBundle(getClass().getClassLoader());
            assertSame(client, ProcessInitializationReply.client(copy));
            assertEquals(pid, ProcessInitializationReply.pid(copy, pid + 1));
            assertEquals(0, ProcessInitializationReply.pid(copy, pid));
        } finally {
            parcel.recycle();
        }
    }

    @Test public void absentOrLegacyReplyNeverSuppliesAnInferredPid() {
        assertEquals(0, ProcessInitializationReply.pid(null, Process.myPid()));
        assertNull(ProcessInitializationReply.client(null));
        Bundle legacy = new Bundle();
        legacy.putBinder("_Black_|_client_", new Binder());
        assertEquals(0, ProcessInitializationReply.pid(legacy, Process.myPid()));
        Bundle noClient = new Bundle();
        noClient.putInt("_VirtualDAP_|_pid_", 123);
        assertEquals(0, ProcessInitializationReply.pid(noClient, Process.myPid()));
    }

    @Test public void invalidClientOrPidCannotBePublished() {
        assertThrows(IllegalArgumentException.class, () -> ProcessInitializationReply.create(null, 123));
        assertThrows(IllegalArgumentException.class, () -> ProcessInitializationReply.create(new Binder(), 0));
        assertThrows(IllegalArgumentException.class, () -> ProcessInitializationReply.create(new Binder(), -1));
    }
}
