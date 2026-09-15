package com.virtualdap.host.container

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import top.niunaijun.blackbox.utils.ApkArchiveType

class ApkArchiveTypeTest {
    @Test fun actualApkWithEmbeddedModulesIsNotAnInstallBundle() {
        archive(".apk", "AndroidManifest.xml", "assets/one.apk", "assets/two.apk") {
            assertFalse(ApkArchiveType.isBundle(it))
        }
    }
    @Test fun manifestTakesPrecedenceEvenOverAnApksFilename() {
        archive(".apks", "AndroidManifest.xml", "assets/one.apk", "assets/two.apk") {
            assertFalse(ApkArchiveType.isBundle(it))
        }
    }
    @Test fun renamedOuterArchiveWithOneApkRemainsABundle() {
        archive(".bin", "base.apk") { assertTrue(ApkArchiveType.isBundle(it)) }
    }
    @Test fun archiveWithoutAnyApkIsNotAnInstallBundle() {
        archive(".apks", "readme.txt") { assertFalse(ApkArchiveType.isBundle(it)) }
        assertFalse(ApkArchiveType.isBundle(null))
    }
    private fun archive(suffix: String, vararg entries: String, check: (java.io.File) -> Unit) {
        val file = Files.createTempFile("apk-kind-", suffix).toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { zip.putNextEntry(ZipEntry(it)); zip.write(byteArrayOf(1)); zip.closeEntry() }
            }
            check(file)
        } finally { file.delete() }
    }
}
