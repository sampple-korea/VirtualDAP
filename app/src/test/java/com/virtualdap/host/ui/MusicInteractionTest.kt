package com.virtualdap.host.ui

import com.virtualdap.host.audio.DsdOutputMode
import com.virtualdap.host.container.ContainerApp
import com.virtualdap.host.container.ContainerPhase
import com.virtualdap.host.model.*
import org.junit.Assert.*
import org.junit.Test

class MusicInteractionTest {
    private val app = ContainerApp("com.example.music", "음악 Player", 34)
    private val route = OutputRoute(-100, "DAC", 11, true, emptyList(), emptyList(), directUsbDeviceId = 1)
    private val audio = PipelineSnapshot(selectedRouteId = route.id, availableRoutes = listOf(route))

    @Test fun searchMatchesTrimmedNamesAndPackageNamesWithoutCaseSensitivity() {
        assertTrue(MusicInteraction.matches(app, " 음악 "))
        assertTrue(MusicInteraction.matches(app, "PLAYER"))
        assertTrue(MusicInteraction.matches(app, "EXAMPLE"))
        assertTrue(MusicInteraction.matches(app, "  "))
        assertFalse(MusicInteraction.matches(app, "other"))
    }

    @Test fun launchRequiresAnEligiblePresentSelectedOutput() {
        assertNull(MusicInteraction.launchBlock(ContainerPhase.READY, app, audio))
        assertNotNull(MusicInteraction.launchBlock(ContainerPhase.READY, app, audio.copy(availableRoutes = emptyList())))
        assertNotNull(MusicInteraction.launchBlock(ContainerPhase.READY, app, audio.copy(outputMode = OutputMode.OFFICIAL_BIT_PERFECT)))
    }

    @Test fun busyStatesExplainWhyLaunchIsDisabled() {
        assertNotNull(MusicInteraction.launchBlock(ContainerPhase.REMOVING, app, audio))
        assertNotNull(MusicInteraction.launchBlock(ContainerPhase.READY, app.copy(starting = true), audio))
        assertNotNull(MusicInteraction.launchBlock(ContainerPhase.READY, app, audio.copy(outputTestPhase = OutputTestPhase.RUNNING)))
        assertNotNull(MusicInteraction.launchBlock(ContainerPhase.READY, app, audio.copy(
            dsdPlayback = DsdPlaybackSnapshot(phase = DsdPlaybackPhase.PAUSED))))
    }

    @Test fun onlyActiveRawDsdBypassesTheUsbVolumeControl() {
        for (mode in DsdOutputMode.entries) {
            for (phase in DsdPlaybackPhase.entries) {
                val dsd = DsdPlaybackSnapshot(phase = phase, mode = mode)
                assertEquals("$mode / $phase", dsd.active && mode != DsdOutputMode.PCM_CONVERSION,
                    MusicInteraction.bypassesSoftwareVolume(audio.copy(dsdPlayback = dsd)))
            }
        }
    }
}
