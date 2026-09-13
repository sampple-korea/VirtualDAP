package com.virtualdap.host.guest

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class GuestBundleManifest(
    val formatVersion: Int,
    val androidApi: Int,
    val architecture: String,
    val backend: String,
    val displayName: String,
    val buildFingerprint: String,
    val imageFile: String,
    val imageBytes: Long,
    val imageSha256: String,
    val services: GuestServices,
    val attestation: GuestAttestation,
) {
    fun asProperties(): String = buildString {
        appendLine("formatVersion=$formatVersion")
        appendLine("androidApi=$androidApi")
        appendLine("architecture=$architecture")
        appendLine("backend=$backend")
        appendLine("displayName=$displayName")
        appendLine("buildFingerprint=$buildFingerprint")
        appendLine("imageFile=$imageFile")
        appendLine("imageBytes=$imageBytes")
        appendLine("imageSha256=$imageSha256")
        appendLine("services=${services.wireValue}")
        appendLine("attestation=${attestation.wireValue}")
    }
}

enum class GuestServices(val wireValue: String) {
    AOSP("aosp"),
    USER_PROVIDED_GMS("user-provided-gms");

    companion object {
        fun parse(value: String): GuestServices = entries.firstOrNull { it.wireValue == value }
            ?: throw GuestBundleException("Unsupported services value: $value")
    }
}

enum class GuestAttestation(val wireValue: String) {
    NOT_CERTIFIED("not-certified"),
    OEM_CERTIFIED("oem-certified");

    companion object {
        fun parse(value: String): GuestAttestation = entries.firstOrNull { it.wireValue == value }
            ?: throw GuestBundleException("Unsupported attestation value: $value")
    }
}

class GuestBundleException(message: String) : IllegalArgumentException(message)

/**
 * Installs the deliberately small VirtualDAP bundle surface: one manifest and one backend disk.
 * Extraction is streamed, bounded, path-safe, and committed only after byte-count and SHA-256
 * verification. A content hash detects corruption; it does not claim that an image is OEM-signed.
 */
