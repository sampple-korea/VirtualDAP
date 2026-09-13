package top.niunaijun.blackbox.core.system.pm.installer;

import android.os.Parcel;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import black.android.content.pm.BRApplicationInfoL;
import top.niunaijun.blackbox.core.AtomicPackagePublisher;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.pm.BPackageSettings;
import top.niunaijun.blackbox.core.system.pm.BPackageUserState;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.utils.NativeUtils;
import top.niunaijun.blackbox.utils.Slog;

/** Every selected split/native library is staged before a single atomic directory publication. */
public final class CopyExecutor implements Executor {
    @Override public int exec(BPackageSettings settings, InstallOption option, int userId) {
        File staging = null;
        try {
            String name = settings.pkg.packageName;
            if (name == null || !name.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
                throw new IOException("Invalid package identity");
            }
            File target = BEnvironment.getAppDir(name);
            File cache = BEnvironment.getCacheDir();
            if (!cache.isDirectory() && !cache.mkdirs()) throw new IOException("Could not create installer cache");
            staging = Files.createTempDirectory(cache.toPath(), "install-").toFile();
            File libs = new File(staging, "lib");
            if (!libs.mkdir()) throw new IOException("Could not stage native libraries");
            if (!option.isFlag(InstallOption.FLAG_SYSTEM)) {
                String abi = BRApplicationInfoL.get(settings.pkg.applicationInfo).primaryCpuAbi();
                NativeUtils.copyNativeLib(new File(settings.pkg.baseCodePath), libs, abi);
                String[] splitPaths = settings.pkg.applicationInfo.splitSourceDirs;
                if (splitPaths != null) {
                    for (String path : splitPaths) NativeUtils.copyNativeLib(new File(path), libs, abi);
                }
                if (option.isFlag(InstallOption.FLAG_STORAGE)) {
                    copy(new File(settings.pkg.baseCodePath), new File(staging, "base.apk"));
                    settings.pkg.baseCodePath = new File(target, "base.apk").getAbsolutePath();
                    settings.pkg.applicationInfo.sourceDir = settings.pkg.baseCodePath;
                    settings.pkg.applicationInfo.publicSourceDir = settings.pkg.baseCodePath;
                    if (splitPaths != null && splitPaths.length > 0) {
                        File splitDir = new File(staging, "splits");
                        if (!splitDir.mkdir()) throw new IOException("Could not stage split APKs");
                        String[] finalPaths = new String[splitPaths.length];
                        for (int index = 0; index < splitPaths.length; index++) {
                            String filename = "part-" + index + ".apk";
                            copy(new File(splitPaths[index]), new File(splitDir, filename));
                            finalPaths[index] = new File(new File(target, "splits"), filename).getAbsolutePath();
                        }
                        settings.pkg.applicationInfo.splitSourceDirs = finalPaths;
                        settings.pkg.applicationInfo.splitPublicSourceDirs = finalPaths;
                    }
                }
            }
            Map<Integer, BPackageUserState> users = new HashMap<>();
            for (Map.Entry<Integer, BPackageUserState> entry : settings.userState.entrySet()) {
                users.put(entry.getKey(), new BPackageUserState(entry.getValue()));
            }
            settings.userState = users; // Never mutate the old installed record on a failed update.
            if (userId >= 0) settings.setInstalled(true, userId);
            Parcel parcel = Parcel.obtain();
            try (FileOutputStream output = new FileOutputStream(new File(staging, "package.conf"))) {
                settings.writeToParcel(parcel, 0);
                output.write(parcel.marshall());
                output.getFD().sync();
            } finally {
                parcel.recycle();
            }
            AtomicPackagePublisher.publish(staging, target);
            return 0;
        } catch (Exception error) {
            Slog.e("VirtualDAP-Installer", "Package transaction rejected", error);
            return -1;
        } finally {
            if (staging != null) {
                try { AtomicPackagePublisher.deleteStaging(staging); }
                catch (IOException error) { Slog.w("VirtualDAP-Installer", "Staging cleanup failed: " + error.getMessage()); }
            }
        }
    }
    private static void copy(File source, File target) throws IOException {
        if (!source.isFile()) throw new IOException("Required APK is missing: " + source.getName());
        try (InputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count != 0) output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
        if (!target.setReadOnly()) throw new IOException("Could not protect imported APK");
    }
}
