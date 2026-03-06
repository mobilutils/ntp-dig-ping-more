package io.github.mobilutils.ntp_dig_ping_more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the DIG test screen.
 */
data class DigUiState(
    val dnsServer: String  = "",
    val fqdn: String       = "",
    val isLoading: Boolean = false,
    val result: DigResult? = null,
)

/**
 * ViewModel for the DIG test screen.
 */
class DigViewModel(
    private val repository: DigRepository = DigRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(DigUiState())
    val uiState: StateFlow<DigUiState> = _uiState.asStateFlow()

    private var queryJob: Job? = null

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
        }
    }
}
