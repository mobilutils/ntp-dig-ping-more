# Walkthrough: Proxy PAC Settings Integration

## Overview

Implemented a global Proxy PAC configuration section in the Settings screen. When enabled, HTTP traffic (`GoogleTimeSyncRepository`) and TLS traffic (`HttpsCertRepository`) are routed through the PAC-resolved proxy. PAC scripts are evaluated using QuickJS. All proxy logic is opt-in and backward-compatible.

## Changes Made

### Phase 1: Dependencies & Data Layer

| File | Change |
|------|--------|
| [libs.versions.toml](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/gradle/libs.versions.toml) | Added `quickjs = "0.9.2"` version + `quickjs-android` library entry |
| [build.gradle.kts](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/build.gradle.kts) | Added `implementation(libs.quickjs)` |
| [ProxyConfig.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/settings/ProxyConfig.kt) | **[NEW]** Data class: `enabled`, `pacUrl`, `lastTested`, `lastTestResult` |
| [SettingsDataStore.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/settings/SettingsDataStore.kt) | Added 4 proxy preference keys (`PROXY_ENABLED`, `PROXY_PAC_URL`, `PROXY_LAST_TESTED`, `PROXY_LAST_TEST_RESULT`) |
| [SettingsRepository.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/settings/SettingsRepository.kt) | Added `proxyConfigFlow: Flow<ProxyConfig>` and `saveProxyConfig()` |

### Phase 2: Core Proxy Engine (new `proxy/` package)

| File | Purpose |
|------|---------|
| [JsEngine.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/proxy/JsEngine.kt) | **[NEW]** Interface for PAC script evaluation |
| [QuickJsEngine.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/proxy/QuickJsEngine.kt) | **[NEW]** QuickJS implementation with PAC utility stubs |
| [ProxyResolver.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/proxy/ProxyResolver.kt) | **[NEW]** Core resolver: PAC fetch, JS eval, result parsing, caching (5-min TTL), test connectivity |

### Phase 3: Repository Integration

| File | Change |
|------|--------|
| [GoogleTimeSyncRepository.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/GoogleTimeSyncRepository.kt) | Added `ProxyResolver?` constructor param; `openConnection(proxy)` when proxy resolved |
| [HttpsCertRepository.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/HttpsCertRepository.kt) | Added `ProxyResolver?` constructor param + `createProxiedSSLSocket()` with HTTP CONNECT tunnel |

> [!NOTE]
> Both repositories use `ProxyResolver? = null` default, preserving full backward compatibility with existing code and tests.

### Phase 4: Settings UI & ViewModel

| File | Change |
|------|--------|
| [SettingsViewModel.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/SettingsViewModel.kt) | Extended with proxy state, `onProxyEnabledChange()`, `onProxyPacUrlChange()` (debounced), `testProxy()`, URL validation |
| [SettingsScreen.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/SettingsScreen.kt) | Added "Proxy Configuration" card: Switch, PAC URL field, Test button, result display |
| [GoogleTimeSyncViewModel.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/GoogleTimeSyncViewModel.kt) | Factory now injects `ProxyResolver` into repository |
| [HttpsCertViewModel.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/HttpsCertViewModel.kt) | Factory now injects `ProxyResolver` into repository |

### Phase 5: Testing (Proxy PAC Settings)

| File | Tests | Status |
|------|-------|--------|
| [ProxyResolverTest.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/test/java/io/github/mobilutils/ntp_dig_ping_more/ProxyResolverTest.kt) | **[NEW]** 14 tests (PAC parsing, disabled/blank config, error handling) | ✅ All pass |
| [SettingsViewModelTest.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/test/java/io/github/mobilutils/ntp_dig_ping_more/SettingsViewModelTest.kt) | **[NEW]** 17 tests (init, timeout, proxy toggle, URL validation, debounce, test proxy) | ✅ All pass |
| [HttpsCertViewModelTest.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/test/java/io/github/mobilutils/ntp_dig_ping_more/HttpsCertViewModelTest.kt) | Existing 24 tests | ✅ All pass |

---

### Phase 6: Proxy PAC Logging

Added a user-controlled logging system for Proxy PAC operations. When enabled, high-level events (PAC fetches, proxy resolutions, errors) are written to a shared in-memory buffer and a persistent file, both capped at 500 lines. BulkActions configs support an independent `"log-proxy": true` JSON field for batch-scoped logging.

#### Logging Infrastructure

| File | Change |
|------|--------|
| [ProxyPacLogger.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/proxy/ProxyPacLogger.kt) | **[NEW]** Thread-safe singleton logger: `ArrayDeque` buffer + persistent file (`proxypac-logs.txt`), both capped at 500 lines. Async file I/O via `Mutex` + `Dispatchers.IO`. `getInstance()` ensures all ViewModels share the same in-memory buffer. `log(event, force)` parameter bypasses the `enabled` check for batch-scoped overrides. |

