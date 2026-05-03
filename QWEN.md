# Qwen Code — Project Context

## Project Overview

**ntp_dig_ping_more** is a production Android app (min SDK 26, target SDK 37) for network diagnostics. It provides 9 built-in tools plus a batch "Bulk Actions" feature: NTP Check, DIG (DNS), Ping, Traceroute, Port Scanner, LAN Scanner, Google Time Sync, HTTPS Certificate Inspector, Device Info, Settings, and Bulk Actions (JSON-configurable command batches).

The app uses **ADB intent extras** for headless automation — configs can be pushed to the app's private directory via `run-as`, then the app launched with `--ez auto_run true` to execute commands without user interaction. A bundled shell script (`BULKACTIONS-ADB-SCRIPT.sh`) wraps this into a fully automated workflow for CI/manual use.

---

## Building & Running

| Command | Purpose |
|---|---|
| `./gradlew assembleDebug` | Build debug APK |
| `./gradlew installDebug` | Build + install debug APK on connected device/emulator |
| `./gradlew test` | Run all unit tests (~265 tests) |
| `./gradlew test --tests "ClassName"` | Run a single test class |
| `./gradlew testDebugUnitTest` | Run debug unit tests only |
| `./gradlew connectedDebugAndroidTest` | Run instrumented tests on connected device |
| `./gradlew jacocoUnitTestReport` | Generate JaCoCo coverage report |

**Open in Android Studio:** File → Open → select this root directory. Wait for Gradle sync, then press ▶ Run.

---

## Architecture

```
MainActivity.kt              ← Entry point, NavHost, bottom nav bar, intent extras (configUri + autoRun)
    │
    ├── AppRoot()            ← Composable with NavHost + NavigationBar
    │
    ├── Screen modules (per tool):
    │   │
    │   ├── *Screen.kt       ← Jetpack Compose UI (Material 3)
    │   ├── *ViewModel.kt    ← StateFlow<UiState>, coroutine lifecycle, command dispatch
    │   ├── *Repository.kt   ← Network I/O (NTPUDPClient, dnsjava, HttpURLConnection, Runtime.exec)
    │   └── *HistoryStore.kt ← DataStore persistence (last 5–10 entries per tool)
    │
    ├── settings/            ← Global Settings + Proxy PAC configuration
    │   ├── SettingsDataStore.kt
    │   ├── SettingsRepository.kt
    │   └── ProxyConfig.kt
    ├── proxy/               ← PAC script evaluation
    │   ├── JsEngine.kt          ← Interface
    │   ├── QuickJsEngine.kt     ← QuickJS-based evaluator
    │   └── ProxyResolver.kt     ← Fetch, eval, parse, cache (5min TTL)
    ├── deviceinfo/          ← Device identity, network, battery, storage, MDM, CA certs
    └── ui/theme/            ← Material 3 colors, typography, theme composable
```

**Key patterns:**
- Every screen follows **MVVM**: `StateFlow<UiState>` → Compose reads state → ViewModel dispatches to Repository
- Coroutines use `Dispatchers.IO` for all I/O; tests use `StandardTestDispatcher` + `advanceUntilIdle()`
- History stores are pipe-delimited (or JSON for LAN Scanner), parsed by custom `parse*History()` functions, persisted via AndroidX DataStore Preferences
- Navigation uses Jetpack Navigation Compose with `NavHost` + `NavigationBar`; the "More" tab reveals hidden screens

---

## Test Suite

Located in `app/src/test/java/io/github/mobilutils/ntp_dig_ping_more/`.

| Test Class | Count | Covers |
|---|---|---|
| `HistoryStoreParsingTest` | 30+ | All history store parsers (backward compat, edge cases) |
| `HttpsCertViewModelTest` | 28 | All cert result variants, history, state transitions |
| `LanScannerViewModelTest` | 21 | Subnet sweep, quick/full modes, progress, error handling |
| `NtpViewModelTest` | 14 | State mutations, repo mocking, error paths, history |
| `GoogleTimeSyncViewModelTest` | 14 | URL fallback, success/error states, reset |
| `TracerouteViewModelTest` | 16 | Start/stop, blank guards, history, cancellation |
| `DigViewModelTest` | 14 | Input handlers, DNS errors, CNAME chains |
| `PortScannerViewModelTest` | 12 | Validation, scan lifecycle, history |
| `DeviceInfoViewModelTest` | 13 | Permissions, loading/success/error, periodic updates |
| `LanScannerRepositoryTest` | — | IP conversion utilities (pure functions) |
| `BulkActionsViewModelTest` | — | Bulk Actions config parsing + execution |
| `BulkConfigParserTest` | — | JSON config parser |
| `ProxyResolverTest` | — | PAC script fetching, eval, cache |
| `SettingsViewModelTest` | — | Timeout input, proxy toggle/PAC URL validation |

