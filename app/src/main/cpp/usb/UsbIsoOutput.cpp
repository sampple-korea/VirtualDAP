#include "UsbIsoOutput.h"
#include <algorithm>
#include <chrono>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>

namespace virtualdap::usb {
namespace {
void require_usb(int result, const char* operation) {
    if (result < 0) throw std::runtime_error(std::string(operation) + ": " + libusb_error_name(result));
}
constexpr unsigned kTransferTimeoutMs = 2000;
constexpr size_t kMaximumTransferBytes = 256 * 1024;
}

std::shared_ptr<IsoOutput> IsoOutput::open(int fd, Profile profile) {
    if (fd < 0 || profile.configuration < 1 || profile.configuration > 255 ||
        profile.interface_number < 0 || profile.interface_number > 255 ||
        profile.control_interface < -1 || profile.control_interface > 255 ||
        profile.alternate < 1 || profile.alternate > 255 || profile.endpoint < 1 || profile.endpoint > 15 ||
        (profile.feedback_endpoint != 0 && (profile.feedback_endpoint < 0x81 || profile.feedback_endpoint > 0x8f))) {
        throw std::invalid_argument("Invalid USB output profile");
    }
    auto output = std::shared_ptr<IsoOutput>(new IsoOutput(profile));
    output->initialize(fd);
    return output;
}

void IsoOutput::initialize(int fd) {
    fd_ = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (fd_ < 0) throw std::runtime_error("Could not duplicate the granted USB descriptor");
    const libusb_init_option options[] = {{LIBUSB_OPTION_NO_DEVICE_DISCOVERY, {0}}};
    require_usb(libusb_init_context(&context_, options, 1), "USB context");
    require_usb(libusb_wrap_sys_device(context_, fd_, &device_), "Granted USB descriptor");
    int current_configuration = 0;
    require_usb(libusb_get_configuration(device_, &current_configuration), "USB configuration");
    if (current_configuration != profile_.configuration) {
        throw std::runtime_error("Requested interface is not in the device's active configuration");
    }
    libusb_device* usb = libusb_get_device(device_);
    speed_ = libusb_get_device_speed(usb);
    if (speed_ != LIBUSB_SPEED_FULL && speed_ != LIBUSB_SPEED_HIGH &&
        speed_ != LIBUSB_SPEED_SUPER && speed_ != LIBUSB_SPEED_SUPER_PLUS) {
        throw std::runtime_error("USB device speed is unavailable or unsuitable for isochronous audio");
    }
    bus_ticks_ = speed_ == LIBUSB_SPEED_FULL ? 1000 : 8000;
    libusb_config_descriptor* raw = nullptr;
    require_usb(libusb_get_active_config_descriptor(usb, &raw), "USB descriptors");
    std::unique_ptr<libusb_config_descriptor, decltype(&libusb_free_config_descriptor)> config(raw, libusb_free_config_descriptor);
    bool output_found = false, feedback_found = profile_.feedback_endpoint == 0;
    bool control_found = profile_.control_interface == -1;
    for (uint8_t i = 0; i < raw->bNumInterfaces; ++i) {
        for (int alt = 0; alt < raw->interface[i].num_altsetting; ++alt) {
            const auto& setting = raw->interface[i].altsetting[alt];
            if (setting.bInterfaceNumber == profile_.control_interface &&
                setting.bInterfaceClass == LIBUSB_CLASS_AUDIO && setting.bInterfaceSubClass == 1) {
                control_found = true;
            }
            if (setting.bInterfaceNumber != profile_.interface_number || setting.bAlternateSetting != profile_.alternate) continue;
            if (setting.bInterfaceClass != LIBUSB_CLASS_AUDIO || setting.bInterfaceSubClass != 2) {
                throw std::runtime_error("Selected interface is not an audio streaming interface");
            }
            for (uint8_t ep = 0; ep < setting.bNumEndpoints; ++ep) {
                const auto& endpoint = setting.endpoint[ep];
                if ((endpoint.bmAttributes & LIBUSB_TRANSFER_TYPE_MASK) != LIBUSB_TRANSFER_TYPE_ISOCHRONOUS) continue;
                if (endpoint.bEndpointAddress == profile_.endpoint) {
                    if (output_found || ((endpoint.bmAttributes >> 4) & 3) != 0 ||
                        ((endpoint.wMaxPacketSize >> 11) & 3) == 3) {
                        throw std::runtime_error("Invalid or ambiguous USB audio endpoint");
                    }
                    if (endpoint.bInterval < 1 || endpoint.bInterval > 16) throw std::runtime_error("Invalid USB interval");
                    interval_ticks_ = 1u << (endpoint.bInterval - 1);
                    packet_limit_ = (endpoint.wMaxPacketSize & 0x7ff) * (1 + ((endpoint.wMaxPacketSize >> 11) & 3));
                    if (speed_ >= LIBUSB_SPEED_SUPER) {
                        libusb_ss_endpoint_companion_descriptor* companion = nullptr;
                        require_usb(libusb_get_ss_endpoint_companion_descriptor(context_, &endpoint, &companion), "USB SuperSpeed interval");
                        packet_limit_ = companion->wBytesPerInterval;
                        libusb_free_ss_endpoint_companion_descriptor(companion);
                    }
                    const int sync = (endpoint.bmAttributes >> 2) & 3;
                    if (sync == 0) throw std::runtime_error("USB audio endpoint has no synchronization mode");
                    if (sync == 1 && profile_.feedback_endpoint == 0) {
                        throw std::runtime_error("Asynchronous USB output requires an explicit feedback endpoint");
                    }
                    output_found = true;
                } else if (endpoint.bEndpointAddress == profile_.feedback_endpoint) {
                    feedback_limit_ = endpoint.wMaxPacketSize & 0x7ff;
                    if (feedback_limit_ < 3 || feedback_limit_ > 4 ||
                        ((endpoint.bmAttributes >> 4) & 3) != 1 || endpoint.bInterval < 1 || endpoint.bInterval > 16) {
                        throw std::runtime_error("Unsupported USB feedback endpoint");
                    }
                    feedback_found = true;
                }
            }
        }
    }
    if (!control_found) throw std::runtime_error("Selected AudioControl interface is missing");
    if (!output_found || !feedback_found || packet_limit_ == 0 || packet_limit_ > 49152) {
        throw std::runtime_error("USB audio endpoint is missing or malformed");
    }
    require_usb(libusb_set_auto_detach_kernel_driver(device_, 1), "USB driver handoff");
    // Interface-recipient clock requests are checked against AudioControl ownership by usbfs.
    // Claim only the descriptor-declared interface on the already user-granted device.
    if (profile_.control_interface >= 0) {
        require_usb(libusb_claim_interface(device_, profile_.control_interface), "USB AudioControl interface permission");
        control_claimed_ = true;
    }
    require_usb(libusb_claim_interface(device_, profile_.interface_number), "USB interface permission");
    claimed_ = true;
    require_usb(libusb_set_interface_alt_setting(device_, profile_.interface_number, 0), "USB idle interface");
}

IsoOutput::~IsoOutput() {
    if (worker_.joinable()) {
        if (worker_.get_id() == std::this_thread::get_id()) worker_.detach();
        else worker_.join();
    }
    release();
}

int IsoOutput::control(int type, int request, int value, int index, unsigned char* data, int length) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (closed_ || running_ || length < 0 || length > 8192) return LIBUSB_ERROR_INVALID_PARAM;
    return libusb_control_transfer(device_, static_cast<uint8_t>(type), static_cast<uint8_t>(request),
        static_cast<uint16_t>(value), static_cast<uint16_t>(index), data,
        static_cast<uint16_t>(length), kTransferTimeoutMs);
}

