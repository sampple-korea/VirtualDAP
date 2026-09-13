package top.niunaijun.blackbox.core;

/** Called only in a hosted application's process, before its Application is constructed. */
public final class AudioCapture {
    private AudioCapture() {}
    public static native boolean install();
}
