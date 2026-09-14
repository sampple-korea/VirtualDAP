package com.virtualdap.host

import android.content.Intent
import android.net.Credentials
import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualdap.host.audio.PcmFormat
import com.virtualdap.host.audio.PcmEncoding
import com.virtualdap.host.container.ContainerRuntime
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import top.niunaijun.blackbox.BlackBoxCore
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos

/** Opt-in local test of an unmodified player's public VIEW action, not a hardware-output test. */
internal class ExternalWavEvidence {
    private val expectedPid = AtomicInteger(-1)
    private val nonSilentFrames = AtomicLong()
    private val format = AtomicReference<PcmFormat>()
    private val observedFrames = AtomicInteger()
    private val real = DoubleArray(2)
    private val imaginary = DoubleArray(2)
    private val energy = DoubleArray(2)

    fun observe(peer: Credentials, pcmFormat: PcmFormat, bytes: ByteArray) {
        if (peer.pid != expectedPid.get()) return
        format.set(pcmFormat)
        var frames = 0L
        for (start in bytes.indices step pcmFormat.frameSizeBytes) {
            if ((start until start + pcmFormat.frameSizeBytes).any { bytes[it] != 0.toByte() }) frames++
        }
        nonSilentFrames.addAndGet(frames)
        if (pcmFormat != PcmFormat(48_000, 2, PcmEncoding.PCM_16)) return
        val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        while (samples.remaining() >= 4 && observedFrames.get() < 48_000) {
            val left = samples.short.toDouble()
            val right = samples.short.toDouble()
            val index = observedFrames.get()
            if (index == 0 && left == 0.0 && right == 0.0) continue
            for (channel in 0..1) {
                val sample = if (channel == 0) left else right
                val angle = 2 * PI * (if (channel == 0) 440 else 660) * index / 48_000
                real[channel] += sample * cos(angle)
                imaginary[channel] += sample * sin(angle)
                energy[channel] += sample * sample
            }
            // Atomic publication makes the finished window visible to the assertion thread.
            observedFrames.incrementAndGet()
        }
    }

    fun verifyPlayback(packageName: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = ContainerRuntime.state.value.applications.single { it.packageName == packageName }
        expectedPid.set(requireNotNull(app.lastStartedPid))
        val screenDeadline = SystemClock.elapsedRealtime() + 30_000
        while (ContainerRuntime.state.value.foregroundActivity?.packageName != packageName &&
            SystemClock.elapsedRealtime() < screenDeadline) SystemClock.sleep(100)
        assertEquals("The external app must be resumed before sending media", packageName,
            ContainerRuntime.state.value.foregroundActivity?.packageName)
        val tone = File(context.cacheDir, "external-player-test.wav")
        // This file contains only our generated test signal, never user/library media. The
        // container and host have the same ordinary UID; no storage permission is overridden.
        try {
            tone.writeBytes(wav())
            val launch = requireNotNull(BlackBoxCore.getBPackageManager().getLaunchIntentForPackage(packageName, 0))
            BlackBoxCore.get().startActivity(Intent(Intent.ACTION_VIEW).apply {
                setPackage(packageName)
                component = requireNotNull(launch.component)
                setDataAndType(Uri.fromFile(tone), "audio/wav")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, 0)
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (observedFrames.get() < 48_000 && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100)
            }
            assertTrue("No non-silent PCM from $packageName PID ${expectedPid.get()}; " +
                "complete ordinary first-run setup before this optional test. Last format=${format.get()}",
                observedFrames.get() >= 48_000)
            assertEquals("Generated WAV sample rate", 48_000, format.get().sampleRate)
            assertEquals("Generated WAV channels", 2, format.get().channelCount)
            for (channel in 0..1) {
                val fraction = 2 * (real[channel] * real[channel] + imaginary[channel] * imaginary[channel]) /
                    (48_000 * energy[channel])
                assertTrue("Channel $channel must carry its generated test tone, fraction=$fraction", fraction > 0.95)
            }
            android.util.Log.i("VirtualDAP-Compat", "External WAV PCM captured: package=$packageName, " +
                "pid=${expectedPid.get()}, format=${format.get()}, nonSilentFrames=${nonSilentFrames.get()}, " +
                "left440Hz/right660Hz verified; not DAC output")
        } finally {
            ContainerRuntime.stop(packageName)
            tone.delete()
        }
    }

    private fun wav(): ByteArray {
        val frames = 48_000 * 10
        val buffer = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + frames * 4)
        buffer.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        buffer.putShort(1).putShort(2).putInt(48_000).putInt(48_000 * 4).putShort(4).putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(frames * 4)
        repeat(frames) { index ->
            buffer.putShort((sin(2 * PI * 440 * index / 48_000) * 2048).toInt().toShort())
            buffer.putShort((sin(2 * PI * 660 * index / 48_000) * 2048).toInt().toShort())
        }
        return buffer.array()
    }
}