**Testing conventions:**
- JUnit 4 (`@RunWith(JUnit4::class)`) + MockK (`mockk(relaxed = true)` for dependencies)
- `StandardTestDispatcher` for coroutine control; `coEvery { ... } returns ...` for suspend mocks
- Test names use backtick format: `` `test description that explains scenario` ``
- Pure functions tested directly (no mocking); ViewModels tested with mocked repos + history stores
- Setup/teardown resets `Dispatchers.Main` in `@After`

---

## Key Files & Locations

| Path | Purpose |
|---|---|
| `app/build.gradle.kts` | Module build config, dependencies, JaCoCo setup |
| `build.gradle.kts` (root) | Plugin aliases, shared `targetSdk` extra property |
| `gradle/libs.versions.toml` | Version catalog (all dependency versions) |
| `BULKACTIONS-ADB-SCRIPT.sh` | Automated ADB script for headless CI/manual use |
| `notes/config-files_bulk-actions/*.json` | 65+ test configs for Bulk Actions automation |
| `notes/20260501_BulkActions-ADB-Automations.md` | ADB automation documentation |
| `TESTING.md` | Comprehensive testing guide |
| `JACOCO_SETUP.md` | JaCoCo coverage configuration |

---

## Scripting & Automation

### `BULKACTIONS-ADB-SCRIPT.sh` (Unix/Linux/macOS)

```bash
./BULKACTIONS-ADB-SCRIPT.sh -f <config> [options]
```

| Flag | Description |
|---|---|
| `-f, --filepath <config>` | Config file path (required; supports `~` expansion and absolute paths) |
| `-e, --emulator-name <name>` | AVD to launch (default: `Medium_Phone_API_35`) |
| `-d, --real-device` | Skip emulator entirely; use connected physical device |
| `-a, --no-interact` | Suppress all prompts (auto-exit) |
| `-s, --show-emulator` | Launch emulator in visible window mode |
| `-h, --help` | Show usage help |

**What it does:** starts emulator → pushes config via `run-as` pipe → launches app with intent extras → polls `.running-tasks` marker file → pulls results to `./test-results/`.

### `BULKACTIONS-ADB-WINDOWS-SCRIPT.bat` (Windows)

Same workflow as the Unix script, using PowerShell for JSON parsing and native CMD constructs.

---

## Dependencies (Key External Libraries)

| Library | Purpose |
|---|---|
| `org.apache.commons:commons-net:3.11.1` | NTP UDP client (`NTPUDPClient`) |
| `dnsjava:dnsjava:3.6.2` | DNS resolution bypassing system resolver |
| `app.cash.quickjs:quickjs-android-wrapper:0.9.2` | PAC script JS evaluation |
| `androidx.datastore:datastore-preferences` | Persistent history + settings |
| `androidx.navigation:navigation-compose` | Compose navigation |

---

## Coding Conventions (Inferred)

- **Kotlin code style:** `official` (per `gradle.properties`)
- **Naming:** camelCase for locals/fields, PascalCase for classes/objects, `snake_case` for file names (`*.kt`)
- **Sealed classes** for UI state and result types (`NtpResult`, etc.)
- **Composables** are file-scoped functions (not class members), previewed with `@Preview`
- **ViewModels** expose constructor-injected dependencies (repository + history store) with companion-object `factory()` delegates for `viewModel()` factory support
- **Comments:** Chinese and English mixed in some files; commit messages use conventional prefixes (`fix()`, `feat()`, `improve/`)

---

## Git Workflow

- Branch naming: `feat/<feature>`, `fix/<issue>`, `improve/<topic>`
- Merge PRs with descriptive titles; version tags at `v2.x` on `main`
- Recent work includes Proxy PAC, Settings, Bulk Actions ADB automation, and Device Info improvements
