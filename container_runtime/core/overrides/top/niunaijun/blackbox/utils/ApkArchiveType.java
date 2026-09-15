package top.niunaijun.blackbox.utils;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Container recognition only; manifest identity and signatures are still parsed afterwards. */
public final class ApkArchiveType {
    public static boolean isBundle(File file) {
        if (file == null) return false;
        try (ZipFile zip = new ZipFile(file)) {
            // Real APKs may carry multiple APK modules as assets. Never unpack those as siblings,
            // even if a document provider gave the outer APK an .apks filename.
            if (zip.getEntry("AndroidManifest.xml") != null) return false;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) return true;
            }
        } catch (IOException ignored) {
            // The normal APK parser will reject unreadable/non-APK input.
        }
        return false;
    }
    private ApkArchiveType() {}
}
