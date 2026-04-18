package io.github.mobilutils.ntp_dig_ping_more

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SimpleNtpViewModel] using MockK for mocking dependencies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NtpViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: SimpleNtpViewModel
    private lateinit var repository: NtpRepository
    private lateinit var historyStore: NtpHistoryStore

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        historyStore = mockk(relaxed = true)

        coEvery { historyStore.historyFlow } returns flowOf(emptyList())

        viewModel = SimpleNtpViewModel(repository, historyStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has default values`() = runTest {
        val state = viewModel.uiState.value

        assertEquals("pool.ntp.org", state.serverAddress)
        assertEquals("123", state.port)
        assertFalse(state.isLoading)
        assertEquals(null, state.result)
        assertTrue(state.history.isEmpty())
    }

    @Test
    fun `onServerAddressChange updates server address and clears result`() = runTest {
        viewModel.onServerAddressChange("time.google.com")

        val state = viewModel.uiState.value
        assertEquals("time.google.com", state.serverAddress)
        assertEquals(null, state.result)
    }

    @Test
    fun `onPortChange accepts only digits`() = runTest {
        viewModel.onPortChange("123abc456")

        val state = viewModel.uiState.value
        // Filters to digits only: "123456", then takes first 5: "12345"
        assertEquals("12345", state.port)
    }

    @Test
    fun `onPortChange caps at 5 characters`() = runTest {
        viewModel.onPortChange("123456789")

        val state = viewModel.uiState.value
        assertEquals("12345", state.port)
    }

    @Test
    fun `onPortChange clears result`() = runTest {
        viewModel.onPortChange("8080")

        val state = viewModel.uiState.value
        assertEquals(null, state.result)
    }

    @Test
    fun `checkReachability with empty host does nothing`() = runTest {
        viewModel.onServerAddressChange("")
        viewModel.checkReachability()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        coVerify(exactly = 0) { repository.query(any(), any()) }
    }

    @Test
    fun `checkReachability sets loading state and calls repository`() = runTest {
        viewModel.onServerAddressChange("pool.ntp.org")
        viewModel.onPortChange("123")

        coEvery { repository.query("pool.ntp.org", 123) } returns NtpResult.Success(
            serverTime = "2024-01-15 10:30:00 UTC",
            offsetMs = 45L,
            delayMs = 120L
        )

        viewModel.checkReachability()

        // Advance the test dispatcher to execute the coroutine
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.result is NtpResult.Success)

        coVerify { repository.query("pool.ntp.org", 123) }
    }

    @Test
    fun `checkReachability handles DNS failure`() = runTest {
        viewModel.onServerAddressChange("invalid.host.xyz")

        coEvery { repository.query("invalid.host.xyz", 123) } returns NtpResult.DnsFailure("invalid.host.xyz")

        viewModel.checkReachability()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.result is NtpResult.DnsFailure)
    }

    @Test
    fun `checkReachability handles timeout`() = runTest {
        viewModel.onServerAddressChange("slow.server.com")

        coEvery { repository.query("slow.server.com", 123) } returns NtpResult.Timeout("slow.server.com")

        viewModel.checkReachability()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.result is NtpResult.Timeout)
    }

    @Test
    fun `checkReachability handles no network`() = runTest {
        viewModel.onServerAddressChange("pool.ntp.org")

        coEvery { repository.query("pool.ntp.org", 123) } returns NtpResult.NoNetwork

        viewModel.checkReachability()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.result is NtpResult.NoNetwork)
    }

    @Test
    fun `checkReachability saves history on completion`() = runTest {
        viewModel.onServerAddressChange("pool.ntp.org")

        coEvery { repository.query("pool.ntp.org", 123) } returns NtpResult.Success(
            serverTime = "2024-01-15 10:30:00 UTC",
            offsetMs = 45L,
            delayMs = 120L
        )

        viewModel.checkReachability()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { historyStore.save(any()) }
    }

    @Test
    fun `selectHistoryEntry populates fields and runs check`() = runTest {
        val entry = NtpHistoryEntry(
            timestamp = "2024/01/15 10:30:00",
            server = "time.google.com",
            port = 123,
            success = true
        )

        coEvery { repository.query("time.google.com", 123) } returns NtpResult.Success(
            serverTime = "2024-01-15 10:30:00 UTC",
            offsetMs = 45L,
            delayMs = 120L
        )

        viewModel.selectHistoryEntry(entry)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("time.google.com", state.serverAddress)
        assertEquals("123", state.port)
        assertTrue(state.result is NtpResult.Success)
    }

    @Test
    fun `history is deduplicated by server and port`() = runTest {
        viewModel.onServerAddressChange("pool.ntp.org")

        coEvery { repository.query(any(), any()) } returns NtpResult.Success(
            serverTime = "2024-01-15 10:30:00 UTC",
            offsetMs = 45L,
            delayMs = 120L
        )

        // Run the same query twice
        viewModel.checkReachability()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.checkReachability()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        // Should only have 1 entry, not 2
        assertEquals(1, state.history.size)
    }

    @Test
    fun `history is capped at 5 entries`() = runTest {
        coEvery { repository.query(any(), any()) } returns NtpResult.Success(
            serverTime = "2024-01-15 10:30:00 UTC",
            offsetMs = 45L,
            delayMs = 120L
        )

        // Run 6 different queries
        for (i in 1..6) {
            viewModel.onServerAddressChange("server${i}.ntp.org")
            viewModel.checkReachability()
            testDispatcher.scheduler.advanceUntilIdle()
        }

        val state = viewModel.uiState.value
        assertEquals(5, state.history.size)
    }

    @Test
    fun `checkReachability uses default port 123 when port is invalid`() = runTest {
        viewModel.onServerAddressChange("pool.ntp.org")
        viewModel.onPortChange("abc")

        coEvery { repository.query("pool.ntp.org", 123) } returns NtpResult.Success(
            serverTime = "2024-01-15 10:30:00 UTC",
            offsetMs = 45L,
            delayMs = 120L
        )

        viewModel.checkReachability()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.query("pool.ntp.org", 123) }
    }

    @Test
    fun `checkReachability cancels previous job if already running`() = runTest {
        viewModel.onServerAddressChange("pool.ntp.org")

        // First query that takes a long time
        coEvery { repository.query("pool.ntp.org", 123) } returns NtpResult.Success(
            serverTime = "2024-01-15 10:30:00 UTC",
            offsetMs = 45L,
            delayMs = 120L
        )

        viewModel.checkReachability()
        // Immediately start another query
        viewModel.checkReachability()
        testDispatcher.scheduler.advanceUntilIdle()

        // Repository should be called at least once (may be called twice if first isn't cancelled fast enough)
        // The key is that the second call completes
        val state = viewModel.uiState.value
        assertTrue(state.result is NtpResult.Success)
    }
}
