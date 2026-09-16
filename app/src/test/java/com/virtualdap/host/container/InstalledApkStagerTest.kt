package com.virtualdap.host.container

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstalledApkStagerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun singleApkIsCopiedByteForByteWithoutAnOuterArchive() {
        val bytes = ByteArray(131_073) { (it * 31).toByte() }
        val source = temporary.newFile("installed.apk").apply { writeBytes(bytes) }
        val output = InstalledApkStager.stage(listOf(source), temporary.newFolder(), bytes.size.toLong())
        assertEquals("package.apk", output.name)
        assertNotEquals(source.canonicalFile, output.canonicalFile)
        assertArrayEquals(bytes, output.readBytes())
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun splitApksRetainOrderAndExactBytesWithoutOuterCompression() {
        val base = temporary.newFile("base.apk").apply { writeBytes(ByteArray(65_537) { 42 }) }
        val split = temporary.newFile("config.apk").apply { writeBytes(ByteArray(32_000) { 7 }) }
        val files = listOf(base, split)
        val output = InstalledApkStager.stage(files, temporary.newFolder(), files.sumOf { it.length() })
        ZipFile(output).use { archive ->
            val entries = archive.entries().asSequence().toList()
            assertEquals(listOf("base.apk", "split-1.apk"), entries.map { it.name })
            entries.forEachIndexed { index, entry ->
                assertArrayEquals(files[index].readBytes(), archive.getInputStream(entry).use { it.readBytes() })
                // Deliberately compressible input distinguishes NO_COMPRESSION from default deflation.
                assertTrue(entry.compressedSize >= entry.size)
            }
        }
    }

    @Test fun byteLimitIsAggregateAndFailedPartialOutputsAreRemoved() {
        val first = temporary.newFile("first.apk").apply { writeBytes(ByteArray(65_537)) }
        val second = temporary.newFile("second.apk").apply { writeBytes(byteArrayOf(9)) }
        for (files in listOf(listOf(first), listOf(first, second))) {
            val directory = temporary.newFolder()
            assertThrows(IllegalArgumentException::class.java) {
                InstalledApkStager.stage(files, directory, files.sumOf { it.length() } - 1)
            }
            assertTrue(directory.listFiles()!!.isEmpty())
            assertEquals(65_537, first.length().toInt())
            assertArrayEquals(byteArrayOf(9), second.readBytes())
        }
    }

    @Test fun invalidInputsAndAnExistingDestinationAreNotOverwritten() {
        val source = temporary.newFile("source.apk").apply { writeBytes(byteArrayOf(1)) }
        val directory = temporary.newFolder()
        val existing = File(directory, "package.apk").apply { writeBytes(byteArrayOf(11)) }
        assertThrows(IllegalStateException::class.java) { InstalledApkStager.stage(listOf(source), directory, 100) }
        assertThrows(IllegalArgumentException::class.java) { InstalledApkStager.stage(emptyList(), directory, 100) }
        assertThrows(IllegalArgumentException::class.java) { InstalledApkStager.stage(List(257) { source }, directory, 100) }
        assertThrows(IllegalArgumentException::class.java) { InstalledApkStager.stage(listOf(source), directory, 0) }
        assertThrows(IllegalArgumentException::class.java) {
            InstalledApkStager.stage(listOf(File(directory, "missing.apk")), directory, 100)
        }
        assertArrayEquals(byteArrayOf(11), existing.readBytes())
    }
}
