package com.virtualdap.host.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePeerPolicyTest {
    private val policy = BridgePeerPolicy(hostUid = 10_321)

    @Test
    fun permitsExpectedContainerAndAudioServiceIdentities() {
        assertTrue(policy.isAllowed(0))
        assertTrue(policy.isAllowed(1000))
        assertTrue(policy.isAllowed(1041))
        assertTrue(policy.isAllowed(10_321))
    }

    @Test
    fun rejectsUnrelatedApplicationIdentity() {
        assertFalse(policy.isAllowed(10_654))
        assertFalse(policy.isAllowed(-1))
    }
}
