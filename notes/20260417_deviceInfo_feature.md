# Device Info Feature — 2026-04-17

## Description

Added a complete, production-ready **Device Info** screen to the app. The screen provides a comprehensive read-only view of device identity, network configuration, battery status, memory/storage, security posture, and installed certificates.

### Data Points Exposed

| Category | Fields |
|---|---|
| **Device** | Device Name (manufacturer + model), IMEI, Serial Number, ICCID, Android Version, API Level, CPU Architecture |
| **Time & Uptime** | Current Device Time (auto-updating every second), Time Since Boot, Time Since Screen-Off |
| **Network** | Active Network Type, IPv4 Address, IPv6 Address, Subnet Mask, Default Gateway, DNS Servers, NTP Server |
| **Wi-Fi** | Connected SSID |
| **Mobile Carrier** | Operator Name |
| **Battery** | Level (%), Charging Status, Health |
| **Memory & Storage** | Total RAM, Available RAM, Total Storage, Available Storage |
| **Security & MDM** | MDM/Device Policy Status (Device Owner, Profile Owner, Managed Profile, or None) |
| **Certificates** | Installed System & User CA certificates (subject, validity dates, type) — capped at 20 displayed |

### Architecture

Follows the existing MVVM pattern used throughout the app:

- **`SystemInfoRepository`** — encapsulates all Android system API calls, handles exceptions, normalizes data, and provides fallbacks for restricted APIs (e.g., "Restricted by Android 10+" for IMEI/ICCID on Android 10+, "Not exposed by OS" for screen-off time).
- **`DeviceInfoViewModel`** — exposes `StateFlow<DeviceInfoState>` to the UI. Handles one-time data fetch on init and periodic updates (every 1 second) for time-sensitive fields (device time, uptime, battery level, charging status).
- **`DeviceInfoScreen`** — Compose UI with `Scaffold` → `LazyColumn` → grouped `Card` sections. Supports loading, success, permission-denied, and error states.

### Permission Handling

Uses `ActivityResultContracts` for runtime permission requests:

| Permission | Type | Purpose |
|---|---|---|
| `ACCESS_WIFI_STATE` | Normal (auto-granted) | Wi-Fi SSID, network link properties |
| `ACCESS_NETWORK_STATE` | Normal (auto-granted) | Active network detection, IP addresses |
| `ACCESS_COARSE_LOCATION` | Dangerous (runtime) | Wi-Fi SSID on Android 10+ |
| `ACCESS_FINE_LOCATION` | Dangerous (runtime) | Wi-Fi SSID on Android 10+ |
| `READ_PHONE_STATE` | Dangerous (runtime) | Carrier name, IMEI, ICCID, serial number |

Permissions are requested on first launch via `rememberLauncherForActivityResult` with `RequestMultiplePermissions()` and `RequestPermission()`. A snackbar rationale is shown if any are denied. Restricted APIs show clear fallback messages rather than crashing or returning null silently.

### Android Version Limitations

| API | Android 10+ (API 29+) Restriction |
|---|---|
| IMEI | Restricted to system/privileged apps; returns `null` for third-party apps |
| Serial Number | `Build.getSerial()` requires device/profile owner; returns `"Restricted by Android 10+"` |
| ICCID | Restricted to system/privileged apps; returns `"Restricted by Android 10+"` |
| Wi-Fi SSID | Requires location permission + location services ON; otherwise shows `"Restricted by Android 10+"` |
| Screen-Off Time | No public API exists; shows `"Not exposed by OS"` |
| MDM Status | Can only detect if *this app* is device/profile owner; third-party apps show `"None"` |

---

## Files Created

| Filename | Purpose |
|---|---|
| `deviceinfo/DeviceInfoModels.kt` | Data models: `DeviceInfo`, `CertificateInfo`, `PermissionState`, `DeviceInfoState` (sealed class for Loading/Success/PermissionDenied/Error) |
| `deviceinfo/SystemInfoRepository.kt` | Encapsulates all Android system API calls for device information: identity, network, battery, memory, storage, certificates, MDM status. Handles exceptions, normalizes data, and provides version-specific fallbacks |
| `deviceinfo/DeviceInfoViewModel.kt` | MVVM ViewModel exposing `StateFlow<DeviceInfoState>`. Loads all device info on init, starts periodic updates (1s) for time-sensitive fields, tracks permission state |
| `deviceinfo/DeviceInfoScreen.kt` | Compose UI screen with `Scaffold` → `LazyColumn` → grouped `InfoCard` sections. Includes `rememberLauncherForActivityResult` for runtime permissions, loading/denied/error states, certificate list display, and a preview composable |

## Files Modified

| Filename | Change |
|---|---|
| `AndroidManifest.xml` | Added `<uses-permission>` declarations for `ACCESS_WIFI_STATE`, `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `READ_PHONE_STATE` |
| `MainActivity.kt` | Added `DeviceInfo` to `AppScreen` sealed class, navigation route, `allAppScreens` list, bottom-bar highlight logic, and `composable` destination. Imported `DeviceInfoScreen` and `Icons.Filled.Info`. Fixed pre-existing `twee` → `tween` typo in animation import |
| `MoreToolsScreen.kt` | Added `AppScreen.DeviceInfo` to the `extraTools` list so it appears in the overflow menu |
