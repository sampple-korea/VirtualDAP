#pragma once
#include "UsbPacketClock.h"
#include <libusb.h>
#include <array>
#include <atomic>
#include <condition_variable>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace virtualdap::usb {

struct Profile {
    int configuration, interface_number, alternate, endpoint, feedback_endpoint;
    int control_interface = -1;
};

struct Statistics {
    uint64_t accepted_frames = 0, submitted_frames = 0, completed_frames = 0;
    uint64_t underruns = 0, feedback_packets = 0, invalid_feedback_packets = 0;
    size_t queued_bytes = 0;
    int error = 0;
};

/** Owns a duplicate of a UsbManager-granted descriptor, not a discovered /dev/bus/usb path. */
class IsoOutput : public std::enable_shared_from_this<IsoOutput> {
public:
    static std::shared_ptr<IsoOutput> open(int granted_fd, Profile profile);
    ~IsoOutput();
    int control(int type, int request, int value, int index, unsigned char* data, int length);
    void start(uint32_t sample_rate, uint32_t frame_bytes);
    int write(const uint8_t* bytes, size_t count);
    bool pause();
    void resume();
    void flush();
    bool drain();
    void close();
    Statistics statistics() const;
    int speed() const { return speed_; }

private:
    explicit IsoOutput(Profile profile) : profile_(profile) {}
    void initialize(int fd);
    void run();
    void prepare_and_submit();
    void complete(libusb_transfer* transfer);
    void fail_locked(int error);
    void release();
    static void callback(libusb_transfer* transfer);
    struct Slot {
        libusb_transfer* transfer = nullptr;
        std::vector<unsigned char> bytes;
        size_t payload_size = 0;
        bool pending = false;
    };
    const Profile profile_;
    int fd_ = -1, speed_ = 0;
    libusb_context* context_ = nullptr;
    libusb_device_handle* device_ = nullptr;
    bool claimed_ = false, control_claimed_ = false, alternate_active_ = false;
    uint32_t packet_limit_ = 0, interval_ticks_ = 0, bus_ticks_ = 0;
    uint32_t feedback_limit_ = 0;
    unsigned invalid_feedback_streak_ = 0;
    uint32_t rate_ = 0, frame_bytes_ = 0, packets_per_transfer_ = 0;
    size_t capacity_ = 0, queued_ = 0, in_flight_ = 0, queue_front_ = 0;
    std::deque<std::vector<uint8_t>> queue_;
    std::array<Slot, 4> slots_;
    libusb_transfer* feedback_ = nullptr;
    std::array<unsigned char, 4> feedback_bytes_{};
    bool feedback_pending_ = false;
    bool running_ = false, paused_ = false, draining_ = false, closed_ = false, starved_ = false;
    bool start_attempted_ = false, released_ = false;
    bool release_started_ = false;
    std::unique_ptr<PacketClock> clock_;
    Statistics stats_;
    mutable std::mutex mutex_;
    std::condition_variable changed_;
    std::thread worker_;
};
}
