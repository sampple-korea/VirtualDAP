package com.virtualdap.host.container

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import top.niunaijun.blackbox.utils.compat.SynchronousDispatch

class SynchronousDispatchTest {
    @Test(timeout = 3000) fun workerRunsExactlyOnceAndReturnsCompletedState() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val calls = AtomicInteger()
            val caller = Thread.currentThread()
            SynchronousDispatch.run(executor) {
                assertNotSame(caller, Thread.currentThread())
                calls.incrementAndGet()
            }
            assertEquals(1, calls.get())
        } finally { executor.shutdownNow() }
    }

    @Test(timeout = 3000) fun exceptionUnblocksCallerAndPreservesOriginalCause() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val failure = SecurityException("fixture initialization denied")
            assertSame(failure, assertThrows(SecurityException::class.java) {
                SynchronousDispatch.run(executor) { throw failure }
            })
            // Failure is delivered to the caller; it does not kill the dispatch thread.
            SynchronousDispatch.run(executor) { }
        } finally { executor.shutdownNow() }
    }

    @Test(timeout = 3000) fun linkageErrorsAlsoUnblockCaller() {
        val error = NoClassDefFoundError("fixture dependency")
        assertSame(error, assertThrows(NoClassDefFoundError::class.java) {
            SynchronousDispatch.run(Executor { it.run() }) { throw error }
        })
    }

    @Test(timeout = 3000) fun rejectedSchedulingNeverWaitsOrRunsAction() {
        val failure = RejectedExecutionException("fixture stopped")
        assertSame(failure, assertThrows(RejectedExecutionException::class.java) {
            SynchronousDispatch.run(Executor { throw failure }) { fail("Must not run") }
        })
    }

    @Test(timeout = 3000) fun interruptionRetainsFlagAndCancelsQueuedWork() {
        var queued: Runnable? = null
        val calls = AtomicInteger()
        try {
            Thread.currentThread().interrupt()
            val failure = assertThrows(IllegalStateException::class.java) {
                SynchronousDispatch.run(Executor { queued = it }) { calls.incrementAndGet() }
            }
            assertTrue(failure.cause is InterruptedException)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        requireNotNull(queued).run()
        assertEquals(0, calls.get())
    }
}
