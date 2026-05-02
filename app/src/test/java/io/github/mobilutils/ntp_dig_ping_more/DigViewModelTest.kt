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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DigViewModel] using MockK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DigViewModelTest {

    private fun fakeSettingsRepository(): SettingsRepository = mockk<SettingsRepository>(relaxed = true).also {
        coEvery { it.timeoutSecondsFlow } returns flowOf(5)
    }

    private fun createViewModel(
        repository: DigRepository = mockk(relaxed = true),
        historyStore: DigHistoryStore = mockk(relaxed = true),
    ): DigViewModel {
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }
        coEvery { repository.resolve(any(), any()) } returns DigResult.NoNetwork
        return DigViewModel(repository, historyStore, fakeSettingsRepository())
    }

    @Test
    fun `initial state has default values`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val viewModel = createViewModel()
        val state = viewModel.uiState.value

        assertEquals("", state.dnsServer)
        assertEquals("", state.fqdn)
        assertFalse(state.isLoading)
        assertEquals(null, state.result)
        assertTrue(state.history.isEmpty())
    }

    @Test
    fun `onDnsServerChange updates dns server and clears result`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val viewModel = createViewModel()
        viewModel.onDnsServerChange("8.8.8.8")

        val state = viewModel.uiState.value
        assertEquals("8.8.8.8", state.dnsServer)
        assertEquals(null, state.result)
    }

    @Test
    fun `onFqdnChange updates fqdn and clears result`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val viewModel = createViewModel()
        viewModel.onFqdnChange("example.com")

        val state = viewModel.uiState.value
        assertEquals("example.com", state.fqdn)
        assertEquals(null, state.result)
    }

    @Test
    fun `runDigQuery with blank fqdn does nothing`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<DigRepository>(relaxed = true)
        val historyStore = mockk<DigHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }
        coEvery { repository.resolve(any(), any()) } returns DigResult.NoNetwork

        val viewModel = DigViewModel(repository, historyStore, fakeSettingsRepository())
        viewModel.onFqdnChange("")
        viewModel.runDigQuery()

        assertFalse(viewModel.uiState.value.isLoading)
        coVerify(exactly = 0) { repository.resolve(any(), any()) }
    }

    @Test
    fun `runDigQuery sets loading and calls repository`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<DigRepository>(relaxed = true)
        val historyStore = mockk<DigHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        val viewModel = DigViewModel(repository, historyStore, fakeSettingsRepository())
        viewModel.onDnsServerChange("8.8.8.8")
        viewModel.onFqdnChange("example.com")

        val digResult = DigResult.Success(
            questionSection = "example.com. IN A",
            records = listOf("example.com.  300  IN  A  93.184.216.34"),
            dnsServer = "8.8.8.8"
        )

        coEvery { repository.resolve("8.8.8.8", "example.com") } returns digResult

        viewModel.runDigQuery()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.result is DigResult.Success)

        coVerify { repository.resolve("8.8.8.8", "example.com") }
    }

    @Test
    fun `runDigQuery handles NxDomain`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<DigRepository>(relaxed = true)
        val historyStore = mockk<DigHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        val viewModel = DigViewModel(repository, historyStore, fakeSettingsRepository())
        viewModel.onFqdnChange("nonexistent.invalid")

        coEvery { repository.resolve(any(), any()) } returns
            DigResult.NxDomain("nonexistent.invalid")

        viewModel.runDigQuery()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.result is DigResult.NxDomain)
    }

    @Test
    fun `runDigQuery handles DnsServerError`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<DigRepository>(relaxed = true)
        val historyStore = mockk<DigHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        val viewModel = DigViewModel(repository, historyStore, fakeSettingsRepository())
        viewModel.onDnsServerChange("bad.server")
        viewModel.onFqdnChange("example.com")

        coEvery { repository.resolve("bad.server", "example.com") } returns
            DigResult.DnsServerError("Connection refused")

        viewModel.runDigQuery()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.result is DigResult.DnsServerError)
    }

    @Test
    fun `runDigQuery handles NoNetwork`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<DigRepository>(relaxed = true)
        val historyStore = mockk<DigHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        val viewModel = DigViewModel(repository, historyStore, fakeSettingsRepository())
        viewModel.onFqdnChange("example.com")

        coEvery { repository.resolve(any(), any()) } returns DigResult.NoNetwork

        viewModel.runDigQuery()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.result is DigResult.NoNetwork)
    }

    @Test
    fun `runDigQuery saves history on completion`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<DigRepository>(relaxed = true)
        val historyStore = mockk<DigHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())

        val viewModel = DigViewModel(repository, historyStore, fakeSettingsRepository())
        viewModel.onDnsServerChange("8.8.8.8")
        viewModel.onFqdnChange("example.com")

        coEvery { repository.resolve(any(), any()) } returns
            DigResult.Success(
                questionSection = "example.com. IN A",
                records = listOf("example.com.  300  IN  A  93.184.216.34"),
                dnsServer = "8.8.8.8"
            )

        viewModel.runDigQuery()
        advanceUntilIdle()

        coVerify { historyStore.save(any()) }
    }

    @Test
    fun `selectHistoryEntry populates fields and runs query`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<DigRepository>(relaxed = true)
        val historyStore = mockk<DigHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        val viewModel = DigViewModel(repository, historyStore, fakeSettingsRepository())
        val entry = DigHistoryEntry(
            timestamp = "2024/01/15 10:30:00",
            dnsServer = "1.1.1.1",
            fqdn = "cloudflare.com",
            status = DigStatus.SUCCESS
        )

        coEvery { repository.resolve("1.1.1.1", "cloudflare.com") } returns
            DigResult.Success(
                questionSection = "cloudflare.com. IN A",
                records = listOf("cloudflare.com.  300  IN  A  104.16.132.229"),
                dnsServer = "1.1.1.1"
            )

        viewModel.selectHistoryEntry(entry)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("1.1.1.1", state.dnsServer)
        assertEquals("cloudflare.com", state.fqdn)
        assertTrue(state.result is DigResult.Success)
    }

    @Test
    fun `history is deduplicated by dnsServer and fqdn`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<DigRepository>(relaxed = true)
        val historyStore = mockk<DigHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        val viewModel = DigViewModel(repository, historyStore, fakeSettingsRepository())
        viewModel.onDnsServerChange("8.8.8.8")
        viewModel.onFqdnChange("example.com")

        coEvery { repository.resolve(any(), any()) } returns
            DigResult.Success(
                questionSection = "example.com. IN A",
                records = listOf("example.com.  300  IN  A  93.184.216.34"),
                dnsServer = "8.8.8.8"
            )

        // Run same query twice
        viewModel.runDigQuery()
        advanceUntilIdle()

        viewModel.runDigQuery()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.history.size)
    }

    @Test
    fun `history is capped at 5 entries`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<DigRepository>(relaxed = true)
        val historyStore = mockk<DigHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { repository.resolve(any(), any()) } returns
            DigResult.Success(
                questionSection = "example.com. IN A",
                records = listOf("example.com.  300  IN  A  93.184.216.34"),
                dnsServer = "8.8.8.8"
            )

        val viewModel = DigViewModel(repository, historyStore, fakeSettingsRepository())

        // Run 6 different queries
        for (i in 1..6) {
            viewModel.onDnsServerChange("8.8.8.8")
            viewModel.onFqdnChange("server${i}.example.com")
            viewModel.runDigQuery()
            advanceUntilIdle()
        }

        assertEquals(5, viewModel.uiState.value.history.size)
    }

    @Test
    fun `history status is SUCCESS for successful result`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<DigRepository>(relaxed = true)
        val historyStore = mockk<DigHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        val viewModel = DigViewModel(repository, historyStore, fakeSettingsRepository())
        viewModel.onFqdnChange("example.com")

        coEvery { repository.resolve(any(), any()) } returns
            DigResult.Success(
                questionSection = "example.com. IN A",
                records = listOf("example.com.  300  IN  A  93.184.216.34"),
                dnsServer = "8.8.8.8"
            )

        viewModel.runDigQuery()
        advanceUntilIdle()

        assertEquals(DigStatus.SUCCESS, viewModel.uiState.value.history[0].status)
    }

    @Test
    fun `history status is FAILED for error result`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<DigRepository>(relaxed = true)
        val historyStore = mockk<DigHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        val viewModel = DigViewModel(repository, historyStore, fakeSettingsRepository())
        viewModel.onFqdnChange("nonexistent.invalid")

        coEvery { repository.resolve(any(), any()) } returns
            DigResult.NxDomain("nonexistent.invalid")

        viewModel.runDigQuery()
        advanceUntilIdle()

        assertEquals(DigStatus.FAILED, viewModel.uiState.value.history[0].status)
    }
}
