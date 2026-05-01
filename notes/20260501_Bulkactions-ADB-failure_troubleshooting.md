# Bulk Actions — ADB Automation Failure Analysis

> Created: 2026-05-10
> Updated: 2026-05-01
> Branch: `feat/bulk-actions-add-intent-for-adb-automation`
> Related: `BULKACTIONS-ADB-SCRIPT.sh`, `BulkActionsViewModel.kt`, `BulkActionsScreen.kt`, `MainActivity.kt`, `AndroidManifest.xml`

---

## Executive Summary

The Bulk Actions ADB automation script (`BULKACTIONS-ADB-SCRIPT.sh`) is **not fully functional**. Multiple issues prevent successful end-to-end testing:

1. **Critical: `adb pull` source path never expands `~`** — results are never retrieved
2. **Critical (new, 2026-05-01): `file://` URIs always fail on SDK 33+** — `READ_EXTERNAL_STORAGE` is no longer a runtime permission; `openInputStream()` throws `EACCES` on every `file://` URI, crashing the app
3. **Moderate: Wait time formula is too short** for multi-command configs
4. **Minor: `writeViaSAF` always returns false** for plain file paths
5. **Minor: Silent failure on empty config**
6. **Minor: File I/O on main thread**

**Severity breakdown:** 2 Critical, 1 Moderate, 3 Minor

---

## Bug 1 (Critical): `adb pull` source path never expands `~`

**Location:** `BULKACTIONS-ADB-SCRIPT.sh`, lines 117–121

### The code

```bash
OUTPUT_FILE=$(json_str_field "$CONFIG_SOURCE" "output-file")

if [ -n "$OUTPUT_FILE" ]; then
       # Expand ~ to /sdcard
    LOCAL_OUTPUT="$RESULTS_DIR/$(basename "${OUTPUT_FILE#\~/}")"
    adb pull "$OUTPUT_FILE" "$LOCAL_OUTPUT" 2>/dev/null || {
        echo "  WARNING: Could not pull $OUTPUT_FILE (may not exist if auto-save failed)"
     }
```

### What's wrong

The comment says "Expand ~ to /sdcard" but the expansion **only applies to `LOCAL_OUTPUT`** (the destination path on the host). The **source** path (`$OUTPUT_FILE`) is passed to `adb pull` as-is — e.g.:

```
adb pull "~/Download/blkacts_single_ping_success.txt" "./test-results/blkacts_single_ping_success.txt"
```

`adb pull` **does not expand `~`**. It interprets it literally, so it tries to read from a file whose name literally starts with `~/` on the device — which doesn't exist.

### Why it actually fails

The app's `BulkConfigParser.expandTilde()` correctly expands `~` to the device's external storage directory (`/storage/emulated/0`), so the file is written to:

```
/storage/emulated/0/Download/blkacts_single_ping_success.txt
```

But the script tries to pull from:

```
~/Download/blkacts_single_ping_success.txt   ← literal path, does not exist
```

**Result:** `adb pull` always fails with "No such file or directory".

### The fix

Expand `~` to `/sdcard` in the **source** path before passing it to `adb pull`:

```bash
if [ -n "$OUTPUT_FILE" ]; then
       # Expand ~ to /sdcard for both device source and local destination
    DEVICE_OUTPUT_FILE=$(echo "$OUTPUT_FILE" | sed 's|^~/|/sdcard/|')
    LOCAL_OUTPUT="$RESULTS_DIR/$(basename "$DEVICE_OUTPUT_FILE")"
    adb pull "$DEVICE_OUTPUT_FILE" "$LOCAL_OUTPUT" 2>/dev/null || {
        echo "  WARNING: Could not pull $DEVICE_OUTPUT_FILE (may not exist if auto-save failed)"
     }
    echo "       -> $LOCAL_OUTPUT"
else
    echo "  No output-file defined in config - results are in-memory only"
fi
```

**Note:** `/sdcard` is the standard ADB-accessible path that maps to `/storage/emulated/0` on the device. The app's `Environment.getExternalStorageDirectory().absolutePath` also resolves to `/storage/emulated/0`, which is symlinked to `/sdcard`.

---

## Bug 2 (Critical — New, 2026-05-01): `file://` URIs always fail on SDK 33+

**Location:** `BulkActionsViewModel.kt` (lines 85, 165), `BulkActionsScreen.kt` (line 110), `AndroidManifest.xml` (line 18)

### The symptom

Every ADB-driven launch of Bulk Actions with a `file://` URI **crashes the app** with:

