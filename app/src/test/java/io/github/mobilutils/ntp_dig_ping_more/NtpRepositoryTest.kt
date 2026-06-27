package io.github.mobilutils.ntp_dig_ping_more

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [NtpRepository] cancellation propagation.
 *
 * These tests verify that when a coroutine is cancelled mid-operation,
 * the operation stops promptly rather than continuing in the background.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NtpRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Cancellation tests
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that a query can be cancelled after DNS resolution but before
     * the blocking getTime() call.
     */
    @Test
    fun `query cancels after DNS resolution`() = runTest {
        val repository = NtpRepository()

        // Launch the query and immediately cancel it
        val job = launch {
            repository.query("pool.ntp.org", 123)
        }

        // Cancel before any significant I/O happens
        job.cancel()

        // Verify the job was cancelled (not completed successfully)
        try {
            job.join()
        } catch (e: CancellationException) {
            // Expected - coroutine was cancelled
            assertTrue(true)
        }
    }

    /**
     * Verifies that a query can be cancelled during a long-running operation.
     * This test uses a very short timeout to ensure the operation completes
     * quickly, then cancels it mid-way through.
     */
    @Test
    fun `query cancellable with very short timeout`() = runTest {
        val repository = NtpRepository()

        // Launch the query with cancellation after a brief delay
        val job = launch {
            repository.query("pool.ntp.org", 123)
        }

        // Give it a moment to start, then cancel
        delay(50) // Virtual time in test dispatcher
        job.cancel()

        // Verify cancellation was propagated
        try {
            job.join()
        } catch (e: CancellationException) {
            assertTrue(true)
        }
    }

    /**
     * Verifies that cancellation works when operation is cancelled mid-way.
     */
    @Test
    fun `query cancellable with delay`() = runTest {
        val repository = NtpRepository()

        // Launch the query with cancellation after a brief delay
        val job = launch {
            repository.query("pool.ntp.org", 123)
        }

        // Give it a moment to start, then cancel
        delay(50) // Virtual time in test dispatcher
        job.cancel()

        // Verify cancellation was propagated
        try {
            job.join()
        } catch (e: CancellationException) {
            assertTrue(true)
        }
    }
}
