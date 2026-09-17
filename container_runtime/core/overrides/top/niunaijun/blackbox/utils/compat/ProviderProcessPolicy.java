package top.niunaijun.blackbox.utils.compat;

/** Pure provider placement policy, shared by both installation paths and host-JVM tests. */
public final class ProviderProcessPolicy {
    private ProviderProcessPolicy() {}

    public static boolean shouldInitialize(String currentProcess, String declaredProcess,
                                           boolean multiprocess) {
        // Invalid parsed metadata is not permission to initialize in an arbitrary process.
        if (currentProcess == null || currentProcess.isEmpty()
                || declaredProcess == null || declaredProcess.isEmpty()) return false;
        return multiprocess || currentProcess.equals(declaredProcess);
    }
}
