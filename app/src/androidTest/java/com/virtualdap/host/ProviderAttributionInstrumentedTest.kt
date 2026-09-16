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
        val original = AttributionSource.Builder(12346).setPackageName("com.example.guest")
            .setAttributionTag("fixture-tag").setNext(next).build()
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
