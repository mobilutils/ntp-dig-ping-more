package io.github.mobilutils.ntp_dig_ping_more.deviceinfo

/**
 * Represents all gathered device information for the Device Info screen.
 * Each field is nullable/optional to handle cases where Android restricts access.
 */
data class DeviceInfo(
    // ── Required data points ──────────────────────────────────────────
    val deviceName: String? = null,
    val imei: String? = null,
    val serialNumber: String? = null,
    val iccid: String? = null,
    val deviceTime: String? = null,
    val ipv4Address: String? = null,
    val ipv6Address: String? = null,
    val subnetMask: String? = null,
    val defaultGateway: String? = null,
    val ntpServer: String? = null,
    val dnsServers: List<String> = emptyList(),
    val carrierName: String? = null,
    val wifiSSID: String? = null,
    val timeSinceReboot: String? = null,
    val timeSinceScreenOff: String? = null,
    val mdmStatus: String? = null,
    val installedCertificates: List<CertificateInfo> = emptyList(),

    // ── Additional suggested fields ───────────────────────────────────
    val androidVersion: String? = null,
    val apiLevel: Int? = null,
    val batteryLevel: Int? = null,
    val isCharging: Boolean? = null,
    val batteryHealth: String? = null,
    val totalRam: String? = null,
    val availableRam: String? = null,
    val totalStorage: String? = null,
    val availableStorage: String? = null,
    val cpuAbi: List<String> = emptyList(),
    val activeNetworkType: String? = null,
)

/**
 * Represents a single installed certificate (user or system CA).
 */
data class CertificateInfo(
    val subject: String,
    val notBefore: String,
    val notAfter: String,
    val type: String, // "System" or "User"
)

/**
 * Sealed class representing the permission state for the Device Info screen.
 */
sealed class PermissionState {
    object Granted : PermissionState()
    object Denied : PermissionState()
    object ShowRationale : PermissionState()
}

/**
 * UI state exposed by the ViewModel to the Composable screen.
 */
sealed class DeviceInfoState {
    object Loading : DeviceInfoState()
    data class Success(
        val deviceInfo: DeviceInfo,
        val permissionState: PermissionState = PermissionState.Granted,
    ) : DeviceInfoState()
    data class PermissionDenied(
        val message: String = "Some information is unavailable due to denied permissions.",
    ) : DeviceInfoState()
    data class Error(val message: String) : DeviceInfoState()
}
