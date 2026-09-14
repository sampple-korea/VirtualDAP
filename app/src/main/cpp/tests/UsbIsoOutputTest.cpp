#include "../usb/UsbIsoOutput.h"
#include <algorithm>
#include <cassert>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <iostream>
#include <map>
#include <unistd.h>

using namespace std::chrono_literals;
using virtualdap::usb::IsoOutput;
using virtualdap::usb::PacketClock;
using virtualdap::usb::Profile;
#define CHECK(value) do { if (!(value)) { std::cerr << "Check failed at " << __LINE__ << ": " #value "\n"; std::abort(); } } while(false)

// This implements the narrow libusb surface used by the real IsoOutput, not a replacement stream
// implementation. Completion callbacks, cancellation, byte lengths and cleanup are exercised.
struct libusb_context {
    struct Pending {
        libusb_transfer* transfer;
        std::chrono::steady_clock::time_point due;
        bool cancelled = false;
    };
    std::mutex lock;
    std::vector<Pending> pending;
    std::chrono::steady_clock::time_point next_out = std::chrono::steady_clock::now();
};
struct libusb_device { libusb_context* context; };
struct libusb_device_handle { libusb_device device; };
namespace fake {
std::mutex mutex;
std::vector<uint8_t> received;
bool feedback = false, invalid_feedback = false, disconnect = false;
bool short_packet = false, refuse_claim = false;
uint32_t rate = 44100;
int speed = LIBUSB_SPEED_HIGH;
uint16_t packet_limit = 512;
int closes = 0, active_contexts = 0, allocated_transfers = 0, cancels = 0, duplicate_fd = -1;
void reset(bool with_feedback = false) {
    std::lock_guard<std::mutex> lock(mutex);
    CHECK(active_contexts == 0 && allocated_transfers == 0);
    received.clear();
    feedback = with_feedback;
    invalid_feedback = disconnect = short_packet = refuse_claim = false;
    rate = 44100;
    speed = LIBUSB_SPEED_HIGH;
    packet_limit = 512;
    closes = cancels = 0;
}
std::vector<uint8_t> bytes() { std::lock_guard<std::mutex> lock(mutex); return received; }
}

