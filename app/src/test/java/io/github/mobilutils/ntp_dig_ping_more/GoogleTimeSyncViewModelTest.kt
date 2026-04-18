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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [GoogleTimeSyncViewModel] using MockK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GoogleTimeSyncViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: GoogleTimeSyncViewModel
    private lateinit var repository: GoogleTimeSyncRepository
    private lateinit var historyStore: GoogleTimeSyncHistoryStore

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        historyStore = mockk(relaxed = true)

        coEvery { historyStore.historyFlow } returns flowOf(emptyList())

        viewModel = GoogleTimeSyncViewModel(repository, historyStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Idle with empty history`() = runTest {
        val state = viewModel.uiState.value

        assertTrue(state.syncState is GoogleTimeSyncUiState.Idle)
        assertTrue(state.history.isEmpty())
    }

    @Test
    fun `syncTime with blank URL uses default URL`() = runTest {
        val defaultTimeResult = TimeSyncResult(
            serverTimeMillis = System.currentTimeMillis(),
            rttMillis = 120L,
            offsetMillis = 45L,
            correctedServerTimeMillis = System.currentTimeMillis(),
            requestTimestamp = System.currentTimeMillis(),
            responseTimestamp = System.currentTimeMillis()
        )

        coEvery { repository.fetchGoogleTime(GoogleTimeSyncRepository.DEFAULT_URL) } returns
            GoogleTimeSyncResult.Success(defaultTimeResult)

        viewModel.syncTime("")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.fetchGoogleTime(GoogleTimeSyncRepository.DEFAULT_URL) }
    }

    @Test
    fun `syncTime trims whitespace from URL`() = runTest {
        val url = "  http://clients2.google.com/time/1/current  "
        val defaultTimeResult = TimeSyncResult(
            serverTimeMillis = System.currentTimeMillis(),
            rttMillis = 120L,
            offsetMillis = 45L,
            correctedServerTimeMillis = System.currentTimeMillis(),
            requestTimestamp = System.currentTimeMillis(),
            responseTimestamp = System.currentTimeMillis()
        )

        coEvery { repository.fetchGoogleTime(GoogleTimeSyncRepository.DEFAULT_URL) } returns
            GoogleTimeSyncResult.Success(defaultTimeResult)

        viewModel.syncTime(url)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.fetchGoogleTime(GoogleTimeSyncRepository.DEFAULT_URL) }
    }

    @Test
    fun `syncTime sets Loading state then Success on completion`() = runTest {
        val timeResult = TimeSyncResult(
            serverTimeMillis = 1705312200000L,
            rttMillis = 120L,
            offsetMillis = 45L,
            correctedServerTimeMillis = 1705312200060L,
            requestTimestamp = 1705312199940L,
            responseTimestamp = 1705312200060L
        )

        coEvery { repository.fetchGoogleTime(any()) } returns
            GoogleTimeSyncResult.Success(timeResult)

        viewModel.syncTime("http://clients2.google.com/time/1/current")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.syncState is GoogleTimeSyncUiState.Success)
        val successState = state.syncState as GoogleTimeSyncUiState.Success
        assertEquals(45L, successState.result.offsetMillis)
        assertEquals(120L, successState.result.rttMillis)
    }

    @Test
    fun `syncTime handles NoNetwork error`() = runTest {
        coEvery { repository.fetchGoogleTime(any()) } returns
            GoogleTimeSyncResult.NoNetwork

        viewModel.syncTime("http://clients2.google.com/time/1/current")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.syncState is GoogleTimeSyncUiState.Error)
        val errorState = state.syncState as GoogleTimeSyncUiState.Error
        assertEquals("No network connection", errorState.message)
    }

    @Test
    fun `syncTime handles Timeout error`() = runTest {
        coEvery { repository.fetchGoogleTime(any()) } returns
            GoogleTimeSyncResult.Timeout("http://clients2.google.com/time/1/current")

        viewModel.syncTime("http://clients2.google.com/time/1/current")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.syncState is GoogleTimeSyncUiState.Error)
        val errorState = state.syncState as GoogleTimeSyncUiState.Error
        assertEquals("Request timed out", errorState.message)
    }

    @Test
    fun `syncTime handles HttpError`() = runTest {
        coEvery { repository.fetchGoogleTime(any()) } returns
            GoogleTimeSyncResult.HttpError(500, "http://clients2.google.com/time/1/current")

        viewModel.syncTime("http://clients2.google.com/time/1/current")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.syncState is GoogleTimeSyncUiState.Error)
        val errorState = state.syncState as GoogleTimeSyncUiState.Error
        assertEquals("HTTP error 500", errorState.message)
    }

    @Test
    fun `syncTime handles ParseError`() = runTest {
        coEvery { repository.fetchGoogleTime(any()) } returns
            GoogleTimeSyncResult.ParseError("Missing field: current_time_millis")

        viewModel.syncTime("http://clients2.google.com/time/1/current")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.syncState is GoogleTimeSyncUiState.Error)
        val errorState = state.syncState as GoogleTimeSyncUiState.Error
        assertEquals("Parse error: Missing field: current_time_millis", errorState.message)
    }

    @Test
    fun `syncTime handles generic Error`() = runTest {
        coEvery { repository.fetchGoogleTime(any()) } returns
            GoogleTimeSyncResult.Error("Connection refused")

        viewModel.syncTime("http://clients2.google.com/time/1/current")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.syncState is GoogleTimeSyncUiState.Error)
        val errorState = state.syncState as GoogleTimeSyncUiState.Error
        assertEquals("Connection refused", errorState.message)
    }

    @Test
    fun `syncTime saves history on completion`() = runTest {
        val timeResult = TimeSyncResult(
            serverTimeMillis = System.currentTimeMillis(),
            rttMillis = 120L,
            offsetMillis = 45L,
            correctedServerTimeMillis = System.currentTimeMillis(),
            requestTimestamp = System.currentTimeMillis(),
            responseTimestamp = System.currentTimeMillis()
        )

        coEvery { repository.fetchGoogleTime(any()) } returns
            GoogleTimeSyncResult.Success(timeResult)

        viewModel.syncTime("http://clients2.google.com/time/1/current")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { historyStore.save(any()) }
    }

    @Test
    fun `reset cancels job and sets Idle state`() = runTest {
        val timeResult = TimeSyncResult(
            serverTimeMillis = System.currentTimeMillis(),
            rttMillis = 120L,
            offsetMillis = 45L,
            correctedServerTimeMillis = System.currentTimeMillis(),
            requestTimestamp = System.currentTimeMillis(),
            responseTimestamp = System.currentTimeMillis()
        )

        coEvery { repository.fetchGoogleTime(any()) } returns
            GoogleTimeSyncResult.Success(timeResult)

        // Start a sync operation
        viewModel.syncTime("http://clients2.google.com/time/1/current")
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify it's in Success state
        assertTrue(viewModel.uiState.value.syncState is GoogleTimeSyncUiState.Success)

        // Reset
        viewModel.reset()

        val state = viewModel.uiState.value
        assertTrue(state.syncState is GoogleTimeSyncUiState.Idle)
    }

    @Test
    fun `selectHistoryEntry calls callback and syncs`() = runTest {
        val entry = GoogleTimeSyncHistoryEntry(
            timestamp = "2024/01/15 10:30:00",
            url = "http://clients2.google.com/time/1/current",
            offsetMs = 45L,
            rttMs = 120L,
            success = true
        )

        var callbackUrl = ""
        val timeResult = TimeSyncResult(
            serverTimeMillis = System.currentTimeMillis(),
            rttMillis = 120L,
            offsetMillis = 45L,
            correctedServerTimeMillis = System.currentTimeMillis(),
            requestTimestamp = System.currentTimeMillis(),
            responseTimestamp = System.currentTimeMillis()
        )

        coEvery { repository.fetchGoogleTime(any()) } returns
            GoogleTimeSyncResult.Success(timeResult)

        viewModel.selectHistoryEntry(entry) { url ->
            callbackUrl = url
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("http://clients2.google.com/time/1/current", callbackUrl)
        coVerify { repository.fetchGoogleTime("http://clients2.google.com/time/1/current") }
    }

    @Test
    fun `history is deduplicated by URL`() = runTest {
        val timeResult = TimeSyncResult(
            serverTimeMillis = System.currentTimeMillis(),
            rttMillis = 120L,
            offsetMillis = 45L,
            correctedServerTimeMillis = System.currentTimeMillis(),
            requestTimestamp = System.currentTimeMillis(),
            responseTimestamp = System.currentTimeMillis()
        )

        coEvery { repository.fetchGoogleTime(any()) } returns
            GoogleTimeSyncResult.Success(timeResult)

        // Run the same URL twice
        viewModel.syncTime("http://clients2.google.com/time/1/current")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.syncTime("http://clients2.google.com/time/1/current")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        // Should only have 1 entry, not 2
        assertEquals(1, state.history.size)
    }

    @Test
    fun `history is capped at 5 entries`() = runTest {
        val timeResult = TimeSyncResult(
            serverTimeMillis = System.currentTimeMillis(),
            rttMillis = 120L,
            offsetMillis = 45L,
            correctedServerTimeMillis = System.currentTimeMillis(),
            requestTimestamp = System.currentTimeMillis(),
            responseTimestamp = System.currentTimeMillis()
        )

        coEvery { repository.fetchGoogleTime(any()) } returns
            GoogleTimeSyncResult.Success(timeResult)

        // Run 6 different queries
        for (i in 1..6) {
            viewModel.syncTime("http://server${i}.google.com/time/1/current")
            testDispatcher.scheduler.advanceUntilIdle()
        }

        val state = viewModel.uiState.value
        assertEquals(5, state.history.size)
    }
}
