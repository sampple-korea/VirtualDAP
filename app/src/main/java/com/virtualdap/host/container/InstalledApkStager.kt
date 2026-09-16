package com.virtualdap.host.container

import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Copies installed APK code into a fresh private staging directory without recompressing it. */
internal object InstalledApkStager {
    fun stage(files: List<File>, directory: File, maximumBytes: Long): File {
        require(files.size in 1..256) { "Installed APK set has an invalid APK count" }
        require(maximumBytes > 0) { "APK import limit must be positive" }
        require(files.all { it.isFile }) { "Installed APK code is no longer available" }
        val single = files.size == 1
        val output = File(directory, if (single) "package.apk" else "installed-app.apks")
        check(output.createNewFile()) { "APK staging destination already exists" }
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        fun copy(inputFile: File, destination: java.io.OutputStream) {
            inputFile.inputStream().use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(count.toLong() <= maximumBytes - total) { "Installed APK set exceeds the import limit" }
                    total += count
                    destination.write(buffer, 0, count)
                }
            }
        }
        try {
            FileOutputStream(output).use { stream ->
                if (single) {
                    copy(files.single(), stream)
                    stream.fd.sync()
                } else {
                    ZipOutputStream(stream).use { zip ->
                        // APKs already contain compressed entries; outer deflation wastes time/CPU.
                        zip.setLevel(Deflater.NO_COMPRESSION)
                        files.forEachIndexed { index, file ->
                            zip.putNextEntry(ZipEntry(if (index == 0) "base.apk" else "split-$index.apk"))
                            copy(file, zip)
                            zip.closeEntry()
                        }
                        zip.finish()
                        stream.fd.sync()
                    }
                }
            }
            return output
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }
}
