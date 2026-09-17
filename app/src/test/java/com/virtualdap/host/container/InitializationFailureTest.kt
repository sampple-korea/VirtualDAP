package com.virtualdap.host.container

import org.junit.Assert.*
import org.junit.Test
import top.niunaijun.blackbox.utils.compat.InitializationFailure

class InitializationFailureTest {
    @Test fun cleanStateDoesNotInventFailureAndFirstCauseRemainsLatched() {
        val state = InitializationFailure()
        state.check()
        val original = SecurityException("fixture application failure")
        state.record(original)
        repeat(3) { assertSame(original, assertThrows(SecurityException::class.java) { state.check() }) }
        state.record(IllegalStateException("later failure"))
        assertSame(original, assertThrows(SecurityException::class.java) { state.check() })
    }

    @Test fun linkageErrorsAndCheckedCausesAreRetained() {
        val error = NoClassDefFoundError("fixture")
        val state = InitializationFailure()
        state.record(error)
        assertSame(error, assertThrows(NoClassDefFoundError::class.java) { state.check() })
        val checked = java.io.IOException("fixture")
        val other = InitializationFailure()
        other.record(checked)
        assertSame(checked, assertThrows(IllegalStateException::class.java) { other.check() }.cause)
        assertThrows(IllegalArgumentException::class.java) { InitializationFailure().record(null) }
    }
}
