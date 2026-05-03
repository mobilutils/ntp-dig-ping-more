# Qwen Code Context — NTP DIG PING MORE

## Project Overview

**NTP DIG PING MORE** is a professional-grade Android network diagnostics toolkit built with Kotlin and Jetpack Compose (Material 3). It provides a suite of network troubleshooting tools in a single app with a bottom navigation bar and an overflow "More" screen.

**Core Features:**
- **NTP Check** — NTP server reachability, server time, clock offset, round-trip delay (via Apache Commons Net `NTPUDPClient`)
- **DIG Test** — DNS resolution to custom servers with full CNAME chain support (via dnsjava `SimpleResolver`)
- **Ping** — Live ICMP ping with streaming terminal output (up to 100 packets)
- **Traceroute** — TTL-probing hop discovery via `ping -c 1 -t <TTL>` (no external binary needed)
- **Port Scanner** — Concurrent TCP/UDP port range scanning with live progress
- **LAN Scanner** — Subnet device discovery (IP, hostname, MAC, latency)
- **Google Time Sync** — HTTP-based time sync with RTT/offset computation
- **Device Info** — Read-only device identity, network, battery, storage, MDM status, CA certs
- **HTTPS Cert Check** — Certificate inspection for hosts
- **Bulk Actions** — Batch execution of diagnostic commands from a JSON config, with ADB automation support for headless/CI workflows

## Architecture

| Layer | Technology |
|---|---|
| Language | Kotlin (via Kotlin Compose plugin) |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM (ViewModel + StateFlow) |
| Navigation | Jetpack Navigation Compose |
| Concurrency | Kotlin Coroutines (`Dispatchers.IO`) |
| Persistence | AndroidX DataStore Preferences (query history) |
| Build | Gradle Kotlin DSL, AGP 9.2.0 |
| Min SDK | 26 (Android 8.0) |
| Compile SDK | 37 |

### Key Dependencies
- `commons-net:3.11.1` — NTP UDP client
- `dnsjava:3.6.2` — DNS resolution
- `androidx.datastore:preferences:1.1.1` — History persistence
- `androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7` — ViewModel integration
- `app.cash.quickjs:quickjs-android:0.9.2` — JS engine for PAC proxy evaluation (Telnet feature)
- `junit:4.13.2`, `mockk:1.13.12`, `kotlinx-coroutines-test:1.8.1` — Testing

## Project Structure

```
app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/
├── MainActivity.kt                 # NavHost, bottom navigation bar
├── MoreToolsScreen.kt              # Overflow screen (Traceroute, Port Scanner, LAN Scanner, etc.)
├── SettingsScreen.kt               # Settings UI + Proxy/PAC configuration screens
├── NtpScreen.kt, NtpViewModel.kt, NtpRepository.kt, NtpHistoryStore.kt
├── DigScreen.kt, DigViewModel.kt, DigRepository.kt, DigHistoryStore.kt
├── PingScreen.kt, PingViewModel.kt, PingHistoryStore.kt
├── TracerouteScreen.kt, TracerouteViewModel.kt, TracerouteHistoryStore.kt
├── PortScannerScreen.kt, PortScannerViewModel.kt, PortScannerHistoryStore.kt
├── LanScannerScreen.kt, LanScannerViewModel.kt, LanScannerRepository.kt, LanScannerHistoryStore.kt
├── GoogleTimeSyncScreen.kt, GoogleTimeSyncViewModel.kt, GoogleTimeSyncRepository.kt, GoogleTimeSyncHistoryStore.kt
├── HttpsCertScreen.kt, HttpsCertViewModel.kt, HttpsCertRepository.kt, HttpsCertHistoryStore.kt
├── BulkActionsScreen.kt, BulkActionsViewModel.kt, BulkActionsRepository.kt, BulkActionsHistoryStore.kt
├── SettingsViewModel.kt              # Settings state management
├── proxy/                            # PAC proxy resolution (QuickJS-based)
│      └── ProxyResolver.kt          # PAC script evaluation for Telnet feature
├── deviceinfo/
│      ├── DeviceInfoScreen.kt
│      ├── DeviceInfoViewModel.kt
│      ├── DeviceInfoModels.kt
│      └── SystemInfoRepository.kt
└── ui/theme/                       # Material 3 colors, typography, theme

app/src/test/java/io/github/mobilutils/ntp_dig_ping_more/
├── NtpViewModelTest.kt             (14 tests)
├── DigViewModelTest.kt             (14 tests)
├── TracerouteViewModelTest.kt      (16 tests)
├── PortScannerViewModelTest.kt     (12 tests)
├── LanScannerViewModelTest.kt      (21 tests)
├── LanScannerRepositoryTest.kt     (18 tests)
├── GoogleTimeSyncViewModelTest.kt (14 tests)
├── HttpsCertViewModelTest.kt       (28 tests)
├── BulkActionsViewModelTest.kt
├── BulkConfigParserTest.kt
├── ProxyResolverTest.kt            # PAC proxy resolver tests
├── SettingsViewModelTest.kt        # Settings UI state tests
├── deviceinfo/DeviceInfoViewModelTest.kt (13 tests)
└── HistoryStoreParsingTest.kt      (30+ tests)
```

