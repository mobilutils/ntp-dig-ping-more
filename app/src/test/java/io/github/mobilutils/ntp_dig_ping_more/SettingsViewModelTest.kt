package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import android.net.Uri
import io.github.mobilutils.ntp_dig_ping_more.proxy.ProxyPacLogger
import io.github.mobilutils.ntp_dig_ping_more.proxy.ProxyResolver
import io.github.mobilutils.ntp_dig_ping_more.proxy.ProxyTestResult
import io.github.mobilutils.ntp_dig_ping_more.settings.ProxyConfig
import io.github.mobilutils.ntp_dig_ping_more.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SettingsViewModel].
 *
 * Tests both the existing timeout functionality and the new proxy/PAC
 * configuration, logging, and file-based PAC features.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var proxyResolver: ProxyResolver
    private lateinit var proxyPacLogger: ProxyPacLogger
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        settingsRepository = mockk(relaxed = true)
        proxyResolver = mockk(relaxed = true)
        proxyPacLogger = mockk(relaxed = true)

        coEvery { settingsRepository.timeoutSecondsFlow } returns flowOf(5)
        coEvery { settingsRepository.proxyConfigFlow } returns flowOf(ProxyConfig())
        every { proxyPacLogger.getLogs() } returns emptyList()

        viewModel = SettingsViewModel(settingsRepository, proxyResolver, proxyPacLogger, appContext = null)
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
    fun `initial state has logging disabled`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.proxyLoggingEnabled)
    }

    @Test
    fun `initial state loads persisted proxy config`() = runTest {
        coEvery { settingsRepository.proxyConfigFlow } returns flowOf(
            ProxyConfig(
                enabled = true,
                pacUrl = "http://proxy.corp.com/proxy.pac",
                lastTested = 1000L,
                lastTestResult = "✓ Success",
                loggingEnabled = true,
            )
        )

        val vm = SettingsViewModel(settingsRepository, proxyResolver, proxyPacLogger, appContext = null)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.proxyEnabled)
        assertEquals("http://proxy.corp.com/proxy.pac", vm.uiState.value.proxyPacUrl)
        assertEquals(1000L, vm.uiState.value.proxyLastTested)
        assertEquals("✓ Success", vm.uiState.value.proxyTestResult)
        assertTrue(vm.uiState.value.proxyLoggingEnabled)
    }

    @Test
    fun `initial state syncs logger enabled flag from persisted config`() = runTest {
        coEvery { settingsRepository.proxyConfigFlow } returns flowOf(
            ProxyConfig(loggingEnabled = true)
        )

        val vm = SettingsViewModel(settingsRepository, proxyResolver, proxyPacLogger, appContext = null)
        testDispatcher.scheduler.advanceUntilIdle()

        verify { proxyPacLogger.enabled = true }
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
        // Returns a @StringRes Int, not null = error
        assertTrue(error != null)
    }

    @Test
    fun `validatePacUrl rejects malformed URL`() {
        val error = viewModel.validatePacUrl("not a url at all")
        // Returns a @StringRes Int, not null = error
        assertTrue(error != null)
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

    // ── Proxy logging ────────────────────────────────────────────────────────

    @Test
    fun `onProxyLoggingEnabledChange updates UI state`() = runTest {
        viewModel.onProxyLoggingEnabledChange(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.proxyLoggingEnabled)
    }

    @Test
    fun `onProxyLoggingEnabledChange sets logger enabled flag`() = runTest {
        viewModel.onProxyLoggingEnabledChange(true)
        testDispatcher.scheduler.advanceUntilIdle()

        verify { proxyPacLogger.enabled = true }
    }

    @Test
    fun `onProxyLoggingEnabledChange persists config`() = runTest {
        viewModel.onProxyLoggingEnabledChange(true)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { settingsRepository.saveProxyConfig(match { it.loggingEnabled }) }
    }

    @Test
    fun `onProxyLoggingEnabledChange to false updates state and logger`() = runTest {
        viewModel.onProxyLoggingEnabledChange(true)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onProxyLoggingEnabledChange(false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.proxyLoggingEnabled)
        verify { proxyPacLogger.enabled = false }
    }

    @Test
    fun `onViewLogs opens dialog with log snapshot`() = runTest {
        val fakeLogs = listOf("[2026-05-03] EVENT_1", "[2026-05-03] EVENT_2")
        every { proxyPacLogger.getLogs() } returns fakeLogs

        viewModel.onViewLogs()

        assertTrue(viewModel.uiState.value.showLogDialog)
        assertEquals(fakeLogs, viewModel.uiState.value.proxyLogs)
    }

    @Test
    fun `onClearLogs clears logger and closes dialog`() = runTest {
        viewModel.onViewLogs()
        assertTrue(viewModel.uiState.value.showLogDialog)

        viewModel.onClearLogs()

        verify { proxyPacLogger.clear() }
        assertTrue(viewModel.uiState.value.proxyLogs.isEmpty())
        assertFalse(viewModel.uiState.value.showLogDialog)
    }

    @Test
    fun `onDismissLogDialog closes dialog`() = runTest {
        viewModel.onViewLogs()
        assertTrue(viewModel.uiState.value.showLogDialog)

        viewModel.onDismissLogDialog()

        assertFalse(viewModel.uiState.value.showLogDialog)
    }

    // ── PAC source mode ──────────────────────────────────────────────────────

    @Test
    fun `initial state has URL as default source mode`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(PacSourceMode.URL, viewModel.uiState.value.pacSourceMode)
    }

    @Test
    fun `onPacSourceModeChange switches to FILE and clears URL`() = runTest {
        // Set a URL first
        viewModel.onProxyPacUrlChange("http://proxy.corp.com/proxy.pac")
        testDispatcher.scheduler.advanceUntilIdle()

        // Switch to FILE mode
        viewModel.onPacSourceModeChange(PacSourceMode.FILE)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(PacSourceMode.FILE, viewModel.uiState.value.pacSourceMode)
        assertEquals("", viewModel.uiState.value.proxyPacUrl)
        assertNull(viewModel.uiState.value.proxyPacUrlError)
        io.mockk.verify { proxyResolver.clearCache() }
        coVerify { settingsRepository.saveProxyConfig(any()) }
    }

    @Test
    fun `onPacSourceModeChange switches to URL and clears file path`() = runTest {
        // Set a file path
        viewModel.onPacSourceModeChange(PacSourceMode.FILE)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onProxyPacUrlChange("/data/user/0/app/files/saved-pac.pac")
        testDispatcher.scheduler.advanceUntilIdle()

        // Switch back to URL mode
        viewModel.onPacSourceModeChange(PacSourceMode.URL)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(PacSourceMode.URL, viewModel.uiState.value.pacSourceMode)
        assertEquals("", viewModel.uiState.value.proxyPacUrl)
    }
     // ── PAC file selection (copy-to-storage) ─────────────────────────────────
     // NOTE: onPacFileSelected requires Android ContentResolver APIs (Uri.parse, openInputStream).
     // These are tested via instrumented tests or cannot be unit-tested without Robolectric.
     // The copy-to-storage logic is integration-tested through the SettingsScreen composable.

     // ── Validation: FILE mode ────────────────────────────────────────────────

     @Test
    fun `validatePacUrl in FILE mode accepts internal private storage path`() {
        val vm = SettingsViewModel(settingsRepository, proxyResolver, proxyPacLogger, appContext = null)
          // Internal paths from copy-to-storage are always valid (no existence check at validate time)
        assertNull(vm.validatePacUrl("/data/user/0/app/files/saved-pac.pac", PacSourceMode.FILE))
         }

     @Test
    fun `validatePacUrl in FILE mode rejects empty path`() {
        val vm = SettingsViewModel(settingsRepository, proxyResolver, proxyPacLogger, appContext = null)
        val error = vm.validatePacUrl("/", PacSourceMode.FILE)
        assertTrue(error != null)
         }

     @Test
    fun `validatePacUrl in FILE mode accepts absolute path with special chars`() {
        val vm = SettingsViewModel(settingsRepository, proxyResolver, proxyPacLogger, appContext = null)
          // Paths with dots and hyphens are valid
        assertNull(vm.validatePacUrl("/data/user/0/app/files/my-saved.pac", PacSourceMode.FILE))
         }

     // ── Validation: URL mode (unchanged) ─────────────────────────────────────


    @Test
    fun `validatePacUrl in URL mode accepts valid HTTP URL`() {
        assertNull(viewModel.validatePacUrl("http://proxy.corp.com/proxy.pac", PacSourceMode.URL))
    }

    @Test
    fun `validatePacUrl in URL mode rejects FTP URL`() {
        val error = viewModel.validatePacUrl("ftp://proxy.corp.com/proxy.pac", PacSourceMode.URL)
        assertTrue(error != null)
    }
}