```
E AndroidRuntime: java.io.FileNotFoundException: /sdcard/Download/blkacts_single_ping_success.json: open failed: EACCES (Permission denied)
E AndroidRuntime:     at android.content.ContentResolver.openInputStream(ContentResolver.java:1526)
E AndroidRuntime:     at io.github.mobilutils.ntp_dig_ping_more.BulkActionsViewModel$onFileSelected$1$json$1.invokeSuspend(BulkActionsViewModel.kt:85)
```

The system log confirms:
```
E PermissionService: Permission android.permission.READ_EXTERNAL_STORAGE isn't requested by package io.github.mobilutils.ntp_dig_ping_more
```

Even after running `adb shell pm grant io.github.mobilutils.ntp_dig_ping_more android.permission.READ_EXTERNAL_STORAGE`, the grant silently fails.

### Root cause

`READ_EXTERNAL_STORAGE` in `AndroidManifest.xml` is declared with:
```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
```

On **Android 13 (API 33) and above**, scoped storage is the default. `READ_EXTERNAL_STORAGE` is **no longer a dangerous permission** — the system does not grant it, and `pm grant` silently ignores it. The `PermissionService` log confirms this: `"isn't requested by package"`.

When the app calls `contentResolver.openInputStream(uri)` on a `file://` URI, the system checks storage permission. Since the permission is not held, it throws `EACCES (Permission denied)`. This is **not a bug in the app's code** — it's the expected behavior of Android's scoped storage model.

### Why the crash happened

Before the fix (this session), `onFileSelected`, `onLoadAndRun`, and the `BulkActionsScreen` auto-load LaunchedEffect all called `openInputStream()` without any try-catch. The unhandled `FileNotFoundException` crashed the entire process, force-finishing the activity.

### What was fixed this session

Three locations now wrap `openInputStream()` in try-catch for `java.io.IOException`, setting an error UI state instead of crashing:

| Location | Function | Line |
|----------|----------|------|
| `BulkActionsViewModel.kt` | `onFileSelected()` | 85 |
| `BulkActionsViewModel.kt` | `onLoadAndRun()` | 165 |
| `BulkActionsScreen.kt` | `LaunchedEffect` auto-load | 110 |

After this fix, the app **no longer crashes**. However, the file is **still not read** — the error message is shown in the UI instead.

### Workaround options

Since `file://` URIs cannot be read on SDK 33+ without storage permission (which cannot be granted), you have three options:

#### Option A: Push to app's private directory (recommended for ADB automation)

Instead of pushing to `/sdcard/Download/`, push to the app's private files directory where no permission is needed:

```bash
# Push config to app's private dir (no permission needed)
adb shell "cat /sdcard/Download/blkacts_single_ping_success.json > /data/user/0/io.github.mobilutils.ntp_dig_ping_more/files/config.json"

# Launch with a file:// URI pointing to the private dir
adb shell am start \
     -n io.github.mobilutils.ntp_dig_ping_more/.MainActivity \
     -d file:///data/user/0/io.github.mobilutils.ntp_dig_ping_more/files/config.json \
     --es auto_run true
```

Similarly, for output files, use the app's private dir:
```json
{
     "output-file": "/data/user/0/io.github.mobilutils.ntp_dig_ping_more/files/bulk-output.txt"
}
```

Then pull:
```bash
adb pull /data/user/0/io.github.mobilutils.ntp_dig_ping_more/files/bulk-output.txt ./test-results/
```

**Pros:** No permission changes needed, works on all SDKs.
**Cons:** Requires changing the ADB script and config files; app needs `WRITE_EXTERNAL_STORAGE` or uses `openFileOutput()`/`getFilesDir()` for writing.

#### Option B: Use the Storage Access Framework (SAF) `OpenDocument` contract

Replace the `file://` URI flow with the standard Android file picker. The user taps "Load JSON Config" in the UI, picks a file from the system file picker, and the system grants a `content://` URI with read permission:

```kotlin
// In BulkActionsScreen.kt — already uses this for manual loading:
val filePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
) { uri: Uri? ->
    uri?.let {
        val fileName = it.lastPathSegment ?: "config.json"
        viewModel.onFileSelected(it, fileName)
     }
}
```

**Pros:** Works on all SDKs, no permissions needed, standard Android UX.
**Cons:** Cannot be automated via ADB `am start` — requires manual user interaction.

#### Option C: Use `content://` URI via `FileProvider`

If the config file must be pushed to a shared location (e.g., `/sdcard/Download/`), expose it via a `FileProvider` so the app receives a `content://` URI:

