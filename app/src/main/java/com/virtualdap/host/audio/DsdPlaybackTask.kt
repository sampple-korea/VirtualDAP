package com.virtualdap.host.audio

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A fully configured output for canonical, interleaved DSD source packets. */
interface DsdPacketOutput : Closeable {
    fun write(interleavedDsd: ByteArray)
    fun setPlaying(playing: Boolean)
    fun finish()
}

sealed interface DsdPlaybackEvent {
    data class Started(
        val format: DsdFormat,
        val sampleCountPerChannel: Long,
        val durationMillis: Long,
    ) : DsdPlaybackEvent

    data class Progress(
        val samplePosition: Long,
        val sampleCountPerChannel: Long,
    ) : DsdPlaybackEvent {
        val fraction: Float = if (sampleCountPerChannel == 0L) 0f
            else (samplePosition.toDouble() / sampleCountPerChannel).coerceIn(0.0, 1.0).toFloat()
    }

    data object Paused : DsdPlaybackEvent
    data object Resumed : DsdPlaybackEvent
    data object Completed : DsdPlaybackEvent
    data object Cancelled : DsdPlaybackEvent
    data class Failed(val error: Throwable) : DsdPlaybackEvent
}

/**
 * Owns one reader and one output on one playback thread. Pause is applied between complete source
 * packets, while cancellation closes the reader and interrupts the owner so a blocked read/drain
 * cannot keep a foreground service alive indefinitely.
 */
class DsdPlaybackTask(
    private val reader: DsdStreamReader,
    private val output: DsdPacketOutput,
    private val packetBytes: Int = 64 * 1024,
    private val onEvent: (DsdPlaybackEvent) -> Unit = {},
) {
    private val control = ReentrantLock()
    private val unpaused = control.newCondition()
    private val started = AtomicBoolean(false)
    @Volatile private var owner: Thread? = null
    @Volatile private var pauseRequested = false
    @Volatile private var cancelRequested = false
    @Volatile private var terminated = false

    init {
        require(packetBytes in reader.format.channelCount..1024 * 1024 &&
            packetBytes / reader.format.channelCount > 0
        ) { "Invalid DSD playback packet size" }
    }

    fun pause() {
        if (!terminated && !cancelRequested) pauseRequested = true
    }

    fun resume() {
        control.withLock {
            pauseRequested = false
            unpaused.signalAll()
        }
    }

    fun cancel() {
        if (terminated) return
        cancelRequested = true
        control.withLock {
            pauseRequested = false
            unpaused.signalAll()
        }
        // DsfReader/DffReader deliberately do not synchronize close() with read(): closing the
        // underlying descriptor is what wakes a thread blocked in a ContentResolver stream.
        runCatching { reader.close() }
        owner?.interrupt()
    }

    /** Runs synchronously and may be invoked exactly once. */
    fun run() {
        check(started.compareAndSet(false, true)) { "A DSD playback task can run only once" }
        owner = Thread.currentThread()
        safeEmit(DsdPlaybackEvent.Started(reader.format, reader.sampleCountPerChannel, reader.durationMillis))
        var terminal: DsdPlaybackEvent
        try {
            while (!cancelRequested && awaitPlaying()) {
                val packet = reader.readInterleaved(packetBytes) ?: break
                check(packet.isNotEmpty() && packet.size % reader.format.channelCount == 0) {
                    "DSD source returned an invalid packet"
                }
                output.write(packet)
                safeEmit(DsdPlaybackEvent.Progress(reader.samplePosition, reader.sampleCountPerChannel))
            }
            if (cancelRequested) {
                terminal = DsdPlaybackEvent.Cancelled
            } else {
                output.finish()
                terminal = DsdPlaybackEvent.Completed
            }
        } catch (failure: Throwable) {
            terminal = if (cancelRequested) DsdPlaybackEvent.Cancelled else DsdPlaybackEvent.Failed(failure)
        }

        var closeFailure: Throwable? = null
        try {
            reader.close()
        } catch (failure: Throwable) {
            closeFailure = failure
        }
        try {
            output.close()
        } catch (failure: Throwable) {
            closeFailure?.addSuppressed(failure) ?: run { closeFailure = failure }
        }
        closeFailure?.let { failure ->
            when (val event = terminal) {
                is DsdPlaybackEvent.Failed -> event.error.addSuppressed(failure)
                DsdPlaybackEvent.Cancelled -> Unit
                else -> terminal = DsdPlaybackEvent.Failed(failure)
            }
        }
        terminated = true
        owner = null
        safeEmit(terminal)
    }

    private fun awaitPlaying(): Boolean {
        if (!pauseRequested) return !cancelRequested
        try {
            output.setPlaying(false)
            safeEmit(DsdPlaybackEvent.Paused)
            control.withLock {
                while (pauseRequested && !cancelRequested) unpaused.await()
            }
            if (cancelRequested) return false
            output.setPlaying(true)
            safeEmit(DsdPlaybackEvent.Resumed)
            return true
        } catch (interrupted: InterruptedException) {
            if (cancelRequested) return false
            Thread.currentThread().interrupt()
            throw interrupted
        }
    }

    /** UI/telemetry callbacks cannot be allowed to corrupt the lossless transport. */
    private fun safeEmit(event: DsdPlaybackEvent) {
        runCatching { onEvent(event) }
    }
}
