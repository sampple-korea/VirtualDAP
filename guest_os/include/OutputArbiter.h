#ifndef VIRTUAL_DAP_OUTPUT_ARBITER_H
#define VIRTUAL_DAP_OUTPUT_ARBITER_H

namespace virtualdap {

/** Serialized by the audio device lock. Opening a direct output does not activate it. */
class OutputArbiter {
  public:
    bool reserve_direct(const void* stream) {
        if (stream == nullptr || direct_ != nullptr) return false;
        direct_ = stream;
        return true;
    }

    bool select(const void* stream) {
        if (stream == nullptr) return false;
        if (active_ != nullptr && active_ == direct_ && stream != direct_) return false;
        active_ = stream;
        return true;
    }

    /** Returns true only if the departing stream actually owns the bridge. */
    bool standby(const void* stream) {
        if (stream == nullptr || active_ != stream) return false;
        active_ = nullptr;
        return true;
    }

    bool close(const void* stream) {
        const bool owned_bridge = standby(stream);
        if (stream == direct_) direct_ = nullptr;
        return owned_bridge;
    }

  private:
    const void* direct_ = nullptr;
    const void* active_ = nullptr;
};

}  // namespace virtualdap

#endif