#### Persistence Layer

| File | Change |
|------|--------|
| [SettingsDataStore.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/settings/SettingsDataStore.kt) | Added `PROXY_LOGGING_ENABLED` preference key |
| [ProxyConfig.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/settings/ProxyConfig.kt) | Added `loggingEnabled: Boolean` field |
| [SettingsRepository.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/settings/SettingsRepository.kt) | Mapped `loggingEnabled` in `proxyConfigFlow` and `saveProxyConfig()` |

#### ProxyResolver Instrumentation

| File | Change |
|------|--------|
| [ProxyResolver.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/proxy/ProxyResolver.kt) | Added `logger: ProxyPacLogger?` + `forceLogging: Boolean` constructor params. `logIfEnabled()` helper passes `force = forceLogging` to the logger. Instrumented at 5 points: `PAC_FETCH_SUCCESS`, `PAC_FETCH_FAIL`, `PROXY_RESOLVED`, `PROXY_TEST`, `PROXY_ERROR`. Updated `forStaticPacUrl()` factory. |

#### BulkActions Integration

| File | Change |
|------|--------|
| [BulkActionsRepository.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/BulkActionsRepository.kt) | Added `logProxy: Boolean?` to `BulkConfig`. Parse `"log-proxy"` JSON field. `buildProxyResolver()` creates `ProxyPacLogger.getInstance()` and passes `forceLogging`. |
| [BulkActionsViewModel.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/BulkActionsViewModel.kt) | Both `onLoadAndRun` and `onRunClicked` pass `forceLogging = config.logProxy == true` to `setupProxyResolver()`. |

#### Settings UI & ViewModel

| File | Change |
|------|--------|
| [SettingsViewModel.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/SettingsViewModel.kt) | Added 3 UI state fields (`proxyLoggingEnabled`, `showLogDialog`, `proxyLogs`). Added `ProxyPacLogger` constructor param + 4 handlers: `onProxyLoggingEnabledChange`, `onViewLogs`, `onClearLogs`, `onDismissLogDialog`. Factory uses `ProxyPacLogger.getInstance()`. |
| [SettingsScreen.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/SettingsScreen.kt) | Added "Enable Proxy Logging" toggle with subtitle, "View Logs" / "Clear Logs" buttons, and `AlertDialog` with monospace `LazyColumn` for log viewing. |

#### Shared Logger Across All Screens

| File | Change |
|------|--------|
| [GoogleTimeSyncViewModel.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/GoogleTimeSyncViewModel.kt) | Factory uses `ProxyPacLogger.getInstance()` and passes it to `ProxyResolver` |
| [HttpsCertViewModel.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/HttpsCertViewModel.kt) | Factory uses `ProxyPacLogger.getInstance()` and passes it to `ProxyResolver` |

> [!NOTE]
> All ViewModels use `ProxyPacLogger.getInstance()` (singleton) so proxy events from any screen (Settings, HttpsCert, GoogleTimeSync, BulkActions) share the same in-memory buffer and appear in the Settings > View Logs dialog.

### Phase 7: Testing (Proxy PAC Logging)

| File | Tests | Status |
|------|-------|--------|
| [ProxyPacLoggerTest.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/test/java/io/github/mobilutils/ntp_dig_ping_more/ProxyPacLoggerTest.kt) | **[NEW]** 10 tests (buffer cap, timestamp format, snapshots, disabled no-op, file writes, file rolling, clear buffer/file, thread safety) | ✅ All pass |
| [SettingsViewModelTest.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/test/java/io/github/mobilutils/ntp_dig_ping_more/SettingsViewModelTest.kt) | +8 logging tests (toggle on/off, persists config, view/clear/dismiss dialog, syncs logger flag) → **25 total** | ✅ All pass |
| [ProxyResolverTest.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/test/java/io/github/mobilutils/ntp_dig_ping_more/ProxyResolverTest.kt) | +4 logging tests (null logger, disabled+no-force, forceLogging, enabled logger) → **18 total** | ✅ All pass |
| [BulkConfigParserTest.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/test/java/io/github/mobilutils/ntp_dig_ping_more/BulkConfigParserTest.kt) | +4 log-proxy tests (true, false, absent, combined fields) | ✅ All pass |

## Verification

| Check | Result |
|-------|--------|
| `./gradlew clean test` | ✅ BUILD SUCCESSFUL — **305 tests pass, 0 failures** |
| `ProxyPacLoggerTest` | ✅ 10/10 passed |
| `ProxyResolverTest` | ✅ 18/18 passed |
| `SettingsViewModelTest` | ✅ 25/25 passed |
| `BulkConfigParserTest` | ✅ All passed (incl. 4 new log-proxy tests) |
| `HttpsCertViewModelTest` | ✅ 24/24 passed |
