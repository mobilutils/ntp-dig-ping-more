# Network Utilities Checker

<p align="left">
  <img src="notes/icon_ntp-dig-ping-more_macgiver.png" alt="App Icon" width="128" height="128">
</p>

A modern Android app for network diagnostics: **NTP reachability testing**, **DNS lookup (DIG)**, **Ping**, **Traceroute**, **Port Scanner**, **LAN Scanner**, **Google Time Sync**, **HTTPS Certificate Inspector**, and **Bulk Actions** (batch execute commands from a JSON config). Includes a global **Settings** screen with configurable operation timeouts and optional **Proxy PAC** routing.

## Visuals

| NTP Check| DIG | PING |
|---|---|---|
|![NTP](screenshots/v2.2/Screenshot_20260307_123547.png)|![DIG](screenshots/v2.2/Screenshot_20260307_123659.png)|![PING](screenshots/v2.2/Screenshot_20260307_123719.png)|
|Port Scanner|Traceroute| |
|![Port Scanner](screenshots/v2.2/Screenshot_20260307_125815.png)|![Traceroute](screenshots/v2.2/Screenshot_20260307_123802.png)| ![LAN Scanner](screenshots/v2.2/Screenshot_20260307_130032.png)|
| Overlay menu (MORE)| | |
|![Overlay menu](screenshots/v2.2/Screenshot_20260307_123727.png)| | |


## Features

### 🕐 NTP Check
- Enter any NTP server address and port (defaults to `pool.ntp.org:123`)
- Displays:
  - ✅ / ❌ Reachability status
  - 🕒 Server Time
  - ⏱ Clock Offset (ms)
  - 📡 Round-Trip Delay (ms)
- Last 5 queries kept as clickable history (persisted across app restarts)
- Graceful error handling: DNS failure, timeout, no network

### 🔍 DIG Test
- Enter a DNS server (IP or FQDN, e.g. `8.8.8.8`) and a name to resolve
- Query goes directly to the specified DNS server (API 29+), not the system resolver
- Displays a real `dig`-style answer section with aligned columns:

```
;; SERVER: 8.8.8.8

;; QUESTION SECTION:
;www.mobilutils.eu.    IN    A

;; ANSWER SECTION:
www.mobilutils.eu.     10800  IN  CNAME  connect.hostinger.com.
connect.hostinger.com.   120  IN  A      34.120.137.41
```

- Full CNAME chain resolution included
- Graceful error handling: NXDOMAIN, resolver unreachable, no network

### 📡 Ping
- Enter any hostname or IP address
- Tap **Ping** to start — button turns red and becomes **Stop** while running
- Output streams live in a scrolling monospace terminal card
- Sends up to 100 ICMP packets (`ping -c 100`); can be stopped at any time
- History icon reflects the outcome:
  - ✅ All packets went through
  - 🤷‍♂️ At least one succeeded but some were lost
  - ❌ No reply received
- Last 5 pinged hosts kept as clickable history (persisted across app restarts)

### 🛤 Traceroute
- Enter any hostname or IP address
- Tap **Traceroute** to start — button turns red and becomes **Stop** while running
- Output streams live hop-by-hop in a scrolling monospace terminal card
- Implemented via `ping -c 1 -t <TTL>` probing (no `traceroute` binary required)
- Each hop that responds with ICMP Time Exceeded reveals its IP and round-trip time
- Probes up to 30 hops; stops automatically when the destination replies
- History icon reflects the outcome:
  - ✅ Destination reached (all or most hops replied)
  - 🤷‍♂️ Some hops replied but destination not reached
  - ❌ No hop replied at all
- Last 5 traced hosts kept as clickable history (persisted across app restarts)

### 🕵️ Port Scanner
- Check which TCP or UDP ports are open on a specific IP or hostname
- Define custom start and end port ranges
- Select between TCP or UDP scanning protocols
- Live progress bar and dynamically updating list of discovered open ports
- Concurrently scans ports to ensure high performance
- Last 5 scans kept as clickable history (persisted across app restarts)

