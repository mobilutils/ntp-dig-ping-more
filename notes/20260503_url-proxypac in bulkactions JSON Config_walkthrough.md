# Walkthrough: `url-proxypac` in BulkActions JSON Config

## Summary

Added a new global field `"url-proxypac"` to the BulkActions JSON config file. When set, the HTTP-based pseudo-commands `checkcert` and `google-timesync` route their traffic through the proxy resolved by the PAC script. This is independent of the app's Settings proxy configuration and does not modify persisted settings.

## Changes Made

### Core Model & Parser

#### [BulkActionsRepository.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/BulkActionsRepository.kt)
- **`BulkConfig`** data class: Added `urlProxyPac: String? = null` field
- **`BulkConfigParser.parse()`**: Extracts `"url-proxypac"` from JSON root object
- **`BulkActionsRepository`**: 
  - Added `bulkProxyResolver: ProxyResolver?` volatile field
  - Added `setupProxyResolver(pacUrl)` / `clearProxyResolver()` public API
  - `executeCommands()` automatically sets up/tears down the proxy resolver
  - `executeCheckcert()`: Creates proxy-aware `HttpsCertRepository` when resolver is set
  - `executeGoogleTimeSync()`: Creates proxy-aware `GoogleTimeSyncRepository` when resolver is set

### Proxy Infrastructure

#### [ProxyResolver.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/proxy/ProxyResolver.kt)
- Added `staticPacUrl: String? = null` constructor parameter
- Added `forStaticPacUrl()` companion factory method — creates a resolver that uses a PAC URL directly without reading from `SettingsRepository`
- `resolveProxy()` now checks `staticPacUrl` first; only falls back to `SettingsRepository` when no static URL is set

### ViewModel

#### [BulkActionsViewModel.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/BulkActionsViewModel.kt)
- Both `onLoadAndRun()` (ADB automation) and `onRunClicked()` (UI button) now call `repository.setupProxyResolver(config.urlProxyPac)` before execution and `repository.clearProxyResolver()` in their `finally` blocks

### Tests

#### [BulkConfigParserTest.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/test/java/io/github/mobilutils/ntp_dig_ping_more/BulkConfigParserTest.kt)
- Added 4 new test cases:
  - `parseConfigWithUrlProxyPac returns value`
  - `parseConfigWithoutUrlProxyPac returns null`
  - `parseConfigWithBlankUrlProxyPac returns null`
  - `parseConfigWithUrlProxyPacAndOtherFields preserves all`

### Documentation & Example Config

#### [README.md](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/README.md)
- Documented `url-proxypac` in the Bulk Actions section with example JSON

#### [blkacts_proxy_checkcert_timesync.json](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/notes/config-files_bulk-actions/blkacts_proxy_checkcert_timesync.json)
- New example config file demonstrating `url-proxypac` usage

## Design Decisions

1. **Static PAC URL bypass**: Rather than writing the PAC URL to DataStore (which would overwrite the user's global proxy settings), the `ProxyResolver` was extended with a `staticPacUrl` parameter that bypasses `SettingsRepository` entirely.

2. **Scoped lifecycle**: The proxy resolver is created before execution and cleaned up in `finally` blocks, ensuring no leaks across bulk action runs.

3. **Only `checkcert` and `google-timesync`**: These are the only HTTP-based pseudo-commands that use `HttpsCertRepository` and `GoogleTimeSyncRepository` — both already accept an optional `ProxyResolver`. Other commands (`ping`, `dig`, `ntp`, `port-scan`, etc.) use raw sockets or process execution and don't support proxy routing.

## Verification

- **All unit tests pass** (full test suite: `./gradlew :app:testDebugUnitTest` — BUILD SUCCESSFUL)
- **No regressions** in existing BulkConfigParser, BulkActionsViewModel, or any other test classes
