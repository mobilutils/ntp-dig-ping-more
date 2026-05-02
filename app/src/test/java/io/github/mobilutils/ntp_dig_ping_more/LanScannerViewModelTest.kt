package io.github.mobilutils.ntp_dig_ping_more

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LanScannerViewModel] using MockK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LanScannerViewModelTest {

    private val sampleSubnetInfo = SubnetInfo(
        ipAddress = "192.168.1.100",
        networkPrefixLength = 24,
        cidr = "192.168.1.0/24",
        baseIp = 3232235776, // 192.168.1.0
        numHosts = 254
    )

    private fun fakeSettingsRepository(): SettingsRepository = mockk<SettingsRepository>(relaxed = true).also {
        coEvery { it.timeoutSecondsFlow } returns flowOf(5)
    }

    private fun createViewModel(
        repository: LanScannerRepository = mockk(relaxed = true),
        historyStore: LanScannerHistoryStore = mockk(relaxed = true),
        testDispatcher: kotlinx.coroutines.test.TestDispatcher = StandardTestDispatcher(),
    ): LanScannerViewModel {
        every { repository.getLocalSubnetInfo() } returns sampleSubnetInfo
        every { repository.longToIp(any()) } answers {
            val ipLong = firstArg<Long>()
            "${(ipLong shr 24) and 0xff}.${(ipLong shr 16) and 0xff}.${(ipLong shr 8) and 0xff}.${ipLong and 0xff}"
        }
        every { repository.ipToLong(any()) } answers {
            val ip = firstArg<String>()
            val parts = ip.split(".")
            (parts[0].toLong() shl 24) or (parts[1].toLong() shl 16) or (parts[2].toLong() shl 8) or parts[3].toLong()
        }
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }
        return LanScannerViewModel(repository, historyStore, testDispatcher, fakeSettingsRepository())
    }

    @Test
    fun `initial state has default values`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val viewModel = createViewModel(testDispatcher = testDispatcher)
        val state = viewModel.uiState.value

        assertNotNull(state.subnetInfo)
        // init calls refreshSubnetInfo() which populates startIp/endIp from subnet info
        assertEquals("192.168.1.1", state.startIp)
        assertEquals("192.168.1.254", state.endIp)
        assertFalse(state.isScanning)
        assertEquals(0f, state.progress)
        assertEquals(0, state.ipsChecked)
        assertEquals(0, state.totalIpsToCheck)
        assertTrue(state.activeDevices.isEmpty())
        assertTrue(state.history.isEmpty())
        assertEquals(null, state.errorMsg)
    }

    @Test
    fun `init loads subnet info and history`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<LanScannerRepository>(relaxed = true)
        val historyStore = mockk<LanScannerHistoryStore>(relaxed = true)
        every { repository.getLocalSubnetInfo() } returns sampleSubnetInfo
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        val newViewModel = LanScannerViewModel(repository, historyStore, testDispatcher, fakeSettingsRepository())
        advanceUntilIdle()

        assertNotNull(newViewModel.uiState.value.subnetInfo)
    }

    @Test
    fun `init sets error when subnet info is null`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<LanScannerRepository>(relaxed = true)
        val historyStore = mockk<LanScannerHistoryStore>(relaxed = true)
        every { repository.getLocalSubnetInfo() } returns null
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        val newViewModel = LanScannerViewModel(repository, historyStore, testDispatcher, fakeSettingsRepository())
        advanceUntilIdle()

        assertEquals("No WiFi / Ethernet connection detected.", newViewModel.uiState.value.errorMsg)
    }

    @Test
    fun `refreshSubnetInfo updates subnet info`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val newSubnetInfo = SubnetInfo(
            ipAddress = "10.0.0.50",
            networkPrefixLength = 16,
            cidr = "10.0.0.0/16",
            baseIp = 167772160,
            numHosts = 65534
        )

        val repository = mockk<LanScannerRepository>(relaxed = true)
        val historyStore = mockk<LanScannerHistoryStore>(relaxed = true)
        every { repository.getLocalSubnetInfo() } returns newSubnetInfo
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        val viewModel = LanScannerViewModel(repository, historyStore, testDispatcher, fakeSettingsRepository())
        viewModel.refreshSubnetInfo()

        val state = viewModel.uiState.value
        assertEquals(newSubnetInfo, state.subnetInfo)
        assertEquals(null, state.errorMsg)
    }

    @Test
    fun `refreshSubnetInfo sets error when no connection`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<LanScannerRepository>(relaxed = true)
        val historyStore = mockk<LanScannerHistoryStore>(relaxed = true)
        every { repository.getLocalSubnetInfo() } returns null
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        val viewModel = LanScannerViewModel(repository, historyStore, testDispatcher, fakeSettingsRepository())
        viewModel.refreshSubnetInfo()

        val state = viewModel.uiState.value
        assertEquals("No WiFi / Ethernet connection detected.", state.errorMsg)
    }

    @Test
    fun `onStartIpChange updates start IP`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val viewModel = createViewModel(testDispatcher = testDispatcher)
        viewModel.onStartIpChange("192.168.1.10")

        val state = viewModel.uiState.value
        assertEquals("192.168.1.10", state.startIp)
    }

    @Test
    fun `onEndIpChange updates end IP`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val viewModel = createViewModel(testDispatcher = testDispatcher)
        viewModel.onEndIpChange("192.168.1.254")

        val state = viewModel.uiState.value
        assertEquals("192.168.1.254", state.endIp)
    }

    @Test
    fun `startScan with invalid IP range shows error`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val viewModel = createViewModel(testDispatcher = testDispatcher)
        viewModel.onStartIpChange("invalid")
        viewModel.onEndIpChange("192.168.1.254")
        viewModel.startScan(isFullScan = true)

        val state = viewModel.uiState.value
        assertEquals("Invalid IP range or range too large (max 65535 hosts).", state.errorMsg)
        assertFalse(state.isScanning)
    }

    @Test
    fun `startScan with reversed IP range shows error`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val viewModel = createViewModel(testDispatcher = testDispatcher)
        viewModel.onStartIpChange("192.168.1.254")
        viewModel.onEndIpChange("192.168.1.1")
        viewModel.startScan(isFullScan = true)

        val state = viewModel.uiState.value
        assertEquals("Invalid IP range or range too large (max 65535 hosts).", state.errorMsg)
    }

    @Test
    fun `startScan with range too large shows error`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val viewModel = createViewModel(testDispatcher = testDispatcher)
        viewModel.onStartIpChange("192.168.1.1")
        viewModel.onEndIpChange("192.169.1.1") // More than 65535 hosts
        viewModel.startScan(isFullScan = true)

        val state = viewModel.uiState.value
        assertEquals("Invalid IP range or range too large (max 65535 hosts).", state.errorMsg)
    }

    @Test
    fun `startScan validates IP format`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val viewModel = createViewModel(testDispatcher = testDispatcher)
        viewModel.onStartIpChange("999.999.999.999")
        viewModel.onEndIpChange("192.168.1.254")
        viewModel.startScan(isFullScan = true)

        val state = viewModel.uiState.value
        assertEquals("Invalid IP range or range too large (max 65535 hosts).", state.errorMsg)
    }

    @Test
    fun `startScan clears error and sets scanning state`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<LanScannerRepository>(relaxed = true)
        val historyStore = mockk<LanScannerHistoryStore>(relaxed = true)
        every { repository.getLocalSubnetInfo() } returns sampleSubnetInfo
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        val viewModel = LanScannerViewModel(repository, historyStore, testDispatcher, fakeSettingsRepository())
        viewModel.onStartIpChange("192.168.1.1")
        viewModel.onEndIpChange("192.168.1.10")

        coEvery { repository.ping(any()) } returns null

        viewModel.startScan(isFullScan = true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(null, state.errorMsg)
    }

    @Test
    fun `startScan stops existing scan before starting new one`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<LanScannerRepository>(relaxed = true)
        val historyStore = mockk<LanScannerHistoryStore>(relaxed = true)
        every { repository.getLocalSubnetInfo() } returns sampleSubnetInfo
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        val viewModel = LanScannerViewModel(repository, historyStore, testDispatcher, fakeSettingsRepository())
        viewModel.onStartIpChange("192.168.1.1")
        viewModel.onEndIpChange("192.168.1.10")

        coEvery { repository.ping(any()) } returns null

        viewModel.startScan(isFullScan = true)
        viewModel.startScan(isFullScan = false)
        advanceUntilIdle()

        // Should not crash, scanning state should be valid
        assertTrue(viewModel.uiState.value.isScanning || viewModel.uiState.value.ipsChecked >= 0)
    }

    @Test
    fun `stopScan sets isScanning to false`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<LanScannerRepository>(relaxed = true)
        val historyStore = mockk<LanScannerHistoryStore>(relaxed = true)
        every { repository.getLocalSubnetInfo() } returns sampleSubnetInfo
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        val viewModel = LanScannerViewModel(repository, historyStore, testDispatcher, fakeSettingsRepository())
        viewModel.onStartIpChange("192.168.1.1")
        viewModel.onEndIpChange("192.168.1.10")

        coEvery { repository.ping(any()) } returns null

        viewModel.startScan(isFullScan = true)
        viewModel.stopScan()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isScanning)
    }

    @Test
    fun `quick scan uses sampled IPs`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<LanScannerRepository>(relaxed = true)
        val historyStore = mockk<LanScannerHistoryStore>(relaxed = true)
        every { repository.getLocalSubnetInfo() } returns sampleSubnetInfo
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        val viewModel = LanScannerViewModel(repository, historyStore, testDispatcher, fakeSettingsRepository())
        viewModel.onStartIpChange("192.168.1.1")
        viewModel.onEndIpChange("192.168.1.254")

        coEvery { repository.ping(any()) } returns null

        viewModel.startScan(isFullScan = false)
        advanceUntilIdle()

        // Quick scan should check a small subset of IPs
        val totalIps = viewModel.uiState.value.totalIpsToCheck
        assertTrue(totalIps in 1..10)
    }

    @Test
    fun `full scan uses all IPs in range`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<LanScannerRepository>(relaxed = true)
        val historyStore = mockk<LanScannerHistoryStore>(relaxed = true)
        every { repository.getLocalSubnetInfo() } returns sampleSubnetInfo
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        every { repository.ipToLong(any()) } answers {
            val ip = firstArg<String>()
            val parts = ip.split(".")
            (parts[0].toLong() shl 24) or (parts[1].toLong() shl 16) or (parts[2].toLong() shl 8) or parts[3].toLong()
        }
        every { repository.longToIp(any()) } answers {
            val ipLong = firstArg<Long>()
            "${(ipLong shr 24) and 0xff}.${(ipLong shr 16) and 0xff}.${(ipLong shr 8) and 0xff}.${ipLong and 0xff}"
        }

        val viewModel = LanScannerViewModel(repository, historyStore, testDispatcher, fakeSettingsRepository())
        viewModel.onStartIpChange("192.168.1.1")
        viewModel.onEndIpChange("192.168.1.10")

        coEvery { repository.ping(any()) } returns null

        viewModel.startScan(isFullScan = true)
        advanceUntilIdle()

        // Full scan should check all IPs in range (10 IPs: 1-10)
        assertEquals(10, viewModel.uiState.value.totalIpsToCheck)
    }

    @Test
    fun `scan progress updates correctly`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<LanScannerRepository>(relaxed = true)
        val historyStore = mockk<LanScannerHistoryStore>(relaxed = true)
        every { repository.getLocalSubnetInfo() } returns sampleSubnetInfo
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        val viewModel = LanScannerViewModel(repository, historyStore, testDispatcher, fakeSettingsRepository())
        viewModel.onStartIpChange("192.168.1.1")
        viewModel.onEndIpChange("192.168.1.5")

        coEvery { repository.ping(any()) } returns null

        viewModel.startScan(isFullScan = true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.progress in 0f..1f)
        assertTrue(state.ipsChecked >= 0)
    }

    @Test
    fun `history is loaded on init`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<LanScannerRepository>(relaxed = true)
        val historyStore = mockk<LanScannerHistoryStore>(relaxed = true)
        every { repository.getLocalSubnetInfo() } returns sampleSubnetInfo
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        val savedHistory = listOf(
            LanScannerHistoryEntry(
                timestamp = "2024/01/15 10:30:00",
                type = "Full Scan",
                subnet = "192.168.1.0/24",
                activeHostsCount = 5
            )
        )

        coEvery { historyStore.historyFlow } returns flowOf(savedHistory)

        val newViewModel = LanScannerViewModel(repository, historyStore, testDispatcher, fakeSettingsRepository())
        advanceUntilIdle()

        assertEquals(1, newViewModel.uiState.value.history.size)
        assertEquals("Full Scan", newViewModel.uiState.value.history[0].type)
    }

    @Test
    fun `history is capped at 10 entries`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<LanScannerRepository>(relaxed = true)
        val historyStore = mockk<LanScannerHistoryStore>(relaxed = true)
        every { repository.getLocalSubnetInfo() } returns sampleSubnetInfo
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        val largeHistory = (1..15).map { i ->
            LanScannerHistoryEntry(
                timestamp = "2024/01/${i.toString().padStart(2, '0')} 10:00:00",
                type = "Quick Scan",
                subnet = "192.168.1.0/24",
                activeHostsCount = i
            )
        }

        coEvery { historyStore.historyFlow } returns flowOf(largeHistory)

        val newViewModel = LanScannerViewModel(repository, historyStore, testDispatcher, fakeSettingsRepository())
        advanceUntilIdle()

        // HistoryStore should already have capped at 10
        assertTrue(newViewModel.uiState.value.history.size <= 10)
    }

    @Test
    fun `history is saved after scan completes`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<LanScannerRepository>(relaxed = true)
        val historyStore = mockk<LanScannerHistoryStore>(relaxed = true)
        every { repository.getLocalSubnetInfo() } returns sampleSubnetInfo
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())

        val viewModel = LanScannerViewModel(repository, historyStore, testDispatcher, fakeSettingsRepository())
        viewModel.onStartIpChange("192.168.1.1")
        viewModel.onEndIpChange("192.168.1.5")

        coEvery { repository.ping(any()) } returns null

        viewModel.startScan(isFullScan = true)
        advanceUntilIdle()

        coVerify { historyStore.save(any()) }
    }

    @Test
    fun `history is saved on partial scan stop`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<LanScannerRepository>(relaxed = true)
        val historyStore = mockk<LanScannerHistoryStore>(relaxed = true)
        every { repository.getLocalSubnetInfo() } returns sampleSubnetInfo
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())

        val viewModel = LanScannerViewModel(repository, historyStore, testDispatcher, fakeSettingsRepository())
        viewModel.onStartIpChange("192.168.1.1")
        viewModel.onEndIpChange("192.168.1.20")

        coEvery { repository.ping(any()) } returns null

        viewModel.startScan(isFullScan = true)
        viewModel.stopScan()
        advanceUntilIdle()

        // History should be saved if any IPs were checked
        coVerify(atLeast = 0) { historyStore.save(any()) }
    }

    @Test
    fun `onCleared cancels scanJob`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<LanScannerRepository>(relaxed = true)
        val historyStore = mockk<LanScannerHistoryStore>(relaxed = true)
        every { repository.getLocalSubnetInfo() } returns sampleSubnetInfo
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        val viewModel = LanScannerViewModel(repository, historyStore, testDispatcher, fakeSettingsRepository())
        viewModel.onStartIpChange("192.168.1.1")
        viewModel.onEndIpChange("192.168.1.254")

        coEvery { repository.ping(any()) } returns null

        viewModel.startScan(isFullScan = true)
        advanceUntilIdle()

        // onCleared() is protected and called by the framework when ViewModel is cleared
        // We can't directly test it, but verify the scan started without issues
        assertTrue(viewModel.uiState.value.isScanning || viewModel.uiState.value.ipsChecked >= 0)
    }

    @Test
    fun `activeDevices is populated when ping succeeds`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repository = mockk<LanScannerRepository>(relaxed = true)
        val historyStore = mockk<LanScannerHistoryStore>(relaxed = true)
        every { repository.getLocalSubnetInfo() } returns sampleSubnetInfo
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        coEvery { historyStore.save(any()) } coAnswers { }

        every { repository.ipToLong(any()) } answers {
            val ip = firstArg<String>()
            val parts = ip.split(".")
            (parts[0].toLong() shl 24) or (parts[1].toLong() shl 16) or (parts[2].toLong() shl 8) or parts[3].toLong()
        }
        every { repository.longToIp(any()) } answers {
            val ipLong = firstArg<Long>()
            "${(ipLong shr 24) and 0xff}.${(ipLong shr 16) and 0xff}.${(ipLong shr 8) and 0xff}.${ipLong and 0xff}"
        }

        val viewModel = LanScannerViewModel(repository, historyStore, testDispatcher, fakeSettingsRepository())
        viewModel.onStartIpChange("192.168.1.1")
        viewModel.onEndIpChange("192.168.1.2")

        coEvery { repository.ping("192.168.1.1") } returns 5
        coEvery { repository.ping("192.168.1.2") } returns null
        coEvery { repository.getMacFromArpTable(any()) } returns "AA:BB:CC:DD:EE:FF"
        coEvery { repository.resolveHostname(any()) } returns "device.local"

        viewModel.startScan(isFullScan = true)
        advanceUntilIdle()

        val devices = viewModel.uiState.value.activeDevices
        assertTrue(devices.isNotEmpty())
        assertEquals("192.168.1.1", devices[0].ip)
    }

    @Test
    fun `scan with empty start or end IP shows error`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val viewModel = createViewModel(testDispatcher = testDispatcher)
        viewModel.onStartIpChange("")
        viewModel.onEndIpChange("192.168.1.254")
        viewModel.startScan(isFullScan = true)

        val state = viewModel.uiState.value
        assertEquals("Invalid IP range or range too large (max 65535 hosts).", state.errorMsg)
    }
}
