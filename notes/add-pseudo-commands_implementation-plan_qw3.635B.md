# Bulk Actions Pseudo Commands — Implementation Plan

**Date:** 2026-04-26
**Author:** Qwen Code (qw3.635B)
**Status:** Plan ready for implementation

---

## Overview

Add four new pseudo-commands to Bulk Actions and rename `nmap` → `port-scan`:

| # | Command | Syntax | Args | Description |
|---|---------|--------|------|-------------|
| 1 | `device-info` | `device-info` | none | Outputs device info (IP, MAC, carrier, battery, storage, etc.) |
| 2 | `tracert` | `tracert <host>` | host (required) | ICMP TTL-probe traceroute |
| 3 | `google-timesync` | `google-timesync` | none | Fetches Google time, shows offset / RTT |
| 4 | `lan-scan` | `lan-scan` | none | Scans local WiFi subnet for devices |
| 5 | `port-scan` | `port-scan -p <ports> <host>` | ports + host (required) | TCP port scan (renamed from `nmap`) |

**Breaking change:** `nmap` prefix is no longer recognized. Existing configs must use `port-scan`.

---

## Current State (as of 2026-04-26)

### `BulkActionsRepository.kt` (the executor)

```kotlin
class BulkActionsRepository(
    private val digRepo: DigRepository = DigRepository(),
    private val ntpRepo: NtpRepository = NtpRepository(),
    private val certRepo: HttpsCertRepository = HttpsCertRepository(),
) {
    // …

    suspend fun executeSingleCommand(name: String, cmd: String): BulkCommandResult {
        val trimmed = cmd.trim()
        val parts = trimmed.split(Regex("\\s+"))
        val prefix = parts.firstOrNull()?.lowercase() ?: ""

        return when {
            prefix == "ping"      -> executePing(name, trimmed, parts)
            prefix == "dig"       -> executeDig(name, trimmed)
            prefix == "ntp"       -> executeNtp(name, trimmed)
            prefix == "nmap"      -> executeNmap(name, trimmed)    // ← rename to port-scan
            prefix == "checkcert" -> executeCheckcert(name, trimmed)
            else                  -> executeRaw(name, trimmed)
        }
    }
}
```

**Existing infrastructure we can reuse:**
| New Command | Reuse |
|---|---|
| `device-info` | `SystemInfoRepository(context)` — `getDeviceInfo()` returns `DeviceInfo` |
| `tracert` | Inline `ping -c 1 -t <TTL> -W 2 <host>` (same logic as `TracerouteScreen`) |
| `google-timesync` | `GoogleTimeSyncRepository()` — `fetchGoogleTime()` returns `GoogleTimeSyncResult` |
| `lan-scan` | `LanScannerRepository(context)` — `getLocalSubnetInfo()`, `ping()`, `getMacFromArpTable()`, `resolveHostname()` |
| `port-scan` | Rename `executeNmap` → `executePortScan` (identical Socket logic) |

### `BulkActionsViewModel.kt` (the UI controller)

- Constructor already takes `Context`: `BulkActionsViewModel(context, repository)`
- Factory creates `BulkActionsRepository()` **without** passing `Context` — this is the bug we fix.
- `BulkActionsScreen.kt` calls `viewModel(factory = BulkActionsViewModel.factory(context))` — factory already receives context.

### `BulkConfigParserTest.kt` (tests)

- Test `parseMultipleCommandsPreservesAllCommands` contains `"cmd5": "nmap -p 80-443 mobilutils.com"` — update to `port-scan`.

---

## Changes Required

### 1. `BulkActionsRepository.kt` — Major changes

#### A. Add `Context` to constructor

```kotlin
class BulkActionsRepository(
    private val context: Context,
    private val digRepo: DigRepository = DigRepository(),
    private val ntpRepo: NtpRepository = NtpRepository(),
    private val certRepo: HttpsCertRepository = HttpsCertRepository(),
)
```

