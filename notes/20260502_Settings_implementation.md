# Global Settings Screen — Implementation Plan

Introduce a persistent global **timeout** setting (1–60 s, default 5 s) that limits the total execution time of all network-based tools. A new Settings screen lets the user configure this value, which is stored in AndroidX DataStore Preferences and consumed by 7 ViewModels via `withTimeout`.

---

## Open Questions

> [!IMPORTANT]
> **Where to surface the Settings entry point?**
> Two options:
> 1. **Add a 5th icon to BottomNav** (currently 4 items: NTP Check, DIG Test, Ping, More).  
>    ⚠️ Adds a dedicated "Settings" icon, making the bar a bit busier.
> 2. **Add "Settings" as a card inside MoreToolsScreen** (similar to BulkActions, DeviceInfo, etc.).  
>    ✅ Less intrusive, consistent with how other secondary tools are accessed.
>
> The spec says "bottom navigation or overflow menu". Recommendation: **Option 2 (MoreToolsScreen card)** to keep the bottom bar clean. Please confirm.
=> Option 2 (MoreToolsScreen card)

> [!IMPORTANT]
> **Timeout scope for `PingViewModel` and `TracerouteViewModel`**  
> Both tools are long-running and *stoppable by the user*:
> - `PingViewModel` runs `ping -c 100` (up to hundreds of seconds).
> - `TracerouteViewModel` loops TTL 1–30 with `ping -c 1 -W 2` per hop (up to 60 s).
>
> Wrapping with `withTimeout(timeoutSeconds * 1000L)` will auto-cancel them after N seconds, the same way the user manually stops them. When `TimeoutCancellationException` fires we should save history and set `isRunning = false`, which is what the `finally {}` block already does. **Does this behaviour match the desired UX?** Should a timeout banner be shown vs. silent stop?
=> silent stop is fine for now

> [!NOTE]
> **Timeout scope for `PortScannerViewModel` and `LanScannerViewModel`**  
> These do chunk-based concurrent scanning. Wrapping the outer `scanJob` in `withTimeout` will cancel the whole job after N seconds. The per-port/per-IP socket timeouts (currently hardcoded to 1 s each) remain independent. Is this the intended behaviour?
=> intended behavior
---

## Proposed Changes

### 1. New Package — `settings`

#### [NEW] `SettingsDataStore.kt`
`io.github.mobilutils.ntp_dig_ping_more.settings`

A top-level DataStore extension and primitive I/O:
```kotlin
private val Context.settingsDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "app_settings")

object SettingsKeys {
    val TIMEOUT_SECONDS = intPreferencesKey("timeout_seconds")
    const val DEFAULT_TIMEOUT = 5
    const val MIN_TIMEOUT = 1
    const val MAX_TIMEOUT = 60
}
```

**Why a separate DataStore name?** Each tool already has its own named store (`ntp_history`, etc.). Settings warrant a dedicated `app_settings` store rather than polluting an existing one.

---

#### [NEW] `SettingsRepository.kt`
`io.github.mobilutils.ntp_dig_ping_more.settings`

```kotlin
class SettingsRepository(private val context: Context) {
    /** Emits the current timeout value reactively. */
    val timeoutSecondsFlow: Flow<Int>

    /** Persists a new timeout, clamping to [MIN_TIMEOUT]..[MAX_TIMEOUT]. */
    suspend fun updateTimeout(seconds: Int)
}
```

- Validates input (clamp `1..60`) so no ViewModel needs its own guard.
- Single responsibility: persistence only — no business logic.

---

### 2. New Settings UI

#### [NEW] `SettingsViewModel.kt`
`io.github.mobilutils.ntp_dig_ping_more` (same root package as all other VMs)

```kotlin
class SettingsViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    data class UiState(
        val timeoutInput: String = "5",   // raw text-field content
        val isError: Boolean = false,
        val savedTimeout: Int = 5,        // last confirmed-valid value
    )

    val uiState: StateFlow<UiState>

    fun onTimeoutChange(value: String)   // validates & calls updateTimeout on valid change
    fun saveTimeout()                    // explicit save (fallback if focus-loss misses)

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory
    }
}
```

