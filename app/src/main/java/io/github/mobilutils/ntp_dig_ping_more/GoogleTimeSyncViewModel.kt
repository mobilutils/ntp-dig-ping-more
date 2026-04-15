package io.github.mobilutils.ntp_dig_ping_more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// UI state
// ─────────────────────────────────────────────────────────────────────────────

sealed class GoogleTimeSyncUiState {
    /** No sync has been attempted yet (or state was reset). */
    data object Idle : GoogleTimeSyncUiState()

    /** A sync request is currently in-flight. */
    data object Loading : GoogleTimeSyncUiState()

    /** The sync completed successfully. */
    data class Success(val result: TimeSyncResult) : GoogleTimeSyncUiState()

    /** The sync failed for any reason. */
    data class Error(val message: String) : GoogleTimeSyncUiState()
}

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ViewModel for the Google Time Sync screen.
 *
 * Survives configuration changes and cancels any in-flight request
 * automatically when [onCleared] is called.
 */
class GoogleTimeSyncViewModel(
    private val repository: GoogleTimeSyncRepository = GoogleTimeSyncRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow<GoogleTimeSyncUiState>(GoogleTimeSyncUiState.Idle)
    val uiState: StateFlow<GoogleTimeSyncUiState> = _uiState.asStateFlow()

    private var syncJob: Job? = null

    /**
     * Launches a time-sync request against [host].
     *
     * Any previously running request is cancelled before the new one starts.
     *
     * @param host  Hostname to query (e.g. `clients2.google.com`).
     */
    fun syncTime(host: String) {
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty()) return

        syncJob?.cancel()
        _uiState.value = GoogleTimeSyncUiState.Loading

        syncJob = viewModelScope.launch {
            val result = repository.fetchGoogleTime(trimmedHost)
            _uiState.value = when (result) {
                is GoogleTimeSyncResult.Success    -> GoogleTimeSyncUiState.Success(result.data)
                is GoogleTimeSyncResult.NoNetwork  -> GoogleTimeSyncUiState.Error("No network connection")
                is GoogleTimeSyncResult.Timeout    -> GoogleTimeSyncUiState.Error("Request timed out (host: ${result.host})")
                is GoogleTimeSyncResult.HttpError  -> GoogleTimeSyncUiState.Error("HTTP error ${result.code} from ${result.host}")
                is GoogleTimeSyncResult.ParseError -> GoogleTimeSyncUiState.Error("Parse error: ${result.message}")
                is GoogleTimeSyncResult.Error      -> GoogleTimeSyncUiState.Error(result.message)
            }
        }
    }

    /**
     * Cancels any in-flight request and returns the UI to the [GoogleTimeSyncUiState.Idle] state.
     */
    fun reset() {
        syncJob?.cancel()
        _uiState.value = GoogleTimeSyncUiState.Idle
    }
}
