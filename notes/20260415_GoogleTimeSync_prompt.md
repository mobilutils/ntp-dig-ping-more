# Add "Google Time Sync" Feature

## Objective
Add a new feature that queries Google's time sync endpoint `http://clients2.google.com/time/1/current` and displays synchronized time with offset calculation.

## Endpoint Specification
```
GET http://clients2.google.com/time/1/current
```

### Response Handling
1. Response is prefixed with `)]}'` (XSSI protection) → **strip first 4 characters** before parsing
2. JSON schema:
```json
{
  "current_time_millis": 1776240093024,  // int64, UTC epoch ms
  "server_nonce": -6.984715536726945E9    // double, replay-attack nonce
}
```

### Time Sync Calculation Logic
```
T1 = client timestamp (ms) just BEFORE sending request
T4 = client timestamp (ms) when response is RECEIVED
serverTime = current_time_millis from response

RTT = T4 - T1
correctedServerTime = serverTime + (RTT / 2)
offset = correctedServerTime - T4  // positive = local clock behind
```

## 🏗️ Implementation Requirements

### 1. Network Layer
- Create `GoogleTimeSyncRepository` with suspend function `fetchGoogleTime(host: String = "clients2.google.com")`
- Use `HttpURLConnection` or `Ktor Client` (lightweight, no new heavy deps)
- Handle:
  - Stripping `)]}'` prefix
  - JSON parsing via `kotlinx.serialization` or `org.json`
  - Timeout: 10s connection, 15s read
  - IOException, malformed response, non-200 status

### 2. Domain/Use Case
- Create `CalculateTimeOffsetUseCase`:
```kotlin
data class TimeSyncResult(
    val serverTimeMillis: Long,
    val rttMillis: Long,
    val offsetMillis: Long,      // local = server + offset
    val correctedServerTimeMillis: Long,
    val requestTimestamp: Long,  // T1
    val responseTimestamp: Long  // T4
)
```

### 3. ViewModel (`GoogleTimeSyncViewModel`)
- StateFlow UI state: `Idle`, `Loading`, `Success(TimeSyncResult)`, `Error(String)`
- Expose: `fun syncTime(host: String)`, `fun reset()`
- Keep last result in memory for UI re-composition

### 4. UI Screen (`GoogleTimeSyncScreen.kt`)
- Input field for host (default: `clients2.google.com`) with "Sync" button
- Loading indicator during request
- Result card showing:
  - 🕐 Server time (formatted: `yyyy-MM-dd HH:mm:ss.SSS UTC`)
  - 📡 RTT: `XXX ms`
  - ⏱️ Offset: `+XX ms` / `-XX ms` (color-coded: green=small, orange=medium, red=large)
  - 🧮 Corrected local time preview
- "Copy offset" button for manual clock adjustment
- Error snackbar with retry option
- Follow Material 3 guidelines, support dark mode

### 5. Navigation & Integration
- Add new route `google_time_sync` in `NavGraph`
- Add entry in main toolbox screen (icon: `Icons.Default.AccessTime`)
- Add string resources in `strings.xml` (prepare for localization)

### 6. Permissions & Manifest
- Ensure `<uses-permission android:name="android.permission.INTERNET" />` is declared
- Add `android:usesCleartextTraffic="true"` in `<application>` (endpoint is HTTP)

### 7. Testing & Edge Cases
- Handle: no network, timeout, invalid JSON, missing prefix, non-200 response
- Unit test `CalculateTimeOffsetUseCase` with mock T1/T4/serverTime
- UI test: loading → success flow

## 🎨 UI Copy (English, adapt for FR if needed)
- Title: "Google Time Sync"
- Subtitle: "Query clients2.google.com for UTC time with offset calculation"
- Button: "Sync Now"
- Labels: "Server Time", "Round-Trip Time", "Clock Offset", "Corrected Time"
- Helper: "Offset = corrected server time − your device time"

## ✅ Acceptance Criteria
- [ ] App queries endpoint over HTTP and parses response correctly
- [ ] Time calculations match specification (RTT/offset/corrected time)
- [ ] UI shows loading, success, and error states
- [ ] Works on Min SDK 26, no crashes on rotation/config change
- [ ] Follows existing code style and architecture patterns
- [ ] No new heavy dependencies added

## 📁 Suggested File Structure
```
app/
├── src/main/java/com/yourapp/timesync/
│   ├── data/
│   │   ├── GoogleTimeSyncRepository.kt
│   │   └── model/GoogleTimeResponse.kt
│   ├── domain/
│   │   └── CalculateTimeOffsetUseCase.kt
│   ├── presentation/
│   │   ├── GoogleTimeSyncViewModel.kt
│   │   ├── GoogleTimeSyncScreen.kt
│   │   └── model/TimeSyncUiState.kt
│   └── navigation/GoogleTimeSyncNavGraph.kt
├── src/main/res/values/strings.xml (add new string resources)
└── AndroidManifest.xml (verify cleartext traffic permission)
```

## 💡 Pro Tips
- Use `System.currentTimeMillis()` for T1/T4 (sufficient precision for ms-level sync)
- Consider adding a "last synced" timestamp in UI
- For advanced: allow user to apply offset to display a "synced clock" preview
- Log verbose network events only in debug builds
```

---

> ℹ️ **Note for you**: Since your endpoint uses **HTTP (not HTTPS)**, remember that `android:usesCleartextTraffic="true"` is required in `AndroidManifest.xml`. For production, consider warning users about cleartext traffic in a tooltip.
