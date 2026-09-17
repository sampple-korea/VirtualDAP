package com.virtualdap.host.container

import org.junit.Assert.*
import org.junit.Test
import top.niunaijun.blackbox.utils.compat.ServiceBindingArguments

class ServiceBindingArgumentsTest {
    private fun args(flags: Any = 0x180000001L) = arrayOf<Any?>(
        Any(), Any(), Any(), "guest", Any(), flags, "renderer-guest", "guest", 0)

    @Test fun externalRendererRetainsInstanceFlagsPayloadAndCallback() {
        for (method in listOf("bindServiceInstance", "bindIsolatedService")) {
            val original = args()
            val before = original.clone()
            val forwarded = ServiceBindingArguments.prepare(method, original, false, "guest", "host")
            assertArrayEquals(before, original)
            for (index in original.indices) {
                if (index == 7) assertEquals("host", forwarded[index])
                else assertSame(original[index], forwarded[index])
            }
        }
    }

    @Test fun containerStubAloneClearsInstanceAndExternalFlagWithoutTruncatingLongFlags() {
        val original = args()
        val forwarded = ServiceBindingArguments.prepare("bindServiceInstance", original, true, "guest", "host")
        assertNull(forwarded[6])
        assertEquals(0x100000001L, forwarded[5])
        assertEquals("renderer-guest", original[6])
        assertEquals(0x180000001L, original[5])
        val ints = ServiceBindingArguments.prepare("bindIsolatedService", args(0x80000001.toInt()), true, "guest", "host")
        assertEquals(1, ints[5])
        assertTrue(ints[5] is Int)
    }

    @Test fun ordinaryBindingsRetainFlagsAndForeignCallerIsNotRewritten() {
        val ordinary = args().filterIndexed { index, _ -> index != 6 }.toTypedArray()
        val forwarded = ServiceBindingArguments.prepare("bindService", ordinary, false, "guest", "host")
        assertEquals("host", forwarded[6])
        assertEquals(ordinary[5], forwarded[5])
        for (caller in arrayOf("foreign", null)) {
            ordinary[6] = caller
            assertEquals(caller, ServiceBindingArguments.prepare("bindService", ordinary, false, "guest", "host")[6])
        }
    }

    @Test fun unknownOrMalformedLayoutsAreNotGuessed() {
        val original = args()
        assertSame(original, ServiceBindingArguments.prepare("unknown", original, true, "guest", "host"))
        assertEquals(-1, ServiceBindingArguments.callerIndex("bindService", original))
        original[5] = "not flags"
        assertSame(original, ServiceBindingArguments.prepare("bindServiceInstance", original, true, "guest", "host"))
        assertEquals(-1, ServiceBindingArguments.callerIndex("bindService", null))
    }
}
