package top.niunaijun.blackbox.core.system.pm.installer;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.pm.BPackageSettings;
import top.niunaijun.blackbox.entity.pm.InstallOption;

/** Directory replacement belongs to CopyExecutor's transaction, not pre-install deletion. */
public final class CreatePackageExecutor implements Executor {
    @Override public int exec(BPackageSettings settings, InstallOption option, int userId) {
        java.io.File root = BEnvironment.getAppRootDir();
        return root.isDirectory() || root.mkdirs() ? 0 : -1;
    }
}
