package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.math.pow

data class LanDevice(
    val ip: String,
    val mac: String? = null,
    val hostname: String? = null,
    val isRouter: Boolean = false,
    val pingMs: Int? = null,
)

data class SubnetInfo(
    val ipAddress: String,
    val networkPrefixLength: Int,
    val cidr: String,
    val baseIp: Long,
    val numHosts: Long
)

class LanScannerRepository(private val context: Context) {

    /**
     * Gets the active WiFi IPv4 address and its subnet prefix.
     */
    fun getLocalSubnetInfo(): SubnetInfo? {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val activeNetwork: Network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null

        // Recommend scanning on WiFi or Ethernet only
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return null
        }

        val linkProperties: LinkProperties =
            connectivityManager.getLinkProperties(activeNetwork) ?: return null

        for (linkAddress in linkProperties.linkAddresses) {
            val inetAddress = linkAddress.address
            if (inetAddress is Inet4Address && !inetAddress.isLoopbackAddress) {
                val ipString = inetAddress.hostAddress ?: continue
                val prefixLength = linkAddress.prefixLength
                val ipLong = ipToLong(ipString)
                val mask = (0xffffffffL shl (32 - prefixLength)) and 0xffffffffL
                val baseIp = ipLong and mask
                val numHosts = (2.0.pow(32 - prefixLength).toLong()) - 2 // minus network and broadcast

                return SubnetInfo(
                    ipAddress = ipString,
                    networkPrefixLength = prefixLength,
                    cidr = "${longToIp(baseIp)}/$prefixLength",
                    baseIp = baseIp,
                    numHosts = numHosts
                )
            }
        }
        return null
    }

    /**
     * Attempts to ping an IP using ICMP via the native ping command,
     * which is often more reliable than InetAddress.isReachable on Android.
     */
    suspend fun ping(ip: String): Int? = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("ping", "-c", "1", "-W", "1", ip))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var timeMs: Int? = null
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line?.contains("time=") == true) {
                    // Extract e.g. "time=2.45 ms"
                    val timeStr = line?.substringAfter("time=")?.substringBefore(" ms")
                    timeMs = timeStr?.toFloatOrNull()?.toInt()
                }
            }
            process.waitFor()
            if (process.exitValue() == 0) {
                timeMs ?: 1 // At least 1ms if successful but couldn't parse time
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resolves the hostname by doing a reverse DNS lookup.
     */
    suspend fun resolveHostname(ip: String): String? = withContext(Dispatchers.IO) {
        try {
            val inet = InetAddress.getByName(ip)
            val hostname = inet.hostName
            if (hostname != ip) hostname else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads /proc/net/arp to find the MAC address for a given IP.
     * Note: Android 13+ restricts reading this file for strict apps,
     * but we provide it as a best-effort.
     */
    suspend fun getMacFromArpTable(ip: String): String? = withContext(Dispatchers.IO) {
        var mac: String? = null
        try {
            val reader = BufferedReader(java.io.FileReader("/proc/net/arp"))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val tokens = line!!.split(Regex("\\s+"))
                if (tokens.size >= 4 && ip == tokens[0]) {
                    val macAddress = tokens[3]
                    if (macAddress.matches(Regex("..:..:..:..:..:..")) && macAddress != "00:00:00:00:00:00") {
                        mac = macAddress
                        break
                    }
                }
            }
            reader.close()
        } catch (e: Exception) {
            // Ignore /proc/net/arp read failures
        }
        mac
    }

    fun longToIp(ipLong: Long): String {
        return String.format(
            "%d.%d.%d.%d",
            (ipLong shr 24) and 0xff,
            (ipLong shr 16) and 0xff,
            (ipLong shr 8) and 0xff,
            ipLong and 0xff
        )
    }

    fun ipToLong(ipAddress: String): Long {
        var result: Long = 0
        val ipAddressInArray = ipAddress.split(".")
        if (ipAddressInArray.size != 4) throw IllegalArgumentException("Invalid IP format")
        for (i in 3 downTo 0) {
            val ip = ipAddressInArray[3 - i].toLong()
            if (ip !in 0..255) throw IllegalArgumentException("Invalid IP part")
            result = result or (ip shl (i * 8))
        }
        return result
    }
}