#### B. Update the `when` block in `executeSingleCommand()`

```kotlin
return when {
    prefix == "ping"            -> executePing(name, trimmed, parts)
    prefix == "dig"             -> executeDig(name, trimmed)
    prefix == "ntp"             -> executeNtp(name, trimmed)
    prefix == "port-scan"       -> executePortScan(name, trimmed)     // renamed
    prefix == "checkcert"       -> executeCheckcert(name, trimmed)
    prefix == "device-info"     -> executeDeviceInfo(name, trimmed)   // NEW
    prefix == "tracert"         -> executeTracert(name, trimmed)      // NEW
    prefix == "google-timesync" -> executeGoogleTimeSync(name, trimmed) // NEW
    prefix == "lan-scan"        -> executeLanScan(name, trimmed)      // NEW
    else                        -> executeRaw(name, trimmed)
}
```

#### C. Rename `executeNmap` → `executePortScan`

Copy the body verbatim, update the doc comment and the method name. No logic changes.

#### D. Implement `executeDeviceInfo(name, cmd)`

```kotlin
private suspend fun executeDeviceInfo(name: String, cmd: String): BulkCommandResult {
    return withContext(Dispatchers.IO) {
        try {
            val t0 = System.currentTimeMillis()
            val repo = SystemInfoRepository(context)
            val di = repo.getDeviceInfo()
            val dur = System.currentTimeMillis() - t0

            val lines = buildList {
                add("[${timestampFmt(LocalDateTime.now())}] $cmd")
                add("[${timestampFmt(LocalDateTime.now())}] Status: SUCCESS (${dur}ms)")
                add("  Device Name: ${di.deviceName}")
                add("  Android Version: ${di.androidVersion}")
                add("  API Level: ${di.apiLevel}")
                add("  IPv4: ${di.ipv4Address ?: "N/A"}")
                add("  IPv6: ${di.ipv6Address ?: "N/A"}")
                add("  Subnet Mask: ${di.subnetMask ?: "N/A"}")
                add("  Default Gateway: ${di.defaultGateway ?: "N/A"}")
                add("  NTP Server: ${di.ntpServer ?: "N/A"}")
                add("  DNS Servers: ${di.dnsServers?.joinToString(", ") ?: "N/A"}")
                add("  Carrier: ${di.carrierName ?: "N/A"}")
                add("  Wi-Fi SSID: ${di.wifiSSID ?: "N/A"}")
                add("  Battery Level: ${di.batteryLevel ?: "N/A"}%")
                add("  Charging: ${di.isCharging ?: "N/A"}")
                add("  Battery Health: ${di.batteryHealth ?: "N/A"}")
                add("  Time Since Reboot: ${di.timeSinceReboot}")
                add("  IMEI: ${di.imei ?: "Restricted by Android 10+"}")
                add("  Serial: ${di.serialNumber ?: "Restricted by Android 10+"}")
                add("  ICCID: ${di.iccid ?: "Restricted by Android 10+"}")
                add("  Total RAM: ${di.totalRam ?: "N/A"}")
                add("  Available RAM: ${di.availableRam ?: "N/A"}")
                add("  Total Storage: ${di.totalStorage ?: "N/A"}")
                add("  Available Storage: ${di.availableStorage ?: "N/A"}")
                add("  CPU ABI: ${di.cpuAbi?.joinToString(", ") ?: "N/A"}")
                add("  Active Network: ${di.activeNetworkType ?: "N/A"}")
            }
            BulkCommandSuccess(name, cmd, lines, dur)
        } catch (e: Exception) {
            BulkCommandError(name, cmd, e.message ?: "Unknown error")
        }
    }
}
```

#### E. Implement `executeTracert(name, cmd)`

Inline TTL-probing logic (same as `TracerouteScreen`):