### 🖥️ LAN Scanner
- Discover active devices on your local Wi-Fi or Ethernet subnet
- Custom scanning ranges with pre-populated values for your current subnet
- Quick Scan (common IPs) or Full Scan (custom range sweep)
- Live list updating with IP, Hostname, MAC address, and latency (ms) for each discovered device
- Tracks scan progress with a stop/cancel capability
- History of past scans persisted across app restarts

### 🌐 Google Time Sync
- Queries `http://clients2.google.com/time/1/current` over HTTP and parses Google's time response
- Strips the `)]}'` XSSI protection prefix before JSON parsing
- Computes full NTP-style time synchronisation metrics:
  - 📡 **RTT** — round-trip time (T4 − T1)
  - ⏱ **Clock Offset** — `correctedServerTime − T4` (positive = local clock behind, negative = ahead)
  - 🧮 **Corrected Server Time** — `serverTime + RTT / 2`
- Color-coded offset indicator: 🟢 < 100 ms · 🟠 < 500 ms · 🔴 ≥ 500 ms
- Custom host field (defaults to `clients2.google.com`)
- One-tap **Copy Offset** button for manual clock-adjustment workflows
- Idle / Loading / Success / Error states survive rotation and config changes
- Supports optional proxy routing when Proxy PAC is enabled in Settings

### 🔒 HTTPS Certificate Inspector
- Enter any hostname and port (defaults to `google.com:443`)
- Performs a real TLS handshake and extracts the peer's leaf certificate
- Displays:
  - Subject / Issuer distinguished names (CN, O, OU, C)
  - Validity period (Not Before / Not After) with days-until-expiry
  - Validity status: ✅ Valid · ⚠️ Expiring Soon · ❌ Expired
  - Serial number, SHA-256 and SHA-1 fingerprints (tap to copy)
  - Subject Alternative Names (SANs)
  - Key algorithm, key size, signature algorithm, chain depth
- Detects untrusted chains and self-signed certificates without bypassing security
- Last 5 inspected hosts kept as clickable history (persisted across app restarts)
- Supports SSL tunneling through HTTP CONNECT when Proxy PAC is enabled

### ⚙️ Settings
- **Operation Timeout** — global timeout (1–60 s) applied to all network tools
  - Changes saved immediately on valid input; reverts on focus-loss if invalid
- **Proxy PAC Configuration** — app-level proxy override (independent of system proxy)
  - Toggle proxy routing on/off
  - PAC URL input with URL format validation (http/https only)
  - PAC scripts evaluated with a lightweight JavaScript engine (QuickJS)
  - PAC results cached with 5-minute TTL for performance
  - Supports `PROXY`, `SOCKS`, and `DIRECT` directives with fallback chains
  - **"Test Proxy/PAC"** button sends a HEAD request through the resolved proxy and reports latency
  - Last test result and timestamp persisted across app restarts
  - Applied to Google Time Sync (HTTP) and HTTPS Certificate Inspector (SSL CONNECT tunnel)
  - All failures fall back silently to DIRECT — proxy issues never block normal usage

### 📱 Device Info
- Comprehensive read-only view of device identity, network, battery, and security
- Displays: Device Name, IMEI, Serial Number, ICCID, Android Version, API Level, CPU Architecture
- Current Device Time (auto-updating), Time Since Boot, Time Since Screen-Off
- Network: IPv4/IPv6, Subnet Mask, Default Gateway, DNS Servers, NTP Server, Active Network Type
- Wi-Fi SSID, Mobile Carrier/Operator name
- Battery Level, Charging Status, Health
- Total/Available RAM & Internal Storage
- MDM/Device Policy Status (Device Owner, Profile Owner, Managed Profile, or None)
- Installed System & User CA Certificates (subject, validity dates, type)
- Handles Android 10+ API restrictions with clear fallback messages (e.g., "Restricted by Android 10+")
- Runtime permission requests via `ActivityResultContracts`; rationale shown if denied

