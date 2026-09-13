package top.niunaijun.blackbox.utils;

import android.os.Build;
import android.os.Process;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** VirtualDAP native-library importer: supported-ABI order, bounded copies and atomic replacement. */
public final class NativeUtils {
    private static final long MAX_NATIVE_BYTES = 1024L * 1024 * 1024;

    private NativeUtils() {}

    public static void copyNativeLib(File apk, File directory) throws Exception {
        copyNativeLib(apk, directory, null);
    }

    public static void copyNativeLib(File apk, File directory, String requestedAbi) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create the native library directory");
        }
        try (ZipFile zip = new ZipFile(apk)) {
            List<ZipEntry> nativeEntries = new ArrayList<>();
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String[] parts = entry.getName().split("/");
                if (!entry.isDirectory() && parts.length == 3 && parts[0].equals("lib") && parts[2].endsWith(".so")) {
                    nativeEntries.add(entry);
                }
            }
            if (nativeEntries.isEmpty()) return;
            String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
            String selected = null;
            for (String abi : abis) {
                if (requestedAbi != null && !requestedAbi.equals(abi)) continue;
                String prefix = "lib/" + abi + "/";
                for (ZipEntry entry : nativeEntries) {
                    if (entry.getName().startsWith(prefix)) {
                        selected = abi;
                        break;
                    }
                }
                if (selected != null) break;
            }
            if (selected == null) throw new IllegalArgumentException("APK has no native libraries compatible with this process");
            String prefix = "lib/" + selected + "/";
            Set<String> names = new HashSet<>();
            byte[] buffer = new byte[64 * 1024];
            long copied = 0;
            for (ZipEntry entry : nativeEntries) {
                if (!entry.getName().startsWith(prefix)) continue;
                String name = entry.getName().substring(prefix.length());
                if (name.contains("..") || name.contains("\\") || !names.add(name)) {
                    throw new IllegalArgumentException("Invalid or duplicate native library name");
                }
                File staging = File.createTempFile(".native-", ".partial", directory);
                try {
                    try (InputStream input = zip.getInputStream(entry); FileOutputStream output = new FileOutputStream(staging)) {
                        int count;
                        while ((count = input.read(buffer)) >= 0) {
                            if (count == 0) continue;
                            copied += count;
                            if (copied > MAX_NATIVE_BYTES) throw new IllegalArgumentException("Native libraries exceed the import limit");
                            output.write(buffer, 0, count);
                        }
                        output.getFD().sync();
                    }
                    if (!staging.setReadOnly()) throw new IllegalStateException("Could not protect the imported native library");
                    File target = new File(directory, name);
                    java.nio.file.Files.move(staging.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    if (staging.exists()) staging.delete();
                }
            }
        }
    }
}