- Reads `settingsRepository.timeoutSecondsFlow` to initialise the text field.
- Validates: `isError = value !in 1..60`.
- Saves immediately on a valid change (spec preference: "immediate save on valid input change").

---

#### [NEW] `SettingsScreen.kt`
`io.github.mobilutils.ntp_dig_ping_more`

Material 3 `Scaffold` with `TopAppBar` ("Settings"):
- Section header: **"Network Configuration"**
- `OutlinedTextField` labelled "Operation Timeout (seconds)"
  - `KeyboardType.Number`
  - `isError` / supporting text: "Must be between 1 and 60" when invalid
  - Helper text: "Applies to total duration of scans/requests."
- The ViewModel is obtained with `viewModel(factory = SettingsViewModel.factory(context))`.

No `Back` navigation arrow (the top app bar is the shared one from `AppRoot`).

---

### 3. Navigation & Entry Point — `MainActivity.kt`

Three changes in one file:

#### a) Add `AppScreen.Settings` sealed object
```kotlin
object Settings : AppScreen("settings", "Settings", Icons.Filled.Settings)
```
Also add it to `allAppScreens` so the top bar title resolves correctly.

#### b) Add composable route in `NavHost`
```kotlin
composable(AppScreen.Settings.route) {
    SettingsScreen()
}
```

#### c) Add Settings card to the `isMoreToolsSelected` route list
The `isMoreToolsSelected` check (line 208–216) already enumerates sub-routes that should keep the "More" tab highlighted. Add `AppScreen.Settings.route` to that list.

> [!NOTE]
> **No BottomNav 5th item.** `bottomNavItems` stays as-is (NTP, DIG, Ping, More).

---

### 4. `MoreToolsScreen.kt` — Add Settings entry

Add `AppScreen.Settings` to the `extraTools` list **at the top** (highest priority, most visible):
```kotlin
val extraTools = listOf(
    AppScreen.Settings,      // ← NEW
    AppScreen.BulkActions,
    AppScreen.Traceroute,
    ...
)
```

---

### 5. ViewModel Timeout Integration (7 files)

Each ViewModel needs two changes:
1. **Constructor** — add `private val settingsRepository: SettingsRepository`.
2. **Main coroutine** — wrap with `withTimeout(timeoutMs)`.
3. **Factory** — pass `SettingsRepository(context.applicationContext)`.
4. **Error handling** — catch `TimeoutCancellationException` and show a timeout error in UI state.

The `SettingsRepository` instance is **not** shared from `MainActivity` (no Hilt). Each ViewModel creates its own instance from `context.applicationContext` in the factory. DataStore internally deduplicates via the named store, so there's no double-write risk.

---

#### [MODIFY] `NtpViewModel.kt` — **Reference implementation**

```kotlin
// Constructor
class SimpleNtpViewModel(
    private val repository: NtpRepository = NtpRepository(),
    private val historyStore: NtpHistoryStore,
    private val settingsRepository: SettingsRepository,  // ← NEW
) : ViewModel()

// checkReachability()
checkJob = viewModelScope.launch {
    val timeoutMs = settingsRepository.timeoutSecondsFlow.first() * 1000L
    val result = try {
        withTimeout(timeoutMs) {
            repository.query(host, port)
        }
    } catch (e: TimeoutCancellationException) {
        NtpResult.Timeout(host)   // already exists in the sealed class ✓
    }
    // ... rest unchanged
}
```

**Note:** `NtpResult.Timeout` already exists, so no new result type is needed.

---

#### [MODIFY] `DigViewModel.kt`

Wrap `repository.resolve(server, fqdn)` inside `withTimeout`. Catch `TimeoutCancellationException` → produce `DigResult.Error("Operation timed out")` (no `DigResult.Timeout` exists; `DigResult.Error` is the appropriate fallback).

---

#### [MODIFY] `PingViewModel.kt`

Wrap the entire `pingJob` launch body in `withTimeout`. The existing `finally {}` block already calls `saveHistory` and sets `isRunning = false`, so it will fire correctly on cancellation. A `TimeoutCancellationException` is caught at the outer `catch (_: Exception)` — change this to be explicit so we can differentiate (show a "Timed out" line in `outputLines`).

