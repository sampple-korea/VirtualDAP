package com.virtualdap.host.service

import com.virtualdap.host.audio.PcmEncoding
import com.virtualdap.host.audio.PcmFormat
import com.virtualdap.host.model.*
import org.junit.Assert.*
import org.junit.Test

class OutputRoutePolicyTest {
    private val direct = OutputRoute(-1000007, "DAC", 11, true, emptyList(), emptyList(), directUsbDeviceId = 7)
    private val official = OutputRoute(7, "DAC", 11, true, emptyList(), emptyList(),
        officialBitPerfectFormats = listOf(PcmFormat(48000, 2, PcmEncoding.PCM_16)))

    @Test fun defaultIsUsbWithReducedInitialVolume() {
        assertEquals(OutputMode.USB, PipelineSnapshot().outputMode)
        assertEquals(0.25f, PipelineSnapshot().usbGain)
    }
    @Test fun selectionNeverCrossesModes() {
        assertTrue(OutputRoutePolicy.eligible(direct, OutputMode.USB))
        assertFalse(OutputRoutePolicy.eligible(official, OutputMode.USB))
        assertTrue(OutputRoutePolicy.eligible(official, OutputMode.OFFICIAL_BIT_PERFECT))
        assertFalse(OutputRoutePolicy.eligible(direct, OutputMode.OFFICIAL_BIT_PERFECT))
    }
    @Test fun disconnectedSelectionDoesNotFallBackToAnotherOutput() {
        assertNull(OutputRoutePolicy.selected(PipelineSnapshot(selectedRouteId = direct.id, availableRoutes = listOf(official))))
    }
    @Test fun officialQueryFailureCannotBeTreatedAsConfirmedSupport() {
        assertFalse(OutputRoutePolicy.eligible(official.copy(officialQueryFailure = "SecurityException"), OutputMode.OFFICIAL_BIT_PERFECT))
    }
}
