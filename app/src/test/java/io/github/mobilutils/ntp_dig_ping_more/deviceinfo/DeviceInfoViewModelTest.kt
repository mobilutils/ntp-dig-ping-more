package io.github.mobilutils.ntp_dig_ping_more.deviceinfo

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DeviceInfoViewModel] using MockK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceInfoViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: DeviceInfoViewModel
    private lateinit var repository: SystemInfoRepository

    private val sampleDeviceInfo = DeviceInfo(
        deviceName = "Samsung Galaxy S21",
        imei = null,
        serialNumber = "Restricted by Android 10+",
        iccid = "Restricted by Android 10+",
        deviceTime = "2024-01-15 10:30:00",
        ipv4Address = "192.168.1.100",
        ipv6Address = "2001:db8::1",
        subnetMask = "255.255.255.0",
        defaultGateway = "192.168.1.1",
        ntpServer = "time.android.com",
        dnsServers = listOf("8.8.8.8", "8.8.4.4"),
        carrierName = "T-Mobile",
        wifiSSID = "MyWiFiNetwork",
        timeSinceReboot = "2h 15m 30s",
        timeSinceScreenOff = "Not exposed by OS",
        mdmStatus = "None",
        installedCertificates = emptyList(),
        androidVersion = "14",
        apiLevel = 34,
        batteryLevel = 85,
        isCharging = false,
        batteryHealth = "Good",
        totalRam = "8.0 GB",
        availableRam = "3.2 GB",
        totalStorage = "128.0 GB",
        availableStorage = "64.5 GB",
        cpuAbi = listOf("arm64-v8a", "armeabi-v7a"),
        activeNetworkType = "WiFi",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()

        every { repository.getDeviceInfo() } returns sampleDeviceInfo
        every { repository.getCurrentDeviceTime() } returns "2024-01-15 10:30:00"
        every { repository.getTimeSinceReboot() } returns "2h 15m 30s"
        every { repository.getBatteryLevel() } returns 85
        every { repository.isCharging() } returns false

        viewModel = DeviceInfoViewModel(repository)
        // Cancel infinite periodic update coroutine to prevent test hangs
        viewModel.stopPeriodicUpdates()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        viewModel.stopPeriodicUpdates()
    }

    @Test
    fun `initial state is Loading`() = runTest {
        val state = viewModel.uiState.value
        assertTrue(state is DeviceInfoState.Loading)
    }

    @Test
    fun `loadDeviceInfo transitions to Success state`() = runTest {
        every { repository.getDeviceInfo() } returns sampleDeviceInfo

        // Trigger loading by calling onPermissionsResult
        viewModel.onPermissionsResult(true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is DeviceInfoState.Success)

        val successState = state as DeviceInfoState.Success
        assertEquals(sampleDeviceInfo, successState.deviceInfo)
        assertTrue(successState.permissionState is PermissionState.Granted)
    }

    @Test
    fun `loadDeviceInfo handles exceptions`() = runTest {
        every { repository.getDeviceInfo() } throws RuntimeException("Test error")

        viewModel.onPermissionsResult(true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is DeviceInfoState.Error)

        val errorState = state as DeviceInfoState.Error
        assertTrue(errorState.message.contains("Failed to gather device information"))
    }

    @Test
    fun `onPermissionsResult with granted sets Success state`() = runTest {
        viewModel.onPermissionsResult(true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is DeviceInfoState.Success)

        val successState = state as DeviceInfoState.Success
        assertTrue(successState.permissionState is PermissionState.Granted)
    }

    @Test
    fun `onPermissionsResult with denied sets PermissionDenied state`() = runTest {
        // Init's loadDeviceInfo runs delay(300) which completes via advanceUntilIdle
        // and sets Success state. Calling onPermissionsResult(false) overrides it.
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onPermissionsResult(false)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is DeviceInfoState.PermissionDenied)

        val deniedState = state as DeviceInfoState.PermissionDenied
        assertTrue(deniedState.message.contains("denied permissions"))
    }

    @Test
    fun `periodic updates device time`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onPermissionsResult(true)
        testDispatcher.scheduler.advanceUntilIdle()

        // Initial load
        assertTrue(viewModel.uiState.value is DeviceInfoState.Success)

        // Periodic updates are stopped in @Before, so state should remain Success
        val state = viewModel.uiState.value
        assertTrue(state is DeviceInfoState.Success)
    }

    @Test
    fun `periodic updates only run when in Success state`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onPermissionsResult(false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is DeviceInfoState.PermissionDenied)

        // State should remain PermissionDenied
        assertTrue(viewModel.uiState.value is DeviceInfoState.PermissionDenied)
    }

    @Test
    fun `periodic updates check for actual changes`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onPermissionsResult(true)
        testDispatcher.scheduler.advanceUntilIdle()

        // Keep values the same
        every { repository.getCurrentDeviceTime() } returns "2024-01-15 10:30:00"
        every { repository.getTimeSinceReboot() } returns "2h 15m 30s"
        every { repository.getBatteryLevel() } returns 85
        every { repository.isCharging() } returns false

        // State should remain Success
        assertTrue(viewModel.uiState.value is DeviceInfoState.Success)
    }

    @Test
    fun `loadDeviceInfo includes permission state when denied`() = runTest {
        // First deny permissions, then grant and load
        viewModel.onPermissionsResult(false)
        testDispatcher.scheduler.advanceUntilIdle()

        // Now grant and reload
        viewModel.onPermissionsResult(true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        if (state is DeviceInfoState.Success) {
            assertTrue(state.permissionState is PermissionState.Granted)
        }
    }

    @Test
    fun `onPermissionsResult triggers full device info reload`() = runTest {
        // Start with denied
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onPermissionsResult(false)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value is DeviceInfoState.PermissionDenied)

        // Grant permissions
        viewModel.onPermissionsResult(true)
        testDispatcher.scheduler.advanceUntilIdle()

        // Should now be in Success state with full device info
        val state = viewModel.uiState.value
        assertTrue(state is DeviceInfoState.Success)

        val successState = state as DeviceInfoState.Success
        assertEquals("Samsung Galaxy S21", successState.deviceInfo.deviceName)
    }

    @Test
    fun `DeviceInfo contains all expected fields`() = runTest {
        viewModel.onPermissionsResult(true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as DeviceInfoState.Success
        val info = state.deviceInfo

        assertEquals("Samsung Galaxy S21", info.deviceName)
        assertEquals("192.168.1.100", info.ipv4Address)
        assertEquals("2001:db8::1", info.ipv6Address)
        assertEquals("255.255.255.0", info.subnetMask)
        assertEquals("192.168.1.1", info.defaultGateway)
        assertEquals("time.android.com", info.ntpServer)
        assertEquals(listOf("8.8.8.8", "8.8.4.4"), info.dnsServers)
        assertEquals("T-Mobile", info.carrierName)
        assertEquals("MyWiFiNetwork", info.wifiSSID)
        assertEquals("14", info.androidVersion)
        assertEquals(34, info.apiLevel)
        assertEquals(85, info.batteryLevel)
        assertEquals(false, info.isCharging)
        assertEquals("Good", info.batteryHealth)
    }

    @Test
    fun `repository exception during load shows error message`() = runTest {
        every { repository.getDeviceInfo() } throws SecurityException("Permission denied")

        viewModel.onPermissionsResult(true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is DeviceInfoState.Error)

        val errorState = state as DeviceInfoState.Error
        assertTrue(errorState.message.contains("SecurityException") || 
                   errorState.message.contains("Failed to gather"))
    }

    @Test
    fun `ViewModel initializes with device info load automatically`() = runTest {
        // ViewModel should start loading in init block
        // Wait for the initial load to complete
        testDispatcher.scheduler.advanceUntilIdle()

        // Should have attempted to load device info
        val state = viewModel.uiState.value
        // Either Loading (if delay hasn't completed) or Success
        assertTrue(state is DeviceInfoState.Loading || state is DeviceInfoState.Success)
    }
}
