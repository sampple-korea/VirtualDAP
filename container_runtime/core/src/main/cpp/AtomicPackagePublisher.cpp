#include <jni.h>
#include <cerrno>
#include <cstdio>
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>

extern "C" JNIEXPORT jint JNICALL
Java_top_niunaijun_blackbox_core_AtomicPackagePublisher_publishNative(
    JNIEnv* env, jclass, jstring staging, jstring target, jboolean replace) {
    if (!staging || !target) return EINVAL;
    const char* from = env->GetStringUTFChars(staging, nullptr);
    if (!from) return ENOMEM;
    const char* to = env->GetStringUTFChars(target, nullptr);
    if (!to) {
        env->ReleaseStringUTFChars(staging, from);
        return ENOMEM;
    }
    // RENAME_EXCHANGE atomically swaps nonempty directories on Android's Linux kernel. On a
    // filesystem that rejects it, fail with the old installation intact; never delete it first.
    const int result = replace
        ? static_cast<int>(syscall(__NR_renameat2, AT_FDCWD, from, AT_FDCWD, to, 2 /* RENAME_EXCHANGE */))
        : rename(from, to);
    const int error = result == 0 ? 0 : errno;
    env->ReleaseStringUTFChars(staging, from);
    env->ReleaseStringUTFChars(target, to);
    return error;
}