```kotlin
private suspend fun executeTracert(name: String, cmd: String): BulkCommandResult {
    return withContext(Dispatchers.IO) {
        try {
            val parts = cmd.split(Regex("\\s+"))
            val host = parts.getOrNull(1)
                ?: return@withContext BulkCommandError(name, cmd, "Usage: tracert <host>")

            val t0 = System.currentTimeMillis()
            val out = mutableListOf<String>()
            var dstReached = false
            var hops = 0

            out.add("[${timestampFmt(LocalDateTime.now())}] $cmd")
            out.add("traceroute to $host, 30 hops max")

            val ttlIpRegex = Regex("""From (\S+).*[Tt]ime to live exceeded""")
            val dstReplyRegex = Regex("""bytes from (\S+):""")
            val ttlAltRegex = Regex("""From (\S+):.*ttl""", RegexOption.IGNORE_CASE)

            for (ttl in 1..30) {
                val num = ttl.toString().padStart(2)
                try {
                    val proc = Runtime.getRuntime().exec(
                        arrayOf("ping", "-c", "1", "-t", ttl.toString(), "-W", "2", host)
                    )
                    val stdout = proc.inputStream.bufferedReader().readText()
                    val stderr = proc.errorStream.bufferedReader().readText()
                    proc.waitFor()
                    val elapsed = System.currentTimeMillis() - t0
                    val combined = stdout + "\n" + stderr

                    when {
                        ttlIpRegex.containsMatchIn(combined) -> {
                            val ip = ttlIpRegex.find(combined)!!.groupValues[1].trimEnd(':')
                            out.add("$num  $ip  ${elapsed} ms")
                            hops++
                        }
                        dstReplyRegex.containsMatchIn(combined) -> {
                            val ip = dstReplyRegex.find(combined)!!.groupValues[1].trimEnd(':')
                            val rtt = Regex("""time[=<]([\d.]+) ms""").find(combined)
                                ?.groupValues?.get(1)?.let { "$it ms" } ?: "${elapsed} ms"
                            out.add("$num  $ip  $rtt")
                            hops++
                            dstReached = true
                            break
                        }
                        ttlAltRegex.containsMatchIn(combined) -> {
                            val ip = ttlAltRegex.find(combined)!!.groupValues[1].trimEnd(':')
                            out.add("$num  $ip  ${elapsed} ms")
                            hops++
                        }
                        else -> out.add("$num  * * *")
                    }
                } catch (_: Exception) {
                    out.add("$num  * * *")
                }
            }

            val dur = System.currentTimeMillis() - t0
            out.add("[${timestampFmt(LocalDateTime.now())}] Status: COMPLETE (${dur}ms)")
            out.add("  Reachable hops: $hops, Destination reached: $dstReached")
            BulkCommandSuccess(name, cmd, out, dur)
        } catch (e: Exception) {
            BulkCommandError(name, cmd, e.message ?: "Unknown error")
        }
    }
}
```

#### F. Implement `executeGoogleTimeSync(name, cmd)`

```kotlin
private suspend fun executeGoogleTimeSync(name: String, cmd: String): BulkCommandResult {
    return withContext(Dispatchers.IO) {
        try {
            val t0 = System.currentTimeMillis()
            val repo = GoogleTimeSyncRepository()
            val result = repo.fetchGoogleTime()
            val dur = System.currentTimeMillis() - t0

            val lines = buildList {
                add("[${timestampFmt(LocalDateTime.now())}] $cmd")
                when (result) {
                    is GoogleTimeSyncResult.Success -> {
                        add("[${timestampFmt(LocalDateTime.now())}] Status: SUCCESS (${dur}ms)")
                        add("  Server Time:  ${Date(result.data.serverTimeMillis)}")
                        add("  RTT:          ${result.data.rttMillis} ms")
                        add("  Offset:       ${result.data.offsetMillis} ms")
                        add("  Corrected:    ${Date(result.data.correctedServerTimeMillis)}")
                    }
                    is GoogleTimeSyncResult.NoNetwork ->
                        add("[${timestampFmt(LocalDateTime.now())}] Status: NO NETWORK (${dur}ms)")
                    is GoogleTimeSyncResult.Timeout ->
                        add("[${timestampFmt(LocalDateTime.now())}] Status: TIMEOUT (${dur}ms)")
                    is GoogleTimeSyncResult.HttpError ->
                        add("[${timestampFmt(LocalDateTime.now())}] Status: HTTP ${result.code} (${dur}ms)")
                    is GoogleTimeSyncResult.ParseError ->
                        add("[${timestampFmt(LocalDateTime.now())}] Status: PARSE ERROR - ${result.message} (${dur}ms)")
                    is GoogleTimeSyncResult.Error ->
                        add("[${timestampFmt(LocalDateTime.now())}] Status: ERROR - ${result.message} (${dur}ms)")
                }
            }
            BulkCommandSuccess(name, cmd, lines, dur)
        } catch (e: Exception) {
            BulkCommandError(name, cmd, e.message ?: "Unknown error")
        }
    }
}
```