void IsoOutput::start(uint32_t sample_rate, uint32_t frame_bytes) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (closed_ || start_attempted_ || frame_bytes == 0 || frame_bytes > 64) throw std::runtime_error("Invalid USB start");
    start_attempted_ = true;
    clock_ = std::make_unique<PacketClock>(sample_rate, bus_ticks_, interval_ticks_);
    if (uint64_t(clock_->maximum_frames(profile_.feedback_endpoint != 0)) * frame_bytes > packet_limit_) {
        throw std::runtime_error("USB alternate setting cannot carry the requested format and feedback margin");
    }
    rate_ = sample_rate;
    frame_bytes_ = frame_bytes;
    packets_per_transfer_ = std::max(1u, bus_ticks_ / (250 * interval_ticks_));
    packets_per_transfer_ = std::min<uint32_t>(packets_per_transfer_, kMaximumTransferBytes / packet_limit_);
    capacity_ = std::min<size_t>(4 * 1024 * 1024, size_t(sample_rate) * frame_bytes / 10);
    capacity_ = std::max(capacity_, size_t(packet_limit_) * packets_per_transfer_ * slots_.size());
    capacity_ -= capacity_ % frame_bytes;
    for (auto& slot : slots_) {
        slot.bytes.resize(size_t(packet_limit_) * packets_per_transfer_);
        slot.transfer = libusb_alloc_transfer(static_cast<int>(packets_per_transfer_));
        if (!slot.transfer) throw std::bad_alloc();
        libusb_fill_iso_transfer(slot.transfer, device_, static_cast<unsigned char>(profile_.endpoint),
            slot.bytes.data(), 0, static_cast<int>(packets_per_transfer_), callback, this, kTransferTimeoutMs);
    }
    if (profile_.feedback_endpoint != 0) {
        feedback_ = libusb_alloc_transfer(1);
        if (!feedback_) throw std::bad_alloc();
        libusb_fill_iso_transfer(feedback_, device_, static_cast<unsigned char>(profile_.feedback_endpoint),
            feedback_bytes_.data(), static_cast<int>(feedback_limit_), 1, callback, this, kTransferTimeoutMs);
        libusb_set_iso_packet_lengths(feedback_, feedback_limit_);
    }
    require_usb(libusb_set_interface_alt_setting(device_, profile_.interface_number, profile_.alternate), "USB audio alternate setting");
    alternate_active_ = true;
    running_ = true;
    try { worker_ = std::thread([owner = shared_from_this()] { owner->run(); }); }
    catch (...) { running_ = false; throw; }
}

