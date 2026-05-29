# Qwen Code — Project Context

## Project Overview

**ntp_dig_ping_more** is a production Android app (min SDK 26, target SDK 37) for network diagnostics. It provides 9 built-in tools plus a batch "Bulk Actions" feature: NTP Check, DIG (DNS), Ping, Traceroute, Port Scanner, LAN Scanner, Google Time Sync, HTTPS Certificate Inspector, Device Info, Settings, and Bulk Actions (JSON-configurable command batches). Current version: **3.41** (version code 28).

The app uses **ADB intent extras** for headless automation — configs can be pushed to the app's private directory via `run-as`, then the app launched with `--ez auto_run true` to execute commands without user interaction. Bundled shell scripts (`BULKACTIONS-ADB-SCRIPT.sh`, `BULKACTIONS-ADB-SCRIPT-NEW.bat`, `BULKACTIONS-ADB-SCRIPT-NEW.ps1`) wrap this into a fully automated workflow for CI/manual use.

---

## Building & Running

| Command | Purpose |
|---|---|
| `./gradlew assembleDebug` | Build debug APK |
| `./gradlew installDebug` | Build + install debug APK on connected device/emulator |
| `./gradlew test` | Run all unit tests (~365 tests) |
| `./gradlew test --tests "ClassName"` | Run a single test class |
| `./gradlew testDebugUnitTest` | Run debug unit tests only |
| `./gradlew connectedDebugAndroidTest` | Run instrumented tests on connected device |
| `./gradlew jacocoUnitTestReport` | Generate JaCoCo coverage report |

**Open in Android Studio:** File → Open → select this root directory. Wait for Gradle sync, then press ▶ Run.

---

## Architecture