1. Add a `FileProvider` to `AndroidManifest.xml`
2. Push config to a directory accessible by the provider
3. Launch with `file://` URI → convert to `content://` via `FileProvider.getUriForFile()`
4. `openInputStream()` works because `content://` URIs don't require storage permission

**Pros:** Full ADB automation support, standard Android pattern.
**Cons:** More setup; requires `AndroidManifest.xml` changes and a provider XML file.

### Recommended approach for ADB automation

For the **automated test/CI pipeline**, **Option A** (push to private dir) is the quickest path to working automation. For **manual testing**, the existing `GetContent` file picker (Option B) is already implemented and works correctly.

**Key takeaway:** Never use `file://` URIs with `openInputStream()` on SDK 33+ — always use `content://` URIs (via file picker or FileProvider) or the app's own private storage.

---

## Bug 3 (Moderate): Wait time formula is too short for multi-command configs

**Location:** `BULKACTIONS-ADB-SCRIPT.sh`, lines 104–111

### The code

```bash
CONFIG_TIMEOUT_SEC=$(json_num_field "$CONFIG_SOURCE" "timeout")
CONFIG_TIMEOUT_SEC="${CONFIG_TIMEOUT_SEC:-30}"     # default 30s if not found

# Estimate: config timeout * 2 (commands run sequentially) + 30s overhead
ESTIMATED_WAIT=$(( CONFIG_TIMEOUT_SEC * 2 + 30 ))
if [ "$ESTIMATED_WAIT" -gt 300 ]; then
    ESTIMATED_WAIT=300      # cap at 5 minutes
fi
```

### What's wrong

The formula `timeout * 2 + 30` assumes commands run in parallel or that the timeout is a total budget. But commands run **sequentially**, and each command can take up to the timeout.

**Example with `blkacts_multi_all9_success.json`** (9 commands, config timeout = 30s):

| Step | Expected time | Script's wait |
|------|--------------|---------------|
| 9 commands × 30s max each | 270s (4.5 min) | 90s (1.5 min) |

The script pulls results after 90s, missing the last 6+ commands.

### The fix

A more accurate formula:

```bash
# Estimate: each command can take up to CONFIG_TIMEOUT_SEC; sequential + 30s overhead
ESTIMATED_WAIT=$(( CONFIG_TIMEOUT_SEC * COMMAND_COUNT + 30 ))
if [ "$ESTIMATED_WAIT" -gt 300 ]; then
    ESTIMATED_WAIT=300
fi
```

Or extract command count from the config:

```bash
# Count commands in the "run" object using grep
COMMAND_COUNT=$(grep -c '"[a-z_-]*": "ping\|dig\|ntp\|port-scan\|checkcert\|device-info\|tracert\|google-timesync\|lan-scan' "$CONFIG_SOURCE" || echo 1)
ESTIMATED_WAIT=$(( CONFIG_TIMEOUT_SEC * COMMAND_COUNT + 30 ))
```

---

## Bug 4 (Minor): `writeViaSAF` always returns false for plain file paths

**Location:** `BulkActionsViewModel.kt`

### The code

```kotlin
private suspend fun writeViaSAF(path: String, results: List<BulkCommandResult>): Boolean {
    val uri = android.net.Uri.parse(path) ?: return false
     // ... SAF logic
}
```

### What's wrong

`Uri.parse("/sdcard/Download/file.txt")` returns `null` because a plain file path is not valid URI syntax. So `writeViaSAF` always returns `false` for file paths, and `autoSaveResults` always falls through to `writeDirect`.

This isn't a functional bug — the fallback works correctly. But it wastes a try/catch round-trip and is misleading.

### The fix

Either remove `writeViaSAF` entirely (since all automation paths use plain file paths), or keep it for `file://` URI support:

```kotlin
private suspend fun writeViaSAF(path: String, results: List<BulkCommandResult>): Boolean {
    val uri = android.net.Uri.parse(path)
     // Plain file paths (not file:// URIs) skip SAF and fall through to writeDirect
    if (uri == null) return false
     // ... rest of SAF logic
}
```

---

## Bug 5 (Minor): `onLoadAndRun` / `onFileSelected` silently does nothing on empty config

**Location:** `BulkActionsViewModel.kt`

### The code (before fix)

```kotlin
fun onLoadAndRun(uri: Uri, fileName: String): Job {
    return viewModelScope.launch {
        val json = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.readBytes()?.decodeToString() ?: ""
         }
        if (json.isBlank()) return@launch     // <-- silently exits
         // ...
     }
}
```

### What's wrong

If the config file is empty, unreadable, or the URI is invalid, the user sees **nothing** — no error message, no loading indicator, no feedback at all. The script reports "Automation complete" even though nothing happened.