int IsoOutput::write(const uint8_t* bytes, size_t count) {
    std::unique_lock<std::mutex> lock(mutex_);
    if (!running_ || closed_ || stats_.error || !bytes || count > 1024 * 1024 ||
        count % frame_bytes_ != 0) return -1;
    size_t offset = 0;
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
    while (offset < count && !closed_ && !stats_.error) {
        if (queued_ + in_flight_ == capacity_) {
            if (changed_.wait_until(lock, deadline) == std::cv_status::timeout) return offset ? static_cast<int>(offset) : -1;
            continue;
        }
        const size_t chunk = std::min(count - offset, capacity_ - queued_ - in_flight_);
        queue_.emplace_back(bytes + offset, bytes + offset + chunk);
        queued_ += chunk;
        offset += chunk;
        stats_.accepted_frames += chunk / frame_bytes_;
        changed_.notify_all();
    }
    return offset ? static_cast<int>(offset) : -1;
}

bool IsoOutput::pause() {
    std::unique_lock<std::mutex> lock(mutex_);
    if (!running_ || closed_) return false;
    paused_ = true;
    changed_.notify_all();
    return changed_.wait_for(lock, std::chrono::seconds(3), [&] { return in_flight_ == 0 || closed_ || stats_.error; })
        && !closed_ && !stats_.error;
}
void IsoOutput::resume() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!running_ || closed_ || stats_.error) throw std::runtime_error("USB stream is unavailable");
    paused_ = false;
    changed_.notify_all();
}
void IsoOutput::flush() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!running_ || closed_ || !paused_ || in_flight_ != 0) throw std::runtime_error("Pause and drain in-flight USB packets before flushing");
    queue_.clear();
    queue_front_ = queued_ = 0;
    stats_.accepted_frames = stats_.submitted_frames = stats_.completed_frames = 0;
    clock_ = std::make_unique<PacketClock>(rate_, bus_ticks_, interval_ticks_);
    starved_ = false;
    changed_.notify_all();
}
bool IsoOutput::drain() {
    std::unique_lock<std::mutex> lock(mutex_);
    if (!running_ || closed_) return false;
    paused_ = false;
    draining_ = true;
    changed_.notify_all();
    const bool success = changed_.wait_for(lock, std::chrono::seconds(5), [&] {
        return (queued_ == 0 && in_flight_ == 0) || closed_ || stats_.error;
    }) && !closed_ && !stats_.error;
    draining_ = false;
    paused_ = true;
    lock.unlock();
    if (success) std::this_thread::sleep_for(std::chrono::milliseconds(20));
    return success;
}
void IsoOutput::close() {
    std::unique_lock<std::mutex> lock(mutex_);
    closed_ = true;
    changed_.notify_all();
    if (!running_) {
        lock.unlock();
        release();
    } else {
        // Normal cancellations complete within a few USB intervals. Do not race the next format's
        // interface claim against this handle's release. A broken kernel cannot block the UI forever;
        // the self-owned worker retains buffers until callbacks eventually complete.
        changed_.wait_for(lock, std::chrono::seconds(3), [&] { return released_; });
    }
}
Statistics IsoOutput::statistics() const {
    std::lock_guard<std::mutex> lock(mutex_);
    auto result = stats_;
    result.queued_bytes = queued_ + in_flight_;
    return result;
}
void IsoOutput::fail_locked(int error) {
    if (!stats_.error) stats_.error = error ? error : LIBUSB_ERROR_IO;
    closed_ = true;
    changed_.notify_all();
}

