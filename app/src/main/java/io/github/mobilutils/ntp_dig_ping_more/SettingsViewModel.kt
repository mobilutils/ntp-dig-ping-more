package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.mobilutils.ntp_dig_ping_more.settings.SettingsKeys
import io.github.mobilutils.ntp_dig_ping_more.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// UI state
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Complete UI state for the Settings screen.
 *
 * @param timeoutInput  Raw text currently shown in the timeout text field.
 * @param isError       True when [timeoutInput] is out of the allowed range or non-numeric.
 * @param savedTimeout  The last successfully saved value; used to restore the
 *                      field if the user leaves it in an invalid state.
 */
data class SettingsUiState(
    val timeoutInput: String = SettingsKeys.DEFAULT_TIMEOUT_SECONDS.toString(),
    val isError: Boolean = false,
    val savedTimeout: Int = SettingsKeys.DEFAULT_TIMEOUT_SECONDS,
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
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

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
    }

    // ── User actions ──────────────────────────────────────────────────────────

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

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {
        /** Creates a factory backed by the application-scoped [SettingsRepository]. */
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(
                        settingsRepository = SettingsRepository(context.applicationContext),
                    ) as T
            }
    }
}
