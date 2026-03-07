package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

data class LanScannerUiState(
    /** Subnet information retrieved from the system. */
    val subnetInfo: SubnetInfo? = null,
    /** Custom start IP address for scanning. */
    val startIp: String = "",
    /** Custom end IP address for scanning. */
    val endIp: String = "",
    /** Whether a scan is currently running. */
    val isScanning: Boolean = false,
    /** Real-time progress percentage (0.0 to 1.0). */
    val progress: Float = 0f,
    /** The number of IPs checked so far. */
    val ipsChecked: Int = 0,
    /** The total number of IPs to check in the current scan. */
    val totalIpsToCheck: Int = 0,
    /** List of active devices found so far in the current scan. */
    val activeDevices: List<LanDevice> = emptyList(),
    /** History of previous scans. */
    val history: List<LanScannerHistoryEntry> = emptyList(),
    /** Last error message, if any. */
    val errorMsg: String? = null,
)

class LanScannerViewModel(
    private val repository: LanScannerRepository,
    private val historyStore: LanScannerHistoryStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LanScannerUiState())
    val uiState: StateFlow<LanScannerUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    init {
        // Load initial subnet network details
        refreshSubnetInfo()

        // Load scan history
        viewModelScope.launch {
            val savedHistory = historyStore.historyFlow.first()
            _uiState.value = _uiState.value.copy(history = savedHistory)
        }
    }

    /**
     * Re-check local network interface details
     */
    fun refreshSubnetInfo() {
        val info = repository.getLocalSubnetInfo()
        _uiState.value = _uiState.value.copy(
            subnetInfo = info,
            startIp = info?.let { repository.longToIp(it.baseIp + 1) } ?: "",
            endIp = info?.let { repository.longToIp(it.baseIp + it.numHosts) } ?: "",
            errorMsg = if (info == null) "No WiFi / Ethernet connection detected." else null
        )
    }

    fun onStartIpChange(value: String) {
        _uiState.value = _uiState.value.copy(startIp = value)
    }

    fun onEndIpChange(value: String) {
        _uiState.value = _uiState.value.copy(endIp = value)
    }

    fun startScan(isFullScan: Boolean) {
        val startStr = _uiState.value.startIp.trim()
        val endStr = _uiState.value.endIp.trim()
        
        val startLong = try { repository.ipToLong(startStr) } catch (e: Exception) { null }
        val endLong = try { repository.ipToLong(endStr) } catch (e: Exception) { null }

        if (startLong == null || endLong == null || startLong > endLong || endLong - startLong > 65535) {
            _uiState.value = _uiState.value.copy(errorMsg = "Invalid IP range or range too large (max 65535 hosts).")
            return
        }

        val subnetInfo = repository.getLocalSubnetInfo()
        _uiState.value = _uiState.value.copy(subnetInfo = subnetInfo, errorMsg = null)

        if (_uiState.value.isScanning) stopScan()

        // 1. Generate IPs to check
        val ipsToScan = if (isFullScan) {
            (startLong..endLong).map { repository.longToIp(it) }
        } else {
            listOf(
                startLong,
                startLong + 5,
                startLong + 10,
                startLong + 50,
                startLong + 100,
                endLong - 1,
                endLong
            ).filter { it in startLong..endLong }.distinct().map { repository.longToIp(it) }
        }

        _uiState.value = _uiState.value.copy(
            isScanning = true,
            progress = 0f,
            ipsChecked = 0,
            totalIpsToCheck = ipsToScan.size,
            activeDevices = emptyList()
        )

        scanJob = viewModelScope.launch(Dispatchers.IO) {
            val checkedCount = AtomicInteger(0)
            val total = ipsToScan.size

            // Partition into batches for basic concurrency without overwhelming the system
            val chunkSize = 20
            val chunks = ipsToScan.chunked(chunkSize)

            for (chunk in chunks) {
                if (!isActive) break // Canceled

                val deferredResults = chunk.map { ip ->
                    async {
                        val pingTime = repository.ping(ip)
                        if (pingTime != null) {
                            // Device is alive. Fetch MAC and Hostname.
                            val mac = repository.getMacFromArpTable(ip)
                            val fqdn = repository.resolveHostname(ip)

                            // Assume standard .1 is typically the router
                            val routerIp = subnetInfo?.let { repository.longToIp(it.baseIp + 1) }
                            
                            val device = LanDevice(
                                ip = ip,
                                mac = mac,
                                hostname = fqdn,
                                isRouter = (ip == routerIp),
                                pingMs = pingTime
                            )

                            withContext(Dispatchers.Main) {
                                // Add uniquely and sort by IP
                                val currentList = _uiState.value.activeDevices.toMutableList()
                                if (currentList.none { it.ip == ip }) {
                                    currentList.add(device)
                                    currentList.sortBy { _ -> // simpler to just re-convert to numeric for sorting later
                                        val parts = ip.split(".")
                                        if (parts.size == 4) parts[3].toIntOrNull() ?: 0 else 0
                                    }
                                    _uiState.value = _uiState.value.copy(activeDevices = currentList)
                                }
                            }
                        }

                        val done = checkedCount.incrementAndGet()
                        withContext(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(
                                ipsChecked = done,
                                progress = done.toFloat() / total
                            )
                        }
                    }
                }
                deferredResults.awaitAll() // Wait for this batch to finish
            }

            // Cleanup when fully complete or canceled
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(isScanning = false)
                saveHistory(isFullScan)
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _uiState.value = _uiState.value.copy(isScanning = false)
        // If we stopped midway, we can still record partial history
        if (_uiState.value.ipsChecked > 0) {
            viewModelScope.launch {
                saveHistory(isFullScan = _uiState.value.totalIpsToCheck > 10)
            }
        }
    }

    private suspend fun saveHistory(isFullScan: Boolean) {
        val activeCount = _uiState.value.activeDevices.size
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
        val scanTypeStr = if (isFullScan) "Full Scan" else "Quick Scan"
        val rangeStr = "${_uiState.value.startIp} - ${_uiState.value.endIp}"

        val newEntry = LanScannerHistoryEntry(
            timestamp = timestamp,
            type = scanTypeStr,
            subnet = rangeStr,
            activeHostsCount = activeCount
        )

        val updatedHistory = (listOf(newEntry) + _uiState.value.history).take(10)
        _uiState.value = _uiState.value.copy(history = updatedHistory)
        historyStore.save(updatedHistory)
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val appContext = context.applicationContext
                    return LanScannerViewModel(
                        repository = LanScannerRepository(appContext),
                        historyStore = LanScannerHistoryStore(appContext),
                    ) as T
                }
            }
    }
}
