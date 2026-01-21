#ifndef VIRTUAL_DAP_RING_BUFFER_H
#define VIRTUAL_DAP_RING_BUFFER_H

#include <stdint.h>
#include <stdatomic.h>

#define RING_BUFFER_MAGIC 0x56444150 // "VDAP"
#define RING_BUFFER_SIZE  (4 * 1024 * 1024) // 4MB Buffer (approx 20 sec of CD quality)

struct ring_buffer_t {
    uint32_t magic;
    uint32_t size;
    // Atomic offsets for lock-free access
    atomic_uint_fast32_t head; // Write index (Producer/Guest)
    atomic_uint_fast32_t tail; // Read index (Consumer/Host)
    
    // Format metadata (Updated by producer on stream change)
    uint32_t sample_rate;
    uint32_t channel_count;
    uint32_t format; // Android audio_format_t
    
    // The data buffer
    uint8_t data[RING_BUFFER_SIZE];
};

// Helper methods (inline for shared usage)
static inline uint32_t ring_buffer_available_read(struct ring_buffer_t* rb) {
    uint32_t head = atomic_load_explicit(&rb->head, memory_order_acquire);
    uint32_t tail = atomic_load_explicit(&rb->tail, memory_order_acquire);
    if (head >= tail) return head - tail;
    return rb->size - (tail - head);
}

static inline uint32_t ring_buffer_available_write(struct ring_buffer_t* rb) {
    // Keep 1 byte empty to distinguish full vs empty
    return rb->size - ring_buffer_available_read(rb) - 1;
}

#endif // VIRTUAL_DAP_RING_BUFFER_H
