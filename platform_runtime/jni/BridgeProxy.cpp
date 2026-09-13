#define LOG_TAG "VirtualDAP-Runtime"

#include <jni.h>
#include <errno.h>
#include <android/log.h>
#include <poll.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <linux/vm_sockets.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

#include <array>
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>

namespace {

#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

constexpr size_t kTokenBytes = 32;
constexpr size_t kBufferBytes = 64 * 1024;

bool read_exact(int fd, void* output, size_t byte_count) {
    auto* bytes = static_cast<uint8_t*>(output);
    size_t received = 0;
    while (received < byte_count) {
        const ssize_t result = recv(fd, bytes + received, byte_count - received, 0);
        if (result > 0) {
            received += static_cast<size_t>(result);
            continue;
        }
        if (result < 0 && errno == EINTR) continue;
        return false;
    }
    return true;
}

bool send_all(int fd, const void* input, size_t byte_count) {
    const auto* bytes = static_cast<const uint8_t*>(input);
    size_t sent = 0;
    while (sent < byte_count) {
        const ssize_t result = send(fd, bytes + sent, byte_count - sent, MSG_NOSIGNAL);
        if (result > 0) {
            sent += static_cast<size_t>(result);
            continue;
        }
        if (result < 0 && errno == EINTR) continue;
        return false;
    }
    return true;
}

bool constant_time_equal(const std::array<uint8_t, kTokenBytes>& left,
                         const std::array<uint8_t, kTokenBytes>& right) {
    uint8_t difference = 0;
    for (size_t index = 0; index < left.size(); ++index) difference |= left[index] ^ right[index];
    return difference == 0;
}

class BridgeProxy {
  public:
    BridgeProxy(uint32_t port, std::string socket_name,
                std::array<uint8_t, kTokenBytes> token)
        : port_(port), socket_name_(std::move(socket_name)), token_(token) {}

    ~BridgeProxy() { Stop(); }

    bool Start() {
        if (port_ == 0 || socket_name_.empty() || socket_name_.size() >= sizeof(sockaddr_un::sun_path)) {
            return false;
        }
        const int server = socket(AF_VSOCK, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (server < 0) {
            ALOGE("AF_VSOCK socket failed: %s", strerror(errno));
            return false;
        }
        sockaddr_vm address{};
        address.svm_family = AF_VSOCK;
        address.svm_cid = VMADDR_CID_ANY;
        address.svm_port = port_;
        if (bind(server, reinterpret_cast<const sockaddr*>(&address), sizeof(address)) != 0 ||
            listen(server, 1) != 0) {
            ALOGE("AF_VSOCK bind/listen failed: %s", strerror(errno));
            close(server);
            return false;
        }
        {
            std::lock_guard<std::mutex> lock(fd_mutex_);
            server_fd_ = server;
        }
        running_.store(true);
        worker_ = std::thread(&BridgeProxy::Run, this);
        return true;
    }

    void Stop() {
        if (!running_.exchange(false)) return;
        {
            std::lock_guard<std::mutex> lock(fd_mutex_);
            shutdown_and_close(&client_fd_);
            shutdown_and_close(&local_fd_);
            shutdown_and_close(&server_fd_);
        }
        if (worker_.joinable()) worker_.join();
    }

  private:
    void Run() {
        int server = -1;
        {
            std::lock_guard<std::mutex> lock(fd_mutex_);
            server = server_fd_;
        }
        while (running_.load()) {
            sockaddr_vm peer{};
            socklen_t peer_size = sizeof(peer);
            const int client = accept4(server, reinterpret_cast<sockaddr*>(&peer), &peer_size,
                                       SOCK_CLOEXEC);
            if (client < 0) {
                if (errno == EINTR) continue;
                if (running_.load()) ALOGE("vsock accept failed: %s", strerror(errno));
                break;
            }
            {
                std::lock_guard<std::mutex> lock(fd_mutex_);
                client_fd_ = client;
            }
            handle_client(client, peer.svm_cid);
            std::lock_guard<std::mutex> lock(fd_mutex_);
            shutdown_and_close(&client_fd_);
            shutdown_and_close(&local_fd_);
        }
    }

    void handle_client(int client, uint32_t peer_cid) {
        const timeval authentication_timeout{2, 0};
        setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &authentication_timeout,
                   sizeof(authentication_timeout));
        std::array<uint8_t, kTokenBytes> presented{};
        if (!read_exact(client, presented.data(), presented.size()) ||
            !constant_time_equal(presented, token_)) {
            ALOGW("Rejected unauthenticated vsock peer CID %u", peer_cid);
            return;
        }
        const timeval streaming_timeout{0, 0};
        setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &streaming_timeout, sizeof(streaming_timeout));

