#include <samplerate.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

namespace {

constexpr double kPi = 3.14159265358979323846;

std::vector<float> convert_tone(double frequency) {
    constexpr int input_rate = 352800;
    constexpr int output_rate = 44100;
    std::vector<float> input(input_rate);
    for (size_t frame = 0; frame < input.size(); ++frame) {
        input[frame] = static_cast<float>(0.8 * std::sin(2.0 * kPi * frequency * frame / input_rate));
    }
    std::vector<float> output(output_rate + 1024);
    SRC_DATA data{};
    data.data_in = input.data();
    data.data_out = output.data();
    data.input_frames = static_cast<long>(input.size());
    data.output_frames = static_cast<long>(output.size());
    data.end_of_input = 1;
    data.src_ratio = static_cast<double>(output_rate) / input_rate;
    const int error = src_simple(&data, SRC_SINC_BEST_QUALITY, 1);
    if (error != 0 || data.input_frames_used != static_cast<long>(input.size())) {
        std::fprintf(stderr, "libsamplerate failed: %s\n", src_strerror(error));
        std::exit(1);
    }
    output.resize(static_cast<size_t>(data.output_frames_gen));
    return output;
}

double settled_rms(const std::vector<float>& samples) {
    const size_t trim = std::min<size_t>(1024, samples.size() / 4);
    double energy = 0.0;
    for (size_t index = trim; index < samples.size() - trim; ++index) {
        energy += static_cast<double>(samples[index]) * samples[index];
    }
    return std::sqrt(energy / std::max<size_t>(1, samples.size() - trim * 2));
}

}  // namespace

int main() {
    const auto passband = convert_tone(1000.0);
    const auto stopband = convert_tone(30000.0);
    const double passband_rms = settled_rms(passband);
    const double stopband_rms = settled_rms(stopband);
    if (passband.size() < 44099 || passband.size() > 44101 || passband_rms < 0.55 ||
        stopband_rms > 0.0001 || stopband_rms >= passband_rms / 1000.0) {
        std::fprintf(stderr, "Unexpected sinc response: frames=%zu pass=%g stop=%g\n",
                     passband.size(), passband_rms, stopband_rms);
        return 1;
    }
    std::printf("libsamplerate %s: pass RMS %.6f, stop RMS %.9f\n",
                src_get_version(), passband_rms, stopband_rms);
    return 0;
}
