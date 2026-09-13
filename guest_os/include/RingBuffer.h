#ifndef VIRTUAL_DAP_RING_BUFFER_H
#define VIRTUAL_DAP_RING_BUFFER_H

#include <stdint.h>

// Optional shared-memory ABI. The socket bridge is the default transport, while this fixed-width
// layout is retained for runtimes that can map one region into both host and guest processes.
#define VIRTUAL_DAP_RING_MAGIC 0x56444150u
#define VIRTUAL_DAP_RING_VERSION 2u
#define VIRTUAL_DAP_RING_HEADER_BYTES 64u
#define VIRTUAL_DAP_RING_CAPACITY (4u * 1024u * 1024u)

struct __attribute__((aligned(8))) virtual_dap_ring_header {
    uint32_t magic;
    uint16_t version;
    uint16_t header_bytes;
    uint32_t capacity;
    uint32_t write_offset;
    uint32_t read_offset;
    uint32_t sample_rate;
    uint16_t channel_count;
    uint16_t encoding;
    uint32_t flags;
    uint64_t stream_epoch;
    uint64_t frames_written;
    uint64_t overruns;
    uint64_t reserved;
};

#ifdef __cplusplus
static_assert(sizeof(virtual_dap_ring_header) == VIRTUAL_DAP_RING_HEADER_BYTES,
              "The VirtualDAP ring header is a public wire ABI");

static inline uint32_t virtual_dap_ring_available_read(const virtual_dap_ring_header* ring) {
    const uint32_t write = __atomic_load_n(&ring->write_offset, __ATOMIC_ACQUIRE);
    const uint32_t read = __atomic_load_n(&ring->read_offset, __ATOMIC_ACQUIRE);
    return write >= read ? write - read : ring->capacity - read + write;
}

static inline uint32_t virtual_dap_ring_available_write(const virtual_dap_ring_header* ring) {
    return ring->capacity - virtual_dap_ring_available_read(ring) - 1u;
}
#endif

#endif  // VIRTUAL_DAP_RING_BUFFER_H