### The fix (applied this session)

Set an error state before returning:

```kotlin
if (json.isNullOrBlank()) {
     _uiState.value = _uiState.value.copy(
        configLoaded = false,
        configFileName = null,
        commandCount = 0,
        validationMessage = ValidationMessage.Error(
            readError ?: "Failed to read config file: $fileName"
         )
     )
    return@launch
}
```

This fix was applied to **all three locations**: `onFileSelected`, `onLoadAndRun`, and the `BulkActionsScreen` auto-load LaunchedEffect.

---

## Bug 6 (Minor): File I/O on main thread

**Location:** `BulkActionsViewModel.kt`

### The code

```kotlin
private suspend fun writeDirect(path: String, results: List<BulkCommandResult>): Boolean {
    return try {
        val file = File(path)
        file.parentFile?.mkdirs()
        val content = generateOutputContent(results)
        file.writeText(content)     // <-- synchronous disk I/O
        true
     } catch (e: Exception) { false }
}
```

### What's wrong

This is called from `onLoadAndRun` which runs in a `viewModelScope.launch { }` coroutine. `viewModelScope` defaults to `Dispatchers.Main`, so file I/O runs on the main thread. For small files this works, but for large outputs (e.g., 9-command configs with full output) it can cause jank or ANRs.

### The fix

Wrap in `withContext(Dispatchers.IO)`:

```kotlin
private suspend fun writeDirect(path: String, results: List<BulkCommandResult>): Boolean {
    return try {
        withContext(Dispatchers.IO) {
            val file = File(path)
            file.parentFile?.mkdirs()
            val content = generateOutputContent(results)
            file.writeText(content)
         }
        true
     } catch (e: Exception) { false }
}
```

---

## Complete Automation Flow Trace (post-fix)

```
Script: adb push config → /sdcard/Download/config.json
   ↓
App: MainActivity receives intent.data (file:// URI) + auto_run=true
   ↓
App: BulkActionsScreen LaunchedEffect(configUri, autoRun)
   ↓
App: viewModel.onLoadAndRun(uri, fileName)
   ↓
App: openInputStream(uri) → EACCES (Permission denied) on SDK 33+ ← BUG #2
   ↓
App: IOException caught → _uiState.validationMessage = Error("Failed to read...")
   ↓
App: App no longer crashes, but config is NOT loaded
   ↓
App: configLoaded = false → Run button stays disabled → nothing happens
   ↓
Script: "Automation complete" but no commands executed
   ↓
Script: adb pull "~/Download/file.txt" ← BUG #1: ~ not expanded
   ↓
Script: Result pull fails → "WARNING: Could not pull"
```

### Post-fix behavior

| Before fix | After fix |
|-----------|-----------|
| `openInputStream()` → unhandled crash | `openInputStream()` → IOException caught, error shown in UI |
| App force-closes immediately | App stays alive, shows error banner |
| No feedback to user | User sees "Failed to read config file" error |
| Still broken on SDK 33+ | Still broken on SDK 33+ (same root cause) |

The fix prevents crashes but does **not** solve the underlying SDK 33+ permission issue. Use Workaround A, B, or C (see Bug 2 above).

---

## Recommended Priority

| Priority | Bug | Fix effort | Status |
|----------|-----|------------|--------|
| **P0** | #1 — `adb pull` source path doesn't expand `~` | 2 min | Open |
| **P0** | #2 — `file://` URIs fail on SDK 33+ | Depends on workaround | Partially fixed (crash → error) |
| **P1** | #3 — Wait time too short for multi-command configs | 5 min | Open |
| P2 | #4 — `writeViaSAF` always returns false for file paths | 1 min | Open |
| P2 | #5 — Silent failure on empty config | 2 min | **Fixed** (all 3 locations) |
| P3 | #6 — File I/O on main thread | 1 min | Open |

---

## SDK 33+ Permission Reference

| Permission | SDK ≤ 32 | SDK ≥ 33 |
|-----------|----------|----------|
| `READ_EXTERNAL_STORAGE` | Granted via `pm grant` or runtime dialog | **Ignored** — not a dangerous permission |
| `file://` + `openInputStream()` | Works if permission granted | **Always fails** with `EACCES` |
| `content://` (SAF) | Works | Works |
| App private storage (`getFilesDir()`) | Works | Works |
| `ActivityResultContracts.OpenDocument` | Works | Works |

**Bottom line:** For ADB automation on SDK 33+, push config files to the app's private directory (`/data/user/0/<package>/files/`) and use `file://` URIs pointing there. Or switch to SAF file picker for manual testing.
