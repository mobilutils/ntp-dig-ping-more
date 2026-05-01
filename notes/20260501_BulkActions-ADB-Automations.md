# Bulk Actions — ADB Automation

> Created: 2026-05-01
> Branch: `feat/bulk-actions-add-intent-for-adb-automation`

---

## 1. Problem

The Bulk Actions screen loads configs via a **system file picker** (`ActivityResultContracts.GetContent()`). This is a separate system UI process that `adb shell input tap`, UI Automator, and Compose testing **cannot interact with**. The user must manually select a file.

## 2. Solution

Add **intent extras** so the app can be launched with a config URI and auto-run flag — completely bypassing the file picker.

### Changes made

| File | Change |
|---|---|
| `MainActivity.kt` | Reads `intent.data` (config URI) and `intent.getBooleanExtra("auto_run", false)`. Passes both to `AppRoot()`. |
| `AppRoot()` (composable) | Accepts `configUri: String?` and `autoRun: Boolean` parameters. Passes them to `BulkActionsScreen`. |
| `BulkActionsScreen.kt` | Accepts same parameters. Uses `LaunchedEffect(configUri)` to auto-load config via `viewModel.onFileSelected()`. If `autoRun == true`, triggers `viewModel.onRunClicked()` after a 500ms delay for UI to settle. |

### Intent extras

| Extra | Type | Description |
|---|---|---|
| `intent.data` (URI) | `Uri` | File URI of the JSON config (e.g. `file:///sdcard/Download/blkacts_single_ping_success.json`) |
| `auto_run` | `boolean` | If `true`, auto-executes commands after loading the config |

## 3. Usage

### Direct (one-off)

```bash
# Push config
adb push notes/config-files_bulk-actions/blkacts_single_ping_success.json /sdcard/Download/

# Launch with auto-load + auto-run
adb shell am start \
    -n io.github.mobilutils.ntp_dig_ping_more/.MainActivity \
    -d "file:///sdcard/Download/blkacts_single_ping_success.json" \
    --es auto_run true

# Wait for execution (adjust based on config timeout)
sleep 60

# Pull results
adb pull /sdcard/Download/blkacts_single_ping_success.txt ./test-results/
```

### Automated script

```bash
# Single config, default emulator
./BULKACTIONS-ADB-SCRIPT.sh blkacts_single_ping_success.json

# Specific emulator
./BULKACTIONS-ADB-SCRIPT.sh blkacts_multi_all9_success.json Pixel_6_API_34

# Fully unattended (no interactive prompts, emulator stays running)
./BULKACTIONS-ADB-SCRIPT.sh blkacts_multi_all9_success.json "" --no-interact
```

The script:
1. Starts emulator if not running
2. Pushes config to `/sdcard/Download/`
3. Launches app with intent extras (auto-load + auto-run)
4. Waits for execution (estimates from config `timeout` field using pure grep/sed)
5. Pulls results to `./test-results/`
6. Optionally closes emulator (only if it was launched by the script and `--no-interact` is not set)

**`--no-interact` flag**: Skips the interactive "Close emulator?" prompt at the end. The emulator stays running. Useful for CI pipelines.

## 4. What the script does NOT do

| Task | Why |
|---|---|
| Grant permissions at runtime | Bulk Actions doesn't need dangerous permissions (only `INTERNET`/`ACCESS_NETWORK_STATE` which are auto-granted) |
| Dismiss permission rationale dialogs | Not needed for this feature |
| Handle permission denial | Not applicable |

## 5. Existing manual UI

The existing manual UI (file picker, "Load JSON Config" button, "Run All Commands" button, "Write to File" button) is **completely untouched**. The intent extras are purely additive — the app works exactly as before when launched without them.

## 6. Auto-save behavior

When a config has an `"output-file"` field:
1. The app validates writability before execution (or suggests a fallback)
2. After execution, results are **automatically saved** to the output path
3. A green success card appears showing the save path
4. No need to press "Write to File" — the auto-save happens in `BulkActionsViewModel.onRunClicked()`

If `output-file` is **not** defined in the config, results remain in-memory only and must be captured via "Write to File" (manual).

## 7. Timeout behavior

Timeout precedence (highest to lowest):
1. Per-command `-t N` flag in the command string
2. Config-level `"timeout"` field (seconds)
3. Default 30 seconds

The script estimates wait time as `max(timeout * 2 + 30, 10s)`, capped at 300s.

## 8. Files

| File | Purpose |
|---|---|
| `BULKACTIONS-ADB-SCRIPT.sh` | Automated script for CI/manual use |
| `notes/20260501_BulkActions-ADB-Automations.md` | This document |
| `MainActivity.kt` | Intent extra reading |
| `BulkActionsScreen.kt` | Auto-load + auto-run logic |
| `notes/config-files_bulk-actions/*.json` | 65 test config files (all with `output-file` except edge cases) |