```
MainActivity.kt                 ← Entry point, NavHost, bottom nav bar, intent extras (configUri + autoRun)
       │
       ├── AppRoot()              ← Composable with NavHost + NavigationBar
       │
       ├── Screen modules (per tool):
       │     │
       │     ├── *Screen.kt          ← Jetpack Compose UI (Material 3)
       │     ├── *ViewModel.kt       ← StateFlow<UiState>, coroutine lifecycle, command dispatch
       │     ├── *Repository.kt      ← Network I/O (NTPUDPClient, dnsjava, HttpURLConnection, Runtime.exec)
       │     └── *HistoryStore.kt ← DataStore persistence (last 5–10 entries per tool)
       │
       ├── settings/              ← Global Settings + Proxy PAC configuration
       │     ├── SettingsDataStore.kt
       │     ├── SettingsRepository.kt
       │     └── ProxyConfig.kt
       ├── proxy/                 ← PAC script evaluation
       │     ├── JsEngine.kt       ← Interface
       │     ├── QuickJsEngine.kt ← QuickJS-based evaluator
       │     └── ProxyResolver.kt ← Fetch, eval, parse, cache (5min TTL)
       ├── deviceinfo/           ← Device identity, network, battery, storage, MDM, CA certs
       └── ui/theme/             ← Material 3 colors, typography, theme composable
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
| LanScannerRepositoryTest | 18 | IP conversion utilities (pure functions) |
| HistoryStoreParsingTest | 30+ | All history store parsers (backward compat, edge cases) |
| HttpsCertViewModelTest | 28+ | All cert result variants, full chain in PartialSuccess (collapsible intermediate/root with [Leaf]/[Intermediate N]/[Root] markers), history, state transitions |
| LanScannerViewModelTest | 21 | Subnet sweep, quick/full modes, progress, error handling |
| TracerouteViewModelTest | 16 | Start/stop, blank guards, history, cancellation |
| NtpViewModelTest | 14 | State mutations, repo mocking, error paths, history |
| GoogleTimeSyncViewModelTest | 14 | URL fallback, success/error states, reset |
| DigViewModelTest | 14 | Input handlers, DNS errors, CNAME chains |
| PortScannerViewModelTest | 12 | Validation, scan lifecycle, history |
| DeviceInfoViewModelTest | 13 | Permissions, loading/success/error, periodic updates |

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
| `BULKACTIONS-ADB-SCRIPT.sh` / `.bat` / `.ps1` | Automated ADB script for headless CI/manual use |
| `notes/config-files_bulk-actions/*.json` | Test configs for Bulk Actions automation |
| `TESTING.md` | Comprehensive testing guide |

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
| `-a, --no-interact` | Suppress all prompts (auto-exit after completion) |
| `-s, --show-emulator` | Launch emulator in visible window mode |
| `-h, --help` | Show usage help |

**What it does:** starts emulator → pushes config via `run-as` pipe → launches app with intent extras → polls `.running-tasks` marker file → pulls results to `./test-results/`.

### ADB Automation Pattern (API 33+)

```bash
APP_ID="io.github.mobilutils.ntp_dig_ping_more"
PRIVATE_DIR="/data/user/0/$APP_ID/files/files"

# 1. Push config via run-as pipe (avoids /sdcard permission issues on SDK 33+)
cat config.json \
     | adb shell "run-as $APP_ID sh -c 'cat > $PRIVATE_DIR/config.json'"

# 2. Launch with auto-load + auto-run (use --ez boolean, NOT --es string)
adb shell am force-stop "$APP_ID"
adb shell am start \
       -n "$APP_ID/.MainActivity" \
       -d "file://$PRIVATE_DIR/config.json" \
       --ez auto_run true

# 3. Wait for execution, then pull results via run-as cat
sleep 60
adb shell "run-as $APP_ID cat $PRIVATE_DIR/config.txt" > ./test-results/output.txt
```

### `--ez` vs `--es`: Intent Extra Type Matters

The **flag type** in the ADB intent must match the Java/Kotlin type expected by the app:

| Flag | Intent extra type | Java getter | When to use |
|---|---|---|---|
| `--ez` | boolean (`true/false`) | `getBooleanExtra()` | `auto_run` — tells the app to auto-execute Bulk Actions on launch |
| `-d` (data URI) | String (URI) | `getDataString()` | Config file path — tells the app *which* config to load |
| `-e, --es` | string | `getStringExtra()` | Strings only — **never use for booleans** |

```bash
# ✅ Correct — boolean extra
adb shell am start -n "$APP_ID/.MainActivity" --ez auto_run true

# ❌ Wrong — string extra; app won't auto-run
adb shell am start -n "$APP_ID/.MainActivity" --es auto_run true
```

### Common Pitfalls

| Mistake | Symptom | Fix |
|---|---|---|
| `--es auto_run true` instead of `--ez` | Config loads but never executes | Use `--ez auto_run true` |
| `file:///sdcard/...` URI path | `EACCES (Permission denied)` on SDK 33+ | Push to app private dir via `run-as` pipe |
| `adb pull /data/user/0/<pkg>/...` | `Permission denied` — `shell` user can't read | Use `adb shell "run-as <pkg> cat <file>" \| > output.txt` |
| `run-as cp/push to /sdcard/` | `Permission denied` — `run-as` sandbox restricts writes | Use host-redirect pull approach instead |

---

## Dependencies (Key External Libraries)

| Library | Purpose |
|---|---|
| `commons-net:3.11.1` | NTP UDP client (`NTPUDPClient`) |
| `dnsjava:3.6.2` | DNS resolution bypassing system resolver |
| `androidx.javascriptengine:1.1.0` | PAC script JS evaluation (QuickJS) |
| `androidx.datastore:datastore-preferences:1.1.1` | Persistent history + settings |
| `androidx.navigation:navigation-compose:2.8.9` | Compose navigation |
| `kotlinx-coroutines-guava:1.8.1` | Coroutines ↔ Guava ListenableFuture bridge |

**Build toolchain:** AGP 9.2.1, Kotlin 2.2.10, JVM 11 target, AndroidX with non-transitive R class.

---

## Coding Conventions (Inferred)

- **Kotlin code style:** `official` (per `gradle.properties`)
- **Naming:** camelCase for locals/fields, PascalCase for classes/objects, `snake_case` for file names (`*.kt`)
- **Sealed classes** for UI state and result types (`NtpResult`, etc.)
- **Composables** are file-scoped functions (not class members), previewed with `@Preview`
- **ViewModels** expose constructor-injected dependencies (repository + history store) with companion-object `factory()` delegates for `viewModel()` factory support
- **Comments:** Chinese and English mixed in some files; commit messages use conventional prefixes (`fix()`, `feat()`, `improve/`)

---

## Notable Known Issues

- **PAC IsInNet Stub:** The `isInNet()` function in `QuickJsEngine.PAC_UTILS` always returns `false`, causing DIRECT rules to fall through to the proxy branch and fail with 403 Forbidden. Android's native PAC evaluator resolves hostnames via DNS before checking subnet membership; QuickJS engine lacks this capability. See memory file `PAC_IsInNetStubIssue.md` for details.

---

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

---

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

---

## License

MIT

# Coding Style Guidelines

## Indentation
- Use **4 spaces** for indentation (no tabs).
- Never mix spaces and tabs.
- When editing existing code, match the indentation style of the surrounding code.

## File Editing Instructions
- Before editing, always read the file first to detect the current indentation style.
- If a file uses 5-space indents, preserve that style in your edits.
- Use the `read_file` tool before `edit` to verify formatting.
- Use a Python script to edit this file instead of the `edit` tool, as it has strict whitespace requirements.

For YAML, Kotlin, and Python files, prioritize `run_shell_command` with `sed` or Python scripts for edits if `edit` fails due to whitespace mismatches. Do not retry `edit` more than twice on these file types.

If `write_file` fails on paths containing special characters (e.g., `.._`, `-_-`) or produces empty files, switch immediately to a shell heredoc (`cat << 'EOF' > path`) to write content.

When requested to update documentation (e.g., QWEN.md, Nextra docs), perform a full audit of the target directory for unused files and redundancies before generating new content.

## Line Endings
- Use LF (`\n`) line endings (standard on macOS/Linux).

## PHP Specific Rules
- File encoding: UTF-8 without BOM
- Line endings: LF (`\n`)
- Indentation: 4 spaces (preserve existing style if different)
- When editing: always include 2 lines of context before/after the change
- Escape $ in old_string if it appears in double-quoted strings

## Command line specifix
on macosx, current os, there are some specifities to command line arguments, they are listed below

## `cat -A`
   argument `-A` isn't available on macOS, use arguments `-vet` instead
- replace `cat -A` by `cat -vet`

