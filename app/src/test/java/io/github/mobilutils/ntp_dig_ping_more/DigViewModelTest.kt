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
 * Unit tests for [DigViewModel] using MockK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DigViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: DigViewModel
    private lateinit var repository: DigRepository
    private lateinit var historyStore: DigHistoryStore

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        historyStore = mockk(relaxed = true)

        coEvery { historyStore.historyFlow } returns flowOf(emptyList())

        viewModel = DigViewModel(repository, historyStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has default values`() = runTest {
        val state = viewModel.uiState.value

        assertEquals("", state.dnsServer)
        assertEquals("", state.fqdn)
        assertFalse(state.isLoading)
        assertEquals(null, state.result)
        assertTrue(state.history.isEmpty())
    }

    @Test
    fun `onDnsServerChange updates dns server and clears result`() = runTest {
        viewModel.onDnsServerChange("8.8.8.8")

        val state = viewModel.uiState.value
        assertEquals("8.8.8.8", state.dnsServer)
        assertEquals(null, state.result)
    }

    @Test
    fun `onFqdnChange updates fqdn and clears result`() = runTest {
        viewModel.onFqdnChange("example.com")

        val state = viewModel.uiState.value
        assertEquals("example.com", state.fqdn)
        assertEquals(null, state.result)
    }

    @Test
    fun `runDigQuery with blank fqdn does nothing`() = runTest {
        viewModel.onFqdnChange("")
        viewModel.runDigQuery()

        assertFalse(viewModel.uiState.value.isLoading)
        coVerify(exactly = 0) { repository.resolve(any(), any()) }
    }

    @Test
    fun `runDigQuery sets loading and calls repository`() = runTest {
        viewModel.onDnsServerChange("8.8.8.8")
        viewModel.onFqdnChange("example.com")

        val digResult = DigResult.Success(
            questionSection = "example.com. IN A",
            records = listOf("example.com.  300  IN  A  93.184.216.34"),
            dnsServer = "8.8.8.8"
        )

        coEvery { repository.resolve("8.8.8.8", "example.com") } returns digResult

        viewModel.runDigQuery()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.result is DigResult.Success)

        coVerify { repository.resolve("8.8.8.8", "example.com") }
    }

    @Test
    fun `runDigQuery handles NxDomain`() = runTest {
        viewModel.onFqdnChange("nonexistent.invalid")

        coEvery { repository.resolve(any(), any()) } returns
            DigResult.NxDomain("nonexistent.invalid")

        viewModel.runDigQuery()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.result is DigResult.NxDomain)
    }

    @Test
    fun `runDigQuery handles DnsServerError`() = runTest {
        viewModel.onDnsServerChange("bad.server")

        coEvery { repository.resolve(any(), any()) } returns
            DigResult.DnsServerError("Connection refused")

        viewModel.runDigQuery()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.result is DigResult.DnsServerError)
    }

    @Test
    fun `runDigQuery handles NoNetwork`() = runTest {
        viewModel.onFqdnChange("example.com")

        coEvery { repository.resolve(any(), any()) } returns DigResult.NoNetwork

        viewModel.runDigQuery()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.result is DigResult.NoNetwork)
    }

    @Test
    fun `runDigQuery saves history on completion`() = runTest {
        viewModel.onDnsServerChange("8.8.8.8")
        viewModel.onFqdnChange("example.com")

        coEvery { repository.resolve(any(), any()) } returns
            DigResult.Success(
                questionSection = "example.com. IN A",
                records = listOf("example.com.  300  IN  A  93.184.216.34"),
                dnsServer = "8.8.8.8"
            )

        viewModel.runDigQuery()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { historyStore.save(any()) }
    }

    @Test
    fun `selectHistoryEntry populates fields and runs query`() = runTest {
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
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("1.1.1.1", state.dnsServer)
        assertEquals("cloudflare.com", state.fqdn)
        assertTrue(state.result is DigResult.Success)
    }

    @Test
    fun `history is deduplicated by dnsServer and fqdn`() = runTest {
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
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.runDigQuery()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.history.size)
    }

    @Test
    fun `history is capped at 5 entries`() = runTest {
        coEvery { repository.resolve(any(), any()) } returns
            DigResult.Success(
                questionSection = "example.com. IN A",
                records = listOf("example.com.  300  IN  A  93.184.216.34"),
                dnsServer = "8.8.8.8"
            )

        // Run 6 different queries
        for (i in 1..6) {
            viewModel.onDnsServerChange("8.8.8.8")
            viewModel.onFqdnChange("server${i}.example.com")
            viewModel.runDigQuery()
            testDispatcher.scheduler.advanceUntilIdle()
        }

        assertEquals(5, viewModel.uiState.value.history.size)
    }

    @Test
    fun `history status is SUCCESS for successful result`() = runTest {
        viewModel.onFqdnChange("example.com")

        coEvery { repository.resolve(any(), any()) } returns
            DigResult.Success(
                questionSection = "example.com. IN A",
                records = listOf("example.com.  300  IN  A  93.184.216.34"),
                dnsServer = "8.8.8.8"
            )

        viewModel.runDigQuery()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(DigStatus.SUCCESS, viewModel.uiState.value.history[0].status)
    }

    @Test
    fun `history status is FAILED for error result`() = runTest {
        viewModel.onFqdnChange("nonexistent.invalid")

        coEvery { repository.resolve(any(), any()) } returns
            DigResult.NxDomain("nonexistent.invalid")

        viewModel.runDigQuery()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(DigStatus.FAILED, viewModel.uiState.value.history[0].status)
    }
}
