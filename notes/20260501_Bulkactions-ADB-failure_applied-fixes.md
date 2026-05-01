# Bulk Actions — Applied Fixes

> Date: 2026-05-01
> Updated: 2026-05-01 (Option A — push to app private dir, corrected for `run-as` workflow)
> Related: `notes/20260501_Bulkactions-ADB-failure_troubleshooting.md`
> Branch: `feat/bulk-actions-add-intent-for-adb-automation`

---

## Summary

Applied all fixes from the troubleshooting analysis to `BULKACTIONS-ADB-SCRIPT.sh`, `BulkActionsViewModel.kt`, `BulkActionsRepository.kt`, and all config JSON files. The critical SDK 33+ permission issue (Bug #2) was resolved by switching to **Option A: push to app's private directory** — no storage permission needed.

---

## Fix 0 (Critical — SDK 33+ scoped storage): Push to app's private directory

**Files:** `BULKACTIONS-ADB-SCRIPT.sh`, `BulkActionsRepository.kt`, `BulkActionsViewModel.kt`, all `notes/config-files_bulk-actions/*.json`

### The problem

On Android 13 (API 33+), `READ_EXTERNAL_STORAGE` is no longer a dangerous permission. `openInputStream()` on a `file://` URI pointing to `/sdcard/...` throws `EACCES (Permission denied)`, crashing the app. Even `adb shell pm grant` silently fails.

### The fix

All config files and output paths were switched from `/sdcard/Download/` (or `~/Download/`) to the app's private files directory (`/data/user/0/<package>/files/`), where no storage permission is required.

#### ADB Script changes (`BULKACTIONS-ADB-SCRIPT.sh`)

**Variables:**
```bash
APP_ID="io.github.mobilutils.ntp_dig_ping_more"
PRIVATE_DIR="/data/user/0/$APP_ID/files"
PUSH_PATH="$PRIVATE_DIR/$CONFIG"
```

**Step 2 — push via host-side pipe to `run-as` (corrected from direct push):**

> **Important:** `adb push` runs as the `shell` user, which **cannot write** to another app's private directory — this is a fundamental Android sandbox restriction. Additionally, `run-as` executes as the app's UID, which **cannot read** `/sdcard/Download/` (a different user namespace). The fix uses a **host-side pipe**: `cat` reads the file on the host, and pipes it into a single `adb shell` command that runs as the app's UID, writing directly to the private dir. The file content never touches `/sdcard/` on the device.

**Before (failed with "Permission denied"):**
```bash
# Approach 1: direct push — shell user cannot write to app private dir
adb push "$CONFIG_SOURCE" "$PUSH_PATH"

# Approach 2: two-stage copy via /sdcard/ — run-as cannot read /sdcard/
adb push "$CONFIG_SOURCE" "$SDCARD_PUSH"
adb shell "run-as $APP_ID cp $SDCARD_PUSH $PUSH_PATH"
```

**After (host-side pipe, single `adb shell`):**
```bash
# Host-side cat pipes file content into a single adb shell that runs as the app's UID.
# This avoids the /sdcard/ read permission issue (run-as cannot read /sdcard/).
cat "$CONFIG_SOURCE" | adb shell "run-as $APP_ID cat > $PUSH_PATH"
```

**Step 3 — verification (new):**
```bash
echo "[3/7] Verifying config file..."
adb shell "run-as $APP_ID test -f $PUSH_PATH" || {
    echo "  ERROR: Config file not found in private dir after push."
    echo "  Try: adb shell run-as $APP_ID ls -la files/"
    exit 1
}
echo "  Config file verified in private directory."
```

#### Config files (`notes/config-files_bulk-actions/*.json`)

All `output-file` values updated:

| Old path | New path |
|---|---|
| `~/Download/blkacts_single_ping_success.txt` | `~/files/blkacts_single_ping_success.txt` |
| `~/Downloads/output-test-run.txt` | `~/files/output-test-run.txt` |
| `/sdcard/Download/bulk-with-deviceinfo.txt` | `~/files/bulk-with-deviceinfo.txt` |
| `/tmp/blkacts_edge_absolute_path.txt` | `~/files/blkacts_edge_absolute_path.txt` |

#### BulkConfigParser (`BulkActionsRepository.kt`)

Added a context holder and updated path expansion:

```kotlin
object BulkConfigParser {
      /** Application context, set once by BulkActionsViewModel factory. */
      @Volatile
    internal var appContext: Context? = null

      /** Expands `~` to the app's private files directory (no permissions needed on SDK 33+). */
    private fun expandTilde(path: String): String {
        if (!path.startsWith("~/")) return path
        val privateDir = appContext?.applicationContext?.filesDir?.absolutePath
              ?: Environment.getExternalStorageDirectory().absolutePath
        return "$privateDir${path.substring(1)}"
      }

      /** Suggests a fallback writable path using the app's private directory. */
    private fun suggestFallbackPath(rawPath: String): String {
        val privateDir = appContext?.applicationContext?.filesDir?.absolutePath
              ?: Environment.getExternalStorageDirectory().absolutePath
        val bulkDir = "$privateDir/BulkActions"
        val fileName = rawPath.substringAfterLast("/")
        return "$bulkDir/$fileName"
      }
}
```

#### BulkActionsViewModel factory (`BulkActionsViewModel.kt`)

The factory now sets `BulkConfigParser.appContext` before creating the ViewModel:

```kotlin
companion object {
    fun factory(context: Context): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
              @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                  // Set app context for BulkConfigParser to use private dir path expansion
                BulkConfigParser.appContext = context.applicationContext
                return BulkActionsViewModel(
                    context = context.applicationContext,
                    repository = BulkActionsRepository(context.applicationContext),
                  ) as T
              }
          }
}
```

### Why this works

- `/data/user/0/<package>/files/` is the app's internal private storage (returned by `Context.getFilesDir()`).
- No `READ_EXTERNAL_STORAGE` or `WRITE_EXTERNAL_STORAGE` permission needed.
- `cat "$CONFIG_SOURCE" | adb shell "run-as $APP_ID cat > $PUSH_PATH"` — the host reads the file and pipes it into a single `adb shell` process that runs as the app's UID, writing directly to its private dir. The file content never touches `/sdcard/` on the device.
- `file:///data/user/0/<package>/files/config.json` URI opens successfully via `contentResolver.openInputStream()`.
- `adb pull /data/user/0/<package>/files/output.txt` retrieves results.

### New automation flow

```
Host: cat config.json | adb shell "run-as <package> cat > /data/user/0/<package>/files/config.json"
      ↓
App: MainActivity receives intent.data (file:// URI to private dir)
      ↓
App: openInputStream() → works, no permission needed
      ↓
App: commands execute, output written to /data/user/0/<package>/files/bulk-output.txt
      ↓
ADB: pull /data/user/0/<package>/files/bulk-output.txt → local
```

---

## Fix 1 (Critical): `adb pull` source path now expands `~`

**File:** `BULKACTIONS-ADB-SCRIPT.sh`

> **Note:** This fix is still in place, but the expansion target changed from `/sdcard/` to the app's private directory (`/data/user/0/<package>/files/`). See Fix 0 above.

**Before:**
```bash
if [ -n "$OUTPUT_FILE" ]; then
      # Expand ~ to /sdcard
    LOCAL_OUTPUT="$RESULTS_DIR/$(basename "${OUTPUT_FILE~/}")"
    adb pull "$OUTPUT_FILE" "$LOCAL_OUTPUT" 2>/dev/null || {
```

**After:**
```bash
if [ -n "$OUTPUT_FILE" ]; then
      # Expand ~ to app's private dir for both device source and local destination
    DEVICE_OUTPUT_FILE=$(echo "$OUTPUT_FILE" | sed 's|^~/|'"$PRIVATE_DIR/"'|')
    LOCAL_OUTPUT="$RESULTS_DIR/$(basename "$DEVICE_OUTPUT_FILE")"
    adb pull "$DEVICE_OUTPUT_FILE" "$LOCAL_OUTPUT" 2>/dev/null || {
```

**Why:** The old code only expanded `~` in the destination path (`LOCAL_OUTPUT`), but passed the unexpanded `~` to `adb pull` as the source. Since `adb pull` doesn't expand `~`, it tried to read a literal file named `~/...` on the device. Now both source and destination use the app's private directory.

---

## Fix 2 (Moderate): Wait time formula accounts for sequential command count

**File:** `BULKACTIONS-ADB-SCRIPT.sh`

**Before:**
```bash
# Estimate: config timeout * 2 (commands run sequentially) + 30s overhead
ESTIMATED_WAIT=$(( CONFIG_TIMEOUT_SEC * 2 + 30 ))
```

**After:**
```bash
# Count commands in the "run" object using grep
COMMAND_COUNT=$(grep -c '"[a-z_-]*":' "$CONFIG_SOURCE" || echo 1)

# Estimate: each command can take up to CONFIG_TIMEOUT_SEC; sequential + 30s overhead
ESTIMATED_WAIT=$(( CONFIG_TIMEOUT_SEC * COMMAND_COUNT + 30 ))
```

**Why:** Commands run sequentially, so `timeout × 2` underestimates for configs with >2 commands. The new formula counts commands from the JSON and multiplies by the per-command timeout.

---

## Fix 3 (Minor): `writeViaSAF` explicitly handles null URI

**File:** `BulkActionsViewModel.kt`

**Before:**
```kotlin
private suspend fun writeViaSAF(path: String, results: List<BulkCommandResult>): Boolean {
    val uri = android.net.Uri.parse(path) ?: return false
```

**After:**
```kotlin
private suspend fun writeViaSAF(path: String, results: List<BulkCommandResult>): Boolean {
    val uri = android.net.Uri.parse(path)
      // Plain file paths (not file:// URIs) skip SAF and fall through to writeDirect
    if (uri == null) return false
```

**Why:** `Uri.parse("/sdcard/...")` returns null — not a bug (fallback works), but the inline Elvis operator is misleading. Split into explicit check with comment for clarity.

---

## Fix 4 (Minor): `onLoadAndRun` reports error on empty config

**File:** `BulkActionsViewModel.kt`

**Before:**
```kotlin
if (json.isBlank()) return@launch
```

**After:**
```kotlin
if (json.isBlank()) {
      _uiState.value = _uiState.value.copy(
        configLoaded = false,
        configFileName = null,
        commandCount = 0,
        validationMessage = ValidationMessage.Error("Config file is empty or unreadable: $fileName")
      )
    return@launch
}
```

**Why:** Previously the user saw nothing if the config was empty/unreadable — no error message, no feedback.

---

## Fix 5 (Minor): File I/O moved off main thread

**File:** `BulkActionsViewModel.kt`

**Before:**
```kotlin
private suspend fun writeDirect(path: String, results: List<BulkCommandResult>): Boolean {
    return try {
        val file = File(path)
        file.parentFile?.mkdirs()
        val content = if (csvEnabled) generateCsvContent(results) else generateOutputContent(results)
        file.writeText(content)
        true
      } catch (e: Exception) { false }
}
```

**After:**
```kotlin
private suspend fun writeDirect(path: String, results: List<BulkCommandResult>): Boolean {
    return try {
        withContext(Dispatchers.IO) {
            val file = File(path)
            file.parentFile?.mkdirs()
            val content = if (csvEnabled) generateCsvContent(results) else generateOutputContent(results)
            file.writeText(content)
          }
        true
      } catch (e: Exception) { false }
}
```

**Why:** `writeDirect` is called from `onLoadAndRun` which runs on `viewModelScope` (defaults to `Dispatchers.Main`). Wrapping in `withContext(Dispatchers.IO)` prevents potential jank/ANRs on large outputs.

---

## Verification

All changes applied via `sed -i ''` (macOS), Python, and `edit` tool. Verified with:
- `sed -n` inspection of changed line ranges
- `grep` for key strings confirming presence of fix code
- No duplicate or orphaned lines remaining
- `bash -n` syntax check passes
- `./gradlew compileDebugKotlin` — **BUILD SUCCESSFUL**

---

## Fix 6 (Minor): `--show-emulator` flag for visible emulator window

**File:** `BULKACTIONS-ADB-SCRIPT.sh`

**Usage:**
```bash
./BULKACTIONS-ADB-SCRIPT.sh blkacts_single_ping_success.json --show-emulator
```

**Changes:**

1. Added `SHOW_EMULATOR=false` default variable.
2. Added `--show-emulator` to arg parsing case block.
3. Updated Usage comment: `[--show-emulator]` appended.
4. Updated emulator launch logic:
     - Default: `-no-skin` (hidden, no window)
     - With `--show-emulator`: omits `-no-skin`, so emulator window is visible

**Before:**
```bash
NO_INTERACT=false
for arg in "$@"; do
    case "$arg" in
          --no-interact) NO_INTERACT=true ;;
    esac
done
...
emulator -avd "$EMULATOR" -no-skin -no-audio -no-boot-anim &
```

**After:**
```bash
NO_INTERACT=false
SHOW_EMULATOR=false
for arg in "$@"; do
    case "$arg" in
          --no-interact) NO_INTERACT=true ;;
          --show-emulator) SHOW_EMULATOR=true ;;
    esac
done
...
if [ "$SHOW_EMULATOR" = true ]; then
    emulator -avd "$EMULATOR" -no-audio -no-boot-anim &
    echo "  Emulator window will be visible."
else
    emulator -avd "$EMULATOR" -no-skin -no-audio -no-boot-anim &
fi
```

**Why:** The default `-no-skin` flag hides the emulator window entirely, making it impossible to observe the app executing during automation. The `--show-emulator` flag lets users see the emulator in real-time for debugging or demonstration purposes.
