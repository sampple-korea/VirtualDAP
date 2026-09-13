package com.virtualdap.host.audio.usb

data class UsbSampleRateRange(val minimum: Int, val maximum: Int, val resolution: Int = 0) {
    init {
        require(minimum > 0 && maximum >= minimum && resolution >= 0) { "Invalid USB sample-rate range" }
    }

    fun contains(rate: Int): Boolean = rate in minimum..maximum &&
        (resolution == 0 || (rate - minimum) % resolution == 0)
}

data class UsbAudioStreamingProfile(
    val configuration: Int,
    val controlInterface: Int,
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val protocol: Int,
    val endpointAddress: Int,
    val feedbackEndpointAddress: Int?,
    val interval: Int,
    val maximumPacketBytes: Int,
    val synchronizationType: Int,
    val channelCount: Int,
    val subslotBytes: Int,
    val bitResolution: Int,
    val pcm: Boolean,
    val floatingPoint: Boolean,
    val rawData: Boolean,
    val clockEntity: Int?,
    val rates: List<UsbSampleRateRange>,
    val endpointFrequencyControl: Boolean,
) {
    val frameBytes: Int get() = channelCount * subslotBytes
}

/**
 * Parses standard UAC1/UAC2 output alternatives from UsbDeviceConnection.getRawDescriptors().
 * UAC2 rates are queried through the clock entity later; RAW_DATA is only a candidate and does
 * not prove that a device accepts native DSD.
 */
object UsbAudioDescriptors {
    fun parse(raw: ByteArray): List<UsbAudioStreamingProfile> {
        require(raw.size <= 1024 * 1024) { "USB descriptors exceed the size limit" }
        val profiles = mutableListOf<Builder>()
        val clocks = mutableMapOf<Triple<Int, Int, Int>, Int>()
        var configuration = 0
        var controlInterface = -1
        var interfaceNumber = -1
        var subclass = 0
        var protocol = 0
        var current: Builder? = null
        var offset = 0
        while (offset < raw.size) {
            require(offset + 2 <= raw.size) { "Truncated USB descriptor header" }
            val length = raw.u8(offset)
            require(length >= 2 && offset + length <= raw.size) { "Invalid USB descriptor length at $offset" }
            val descriptor = raw.copyOfRange(offset, offset + length)
            when (descriptor.u8(1)) {
                2 -> {
                    require(length >= 9) { "Truncated configuration descriptor" }
                    configuration = descriptor.u8(5)
                    controlInterface = -1
                    current = null
                    subclass = 0
                }
                4 -> {
                    require(length >= 9) { "Truncated interface descriptor" }
                    interfaceNumber = descriptor.u8(2)
                    subclass = if (descriptor.u8(5) == 1) descriptor.u8(6) else 0
                    protocol = descriptor.u8(7)
                    current = null
                    if (subclass == 1) controlInterface = interfaceNumber
                    if (subclass == 2 && descriptor.u8(3) != 0 && protocol in setOf(0, 0x20)) {
                        current = Builder(configuration, controlInterface, interfaceNumber, descriptor.u8(3), protocol)
                        profiles += current
                    }
                }
                0x24 -> {
                    require(length >= 3) { "Truncated class descriptor" }
                    val subtype = descriptor.u8(2)
                    if (subclass == 1 && protocol == 0x20) {
                        if (subtype == 2 && length >= 17) {
                            clocks[Triple(configuration, controlInterface, descriptor.u8(3))] = descriptor.u8(7)
                        } else if (subtype == 3 && length >= 12) {
                            clocks[Triple(configuration, controlInterface, descriptor.u8(3))] = descriptor.u8(8)
                        }
                    }
                    if (subclass == 2) current?.readClassDescriptor(descriptor)
                }
                5 -> current?.readEndpoint(descriptor)
                0x25 -> current?.readEndpointClassDescriptor(descriptor)
            }
            offset += length
        }
        return profiles.mapNotNull { it.build(clocks[Triple(it.configuration, it.controlInterface, it.terminal)]) }
    }

    /** UAC2 GET_RANGE payload, including its two-byte subrange count. */
    fun parseClockRanges(payload: ByteArray): List<UsbSampleRateRange> {
        require(payload.size >= 2) { "Missing USB clock range count" }
        val count = payload.u16(0)
        require(count in 1..256 && payload.size == 2 + count * 12) { "Invalid USB clock range payload" }
        return List(count) { index ->
            val offset = 2 + index * 12
            UsbSampleRateRange(payload.u32(offset), payload.u32(offset + 4), payload.u32(offset + 8))
        }
    }

