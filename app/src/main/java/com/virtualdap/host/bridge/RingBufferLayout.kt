package com.virtualdap.host.bridge

/** Versioned, fixed-width ABI shared by the guest HAL and host reader. */
object RingBufferLayout {
    const val MAGIC = 0x56444150
    const val VERSION: Short = 2
    const val HEADER_BYTES = 64
    const val DEFAULT_CAPACITY = 4 * 1024 * 1024

    const val MAGIC_OFFSET = 0
    const val VERSION_OFFSET = 4
    const val HEADER_SIZE_OFFSET = 6
    const val CAPACITY_OFFSET = 8
    const val WRITE_OFFSET = 12
    const val READ_OFFSET = 16
    const val SAMPLE_RATE_OFFSET = 20
    const val CHANNEL_COUNT_OFFSET = 24
    const val ENCODING_OFFSET = 26
    const val FLAGS_OFFSET = 28
    const val STREAM_EPOCH_OFFSET = 32
    const val FRAMES_WRITTEN_OFFSET = 40
    const val OVERRUNS_OFFSET = 48
}
