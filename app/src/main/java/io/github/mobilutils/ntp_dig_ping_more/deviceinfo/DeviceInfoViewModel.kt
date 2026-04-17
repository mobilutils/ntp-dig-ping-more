package io.github.mobilutils.ntp_dig_ping_more.deviceinfo

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ViewModel for the Device Info screen.
 *
 * Exposes a [StateFlow<DeviceInfoState>] that the Composable UI collects.
 * Handles:
 *   - One-time device info fetch on init
 *   - Periodic updates for time-related fields (device time, uptime, battery)
 *   - Permission state tracking
 */
class DeviceInfoViewModel(
    private val repository: SystemInfoRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DeviceInfoState>(DeviceInfoState.Loading)
    val uiState: StateFlow<DeviceInfoState> = _uiState.asStateFlow()

    /** Whether all required permissions have been granted. */
    private var permissionsGranted = true

    init {
        loadDeviceInfo()
        startPeriodicUpdates()
    }

    /**
     * Called by the UI layer when permission results are known.
     * Uses [ActivityResultContracts] in the Composable to gather this.
     */
    fun onPermissionsResult(granted: Boolean) {
        permissionsGranted = granted
        if (granted) {
            loadDeviceInfo()
        } else {
            _uiState.value = DeviceInfoState.PermissionDenied(
                "Some network and telephony information is unavailable due to denied permissions. " +
                        "You can grant permissions in Settings for full details."
            )
        }
    }

    /**
     * Loads all device information once via the repository.
     * Runs on IO dispatcher to avoid blocking the main thread.
     */
    private fun loadDeviceInfo() {
        viewModelScope.launch {
            try {
                _uiState.value = DeviceInfoState.Loading
                // Simulate a brief loading state for UX feel
                delay(300)
                val deviceInfo = repository.getDeviceInfo()
                _uiState.value = DeviceInfoState.Success(
                    deviceInfo = deviceInfo,
                    permissionState = if (permissionsGranted)
                        PermissionState.Granted
                    else
                        PermissionState.Denied,
                )
            } catch (e: Exception) {
                _uiState.value = DeviceInfoState.Error(
                    message = "Failed to gather device information: ${e.localizedMessage}"
                )
            }
        }
    }

    /**
     * Starts a coroutine that periodically updates time-sensitive fields.
     * This runs every second and only updates the device time and uptime,
     * avoiding expensive full repository re-fetches.
     */
    private fun startPeriodicUpdates() {
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val currentState = _uiState.value
                if (currentState is DeviceInfoState.Success) {
                    val currentTime = repository.getCurrentDeviceTime()
                    val timeSinceReboot = repository.getTimeSinceReboot()
                    val batteryLevel = repository.getBatteryLevel()
                    val isCharging = repository.isCharging()

                    // Only update if values actually changed to avoid unnecessary recompositions
                    val currentInfo = currentState.deviceInfo
                    if (currentInfo.deviceTime != currentTime ||
                        currentInfo.timeSinceReboot != timeSinceReboot ||
                        currentInfo.batteryLevel != batteryLevel ||
                        currentInfo.isCharging != isCharging
                    ) {
                        val updatedInfo = currentInfo.copy(
                            deviceTime = currentTime,
                            timeSinceReboot = timeSinceReboot,
                            batteryLevel = batteryLevel,
                            isCharging = isCharging,
                        )
                        _uiState.value = currentState.copy(deviceInfo = updatedInfo)
                    }
                }
            }
        }
    }

    /**
     * Factory for creating [DeviceInfoViewModel] with a [SystemInfoRepository].
     */
    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DeviceInfoViewModel(
                        repository = SystemInfoRepository(context.applicationContext)
                    ) as T
            }
    }
}
