package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ─────────────────────────────────────────────────────────────────────────────
// UI state
// ─────────────────────────────────────────────────────────────────────────────

sealed class GoogleTimeSyncUiState {
    /** No sync has been attempted yet (or state was explicitly reset). */
    data object Idle : GoogleTimeSyncUiState()

    /** A sync request is currently in-flight. */
    data object Loading : GoogleTimeSyncUiState()

    /** The sync completed successfully. */
    data class Success(val result: TimeSyncResult) : GoogleTimeSyncUiState()

    /** The sync failed for any reason. */
    data class Error(val message: String) : GoogleTimeSyncUiState()
}

/**
 * Complete UI state for the Google Time Sync screen.
 *
 * Wrapping [syncState] and [history] in a single data class lets the screen
 * observe a single StateFlow instead of two, mirroring the NTP screen pattern.
 */
data class GoogleTimeSyncScreenState(
    val syncState: GoogleTimeSyncUiState = GoogleTimeSyncUiState.Idle,
    /** Up to 5 most-recent sync results, newest-first. */
    val history: List<GoogleTimeSyncHistoryEntry> = emptyList(),
)

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ViewModel for the Google Time Sync screen.
 *
 * Survives configuration changes and cancels any in-flight request
 * automatically when [onCleared] is called.
 *
 * History is loaded from [GoogleTimeSyncHistoryStore] on construction and
 * persisted after every sync, so it survives app kill and device reboots.
 */
class GoogleTimeSyncViewModel(
    private val repository   : GoogleTimeSyncRepository    = GoogleTimeSyncRepository(),
    private val historyStore : GoogleTimeSyncHistoryStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GoogleTimeSyncScreenState())
    val uiState: StateFlow<GoogleTimeSyncScreenState> = _uiState.asStateFlow()

    private var syncJob: Job? = null

    init {
        // Restore persisted history as soon as the ViewModel is created.
        viewModelScope.launch {
            val saved = historyStore.historyFlow.first()
            _uiState.value = _uiState.value.copy(history = saved)
        }
    }

    /**
     * Launches a time-sync request against [url].
     *
     * If [url] is blank the default endpoint ([GoogleTimeSyncRepository.DEFAULT_URL]) is used.
     * Any previously running request is cancelled before the new one starts.
     *
     * @param url  Full URL to query (e.g. `http://clients2.google.com/time/1/current`).
     */
    fun syncTime(url: String) {
        val effectiveUrl = url.trim().ifBlank { GoogleTimeSyncRepository.DEFAULT_URL }

        syncJob?.cancel()
        _uiState.value = _uiState.value.copy(syncState = GoogleTimeSyncUiState.Loading)

        syncJob = viewModelScope.launch {
            val result = repository.fetchGoogleTime(effectiveUrl)

            val syncState: GoogleTimeSyncUiState
            val newEntry: GoogleTimeSyncHistoryEntry

            val timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))

            when (result) {
                is GoogleTimeSyncResult.Success -> {
                    syncState = GoogleTimeSyncUiState.Success(result.data)
                    newEntry  = GoogleTimeSyncHistoryEntry(
                        timestamp = timestamp,
                        url       = effectiveUrl,
                        offsetMs  = result.data.offsetMillis,
                        rttMs     = result.data.rttMillis,
                        success   = true,
                    )
                }
                else -> {
                    val message = when (result) {
                        is GoogleTimeSyncResult.NoNetwork  -> "No network connection"
                        is GoogleTimeSyncResult.Timeout    -> "Request timed out"
                        is GoogleTimeSyncResult.HttpError  -> "HTTP error ${result.code}"
                        is GoogleTimeSyncResult.ParseError -> "Parse error: ${result.message}"
                        is GoogleTimeSyncResult.Error      -> result.message
                        else                               -> "Unknown error"
                    }
                    syncState = GoogleTimeSyncUiState.Error(message)
                    newEntry  = GoogleTimeSyncHistoryEntry(
                        timestamp = timestamp,
                        url       = effectiveUrl,
                        offsetMs  = 0L,
                        rttMs     = 0L,
                        success   = false,
                    )
                }
            }

            // Prepend new entry; remove duplicates by URL; cap at 5.
            val updatedHistory = (listOf(newEntry) + _uiState.value.history
                .filter { it.url != effectiveUrl })
                .take(5)

            _uiState.value = _uiState.value.copy(
                syncState = syncState,
                history   = updatedHistory,
            )

            historyStore.save(updatedHistory)
        }
    }

    /**
     * Cancels any in-flight request and returns the UI to the [GoogleTimeSyncUiState.Idle] state.
     * History is preserved.
     */
    fun reset() {
        syncJob?.cancel()
        _uiState.value = _uiState.value.copy(syncState = GoogleTimeSyncUiState.Idle)
    }

    /** Populates the URL field from a history entry and immediately runs a sync. */
    fun selectHistoryEntry(entry: GoogleTimeSyncHistoryEntry, onUrlSelected: (String) -> Unit) {
        onUrlSelected(entry.url)
        syncTime(entry.url)
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {
        /** Creates a factory that supplies [GoogleTimeSyncHistoryStore] via [context]. */
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    GoogleTimeSyncViewModel(
                        repository   = GoogleTimeSyncRepository(),
                        historyStore = GoogleTimeSyncHistoryStore(context.applicationContext),
                    ) as T
            }
    }
}
