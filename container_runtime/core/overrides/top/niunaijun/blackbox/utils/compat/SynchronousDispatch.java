package top.niunaijun.blackbox.utils.compat;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;

/** Runs framework work on its owning thread without stranding a caller when that work fails. */
public final class SynchronousDispatch {
    private SynchronousDispatch() {}

    public static void run(Executor executor, Runnable action) {
        FutureTask<Void> task = new FutureTask<>(action, null);
        executor.execute(task); // Rejected dispatch must fail immediately, not start waiting.
        try {
            task.get();
        } catch (InterruptedException interrupted) {
            // Prevent a still-queued action from starting after its caller has given up.
            task.cancel(false);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Application initialization wait interrupted", interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Application initialization failed", cause);
        }
    }
}
