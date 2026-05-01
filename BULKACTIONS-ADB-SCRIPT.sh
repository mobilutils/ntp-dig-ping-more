#!/bin/bash
# ------------------------------------------------------------------
# BULKACTIONS-ADB-SCRIPT.sh
#
# Fully automated Bulk Actions execution via ADB — no user interaction.
#
# Prerequisites:
#      - ADB installed and on PATH
#      - Emulator running OR device connected
#      - App already installed (or run ./gradlew installDebug first)
#
# Usage:
#       ./BULKACTIONS-ADB-SCRIPT.sh [config_filename] [emulator_name] [--no-interact] [--show-emulator]
#
# Examples:
#      ./BULKACTIONS-ADB-SCRIPT.sh blkacts_single_ping_success.json
#      ./BULKACTIONS-ADB-SCRIPT.sh blkacts_multi_all9_success.json Medium_Phone_API_35
#      ./BULKACTIONS-ADB-SCRIPT.sh blkacts_multi_all9_success.json "" --no-interact
#      ./BULKACTIONS-ADB-SCRIPT.sh blkacts_single_ping_success.json "" --show-emulator
#
# ── Key fixes applied (vs. original script) ──────────────────────────
#
#  FIX 1 (Critical): Use --ez for boolean intent extra, not --es.
#      `adb shell am start --es auto_run true` passes a String, and
#      `getBooleanExtra("auto_run", false)` silently returns false.
#      → Use `--ez auto_run true` to pass a real boolean.
#
#  FIX 2 (Critical): Private dir is .../files/files/, not .../files/.
#      Context.filesDir resolves to /data/user/0/<pkg>/files on disk,
#      but the shell path for the *contents* is /data/user/0/<pkg>/files/files/
#      because the directory listing shows `files → files/files/`.
#      → PRIVATE_DIR is now /data/user/0/$APP_ID/files/files
#
#  FIX 3 (Critical): adb pull cannot read from app private dir.
#      `adb pull /data/user/0/<pkg>/files/...` fails with "Permission denied"
#      because adb runs as the shell user, not the app UID.
#      → Use `adb shell run-as <pkg> cat <file>` and redirect to host.
#
#  FIX 4 (Moderate): Wait time accounts for sequential command count.
#      Old: timeout * 2 + 30. New: timeout * command_count + 30.
#
#  FIX 5 (Minor): adb pull ~ expansion was broken.
#      ~ in output-file is expanded to PRIVATE_DIR (app's filesDir).
# ------------------------------------------------------------------

set -euo pipefail

# -- Parse flags and positional args ---------------------------------
# Filter out flags so positional args are extracted correctly regardless of order.
FILTERED_ARGS=()
NO_INTERACT=false
SHOW_EMULATOR=false
for arg in "$@"; do
    case "$arg" in
         --no-interact)   NO_INTERACT=true ;;
         --show-emulator) SHOW_EMULATOR=true ;;
         *) FILTERED_ARGS+=("$arg") ;;
    esac
done

# -- Defaults --------------------------------------------------------
CONFIG="${FILTERED_ARGS[0]:-blkacts_single_ping_success.json}"
EMULATOR="${FILTERED_ARGS[1]:-Medium_Phone_API_35}"

APP_ID="io.github.mobilutils.ntp_dig_ping_more"

# FIX 2: The actual writable filesDir on device is .../files/files/
# Context.filesDir returns /data/user/0/<pkg>/files on the Kotlin side,
# but the shell-accessible directory for *contents* is /data/user/0/<pkg>/files/files/
# FILES_DIR = what Kotlin's filesDir.absolutePath returns (the tilde-expansion base)
# PRIVATE_DIR = where files are actually stored on the shell (push/pull target)
FILES_DIR="/data/user/0/$APP_ID/files"
PRIVATE_DIR="$FILES_DIR/files"

PUSH_PATH="$PRIVATE_DIR/$CONFIG"
RESULTS_DIR="./test-results"

# Resolve script directory for finding config files
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONFIG_SOURCE="$SCRIPT_DIR/notes/config-files_bulk-actions/$CONFIG"

# -- Helper: extract JSON string field (pure grep/sed, no python) ---
# Usage: json_str_field <file> <field_name>
json_str_field() {
    local file="$1" field="$2"
    local result
    result=$(grep -o "\"$field\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" "$file" 2>/dev/null | \
        sed 's/.*:[[:space:]]*"\(.*\)"/\1/' | head -1) || true
    echo "${result:-}"
}

