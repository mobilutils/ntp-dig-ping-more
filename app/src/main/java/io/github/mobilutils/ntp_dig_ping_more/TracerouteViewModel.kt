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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * UI state for the Traceroute screen.
 */
data class TracerouteUiState(
    val host: String = "",
    val isRunning: Boolean = false,
    val outputLines: List<String> = emptyList(),
    val history: List<TracerouteHistoryEntry> = emptyList(),
)

/**
 * ViewModel for the Traceroute screen.
 *
 * Since Android devices typically don't have a `traceroute` binary, this
 * implements traceroute by probing with `ping -c 1 -t <TTL> -W 2 <host>`
 * for TTL = 1, 2, … up to 30.  For each TTL:
 *
 *  - An intermediate router receiving a packet with TTL=1 sends back an
 *    ICMP "Time to live exceeded" — ping prints "From <router-ip> … Time to
 *    live exceeded".  We capture that IP as the hop.
 *  - If the destination itself replies, we've reached the end.
 *  - If neither, the hop timed out ("* * *").
 *
 * Status logic:
 *  - ALL_SUCCESS → destination reached (at least one hop replied)
 *  - PARTIAL     → some hops replied but destination not reached / stopped early
 *  - ALL_FAILED  → no hop replied at all
 */
class TracerouteViewModel(
    private val historyStore: TracerouteHistoryStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TracerouteUiState())
    val uiState: StateFlow<TracerouteUiState> = _uiState.asStateFlow()

    private var traceJob: Job? = null

    // "From 192.168.1.1 icmp_seq=…  Time to live exceeded"
    private val ttlExceededIpRegex = Regex("""From (\S+).*[Tt]ime to live exceeded""")
    // "64 bytes from 142.251.12.100:"
    private val destinationReplyRegex = Regex("""bytes from (\S+):""")
    // Alternative: some Android pings say "From x.x.x.x: ..."
    private val ttlExceededAltRegex = Regex("""From (\S+):.*ttl""", RegexOption.IGNORE_CASE)

    init {
        viewModelScope.launch {
            val saved = historyStore.historyFlow.first()
            _uiState.value = _uiState.value.copy(history = saved)
        }
    }

    fun onHostChange(value: String) {
        _uiState.value = _uiState.value.copy(host = value)
    }

    fun startTraceroute() {
        val host = _uiState.value.host.trim()
        if (host.isBlank() || _uiState.value.isRunning) return

        _uiState.value = _uiState.value.copy(
            isRunning   = true,
            outputLines = emptyList(),
        )

        traceJob = viewModelScope.launch {
            appendLine("traceroute to $host, 30 hops max")

            var reachableHops = 0
            var destinationReached = false

            for (ttl in 1..30) {
                if (!isActive) break

                val hopResult = withContext(Dispatchers.IO) { probeHop(host, ttl) }
                appendLine(hopResult.displayLine)

                if (hopResult.isReachable) reachableHops++
                if (hopResult.isDestination) {
                    destinationReached = true
                    break
                }
            }

            val status = when {
                reachableHops == 0 -> TracerouteStatus.ALL_FAILED
                destinationReached -> TracerouteStatus.ALL_SUCCESS
                else               -> TracerouteStatus.PARTIAL
            }

            val wasRunning = _uiState.value.isRunning
            _uiState.value = _uiState.value.copy(isRunning = false)
            if (wasRunning) saveHistory(host, status)
        }
    }

    fun stopTraceroute() {
        traceJob?.cancel()
        val lines    = _uiState.value.outputLines
        val hopLines = lines.filter { it.trimStart().firstOrNull()?.isDigit() == true }
        val timeouts = hopLines.count { "* * *" in it }
        val replied  = hopLines.size - timeouts
        val status = when {
            replied == 0  -> TracerouteStatus.ALL_FAILED
            timeouts == 0 -> TracerouteStatus.ALL_SUCCESS
            else          -> TracerouteStatus.PARTIAL
        }
        _uiState.value = _uiState.value.copy(isRunning = false)
        viewModelScope.launch { saveHistory(_uiState.value.host.trim(), status) }
    }

    fun selectHistoryEntry(entry: TracerouteHistoryEntry) {
        if (_uiState.value.isRunning) stopTraceroute()
        _uiState.value = _uiState.value.copy(host = entry.host)
        startTraceroute()
    }

    // ── Hop probing ───────────────────────────────────────────────────────────

    private data class HopResult(
        val displayLine: String,
        val isReachable: Boolean,
        val isDestination: Boolean,
    )

    /**
     * Probes a single hop at [ttl] by running `ping -c 1 -t <ttl> -W 2 <host>`.
     * Blocks the calling IO thread until the ping finishes (≤ 2 s per hop).
     */
    private fun probeHop(host: String, ttl: Int): HopResult {
        val paddedTtl = ttl.toString().padStart(2)
        return try {
            val t0 = System.currentTimeMillis()
            val process = Runtime.getRuntime().exec(
                arrayOf("ping", "-c", "1", "-t", ttl.toString(), "-W", "2", host)
            )
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            process.waitFor()
            val elapsed  = System.currentTimeMillis() - t0
            val combined = stdout + "\n" + stderr

            when {
                // Intermediate hop replied with ICMP Time Exceeded
                ttlExceededIpRegex.containsMatchIn(combined) -> {
                    val ip = ttlExceededIpRegex.find(combined)!!.groupValues[1].trimEnd(':')
                    HopResult("$paddedTtl  $ip  ${elapsed} ms",
                        isReachable = true, isDestination = false)
                }
                // Destination itself replied
                destinationReplyRegex.containsMatchIn(combined) -> {
                    val ip = destinationReplyRegex.find(combined)!!.groupValues[1].trimEnd(':')
                    val rtt = Regex("""time[=<]([\d.]+) ms""").find(combined)
                        ?.groupValues?.get(1)?.let { "$it ms" } ?: "${elapsed} ms"
                    HopResult("$paddedTtl  $ip  $rtt",
                        isReachable = true, isDestination = true)
                }
                // Alt Android ping "From x.x.x.x: … ttl …" format
                ttlExceededAltRegex.containsMatchIn(combined) -> {
                    val ip = ttlExceededAltRegex.find(combined)!!.groupValues[1].trimEnd(':')
                    HopResult("$paddedTtl  $ip  ${elapsed} ms",
                        isReachable = true, isDestination = false)
                }
                // Timeout — no ICMP reply within 2 s
                else -> HopResult("$paddedTtl  * * *",
                    isReachable = false, isDestination = false)
            }
        } catch (_: Exception) {
            val paddedTtl2 = ttl.toString().padStart(2)
            HopResult("$paddedTtl2  * * *", isReachable = false, isDestination = false)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun appendLine(line: String) {
        _uiState.value = _uiState.value.copy(
            outputLines = _uiState.value.outputLines + line,
        )
    }

    private suspend fun saveHistory(host: String, status: TracerouteStatus) {
        if (host.isBlank()) return
        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
        val newEntry = TracerouteHistoryEntry(
            timestamp = timestamp,
            host      = host,
            status    = status,
        )
        val updatedHistory = (listOf(newEntry) + _uiState.value.history
            .filter { it.host != host })
            .take(5)
        _uiState.value = _uiState.value.copy(history = updatedHistory)
        historyStore.save(updatedHistory)
    }

    override fun onCleared() {
        super.onCleared()
        traceJob?.cancel()
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TracerouteViewModel(
                        historyStore = TracerouteHistoryStore(context.applicationContext),
                    ) as T
            }
    }
}
