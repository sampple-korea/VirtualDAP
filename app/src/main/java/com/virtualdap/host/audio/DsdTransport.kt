package com.virtualdap.host.audio

enum class DsdBitOrder { MSB_FIRST, LSB_FIRST }

/** One interleaved byte per channel carries eight consecutive one-bit samples. */
data class DsdFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val bitOrder: DsdBitOrder = DsdBitOrder.MSB_FIRST,
) {
    init {
        require(sampleRate in setOf(
            2_822_400, 5_644_800, 11_289_600, 22_579_200, 45_158_400,
            3_072_000, 6_144_000, 12_288_000, 24_576_000, 49_152_000,
        )) {
            "Unsupported DSD rate: $sampleRate"
        }
        require(channelCount in 1..8) { "Invalid DSD channel count" }
    }

    val dopSampleRate: Int get() = sampleRate / 16

    fun shortLabel(): String {
        val baseRate = if (sampleRate % 44_100 == 0) 44_100 else 48_000
        return "DSD${sampleRate / baseRate} · ${sampleRate / 1_000.0} kHz · $channelCount ch"
    }
}

enum class DopContainer(val bytesPerSample: Int) {
    PACKED_24(3),
    /** UAC 24-bit samples in four-byte subslots are left-justified. */
    PADDED_32(4),
}

/**
 * DoP 1.1 framing. Only an explicitly bit-transparent, DoP-capable output may consume the result.
 * These bytes must not be routed through StreamingPcmConverter, volume processing or a PCM mixer.
 */
class DopEncoder(
    private val format: DsdFormat,
    private val container: DopContainer = DopContainer.PACKED_24,
) {
    private var pending = ByteArray(0)
    private var marker = 0x05

    fun encode(interleavedDsd: ByteArray): ByteArray {
        require(interleavedDsd.size <= 1024 * 1024) { "DSD packet exceeds the size limit" }
        require(interleavedDsd.size % format.channelCount == 0) {
            "DSD packet contains an incomplete interleaved channel frame"
        }
        val input = if (pending.isEmpty()) interleavedDsd else pending + interleavedDsd
        val bytesPerDopFrame = format.channelCount * 2
        val frameCount = input.size / bytesPerDopFrame
        val output = ByteArray(frameCount * format.channelCount * container.bytesPerSample)
        var destination = 0
        repeat(frameCount) { frame ->
            val source = frame * bytesPerDopFrame
            repeat(format.channelCount) { channel ->
                if (container == DopContainer.PADDED_32) output[destination++] = 0
                // The oldest DSD bit occupies payload bit 15. In a little-endian 24-bit
                // container the later byte therefore precedes the earlier byte.
                output[destination++] = normalize(input[source + format.channelCount + channel])
                output[destination++] = normalize(input[source + channel])
                output[destination++] = marker.toByte()
            }
            marker = marker xor 0xff
        }
        pending = input.copyOfRange(frameCount * bytesPerDopFrame, input.size)
        return output
    }

    /** Do not silently add music samples or lose half a frame when a source is truncated. */
    fun finish() {
        check(pending.isEmpty()) { "DSD stream ends with an incomplete 16-sample DoP frame" }
    }

    /** A seek/flush is a discontinuity, so no payload or marker state may cross it. */
    fun reset() {
        pending = ByteArray(0)
        marker = 0x05
    }

    private fun normalize(byte: Byte): Byte = when (format.bitOrder) {
        DsdBitOrder.MSB_FIRST -> byte
        DsdBitOrder.LSB_FIRST -> (Integer.reverse(byte.toInt() and 0xff) ushr 24).toByte()
    }
}

// NATIVE_DSD is retained as a command identifier so stale requests fail explicitly.
enum class DsdOutputMode { NATIVE_DSD, DOP, PCM_CONVERSION }
