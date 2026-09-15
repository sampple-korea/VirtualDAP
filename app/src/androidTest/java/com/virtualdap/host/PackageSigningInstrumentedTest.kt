package com.virtualdap.host

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.niunaijun.blackbox.core.system.pm.InstalledPackageSigning

@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class PackageSigningInstrumentedTest {
    @Test fun importedArchiveMatchesPlatformSigningMetadataAndQueryFlags() {
        withFixture { file, manager ->
            val mask = PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES
            val original = requireNotNull(manager.getPackageArchiveInfo(file.path, mask))
            for (flags in listOf(0, PackageManager.GET_SIGNATURES, PackageManager.GET_SIGNING_CERTIFICATES, mask)) {
                val actual = PackageInfo().apply { packageName = original.packageName }
                InstalledPackageSigning.populate(manager, file.path, actual, flags)
                if (flags and PackageManager.GET_SIGNATURES != 0) {
                    assertArrayEquals(original.signatures, actual.signatures)
                    assertNotSame(original.signatures, actual.signatures)
                } else assertNull(actual.signatures)
                if (flags and PackageManager.GET_SIGNING_CERTIFICATES != 0) {
                    assertNotNull(actual.signingInfo)
                    assertArrayEquals(original.signingInfo!!.apkContentsSigners, actual.signingInfo!!.apkContentsSigners)
                    assertArrayEquals(original.signingInfo!!.signingCertificateHistory, actual.signingInfo!!.signingCertificateHistory)
                    assertEquals(original.signingInfo!!.hasMultipleSigners(), actual.signingInfo!!.hasMultipleSigners())
                } else assertNull(actual.signingInfo)
            }
        }
    }

    @Test fun missingOrDifferentArchiveCannotBorrowHostSigningIdentity() {
        withFixture { file, manager ->
            val host = InstrumentationRegistry.getInstrumentation().targetContext.packageName
            val target = PackageInfo().apply { packageName = host }
            assertThrows(IllegalStateException::class.java) {
                InstalledPackageSigning.populate(manager, file.path, target, PackageManager.GET_SIGNATURES)
            }
            assertThrows(IllegalStateException::class.java) {
                InstalledPackageSigning.populate(manager, file.path + ".missing", target, PackageManager.GET_SIGNING_CERTIFICATES)
            }
            assertNull(target.signatures)
            assertNull(target.signingInfo)
            // A caller not asking for certificates should not parse the APK at all.
            InstalledPackageSigning.populate(manager, file.path + ".missing", target, 0)
        }
    }

    private fun withFixture(check: (File, PackageManager) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File.createTempFile("signing-metadata-", ".apk", context.cacheDir)
        try {
            instrumentation.context.assets.open("music-fixture.apk").use { input ->
                file.outputStream().use(input::copyTo)
            }
            check(file, context.packageManager)
        } finally { file.delete() }
    }
}
