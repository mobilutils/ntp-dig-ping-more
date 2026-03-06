package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * UI state for the Ping screen.
 */
data class PingUiState(
    /** Text field content. */
    val host: String = "",
    /** Whether a ping is currently running. */
    val isRunning: Boolean = false,
    /** Accumulated output lines from the ping process. */
    val outputLines: List<String> = emptyList(),
    /** Up to 5 most recent distinct host pings, newest first. */
    val history: List<PingHistoryEntry> = emptyList(),
)

/**
 * ViewModel for the Ping screen.
 *
 * Launches `ping` as a native subprocess and streams its stdout line-by-line
 * into [PingUiState.outputLines].  The process can be stopped at any time via
 * [stopPing], which also saves the run to the history.
 *
 * Status logic (from collected output lines):
 *  - ALL_SUCCESS  → every sent packet got a reply (received == sent > 0)
 *  - PARTIAL      → at least one reply, but some were lost (received < sent)
 *  - ALL_FAILED   → no reply received at all
 *
 * "sent" is estimated from the highest icmp_seq= number seen.
 * "received" is the count of "bytes from" reply lines.
 */
class PingViewModel(
    private val historyStore: PingHistoryStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PingUiState())
    val uiState: StateFlow<PingUiState> = _uiState.asStateFlow()

    private var pingJob: Job? = null
    private var pingProcess: Process? = null

    // Regex to extract the icmp_seq number from a reply or timeout line
    private val icmpSeqRegex = Regex("""icmp_seq=(\d+)""")

    init {
        viewModelScope.launch {
            val saved = historyStore.historyFlow.first()
            _uiState.value = _uiState.value.copy(history = saved)
        }
    }

    fun onHostChange(value: String) {
        _uiState.value = _uiState.value.copy(host = value)
    }

    /** Starts pinging [PingUiState.host]. No-op if already running. */
    fun startPing() {
        val host = _uiState.value.host.trim()
        if (host.isBlank() || _uiState.value.isRunning) return

        _uiState.value = _uiState.value.copy(
            isRunning = true,
            outputLines = emptyList(),
        )

        pingJob = viewModelScope.launch {
            try {
                val process = withContext(Dispatchers.IO) {
                    Runtime.getRuntime().exec(arrayOf("ping", "-c", "100", host))
                }
                pingProcess = process

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                withContext(Dispatchers.IO) {
                    var line: String? = null
                    while (isActive && reader.readLine().also { line = it } != null) {
                        val trimmed = line!!
                        withContext(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(
                                outputLines = _uiState.value.outputLines + trimmed,
                            )
                        }
                    }
                }
                process.waitFor()
            } catch (_: Exception) {
                // Interrupted or cancelled – treat as stopped
            } finally {
                pingProcess?.destroy()
                pingProcess = null

                val wasRunning = _uiState.value.isRunning
                val status = computeStatus(_uiState.value.outputLines)
                _uiState.value = _uiState.value.copy(isRunning = false)

                if (wasRunning) {
                    saveHistory(host, status)
                }
            }
        }
    }

    /** Stops the currently running ping and saves a history entry. */
    fun stopPing() {
        pingProcess?.destroy()
        pingJob?.cancel()
        pingProcess = null

        val host = _uiState.value.host.trim()
        val status = computeStatus(_uiState.value.outputLines)

        _uiState.value = _uiState.value.copy(isRunning = false)
        viewModelScope.launch { saveHistory(host, status) }
    }

    /** Populates the host field from a history entry and immediately starts a ping. */
    fun selectHistoryEntry(entry: PingHistoryEntry) {
        if (_uiState.value.isRunning) stopPing()
        _uiState.value = _uiState.value.copy(host = entry.host)
        startPing()
    }

    /**
     * Derives a [PingStatus] from the collected output lines.
     *
     * - sent  = highest icmp_seq number seen across all lines (proxy for packets sent)
     * - received = number of lines containing "bytes from" (each successful reply)
     *
     * ✅ ALL_SUCCESS  : received == sent > 0
     * 🤷 PARTIAL      : 0 < received < sent
     * ❌ ALL_FAILED   : received == 0
     */
    private fun computeStatus(lines: List<String>): PingStatus {
        val received = lines.count { it.contains("bytes from") }
        // Highest icmp_seq seen — present in both reply ("bytes from") and
        // "Request timeout" lines, giving a good estimate of sent count.
        val maxSeq = lines.mapNotNull { line ->
            icmpSeqRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 0

        // If no icmp_seq seen at all, fall back to received count as "sent"
        val sent = if (maxSeq > 0) maxSeq else received

        return when {
            received == 0        -> PingStatus.ALL_FAILED
            received >= sent     -> PingStatus.ALL_SUCCESS
            else                 -> PingStatus.PARTIAL
        }
    }

    private suspend fun saveHistory(host: String, status: PingStatus) {
        if (host.isBlank()) return
        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
        val newEntry = PingHistoryEntry(timestamp = timestamp, host = host, status = status)
        val updatedHistory = (listOf(newEntry) + _uiState.value.history
            .filter { it.host != host })
            .take(5)
        _uiState.value = _uiState.value.copy(history = updatedHistory)
        historyStore.save(updatedHistory)
    }

    override fun onCleared() {
        super.onCleared()
        pingProcess?.destroy()
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PingViewModel(
                        historyStore = PingHistoryStore(context.applicationContext),
                    ) as T
            }
    }
}
