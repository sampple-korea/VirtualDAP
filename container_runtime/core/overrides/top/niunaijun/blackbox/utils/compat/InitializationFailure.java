package top.niunaijun.blackbox.utils.compat;

import java.util.concurrent.atomic.AtomicReference;

/** A partially initialized app process must not be reported ready or silently retried. */
public final class InitializationFailure {
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    public void record(Throwable cause) {
        if (cause == null) throw new IllegalArgumentException("Missing initialization failure");
        failure.compareAndSet(null, cause);
    }

    public void check() {
        Throwable cause = failure.get();
        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
        if (cause instanceof Error) throw (Error) cause;
        if (cause != null) throw new IllegalStateException("Application initialization failed", cause);
    }
}