        const int local = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (local < 0) return;
        sockaddr_un address{};
        address.sun_family = AF_UNIX;
        address.sun_path[0] = '\0';
        memcpy(address.sun_path + 1, socket_name_.data(), socket_name_.size());
        const auto address_size = static_cast<socklen_t>(
            offsetof(sockaddr_un, sun_path) + 1 + socket_name_.size());
        if (connect(local, reinterpret_cast<const sockaddr*>(&address), address_size) != 0) {
            ALOGW("Host audio socket connection failed: %s", strerror(errno));
            close(local);
            return;
        }
        {
            std::lock_guard<std::mutex> lock(fd_mutex_);
            local_fd_ = local;
        }
        std::array<uint8_t, kBufferBytes> buffer{};
        while (running_.load()) {
            const ssize_t received = recv(client, buffer.data(), buffer.size(), 0);
            if (received > 0) {
                if (!send_all(local, buffer.data(), static_cast<size_t>(received))) break;
                continue;
            }
            if (received < 0 && errno == EINTR) continue;
            break;
        }
    }

    static void shutdown_and_close(int* fd) {
        if (*fd < 0) return;
        shutdown(*fd, SHUT_RDWR);
        close(*fd);
        *fd = -1;
    }

    const uint32_t port_;
    const std::string socket_name_;
    const std::array<uint8_t, kTokenBytes> token_;
    std::atomic<bool> running_{false};
    std::mutex fd_mutex_;
    int server_fd_ = -1;
    int client_fd_ = -1;
    int local_fd_ = -1;
    std::thread worker_;
};

std::mutex g_proxy_mutex;
std::unique_ptr<BridgeProxy> g_proxy;

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_virtualdap_platformruntime_GuestRuntimeService_nativeStartBridgeProxy(
    JNIEnv* env, jobject /* instance */, jint port, jstring socket_name, jbyteArray token) {
    if (socket_name == nullptr || token == nullptr ||
        env->GetArrayLength(token) != static_cast<jsize>(kTokenBytes)) {
        return JNI_FALSE;
    }
    const char* raw_name = env->GetStringUTFChars(socket_name, nullptr);
    if (raw_name == nullptr) return JNI_FALSE;
    const std::string name(raw_name);
    env->ReleaseStringUTFChars(socket_name, raw_name);
    std::array<uint8_t, kTokenBytes> expected{};
    env->GetByteArrayRegion(token, 0, expected.size(), reinterpret_cast<jbyte*>(expected.data()));
    if (env->ExceptionCheck()) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(g_proxy_mutex);
    if (g_proxy != nullptr) return JNI_FALSE;
    auto proxy = std::make_unique<BridgeProxy>(static_cast<uint32_t>(port), name, expected);
    if (!proxy->Start()) return JNI_FALSE;
    g_proxy = std::move(proxy);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_virtualdap_platformruntime_GuestRuntimeService_nativeStopBridgeProxy(
    JNIEnv* /* env */, jobject /* instance */) {
    std::unique_ptr<BridgeProxy> proxy;
    {
        std::lock_guard<std::mutex> lock(g_proxy_mutex);
        proxy = std::move(g_proxy);
    }
    if (proxy != nullptr) proxy->Stop();
}
