package com.virtualdap.host.container

import org.junit.Assert.*
import org.junit.Test
import top.niunaijun.blackbox.utils.compat.InitializationAttempt

class InitializationAttemptTest {
    @Test fun successfulStartupPublishesBeforeReleasingWaiters() {
        val events = mutableListOf<String>()
        assertTrue(InitializationAttempt.run({ events.add("ready"); true },
            { events.add("rollback") }, { events.add("release") }))
        assertEquals(listOf("ready", "release"), events)
    }

    @Test fun missingProviderRollsBackBeforeReleasingWaiters() {
        val events = mutableListOf<String>()
        assertFalse(InitializationAttempt.run({ false }, { events.add("rollback") }, { events.add("release") }))
        assertEquals(listOf("rollback", "release"), events)
    }

    @Test fun exceptionCannotLeaveRecordOrClosedWaiters() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("startup")
        try {
            InitializationAttempt.run({ throw failure }, { events.add("rollback") }, { events.add("release") })
            fail("Expected real startup failure")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(listOf("rollback", "release"), events)
    }

    @Test fun rollbackFailureStillReleasesWaiters() {
        var released = false
        val failure = IllegalStateException("rollback")
        try {
            InitializationAttempt.run({ false }, { throw failure }, { released = true })
            fail("Expected rollback failure")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertTrue(released)
    }

    @Test fun failedAttemptDoesNotPreventLaterStartup() {
        var published = true
        var releases = 0
        assertFalse(InitializationAttempt.run({ false }, { published = false }, { releases++ }))
        assertFalse(published)
        assertTrue(InitializationAttempt.run({ published = true; true }, { published = false }, { releases++ }))
        assertTrue(published)
        assertEquals(2, releases)
    }
}
