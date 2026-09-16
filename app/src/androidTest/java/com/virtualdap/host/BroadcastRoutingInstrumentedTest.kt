package com.virtualdap.host

import android.content.Intent
import android.os.Binder
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.niunaijun.blackbox.proxy.record.ProxyBroadcastRecord
import top.niunaijun.blackbox.utils.compat.ContainerBroadcastRouting

@RunWith(AndroidJUnit4::class)
class BroadcastRoutingInstrumentedTest {
    private val host = "com.virtualdap.host"

    @Test fun privateBroadcastRetainsRestrictionsAndTargetsOnlyTheActualHostUser() {
        for ((method, count, index) in listOf(Triple("broadcastIntent", 13, 1),
            Triple("broadcastIntentWithFeature", 16, 2))) {
            for (user in listOf(0, -1, -2, -3)) {
                val original = Intent("com.example.PRIVATE").setPackage("com.example.guest")
                    .putExtra("payload", "com.example.guest")
                val args = arrayOfNulls<Any>(count)
                args[0] = Binder()
                if (index == 2) args[1] = "unchanged-attribution-tag"
                args[index] = original
                args[index + 1] = "audio/flac"
                args[index + 2] = Binder()
                args[index + 3] = 37
                args[index + 4] = "unchanged-result"
                args[index + 5] = Bundle().apply { putString("result", "untouched") }
                args[index + 6] = arrayOf("com.example.REQUIRED_PERMISSION")
                if (index == 2) {
                    args[9] = arrayOf("com.example.EXCLUDED_PERMISSION")
                    args[10] = arrayOf("com.example.excluded.package")
                }
                args[count - 5] = 42
                args[count - 4] = Bundle().apply { putBoolean("option", true) }
                args[count - 3] = true
                args[count - 2] = false
                args[count - 1] = user
                val shadow = Intent(original.action).setPackage(host)
                val result = ContainerBroadcastRouting.prepare(method, args, shadow, 0, 10, host)
                assertNotSame(args, result)
                assertEquals(10, result.last())
                assertEquals(user, args.last())
                args.indices.filter { it != index && it != count - 1 }.forEach {
                    assertSame("Unexpected argument change at $it", args[it], result[it])
                }
                val transport = result[index] as Intent
                assertEquals(host, transport.`package`)
                assertNull(transport.component)
                assertFalse(shadow.hasExtra("_B_|_target_"))
                val record = ProxyBroadcastRecord.create(transport)
                assertEquals(0, record.mUserId)
                assertNotSame(original, record.mIntent)
                assertEquals("com.example.guest", record.mIntent.`package`)
                assertEquals("com.example.guest", record.mIntent.getStringExtra("payload"))
                assertSame(args, ContainerBroadcastRouting.prepare(method, args, null, 0, 10, host))
            }
        }
    }

    @Test fun externalOrCrossSpaceBroadcastCannotBorrowThePrivateTransport() {
        val args = arrayOfNulls<Any>(16).apply { this[2] = Intent("fixture"); this[15] = -1 }
        for (shadow in listOf(Intent("fixture"), Intent("fixture").setPackage("com.example.other"),
            Intent("fixture").setPackage(host).setClassName(host, "OtherReceiver"))) {
            assertThrows(SecurityException::class.java) {
                ContainerBroadcastRouting.prepare("broadcastIntentWithFeature", args, shadow, 0, 0, host)
            }
        }
        args[15] = 22
        assertThrows(SecurityException::class.java) {
            ContainerBroadcastRouting.prepare("broadcastIntentWithFeature", args, Intent().setPackage(host), 0, 0, host)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContainerBroadcastRouting.intentIndex("broadcastIntentWithFeature", arrayOfNulls<Any>(17))
        }
    }
}
