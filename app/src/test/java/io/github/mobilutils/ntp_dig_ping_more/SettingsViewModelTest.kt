package io.github.mobilutils.ntp_dig_ping_more

import io.github.mobilutils.ntp_dig_ping_more.proxy.ProxyResolver
import io.github.mobilutils.ntp_dig_ping_more.proxy.ProxyTestResult
import io.github.mobilutils.ntp_dig_ping_more.settings.ProxyConfig
import io.github.mobilutils.ntp_dig_ping_more.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SettingsViewModel].
 *
 * Tests both the existing timeout functionality and the new proxy/PAC
 * configuration features.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var proxyResolver: ProxyResolver
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        settingsRepository = mockk(relaxed = true)
        proxyResolver = mockk(relaxed = true)

        coEvery { settingsRepository.timeoutSecondsFlow } returns flowOf(5)
        coEvery { settingsRepository.proxyConfigFlow } returns flowOf(ProxyConfig())

        viewModel = SettingsViewModel(settingsRepository, proxyResolver)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Initialization ───────────────────────────────────────────────────────

    @Test
    fun `initial state has default timeout`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("5", viewModel.uiState.value.timeoutInput)
    }

    @Test
    fun `initial state has proxy disabled`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.proxyEnabled)
    }

    @Test
    fun `initial state has empty PAC URL`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("", viewModel.uiState.value.proxyPacUrl)
    }

    @Test
    fun `initial state loads persisted proxy config`() = runTest {
        coEvery { settingsRepository.proxyConfigFlow } returns flowOf(
            ProxyConfig(
                enabled = true,
                pacUrl = "http://proxy.corp.com/proxy.pac",
                lastTested = 1000L,
                lastTestResult = "✓ Success",
            )
        )

        val vm = SettingsViewModel(settingsRepository, proxyResolver)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.proxyEnabled)
        assertEquals("http://proxy.corp.com/proxy.pac", vm.uiState.value.proxyPacUrl)
        assertEquals(1000L, vm.uiState.value.proxyLastTested)
        assertEquals("✓ Success", vm.uiState.value.proxyTestResult)
    }

    // ── Timeout ──────────────────────────────────────────────────────────────

    @Test
    fun `onTimeoutChange with valid value saves and clears error`() = runTest {
        viewModel.onTimeoutChange("10")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("10", viewModel.uiState.value.timeoutInput)
        assertFalse(viewModel.uiState.value.isError)
        coVerify { settingsRepository.updateTimeout(10) }
    }

    @Test
    fun `onTimeoutChange with out-of-range value sets error`() = runTest {
        viewModel.onTimeoutChange("99")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isError)
    }

    @Test
    fun `revert restores last saved value`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onTimeoutChange("99") // out of range
        assertTrue(viewModel.uiState.value.isError)

        viewModel.revert()
        assertEquals("5", viewModel.uiState.value.timeoutInput)
        assertFalse(viewModel.uiState.value.isError)
    }

    // ── Proxy enable/disable ─────────────────────────────────────────────────

    @Test
    fun `onProxyEnabledChange updates state`() = runTest {
        viewModel.onProxyEnabledChange(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.proxyEnabled)
    }

    @Test
    fun `onProxyEnabledChange saves config`() = runTest {
        viewModel.onProxyEnabledChange(true)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { settingsRepository.saveProxyConfig(any()) }
    }

    // ── PAC URL validation ───────────────────────────────────────────────────

    @Test
    fun `validatePacUrl accepts empty string`() {
        assertNull(viewModel.validatePacUrl(""))
    }

    @Test
    fun `validatePacUrl accepts valid HTTP URL`() {
        assertNull(viewModel.validatePacUrl("http://proxy.corp.com/proxy.pac"))
    }

    @Test
    fun `validatePacUrl accepts valid HTTPS URL`() {
        assertNull(viewModel.validatePacUrl("https://proxy.corp.com/proxy.pac"))
    }

    @Test
    fun `validatePacUrl rejects FTP URL`() {
        val error = viewModel.validatePacUrl("ftp://proxy.corp.com/proxy.pac")
        assertTrue(error != null && error.contains("http"))
    }

    @Test
    fun `validatePacUrl rejects malformed URL`() {
        val error = viewModel.validatePacUrl("not a url at all")
        assertTrue(error != null && error.contains("Invalid"))
    }

    @Test
    fun `validatePacUrl rejects URL without host`() {
        val error = viewModel.validatePacUrl("http://")
        // Depending on URL parser, this may be "Invalid" or "hostname"
        assertTrue(error != null)
    }

    // ── PAC URL input with debounce ──────────────────────────────────────────

    @Test
    fun `onProxyPacUrlChange updates URL immediately`() = runTest {
        viewModel.onProxyPacUrlChange("http://test.com/pac")
        // URL should update immediately (before debounce)
        assertEquals("http://test.com/pac", viewModel.uiState.value.proxyPacUrl)
    }

    @Test
    fun `onProxyPacUrlChange clears error immediately while typing`() = runTest {
        // First set an invalid URL and let debounce fire
        viewModel.onProxyPacUrlChange("not a url")
        testDispatcher.scheduler.advanceTimeBy(400)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.proxyPacUrlError != null)

        // Now start typing a new URL — error should clear immediately
        viewModel.onProxyPacUrlChange("http://")
        assertNull(viewModel.uiState.value.proxyPacUrlError)
    }

    @Test
    fun `onProxyPacUrlChange clears proxy cache`() = runTest {
        viewModel.onProxyPacUrlChange("http://new.pac.url")
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify clearCache was called
        io.mockk.verify { proxyResolver.clearCache() }
    }

    // ── Test Proxy ───────────────────────────────────────────────────────────

    @Test
    fun `testProxy sets isTestingProxy while running`() = runTest {
        coEvery { proxyResolver.testProxy() } returns
            ProxyTestResult(success = true, message = "✓ HTTP 204", latencyMs = 50)

        viewModel.testProxy()
        // Should be in testing state
        assertTrue(viewModel.uiState.value.isTestingProxy)

        testDispatcher.scheduler.advanceUntilIdle()

        // Should be done testing
        assertFalse(viewModel.uiState.value.isTestingProxy)
    }

    @Test
    fun `testProxy updates result on success`() = runTest {
        coEvery { proxyResolver.testProxy() } returns
            ProxyTestResult(success = true, message = "✓ HTTP 204 via proxy (50ms)", latencyMs = 50)

        viewModel.testProxy()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("✓ HTTP 204 via proxy (50ms)", viewModel.uiState.value.proxyTestResult)
        assertTrue(viewModel.uiState.value.proxyLastTested > 0)
    }

    @Test
    fun `testProxy updates result on failure`() = runTest {
        coEvery { proxyResolver.testProxy() } returns
            ProxyTestResult(success = false, message = "✗ Connection refused")

        viewModel.testProxy()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("✗ Connection refused", viewModel.uiState.value.proxyTestResult)
    }

    @Test
    fun `testProxy saves result to settings`() = runTest {
        coEvery { proxyResolver.testProxy() } returns
            ProxyTestResult(success = true, message = "✓ OK", latencyMs = 10)

        viewModel.testProxy()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { settingsRepository.saveProxyConfig(any()) }
    }
}
