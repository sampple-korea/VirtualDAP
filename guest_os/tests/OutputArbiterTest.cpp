#include "OutputArbiter.h"

#include <cstdio>
#include <cstdlib>

#define CHECK(condition)                                                              \
    do {                                                                              \
        if (!(condition)) {                                                           \
            std::fprintf(stderr, "CHECK failed at line %d: %s\n", __LINE__, #condition); \
            std::abort();                                                             \
        }                                                                             \
    } while (false)

int main() {
    virtualdap::OutputArbiter arbiter;
    const int mixer = 1;
    const int direct = 2;
    const int other_direct = 3;
    CHECK(!arbiter.select(nullptr));
    CHECK(!arbiter.reserve_direct(nullptr));
    CHECK(arbiter.select(&mixer));
    CHECK(arbiter.reserve_direct(&direct));
    CHECK(!arbiter.reserve_direct(&other_direct));
    // AudioFlinger can open a direct output before the app starts writing.
    CHECK(arbiter.select(&mixer));
    CHECK(arbiter.select(&direct));
    CHECK(!arbiter.select(&mixer));
    CHECK(!arbiter.standby(&mixer));
    CHECK(!arbiter.close(&mixer));
    CHECK(arbiter.select(&direct));
    // A paused direct player must allow normal music to resume, even before it closes.
    CHECK(arbiter.standby(&direct));
    CHECK(arbiter.select(&mixer));
    CHECK(arbiter.select(&direct));
    CHECK(arbiter.close(&direct));
    CHECK(arbiter.select(&mixer));
    CHECK(arbiter.reserve_direct(&other_direct));
    CHECK(!arbiter.close(&other_direct));
    CHECK(arbiter.standby(&mixer));
    return 0;
}
