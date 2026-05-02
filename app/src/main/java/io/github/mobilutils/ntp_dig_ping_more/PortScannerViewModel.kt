package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.mobilutils.ntp_dig_ping_more.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class PortScannerUiState(
    val host: String = "",
    val startPort: String = "1",
    val endPort: String = "1024",
    val protocol: PortScannerProtocol = PortScannerProtocol.TCP,
    val isRunning: Boolean = false,
    val progress: Float = 0f,
    val discoveredPorts: List<Int> = emptyList(),
    val history: List<PortScannerHistoryEntry> = emptyList(),
)

class PortScannerViewModel(
    private val historyStore: PortScannerHistoryStore,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PortScannerUiState())
    val uiState: StateFlow<PortScannerUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            val saved = historyStore.historyFlow.first()
            _uiState.value = _uiState.value.copy(history = saved)
        }
    }

    fun onHostChange(value: String) {
        _uiState.value = _uiState.value.copy(host = value)
    }

    fun onStartPortChange(value: String) {
        _uiState.value = _uiState.value.copy(startPort = value)
    }

    fun onEndPortChange(value: String) {
        _uiState.value = _uiState.value.copy(endPort = value)
    }

    fun onProtocolChange(protocol: PortScannerProtocol) {
        _uiState.value = _uiState.value.copy(protocol = protocol)
    }

    fun startScan() {
        val host = _uiState.value.host.trim()
        val startPort = _uiState.value.startPort.toIntOrNull() ?: 0
        val endPort = _uiState.value.endPort.toIntOrNull() ?: 0
        val protocol = _uiState.value.protocol

        if (host.isBlank() || startPort < 1 || endPort > 65535 || startPort > endPort || _uiState.value.isRunning) {
            return
        }

        _uiState.value = _uiState.value.copy(
            isRunning = true,
            progress = 0f,
            discoveredPorts = emptyList(),
        )

        scanJob = viewModelScope.launch(Dispatchers.IO) {
            val timeoutMs = settingsRepository.timeoutSecondsFlow.first() * 1000L
            try {
                withTimeout(timeoutMs) {
                    val totalPorts = endPort - startPort + 1
                    var scannedCount = 0
                    val discovered = mutableListOf<Int>()
                    val mutex = Mutex()

                    val portsToScan = (startPort..endPort).toList()

                    // Limit concurrency to prevent OutOfMemory and Too Many Open Files errors
                    val concurrencyLimit = 50
                    val chunks = portsToScan.chunked(concurrencyLimit)

                    for (chunk in chunks) {
                        if (!isActive) break

                        val deferreds = chunk.map { port ->
                            async {
                                val isOpen = if (protocol == PortScannerProtocol.TCP) {
                                    checkTcpPort(host, port)
                                } else {
                                    checkUdpPort(host, port)
                                }

                                mutex.withLock {
                                    scannedCount++
                                    if (isOpen) {
                                        discovered.add(port)
                                        discovered.sort()
                                    }

                                    val currentProgress = scannedCount.toFloat() / totalPorts
                                    withContext(Dispatchers.Main) {
                                        _uiState.value = _uiState.value.copy(
                                            progress = currentProgress,
                                            discoveredPorts = discovered.toList()
                                        )
                                    }
                                }
                            }
                        }
                        deferreds.awaitAll()
                    }

                    // Once scan completes
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(isRunning = false, progress = 1f)
                        saveHistory(host, startPort.toString(), endPort.toString(), protocol)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isRunning = false)
                    saveHistory(host, startPort.toString(), endPort.toString(), protocol)
                }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _uiState.value = _uiState.value.copy(isRunning = false)
        viewModelScope.launch {
            val s = _uiState.value
            saveHistory(s.host.trim(), s.startPort, s.endPort, s.protocol)
        }
    }

    fun selectHistoryEntry(entry: PortScannerHistoryEntry) {
        if (_uiState.value.isRunning) stopScan()
        _uiState.value = _uiState.value.copy(
            host = entry.host,
            startPort = entry.startPort,
            endPort = entry.endPort,
            protocol = entry.protocol
        )
        startScan()
    }

    private fun checkTcpPort(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1000)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun checkUdpPort(host: String, port: Int): Boolean {
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 1000 // 1 second timeout
                socket.connect(InetSocketAddress(host, port))
                val sendData = ByteArray(0)
                val sendPacket = DatagramPacket(sendData, sendData.size)
                socket.send(sendPacket)

                val receiveData = ByteArray(1024)
                val receivePacket = DatagramPacket(receiveData, receiveData.size)
                socket.receive(receivePacket)
                true // got a response
            }
        } catch (e: SocketTimeoutException) {
            // No response. Could be open or filtered.
            false
        } catch (e: Exception) {
            // PortUnreachableException or other errors
            false
        }
    }

    private suspend fun saveHistory(host: String, startPort: String, endPort: String, protocol: PortScannerProtocol) {
        if (host.isBlank()) return
        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
        val newEntry = PortScannerHistoryEntry(
            timestamp = timestamp,
            host = host,
            startPort = startPort,
            endPort = endPort,
            protocol = protocol
        )
        val updatedHistory = (listOf(newEntry) + _uiState.value.history
            .filter { it.host != host || it.protocol != protocol || it.startPort != startPort || it.endPort != endPort })
            .take(5)
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
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PortScannerViewModel(
                        historyStore = PortScannerHistoryStore(context.applicationContext),
                        settingsRepository = SettingsRepository(context.applicationContext),
                    ) as T
            }
    }
}
