package top.niunaijun.blackbox.utils;

import android.os.Build;
import android.os.Process;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipFile;

/** Use the host framework's binary manifest parser; never infer APK identity from its filename. */
public final class ApkBundle {
    public static SplitApkPlanner.Plan plan(List<File> files) throws Exception {
        Method parse = Class.forName("android.content.pm.PackageParser")
            .getDeclaredMethod("parseApkLite", File.class, int.class);
        parse.setAccessible(true);
        List<SplitApkPlanner.Part> parts = new ArrayList<>();
        for (File file : files) {
            Object lite = parse.invoke(null, file, 0);
            if (lite == null) throw new IOException("Could not parse an APK manifest");
            Class<?> type = lite.getClass();
            String name = (String) type.getField("packageName").get(lite);
            String split = (String) type.getField("splitName").get(lite);
            long version = Integer.toUnsignedLong(type.getField("versionCode").getInt(lite)) |
                ((long) type.getField("versionCodeMajor").getInt(lite) << 32);
            Set<String> abis = new HashSet<>();
            try (ZipFile zip = new ZipFile(file)) {
                Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = entries.nextElement();
                    String[] path = entry.getName().split("/");
                    if (!entry.isDirectory() && path.length == 3 && path[0].equals("lib") && path[2].endsWith(".so")) {
                        abis.add(path[1]);
                    }
                }
            }
            parts.add(new SplitApkPlanner.Part(file, name, split, version, abis));
        }
        return SplitApkPlanner.select(parts, Arrays.asList(
            Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS));
    }

    /** Move selected files within the private staging directory; originals outside it are untouched. */
    public static File stage(SplitApkPlanner.Plan plan, File directory) throws IOException {
        if (!directory.mkdir()) throw new IOException("Could not prepare the selected APK cluster");
        Files.move(plan.base.file.toPath(), new File(directory, "base.apk").toPath());
        int index = 0;
        for (SplitApkPlanner.Part part : plan.splits) {
            Files.move(part.file.toPath(), new File(directory, "part-" + (++index) + ".apk").toPath());
        }
        return directory;
    }
    private ApkBundle() {}
}
