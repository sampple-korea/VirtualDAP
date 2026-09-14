package com.virtualdap.host.service

import com.virtualdap.host.audio.PcmEncoding
import com.virtualdap.host.audio.PcmFormat
import com.virtualdap.host.model.OutputRoute
import org.junit.Assert.*
import org.junit.Test

class OutputSupportPresentationTest {
    private val usb = OutputRoute(7, "USB DAC", 11, true, listOf(48000), emptyList())

    @Test fun connectedUsbDoesNotImplyOfficialOrDirectSupport() {
        val message = OutputSupportPresentation.officialStatus(usb)
        assertTrue(message.contains("USB 오디오가 연결"))
        assertTrue(message.contains("제공하지 않았습니다"))
        assertTrue(message.contains("기본 USB 모드의 지원 여부와는 별개"))
    }

    @Test fun queryFailureIsNotMisreportedAsUnsupportedHardware() {
        val message = OutputSupportPresentation.officialStatus(usb.copy(officialQueryFailure = "SecurityException"))
        assertTrue(message.contains("조회 실패"))
        assertTrue(message.contains("확인하지 못했습니다"))
        assertFalse(message.contains("제공하지 않았습니다"))
    }

    @Test fun unhandledAdvertisedFormatIsDistinguishedFromMissingCapability() {
        val message = OutputSupportPresentation.officialStatus(usb.copy(officialBitPerfectReported = true))
        assertTrue(message.contains("이 빌드에서 사용할 수 있는 포맷은 없습니다"))
    }

    @Test fun actualAdvertisedFormatsAreShown() {
        val format = PcmFormat(48000, 2, PcmEncoding.PCM_16)
        assertEquals(format.shortLabel(), OutputSupportPresentation.officialStatus(
            usb.copy(officialBitPerfectFormats = listOf(format)),
        ))
    }

    @Test fun builtInOutputIsNotCalledAUsbDevice() {
        assertFalse(OutputSupportPresentation.officialStatus(usb.copy(isUsb = false)).contains("USB 오디오가 연결"))
    }
}
