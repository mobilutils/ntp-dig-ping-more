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

sealed class HttpsCertUiState {
    /** Initial state — no lookup has been attempted. */
    data object Idle : HttpsCertUiState()

    /** A TLS handshake is in progress. */
    data object Loading : HttpsCertUiState()

    /** Handshake succeeded and the chain is fully trusted. */
    data class Success(val info: CertificateInfo) : HttpsCertUiState()

    /**
     * The certificate was extracted but the chain has a trust issue (expired
     * or untrusted CA). The cert data is still shown with a warning banner.
     */
    data class PartialSuccess(
        val info: CertificateInfo,
        val warningMessage: String,
    ) : HttpsCertUiState()

    /** A hard failure — no certificate data to display. */
    data class Error(val message: String) : HttpsCertUiState()
}

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ViewModel for the HTTPS Certificate Inspector screen.
 *
 * Survives configuration changes; any in-flight request is automatically
 * cancelled when [onCleared] is called.
 */
class HttpsCertViewModel(
    private val repository:    HttpsCertRepository,
    private val historyStore:  HttpsCertHistoryStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HttpsCertUiState>(HttpsCertUiState.Idle)
    val uiState: StateFlow<HttpsCertUiState> = _uiState.asStateFlow()

    private val _history = MutableStateFlow<List<HttpsCertHistoryEntry>>(emptyList())
    val history: StateFlow<List<HttpsCertHistoryEntry>> = _history.asStateFlow()

    private var fetchJob: Job? = null

    // ── Input fields (kept in the VM so they survive rotation) ───────────────

    private val _host = MutableStateFlow("google.com")
    val host: StateFlow<String> = _host.asStateFlow()

    private val _port = MutableStateFlow("443")
    val port: StateFlow<String> = _port.asStateFlow()

    init {
        // Load persisted history on first creation
        viewModelScope.launch {
            _history.value = historyStore.historyFlow.first()
        }
    }

    fun onHostChange(value: String) {
        _host.value = value
        // Clear results when the user edits the input
        if (_uiState.value !is HttpsCertUiState.Idle) reset()
    }

    fun onPortChange(value: String) {
        // Accept only digits, max 5 chars
        if (value.all { it.isDigit() } && value.length <= 5) {
            _port.value = value
            if (_uiState.value !is HttpsCertUiState.Idle) reset()
        }
    }

    /**
     * Launches a certificate fetch for the current [host] / [port] values.
     * Any previously running fetch is cancelled first.
     */
    fun fetchCert() {
        val h = _host.value.trim()
        val p = _port.value.trim().toIntOrNull()

        if (h.isBlank()) return
        if (p == null || p !in 1..65535) {
            _uiState.value = HttpsCertUiState.Error("Port must be a number between 1 and 65535")
            return
        }

        fetchJob?.cancel()
        _uiState.value = HttpsCertUiState.Loading

        fetchJob = viewModelScope.launch {
            val newState = when (val result = repository.fetchCertificate(h, p)) {
                is HttpsCertResult.Success ->
                    HttpsCertUiState.Success(result.info)

                is HttpsCertResult.CertExpired ->
                    HttpsCertUiState.PartialSuccess(
                        info           = result.info,
                        warningMessage = "⚠️ Certificate expired ${-result.info.daysUntilExpiry} day(s) ago",
                    )

                is HttpsCertResult.UntrustedChain ->
                    HttpsCertUiState.PartialSuccess(
                        info             = result.info,
                        warningMessage = "⚠️ Untrusted chain — ${result.reason}",
                    )

                is HttpsCertResult.NoNetwork ->
                    HttpsCertUiState.Error("No network connection")

                is HttpsCertResult.HostnameUnresolved ->
                    HttpsCertUiState.Error("Cannot resolve hostname \"${result.host}\"")

                is HttpsCertResult.Timeout ->
                    HttpsCertUiState.Error("Connection timed out (${result.host})")

                is HttpsCertResult.Error ->
                    HttpsCertUiState.Error(result.message)
            }

            _uiState.value = newState
            saveHistory(h, p, newState)
        }
    }

    /** Populates the host/port fields from a history entry and immediately re-fetches. */
    fun selectHistoryEntry(entry: HttpsCertHistoryEntry) {
        _host.value = entry.host
        _port.value = entry.port.toString()
        fetchCert()
    }

    /** Cancels any in-flight request and returns the UI to [HttpsCertUiState.Idle]. */
    fun reset() {
        fetchJob?.cancel()
        _uiState.value = HttpsCertUiState.Idle
    }

    // ── History persistence ────────────────────────────────────────────────────

    private suspend fun saveHistory(host: String, port: Int, state: HttpsCertUiState) {
        if (host.isBlank()) return

        val (status, summary) = when (state) {
            is HttpsCertUiState.Success -> {
                val info = state.info
                val days = info.daysUntilExpiry
                val validLabel = when (info.validityStatus) {
                    CertValidityStatus.VALID          -> if (days > 365) "valid" else "valid · ${days}d"
                    CertValidityStatus.EXPIRING_SOON  -> "expiring · ${days}d"
                    CertValidityStatus.EXPIRED        -> "expired"
                }
                CertHistoryStatus.VALID to "${info.keyAlgorithm} ${info.keySize} · $validLabel"
            }
            is HttpsCertUiState.PartialSuccess -> {
                val info = state.info
                when (info.validityStatus) {
                    CertValidityStatus.EXPIRED ->
                        CertHistoryStatus.EXPIRED to "expired ${-info.daysUntilExpiry}d ago"
                    else ->
                        CertHistoryStatus.UNTRUSTED to "untrusted chain"
                }
            }
            is HttpsCertUiState.Error ->
                CertHistoryStatus.ERROR to state.message.take(40)
            else -> return   // Idle / Loading — nothing to save
        }

        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))

        val newEntry = HttpsCertHistoryEntry(
            timestamp = timestamp,
            host      = host,
            port      = port,
            status    = status,
            summary   = summary,
        )

        // Deduplicate by host+port — keep newer entry on top
        val updatedHistory = (listOf(newEntry) +
                _history.value.filter { it.host != host || it.port != port })
            .take(5)

        _history.value = updatedHistory
        historyStore.save(updatedHistory)
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val appContext = context.applicationContext
                    val settingsRepo = io.github.mobilutils.ntp_dig_ping_more.settings.SettingsRepository(appContext)
                    val logger = io.github.mobilutils.ntp_dig_ping_more.proxy.ProxyPacLogger.getInstance(
                        logFile = java.io.File(appContext.filesDir, "proxypac-logs.txt"),
                    )
                    val proxyResolver = io.github.mobilutils.ntp_dig_ping_more.proxy.ProxyResolver(
                        settingsRepo,
                        io.github.mobilutils.ntp_dig_ping_more.proxy.QuickJsEngine(),
                        logger = logger,
                    )
                    return HttpsCertViewModel(
                        repository   = HttpsCertRepository(proxyResolver),
                        historyStore = HttpsCertHistoryStore(appContext),
                    ) as T
                }
            }
    }
}
