package top.niunaijun.blackbox.utils.compat;

import android.app.ActivityManager.RunningAppProcessInfo;
import android.os.Parcel;

/** Guest-visible names must not mutate framework-owned (potentially cached) process records. */
public final class RunningProcessSnapshot {
    private RunningProcessSnapshot() {}

    public static RunningAppProcessInfo forGuest(RunningAppProcessInfo source, String name) {
        Parcel parcel = Parcel.obtain();
        try {
            source.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            RunningAppProcessInfo snapshot = RunningAppProcessInfo.CREATOR.createFromParcel(parcel);
            snapshot.processName = name;
            return snapshot;
        } finally { parcel.recycle(); }
    }
}
