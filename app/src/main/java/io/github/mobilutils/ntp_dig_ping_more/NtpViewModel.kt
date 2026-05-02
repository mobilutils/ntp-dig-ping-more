package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.mobilutils.ntp_dig_ping_more.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A single entry in the query history list. */
data class NtpHistoryEntry(
    val timestamp: String,   // "yyyy/MM/dd HH:mm:ss"
    val server: String,
    val port: Int,
    val success: Boolean,
)

/**
 * Represents the complete UI state for the NTP checker screen.
 */
data class NtpUiState(
    /** Text field contents. */
    val serverAddress: String = "pool.ntp.org",
    /** NTP port (default 123). */
    val port: String = "123",
    /** Whether a network request is currently in progress. */
    val isLoading: Boolean = false,
    /** The result of the last check, or null if no check has been run yet. */
    val result: NtpResult? = null,
    /** Up to 5 most recent distinct (server, port) queries, newest first. */
    val history: List<NtpHistoryEntry> = emptyList(),
)

/**
 * ViewModel for the NTP checker.
 *
 * Survives configuration changes (rotation, etc.) and automatically cancels
 * any in-flight coroutine when [onCleared] is called.
 *
 * History is loaded from [NtpHistoryStore] on construction and saved after
 * every query, making it survive app kill and device reboots.
 */
class SimpleNtpViewModel(
    private val repository: NtpRepository = NtpRepository(),
    private val historyStore: NtpHistoryStore,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NtpUiState())
    val uiState: StateFlow<NtpUiState> = _uiState.asStateFlow()

    /** Reference to the currently running check job so we can cancel it. */
    private var checkJob: Job? = null

    init {
        // Restore persisted history as soon as the ViewModel is created.
        viewModelScope.launch {
            val savedHistory = historyStore.historyFlow.first()
            _uiState.value = _uiState.value.copy(history = savedHistory)
        }
    }

    /** Called whenever the user edits the server address text field. */
    fun onServerAddressChange(newValue: String) {
        _uiState.value = _uiState.value.copy(serverAddress = newValue, result = null)
    }

    /** Called whenever the user edits the port text field. */
    fun onPortChange(newValue: String) {
        // Accept only digits and cap at 5 chars (max port 65535).
        val filtered = newValue.filter { it.isDigit() }.take(5)
        _uiState.value = _uiState.value.copy(port = filtered, result = null)
    }

    /**
     * Starts an NTP reachability check against the current [NtpUiState.serverAddress].
     *
     * If a check is already running it is cancelled before the new one starts.
     */
    fun checkReachability() {
        val host = _uiState.value.serverAddress.trim()
        if (host.isEmpty()) return
        val port = _uiState.value.port.toIntOrNull()?.takeIf { it in 1..65535 } ?: 123

        // Cancel any previous in-flight request.
        checkJob?.cancel()

        _uiState.value = _uiState.value.copy(isLoading = true, result = null)

        checkJob = viewModelScope.launch {
            val timeoutMs = settingsRepository.timeoutSecondsFlow.first() * 1000L
            val result = try {
                withTimeout(timeoutMs) {
                    repository.query(host, port)
                }
            } catch (_: TimeoutCancellationException) {
                NtpResult.Timeout(host)
            }

            // Record history AFTER the result is known so we can stamp success/failure.
            val timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
            val newEntry = NtpHistoryEntry(
                timestamp = timestamp,
                server = host,
                port = port,
                success = result is NtpResult.Success,
            )
            val updatedHistory = (listOf(newEntry) + _uiState.value.history
                .filter { it.server != host || it.port != port })
                .take(5)

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                result = result,
                history = updatedHistory,
            )

            // Persist the updated history.
            historyStore.save(updatedHistory)
        }
    }

    /** Populates server/port fields from a history entry and immediately runs a check. */
    fun selectHistoryEntry(entry: NtpHistoryEntry) {
        _uiState.value = _uiState.value.copy(
            serverAddress = entry.server,
            port = entry.port.toString(),
            result = null,
        )
        checkReachability()
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {
        /** Creates a factory that supplies [NtpHistoryStore] via [context]. */
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SimpleNtpViewModel(
                        repository = NtpRepository(),
                        historyStore = NtpHistoryStore(context.applicationContext),
                        settingsRepository = SettingsRepository(context.applicationContext),
                    ) as T
            }
    }
}