void IsoOutput::prepare_and_submit() {
    // Called only by the event worker, holding mutex_. Fractional clock state advances only
    // when a packet is submitted; no silence or fake DoP/DSD payload is inserted on starvation.
    if (paused_) return;
    for (auto& slot : slots_) {
        if (slot.pending) continue;
        PacketClock planned = *clock_;
        unsigned packets = 0;
        size_t bytes = 0;
        for (; packets < packets_per_transfer_; ++packets) {
            PacketClock next = planned;
            size_t packet = size_t(next.next_frames()) * frame_bytes_;
            if (bytes + packet > queued_) {
                if (!draining_) break;
                packet = queued_ - bytes;
            }
            if (packet > packet_limit_) { fail_locked(LIBUSB_ERROR_OVERFLOW); return; }
            slot.transfer->iso_packet_desc[packets].length = static_cast<unsigned>(packet);
            bytes += packet;
            planned = next;
            if (bytes == queued_) { ++packets; break; }
        }
        if (!packets || bytes == 0 || (!draining_ && packets < packets_per_transfer_)) break;
        size_t copied = 0;
        while (copied < bytes) {
            auto& front = queue_.front();
            size_t part = std::min(bytes - copied, front.size() - queue_front_);
            memcpy(slot.bytes.data() + copied, front.data() + queue_front_, part);
            copied += part;
            queue_front_ += part;
            if (queue_front_ == front.size()) { queue_.pop_front(); queue_front_ = 0; }
        }
        queued_ -= bytes;
        slot.transfer->length = static_cast<int>(bytes);
        slot.transfer->num_iso_packets = static_cast<int>(packets);
        slot.payload_size = bytes;
        const int result = libusb_submit_transfer(slot.transfer);
        if (result < 0) { fail_locked(result); return; }
        *clock_ = planned;
        slot.pending = true;
        in_flight_ += bytes;
        stats_.submitted_frames += bytes / frame_bytes_;
        starved_ = false;
    }
}