```kotlin
pingJob = viewModelScope.launch {
    val timeoutMs = settingsRepository.timeoutSecondsFlow.first() * 1000L
    try {
        withTimeout(timeoutMs) {
            // ... existing subprocess reading logic
        }
    } catch (e: TimeoutCancellationException) {
        _uiState.value = _uiState.value.copy(
            outputLines = _uiState.value.outputLines + "--- Timed out after ${timeoutMs / 1000}s ---"
        )
    } catch (_: Exception) { /* interrupted */ }
    finally {
        // ... cleanup unchanged
    }
}
```

---

#### [MODIFY] `TracerouteViewModel.kt`

Wrap the `for (ttl in 1..30)` loop in `withTimeout`. On `TimeoutCancellationException`, append `"--- Timed out ---"` to `outputLines` and save history with the partial result. The status computation after the loop handles partial hops gracefully already.

---

#### [MODIFY] `PortScannerViewModel.kt`

Wrap the outer `scanJob = viewModelScope.launch(Dispatchers.IO) { ... }` body in `withTimeout`. On cancellation, set `isRunning = false` and call `saveHistory`.

---

#### [MODIFY] `LanScannerViewModel.kt`

Same pattern as `PortScannerViewModel` — wrap `scanJob` body in `withTimeout`.

---

#### [MODIFY] `GoogleTimeSyncViewModel.kt`

Wrap `repository.fetchGoogleTime(effectiveUrl)` in `withTimeout`. Catch `TimeoutCancellationException` → produce `GoogleTimeSyncResult.Timeout` (already exists in the sealed class).

---

### Summary Table

| File | Status | Change type |
|---|---|---|
| `settings/SettingsDataStore.kt` | 🆕 New | DataStore extension + key constants |
| `settings/SettingsRepository.kt` | 🆕 New | Flow + suspend updateTimeout |
| `SettingsViewModel.kt` | 🆕 New | ViewModel + factory |
| `SettingsScreen.kt` | 🆕 New | Compose UI |
| `MainActivity.kt` | ✏️ Modify | Add `AppScreen.Settings`, NavHost route, `isMoreToolsSelected` list |
| `MoreToolsScreen.kt` | ✏️ Modify | Add Settings to `extraTools` list |
| `NtpViewModel.kt` | ✏️ Modify | Constructor + `withTimeout` in `checkReachability()` |
| `DigViewModel.kt` | ✏️ Modify | Constructor + `withTimeout` in `runDigQuery()` |
| `PingViewModel.kt` | ✏️ Modify | Constructor + `withTimeout` in `startPing()` |
| `TracerouteViewModel.kt` | ✏️ Modify | Constructor + `withTimeout` in `startTraceroute()` |
| `PortScannerViewModel.kt` | ✏️ Modify | Constructor + `withTimeout` in `startScan()` |
| `LanScannerViewModel.kt` | ✏️ Modify | Constructor + `withTimeout` in `startScan()` |
| `GoogleTimeSyncViewModel.kt` | ✏️ Modify | Constructor + `withTimeout` in `syncTime()` |
| `app/build.gradle.kts` | ✅ No change | DataStore Preferences already in deps |

---

## Verification Plan

### Automated Tests
- Run `./gradlew testDebugUnitTest` after changes to confirm no existing tests break.
- No new unit tests are in scope for this plan (can be added separately).

### Manual Verification (after build)
1. Build and install debug APK: `./gradlew installDebug`.
2. Navigate to **More → Settings**; verify the screen renders with a timeout field defaulting to `5`.
3. Enter `3` → field saves; navigate away and back → value persists.
4. Enter `0` or `61` → error state shown, value not saved.
5. Run **NTP Check** against a slow/unreachable server → confirm "Unreachable – Timeout" after ≤3 s.
6. Run **DIG** against an unreachable server → confirm timeout error message.
7. Run **Ping** → confirm auto-stops with timeout message after N s.
8. Run **Traceroute** → confirm partial results with "Timed out" appended.
9. Run **Port Scanner** (large range) → stops after N s.
10. Run **LAN Scanner** → stops after N s.
11. Run **Google Time Sync** against slow URL → timeout error.
12. Kill and relaunch the app → confirm saved timeout value is restored.
