package io.stride.spikes.appstore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AppstoreStartupTest {
    @Test
    fun `check decision stays ordered on the calling thread`() {
        val caller = Thread.currentThread()
        var checkThread: Thread? = null
        var restored = false
        val executor = Executors.newSingleThreadExecutor()

        try {
            AppstoreStartup(executor).initialize(
                shouldCheck = true,
                check = { checkThread = Thread.currentThread() },
                restore = { restored = true },
                onFailure = { throw AssertionError(it) },
            )

            assertTrue(checkThread === caller)
            assertFalse(restored)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `synchronous service start failure is reported instead of escaping launch`() {
        var failure: Exception? = null
        val executor = Executors.newSingleThreadExecutor()

        try {
            AppstoreStartup(executor).initialize(
                shouldCheck = true,
                check = { throw SecurityException("service unavailable") },
                restore = { throw AssertionError("restore must not run") },
                onFailure = { failure = it },
            )

            assertTrue(failure is SecurityException)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `startup work leaves the calling thread before doing expensive work`() {
        val caller = Thread.currentThread()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var worker: Thread? = null
        val executor = Executors.newSingleThreadExecutor()

        try {
            AppstoreStartup(executor).initialize(
                shouldCheck = false,
                check = { throw AssertionError("check must not run") },
                restore = {
                    worker = Thread.currentThread()
                    entered.countDown()
                    release.await()
                    finished.countDown()
                },
                onFailure = { throw AssertionError(it) },
            )

            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(finished.await(50, TimeUnit.MILLISECONDS))
            assertNotEquals(caller, worker)
            release.countDown()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `startup failure is published explicitly`() {
        val failed = CountDownLatch(1)
        var message: String? = null
        val executor = Executors.newSingleThreadExecutor()

        try {
            AppstoreStartup(executor).run(
                work = { error("bad cache") },
                onFailure = {
                    message = it.message
                    failed.countDown()
                },
            )

            assertTrue(failed.await(1, TimeUnit.SECONDS))
            assertTrue(message == "bad cache")
        } finally {
            executor.shutdownNow()
        }
    }
}
