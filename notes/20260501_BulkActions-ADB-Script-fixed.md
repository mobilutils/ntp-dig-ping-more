# Bulk Actions — ADB Script: Root Causes & Fixes

> Date: 2026-05-01  
> Status: **WORKING** (confirmed end-to-end on emulator API 35)  
> Script: `BULKACTIONS-ADB-SCRIPT.sh`

---

## Summary

The original script had 5 bugs preventing automation from working. After applying all 5 fixes the script runs fully end-to-end: push → launch → auto-run → auto-save → pull.

---

## FIX 1 (Critical): `--es` vs `--ez` for boolean intent extras

**Root cause:** `adb shell am start --es auto_run true` passes `auto_run` as a **String** extra.  
`getBooleanExtra("auto_run", false)` cannot read String extras — it silently returns the default `false`.  
Android logs a warning: `Key auto_run expected Boolean but value was a java.lang.String. The default value false was returned.`

This caused the app to load the config but never trigger `onLoadAndRun()`. The config file was opened and displayed, but commands were never executed.

**Fix:**
```bash
# Wrong (passes String "true"):
adb shell am start --es auto_run true

# Correct (passes boolean true):
adb shell am start --ez auto_run true
```

**ADB extra type flags reference:**

| Flag | Type | Example |
|------|------|---------|
| `--es` | String | `--es key "value"` |
| `--ez` | Boolean | `--ez key true` |
| `--ei` | Integer | `--ei key 42` |
| `--el` | Long | `--el key 1000` |

---

## FIX 2 (Critical): Private dir is `.../files/files/`, not `.../files/`

**Root cause:** On this emulator (API 35), the directory structure is:
```
/data/user/0/<pkg>/files/          ← writable by run-as (drwxrwx--x)
/data/user/0/<pkg>/files/files/    ← actual filesDir contents (drwx------)
```

`Context.filesDir.absolutePath` in Kotlin returns `/data/user/0/<pkg>/files`.  
But the shell path for reading/writing *into* that directory is `/data/user/0/<pkg>/files/files/`.

This means:
- Push target: `PRIVATE_DIR = /data/user/0/<pkg>/files/files`
- Tilde expansion base: `FILES_DIR = /data/user/0/<pkg>/files`

**Fix:**
```bash
# Two variables to distinguish the two levels:
FILES_DIR="/data/user/0/$APP_ID/files"       # Kotlin's filesDir (tilde base)
PRIVATE_DIR="$FILES_DIR/files"               # Actual shell-accessible directory
PUSH_PATH="$PRIVATE_DIR/$CONFIG"             # Push/pull file target
```

---

## FIX 3 (Critical): `adb pull` cannot read from app private dir

**Root cause:** `adb pull /data/user/0/<pkg>/files/...` fails with `Permission denied` because the `adb` process runs as the `shell` user, which has no access to another app's private directory (`drwx------` owned by `u0_a220`).

**Fix (push):** Pipe host file content via `run-as`:
```bash
# Push: cat on host → adb shell run-as → write on device
cat "$CONFIG_SOURCE" | adb shell "run-as $APP_ID sh -c 'cat > $PUSH_PATH'"
```

**Fix (pull):** `run-as cat` on device → redirect on host:
```bash
# Pull: run-as cat on device → capture to local file on host
adb shell "run-as $APP_ID cat $DEVICE_OUTPUT_FILE" > "$LOCAL_OUTPUT" 2>/dev/null
```

> **Note:** `run-as` cannot write to `/sdcard/` either (different mount namespace). The host-pipe approach avoids `/sdcard/` entirely.

---

## FIX 4 (Moderate): Wait time formula was too short for multi-command configs

**Root cause:** Original formula: `timeout * 2 + 30`. With 9 commands × 30s each = 270s needed, but script only waited 90s.

**Fix:** Count commands × timeout:
```bash
COMMAND_COUNT=$(grep -c '"[a-zA-Z0-9_-]*"[[:space:]]*:' "$CONFIG_SOURCE" || echo 1)
COMMAND_COUNT=$(( COMMAND_COUNT > 2 ? COMMAND_COUNT - 2 : 1 ))  # subtract top-level keys
ESTIMATED_WAIT=$(( CONFIG_TIMEOUT_SEC * COMMAND_COUNT + 30 ))
```

---

## FIX 5 (Minor): Tilde expansion was incorrect for pull

**Root cause:** The output-file path `~/files/foo.txt` was not correctly expanded to the device path.

The Kotlin code expands `~` using:
```kotlin
val privateDir = appContext?.applicationContext?.filesDir?.absolutePath  // = FILES_DIR
return "$privateDir${path.substring(1)}"
// "~/files/foo.txt" → FILES_DIR + "/files/foo.txt" = PRIVATE_DIR/foo.txt
```

So `~/files/blkacts_single_ping_success.txt` expands to `$FILES_DIR/files/blkacts_single_ping_success.txt` = `$PRIVATE_DIR/blkacts_single_ping_success.txt`.

**Fix:**
```bash
if echo "$OUTPUT_FILE" | grep -q '^~/'; then
    path_after_tilde="${OUTPUT_FILE#\~}"         # /files/blkacts_single_ping_success.txt
    DEVICE_OUTPUT_FILE="${FILES_DIR}${path_after_tilde}"  # FILES_DIR + /files/foo.txt = PRIVATE_DIR/foo.txt
else
    DEVICE_OUTPUT_FILE="$OUTPUT_FILE"
fi
```

---

## Verified Working Flow

```
Script: cat config.json | adb shell "run-as <pkg> sh -c 'cat > PRIVATE_DIR/config.json'"
   ↓
Script: adb shell am start -d file://PRIVATE_DIR/config.json --ez auto_run true
   ↓
App: MainActivity.onCreate() reads intent.data + getBooleanExtra("auto_run", false) → true
   ↓
App: BulkActionsScreen LaunchedEffect → viewModel.onLoadAndRun(uri, fileName)
   ↓
App: openInputStream(file://PRIVATE_DIR/config.json) → success (private dir, no permission needed)
   ↓
App: commands execute (ping, dig, ntp, etc.)
   ↓
App: autoSaveResults() → writeDirect(PRIVATE_DIR/output.txt) → success
   ↓
Script: sleep (estimated wait)
   ↓
Script: adb shell "run-as <pkg> cat PRIVATE_DIR/output.txt" > ./test-results/output.txt
   ↓
Host: result file has 43 lines / 3208 bytes ✓
```

---

## Quick Reference: One-Off ADB Commands

```bash
APP_ID="io.github.mobilutils.ntp_dig_ping_more"
PRIVATE_DIR="/data/user/0/$APP_ID/files/files"
FILES_DIR="/data/user/0/$APP_ID/files"

# Push config:
cat notes/config-files_bulk-actions/blkacts_single_ping_success.json \
  | adb shell "run-as $APP_ID sh -c 'cat > $PRIVATE_DIR/blkacts_single_ping_success.json'"

# Launch (IMPORTANT: --ez not --es):
adb shell am force-stop "$APP_ID"
adb shell am start \
    -n "$APP_ID/.MainActivity" \
    -d "file://$PRIVATE_DIR/blkacts_single_ping_success.json" \
    --ez auto_run true

# Wait for execution...
sleep 60

# Pull result:
adb shell "run-as $APP_ID cat $PRIVATE_DIR/blkacts_single_ping_success.txt" \
  > ./test-results/blkacts_single_ping_success.txt
```
