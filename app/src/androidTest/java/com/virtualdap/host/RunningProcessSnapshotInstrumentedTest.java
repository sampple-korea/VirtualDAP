package com.virtualdap.host;

import android.app.ActivityManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import top.niunaijun.blackbox.utils.compat.RunningProcessSnapshot;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class RunningProcessSnapshotInstrumentedTest {
    @Test public void guestNameAndPackageArrayNeverMutateHostSnapshot() {
        ActivityManager.RunningAppProcessInfo host = new ActivityManager.RunningAppProcessInfo(
                "com.virtualdap.host:p0", 123, new String[]{"com.virtualdap.host"});
        host.uid = 10217;
        host.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        host.lastTrimLevel = 20;
        ActivityManager.RunningAppProcessInfo guest = RunningProcessSnapshot.forGuest(host, "fixture.music");
        assertNotSame(host, guest);
        assertEquals("com.virtualdap.host:p0", host.processName);
        assertEquals("fixture.music", guest.processName);
        assertEquals(host.pid, guest.pid);
        assertEquals(host.uid, guest.uid);
        assertEquals(host.importance, guest.importance);
        assertEquals(host.lastTrimLevel, guest.lastTrimLevel);
        assertArrayEquals(host.pkgList, guest.pkgList);
        guest.pkgList[0] = "changed";
        assertEquals("com.virtualdap.host", host.pkgList[0]);
        assertEquals("second", RunningProcessSnapshot.forGuest(host, "second").processName);
        assertEquals("fixture.music", guest.processName);
    }
}