    private class Builder(
        val configuration: Int,
        val controlInterface: Int,
        val interfaceNumber: Int,
        val alternate: Int,
        val protocol: Int,
    ) {
        var terminal = 0
        var channelCount = 0
        var subslot = 0
        var bits = 0
        var formats = 0L
        var endpoint = 0
        var feedback: Int? = null
        var interval = 0
        var maximumPacketBytes = 0
        var syncType = 0
        var endpointFrequencyControl = false
        private var lastEndpoint = 0
        val rates = mutableListOf<UsbSampleRateRange>()

        fun readClassDescriptor(bytes: ByteArray) {
            when (bytes.u8(2)) {
                1 -> {
                    if (protocol == 0) {
                        require(bytes.size >= 7) { "Truncated UAC1 stream descriptor" }
                        terminal = bytes.u8(3)
                        formats = when (bytes.u16(5)) { 1 -> 1; 3 -> 4; else -> 0 }
                    } else {
                        require(bytes.size >= 16) { "Truncated UAC2 stream descriptor" }
                        if (bytes.u8(5) != 1) return
                        terminal = bytes.u8(3)
                        formats = bytes.u32(6).toLong() and 0xffff_ffffL
                        channelCount = bytes.u8(10)
                    }
                }
                2 -> {
                    require(bytes.size >= 4) { "Truncated format descriptor" }
                    if (bytes.u8(3) != 1) return
                    if (protocol == 0) {
                        require(bytes.size >= 8) { "Truncated UAC1 PCM format" }
                        channelCount = bytes.u8(4)
                        subslot = bytes.u8(5)
                        bits = bytes.u8(6)
                        val count = bytes.u8(7)
                        require(bytes.size >= if (count == 0) 14 else 8 + count * 3) {
                            "Truncated UAC1 sample frequencies"
                        }
                        if (count == 0) {
                            rates += UsbSampleRateRange(bytes.u24(8), bytes.u24(11))
                        } else repeat(count) { index ->
                            val rate = bytes.u24(8 + index * 3)
                            rates += UsbSampleRateRange(rate, rate)
                        }
                    } else {
                        require(bytes.size >= 6) { "Truncated UAC2 PCM format" }
                        subslot = bytes.u8(4)
                        bits = bytes.u8(5)
                    }
                }
            }
        }

        fun readEndpoint(bytes: ByteArray) {
            require(bytes.size >= 7) { "Truncated USB endpoint" }
            val address = bytes.u8(2)
            lastEndpoint = address
            val attributes = bytes.u8(3)
            if (attributes and 3 != 1) return // Audio transfers must be isochronous.
            val usage = (attributes ushr 4) and 3
            if (address and 0x80 != 0 && usage == 1) {
                feedback = address
            } else if (address and 0x80 == 0 && usage == 0) {
                require(endpoint == 0) { "Ambiguous USB audio output endpoints" }
                endpoint = address
                interval = bytes.u8(6)
                val packetSize = bytes.u16(4)
                val additionalTransactions = (packetSize ushr 11) and 3
                require(additionalTransactions != 3) { "Invalid high-bandwidth USB endpoint" }
                maximumPacketBytes = (packetSize and 0x7ff) * (additionalTransactions + 1)
                syncType = (attributes ushr 2) and 3
                if (bytes.size >= 9 && bytes.u8(8) != 0) feedback = bytes.u8(8)
            }
        }

        fun readEndpointClassDescriptor(bytes: ByteArray) {
            if (bytes.size >= 4 && protocol == 0 && endpoint != 0 && lastEndpoint == endpoint) {
                endpointFrequencyControl = bytes.u8(3) and 1 != 0
            }
        }

        fun build(clock: Int?): UsbAudioStreamingProfile? {
            if (endpoint == 0 || channelCount !in 1..8 || subslot !in 1..8 ||
                bits !in 1..subslot * 8 || interval !in 1..16 || maximumPacketBytes == 0
            ) return null
            return UsbAudioStreamingProfile(
                configuration, controlInterface, interfaceNumber, alternate, protocol, endpoint,
                feedback, interval, maximumPacketBytes, syncType, channelCount, subslot, bits,
                formats and 1 != 0L, formats and 4 != 0L, formats and 0x8000_0000L != 0L,
                clock, rates.toList(), endpointFrequencyControl,
            )
        }
    }

    private fun ByteArray.u8(offset: Int) = this[offset].toInt() and 0xff
    private fun ByteArray.u16(offset: Int) = u8(offset) or (u8(offset + 1) shl 8)
    private fun ByteArray.u24(offset: Int) = u16(offset) or (u8(offset + 2) shl 16)
    private fun ByteArray.u32(offset: Int) = u24(offset) or (u8(offset + 3) shl 24)
}

/** Fractional packet lengths preserve rates such as 44.1 kHz without modifying any PCM sample. */
class UsbIsoPacketClock(
    val sampleRate: Int,
    val serviceIntervalsPerSecond: Int,
) {
    private var remainder = 0L

    init {
        require(sampleRate in 8_000..6_144_000 && serviceIntervalsPerSecond in 1..8_000)
    }

    fun nextFrames(): Int {
        remainder += sampleRate
        val frames = remainder / serviceIntervalsPerSecond
        remainder %= serviceIntervalsPerSecond
        return frames.toInt()
    }
}
