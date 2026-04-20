# Project Context: NTP DIG PING MORE

## Overview

A modern Android app (min SDK 26, target SDK 36) providing **network diagnostics tools**: NTP Check, DNS Lookup (DIG), Ping, Traceroute, Port Scanner, LAN Scanner, Google Time Sync, and Device Info. Built with **Kotlin**, **Jetpack Compose (Material 3)**, **MVVM architecture**, and **Kotlin Coroutines**.

The app is packaged under `io.github.mobilutils.ntp_dig_ping_more` (version 2.3, versionCode 6).

---

## Project Structure

```
app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/
├── MainActivity.kt              # NavHost + bottom navigation bar
├── NtpScreen.kt                 # NTP check screen
├── NtpRepository.kt             # NTPUDPClient I/O
├── NtpViewModel.kt              # NTP UI state (StateFlow)
├── NtpHistoryStore.kt           # DataStore persistence (last 5 queries)
├── DigScreen.kt                 # DIG / DNS lookup screen
├── DigRepository.kt             # dnsjava SimpleResolver I/O
├── DigViewModel.kt              # DIG UI state
├── DigHistoryStore.kt           # DataStore persistence
├── PingScreen.kt                # Ping screen
├── PingViewModel.kt             # Ping UI state, process lifecycle
├── PingHistoryStore.kt          # DataStore persistence
├── TracerouteScreen.kt          # Traceroute screen (TTL-probing ICMP)
├── TracerouteViewModel.kt       # Traceroute UI state
├── TracerouteHistoryStore.kt    # DataStore persistence
├── PortScannerScreen.kt         # Port Scanner screen
├── PortScannerViewModel.kt      # Concurrent TCP/UDP scanning
├── PortScannerHistoryStore.kt   # DataStore persistence
├── LanScannerScreen.kt          # LAN Scanner screen
├── LanScannerViewModel.kt       # Subnet sweep, concurrent pings
├── LanScannerRepository.kt      # Subnet detection, ARP parsing
├── LanScannerHistoryStore.kt    # DataStore persistence
├── GoogleTimeSyncScreen.kt      # Google Time Sync screen
├── GoogleTimeSyncViewModel.kt   # Idle/Loading/Success/Error StateFlow
├── GoogleTimeSyncRepository.kt  # HTTP fetch, XSSI strip, offset calc
├── GoogleTimeSyncHistoryStore.kt # DataStore persistence
├── HttpsCertScreen.kt           # HTTPS certificate checker
├── HttpsCertViewModel.kt        # HTTPS cert UI state
├── HttpsCertRepository.kt       # HTTPS cert I/O
├── HttpsCertHistoryStore.kt     # DataStore persistence
├── deviceinfo/
│   ├── DeviceInfoModels.kt      # Data models (DeviceInfo, CertificateInfo)
│   ├── SystemInfoRepository.kt  # Identity, network, battery, storage, certs
│   ├── DeviceInfoViewModel.kt   # StateFlow<DeviceInfoState>, periodic updates
│   └── DeviceInfoScreen.kt      # Compose UI: Scaffold, LazyColumn, Cards
└── ui/theme/
    ├── Color.kt                 # Material 3 color palette
    ├── Theme.kt                 # AppTheme, dark mode support
    └── Type.kt                  # Typography
```

**37 Kotlin source files** total across the main source set.

---

## Building and Running

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 36 installed
- Java 11+ (compileOptions sourceCompatibility/targetCompatibility)

### Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Install debug APK on connected device
./gradlew installDebug

# Run all unit tests
./gradlew test

# Run Android instrumentation tests
./gradlew connectedAndroidTest

