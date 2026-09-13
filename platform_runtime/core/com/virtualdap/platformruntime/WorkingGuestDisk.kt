package com.virtualdap.platformruntime

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.Files

/**
 * Keeps one mutable disk per verified base image. A guest legitimately changes its disk when it
 * installs apps or saves login state; comparing that working disk with the base hash would erase
 * those changes on every subsequent boot.
 *
 * The caller owns [input], serializes access, and provides a private, app-owned [root].
 */
class WorkingGuestDisk(private val root: File) {
    fun prepare(
        input: InputStream,
        imageBytes: Long,
        baseSha256: String,
        availableBytes: () -> Long = { root.usableSpace },
    ): File {
        require(imageBytes in 1..MAX_IMAGE_BYTES) { "Invalid guest disk size" }
        require(baseSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid base image hash" }
        check(root.isDirectory || root.mkdirs()) { "Could not create the guest disk directory" }
        val installed = File(root, baseSha256)
        val disk = File(installed, DISK_NAME)
        val provenance = File(installed, PROVENANCE_NAME)
        if (installed.exists()) {
            // Fail with the existing data intact if a disk is truncated or its provenance is lost.
            // Never turn a verification failure into an implicit factory reset.
            check(disk.isFile && disk.length() == imageBytes) { "Saved guest disk size changed" }
            check(provenance.isFile && provenance.length() <= 128 &&
                provenance.readText(Charsets.UTF_8).trim() == baseSha256
            ) { "Saved guest disk provenance is invalid" }
            return disk
        }
        check(availableBytes() >= imageBytes + MINIMUM_FREE_BYTES) {
            "Not enough storage for the writable guest disk"
        }
        val staging = Files.createTempDirectory(root.toPath(), ".import-").toFile()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            FileOutputStream(File(staging, DISK_NAME)).use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    copied += count
                    require(copied <= imageBytes) { "Guest disk is larger than declared" }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                require(copied == imageBytes) { "Guest disk byte count changed" }
                val expected = baseSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                require(MessageDigest.isEqual(digest.digest(), expected)) {
                    "Guest disk SHA-256 changed across the Binder boundary"
                }
                output.fd.sync()
            }
            FileOutputStream(File(staging, PROVENANCE_NAME)).use { output ->
                output.write("$baseSha256\n".toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            check(!installed.exists() && staging.renameTo(installed)) {
                "Could not activate the writable guest disk"
            }
            return disk
        } finally {
            // This unique staging directory contains only the uncommitted copy made above.
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    companion object {
        private const val DISK_NAME = "guest.img"
        private const val PROVENANCE_NAME = "base.sha256"
        private const val MAX_IMAGE_BYTES = 24L * 1024 * 1024 * 1024
        private const val MINIMUM_FREE_BYTES = 256L * 1024 * 1024
    }
}
