package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.mobilutils.ntp_dig_ping_more.proxy.ProxyResolver
import io.github.mobilutils.ntp_dig_ping_more.proxy.QuickJsEngine
import io.github.mobilutils.ntp_dig_ping_more.settings.ProxyConfig
import io.github.mobilutils.ntp_dig_ping_more.settings.SettingsKeys
import io.github.mobilutils.ntp_dig_ping_more.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// UI state
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Complete UI state for the Settings screen.
 *
 * @param timeoutInput     Raw text currently shown in the timeout text field.
 * @param isError          True when [timeoutInput] is out of the allowed range or non-numeric.
 * @param savedTimeout     The last successfully saved value; used to restore the
 *                         field if the user leaves it in an invalid state.
 * @param proxyEnabled     Whether proxy routing is currently active.
 * @param proxyPacUrl      Text in the PAC URL field.
 * @param proxyPacUrlError Inline validation error for the PAC URL, or null if valid.
 * @param isTestingProxy   True while a proxy connectivity test is in progress.
 * @param proxyTestResult  Human-readable result of the last test, or null if never tested.
 * @param proxyLastTested  Epoch millis of the last test, or 0 if never tested.
 */
data class SettingsUiState(
    val timeoutInput: String = SettingsKeys.DEFAULT_TIMEOUT_SECONDS.toString(),
    val isError: Boolean = false,
    val savedTimeout: Int = SettingsKeys.DEFAULT_TIMEOUT_SECONDS,
    // Proxy / PAC fields
    val proxyEnabled: Boolean = false,
    val proxyPacUrl: String = "",
    val proxyPacUrlError: String? = null,
    val isTestingProxy: Boolean = false,
    val proxyTestResult: String? = null,
    val proxyLastTested: Long = 0L,
)

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ViewModel for the Settings screen.
 *
 * Responsibilities:
 *  - Load the persisted timeout on creation and expose it via [uiState].
 *  - Validate the user's raw text-field input (must be an integer in 1..60).
 *  - Save valid values immediately via [SettingsRepository.updateTimeout].
 *  - Revert the text field to the last known-good value on explicit [revert] call
 *    (e.g. when focus leaves an invalid field).
 *  - Manage the Proxy/PAC configuration (enable/disable, PAC URL, test connectivity).
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val proxyResolver: ProxyResolver,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** Debounce job for PAC URL validation. */
    private var pacUrlDebounceJob: Job? = null

    /** Job for the current proxy test, so it can be cancelled if needed. */
    private var testJob: Job? = null

    init {
        // Seed the text field from the persisted value (handles first-launch default too).
        viewModelScope.launch {
            settingsRepository.timeoutSecondsFlow.collect { persisted ->
                // Only update if the field is not being actively edited to an invalid value
                val current = _uiState.value
                if (!current.isError) {
                    _uiState.value = current.copy(
                        timeoutInput = persisted.toString(),
                        savedTimeout = persisted,
                    )
                } else {
                    // Just sync the known-good backing value for revert purposes
                    _uiState.value = current.copy(savedTimeout = persisted)
                }
            }
        }

        // Load persisted proxy config on creation
        viewModelScope.launch {
            val config = settingsRepository.proxyConfigFlow.first()
            _uiState.value = _uiState.value.copy(
                proxyEnabled    = config.enabled,
                proxyPacUrl     = config.pacUrl,
                proxyLastTested = config.lastTested,
                proxyTestResult = config.lastTestResult,
            )
        }
    }

    // ── Timeout actions ──────────────────────────────────────────────────────

    /**
     * Called on every keystroke in the timeout text field.
     *
     * If [value] parses to a valid integer in [SettingsKeys.MIN_TIMEOUT_SECONDS]..[SettingsKeys.MAX_TIMEOUT_SECONDS],
     * the new value is immediately persisted and [isError] is cleared.
     * Otherwise [isError] is set so the UI can show inline feedback.
     */
    fun onTimeoutChange(value: String) {
        // Accept only numeric input (allow empty for backspace UX)
        val filtered = value.filter { it.isDigit() }.take(3)
        val parsed = filtered.toIntOrNull()
        val valid = parsed != null &&
                parsed >= SettingsKeys.MIN_TIMEOUT_SECONDS &&
                parsed <= SettingsKeys.MAX_TIMEOUT_SECONDS

        _uiState.value = _uiState.value.copy(
            timeoutInput = filtered,
            isError = parsed == null || parsed < SettingsKeys.MIN_TIMEOUT_SECONDS || parsed > SettingsKeys.MAX_TIMEOUT_SECONDS,
        )

        if (valid && parsed != null) {
            viewModelScope.launch {
                settingsRepository.updateTimeout(parsed)
            }
        }
    }

    /**
     * Reverts the text field to the last successfully saved value.
     *
     * Call this when the text field loses focus while still in an invalid state,
     * so the displayed value always reflects a real persisted setting.
     */
    fun revert() {
        val saved = _uiState.value.savedTimeout
        _uiState.value = _uiState.value.copy(
            timeoutInput = saved.toString(),
            isError = false,
        )
    }

    // ── Proxy / PAC actions ──────────────────────────────────────────────────

    /**
     * Toggles proxy routing on/off and persists immediately.
     */
    fun onProxyEnabledChange(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(proxyEnabled = enabled)
        viewModelScope.launch {
            saveCurrentProxyConfig()
        }
    }

    /**
     * Called on every keystroke in the PAC URL text field.
     *
     * Validation is debounced by 300 ms to avoid distracting the user
     * while they are still typing. Valid URLs are persisted; invalid
     * ones set [SettingsUiState.proxyPacUrlError].
     */
    fun onProxyPacUrlChange(url: String) {
        _uiState.value = _uiState.value.copy(
            proxyPacUrl = url,
            proxyPacUrlError = null, // clear error immediately while typing
        )

        // Clear proxy cache when URL changes
        proxyResolver.clearCache()

        pacUrlDebounceJob?.cancel()
        pacUrlDebounceJob = viewModelScope.launch {
            delay(300) // debounce

            val error = validatePacUrl(url)
            _uiState.value = _uiState.value.copy(proxyPacUrlError = error)

            if (error == null) {
                saveCurrentProxyConfig()
            }
        }
    }

    /**
     * Tests proxy connectivity and updates the UI with the result.
     */
    fun testProxy() {
        testJob?.cancel()
        _uiState.value = _uiState.value.copy(isTestingProxy = true)

        testJob = viewModelScope.launch {
            val result = proxyResolver.testProxy()
            val now = System.currentTimeMillis()

            _uiState.value = _uiState.value.copy(
                isTestingProxy  = false,
                proxyTestResult = result.message,
                proxyLastTested = now,
            )

            // Persist test result
            saveCurrentProxyConfig(
                overrideLastTested = now,
                overrideTestResult = result.message,
            )
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Validates a PAC URL string.
     *
     * @return An error message, or `null` if the URL is valid (or empty, since
     *         an empty URL simply means "no proxy configured").
     */
    internal fun validatePacUrl(url: String): String? {
        if (url.isBlank()) return null // empty is allowed — means disabled
        return try {
            val parsed = java.net.URL(url)
            when {
                parsed.protocol !in listOf("http", "https") ->
                    "Only http:// and https:// URLs are supported"
                parsed.host.isNullOrBlank() ->
                    "URL must contain a hostname"
                else -> null
            }
        } catch (_: Exception) {
            "Invalid URL format"
        }
    }

    /**
     * Persists the current proxy config from UI state to DataStore.
     */
    private suspend fun saveCurrentProxyConfig(
        overrideLastTested: Long? = null,
        overrideTestResult: String? = null,
    ) {
        val state = _uiState.value
        settingsRepository.saveProxyConfig(
            ProxyConfig(
                enabled        = state.proxyEnabled,
                pacUrl         = state.proxyPacUrl,
                lastTested     = overrideLastTested ?: state.proxyLastTested,
                lastTestResult = overrideTestResult ?: state.proxyTestResult,
            )
        )
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {
        /** Creates a factory backed by the application-scoped [SettingsRepository]. */
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val appContext = context.applicationContext
                    val settingsRepo = SettingsRepository(appContext)
                    val proxyResolver = ProxyResolver(settingsRepo, QuickJsEngine())
                    return SettingsViewModel(
                        settingsRepository = settingsRepo,
                        proxyResolver      = proxyResolver,
                    ) as T
                }
            }
    }
}
