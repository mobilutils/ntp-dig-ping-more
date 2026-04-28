package io.github.mobilutils.ntp_dig_ping_more.deviceinfo

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import io.github.mobilutils.ntp_dig_ping_more.deviceinfo.CertificateInfo
import io.github.mobilutils.ntp_dig_ping_more.deviceinfo.DeviceInfo
import java.io.File
import java.io.FileInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Repository that encapsulates all Android system API calls for device information.
 * Handles exceptions, normalizes data, and provides fallbacks for restricted APIs.
 */
class SystemInfoRepository(
    private val context: Context,
) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val devicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val batteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    /**
     * Gathers all device information in one call.
     * This should be called from a background thread (IO dispatcher).
     */
    fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            // Required data points
            deviceName = getDeviceName(),
            imei = getImei(),
            serialNumber = getSerialNumber(),
            iccid = getIccid(),
            deviceTime = getCurrentDeviceTime(),
            ipv4Address = getIpv4Address(),
            ipv6Address = getIpv6Address(),
            subnetMask = getSubnetMask(),
            defaultGateway = getDefaultGateway(),
            ntpServer = getNtpServer(),
            dnsServers = getDnsServers(),
            carrierName = getCarrierName(),
            wifiSSID = getWifiSSID(),
            timeSinceReboot = getTimeSinceReboot(),
            timeSinceScreenOff = getTimeSinceScreenOff(),
            mdmStatus = getMdmStatus(),
            installedCertificates = getInstalledCertificates(),

            // Additional suggested fields
            androidVersion = getAndroidVersion(),
            apiLevel = getApiLevel(),
            batteryLevel = getBatteryLevel(),
            isCharging = isCharging(),
            batteryHealth = getBatteryHealth(),
            totalRam = getTotalRam(),
            availableRam = getAvailableRam(),
            totalStorage = getTotalStorage(),
            availableStorage = getAvailableStorage(),
            cpuAbi = getCpuAbi(),
            activeNetworkType = getActiveNetworkType(),
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Required data points
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns the device manufacturer model.
     * Android does not expose a user-defined device name without special permissions,
     * so we use manufacturer + model.
     */
    fun getDeviceName(): String {
        return try {
            "${Build.MANUFACTURER} ${Build.MODEL}"
        } catch (e: Exception) {
            "Unknown Device"
        }
    }

    /**
     * Gets the device IMEI (International Mobile Equipment Identity).
     *
     * Android 10+ (API 29+) restriction:
     *   - Requires READ_PHONE_STATE permission AND the app to be a device/profile owner,
     *     OR have the READ_PRIVILEGED_PHONE_STATE permission (system apps only).
     *   - For normal third-party apps on Android 10+, this returns null.
     *
     * Android 9 and below:
     *   - Requires READ_PHONE_STATE permission.
     *   - Returns the IMEI for GSM devices, MEID for CDMA devices.
     *
     * On devices without cellular radios (e.g., Wi-Fi-only tablets), returns null.
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    fun getImei(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: IMEI is restricted to system/privileged apps
                // Non-privileged apps get null or an exception
                null
            } else {
                // Android 9 and below: use TelephonyManager.getDeviceId()
                @Suppress("DEPRECATION")
                telephonyManager.getDeviceId()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gets the device hardware serial number.
     *
     * Android 10+ (API 29+) restriction:
     *   - Build.getSerial() requires READ_PHONE_STATE permission AND
     *     the app to be a device/profile owner, or have special privileges.
     *   - Throws SecurityException for normal third-party apps.
     *
     * Android 9 and below:
     *   - Build.SERIAL is deprecated in favor of Build.getSerial().
     *   - Returns the hardware serial number if available.
     *
     * Note: On many modern devices, this returns "unknown" or null for privacy reasons.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun getSerialNumber(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: requires elevated privileges
                "Restricted by Android 10+"
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8.0-9: Build.getSerial() requires READ_PHONE_STATE
                Build.getSerial()
            } else {
                // Android 7.x and below: use deprecated constant
                @Suppress("DEPRECATION")
                Build.SERIAL.takeIf { it.isNotBlank() && it != "unknown" }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gets the ICCID (Integrated Circuit Card Identifier) of the SIM card.
     * This is the unique serial number printed on the SIM card itself.
     *
     * Android 10+ (API 29+) restriction:
     *   - Requires READ_PHONE_STATE permission AND the app to be a device/profile owner,
     *     or have READ_PRIVILEGED_PHONE_STATE (system apps only).
     *
     * Android 9 and below:
     *   - Requires READ_PHONE_STATE permission.
     *   - Returns the ICCID if a SIM is present, null otherwise.
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    fun getIccid(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: ICCID is restricted to system/privileged apps
                "Restricted by Android 10+"
            } else {
                // Android 9 and below: use TelephonyManager.simSerialNumber
                @Suppress("DEPRECATION")
                telephonyManager.simSerialNumber.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns the current device time, formatted in the user's locale.
     */
    fun getCurrentDeviceTime(): String {
        return try {
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            formatter.format(Date())
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * Gets the current active network's IPv4 address.
     * Uses LinkProperties from ConnectivityManager (modern approach).
     * Requires ACCESS_NETWORK_STATE permission.
     */
    @SuppressLint("MissingPermission") // Falls back gracefully on denial
    fun getIpv4Address(): String? {
        return try {
            val network = connectivityManager.activeNetwork
            val linkProps = connectivityManager.getLinkProperties(network)
            linkProps?.linkAddresses
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull()
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gets the current active network's IPv6 address.
     * Uses LinkProperties from ConnectivityManager.
     */
    @SuppressLint("MissingPermission")
    fun getIpv6Address(): String? {
        return try {
            val network = connectivityManager.activeNetwork
            val linkProps = connectivityManager.getLinkProperties(network)
            linkProps?.linkAddresses
                ?.map { it.address }
                ?.filterIsInstance<Inet6Address>()
                ?.firstOrNull { !it.isSiteLocalAddress } // Prefer global IPv6
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gets the subnet mask from the active network's link properties.
     * Calculated from the prefix length of the first IPv4 link address.
     */
    @SuppressLint("MissingPermission")
    fun getSubnetMask(): String? {
        return try {
            val network = connectivityManager.activeNetwork
            val linkProps = connectivityManager.getLinkProperties(network)
            val ipv4LinkAddress = linkProps?.linkAddresses
                ?.map { it.address to it.prefixLength }
                ?.firstOrNull { it.first is Inet4Address }
            if (ipv4LinkAddress != null) {
                val prefixLength = ipv4LinkAddress.second
                // Convert prefix length to subnet mask string (e.g., 24 -> 255.255.255.0)
                val mask = (0xFFFFFFFF shl (32 - prefixLength)).toLong() and 0xFFFFFFFFL
                val octets = listOf(
                    (mask shr 24) and 0xFF,
                    (mask shr 16) and 0xFF,
                    (mask shr 8) and 0xFF,
                    mask and 0xFF,
                )
                octets.joinToString(".")
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gets the default gateway/router IP from link properties.
     * Uses LinkProperties.routes to find the default route (0.0.0.0/0).
     * Falls back to reading /proc/net/route if ConnectivityManager doesn't provide it.
     */
    @SuppressLint("MissingPermission")
    fun getDefaultGateway(): String? {
        return try {
            val network = connectivityManager.activeNetwork
            val linkProps = connectivityManager.getLinkProperties(network)
            // On Android 10+, routes may not be accessible without special permissions.
            // Try to get the gateway from linkAddresses as a fallback.
            var gateway: String? = null
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+: use explicit default route marker
                linkProps?.routes
                    ?.firstOrNull { it.isDefaultRoute }
                    ?.gateway
                    ?.hostAddress
            } else {
                // Fallback: first route's gateway (usually the default)
                linkProps?.routes
                    ?.firstOrNull()
                    ?.gateway
                    ?.hostAddress
            }
            
            // If ConnectivityManager didn't provide a gateway, try /proc/net/route
            if (gateway == null) {
                gateway = getGatewayFromProcNetRoute()
            }
            
            gateway
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fallback method: reads gateway from /proc/net/route.
     * This is a low-level Linux interface that's always accessible.
     * /proc/net/route uses hex-encoded little-endian IPs.
     * Returns the default gateway IP if found, null otherwise.
     */
    private fun getGatewayFromProcNetRoute(): String? {
        return try {
            val procFile = File("/proc/net/route")
            if (!procFile.exists()) return null

            val lines = procFile.readLines()
            if (lines.size < 2) return null

            // Skip header line (interface names), parse each route entry
            for (i in 1 until lines.size) {
                val line = lines[i].trim()
                if (line.isBlank()) continue

                val parts = line.split(Regex("\\s+"))
                if (parts.size < 9) continue

                // Column layout (hex fields):
                // [0] = interface name (e.g., "wlan0")
                // [1] = destination IP (hex, little-endian) — "00000000" = default route
                // [2] = gateway IP (hex, little-endian)
                // [3] = flags
                // [8] = metric
                val destHex = parts[1]
                val gatewayHex = parts[2]

                // Default route: destination is 0.0.0.0 (all zeros in hex)
                if (destHex == "00000000" || destHex == "0000000000000000") {
                    // Skip directly connected routes (gateway 0.0.0.0)
                    if (gatewayHex == "00000000" || gatewayHex == "0000000000000000") continue
                    val gwIp = hexToIpv4(gatewayHex)
                    if (gwIp != null) return gwIp
                }
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Converts a hex string from /proc/net/route to a dotted-decimal IPv4 address.
     * The hex value is stored in little-endian byte order.
     * E.g., "4a0002ac" -> bytes [0xac, 0x02, 0x00, 0x4a] -> "172.2.0.74"
     */
    private fun hexToIpv4(hex: String): String? {
        return try {
            // Handle both 32-bit (8 chars) and 64-bit (16 chars) hex values
            // For 64-bit values, the IPv4 address is in the lower 32 bits (first 8 chars in little-endian)
            val cleanHex = if (hex.length > 8) hex.take(8) else hex
            val bytes = cleanHex.chunked(2).map { it.toInt(16).toByte() }
            // Reverse byte order (little-endian to network byte order)
            bytes.reversed().joinToString(".") { (it.toInt() and 0xFF).toString() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads the NTP server from system settings.
     * Note: Settings.Global.NTP_SERVER is not a public constant on all Android versions.
     * We use the literal key "ntp_server" which is used by AOSP.
     */
    fun getNtpServer(): String? {
        return try {
            // NTP_SERVER is not a public API constant on all Android versions
            // Using the known key "ntp_server" from AOSP Settings.Global
            val ntp = Settings.Global.getString(
                context.contentResolver,
                "ntp_server"
            )
            if (ntp.isNullOrBlank()) null else ntp
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gets DNS servers from the active network's LinkProperties.
     * This is the most reliable way on modern Android.
     */
    @SuppressLint("MissingPermission")
    fun getDnsServers(): List<String> {
        return try {
            val network = connectivityManager.activeNetwork
            val linkProps = connectivityManager.getLinkProperties(network)
            linkProps?.dnsServers?.map { it.hostAddress ?: it.hostName } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Gets the mobile carrier/operator name.
     * Requires READ_PHONE_STATE permission on Android 10+.
     * Returns null if permission denied or no SIM.
     */
    @SuppressLint("MissingPermission")
    fun getCarrierName(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: requires READ_PHONE_STATE; may return null without it
                telephonyManager.simOperatorName.takeIf { it.isNotBlank() }
                    ?: telephonyManager.networkOperatorName.takeIf { it.isNotBlank() }
            } else {
                telephonyManager.networkOperatorName.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gets the connected Wi-Fi SSID.
     * On Android 10+, requires either:
     *   - ACCESS_FINE_LOCATION permission AND location services enabled, OR
     *   - ACCESS_WIFI_STATE with special carrier privileges (rare for third-party apps)
     * Returns null or "Restricted by Android 10+" if unavailable.
     *
     * Note: WifiManager.connectionInfo is deprecated on Android 14+ but there is
     * no direct replacement for third-party apps. We suppress the deprecation warning.
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    @Suppress("DEPRECATION")
    fun getWifiSSID(): String? {
        return try {
            val wifiInfo: WifiInfo? = wifiManager.connectionInfo
            val ssid = wifiInfo?.ssid
            if (ssid == null || ssid == "<unknown ssid>" || ssid == "0x") {
                // Android 10+ restriction
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "Restricted by Android 10+"
                } else {
                    null
                }
            } else {
                // SSID comes wrapped in quotes; strip them
                ssid.trim('"')
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Calculates time since last system reboot using elapsedRealtime.
     * SystemClock.elapsedRealtime() returns milliseconds since boot (including sleep).
     */
    fun getTimeSinceReboot(): String {
        return try {
            val elapsedMillis = SystemClock.elapsedRealtime()
            formatDuration(elapsedMillis)
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * Time since last screen-off event.
     * Android does NOT expose a public API for this without persistent background services
     * or registering a BroadcastReceiver with a manifest-registered receiver (which has
     * limitations on Android 10+). We return a clear fallback.
     */
    fun getTimeSinceScreenOff(): String? {
        // PowerManager.isInteractive() tells us current state, but NOT when it changed.
        // There is no public API to get "time since last screen-off" without a persistent
        // service tracking SCREEN_ON/SCREEN_OFF broadcasts.
        // Per spec: show "Not exposed by OS" if not reliably gatherable.
        return "Not exposed by OS"
    }

    /**
     * Gets MDM / Device Policy status using DevicePolicyManager.
     * Checks for Device Owner, Profile Owner, Managed Profile, or None.
     *
     * Note: DevicePolicyManager does not expose APIs to query if *another* app is
     * the device owner without being a device admin ourselves. We can only check
     * if this app holds those roles or if device admin is active.
     */
    fun getMdmStatus(): String {
        return try {
            // Check if this app is the device owner
            val isDeviceOwner = devicePolicyManager.isDeviceOwnerApp(context.packageName)

            // Check if this app is the profile owner
            val isProfileOwner = devicePolicyManager.isProfileOwnerApp(context.packageName)

            // Check for managed profiles (Android 7.0+)
            // getManagedProfiles() returns list of managed profiles for the calling user
            val hasManagedProfile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // We need to be a device/profile owner to call this reliably
                // Fallback: check if any admin is active
                false
            } else {
                false
            }

            when {
                isDeviceOwner -> "Device Owner (this app)"
                isProfileOwner -> "Profile Owner (this app)"
                hasManagedProfile -> "Managed Profile"
                else -> "None"
            }
        } catch (e: Exception) {
            "None"
        }
    }

    /**
     * Enumerates installed certificates from the Android KeyStore.
     * Reads from "AndroidCAStore" which includes both system and user CAs.
     * Note: This reads the CA trust store, not app-specific keystores.
     */
    fun getInstalledCertificates(): List<CertificateInfo> {
        return try {
            val keyStore = KeyStore.getInstance("AndroidCAStore")
            keyStore.load(null)

            val aliases = keyStore.aliases()
            val certs = mutableListOf<CertificateInfo>()

            aliases.asSequence().take(50).forEach { alias ->
                // Determine if it's a user or system cert by alias convention
                // System certs have numeric aliases; user certs start with "user:"
                val certType = if (alias.startsWith("user:")) "User" else "System"

                val certificate = keyStore.getCertificate(alias)
                if (certificate is X509Certificate) {
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    certs.add(
                        CertificateInfo(
                            subject = certificate.subjectX500Principal.name,
                            notBefore = dateFormat.format(certificate.notBefore),
                            notAfter = dateFormat.format(certificate.notAfter),
                            type = certType,
                        )
                    )
                }
            }

            certs
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Additional suggested fields
    // ─────────────────────────────────────────────────────────────────────

    fun getAndroidVersion(): String {
        return Build.VERSION.RELEASE
    }

    fun getApiLevel(): Int {
        return Build.VERSION.SDK_INT
    }

    /**
     * Battery level as a percentage (0-100).
     */
    fun getBatteryLevel(): Int? {
        return try {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                // ACTION_BATTERY_CHANGED is a sticky broadcast; registerReceiver(null) retrieves it
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(null, filter)
                }
            }
            batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.let { level ->
                val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    ((level / scale.toFloat()) * 100).roundToInt()
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Whether the device is currently charging (AC, USB, or Wireless).
     */
    fun isCharging(): Boolean? {
        return try {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(null, filter)
                }
            }
            batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let { status ->
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Battery health status (e.g., Good, Overheat, Dead, Over Voltage).
     */
    fun getBatteryHealth(): String? {
        return try {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(null, filter)
                }
            }
            batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)?.let { health ->
                when (health) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
                    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Unspecified Failure"
                    BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                    else -> "Unknown"
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Total RAM in human-readable format (e.g., "4.0 GB").
     */
    fun getTotalRam(): String? {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            // totalMem is in bytes
            formatBytes(memInfo.totalMem)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Available RAM in human-readable format.
     */
    fun getAvailableRam(): String? {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            formatBytes(memInfo.availMem)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Total internal storage capacity.
     * Reads from /data partition (user-accessible storage).
     */
    fun getTotalStorage(): String? {
        return try {
            val dataDir = Environment.getDataDirectory()
            val stat = android.os.StatFs(dataDir.path)
            val totalBytes = stat.totalBytes
            formatBytes(totalBytes)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Available internal storage.
     */
    fun getAvailableStorage(): String? {
        return try {
            val dataDir = Environment.getDataDirectory()
            val stat = android.os.StatFs(dataDir.path)
            val availableBytes = stat.availableBytes
            formatBytes(availableBytes)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Supported CPU architectures (ABIs), ordered by preference.
     */
    fun getCpuAbi(): List<String> {
        return try {
            Build.SUPPORTED_ABIS.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Gets the active network type: Wi-Fi, Cellular, Ethernet, VPN, or None.
     * Uses NetworkCapabilities (modern approach, not deprecated).
     */
    @SuppressLint("MissingPermission")
    fun getActiveNetworkType(): String? {
        return try {
            val network = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(network)
            when {
                caps == null -> "None"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN) -> "LoWPAN"
                else -> "Unknown"
            }
        } catch (e: Exception) {
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helper functions
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Formats milliseconds into a human-readable duration string.
     * E.g., "2d 5h 30m 15s"
     */
    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return buildString {
            if (days > 0) append("${days}d ")
            if (hours > 0) append("${hours}h ")
            if (minutes > 0) append("${minutes}m ")
            append("${secs}s")
        }.trim()
    }

    /**
     * Formats bytes into human-readable storage size.
     * E.g., "128.0 GB", "4.0 MB"
     */
    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "Unknown"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024
            unitIndex++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}
