package com.virtualdap.host.audio

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DsdPlaybackTaskTest {
    private val format = DsdFormat(2_822_400, 2)

    private class Packets(
        override val format: DsdFormat,
        packets: List<ByteArray>,
    ) : DsdStreamReader {
        private val remaining = ArrayDeque(packets)
        override val sampleCountPerChannel = packets.sumOf { it.size.toLong() / format.channelCount } * 8L
        override val durationMillis = sampleCountPerChannel * 1_000L / format.sampleRate
        override var samplePosition = 0L
        var closed = false
        override fun readInterleaved(maximumBytes: Int): ByteArray? = remaining.removeFirstOrNull()?.also {
            require(it.size <= maximumBytes)
            samplePosition += it.size / format.channelCount * 8L
        }
        override fun close() { closed = true }
    }

    private class RecordingOutput : DsdPacketOutput {
        val bytes = ByteArrayOutputStream()
        val playing = mutableListOf<Boolean>()
        var finishes = 0
        var closed = false
        override fun write(interleavedDsd: ByteArray) { bytes.write(interleavedDsd) }
        override fun setPlaying(playing: Boolean) { this.playing += playing }
        override fun finish() { finishes++ }
        override fun close() { closed = true }
    }

    @Test fun streamsEveryPacketThenFinishesAndClosesExactlyOnce() {
        val source = Packets(format, listOf(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6)))
        val output = RecordingOutput()
        val events = mutableListOf<DsdPlaybackEvent>()

        DsdPlaybackTask(source, output, packetBytes = 4, onEvent = events::add).run()

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), output.bytes.toByteArray())
        assertEquals(1, output.finishes)
        assertTrue(output.closed)
        assertTrue(source.closed)
        assertTrue(events.first() is DsdPlaybackEvent.Started)
        assertEquals(listOf(16L, 24L), events.filterIsInstance<DsdPlaybackEvent.Progress>().map { it.samplePosition })
        assertEquals(DsdPlaybackEvent.Completed, events.last())
    }

    @Test fun pauseAndResumeOnlyTouchTheOutputAtAPacketBoundary() {
        val source = Packets(format, listOf(byteArrayOf(1, 2), byteArrayOf(3, 4)))
        val output = RecordingOutput()
        val events = Collections.synchronizedList(mutableListOf<DsdPlaybackEvent>())
        val paused = CountDownLatch(1)
        lateinit var task: DsdPlaybackTask
        task = DsdPlaybackTask(source, output, packetBytes = 2) { event ->
            events += event
            if (event is DsdPlaybackEvent.Progress && event.samplePosition == 8L) task.pause()
            if (event == DsdPlaybackEvent.Paused) paused.countDown()
        }
        val worker = Thread(task::run).apply { start() }

        assertTrue(paused.await(2, TimeUnit.SECONDS))
        assertArrayEquals(byteArrayOf(1, 2), output.bytes.toByteArray())
        assertEquals(listOf(false), output.playing)
        task.resume()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.bytes.toByteArray())
        assertEquals(listOf(false, true), output.playing)
        assertTrue(events.contains(DsdPlaybackEvent.Resumed))
        assertEquals(DsdPlaybackEvent.Completed, events.last())
    }

    @Test fun cancellationClosesAReaderToUnblockItAndNeverDrainsTheOutput() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val source = object : DsdStreamReader {
            override val format = this@DsdPlaybackTaskTest.format
            override val sampleCountPerChannel = 8L
            override val samplePosition = 0L
            override val durationMillis = 0L
            @Volatile var closed = false
            override fun readInterleaved(maximumBytes: Int): ByteArray? {
                entered.countDown()
                released.await()
                if (closed) throw IOException("closed")
                return byteArrayOf(1, 2)
            }
            override fun close() { closed = true; released.countDown() }
        }
        val output = RecordingOutput()
        val events = Collections.synchronizedList(mutableListOf<DsdPlaybackEvent>())
        val task = DsdPlaybackTask(source, output, onEvent = events::add)
        val worker = Thread(task::run).apply { start() }

        assertTrue(entered.await(2, TimeUnit.SECONDS))
        task.cancel()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertEquals(0, output.finishes)
        assertTrue(output.closed)
        assertEquals(DsdPlaybackEvent.Cancelled, events.last())
    }

    @Test fun outputFailureIsReportedAndBothSidesAreClosed() {
        val source = Packets(format, listOf(byteArrayOf(1, 2)))
        val output = object : DsdPacketOutput {
            var closed = false
            override fun write(interleavedDsd: ByteArray) = throw IOException("DAC removed")
            override fun setPlaying(playing: Boolean) = Unit
            override fun finish() = Unit
            override fun close() { closed = true }
        }
        val events = mutableListOf<DsdPlaybackEvent>()

        DsdPlaybackTask(source, output, onEvent = events::add).run()

        val failure = events.last() as DsdPlaybackEvent.Failed
        assertEquals("DAC removed", failure.error.message)
        assertTrue(source.closed)
        assertTrue(output.closed)
    }
}
