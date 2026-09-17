package com.virtualdap.host.container

import org.junit.Assert.*
import org.junit.Test

class ContainerActivityStateTest {
    private val first = ContainerActivity("com.example.music", 123, "instance-one", "PlayerActivity")
    private val state = ContainerSnapshot(applications = listOf(
        ContainerApp(first.packageName, "Music", 34, first.pid),
        ContainerApp("com.example.other", "Other", 34, 456),
    ))

    @Test fun resumeAndPauseTrackTheSameInstance() {
        val resumed = state.activityChanged(first, true)
        assertEquals(first, resumed.foregroundActivity)
        assertNull(resumed.activityChanged(first, false).foregroundActivity)
    }

    @Test fun latePauseCannotClearSuccessorEvenForTheSameActivityClass() {
        val second = first.copy(identity = "instance-two")
        val resumed = state.activityChanged(first, true).activityChanged(second, true)
        assertSame(resumed, resumed.activityChanged(first, false))
        assertEquals(second, resumed.foregroundActivity)
    }

    @Test fun oldProcessCannotPauseNewProcessActivity() {
        val restarted = first.copy(pid = 789)
        val resumed = state.activityChanged(restarted, true)
        assertSame(resumed, resumed.activityChanged(first, false))
    }

    @Test fun unknownPackageEventsAreIgnored() {
        val unknown = first.copy(packageName = "com.example.uninstalled")
        assertSame(state, state.activityChanged(unknown, true))
        assertSame(state, state.activityChanged(unknown, false))
    }

    @Test fun stopClearsOnlyTheStoppedAppsLifecycleEvidence() {
        val resumed = state.activityChanged(first, true)
        assertEquals(first, resumed.appStopped("com.example.other").foregroundActivity)
        val stopped = resumed.appStopped(first.packageName)
        assertNull(stopped.foregroundActivity)
        assertNull(stopped.applications.first().lastStartedPid)
        assertEquals(456, stopped.applications.last().lastStartedPid)
    }

    @Test fun removalRequiresReadyAndAnInstalledNonServiceTarget() {
        val ready = state.copy(phase = ContainerPhase.READY)
        assertTrue(ready.canRemove(first.packageName))
        assertFalse(ready.canRemove("com.example.missing"))
        assertFalse(ready.copy(phase = ContainerPhase.INSTALLING).canRemove(first.packageName))
        assertFalse(ready.copy(phase = ContainerPhase.REMOVING).canRemove(first.packageName))
        val services = ready.copy(applications = listOf(ContainerApp("com.google.android.gms", "Services", 34)))
        assertFalse(services.canRemove("com.google.android.gms"))
    }

    @Test fun stopClearsPendingLaunchPresentation() {
        val starting = state.copy(applications = state.applications.map { it.copy(starting = true) })
        val stopped = starting.appStopped(first.packageName)
        assertFalse(stopped.applications.first().starting)
        assertTrue(stopped.applications.last().starting)
    }
}