#### G. Implement `executeLanScan(name, cmd)`

```kotlin
private suspend fun executeLanScan(name: String, cmd: String): BulkCommandResult {
    return withContext(Dispatchers.IO) {
        try {
            val t0 = System.currentTimeMillis()
            val repo = LanScannerRepository(context)
            val subnet = repo.getLocalSubnetInfo()
                ?: return@withContext BulkCommandError(name, cmd, "No active WiFi network found")

            val out = mutableListOf<String>()
            out.add("[${timestampFmt(LocalDateTime.now())}] $cmd")
            out.add("[${timestampFmt(LocalDateTime.now())}] Subnet: ${subnet.cidr}")
            out.add("[${timestampFmt(LocalDateTime.now())}] Scanning up to 256 hosts…")

            val devices = mutableListOf<String>()
            val limit = minOf(subnet.numHosts, 256L)
            for (i in 0 until limit) {
                val ip = repo.longToIp(subnet.baseIp + i + 1)
                val rtt = repo.ping(ip) ?: continue
                val mac = repo.getMacFromArpTable(ip)
                val host = repo.resolveHostname(ip)
                val isRouter = (i == 0)

                val info = buildString {
                    append(ip)
                    mac?.let { append(" MAC:$it") }
                    host?.let { append(" Host:$it") }
                    append(" RTT:${rtt}ms")
                    if (isRouter) append(" [ROUTER]")
                }
                devices.add(info)
            }

            val dur = System.currentTimeMillis() - t0
            out.add("[${timestampFmt(LocalDateTime.now())}] Status: COMPLETE (${dur}ms)")
            out.add("  Devices found: ${devices.size}")
            devices.forEach { out.add("  $it") }
            BulkCommandSuccess(name, cmd, out, dur)
        } catch (e: Exception) {
            BulkCommandError(name, cmd, e.message ?: "Unknown error")
        }
    }
}
```

---

### 2. `BulkActionsViewModel.kt` — Minor changes

#### A. Update the factory to pass `Context` to repository

```kotlin
companion object {
    fun factory(context: Context): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BulkActionsViewModel(
                    context = context.applicationContext,
                    repository = BulkActionsRepository(context.applicationContext),  // ← pass Context
                ) as T
        }
}
```

---

### 3. `BulkConfigParserTest.kt` — Minor update

Change the test that references `nmap`:

```kotlin
// Before:
"cmd5": "nmap -p 80-443 mobilutils.com"

// After:
"cmd5": "port-scan -p 80-443 mobilutils.com"
```

---

### 4. `README.md` — Minor update

Update the Bulk Actions section to list all supported commands:

```markdown
### ⚡ Bulk Actions

- Supports built-in command types: `ping`, `dig`, `ntp`, `port-scan`, `checkcert`, `device-info`, `tracert`, `google-timesync`, `lan-scan`
```

---

### 5. `notes/20260425_BulkActions-viaLocalConfigFile.md` — Minor update