extern "C" {
int LIBUSB_CALL libusb_init_context(libusb_context** output, const libusb_init_option options[], int count) {
    CHECK(count == 1 && options[0].option == LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    *output = new libusb_context;
    std::lock_guard<std::mutex> lock(fake::mutex);
    ++fake::active_contexts;
    return 0;
}
void LIBUSB_CALL libusb_exit(libusb_context* context) {
    CHECK(context->pending.empty());
    delete context;
    std::lock_guard<std::mutex> lock(fake::mutex);
    --fake::active_contexts;
}
int LIBUSB_CALL libusb_wrap_sys_device(libusb_context* context, intptr_t fd, libusb_device_handle** output) {
    CHECK(fcntl(static_cast<int>(fd), F_GETFD) >= 0);
    fake::duplicate_fd = static_cast<int>(fd);
    *output = new libusb_device_handle{{context}};
    return 0;
}
void LIBUSB_CALL libusb_close(libusb_device_handle* handle) {
    CHECK(handle->device.context->pending.empty());
    delete handle;
    std::lock_guard<std::mutex> lock(fake::mutex);
    ++fake::closes;
}
const char* LIBUSB_CALL libusb_error_name(int) { return "FAKE_USB_ERROR"; }
libusb_device* LIBUSB_CALL libusb_get_device(libusb_device_handle* handle) { return &handle->device; }
int LIBUSB_CALL libusb_get_device_speed(libusb_device*) { return fake::speed; }
int LIBUSB_CALL libusb_get_configuration(libusb_device_handle*, int* value) { *value = 1; return 0; }
int LIBUSB_CALL libusb_set_auto_detach_kernel_driver(libusb_device_handle*, int value) { CHECK(value == 1); return 0; }
int LIBUSB_CALL libusb_claim_interface(libusb_device_handle*, int number) {
    CHECK(number == 1);
    return fake::refuse_claim ? LIBUSB_ERROR_ACCESS : 0;
}
int LIBUSB_CALL libusb_release_interface(libusb_device_handle*, int number) { CHECK(number == 1); return 0; }
int LIBUSB_CALL libusb_set_interface_alt_setting(libusb_device_handle*, int number, int alt) {
    CHECK(number == 1 && (alt == 0 || alt == 1)); return 0;
}
int LIBUSB_CALL libusb_get_active_config_descriptor(libusb_device*, libusb_config_descriptor** output) {
    static libusb_endpoint_descriptor endpoints[2]{};
    static libusb_interface_descriptor alternate{};
    static libusb_interface interface{};
    static libusb_config_descriptor configuration{};
    endpoints[0].bEndpointAddress = 1;
    endpoints[0].bmAttributes = LIBUSB_TRANSFER_TYPE_ISOCHRONOUS | ((fake::feedback ? 1 : 3) << 2);
    endpoints[0].wMaxPacketSize = fake::packet_limit;
    endpoints[0].bInterval = 1;
    endpoints[1].bEndpointAddress = 0x81;
    endpoints[1].bmAttributes = LIBUSB_TRANSFER_TYPE_ISOCHRONOUS | (1 << 4);
    endpoints[1].wMaxPacketSize = 4;
    endpoints[1].bInterval = 4;
    alternate.bInterfaceNumber = 1;
    alternate.bAlternateSetting = 1;
    alternate.bInterfaceClass = LIBUSB_CLASS_AUDIO;
    alternate.bInterfaceSubClass = 2;
    alternate.bNumEndpoints = fake::feedback ? 2 : 1;
    alternate.endpoint = endpoints;
    interface.altsetting = &alternate;
    interface.num_altsetting = 1;
    configuration.bNumInterfaces = 1;
    configuration.bConfigurationValue = 1;
    configuration.interface = &interface;
    *output = &configuration;
    return 0;
}
void LIBUSB_CALL libusb_free_config_descriptor(libusb_config_descriptor*) {}
int LIBUSB_CALL libusb_get_ss_endpoint_companion_descriptor(libusb_context*, const libusb_endpoint_descriptor*, libusb_ss_endpoint_companion_descriptor** output) {
    *output = new libusb_ss_endpoint_companion_descriptor{};
    (*output)->wBytesPerInterval = fake::packet_limit;
    return 0;
}
void LIBUSB_CALL libusb_free_ss_endpoint_companion_descriptor(libusb_ss_endpoint_companion_descriptor* value) { delete value; }
int LIBUSB_CALL libusb_control_transfer(libusb_device_handle*, uint8_t, uint8_t, uint16_t, uint16_t,
                                      unsigned char* data, uint16_t length, unsigned int timeout) {
    CHECK(timeout > 0);
    std::fill(data, data + length, 0xab);
    return length;
}
libusb_transfer* LIBUSB_CALL libusb_alloc_transfer(int packets) {
    auto* result = static_cast<libusb_transfer*>(calloc(1, sizeof(libusb_transfer) + packets * sizeof(libusb_iso_packet_descriptor)));
    std::lock_guard<std::mutex> lock(fake::mutex);
    ++fake::allocated_transfers;
    return result;
}
void LIBUSB_CALL libusb_free_transfer(libusb_transfer* transfer) {
    free(transfer);
    std::lock_guard<std::mutex> lock(fake::mutex);
    --fake::allocated_transfers;
}
int LIBUSB_CALL libusb_submit_transfer(libusb_transfer* transfer) {
    auto* context = transfer->dev_handle->device.context;
    std::lock_guard<std::mutex> lock(context->lock);
    CHECK(std::none_of(context->pending.begin(), context->pending.end(), [&](auto& item) { return item.transfer == transfer; }));
    auto due = std::chrono::steady_clock::now();
    if (transfer->endpoint & 0x80) {
        due += 1ms;
    } else {
        due = std::max(due, context->next_out) + std::chrono::microseconds(
            transfer->num_iso_packets * (fake::speed == LIBUSB_SPEED_FULL ? 1000 : 125));
        context->next_out = due;
    }
    context->pending.push_back({transfer, due, false});
    return 0;
}
int LIBUSB_CALL libusb_cancel_transfer(libusb_transfer* transfer) {
    auto* context = transfer->dev_handle->device.context;
    std::lock_guard<std::mutex> lock(context->lock);
    for (auto& item : context->pending) if (item.transfer == transfer) {
        item.cancelled = true;
        item.due = std::chrono::steady_clock::now();
        std::lock_guard<std::mutex> counts(fake::mutex);
        ++fake::cancels;
        return 0;
    }
    return LIBUSB_ERROR_NOT_FOUND;
}
int LIBUSB_CALL libusb_handle_events_timeout(libusb_context* context, timeval*) {
    libusb_context::Pending event{};
    {
        std::lock_guard<std::mutex> lock(context->lock);
        const auto item = std::min_element(context->pending.begin(), context->pending.end(),
            [](auto& a, auto& b) { return a.due < b.due; });
        if (item != context->pending.end() && item->due <= std::chrono::steady_clock::now()) {
            event = *item;
            context->pending.erase(item);
        }
    }
    if (!event.transfer) { std::this_thread::sleep_for(200us); return 0; }
    auto* transfer = event.transfer;
    {
        std::lock_guard<std::mutex> lock(fake::mutex);
        transfer->status = event.cancelled ? LIBUSB_TRANSFER_CANCELLED :
            fake::disconnect ? LIBUSB_TRANSFER_NO_DEVICE : LIBUSB_TRANSFER_COMPLETED;
        for (int i = 0; i < transfer->num_iso_packets; ++i) {
            auto& packet = transfer->iso_packet_desc[i];
            packet.status = transfer->status;
            packet.actual_length = transfer->status == LIBUSB_TRANSFER_COMPLETED ? packet.length : 0;
        }
        if (transfer->status == LIBUSB_TRANSFER_COMPLETED) {
            if (transfer->endpoint & 0x80) {
                uint32_t clock = fake::invalid_feedback ? 0 : uint32_t(uint64_t(fake::rate) * 65536 /
                    (fake::speed == LIBUSB_SPEED_FULL ? 1000 : 8000));
                for (int i = 0; i < 4; ++i) transfer->buffer[i] = static_cast<unsigned char>(clock >> (8 * i));
            } else {
                if (fake::short_packet && transfer->iso_packet_desc[0].actual_length > 0) --transfer->iso_packet_desc[0].actual_length;
                fake::received.insert(fake::received.end(), transfer->buffer, transfer->buffer + transfer->length);
            }
        }
    }
    transfer->callback(transfer);
    return 0;
}
}

template<typename F> void await(F predicate) {
    const auto end = std::chrono::steady_clock::now() + 4s;
    while (!predicate() && std::chrono::steady_clock::now() < end) std::this_thread::sleep_for(1ms);
    CHECK(predicate());
}
void finish(std::shared_ptr<IsoOutput>& output, int original_fd) {
    output->close();
    std::weak_ptr<IsoOutput> lifetime = output;
    output.reset();
    await([&] { return lifetime.expired(); });
    CHECK(fcntl(original_fd, F_GETFD) >= 0); // The caller's descriptor is not owned by native USB.
    CHECK(fcntl(fake::duplicate_fd, F_GETFD) < 0);
    CHECK(fake::active_contexts == 0 && fake::allocated_transfers == 0);
}

int main() {
    for (uint32_t rate : {44100u, 48000u, 96000u, 192000u, 352800u, 6144000u}) {
        for (uint32_t ticks : {1000u, 8000u}) {
            PacketClock clock(rate, ticks, 1);
            uint64_t frames = 0;
            for (uint32_t i = 0; i < ticks; ++i) frames += clock.next_frames();
            CHECK(frames == rate);
        }
    }
    PacketClock fractional_interval(44100, 1000, 16);
    uint64_t sixteen_seconds = 0;
    for (int i = 0; i < 1000; ++i) sixteen_seconds += fractional_interval.next_frames();
    CHECK(sixteen_seconds == 44100 * 16);
    PacketClock feedback(48000, 8000, 1);
    const uint8_t high[] = {0, 0, 6, 0}, bad[] = {0, 0, 0, 0};
    CHECK(feedback.feedback(high, 4));
    CHECK(!feedback.feedback(bad, 4));
    CHECK(feedback.next_frames() == 6);
    const int fd = ::open("/dev/null", O_RDONLY | O_CLOEXEC); // Fake backend only; never a USB path.
    CHECK(fd >= 0);
    std::vector<uint8_t> pcm(4410 * 4 + 17 * 4);
    for (size_t i = 0; i < pcm.size(); ++i) pcm[i] = static_cast<uint8_t>(i * 17);
    const Profile nominal{1, 1, 1, 1, 0}, asynchronous{1, 1, 1, 1, 0x81};

    fake::reset();
    auto output = IsoOutput::open(fd, nominal);
    unsigned char value[2]{};
    CHECK(output->control(0x80, 1, 0, 0, value, 2) == 2 && value[0] == 0xab);
    output->start(44100, 4);
    CHECK(output->control(0x80, 1, 0, 0, value, 2) == LIBUSB_ERROR_INVALID_PARAM);
    CHECK(output->write(pcm.data(), pcm.size() - 1) < 0);
    CHECK(output->write(pcm.data(), pcm.size()) == int(pcm.size()));
    CHECK(output->drain());
    CHECK(fake::bytes() == pcm);
    CHECK(output->statistics().completed_frames == pcm.size() / 4);
    CHECK(output->statistics().queued_bytes == 0);
    finish(output, fd);

    for (int speed : {LIBUSB_SPEED_FULL, LIBUSB_SPEED_SUPER}) {
        fake::reset(true);
        fake::speed = speed;
        output = IsoOutput::open(fd, asynchronous);
        output->start(44100, 4);
        CHECK(output->write(pcm.data(), pcm.size()) == int(pcm.size()));
        CHECK(output->drain());
        CHECK(fake::bytes() == pcm && output->statistics().feedback_packets > 0);
        finish(output, fd);
    }

    fake::reset();
    fake::packet_limit = 24; // Exactly six 16-bit stereo frames at 48 kHz/high speed.
    output = IsoOutput::open(fd, nominal);
    output->start(48000, 4); // Synchronous output does not need an asynchronous-feedback margin.
    CHECK(output->write(pcm.data(), 68) == 68);
    CHECK(output->drain());
    CHECK(fake::bytes().size() == 68);
    finish(output, fd);

    fake::reset();
    output = IsoOutput::open(fd, nominal);
    output->start(44100, 4);
    CHECK(output->pause());
    CHECK(output->write(pcm.data(), pcm.size()) == int(pcm.size()));
    std::this_thread::sleep_for(10ms);
    CHECK(fake::bytes().empty());
    output->flush();
    CHECK(output->statistics().queued_bytes == 0);
    output->resume();
    CHECK(output->write(pcm.data(), 68) == 68);
    CHECK(output->drain());
    CHECK(fake::bytes() == std::vector<uint8_t>(pcm.begin(), pcm.begin() + 68));
    finish(output, fd);

    fake::reset(true);
    output = IsoOutput::open(fd, asynchronous);
    output->start(44100, 4);
    CHECK(output->write(pcm.data(), pcm.size()) == int(pcm.size()));
    CHECK(output->drain());
    CHECK(output->statistics().feedback_packets > 0);
    CHECK(fake::bytes() == pcm);
    finish(output, fd);

    for (int failure = 0; failure < 3; ++failure) {
        fake::reset(failure == 2);
        {
            std::lock_guard<std::mutex> lock(fake::mutex);
            fake::short_packet = failure == 0;
            fake::disconnect = failure == 1;
            fake::invalid_feedback = failure == 2;
        }
        output = IsoOutput::open(fd, failure == 2 ? asynchronous : nominal);
        output->start(44100, 4);
        CHECK(output->write(pcm.data(), pcm.size()) == int(pcm.size()));
        await([&] { return output->statistics().error != 0; });
        CHECK(!output->drain());
        finish(output, fd);
    }

    fake::reset();
    output = IsoOutput::open(fd, nominal);
    output->start(44100, 4);
    CHECK(output->write(pcm.data(), pcm.size()) == int(pcm.size()));
    await([&] { return output->statistics().submitted_frames > 0; });
    finish(output, fd);
    CHECK(fake::cancels > 0);

    fake::reset();
    fake::refuse_claim = true;
    bool rejected = false;
    try { output = IsoOutput::open(fd, nominal); } catch (const std::exception&) { rejected = true; }
    CHECK(rejected && fake::active_contexts == 0);
    ::close(fd);
    std::cout << "USB output: exact bytes, fractional clock, feedback, pause/flush/drain, cancellation, errors and descriptor ownership OK\n";
}
