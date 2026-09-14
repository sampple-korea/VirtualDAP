package com.virtualdap.host

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualdap.host.audio.DsdOutputMode
import com.virtualdap.host.model.DsdPlaybackPhase
import com.virtualdap.host.service.AudioPipelineService
import com.virtualdap.host.service.PipelineStore
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DsdPlaybackInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val created = mutableListOf<Uri>()

    @After fun cleanUp() {
        context.stopService(android.content.Intent(context, AudioPipelineService::class.java))
        created.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        created.clear()
    }

    @Test fun foregroundServiceStreamsARealDsfThroughTheNativeDecoderAndAudioTrack() {
        val sourceBytesPerChannel = 22_050
        val uri = publish("virtualdap-service-fixture.dsf", dsf(sourceBytesPerChannel))

        AudioPipelineService.playDsd(
            context,
            uri,
            DsdOutputMode.PCM_CONVERSION,
            dopCapabilityConfirmed = false,
            displayName = "service fixture.dsf",
        )

        val result = awaitState { state ->
            state.fileName == "service fixture.dsf" &&
                state.phase in setOf(DsdPlaybackPhase.COMPLETED, DsdPlaybackPhase.ERROR)
        }
        assertEquals(result.lastError, DsdPlaybackPhase.COMPLETED, result.phase)
        assertEquals("service fixture.dsf", result.fileName)
        assertEquals(DsdOutputMode.PCM_CONVERSION, result.mode)
        assertEquals(sourceBytesPerChannel * 8L, result.sampleCountPerChannel)
        assertEquals(result.sampleCountPerChannel, result.samplePosition)
        assertEquals(2, result.format?.channelCount)
        assertEquals(2_822_400, result.format?.sampleRate)
        assertNotNull(result.outputFormat)
        assertNull(result.lastError)
    }

    @Test fun nativeModeWithoutAnExclusiveUsbRouteFailsInsteadOfFallingBackToPcm() {
        val uri = publish("virtualdap-native-guard.dsf", dsf(64))

        val fileName = "virtualdap-native-guard.dsf"
        AudioPipelineService.playDsd(
            context,
            uri,
            DsdOutputMode.NATIVE_DSD,
            dopCapabilityConfirmed = false,
            displayName = fileName,
        )

        val result = awaitState { it.fileName == fileName && it.phase == DsdPlaybackPhase.ERROR }
        assertTrue(result.lastError.orEmpty().contains("Exclusive USB"))
        assertEquals(DsdOutputMode.NATIVE_DSD, result.mode)
        assertNull(result.outputFormat)
    }

    private fun awaitState(
        timeoutMillis: Long = 12_000,
        predicate: (com.virtualdap.host.model.DsdPlaybackSnapshot) -> Boolean,
    ): com.virtualdap.host.model.DsdPlaybackSnapshot {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            val current = PipelineStore.state.value.dsdPlayback
            if (predicate(current)) return current
            SystemClock.sleep(20)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Timed out waiting for DSD playback; last state=${PipelineStore.state.value.dsdPlayback}")
    }

    private fun publish(name: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-dsf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VirtualDAP-tests")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        created += uri
        context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun dsf(audioBytesPerChannel: Int): ByteArray {
        val blockBytes = 4096
        val blocks = (audioBytesPerChannel + blockBytes - 1) / blockBytes
        val payloadBytes = blocks * blockBytes * 2
        val totalBytes = 28 + 52 + 12 + payloadBytes
        return ByteArrayOutputStream().apply {
            write("DSD ".toByteArray())
            writeLe64(28)
            writeLe64(totalBytes.toLong())
            writeLe64(0)
            write("fmt ".toByteArray())
            writeLe64(52)
            writeLe32(1)
            writeLe32(0)
            writeLe32(2)
            writeLe32(2)
            writeLe32(2_822_400)
            writeLe32(1)
            writeLe64(audioBytesPerChannel * 8L)
            writeLe32(blockBytes)
            writeLe32(0)
            write("data".toByteArray())
            writeLe64((12 + payloadBytes).toLong())
            repeat(blocks) { block ->
                repeat(2) { channel ->
                    repeat(blockBytes) { byte ->
                        val position = block * blockBytes + byte
                        write(if (position < audioBytesPerChannel) (position * 31 + channel * 73) and 0xff else 0)
                    }
                }
            }
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLe32(value: Int) = repeat(4) { write(value ushr (it * 8)) }
    private fun ByteArrayOutputStream.writeLe64(value: Long) = repeat(8) { write((value ushr (it * 8)).toInt()) }
}
