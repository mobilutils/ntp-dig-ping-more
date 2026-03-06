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

/**
 * UI state for the DIG test screen.
 */
data class DigUiState(
    val dnsServer: String  = "",
    val fqdn: String       = "",
    val isLoading: Boolean = false,
    val result: DigResult? = null,
    /** Up to 5 most recent distinct queries, newest first. */
    val history: List<DigHistoryEntry> = emptyList(),
)

/**
 * ViewModel for the DIG test screen.
 */
class DigViewModel(
    private val repository: DigRepository = DigRepository(),
    private val historyStore: DigHistoryStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DigUiState())
    val uiState: StateFlow<DigUiState> = _uiState.asStateFlow()

    private var queryJob: Job? = null

    init {
        viewModelScope.launch {
            val saved = historyStore.historyFlow.first()
            _uiState.value = _uiState.value.copy(history = saved)
        }
    }

    fun onDnsServerChange(value: String) {
        _uiState.value = _uiState.value.copy(dnsServer = value, result = null)
    }

    fun onFqdnChange(value: String) {
        _uiState.value = _uiState.value.copy(fqdn = value, result = null)
    }

    /**
     * Launches a DNS resolution for the current [DigUiState.fqdn] through
     * [DigUiState.dnsServer].  Cancels any in-flight query first.
     */
    fun runDigQuery() {
        val server = _uiState.value.dnsServer.trim()
        val fqdn   = _uiState.value.fqdn.trim()
        if (fqdn.isBlank()) return

        queryJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true, result = null)

        queryJob = viewModelScope.launch {
            val result = repository.resolve(server, fqdn)
            _uiState.value = _uiState.value.copy(isLoading = false, result = result)
            saveHistory(server, fqdn, result)
        }
    }

    /** Populates the dns server and fqdn fields from a history entry and immediately runs a query. */
    fun selectHistoryEntry(entry: DigHistoryEntry) {
        _uiState.value = _uiState.value.copy(
            dnsServer = entry.dnsServer,
            fqdn      = entry.fqdn,
        )
        runDigQuery()
    }

    private suspend fun saveHistory(server: String, fqdn: String, result: DigResult) {
        if (fqdn.isBlank()) return
        val status = if (result is DigResult.Success) DigStatus.SUCCESS else DigStatus.FAILED
        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
        val newEntry = DigHistoryEntry(
            timestamp = timestamp,
            dnsServer = server,
            fqdn      = fqdn,
            status    = status,
        )
        // Deduplicate by (dnsServer, fqdn) pair — keep only the latest
        val updatedHistory = (listOf(newEntry) + _uiState.value.history
            .filter { it.dnsServer != server || it.fqdn != fqdn })
            .take(5)
        _uiState.value = _uiState.value.copy(history = updatedHistory)
        historyStore.save(updatedHistory)
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DigViewModel(
                        repository   = DigRepository(),
                        historyStore = DigHistoryStore(context.applicationContext),
                    ) as T
            }
    }
}
