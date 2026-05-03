package io.github.mobilutils.ntp_dig_ping_more

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.github.mobilutils.ntp_dig_ping_more.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fakeSettingsRepository(): SettingsRepository = mockk<SettingsRepository>(relaxed = true).also {
        coEvery { it.timeoutSecondsFlow } returns flowOf(5)
    }

    private fun createViewModel(
        repository: GoogleTimeSyncRepository = mockk(relaxed = true),
        historyStore: GoogleTimeSyncHistoryStore = mockk(relaxed = true),
    ): GoogleTimeSyncViewModel {
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        return GoogleTimeSyncViewModel(repository, historyStore, fakeSettingsRepository())
    }

    @Test
    fun `initial state is Idle with empty history`() = runTest {
        val viewModel = createViewModel()
        val state = viewModel.uiState.value

        assertTrue(state.syncState is GoogleTimeSyncUiState.Idle)
        assertTrue(state.history.isEmpty())
    }

    @Test
    fun `syncTime with blank URL uses default URL`() = runTest {
        val repository = mockk<GoogleTimeSyncRepository>(relaxed = true)
        val historyStore = mockk<GoogleTimeSyncHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())

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

        val viewModel = GoogleTimeSyncViewModel(repository, historyStore, fakeSettingsRepository())
        viewModel.syncTime("")
        advanceUntilIdle()

        coVerify { repository.fetchGoogleTime(GoogleTimeSyncRepository.DEFAULT_URL) }
    }

    @Test
    fun `syncTime trims whitespace from URL`() = runTest {
        val repository = mockk<GoogleTimeSyncRepository>(relaxed = true)
        val historyStore = mockk<GoogleTimeSyncHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())

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

        val viewModel = GoogleTimeSyncViewModel(repository, historyStore, fakeSettingsRepository())
        viewModel.syncTime(url)
        advanceUntilIdle()

        coVerify { repository.fetchGoogleTime(GoogleTimeSyncRepository.DEFAULT_URL) }
    }

    @Test
    fun `syncTime sets Loading state then Success on completion`() = runTest {
        val repository = mockk<GoogleTimeSyncRepository>(relaxed = true)
        val historyStore = mockk<GoogleTimeSyncHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())

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

        val viewModel = GoogleTimeSyncViewModel(repository, historyStore, fakeSettingsRepository())
        viewModel.syncTime("http://clients2.google.com/time/1/current")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.syncState is GoogleTimeSyncUiState.Success)
        val successState = state.syncState as GoogleTimeSyncUiState.Success
        assertEquals(45L, successState.result.offsetMillis)
        assertEquals(120L, successState.result.rttMillis)
    }

    @Test
    fun `syncTime handles NoNetwork error`() = runTest {
        val repository = mockk<GoogleTimeSyncRepository>(relaxed = true)
        val historyStore = mockk<GoogleTimeSyncHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())

        coEvery { repository.fetchGoogleTime(any()) } returns
            GoogleTimeSyncResult.NoNetwork

        val viewModel = GoogleTimeSyncViewModel(repository, historyStore, fakeSettingsRepository())
        viewModel.syncTime("http://clients2.google.com/time/1/current")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.syncState is GoogleTimeSyncUiState.Error)
        val errorState = state.syncState as GoogleTimeSyncUiState.Error
        assertEquals("No network connection", errorState.message)
    }

    @Test
    fun `syncTime handles Timeout error`() = runTest {
        val repository = mockk<GoogleTimeSyncRepository>(relaxed = true)
        val historyStore = mockk<GoogleTimeSyncHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())

        coEvery { repository.fetchGoogleTime(any()) } returns
            GoogleTimeSyncResult.Timeout("http://clients2.google.com/time/1/current")

        val viewModel = GoogleTimeSyncViewModel(repository, historyStore, fakeSettingsRepository())
        viewModel.syncTime("http://clients2.google.com/time/1/current")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.syncState is GoogleTimeSyncUiState.Error)
        val errorState = state.syncState as GoogleTimeSyncUiState.Error
        assertEquals("Request timed out", errorState.message)
    }

    @Test
    fun `syncTime handles HttpError`() = runTest {
        val repository = mockk<GoogleTimeSyncRepository>(relaxed = true)
        val historyStore = mockk<GoogleTimeSyncHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())

        coEvery { repository.fetchGoogleTime(any()) } returns
            GoogleTimeSyncResult.HttpError(500, "http://clients2.google.com/time/1/current")

        val viewModel = GoogleTimeSyncViewModel(repository, historyStore, fakeSettingsRepository())
        viewModel.syncTime("http://clients2.google.com/time/1/current")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.syncState is GoogleTimeSyncUiState.Error)
        val errorState = state.syncState as GoogleTimeSyncUiState.Error
        assertEquals("HTTP error 500", errorState.message)
    }

    @Test
    fun `syncTime handles ParseError`() = runTest {
        val repository = mockk<GoogleTimeSyncRepository>(relaxed = true)
        val historyStore = mockk<GoogleTimeSyncHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())

        coEvery { repository.fetchGoogleTime(any()) } returns
            GoogleTimeSyncResult.ParseError("Missing field: current_time_millis")

        val viewModel = GoogleTimeSyncViewModel(repository, historyStore, fakeSettingsRepository())
        viewModel.syncTime("http://clients2.google.com/time/1/current")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.syncState is GoogleTimeSyncUiState.Error)
        val errorState = state.syncState as GoogleTimeSyncUiState.Error
        assertEquals("Parse error: Missing field: current_time_millis", errorState.message)
    }

    @Test
    fun `syncTime handles generic Error`() = runTest {
        val repository = mockk<GoogleTimeSyncRepository>(relaxed = true)
        val historyStore = mockk<GoogleTimeSyncHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())

        coEvery { repository.fetchGoogleTime(any()) } returns
            GoogleTimeSyncResult.Error("Connection refused")

        val viewModel = GoogleTimeSyncViewModel(repository, historyStore, fakeSettingsRepository())
        viewModel.syncTime("http://clients2.google.com/time/1/current")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.syncState is GoogleTimeSyncUiState.Error)
        val errorState = state.syncState as GoogleTimeSyncUiState.Error
        assertEquals("Connection refused", errorState.message)
    }

    @Test
    fun `syncTime saves history on completion`() = runTest {
        val repository = mockk<GoogleTimeSyncRepository>(relaxed = true)
        val historyStore = mockk<GoogleTimeSyncHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())

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

        val viewModel = GoogleTimeSyncViewModel(repository, historyStore, fakeSettingsRepository())
        viewModel.syncTime("http://clients2.google.com/time/1/current")
        advanceUntilIdle()

        coVerify { historyStore.save(any()) }
    }

    @Test
    fun `reset cancels job and sets Idle state`() = runTest {
        val repository = mockk<GoogleTimeSyncRepository>(relaxed = true)
        val historyStore = mockk<GoogleTimeSyncHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())

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

        val viewModel = GoogleTimeSyncViewModel(repository, historyStore, fakeSettingsRepository())

        // Start a sync operation
        viewModel.syncTime("http://clients2.google.com/time/1/current")
        advanceUntilIdle()

        // Verify it's in Success state
        assertTrue(viewModel.uiState.value.syncState is GoogleTimeSyncUiState.Success)

        // Reset
        viewModel.reset()

        val state = viewModel.uiState.value
        assertTrue(state.syncState is GoogleTimeSyncUiState.Idle)
    }

    @Test
    fun `selectHistoryEntry calls callback and syncs`() = runTest {
        val repository = mockk<GoogleTimeSyncRepository>(relaxed = true)
        val historyStore = mockk<GoogleTimeSyncHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())

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

        val viewModel = GoogleTimeSyncViewModel(repository, historyStore, fakeSettingsRepository())
        viewModel.selectHistoryEntry(entry) { url ->
            callbackUrl = url
        }
        advanceUntilIdle()

        assertEquals("http://clients2.google.com/time/1/current", callbackUrl)
        coVerify { repository.fetchGoogleTime("http://clients2.google.com/time/1/current") }
    }

    @Test
    fun `history is deduplicated by URL`() = runTest {
        val repository = mockk<GoogleTimeSyncRepository>(relaxed = true)
        val historyStore = mockk<GoogleTimeSyncHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())

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

        val viewModel = GoogleTimeSyncViewModel(repository, historyStore, fakeSettingsRepository())

        // Run the same URL twice
        viewModel.syncTime("http://clients2.google.com/time/1/current")
        advanceUntilIdle()

        viewModel.syncTime("http://clients2.google.com/time/1/current")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Should only have 1 entry, not 2
        assertEquals(1, state.history.size)
    }

    @Test
    fun `history is capped at 5 entries`() = runTest {
        val repository = mockk<GoogleTimeSyncRepository>(relaxed = true)
        val historyStore = mockk<GoogleTimeSyncHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())

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

        val viewModel = GoogleTimeSyncViewModel(repository, historyStore, fakeSettingsRepository())

        // Run 6 different queries
        for (i in 1..6) {
            viewModel.syncTime("http://server${i}.google.com/time/1/current")
            advanceUntilIdle()
        }

        val state = viewModel.uiState.value
        assertEquals(5, state.history.size)
    }
}
