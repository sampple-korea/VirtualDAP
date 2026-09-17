package com.virtualdap.host.container

import org.junit.Assert.*
import org.junit.Test
import top.niunaijun.blackbox.utils.compat.WifiCallerAttribution

class WifiCallerAttributionTest {
    interface WifiProbe {
        fun getConnectionInfo(caller: String?, feature: String?): Any?
        fun unrelated(caller: String?, feature: String?): Any?
        fun getConnectionInfo(caller: String?): Any?
    }

    @Test fun onlyExactCallerFieldChangesAndResultsRemainUntouched() {
        val result = Any()
        val seen = mutableListOf<Pair<String?, String?>>()
        val target = object : WifiProbe {
            override fun getConnectionInfo(caller: String?, feature: String?): Any {
                seen += caller to feature
                return result
            }
            override fun unrelated(caller: String?, feature: String?): Any {
                seen += caller to feature
                return result
            }
            override fun getConnectionInfo(caller: String?): Any {
                seen += caller to null
                return result
            }
        }
        val method = WifiProbe::class.java.getMethod("getConnectionInfo", String::class.java, String::class.java)
        val original = arrayOf<Any?>("guest", "guest")
        assertSame(result, WifiCallerAttribution.invoke(target, method, original, "guest", "host"))
        assertEquals("host" to "guest", seen.last())
        assertArrayEquals(arrayOf<Any?>("guest", "guest"), original)
        for (caller in arrayOf("other", "host", null)) {
            assertSame(result, WifiCallerAttribution.invoke(target, method, arrayOf(caller, null), "guest", "host"))
            assertEquals(caller to null, seen.last())
        }
        for (invalidGuest in arrayOf(null, "")) {
            WifiCallerAttribution.invoke(target, method, original, invalidGuest, "host")
            assertEquals("guest" to "guest", seen.last())
        }
        val unrelated = WifiProbe::class.java.getMethod("unrelated", String::class.java, String::class.java)
        for (invalidHost in arrayOf(null, "")) {
            WifiCallerAttribution.invoke(target, method, original, "guest", invalidHost)
            assertEquals("guest" to "guest", seen.last())
        }
        WifiCallerAttribution.invoke(target, unrelated, original, "guest", "host")
        assertEquals("guest" to "guest", seen.last())
        val unknownLayout = WifiProbe::class.java.getMethod("getConnectionInfo", String::class.java)
        WifiCallerAttribution.invoke(target, unknownLayout, arrayOf("guest"), "guest", "host")
        assertEquals("guest" to null, seen.last())
    }

    @Test fun nullConnectionAndRemoteDenialAreNotFabricatedSuccess() {
        val denial = SecurityException("fixture Wi-Fi access denied")
        val target = object : WifiProbe {
            override fun getConnectionInfo(caller: String?, feature: String?): Any? {
                if (feature == "deny") throw denial
                return null
            }
            override fun unrelated(caller: String?, feature: String?): Any? = null
            override fun getConnectionInfo(caller: String?): Any? = null
        }
        val method = WifiProbe::class.java.getMethod("getConnectionInfo", String::class.java, String::class.java)
        assertNull(WifiCallerAttribution.invoke(target, method, arrayOf("guest", null), "guest", "host"))
        assertSame(denial, assertThrows(SecurityException::class.java) {
            WifiCallerAttribution.invoke(target, method, arrayOf("guest", "deny"), "guest", "host")
        })
    }
}
