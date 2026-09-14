package com.virtualdap.host.audio

enum class DsdWordOrder { BIG_ENDIAN, LITTLE_ENDIAN }

data class NativeDsdLayout(val wordBytes: Int, val wordOrder: DsdWordOrder, val bitOrder: DsdBitOrder) {
    init { require(wordBytes in setOf(1, 2, 4)) }
}

/** Groups chronological DSD bytes into explicitly negotiated per-channel words, without DSP. */
class NativeDsdEncoder(private val format: DsdFormat, val layout: NativeDsdLayout) {
    private var pending = ByteArray(0)
    val transportRate: Int = format.sampleRate / (8 * layout.wordBytes)

    fun encode(input: ByteArray): ByteArray {
        require(input.size <= 1024 * 1024 && input.size % format.channelCount == 0) {
            "Invalid interleaved DSD packet"
        }
        val combined = if (pending.isEmpty()) input else pending + input
        val frameBytes = layout.wordBytes * format.channelCount
        val complete = combined.size / frameBytes * frameBytes
        val output = ByteArray(complete)
        var destination = 0
        for (frame in 0 until complete / frameBytes) {
            repeat(format.channelCount) { channel ->
                repeat(layout.wordBytes) { byte ->
                    val temporal = if (layout.wordOrder == DsdWordOrder.BIG_ENDIAN) byte else layout.wordBytes - 1 - byte
                    val sample = combined[frame * frameBytes + temporal * format.channelCount + channel]
                    output[destination++] = if (format.bitOrder == layout.bitOrder) sample
                        else (Integer.reverse(sample.toInt() and 0xff) ushr 24).toByte()
                }
            }
        }
        pending = combined.copyOfRange(complete, combined.size)
        return output
    }

    fun finish() { check(pending.isEmpty()) { "DSD source ends with an incomplete native transport word" } }
    fun reset() { pending = ByteArray(0) }
}

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
