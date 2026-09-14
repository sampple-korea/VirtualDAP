package com.virtualdap.host.container

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerRecoveryTest {
    @Test fun failedOrReadyMusicSpaceAllowsRefresh() {
        assertTrue(ContainerPhase.ERROR.canRefresh)
        assertTrue(ContainerPhase.READY.canRefresh)
    }

    @Test fun ongoingInitializationOrImportDisablesRefresh() {
        assertFalse(ContainerPhase.INITIALIZING.canRefresh)
        assertFalse(ContainerPhase.INSTALLING.canRefresh)
    }
}