# Launch on connected device
adb shell am start -n io.github.mobilutils.ntp_dig_ping_more/.MainActivity
```

---

## Key Dependencies

| Dependency | Purpose |
|---|---|
| `commons-net` (Apache Commons Net 3.11.1) | NTP UDP packet exchange |
| `dnsjava` 3.6.2 | DNS resolution (SimpleResolver, CNAME chains) |
| `androidx.datastore.preferences` | Persistent query history |
| `androidx.navigation.compose` | Bottom navigation + screen routing |
| `androidx.lifecycle.viewmodel.compose` | ViewModel integration with Compose |
| `org.json` (platform) | Google Time Sync JSON parsing |
| `java.net.HttpURLConnection` | Google Time Sync HTTP request |

### Test Dependencies
- JUnit 4, MockK 1.13, Kotlin Coroutines Test (70+ unit tests)

---

## Architecture Patterns

- **MVVM**: Each tool has a `*Screen.kt` (Compose UI), `*ViewModel.kt` (StateFlow-based UI state), and `*Repository.kt` (network I/O).
- **StateFlow**: All ViewModels expose `StateFlow<UiState>` for declarative UI updates.
- **Coroutines**: All network I/O runs on `Dispatchers.IO`.
- **Persistence**: Each tool has a corresponding `*HistoryStore.kt` using DataStore to persist the last 5 queries.
- **Sealed Classes**: Repository results use sealed classes (e.g., `NtpResult`) for typed error handling.
- **Navigation**: Jetpack Navigation Compose with a bottom navigation bar for primary tools; a "MORE" overflow menu for secondary tools.

---

## Permissions

**Normal (auto-granted):**
- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`

**Dangerous (runtime via ActivityResultContracts):**
- `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `READ_PHONE_STATE`
- Needed for Wi-Fi SSID, carrier name, IMEI, ICCID, serial number.

**Note:** `android:usesCleartextTraffic="true"` is set in `AndroidManifest.xml` for the Google Time Sync HTTP endpoint.

---

## Error Handling

All tools use sealed result classes to represent success/failure states:

| Error | Cause |
|---|---|
| DNS Failure / NXDOMAIN | Hostname unresolvable or doesn't exist |
| Timeout | Server didn't respond within timeout window |
| No Network | No active internet connection |
| HTTP Error | Non-200 response from Google Time endpoint |
| Parse Error | Invalid response format (XSSI prefix / JSON) |
| General Error | Unexpected exception |

---

## Coding Conventions (Inferred)

- **Kotlin code style**: `official` (per `gradle.properties`)
- **JVM target**: 11
- **AndroidX**: Enabled (`android.useAndroidX=true`)
- **Non-transitive R classes**: Enabled (`android.nonTransitiveRClass=true`)
- **ProGuard/R8**: Enabled for release builds (minify enabled)
- **Packaging exclusions**: META-INF license/notice files from commons-net, dnsjava, netty excluded to avoid APK merge conflicts
- **Naming**: `*Screen.kt` (Compose), `*ViewModel.kt` (AndroidX ViewModel), `*Repository.kt` (I/O), `*HistoryStore.kt` (DataStore)
- **Package**: `io.github.mobilutils.ntp_dig_ping_more` (with subpackage `deviceinfo` and `ui.theme`)

---

## Testing

- 70+ unit tests covering ViewModels, repositories, and data parsing
- Tests run on JVM (no Android instrumentation needed for business logic)
- MockK used for mocking Android APIs and coroutines
- Test files live in `app/src/test/java/`

---

## Notes for Development

1. **Target SDK**: `defaultTargetSdkVersion` is set to 36 in the root `build.gradle.kts` via `extra`. The app module reads this with `targetSdk { version = release(rootProject.extra["defaultTargetSdkVersion"] as Int) }`.
2. **Google Time Sync**: Uses plain HTTP (not HTTPS). The `)]}'` XSSI prefix must be stripped before parsing the JSON body.
3. **Traceroute**: Implemented via `ping -c 1 -t <TTL>` probing (no `traceroute` binary needed). Probes up to 30 hops.
4. **LAN Scanner**: Uses concurrent ping/ARP sweep for device discovery.
5. **Android 10+ restrictions**: IMEI, serial number, and other sensitive APIs are restricted; clear fallback messages are shown.
6. **`android.builtInKotlin=false`** and other AGP 9.0 compatibility flags are set in `gradle.properties`.

## Qwen Added Memories
- Project: android-ntp_dig_ping_more Android app (Kotlin, Compose, MVVM). Test fixing progress: 31 failures → 8 failures. Fixed: HttpsCertViewModelTest (7 tests via explicit result stubs), LanScannerViewModelTest initial state assertion, TracerouteViewModel history cap, DeviceInfoViewModel stopPeriodicUpdates. Remaining 8 failures: DigViewModelTest (1), LanScannerViewModelTest (history saved, activeDevices), DeviceInfoViewModelTest (4 periodic update/permission tests). Source changes: LanScannerViewModel.kt +.take(10) cap, TracerouteViewModel.kt +.take(5) cap, DeviceInfoViewModel.kt +stopPeriodicUpdates().
