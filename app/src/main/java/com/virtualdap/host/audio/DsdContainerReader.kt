package com.virtualdap.host.audio

import java.io.EOFException
import java.io.InputStream
import java.io.PushbackInputStream

/** Detects DSF/DSDIFF from their on-stream signatures; file extensions and MIME labels are untrusted. */
object DsdContainerReader {
    fun open(input: InputStream): DsdStreamReader {
        val buffered = PushbackInputStream(input, 4)
        return try {
            val signature = ByteArray(4)
            var offset = 0
            while (offset < signature.size) {
                val read = buffered.read(signature, offset, signature.size - offset)
                if (read < 0) throw EOFException("Truncated DSD container signature")
                if (read == 0) {
                    val one = buffered.read()
                    if (one < 0) throw EOFException("Truncated DSD container signature")
                    signature[offset++] = one.toByte()
                } else offset += read
            }
            buffered.unread(signature)
            when (String(signature, Charsets.US_ASCII)) {
                "DSD " -> DsfReader.open(buffered)
                "FRM8" -> DffReader.open(buffered)
                else -> throw IllegalArgumentException("The selected file is not DSF or DSDIFF")
            }
        } catch (failure: Throwable) {
            try {
                buffered.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }
}