### ⚡ Bulk Actions
- Load a JSON configuration file defining multiple diagnostic commands to execute in sequence
- Supports built-in command types: `ping`, `dig`, `ntp`, `port-scan`, `checkcert`, `device-info`, `tracert`, `google-timesync`, `lan-scan`
- Unknown command prefixes fall back to raw shell execution
- Each command runs with a 30-second timeout; failures and timeouts are captured per-command without stopping the batch
- Real-time progress bar with command-by-command status (SUCCESS / ERROR / TIMEOUT)
- Terminal-style output with color-coded results
- Auto-saves results to the `"output-file"` path defined in the config after execution (with writability validation)
- **"Validate Config"** button checks JSON structure and output-file writability, suggesting a fallback path if needed
- **"Write to File"** button launches an SAF picker for manual save location selection
- Graceful error handling: invalid JSON, unwritable paths, network failures — all captured and displayed without crashing

#### Supported Pseudo-Commands

| Command | Syntax | Description |
|---|---|---|
| `ping` | `ping -c N [-t W] host` | ICMP ping (N packets, -t W = per-packet wait in seconds + coroutine timeout) |
| `dig` | `dig @server fqdn [-t T]` | DNS lookup to custom server (-t T = coroutine timeout in seconds) |
| `ntp` | `ntp [pool] [-t T]` | NTP query (-t T = coroutine timeout in seconds) |
| `port-scan` | `port-scan [-p ports] [-t S] host` | TCP port scan (-t S = connect timeout per port in seconds; ports defaults to 22) |
| `checkcert` | `checkcert -p port [-t T] host` | HTTPS certificate check (-t T = coroutine timeout in seconds) |
| `device-info` | `device-info` | Device identity, network, battery, storage (no -t) |
| `tracert` | `tracert host [-t H]` | TTL-probing traceroute (-t H = max hops, also sets coroutine timeout) |
| `google-timesync` | `google-timesync` | Google time sync (no -t) |
| `lan-scan` | `lan-scan` | LAN subnet device discovery (no -t) |

**Timeout precedence:** per-command `-t` > config-level `"timeout"` > default 30s.

### 🤖 ADB Automation (headless / CI)

Bulk Actions supports fully automated execution via ADB intent extras — **no user interaction required**.

#### Working commands (API 33+)

On Android 13+ (API 33), `READ_EXTERNAL_STORAGE` is no longer grantable and `file://` URIs pointing to `/sdcard/` always throw `EACCES`. The working approach uses the **app's private directory** and `run-as` for both push and pull:

```bash
APP_ID="io.github.mobilutils.ntp_dig_ping_more"
PRIVATE_DIR="/data/user/0/$APP_ID/files/files"

# 1. Push config (host-side pipe via run-as — avoids shell/sdcard permission issues)
cat notes/config-files_bulk-actions/blkacts_single_ping_success.json \
  | adb shell "run-as $APP_ID sh -c 'cat > $PRIVATE_DIR/blkacts_single_ping_success.json'"

# 2. Launch with auto-load + auto-run
#    IMPORTANT: use --ez (boolean), NOT --es (string) — --es silently fails
adb shell am force-stop "$APP_ID"
adb shell am start \
    -n "$APP_ID/.MainActivity" \
    -d "file://$PRIVATE_DIR/blkacts_single_ping_success.json" \
    --ez auto_run true

# 3. Wait for execution to complete
sleep 60

# 4. Pull results (adb pull cannot read private dir — use run-as cat instead)
adb shell "run-as $APP_ID cat $PRIVATE_DIR/blkacts_single_ping_success.txt" \
  > ./test-results/blkacts_single_ping_success.txt
```

#### Common pitfalls

| Mistake | Symptom | Correct approach |
|---------|---------|-----------------|
| `--es auto_run true` | App loads config but never runs (Android logs: *"expected Boolean but value was String"*) | Use `--ez auto_run true` |
| `adb push ... /sdcard/Download/` + `file:///sdcard/...` | `EACCES (Permission denied)` on SDK 33+ — `READ_EXTERNAL_STORAGE` is no longer grantable | Push to `$PRIVATE_DIR` via `run-as` pipe |
| `adb pull /data/user/0/<pkg>/files/...` | `Permission denied` — `adb` runs as `shell` user | Use `adb shell "run-as <pkg> cat <file>"` and redirect to host |
| `adb shell "run-as <pkg> cp <file> /sdcard/..."` | `Permission denied` — `run-as` cannot write to `/sdcard/` | Use the host-redirect pull approach above |

#### Bundled script
##### Mac & Linux / Unix

