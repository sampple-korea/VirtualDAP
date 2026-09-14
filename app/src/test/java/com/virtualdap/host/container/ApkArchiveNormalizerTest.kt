package com.virtualdap.host.container

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkArchiveNormalizerTest {
    @get:Rule val temporary = TemporaryFolder()

    private val profile = BundletoolDeviceProfile(
        sdkVersion = 36,
        supportedAbis = listOf("x86_64"),
        screenDensityDpi = 420,
        textureCompressionFormats = listOf(10, 1),
    )

    @Test fun bundletoolTocSelectsOnlyTheCurrentProcessAbi() {
        val archive = zip(
            "toc.pb" to toc(
                variant(abiTarget(3, 5), module("base", "variants/arm64/base.apk"), module("decoder", "variants/arm64/feature.apk")),
                variant(abiTarget(5, 3), module("base", "variants/x86_64/base.apk"), module("decoder", "variants/x86_64/feature.apk")),
            ),
            "variants/arm64/base.apk" to "arm base".toByteArray(),
            "variants/arm64/feature.apk" to "arm feature".toByteArray(),
            "variants/x86_64/base.apk" to "x64 base".toByteArray(),
            "variants/x86_64/feature.apk" to "x64 feature".toByteArray(),
        )
        val output = temporary.newFile("selected.apks")

        assertTrue(ApkArchiveNormalizer.normalizeIfArchive(archive, output, profile, 1_000_000))

        ZipFile(output).use { selected ->
            val entries = selected.entries().asSequence().filterNot { it.isDirectory }.toList()
            assertEquals(listOf("part-001.apk", "part-002.apk"), entries.map { it.name })
            assertEquals(
                listOf("x64 base", "x64 feature"),
                entries.map { selected.getInputStream(it).use { input -> input.readBytes().decodeToString() } },
            )
        }
    }

    @Test fun bundletoolDensityUsesAndroidsScaleDownPreference() {
        val archive = zip(
            "toc.pb" to toc(
                variant(densityTarget(320, 480), module("base", "density/320.apk")),
                variant(densityTarget(480, 320), module("base", "density/480.apk")),
            ),
            "density/320.apk" to "320".toByteArray(),
            "density/480.apk" to "480".toByteArray(),
        )
        val output = temporary.newFile("density.apks")

        ApkArchiveNormalizer.normalizeIfArchive(archive, output, profile, 1_000_000)

        ZipFile(output).use { selected ->
            val entry = selected.entries().asSequence().single()
            assertEquals("480", selected.getInputStream(entry).use { it.readBytes().decodeToString() })
        }
    }

    @Test fun bundletoolSdkAlternativesChooseTheHighestCompatibleMinimum() {
        val archive = zip(
            "toc.pb" to toc(
                variant(sdkTarget(21, 33), module("base", "sdk/21.apk")),
                variant(sdkTarget(33, 21), module("base", "sdk/33.apk")),
            ),
            "sdk/21.apk" to "sdk21".toByteArray(),
            "sdk/33.apk" to "sdk33".toByteArray(),
        )
        val output = temporary.newFile("sdk.apks")

        ApkArchiveNormalizer.normalizeIfArchive(archive, output, profile, 1_000_000)

        ZipFile(output).use { selected ->
            val entry = selected.entries().asSequence().single()
            assertEquals("sdk33", selected.getInputStream(entry).use { it.readBytes().decodeToString() })
        }
    }

    @Test fun ambiguousOrFutureTargetingIsRejected() {
        val ambiguous = zip(
            "toc.pb" to toc(
                variant(byteArrayOf(), module("base", "one.apk")),
                variant(byteArrayOf(), module("base", "two.apk")),
            ),
            "one.apk" to byteArrayOf(1),
            "two.apk" to byteArrayOf(2),
        )
        val ambiguousError = assertThrows(IllegalArgumentException::class.java) {
            ApkArchiveNormalizer.normalizeIfArchive(
                ambiguous, temporary.newFile("ambiguous.apks"), profile, 1_000_000,
            )
        }
        assertTrue(ambiguousError.message.orEmpty().contains("multiple variants"))

        val futureTarget = messageField(99, byteArrayOf())
        val future = zip(
            "toc.pb" to toc(variant(futureTarget, module("base", "future.apk"))),
            "future.apk" to byteArrayOf(3),
        )
        val futureError = assertThrows(IllegalArgumentException::class.java) {
            ApkArchiveNormalizer.normalizeIfArchive(
                future, temporary.newFile("future.apks"), profile, 1_000_000,
            )
        }
        assertTrue(futureError.message.orEmpty().contains("newer unsupported"))
    }

    @Test fun genericXapkKeepsNestedApksAndIgnoresExpansionFiles() {
        val archive = zip(
            "manifest.json" to "{}".toByteArray(),
            "apk/base.apk" to "base".toByteArray(),
            "apk/config.en.apk" to "language".toByteArray(),
            "Android/obb/example/main.obb" to ByteArray(64),
        )
        val output = temporary.newFile("generic.apks")

        assertTrue(ApkArchiveNormalizer.normalizeIfArchive(archive, output, profile, 1_000_000))

        ZipFile(output).use { selected ->
            assertEquals(2, selected.entries().asSequence().count())
        }
    }

    @Test fun ordinaryApkIsNotRepacked() {
        val apk = zip("AndroidManifest.xml" to byteArrayOf(1, 2, 3))
        val output = File(temporary.root, "unused.apks")

        assertFalse(ApkArchiveNormalizer.normalizeIfArchive(apk, output, profile, 1_000_000))
        assertFalse(output.exists())
    }

    @Test fun unsafePathsAndMalformedTablesAreRejected() {
        val unsafe = zip("../base.apk" to byteArrayOf(1))
        val unsafeError = assertThrows(IllegalArgumentException::class.java) {
            ApkArchiveNormalizer.normalizeIfArchive(
                unsafe, temporary.newFile("unsafe.apks"), profile, 1_000_000,
            )
        }
        assertTrue(unsafeError.message.orEmpty().contains("unsafe"))

        val malformed = zip(
            "toc.pb" to byteArrayOf(0x80.toByte()),
            "base.apk" to byteArrayOf(1),
        )
        assertThrows(Exception::class.java) {
            ApkArchiveNormalizer.normalizeIfArchive(
                malformed, temporary.newFile("malformed.apks"), profile, 1_000_000,
            )
        }

        val jsonOnly = zip(
            "toc.json" to "{}".toByteArray(),
            "base.apk" to byteArrayOf(1),
        )
        val jsonError = assertThrows(IllegalArgumentException::class.java) {
            ApkArchiveNormalizer.normalizeIfArchive(
                jsonOnly, temporary.newFile("json-only.apks"), profile, 1_000_000,
            )
        }
        assertTrue(jsonError.message.orEmpty().contains("JSON-only"))
    }

    @Test fun missingSelectedEntriesAndExpandedSizeOverflowAreRejected() {
        val missing = zip(
            "toc.pb" to toc(variant(byteArrayOf(), module("base", "missing.apk"))),
            "unrelated.apk" to byteArrayOf(1),
        )
        val missingError = assertThrows(IllegalArgumentException::class.java) {
            ApkArchiveNormalizer.normalizeIfArchive(
                missing, temporary.newFile("missing.apks"), profile, 1_000_000,
            )
        }
        assertTrue(missingError.message.orEmpty().contains("missing from the archive"))

        val oversized = zip("base.apk" to byteArrayOf(1, 2, 3, 4))
        val sizeError = assertThrows(IllegalArgumentException::class.java) {
            ApkArchiveNormalizer.normalizeIfArchive(
                oversized, temporary.newFile("oversized.apks"), profile, 3,
            )
        }
        assertTrue(sizeError.message.orEmpty().contains("import limit"))
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val file = temporary.newFile("archive-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { output ->
            for ((name, bytes) in entries) {
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return file
    }

    private fun toc(vararg variants: ByteArray): ByteArray = bytes(
        *variants.map { messageField(1, it) }.toTypedArray(),
        stringField(4, "com.virtualdap.fixture.music"),
    )

    private fun variant(targeting: ByteArray, vararg modules: ByteArray): ByteArray = bytes(
        messageField(1, targeting),
        *modules.map { messageField(2, it) }.toTypedArray(),
    )

    private fun module(name: String, vararg paths: String): ByteArray {
        val metadata = stringField(1, name)
        return bytes(
            messageField(1, metadata),
            *paths.map { path ->
                messageField(2, bytes(stringField(2, path), messageField(3, byteArrayOf())))
            }.toTypedArray(),
        )
    }

    private fun abiTarget(value: Int, alternative: Int): ByteArray = messageField(
        2,
        bytes(
            messageField(1, varintField(1, value.toLong())),
            messageField(2, varintField(1, alternative.toLong())),
        ),
    )

    private fun densityTarget(value: Int, alternative: Int): ByteArray = messageField(
        3,
        bytes(
            messageField(1, varintField(2, value.toLong())),
            messageField(2, varintField(2, alternative.toLong())),
        ),
    )

    private fun sdkTarget(value: Int, alternative: Int): ByteArray = messageField(
        1,
        bytes(
            messageField(1, sdkVersion(value)),
            messageField(2, sdkVersion(alternative)),
        ),
    )

    private fun sdkVersion(minimum: Int): ByteArray = messageField(1, varintField(1, minimum.toLong()))

    private fun stringField(number: Int, value: String): ByteArray =
        messageField(number, value.toByteArray(Charsets.UTF_8))

    private fun messageField(number: Int, value: ByteArray): ByteArray = bytes(
        varint((number.toLong() shl 3) or 2),
        varint(value.size.toLong()),
        value,
    )

    private fun varintField(number: Int, value: Long): ByteArray = bytes(
        varint(number.toLong() shl 3),
        varint(value),
    )

    private fun varint(input: Long): ByteArray {
        var value = input
        val output = ByteArrayOutputStream()
        while (value and 0x7f.inv().toLong() != 0L) {
            output.write(((value and 0x7f) or 0x80).toInt())
            value = value ushr 7
        }
        output.write(value.toInt())
        return output.toByteArray()
    }

    private fun bytes(vararg parts: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        parts.forEach(output::writeBytes)
        return output.toByteArray()
    }
}