# -- Helper: extract JSON numeric field (pure grep/sed, no python) ---
# Usage: json_num_field <file> <field_name>
json_num_field() {
    local file="$1" field="$2"
    local result
    result=$(grep -o "\"$field\"[[:space:]]*:[[:space:]]*-*[0-9]*" "$file" 2>/dev/null | \
        sed 's/.*:[[:space:]]*//' | head -1) || true
    echo "${result:-}"
}

# -- Validate config file exists ------------------------------------
if [ ! -f "$CONFIG_SOURCE" ]; then
    echo "ERROR: Config file not found: $CONFIG_SOURCE"
    echo "Available configs:"
    ls -1 "$SCRIPT_DIR/notes/config-files_bulk-actions/"*.json 2>/dev/null | \
        sed 's|.*/notes/config-files_bulk-actions/||' | sed 's/.json$//' | \
        sed 's/^/    - /'
    exit 1
fi

echo "================================================================"
echo "  Bulk Actions ADB Automation"
echo "================================================================"
echo "  Config:        $CONFIG"
echo "  Emulator:      $EMULATOR"
echo "  Push path:     $PUSH_PATH"
echo "  Results dir:   $RESULTS_DIR"
echo "================================================================"

# -- 1. Ensure emulator is running ----------------------------------
if ! adb devices | grep -q $'\tdevice'; then
    echo ""
    echo "[1/7] Starting emulator: $EMULATOR"
    if [ "$SHOW_EMULATOR" = true ]; then
        emulator -avd "$EMULATOR" -no-audio -no-boot-anim &
        echo "  Emulator window will be visible."
    else
        emulator -avd "$EMULATOR" -no-skin -no-audio -no-boot-anim &
    fi
    EMULATOR_LAUNCHED=true

    echo "  Waiting for device..."
    adb wait-for-device
    # Wait for boot to complete
    echo "  Booting... (waiting up to 120s)"
    for i in $(seq 1 120); do
        sleep 1
        if adb shell getprop sys.boot_completed 2>/dev/null | grep -q "1"; then
            echo "  Boot complete after ${i}s."
            break
        fi
    done
else
    EMULATOR_LAUNCHED=false
    echo ""
    echo "[1/7] Device/emulator already running"
fi

# -- 2. Push config file to app's private directory -----------------
echo ""
echo "[2/7] Pushing config file..."
# FIX 2 + 3: ADB cannot write directly to app's private dir (shell user has no permission).
# Host-side cat pipes file content into a single adb shell that runs as the app's UID via run-as.
# This avoids both the shell-user permission issue (push) and the /sdcard/ issue (run-as can't
# read /sdcard because it runs in the app's mount namespace).
cat "$CONFIG_SOURCE" | adb shell "run-as $APP_ID sh -c 'cat > $PUSH_PATH'"

# -- 3. Verify file was written -------------------------------------
echo ""
echo "[3/7] Verifying config file..."
adb shell "run-as $APP_ID test -f $PUSH_PATH" || {
    echo "  ERROR: Config file not found in private dir after push."
    echo "  Try: adb shell run-as $APP_ID ls -la files/files/"
    exit 1
}
echo "  Config file verified in private directory."

# -- 4. Launch app with intent extras -------------------------------
echo ""
echo "[4/7] Launching app (auto-load + auto-run)..."
FILE_URI="file://$PUSH_PATH"

# FIX 1: Use --ez (boolean extra) NOT --es (string extra).
# getBooleanExtra("auto_run", false) silently returns false when the value
# is a String (passed via --es). --ez passes a genuine boolean.
adb shell am force-stop "$APP_ID" 2>/dev/null || true
sleep 0.5
adb shell am start \
    -n "$APP_ID/.MainActivity" \
    -d "$FILE_URI" \
    --ez auto_run true

echo "  intent.data  = $FILE_URI"
echo "  auto_run     = true (boolean via --ez)"

# -- 5. Wait for execution to complete -------------------------------
echo ""
echo "[5/7] Waiting for bulk execution..."

# Extract timeout from config using pure grep/sed
CONFIG_TIMEOUT_SEC=$(json_num_field "$CONFIG_SOURCE" "timeout")
CONFIG_TIMEOUT_SEC="${CONFIG_TIMEOUT_SEC:-30}"    # default 30s if not found

# FIX 4: Count commands in the "run" object and multiply sequentially.
# Old formula (timeout * 2 + 30) underestimates for configs with >2 commands.
COMMAND_COUNT=$(grep -c '"[a-zA-Z0-9_-]*"[[:space:]]*:' "$CONFIG_SOURCE" || echo 1)
# Subtract 2 for the top-level keys ("output-file", "timeout") — rough but safe
COMMAND_COUNT=$(( COMMAND_COUNT > 2 ? COMMAND_COUNT - 2 : 1 ))

