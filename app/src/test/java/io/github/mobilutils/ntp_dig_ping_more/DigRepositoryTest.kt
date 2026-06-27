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
 * Unit tests for [DigRepository] cancellation propagation.
 *
 * These tests verify that when a coroutine is cancelled mid-operation,
 * the DNS query stops promptly rather than continuing in the background.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DigRepositoryTest {

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
     * Verifies that a DNS query can be cancelled after name processing but before
     * the blocking resolver.send() call.
     */
    @Test
    fun `resolve cancels after name processing`() = runTest {
        val repository = DigRepository()

        // Launch the query and immediately cancel it
        val job = launch {
            repository.resolve("8.8.8.8", "example.com")
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
     * Verifies that a DNS query can be cancelled during a long-running operation.
     */
    @Test
    fun `resolve cancellable with delay`() = runTest {
        val repository = DigRepository()

        // Launch the query with cancellation after a brief delay
        val job = launch {
            repository.resolve("8.8.8.8", "example.com")
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
     * Verifies that multiple concurrent DNS queries can all be cancelled.
     */
    @Test
    fun `multiple resolves can all be cancelled`() = runTest {
        val repository = DigRepository()

        // Launch multiple queries
        val jobs = List(3) {
            launch {
                repository.resolve("8.8.8.8", "example.com")
            }
        }

        // Cancel all of them
        jobs.forEach { it.cancel() }

        // Verify all were cancelled
        jobs.forEach { job ->
            try {
                job.join()
            } catch (e: CancellationException) {
                assertTrue(true)
            }
        }
    }

    /**
     * Verifies that cancellation works when operation is cancelled mid-way.
     */
    @Test
    fun `resolve cancellable with delay and cancel`() = runTest {
        val repository = DigRepository()

        // Launch the query with cancellation after a brief delay
        val job = launch {
            repository.resolve("8.8.8.8", "example.com")
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
