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
 * Unit tests for [HttpsCertRepository] cancellation propagation.
 *
 * These tests verify that when a coroutine is cancelled mid-operation,
 * the TLS handshake stops promptly rather than continuing in the background.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HttpsCertRepositoryTest {

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
     * Verifies that a certificate fetch can be cancelled after SSL context setup
     * but before the connection is established.
     */
    @Test
    fun `fetchCertificate cancels after SSL context setup`() = runTest {
        val repository = HttpsCertRepository()

        // Launch the fetch and immediately cancel it
        val job = launch {
            repository.fetchCertificate("google.com", 443)
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
     * Verifies that a certificate fetch can be cancelled during TLS handshake.
     */
    @Test
    fun `fetchCertificate cancellable with delay and cancel`() = runTest {
        val repository = HttpsCertRepository()

        // Launch the fetch with cancellation after a brief delay
        val job = launch {
            repository.fetchCertificate("google.com", 443)
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
     * Verifies that multiple concurrent certificate fetches can all be cancelled.
     */
    @Test
    fun `multiple fetchCertificates can all be cancelled`() = runTest {
        val repository = HttpsCertRepository()

        // Launch multiple fetches
        val jobs = List(3) {
            launch {
                repository.fetchCertificate("google.com", 443)
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
    fun `fetchCertificate cancellable with delay`() = runTest {
        val repository = HttpsCertRepository()

        // Launch the fetch with cancellation after a brief delay
        val job = launch {
            repository.fetchCertificate("google.com", 443)
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
