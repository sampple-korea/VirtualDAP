package com.virtualdap.host.container

import org.junit.Assert.*
import org.junit.Test
import top.niunaijun.blackbox.utils.compat.ProviderProcessPolicy.shouldInitialize

class ProviderProcessPolicyTest {
    @Test fun mainProvidersStayInMainProcess() {
        assertTrue(shouldInitialize("com.example.app", "com.example.app", false))
        for (process in listOf("com.example.app:background", "com.example.app:quick_launch", "com.other.app")) {
            assertFalse(process, shouldInitialize(process, "com.example.app", false))
        }
    }

    @Test fun privateProvidersStayInTheirDeclaredProcess() {
        val processes = listOf("com.example.app", "com.example.app:one", "com.example.app:two")
        for (current in processes) for (declared in processes) {
            assertEquals("$current / $declared", current == declared, shouldInitialize(current, declared, false))
        }
    }

    @Test fun explicitMultiprocessDeclarationIsPreserved() {
        assertTrue(shouldInitialize("com.example.app", "com.example.app:provider", true))
        assertTrue(shouldInitialize("com.example.app:worker", "com.example.app", true))
    }

    @Test fun missingMetadataNeverInitializesAccidentally() {
        for (invalid in arrayOf(null, "")) for (multi in listOf(false, true)) {
            assertFalse(shouldInitialize(invalid, "com.example.app", multi))
            assertFalse(shouldInitialize("com.example.app", invalid, multi))
            assertFalse(shouldInitialize(invalid, invalid, multi))
        }
    }
}