class GuestBundleInstaller(
    private val maxImageBytes: Long = MAX_IMAGE_BYTES,
) {
    fun install(
        input: InputStream,
        installationRoot: File,
        validateManifest: (GuestBundleManifest) -> Unit = {},
    ): GuestBundleManifest {
        installationRoot.mkdirs()
        require(installationRoot.isDirectory) { "Installation root is not a directory" }
        val staging = File(installationRoot, ".staging-${System.nanoTime()}")
        if (!staging.mkdir()) throw GuestBundleException("Could not create the staging directory")

        try {
            val manifest = ZipInputStream(BufferedInputStream(input)).use { zip ->
                val first = zip.nextEntry ?: throw GuestBundleException("Guest bundle is empty")
                if (first.name != MANIFEST_FILE || first.isDirectory) {
                    throw GuestBundleException("$MANIFEST_FILE must be the first bundle entry")
                }
                val manifestText = zip.readBounded(MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
                zip.closeEntry()
                val parsed = parseManifest(manifestText)
                validateManifest(parsed)
                if (parsed.imageBytes > maxImageBytes) {
                    throw GuestBundleException("Guest image exceeds the configured size limit")
                }

                val second = zip.nextEntry ?: throw GuestBundleException("Guest image is missing")
                validateImageEntry(second, parsed)
                val image = File(staging, IMAGE_FILE)
                val digest = MessageDigest.getInstance("SHA-256")
                val copied = copyImage(zip, image, parsed.imageBytes, digest)
                zip.closeEntry()
                if (copied != parsed.imageBytes) {
                    throw GuestBundleException("Guest image size mismatch: expected ${parsed.imageBytes}, got $copied")
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualHash.equals(parsed.imageSha256, ignoreCase = true)) {
                    throw GuestBundleException("Guest image SHA-256 mismatch")
                }
                if (zip.nextEntry != null) {
                    throw GuestBundleException("Unexpected extra entry in guest bundle")
                }
                parsed
            }

            File(staging, MANIFEST_FILE).writeText(manifest.asProperties(), Charsets.UTF_8)
            commit(staging, File(installationRoot, ACTIVE_DIR))
            return manifest
        } catch (error: Exception) {
            staging.deleteRecursively()
            if (error is GuestBundleException) throw error
            throw GuestBundleException(error.message ?: "Could not install guest bundle")
        }
    }

    fun readInstalled(installationRoot: File): GuestBundleManifest? {
        val manifest = File(File(installationRoot, ACTIVE_DIR), MANIFEST_FILE)
        val image = File(File(installationRoot, ACTIVE_DIR), IMAGE_FILE)
        if (!manifest.isFile || !image.isFile) return null
        return parseManifest(manifest.readText(Charsets.UTF_8)).also {
            if (image.length() != it.imageBytes) throw GuestBundleException("Installed guest image size changed")
        }
    }

    fun installedImage(installationRoot: File): File = File(File(installationRoot, ACTIVE_DIR), IMAGE_FILE)

    fun verifyInstalled(installationRoot: File, manifest: GuestBundleManifest) {
        val image = installedImage(installationRoot)
        if (!image.isFile || image.length() != manifest.imageBytes) {
            throw GuestBundleException("Installed guest image size changed")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        image.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        if (actualHash != manifest.imageSha256) {
            throw GuestBundleException("Installed guest image SHA-256 changed")
        }
    }

    private fun validateImageEntry(entry: ZipEntry, manifest: GuestBundleManifest) {
        if (entry.isDirectory || entry.name != manifest.imageFile || entry.name != BUNDLE_IMAGE_FILE) {
            throw GuestBundleException("Expected guest image entry $BUNDLE_IMAGE_FILE")
        }
        if (entry.size >= 0 && entry.size != manifest.imageBytes) {
            throw GuestBundleException("Guest image ZIP metadata does not match the manifest")
        }
    }

    private fun copyImage(
        input: InputStream,
        target: File,
        expectedBytes: Long,
        digest: MessageDigest,
    ): Long {
        var total = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        FileOutputStream(target).use { output ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > expectedBytes || total > maxImageBytes) {
                    throw GuestBundleException("Guest image is larger than declared")
                }
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
            output.flush()
            output.fd.sync()
        }
        return total
    }

    private fun commit(staging: File, active: File) {
        val backup = File(active.parentFile, ".previous")
        backup.deleteRecursively()
        if (active.exists() && !active.renameTo(backup)) {
            throw GuestBundleException("Could not preserve the currently installed guest")
        }
        if (!staging.renameTo(active)) {
            if (backup.exists()) backup.renameTo(active)
            throw GuestBundleException("Could not activate the verified guest")
        }
        backup.deleteRecursively()
    }

    companion object {
        const val MANIFEST_FILE = "manifest.properties"
        const val BUNDLE_IMAGE_FILE = "payload/guest.img"
        const val IMAGE_FILE = "guest.img"
        const val ACTIVE_DIR = "active"
        const val REQUIRED_ANDROID_API = 33
        const val REQUIRED_BACKEND = "virtualdap-platform-v1"
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_IMAGE_BYTES = 24L * 1024 * 1024 * 1024

        fun parseManifest(text: String): GuestBundleManifest {
            if (text.toByteArray(Charsets.UTF_8).size > MAX_MANIFEST_BYTES) {
                throw GuestBundleException("Guest manifest is too large")
            }
            val values = linkedMapOf<String, String>()
            text.lineSequence().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
                val separator = line.indexOf('=')
                if (separator <= 0) throw GuestBundleException("Malformed manifest line ${index + 1}")
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                if (key !in REQUIRED_KEYS) throw GuestBundleException("Unknown manifest key: $key")
                if (value.isEmpty()) throw GuestBundleException("Manifest value is empty: $key")
                if (values.put(key, value) != null) throw GuestBundleException("Duplicate manifest key: $key")
            }
            val missing = REQUIRED_KEYS - values.keys
            if (missing.isNotEmpty()) throw GuestBundleException("Missing manifest keys: ${missing.sorted().joinToString()}")

            val formatVersion = values.int("formatVersion")
            val androidApi = values.int("androidApi")
            val architecture = values.getValue("architecture")
            val backend = values.getValue("backend")
            val displayName = values.getValue("displayName")
            val fingerprint = values.getValue("buildFingerprint")
            val imageFile = values.getValue("imageFile")
            val imageBytes = values.long("imageBytes")
            val hash = values.getValue("imageSha256").lowercase()

            if (formatVersion != 1) throw GuestBundleException("Unsupported bundle format: $formatVersion")
            if (androidApi != REQUIRED_ANDROID_API) {
                throw GuestBundleException("Android API $androidApi is unsupported; API $REQUIRED_ANDROID_API is required")
            }
            if (architecture !in setOf("arm64-v8a", "x86_64")) {
                throw GuestBundleException("Unsupported guest architecture: $architecture")
            }
            if (backend != REQUIRED_BACKEND) throw GuestBundleException("Unsupported runtime backend: $backend")
            if (displayName.length !in 1..80) throw GuestBundleException("Invalid display name")
            if (fingerprint.length !in 1..255) throw GuestBundleException("Invalid build fingerprint")
            if (imageFile != BUNDLE_IMAGE_FILE) throw GuestBundleException("Invalid guest image path")
            if (imageBytes !in 1..MAX_IMAGE_BYTES) throw GuestBundleException("Invalid guest image size")
            if (!hash.matches(Regex("[0-9a-f]{64}"))) throw GuestBundleException("Invalid guest image SHA-256")

            return GuestBundleManifest(
                formatVersion = formatVersion,
                androidApi = androidApi,
                architecture = architecture,
                backend = backend,
                displayName = displayName,
                buildFingerprint = fingerprint,
                imageFile = imageFile,
                imageBytes = imageBytes,
                imageSha256 = hash,
                services = GuestServices.parse(values.getValue("services")),
                attestation = GuestAttestation.parse(values.getValue("attestation")),
            )
        }

        private val REQUIRED_KEYS = setOf(
            "formatVersion",
            "androidApi",
            "architecture",
            "backend",
            "displayName",
            "buildFingerprint",
            "imageFile",
            "imageBytes",
            "imageSha256",
            "services",
            "attestation",
        )

        private fun Map<String, String>.int(key: String): Int = getValue(key).toIntOrNull()
            ?: throw GuestBundleException("Manifest value is not an integer: $key")

        private fun Map<String, String>.long(key: String): Long = getValue(key).toLongOrNull()
            ?: throw GuestBundleException("Manifest value is not an integer: $key")

        private fun InputStream.readBounded(limit: Int): ByteArray {
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                if (result.size() + count > limit) throw GuestBundleException("Guest manifest is too large")
                result.write(buffer, 0, count)
            }
            return result.toByteArray()
        }
    }
}
