package top.niunaijun.blackbox.utils;

import android.os.Build;
import android.os.Process;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** APK architecture checks include every ABI actually supported by the host process. */
public final class AbiUtils {
    private final Set<String> libraries = new HashSet<>();

    public AbiUtils(File apk) {
        try (ZipFile zip = new ZipFile(apk)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String[] parts = entry.getName().split("/");
                if (!entry.isDirectory() && parts.length == 3 && parts[0].equals("lib") && parts[2].endsWith(".so")) {
                    libraries.add(parts[1]);
                }
            }
        } catch (Exception error) {
            throw new IllegalArgumentException("Could not read APK native architectures", error);
        }
    }

    public static boolean isSupport(File apk) {
        AbiUtils info = new AbiUtils(apk);
        return info.isEmptyAib() || (Process.is64Bit() ? info.is64Bit() : info.is32Bit());
    }

    public boolean is64Bit() {
        return !java.util.Collections.disjoint(libraries, Arrays.asList(Build.SUPPORTED_64_BIT_ABIS));
    }

    public boolean is32Bit() {
        return !java.util.Collections.disjoint(libraries, Arrays.asList(Build.SUPPORTED_32_BIT_ABIS));
    }

    public boolean isEmptyAib() { return libraries.isEmpty(); }
}
