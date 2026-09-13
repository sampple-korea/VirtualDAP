package com.virtualdap.host.audio.usb

/** One USB control transaction using the descriptor granted by Android's UsbManager. */
fun interface UsbAudioControlPipe {
    fun transfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray): Int
}

class UsbAudioClockControl(private val pipe: UsbAudioControlPipe) {
    fun supportedRates(profile: UsbAudioStreamingProfile): List<UsbSampleRateRange> {
        if (profile.protocol == 0) return profile.rates
        require(profile.protocol == 0x20) { "Unsupported USB Audio protocol" }
        val index = clockIndex(profile)
        val countBytes = ByteArray(2)
        readExact(0xa1, 2, 0x100, index, countBytes)
        val count = countBytes.u16(0)
        require(count in 1..256) { "Invalid number of USB clock ranges" }
        val ranges = ByteArray(2 + count * 12)
        readExact(0xa1, 2, 0x100, index, ranges)
        return UsbAudioDescriptors.parseClockRanges(ranges)
    }

    /** Set a declared rate and read it back. A rejected or different rate is a negotiation error. */
    fun setAndVerify(profile: UsbAudioStreamingProfile, sampleRate: Int) {
        require(sampleRate in 8_000..6_144_000) { "Invalid requested USB audio rate" }
        require(supportedRates(profile).any { it.contains(sampleRate) }) {
            "USB interface does not advertise $sampleRate Hz"
        }
        if (profile.protocol == 0 && !profile.endpointFrequencyControl) {
            require(profile.rates.size == 1 &&
                profile.rates.single().minimum == profile.rates.single().maximum
            ) { "USB interface offers multiple rates but no frequency control" }
            return // The alternate setting itself fixes this single rate.
        }
        val isUac2 = profile.protocol == 0x20
        val index = if (isUac2) clockIndex(profile) else profile.endpointAddress
        val value = ByteArray(if (isUac2) 4 else 3) { byte -> (sampleRate ushr (byte * 8)).toByte() }
        val written = pipe.transfer(if (isUac2) 0x21 else 0x22, 1, 0x100, index, value)
        check(written == value.size) { "USB DAC rejected the requested sample rate" }
        val readback = ByteArray(value.size)
        readExact(if (isUac2) 0xa1 else 0xa2, if (isUac2) 1 else 0x81, 0x100, index, readback)
        var actual = 0
        readback.forEachIndexed { byte, data -> actual = actual or ((data.toInt() and 0xff) shl (byte * 8)) }
        check(actual == sampleRate) { "USB DAC reports $actual Hz after requesting $sampleRate Hz" }
    }

    private fun clockIndex(profile: UsbAudioStreamingProfile): Int {
        val clock = profile.clockEntity ?: throw IllegalArgumentException("USB clock source is missing")
        require(profile.controlInterface in 0..255 && clock in 1..255) {
            "USB clock source is unavailable; a selector must be resolved before negotiation"
        }
        return (clock shl 8) or profile.controlInterface
    }

    private fun readExact(type: Int, request: Int, value: Int, index: Int, data: ByteArray) {
        check(pipe.transfer(type, request, value, index, data) == data.size) { "USB clock returned a truncated response" }
    }

    private fun ByteArray.u16(offset: Int) = (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8)
}
