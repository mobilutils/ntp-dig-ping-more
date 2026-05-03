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

### Phase 5: Testing

| File | Tests | Status |
|------|-------|--------|
| [ProxyResolverTest.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/test/java/io/github/mobilutils/ntp_dig_ping_more/ProxyResolverTest.kt) | **[NEW]** 14 tests (PAC parsing, disabled/blank config, error handling) | ✅ All pass |
| [SettingsViewModelTest.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/test/java/io/github/mobilutils/ntp_dig_ping_more/SettingsViewModelTest.kt) | **[NEW]** 17 tests (init, timeout, proxy toggle, URL validation, debounce, test proxy) | ✅ All pass |
| [HttpsCertViewModelTest.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/test/java/io/github/mobilutils/ntp_dig_ping_more/HttpsCertViewModelTest.kt) | Existing 24 tests | ✅ All pass |

## Verification

| Check | Result |
|-------|--------|
| `./gradlew assembleDebug` | ✅ BUILD SUCCESSFUL |
| `ProxyResolverTest` | ✅ 14/14 passed |
| `SettingsViewModelTest` | ✅ 17/17 passed |
| `HttpsCertViewModelTest` | ✅ 24/24 passed |
| `GoogleTimeSyncViewModelTest` | ⚠️ Pre-existing Looper/Dispatchers.Main failures (unrelated) |
| `TracerouteViewModelTest` | ⚠️ Pre-existing Looper failures (unrelated) |

> [!WARNING]
> `GoogleTimeSyncViewModelTest` and `TracerouteViewModelTest` have pre-existing failures caused by missing `Dispatchers.setMain()` setup. These are **not** regressions from this change.
