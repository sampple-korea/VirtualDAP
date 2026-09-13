package top.niunaijun.blackbox.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** Publishes an already complete APK directory without first removing the installed version. */
public final class AtomicPackagePublisher {
    static { System.loadLibrary("blackbox"); }
    private static native int publishNative(String staging, String target, boolean replace);
    public static void publish(File staging, File target) throws IOException {
        if (Files.isSymbolicLink(target.toPath()) || Files.isSymbolicLink(staging.toPath())) {
            throw new IOException("Package directory must not be a symbolic link");
        }
        if (!staging.isDirectory() || (target.exists() && !target.isDirectory())) {
            throw new IOException("Atomic package publication requires directories");
        }
        int error = publishNative(staging.getAbsolutePath(), target.getAbsolutePath(), target.exists());
        if (error != 0) throw new IOException("Atomic APK publication failed (errno " + error + ")");
    }
    public static void deleteStaging(File staging) throws IOException {
        if (!Files.exists(staging.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(staging.toPath(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    private AtomicPackagePublisher() {}
}
