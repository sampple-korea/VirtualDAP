package com.virtualdap.host.audio

/** PCM formats carried over the VirtualDAP bridge. Values are stable wire identifiers. */
enum class PcmEncoding(val wireId: Int, val bytesPerSample: Int, val displayName: String) {
    PCM_16(1, 2, "16-bit PCM"),
    PCM_24_PACKED(2, 3, "24-bit PCM"),
    PCM_32(3, 4, "32-bit PCM"),
    PCM_FLOAT(4, 4, "32-bit float");

    companion object {
        fun fromWireId(id: Int): PcmEncoding = entries.firstOrNull { it.wireId == id }
            ?: throw IllegalArgumentException("Unsupported PCM wire encoding: $id")
    }
}

data class PcmFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: PcmEncoding,
) {
    init {
        // DSD-to-PCM first emits one float sample per source byte (8:1 decimation), so DSD1024
        // reaches 6.144 MHz before the explicit high-quality output resampler. Bridge inputs retain
        // their tighter 768 kHz protocol limit at the wire parser.
        require(sampleRate in 8_000..6_144_000) { "Invalid sample rate: $sampleRate" }
        require(channelCount in 1..8) { "Invalid channel count: $channelCount" }
    }

    val frameSizeBytes: Int = channelCount * encoding.bytesPerSample
    val nominalBitrate: Long = sampleRate.toLong() * channelCount * encoding.bytesPerSample * 8L

    fun shortLabel(): String = "${sampleRate / 1_000.0} kHz · ${encoding.displayName} · ${channelCount} ch"
}