Update the command mapping table to include new commands and note the `nmap` → `port-scan` rename.

---

## Example Config File

```json
{
  "output-file": "~/Downloads/bulk-output.txt",
  "run": {
    "info": "device-info",
    "trace_google": "tracert google.com",
    "time_sync": "google-timesync",
    "local_network": "lan-scan",
    "port_scan_1": "port-scan -p 80,443 google.com",
    "port_scan_2": "port-scan -p 22-8080 example.com"
  }
}
```

---

## Test Updates

### New tests to add to `BulkActionsRepositoryTest.kt` (or extend existing)

| Test | What it verifies |
|---|---|
| `executeDeviceInfo_success` | Returns `BulkCommandSuccess` with device fields |
| `executeDeviceInfo_exception` | Returns `BulkCommandError` on repo failure |
| `executeTracert_success` | Parses TTL hops, detects destination |
| `executeTracert_missingHost` | Returns error: "Usage: tracert \<host\>" |
| `executeGoogleTimeSync_success` | Returns server time, RTT, offset |
| `executeGoogleTimeSync_noNetwork` | Returns `NoNetwork` mapped to error line |
| `executeLanScan_success` | Returns device list from subnet |
| `executeLanScan_noNetwork` | Returns error: "No active WiFi network found" |
| `executePortScan_success` | Parses `-p ports host`, reports open ports |
| `nmap_noLongerRecognized` | Prefix `nmap` falls through to `executeRaw` |

---

## Permissions Required

All new commands reuse permissions already declared in `AndroidManifest.xml`:

| Permission | Used by |
|---|---|
| `INTERNET` | All commands |
| `ACCESS_NETWORK_STATE` | LAN scan subnet detection |
| `ACCESS_WIFI_STATE` | LAN scan WiFi detection |
| `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` | LAN scan SSID |
| `READ_PHONE_STATE` | Device info IMEI/ICCID (restricted Android 10+) |

---

## Files to Modify

| File | Change | Description |
|---|---|---|
| `BulkActionsRepository.kt` | **Major** | Add `Context` param, rename `nmap`→`port-scan`, add 4 executors |
| `BulkActionsViewModel.kt` | **Minor** | Pass `Context` to `BulkActionsRepository()` in factory |
| `BulkConfigParserTest.kt` | **Minor** | Update `nmap` reference to `port-scan` |
| `README.md` | **Minor** | Update command list in Bulk Actions section |
| `notes/20260425_BulkActions-viaLocalConfigFile.md` | **Minor** | Update command mapping table |

---

## Risk & Edge Cases

1. **Context lifecycle** — `BulkActionsRepository` now holds a `Context`. The ViewModel factory passes `context.applicationContext` to avoid memory leaks.

2. **Traceroute on Android** — Uses `ping -c 1 -t TTL` which requires root or `CAP_NET_RAW`. On non-rooted devices, TTL probes may always fail → output will show `* * *` for each hop. This is expected Android behavior.

3. **LAN scan performance** — Limited to 256 hosts (effectively a /24) to avoid extremely long scans on larger subnets.

4. **Breaking change** — `nmap` prefix no longer works. Any saved config files or user bookmarks referencing `nmap` will fall through to `executeRaw`, which will fail with exit code 127 ("command not found"). Consider adding a deprecation warning in a future release.

---

## Implementation Order (recommended)

1. Rename `executeNmap` → `executePortScan` in `BulkActionsRepository.kt`
2. Add `Context` parameter to `BulkActionsRepository` constructor
3. Add the 4 new `when` branches + executor methods to `executeSingleCommand()`
4. Implement `executeDeviceInfo`, `executeTracert`, `executeGoogleTimeSync`, `executeLanScan`
5. Update `BulkActionsViewModel.factory()` to pass `Context`
6. Update `BulkConfigParserTest.kt` (nmap → port-scan)
7. Update `README.md` and notes files
8. Add unit tests for new executors
