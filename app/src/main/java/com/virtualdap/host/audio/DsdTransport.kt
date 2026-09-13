package com.virtualdap.host.audio

enum class DsdBitOrder { MSB_FIRST, LSB_FIRST }

/** One interleaved byte per channel carries eight consecutive one-bit samples. */
data class DsdFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val bitOrder: DsdBitOrder = DsdBitOrder.MSB_FIRST,
) {
    init {
        require(sampleRate in setOf(2_822_400, 5_644_800, 11_289_600, 22_579_200, 45_158_400)) {
            "Unsupported DSD rate: $sampleRate"
        }
        require(channelCount in 1..8) { "Invalid DSD channel count" }
    }

    val dopSampleRate: Int get() = sampleRate / 16
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

    private fun normalize(byte: Byte): Byte = when (format.bitOrder) {
        DsdBitOrder.MSB_FIRST -> byte
        DsdBitOrder.LSB_FIRST -> (Integer.reverse(byte.toInt() and 0xff) ushr 24).toByte()
    }
}

enum class DsdOutputMode { NATIVE_DSD, DOP, PCM_CONVERSION }

data class DsdOutputCapabilities(
    val channelCount: Int,
    val nativeDsdRates: Set<Int> = emptySet(),
    val dopCarrierRates: Set<Int> = emptySet(),
    val bitTransparent: Boolean = false,
)

object DsdOutputPlanner {
    fun select(source: DsdFormat, output: DsdOutputCapabilities): DsdOutputMode = when {
        !output.bitTransparent || output.channelCount != source.channelCount -> DsdOutputMode.PCM_CONVERSION
        source.sampleRate in output.nativeDsdRates -> DsdOutputMode.NATIVE_DSD
        source.dopSampleRate in output.dopCarrierRates -> DsdOutputMode.DOP
        else -> DsdOutputMode.PCM_CONVERSION
    }
}