## Building and Running

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device/emulator
./gradlew installDebug

# Run all unit tests
./gradlew test

# Run a specific test class
./gradlew test --tests "NtpViewModelTest"

# Run with JaCoCo coverage report
./gradlew jacocoUnitTestReport

# Full build (includes tests)
./gradlew build
```

In Android Studio: open the project root → wait for Gradle sync → connect device/emulator → press **▶ Run**.

## Testing Conventions

- **70+ unit tests** covering ViewModels (with MockK), pure functions (IP conversion, parsing), and history store serialization.
- All ViewModel tests use `StandardTestDispatcher` + `advanceUntilIdle()` for coroutine control.
- Tests live in `app/src/test/java/...` following the same package as the source code.
- Test template: use `@RunWith(JUnit4::class)`, `@OptIn(ExperimentalCoroutinesApi::class)`, `runTest` scope, MockK `coEvery`/`coVerify`.
- **Pending coverage:** `PingViewModel` (Runtime.exec mocking), Compose UI tests in `androidTest/`.
- JaCoCo is enabled for debug builds (`enableUnitTestCoverage = true`).

## Development Notes

- **MVVM pattern:** Each feature has a `*Screen.kt` (Compose UI), `*ViewModel.kt` (StateFlow state), and optionally `*Repository.kt` (network I/O) and `*HistoryStore.kt` (DataStore).
- **Coroutine concurrency:** All network operations run on `Dispatchers.IO` via coroutines. Timeouts are applied per-operation.
- **History persistence:** Each tool keeps the last 5–10 entries in DataStore, persisted across app restarts.
- **Runtime permissions:** Location (`ACCESS_COARSE/FINE_LOCATION`) and phone state (`READ_PHONE_STATE`) are requested at runtime via `ActivityResultContracts`.
- **Cleartext traffic:** `android:usesCleartextTraffic="true"` is set in `AndroidManifest.xml` for the Google Time Sync HTTP endpoint.
- **Android 13+ (API 33):** `READ_EXTERNAL_STORAGE` is no longer grantable; Bulk Actions ADB automation pushes configs via `run-as` pipe and pulls results the same way.
- **Build config:** Keystore at `.keystore/my-release.keystore`. ProGuard disabled for release (`isMinifyEnabled = false`).
- **Version catalog:** All dependency versions are in `gradle/libs.versions.toml`.
- **Target SDK:** Set to 37 via `rootProject.extra["defaultTargetSdkVersion"]` in the root `build.gradle.kts`.

## Bulk Actions / ADB Automation

The app supports headless batch execution via ADB intent extras (`--ez auto_run true`). See `README.md` for full ADB script documentation and common pitfalls. Bundled scripts:
- `BULKACTIONS-ADB-SCRIPT.sh` — Mac/Linux/Unix
- `BULKACTIONS-ADB-WINDOWS-SCRIPT.bat` — Windows

## Permissions Required

```xml
INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE    (normal — auto-granted)
ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION          (dangerous — runtime request)
READ_PHONE_STATE                                      (dangerous — runtime request)
```

## Version

- **Name:** NTP DIG PING MORE
- **Version:** 2.9 (release), 2.9-dev (debug)
- **Version Code:** 20

## License

MIT