```bash
# Single config (default emulator)
./BULKACTIONS-ADB-SCRIPT.sh blkacts_single_ping_success.json

# Specific emulator
./BULKACTIONS-ADB-SCRIPT.sh blkacts_multi_all9_success.json Medium_Phone_API_35

# Fully unattended — no interactive prompts at the end
./BULKACTIONS-ADB-SCRIPT.sh blkacts_multi_all9_success.json "" --no-interact

# Show emulator window during run
./BULKACTIONS-ADB-SCRIPT.sh blkacts_single_ping_success.json "" --show-emulator
```

The script handles emulator startup, push, launch, wait for marker file .running-tasks to be create, then to be removed, finaly pull the resulting file automatically.

##### Windows
###### /!\ This needs to be tests 
```bat
:: Single config (default emulator)
BULKACTIONS-ADB-WINDOWS-SCRIPT.bat blkacts_single_ping_success.json

:: Specific emulator
BULKACTIONS-ADB-WINDOWS-SCRIPT.bat blkacts_multi_all9_success.json Medium_Phone_API_35

:: Fully unattended — no interactive prompts at the end
BULKACTIONS-ADB-WINDOWS-SCRIPT.bat blkacts_multi_all9_success.json "" --no-interact

:: Show emulator window during run
BULKACTIONS-ADB-WINDOWS-SCRIPT.bat blkacts_single_ping_success.json "" --show-emulator
```

The Windows script provides the same automation as the Unix version: emulator startup, config push, app launch with intent extras, polling for the `.running-tasks` marker file (creation then removal), and automatic results pull. It uses PowerShell for JSON field extraction and native CMD constructs throughout.

See [notes/20260501_BulkActions-ADB-Script-fixed.md](notes/20260501_BulkActions-ADB-Script-fixed.md) for the full root-cause analysis of every fix applied.

## Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM (ViewModel + StateFlow) |
| Navigation | Jetpack Navigation Compose |
| Concurrency | Kotlin Coroutines (`Dispatchers.IO`) |
| NTP | Apache Commons Net 3.11.1 (`NTPUDPClient`) |
| DNS | dnsjava 3.6.2 (`SimpleResolver`) |
| JS Engine | QuickJS 0.9.2 (`app.cash.quickjs`) — PAC script evaluation |
| Persistence | AndroidX DataStore (history + global settings) |
| Testing | JUnit 4, MockK 1.13, Coroutines Test |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 (Android 15) |

## Project Structure

