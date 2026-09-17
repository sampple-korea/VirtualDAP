package top.niunaijun.blackbox.utils.compat;

import java.util.function.BooleanSupplier;

/** A failed startup must never leave a published record or strand its waiters. */
public final class InitializationAttempt {
    private InitializationAttempt() {}

    public static boolean run(BooleanSupplier initialize, Runnable rollback, Runnable release) {
        boolean ready = false;
        try {
            ready = initialize.getAsBoolean();
            return ready;
        } finally {
            try {
                if (!ready) rollback.run();
            } finally {
                release.run();
            }
        }
    }
}
