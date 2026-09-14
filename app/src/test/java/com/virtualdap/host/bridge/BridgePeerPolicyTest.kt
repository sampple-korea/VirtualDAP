package com.virtualdap.host.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePeerPolicyTest {
    @Test fun acceptsOnlyTheOwningApplicationUid() {
        val policy = BridgePeerPolicy(10_321)
        assertTrue(policy.isAllowed(10_321))
        for (uid in listOf(-1, 0, 1000, 1041, 10_654, 10_777)) {
            assertFalse("Unexpected bridge access for uid $uid", policy.isAllowed(uid))
        }
    }
}
