package com.virtualdap.host.audio.usb

import com.virtualdap.host.audio.DsdBitOrder
import com.virtualdap.host.audio.DsdWordOrder
import com.virtualdap.host.audio.NativeDsdLayout

data class UsbDeviceIdentity(val vendor: Int, val product: Int, val revision: Int) {
    val key: Long get() = (vendor.toLong() shl 16) or product.toLong()
    companion object {
        fun fromDescriptors(raw: ByteArray): UsbDeviceIdentity? {
            if (raw.size < 18 || (raw[0].toInt() and 0xff) != 18 || (raw[1].toInt() and 0xff) != 1) return null
            fun word(offset: Int) = (raw[offset].toInt() and 0xff) or ((raw[offset + 1].toInt() and 0xff) shl 8)
            return UsbDeviceIdentity(word(8), word(10), word(12))
        }
    }
}

/**
 * Independently represented hardware format facts, not copied driver implementation.
 * These qualify a wire layout, not a measured result on the connected hardware.
 */
object UsbDsdDeviceRules {
    const val REFERENCE = "https://github.com/torvalds/linux/blob/b7313376809292f0e6bf2d5750225c8b66e9ccda/sound/usb/quirks.c"
    private val bigEndianAlt2 = setOf(
        0x139f5504L, 0x20b13089L, 0x25220007L, 0x25220009L, 0x25220012L, 0x27720230L, 0x16d00a23L,
    )
    private val bigEndianAlt3 = setOf(
        0x0d8c0316L, 0x10cb0103L, 0x16d006b2L, 0x16d006b4L, 0x16d00733L, 0x16d009d8L,
        0x16d009dbL, 0x16d009ddL, 0x16d00ab1L, 0x16d0eca1L, 0x1db50003L, 0x20a04143L,
        0x22e1ca01L, 0x249c9326L, 0x26160106L, 0x26220041L, 0x26220061L, 0x278b5100L,
        0x27f73002L, 0x29a20086L, 0x6b420042L,
    )
    // Linux only promotes UAC2 RAW_DATA to DSD for devices carrying its DSD_RAW
    // quirk. Mirror that allow-list and its device-level overrides; RAW_DATA alone
    // is deliberately never treated as proof because it is a generic container.
    private val rawVendors = setOf(
        0x1511, 0x152a, 0x18d1, 0x20b1, 0x21ed, 0x22d9, 0x23ba, 0x25ce, 0x2622,
        0x2772, 0x278b, 0x292b, 0x2972, 0x2ab6, 0x2afd, 0x2d87, 0x2fc6, 0x3336,
        0x3353, 0x35f4, 0x3842, 0xc502,
    )
    private val rawDevices = setOf(
        0x06610883L, 0x154e300bL, 0x16d00ab1L, 0x16d0eca1L, 0x21b40230L,
        0x21b40232L, 0x262a9302L,
    )
    private val rawExceptions = setOf(
        0x152a880aL, 0x27720502L, 0x2fc6f06bL, 0x2fc6f0b5L, 0x2fc6f0b7L,
    )

    fun qualify(profiles: List<UsbAudioStreamingProfile>, identity: UsbDeviceIdentity?): List<UsbAudioStreamingProfile> {
        if (identity == null) return profiles
        return profiles.map { profile ->
            val wordOrder = when {
                identity.key in bigEndianAlt2 && profile.alternateSetting == 2 -> DsdWordOrder.BIG_ENDIAN
                identity.key in bigEndianAlt3 && profile.alternateSetting == 3 -> DsdWordOrder.BIG_ENDIAN
                identity.key == 0x16d0071aL && profile.alternateSetting == 2 && identity.revision == 0x0199 ->
                    DsdWordOrder.LITTLE_ENDIAN
                identity.key == 0x16d0071aL && profile.alternateSetting == 2 && identity.revision in setOf(0x019b, 0x0203) ->
                    DsdWordOrder.BIG_ENDIAN
                (identity.vendor in rawVendors || identity.key in rawDevices) &&
                    identity.key !in rawExceptions && profile.rawData -> DsdWordOrder.BIG_ENDIAN
                else -> null
            }
            if (wordOrder == null) {
                if (identity.key == 0x16d0071aL && profile.alternateSetting == 2) {
                    profile.copy(pcm = false, floatingPoint = false, rawData = true, dsdQualification = "Unqualified Amanero firmware revision")
                } else profile
            }
            else {
                // Never let a known DSD-only alternate become a PCM fallback, even if its
                // descriptor lies or its layout is not supported by this implementation.
                profile.copy(
                    pcm = false, floatingPoint = false, rawData = true,
                    nativeDsd = NativeDsdLayout(4, wordOrder, DsdBitOrder.MSB_FIRST).takeIf { profile.subslotBytes == 4 },
                    dsdQualification = REFERENCE,
                )
            }
        }
    }

    fun parseQualified(raw: ByteArray): List<UsbAudioStreamingProfile> =
        qualify(UsbAudioDescriptors.parse(raw), UsbDeviceIdentity.fromDescriptors(raw))
}