```
app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/
├── MainActivity.kt              # NavHost, bottom navigation bar, NTP screen UI
├── MoreToolsScreen.kt           # Overflow screen: Settings, Traceroute, Port Scanner, etc.
├── NtpRepository.kt             # NTP network I/O (NTPUDPClient, sealed NtpResult)
├── NtpViewModel.kt              # NTP UI state (StateFlow<NtpUiState>), coroutine lifecycle
├── NtpHistoryStore.kt           # DataStore persistence for NTP query history
├── DigScreen.kt                 # DIG test screen composable
├── DigViewModel.kt              # DIG UI state, delegates to DigRepository
├── DigRepository.kt             # DNS resolution via dnsjava SimpleResolver
├── PingScreen.kt                # Ping screen composable
├── PingViewModel.kt             # Ping UI state, process lifecycle, three-state status
├── PingHistoryStore.kt          # DataStore persistence for Ping history
├── TracerouteScreen.kt          # Traceroute screen composable
├── TracerouteViewModel.kt       # TTL-probing traceroute via ping, hop parsing, status
├── TracerouteHistoryStore.kt    # DataStore persistence for Traceroute history
├── PortScannerScreen.kt         # Port Scanner screen composable
├── PortScannerViewModel.kt      # Port Scanner UI state, concurrent scanning logic
├── PortScannerHistoryStore.kt   # DataStore persistence for Port Scanner history
├── LanScannerScreen.kt          # LAN Scanner screen composable
├── LanScannerViewModel.kt       # LAN Scanner UI state, concurrent ping/ARP sweep
├── LanScannerRepository.kt      # Networking logic, subnet detection, ARP parsing
├── LanScannerHistoryStore.kt    # DataStore persistence for LAN Scanner history
├── GoogleTimeSyncRepository.kt  # HTTP fetch, XSSI strip, JSON parse, T1/T4 offset calc
├── GoogleTimeSyncViewModel.kt   # Idle/Loading/Success/Error StateFlow, syncTime() & reset()
├── GoogleTimeSyncScreen.kt      # Google Time Sync screen composable
├── HttpsCertRepository.kt       # TLS handshake, cert extraction, CONNECT tunnel for proxied SSL
├── HttpsCertViewModel.kt        # HTTPS Cert Inspector state, history, fetchCert()
├── HttpsCertScreen.kt           # HTTPS Certificate screen composable
├── HttpsCertHistoryStore.kt     # DataStore persistence for cert inspection history
├── SettingsViewModel.kt         # Settings state: timeout, proxy config, PAC URL validation
├── SettingsScreen.kt            # Settings screen: timeout input, proxy toggle/URL/test
├── settings/
│   ├── SettingsDataStore.kt     # DataStore keys: timeout, proxy_enabled, pac_url, etc.
│   ├── SettingsRepository.kt    # Reactive flows + mutations for all persisted settings
│   └── ProxyConfig.kt           # Data class for proxy PAC configuration
├── proxy/
│   ├── JsEngine.kt              # Interface for PAC script evaluation
│   ├── QuickJsEngine.kt         # QuickJS-based FindProxyForURL evaluator
│   └── ProxyResolver.kt         # PAC fetch, eval, parse, cache, proxy test
├── deviceinfo/
│   ├── DeviceInfoModels.kt      # Data models: DeviceInfo, CertificateInfo, DeviceInfoState
│   ├── SystemInfoRepository.kt  # System API calls: identity, network, battery, storage, MDM, certs
│   ├── DeviceInfoViewModel.kt   # StateFlow<DeviceInfoState>, periodic updates
│   └── DeviceInfoScreen.kt      # Compose UI: Scaffold, LazyColumn, Cards, permission handling
└── ui/theme/                    # Material 3 colors, typography, theme
```

## Requirements

- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 35 installed
- A device or emulator running Android 8.0+ (API 26+)

## Running the App

### Android Studio

1. Open Android Studio → **File → Open** → select this folder
2. Wait for Gradle sync to complete
3. Connect a device or start an emulator
4. Press **▶ Run**

### Command Line

```bash
# Build and install debug APK
./gradlew installDebug

# Launch on connected device
adb shell am start -n io.github.mobilutils.ntp_dig_ping_more/.MainActivity
```

## Testing

This project includes a unit test suite (255 tests) covering business logic, ViewModels, proxy resolution, and data parsing.

```bash
# Run all unit tests
./gradlew test

# Run specific test class
./gradlew testDebugUnitTest --tests "io.github.mobilutils.ntp_dig_ping_more.ProxyResolverTest"
```

See [TESTING.md](TESTING.md) for details.

## Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
```

`INTERNET`, `ACCESS_NETWORK_STATE`, and `ACCESS_WIFI_STATE` are normal permissions (auto-granted at install). `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, and `READ_PHONE_STATE` are dangerous permissions requested at runtime via `ActivityResultContracts`. They are needed for Wi-Fi SSID, carrier name, IMEI, ICCID, and serial number.

> **Note:** `android:usesCleartextTraffic="true"` is set in `AndroidManifest.xml` because the Google Time Sync endpoint (`http://clients2.google.com/time/1/current`) is served over plain HTTP. All other features use HTTPS or non-HTTP protocols (UDP/ICMP/TCP sockets).

## Error States

| Error | Cause |
|---|---|
| DNS Failure | Hostname could not be resolved |
| NXDOMAIN | Queried name does not exist |
| Timeout | Server did not respond within the timeout window |
| No Network | Device has no active internet connection |
| HTTP Error | Non-200 response from the Google Time endpoint |
| Parse Error | Response body missing XSSI prefix or invalid JSON |
| Untrusted Chain | TLS certificate chain failed PKIX validation |
| Cert Expired | TLS certificate validity period has lapsed |
| CONNECT Failed | Proxy rejected the HTTP CONNECT tunnel request |
| Error | Any other unexpected exception |

## License

MIT
