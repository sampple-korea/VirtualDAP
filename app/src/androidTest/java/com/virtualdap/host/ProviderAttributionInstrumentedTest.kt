package com.virtualdap.host

import android.content.AttributionSource
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.fake.service.context.providers.ContentProviderStub

@RunWith(AndroidJUnit4::class)
class ProviderAttributionInstrumentedTest {
    interface ProviderProbe : IInterface {
        fun query(caller: AttributionSource, selection: String, extras: Bundle): Bundle
        fun call(caller: AttributionSource, authority: String, method: String, arg: String, extras: Bundle): Bundle
    }

    @Test fun providerAttributionPreservesPayloadChainTokenAndRemoteErrors() {
        val host = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(host.packageName, BlackBoxCore.getHostPkg())
        val next = AttributionSource.Builder(12345).setPackageName("com.example.downstream").build()
        val builder = AttributionSource.Builder(12346).setPackageName("com.example.guest")
            .setAttributionTag("fixture-tag").setNext(next)
        AttributionSource.Builder::class.java.getDeclaredMethod("setPid", Int::class.javaPrimitiveType)
            .invoke(builder, 12347)
        AttributionSource.Builder::class.java.getDeclaredMethod("setRenouncedPermissions", Set::class.java)
            .invoke(builder, setOf("android.permission.CAMERA"))
        val original = builder.build()
        // API 34 has no withToken helper. Set a non-default fixture token on its private test
        // object so the adapter still has to preserve it on every supported Android version.
        val state = requireNotNull(black.android.content.BRAttributionSource.get(original).mAttributionSourceState())
        state.javaClass.getDeclaredField("token").apply { isAccessible = true }.set(state, Binder())
        val extras = Bundle().apply { putString("payload", "com.example.guest") }
        val remoteError = SecurityException("fixture provider denied")
        val binder = Binder()
        var queries = 0
        val target = object : ProviderProbe {
            override fun asBinder(): IBinder = binder
            override fun query(caller: AttributionSource, selection: String, extras: Bundle): Bundle {
                assertNotSame(original, caller)
                assertEquals(host.applicationInfo.uid, caller.uid)
                assertEquals(host.packageName, caller.packageName)
                assertEquals("fixture-tag", caller.attributionTag)
                assertEquals(next, caller.next)
                assertEquals("com.example.guest", selection)
                assertEquals("com.example.guest", extras.getString("payload"))
                // A copy must preserve the OS attribution token, not register a fabricated identity.
                val token = AttributionSource::class.java.getDeclaredMethod("getToken")
                assertEquals(token.invoke(original), token.invoke(caller))
                val pid = AttributionSource::class.java.getDeclaredMethod("getPid")
                assertEquals(12347, pid.invoke(caller))
                val renounced = AttributionSource::class.java.getDeclaredMethod("getRenouncedPermissions")
                assertEquals(setOf("android.permission.CAMERA"), renounced.invoke(caller))
                if (android.os.Build.VERSION.SDK_INT >= 35) assertEquals(original.deviceId, caller.deviceId)
                queries++
                return extras
            }
            override fun call(caller: AttributionSource, authority: String, method: String, arg: String, extras: Bundle): Bundle {
                assertEquals(host.packageName, caller.packageName)
                assertEquals("com.example.authority", authority)
                assertEquals("com.example.guest", method)
                assertEquals("unmodified-argument", arg)
                throw remoteError
            }
        }
        val proxy = ContentProviderStub().wrapper(target, "com.example.guest") as ProviderProbe
        assertSame(binder, proxy.asBinder())
        assertSame(extras, proxy.query(original, "com.example.guest", extras))
        assertEquals(1, queries)
        val actual = assertThrows(SecurityException::class.java) {
            proxy.call(original, "com.example.authority", "com.example.guest", "unmodified-argument", extras)
        }
        assertSame(remoteError, actual)
        assertEquals(12346, original.uid)
        assertEquals("com.example.guest", original.packageName)
        assertEquals(12345, next.uid)
        assertEquals("com.example.downstream", next.packageName)
    }
}
