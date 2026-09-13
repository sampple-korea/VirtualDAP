package com.virtualdap.host.guest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GuestBundleInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installsVerifiedAndroid13Image() {
        val image = ByteArray(4096) { (it * 31).toByte() }
        val root = temporaryFolder.newFolder("guest")

        val manifest = GuestBundleInstaller(maxImageBytes = 8192).install(
            ByteArrayInputStream(bundle(image)),
            root,
        )

        assertEquals(33, manifest.androidApi)
        assertEquals(GuestServices.USER_PROVIDED_GMS, manifest.services)
        assertArrayEquals(image, GuestBundleInstaller().installedImage(root).readBytes())
        assertEquals(manifest, GuestBundleInstaller().readInstalled(root))
        GuestBundleInstaller().verifyInstalled(root, manifest)
    }

    @Test
    fun detectsInstalledImageTamperingBeforeLaunch() {
        val root = temporaryFolder.newFolder("tampered")
        val installer = GuestBundleInstaller(maxImageBytes = 8192)
        val manifest = installer.install(ByteArrayInputStream(bundle(byteArrayOf(1, 2, 3))), root)
        installer.installedImage(root).writeBytes(byteArrayOf(3, 2, 1))

        val error = runCatching { installer.verifyInstalled(root, manifest) }.exceptionOrNull()

        assertTrue(error?.message?.contains("SHA-256 changed") == true)
    }

    @Test
    fun rejectsHashMismatchWithoutReplacingActiveImage() {
        val first = byteArrayOf(1, 2, 3)
        val root = temporaryFolder.newFolder("guest")
        val installer = GuestBundleInstaller(maxImageBytes = 8192)
        installer.install(ByteArrayInputStream(bundle(first)), root)

        val broken = bundle(byteArrayOf(8, 9), hashOverride = "0".repeat(64))
        val error = runCatching { installer.install(ByteArrayInputStream(broken), root) }.exceptionOrNull()

        assertTrue(error is GuestBundleException)
        assertArrayEquals(first, installer.installedImage(root).readBytes())
    }

    @Test
    fun rejectsWrongAndroidVersion() {
        val error = runCatching {
            GuestBundleInstaller(maxImageBytes = 8192).install(
                ByteArrayInputStream(bundle(byteArrayOf(1), api = 34)),
                temporaryFolder.newFolder("wrong-api"),
            )
        }.exceptionOrNull()

        assertTrue(error?.message?.contains("API 33 is required") == true)
    }

    @Test
    fun rejectsUnexpectedArchiveEntries() {
        val image = byteArrayOf(1, 2, 3)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("../manifest.properties"))
            zip.write(manifest(image).toByteArray())
            zip.closeEntry()
        }

        val error = runCatching {
            GuestBundleInstaller(maxImageBytes = 8192).install(
                ByteArrayInputStream(output.toByteArray()),
                temporaryFolder.newFolder("unsafe"),
            )
        }.exceptionOrNull()

        assertTrue(error is GuestBundleException)
    }

    private fun bundle(image: ByteArray, api: Int = 33, hashOverride: String? = null): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(GuestBundleInstaller.MANIFEST_FILE))
            zip.write(manifest(image, api, hashOverride).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(GuestBundleInstaller.BUNDLE_IMAGE_FILE))
            zip.write(image)
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun manifest(image: ByteArray, api: Int = 33, hashOverride: String? = null): String {
        val hash = hashOverride ?: MessageDigest.getInstance("SHA-256").digest(image)
            .joinToString("") { "%02x".format(it) }
        return """
            formatVersion=1
            androidApi=$api
            architecture=arm64-v8a
            backend=virtualdap-platform-v1
            displayName=VirtualDAP Android 13
            buildFingerprint=virtualdap/aosp_arm64/virtualdap:13/TQ3A.230901.001/test:userdebug/test-keys
            imageFile=payload/guest.img
            imageBytes=${image.size}
            imageSha256=$hash
            services=user-provided-gms
            attestation=not-certified
        """.trimIndent()
    }
}
