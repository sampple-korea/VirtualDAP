#include "dsd2pcm.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

#define CHECK(condition) do { if (!(condition)) { \
    std::fprintf(stderr, "CHECK failed at line %d: %s\n", __LINE__, #condition); \
    std::abort(); } } while (false)

int main() {
    dsd2pcm_precalc();
    auto* whole = dsd2pcm_init();
    auto* split = dsd2pcm_init();
    CHECK(whole != nullptr && split != nullptr);
    std::vector<unsigned char> bits(512);
    for (size_t i = 0; i < bits.size(); ++i) bits[i] = static_cast<unsigned char>(i * 71);
    std::vector<float> all(bits.size()), chunks(bits.size());
    dsd2pcm_translate(whole, bits.size(), bits.data(), 1, 0, all.data(), 1);
    dsd2pcm_translate(split, 37, bits.data(), 1, 0, chunks.data(), 1);
    dsd2pcm_translate(split, bits.size() - 37, bits.data() + 37, 1, 0, chunks.data() + 37, 1);
    CHECK(all == chunks);

    dsd2pcm_reset(whole);
    dsd2pcm_reset(split);
    std::vector<unsigned char> reversed(bits.size());
    std::transform(bits.begin(), bits.end(), reversed.begin(),
                   [](unsigned char value) { return dsd2pcm_bitreverse[value]; });
    dsd2pcm_translate(whole, bits.size(), bits.data(), 1, 0, all.data(), 1);
    dsd2pcm_translate(split, reversed.size(), reversed.data(), 1, 1, chunks.data(), 1);
    CHECK(all == chunks);

    for (unsigned char pattern : {0x00, 0xff, 0x69}) {
        dsd2pcm_reset(whole);
        std::fill(bits.begin(), bits.end(), pattern);
        dsd2pcm_translate(whole, bits.size(), bits.data(), 1, 0, all.data(), 1);
        const float expected = pattern == 0 ? -1.0f : pattern == 0xff ? 1.0f : 0.0f;
        CHECK(std::isfinite(all.back()));
        CHECK(std::abs(all.back() - expected) < 0.00001f);
    }
    dsd2pcm_destroy(whole);
    dsd2pcm_destroy(split);
    std::puts("DSD filter: packet continuity, bit order, DC gain and silence OK");
}