##
# might imprive with a loop based on :
# adb shell run-as "io.github.mobilutils.ntp_dig_ping_more" ls -lAtr /data/user/0/io.github.mobilutils.ntp_dig_ping_more/files/files/| tail -n 1
##
# Estimate: each command can take up to CONFIG_TIMEOUT_SEC; sequential + 30s overhead
ESTIMATED_WAIT=$(( CONFIG_TIMEOUT_SEC * COMMAND_COUNT + 30 ))
if [ "$ESTIMATED_WAIT" -gt 300 ]; then
    ESTIMATED_WAIT=300      # cap at 5 minutes
fi
if [ "$ESTIMATED_WAIT" -lt 10 ]; then
    ESTIMATED_WAIT=10       # minimum 10s
fi

echo "  Config timeout:  ${CONFIG_TIMEOUT_SEC}s per command"
echo "  Command count:   ${COMMAND_COUNT}"
echo "  Estimated wait:  ${ESTIMATED_WAIT}s"
sleep "$ESTIMATED_WAIT"

# -- 6. Pull results -------------------------------------------------
echo ""
echo "[6/7] Pulling results..."
mkdir -p "$RESULTS_DIR"

# FIX 5: Expand ~ in output-file to app's private dir (same as BulkConfigParser.expandTilde).
OUTPUT_FILE=$(json_str_field "$CONFIG_SOURCE" "output-file")

if [ -n "$OUTPUT_FILE" ]; then
    # FIX 5: Expand ~ in output-file to the app's private filesDir.
    # BulkConfigParser.expandTilde() uses: filesDir.absolutePath + path.substring(1)
    # where filesDir.absolutePath = FILES_DIR = /data/user/0/<pkg>/files
    # Example: "~/files/foo.txt" → FILES_DIR + "/files/foo.txt" = PRIVATE_DIR/foo.txt
    #          "~/foo.txt"       → FILES_DIR + "/foo.txt" = FILES_DIR/foo.txt
    #          "/abs/path"       → "/abs/path" (no change)
    if echo "$OUTPUT_FILE" | grep -q '^~/'; then
        path_after_tilde="${OUTPUT_FILE#\~}"         # e.g. /files/blkacts_single_ping_success.txt
        DEVICE_OUTPUT_FILE="${FILES_DIR}${path_after_tilde}"
    else
        DEVICE_OUTPUT_FILE="$OUTPUT_FILE"
    fi

    LOCAL_OUTPUT="$RESULTS_DIR/$(basename "$DEVICE_OUTPUT_FILE")"

    # FIX 3: adb pull cannot read from private dir (shell user denied).
    # Use run-as to cat the file to stdout, capture on host with shell redirect.
    echo "  Pulling: $DEVICE_OUTPUT_FILE"
    adb shell "run-as $APP_ID cat $DEVICE_OUTPUT_FILE" > "$LOCAL_OUTPUT" 2>/dev/null || {
        echo "  WARNING: Could not pull $DEVICE_OUTPUT_FILE (may not exist if auto-save failed)"
        LOCAL_OUTPUT=""
    }

    if [ -n "$LOCAL_OUTPUT" ] && [ -s "$LOCAL_OUTPUT" ]; then
        echo "       -> $LOCAL_OUTPUT"
    else
        echo "  WARNING: Output file is empty or missing. Did the commands complete in time?"
        echo "  Hint: Increase wait time or check app logs with: adb logcat | grep ntp_dig"
        LOCAL_OUTPUT=""
    fi
else
    echo "  No output-file defined in config — results are in-memory only"
    LOCAL_OUTPUT=""
fi

# -- 7. Report -------------------------------------------------------
echo ""
echo "[7/7] Done."
echo ""
echo "================================================================"
echo "  Automation complete."
if [ -n "${LOCAL_OUTPUT:-}" ] && [ -f "$LOCAL_OUTPUT" ] && [ -s "$LOCAL_OUTPUT" ]; then
    echo "  Results: $LOCAL_OUTPUT"
    echo "  Lines:   $(wc -l < "$LOCAL_OUTPUT")"
    echo "  Size:    $(wc -c < "$LOCAL_OUTPUT") bytes"
else
    echo "  No results file found."
fi
echo "================================================================"

# Optionally keep emulator running or close it
if [ "$NO_INTERACT" = false ] && [ "$EMULATOR_LAUNCHED" = true ]; then
    echo ""
    read -p "Close emulator? (y/N): " -r
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo "Closing emulator..."
        adb emu kill
    fi
fi
