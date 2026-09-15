package top.niunaijun.blackbox.core.system.pm;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.SigningInfo;

/** Reports the imported APK's actual platform-parsed identity, never a host namesake's identity. */
public final class InstalledPackageSigning {
    @SuppressWarnings("deprecation")
    public static void populate(PackageManager manager, String archivePath, PackageInfo target, int flags) {
        int requested = flags & (PackageManager.GET_SIGNATURES | PackageManager.GET_SIGNING_CERTIFICATES);
        if (requested == 0) return;
        PackageInfo archive = manager.getPackageArchiveInfo(archivePath, requested);
        if (archive == null || target.packageName == null || !target.packageName.equals(archive.packageName)) {
            throw new IllegalStateException("Cannot read signing identity from the installed APK");
        }
        if ((requested & PackageManager.GET_SIGNATURES) != 0) {
            if (archive.signatures == null || archive.signatures.length == 0) {
                throw new IllegalStateException("Installed APK has no parsed signing certificates");
            }
            // Preserve Android's legacy GET_SIGNATURES semantics for rotated certificates.
            target.signatures = archive.signatures.clone();
        }
        if ((requested & PackageManager.GET_SIGNING_CERTIFICATES) != 0) {
            if (archive.signingInfo == null) {
                throw new IllegalStateException("Installed APK has no parsed signing history");
            }
            target.signingInfo = new SigningInfo(archive.signingInfo);
        }
    }

    private InstalledPackageSigning() {}
}