void IsoOutput::callback(libusb_transfer* transfer) {
    static_cast<IsoOutput*>(transfer->user_data)->complete(transfer);
}
void IsoOutput::complete(libusb_transfer* transfer) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (transfer == feedback_) {
        feedback_pending_ = false;
        if (!closed_ && !paused_) {
            if (transfer->status != LIBUSB_TRANSFER_COMPLETED || transfer->iso_packet_desc[0].status != LIBUSB_TRANSFER_COMPLETED) {
                fail_locked(LIBUSB_ERROR_IO);
            } else if (clock_->feedback(feedback_bytes_.data(), transfer->iso_packet_desc[0].actual_length)) {
                ++stats_.feedback_packets;
                invalid_feedback_streak_ = 0;
            } else {
                ++stats_.invalid_feedback_packets;
                if (++invalid_feedback_streak_ >= 8) fail_locked(LIBUSB_ERROR_INVALID_PARAM);
            }
        }
    } else {
        for (auto& slot : slots_) if (slot.transfer == transfer) {
            slot.pending = false;
            in_flight_ -= slot.payload_size;
            if (!closed_) {
                bool valid = transfer->status == LIBUSB_TRANSFER_COMPLETED;
                for (int i = 0; i < transfer->num_iso_packets; ++i) {
                    valid = valid && transfer->iso_packet_desc[i].status == LIBUSB_TRANSFER_COMPLETED &&
                        transfer->iso_packet_desc[i].actual_length == transfer->iso_packet_desc[i].length;
                }
                if (valid) stats_.completed_frames += slot.payload_size / frame_bytes_;
                else fail_locked(LIBUSB_ERROR_IO);
            }
            break;
        }
    }
    changed_.notify_all();
}

void IsoOutput::run() {
    bool cancelling = false;
    for (;;) {
        {
            std::unique_lock<std::mutex> lock(mutex_);
            if (closed_) {
                if (!cancelling) {
                    for (auto& slot : slots_) if (slot.pending) libusb_cancel_transfer(slot.transfer);
                    if (feedback_pending_) libusb_cancel_transfer(feedback_);
                    cancelling = true;
                }
                if (!feedback_pending_ && std::none_of(slots_.begin(), slots_.end(), [](const Slot& slot) { return slot.pending; })) break;
            } else {
                if (feedback_ && !feedback_pending_ && !paused_ && in_flight_ > 0) {
                    const int result = libusb_submit_transfer(feedback_);
                    if (result < 0) fail_locked(result);
                    else feedback_pending_ = true;
                }
                if (!closed_) prepare_and_submit();
                if (!paused_ && !draining_ && stats_.completed_frames > 0 && in_flight_ == 0 && !starved_) {
                    ++stats_.underruns;
                    starved_ = true;
                }
                if (!closed_ && in_flight_ == 0 && !feedback_pending_) {
                    const auto queued_before = queued_;
                    const bool paused_before = paused_, draining_before = draining_;
                    changed_.wait(lock, [&] {
                        return closed_ || queued_ != queued_before || paused_ != paused_before || draining_ != draining_before;
                    });
                    continue;
                }
            }
        }
        timeval timeout{0, 1000};
        const int result = libusb_handle_events_timeout(context_, &timeout);
        if (result < 0 && result != LIBUSB_ERROR_INTERRUPTED) {
            std::lock_guard<std::mutex> lock(mutex_);
            fail_locked(result);
        }
    }
    release(); // All completion/cancellation callbacks ran before any transfer is freed.
}

void IsoOutput::release() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (release_started_) return;
        release_started_ = true;
    }
    for (auto& slot : slots_) if (slot.transfer) {
        libusb_free_transfer(slot.transfer);
        slot.transfer = nullptr;
    }
    if (feedback_) { libusb_free_transfer(feedback_); feedback_ = nullptr; }
    if (device_) {
        if (alternate_active_) libusb_set_interface_alt_setting(device_, profile_.interface_number, 0);
        if (claimed_) libusb_release_interface(device_, profile_.interface_number);
        if (control_claimed_) libusb_release_interface(device_, profile_.control_interface);
        libusb_close(device_);
        device_ = nullptr;
    }
    if (context_) { libusb_exit(context_); context_ = nullptr; }
    if (fd_ >= 0) { ::close(fd_); fd_ = -1; }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        released_ = true;
        changed_.notify_all();
    }
}
}
