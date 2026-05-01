#!/bin/bash
# ────────────────────────────────────────────────────────────────────
# BULKACTIONS-ADB-SCRIPT.sh
#
# Fully automated Bulk Actions execution via ADB — no user interaction.
#
# Prerequisites:
#    - ADB installed and on PATH
#    - Emulator running OR device connected
#    - App already installed (or run ./gradlew installDebug first)
#
# Usage:
#    ./BULKACTIONS-ADB-SCRIPT.sh [config_filename] [emulator_name] [--no-interact]
#
# Examples:
#    ./BULKACTIONS-ADB-SCRIPT.sh blkacts_single_ping_success.json
#    ./BULKACTIONS-ADB-SCRIPT.sh blkacts_multi_all9_success.json Pixel_6_API_34
#    ./BULKACTIONS-ADB-SCRIPT.sh blkacts_multi_all9_success.json "" --no-interact
# ────────────────────────────────────────────────────────────────────

set -euo pipefail

# ── Defaults ──────────────────────────────────────────────────────
NO_INTERACT=false
for arg in "$@"; do
    case "$arg" in
        --no-interact) NO_INTERACT=true ;;
    esac
done

CONFIG="${1:-blkacts_single_ping_success.json}"
EMULATOR="${2:-Pixel_6_API_34}"
APP_ID="io.github.mobilutils.ntp_dig_ping_more"
PUSH_PATH="/sdcard/Download/$CONFIG"
RESULTS_DIR="./test-results"

# Resolve script directory for finding config files
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONFIG_SOURCE="$SCRIPT_DIR/notes/config-files_bulk-actions/$CONFIG"

# ── Helper: extract JSON string field (pure grep/sed, no python) ──
# Usage: json_str_field <file> <field_name>
json_str_field() {
    local file="$1" field="$2"
    grep -o "\"$field\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" "$file" | \
        sed 's/.*:[[:space:]]*"\(.*\)"/\1/' | head -1
}

# ── Helper: extract JSON numeric field (pure grep/sed, no python) ──
# Usage: json_num_field <file> <field_name>
json_num_field() {
    local file="$1" field="$2"
    grep -o "\"$field\"[[:space:]]*:[[:space:]]*-?[0-9]\+" "$file" | \
        sed 's/.*:[[:space:]]*//' | head -1
}

# ── Validate config file exists ──────────────────────────────────
if [ ! -f "$CONFIG_SOURCE" ]; then
    echo "ERROR: Config file not found: $CONFIG_SOURCE"
    echo "Available configs:"
    ls -1 "$SCRIPT_DIR/notes/config-files_bulk-actions/"*.json 2>/dev/null | \
        sed 's|.*/notes/config-files_bulk-actions/||' | sed 's/.json$//' | \
        sed 's/^/   - /'
    exit 1
fi

echo "═══════════════════════════════════════════════════════════"
echo "  Bulk Actions ADB Automation"
echo "═══════════════════════════════════════════════════════════"
echo "  Config:      $CONFIG"
echo "  Emulator:    $EMULATOR"
echo "  Push path:   $PUSH_PATH"
echo "  Results dir: $RESULTS_DIR"
echo "═══════════════════════════════════════════════════════════"

# ── 1. Ensure emulator is running ────────────────────────────────
if ! adb devices | grep -q $'\tdevice'; then
    echo ""
    echo "[1/6] Starting emulator: $EMULATOR"
    emulator -avd "$EMULATOR" -no-skin -no-audio -no-boot-anim &
    EMULATOR_LAUNCHED=true

    echo "  Waiting for device..."
    adb wait-for-device
    # Wait for boot to complete
    adb shell getprop sys.boot_completed | grep -q "1" || {
        echo "  Booting... (waiting up to 120s)"
        for i in $(seq 1 120); do
            sleep 1
            if adb shell getprop sys.boot_completed | grep -q "1"; then
                break
            fi
        done
    }
else
    EMULATOR_LAUNCHED=false
    echo ""
    echo "[1/6] Device/emulator already running"
fi

# ── 2. Push config file ─────────────────────────────────────────
echo ""
echo "[2/6] Pushing config file..."
adb push "$CONFIG_SOURCE" "$PUSH_PATH"
echo "   → $PUSH_PATH"

# ── 3. Launch app with intent extras ─────────────────────────────
echo ""
echo "[3/6] Launching app (auto-load + auto-run)..."
FILE_URI="file://$PUSH_PATH"
adb shell am start \
     -n "$APP_ID/.MainActivity" \
     -d "$FILE_URI" \
     --es auto_run true

echo "  intent.data      = $FILE_URI"
echo "  auto_run         = true"

# ── 4. Wait for execution to complete ────────────────────────────
echo ""
echo "[4/6] Waiting for bulk execution..."

# Extract timeout from config using pure grep/sed
CONFIG_TIMEOUT_SEC=$(json_num_field "$CONFIG_SOURCE" "timeout")
CONFIG_TIMEOUT_SEC="${CONFIG_TIMEOUT_SEC:-30}"  # default 30s if not found

# Estimate: config timeout * 2 (commands run sequentially) + 30s overhead
ESTIMATED_WAIT=$(( CONFIG_TIMEOUT_SEC * 2 + 30 ))
if [ "$ESTIMATED_WAIT" -gt 300 ]; then
    ESTIMATED_WAIT=300   # cap at 5 minutes
fi

echo "  Config timeout: ${CONFIG_TIMEOUT_SEC}s"
echo "  Estimated wait: ${ESTIMATED_WAIT}s"
sleep "$ESTIMATED_WAIT"

# ── 5. Pull results ─────────────────────────────────────────────
echo ""
echo "[5/6] Pulling results..."
mkdir -p "$RESULTS_DIR"

# Extract output-file from config using pure grep/sed
OUTPUT_FILE=$(json_str_field "$CONFIG_SOURCE" "output-file")

if [ -n "$OUTPUT_FILE" ]; then
    # Expand ~ to /sdcard
    LOCAL_OUTPUT="$RESULTS_DIR/$(basename "${OUTPUT_FILE#\~/}")"
    adb pull "$OUTPUT_FILE" "$LOCAL_OUTPUT" 2>/dev/null || {
        echo "  WARNING: Could not pull $OUTPUT_FILE (may not exist if auto-save failed)"
    }
    echo "   → $LOCAL_OUTPUT"
else
    echo "  No output-file defined in config — results are in-memory only"
fi

# ── 6. Report ────────────────────────────────────────────────────
echo ""
echo "[6/6] Done."
echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  Automation complete."
if [ -n "$OUTPUT_FILE" ] && [ -f "$LOCAL_OUTPUT" ]; then
    echo "  Results: $LOCAL_OUTPUT"
    echo "  Lines:    $(wc -l < "$LOCAL_OUTPUT")"
else
    echo "  No results file found."
fi
echo "═══════════════════════════════════════════════════════════"

# Optionally keep emulator running or close it
if [ "$NO_INTERACT" = false ] && [ "$EMULATOR_LAUNCHED" = true ]; then
    echo ""
    read -p "Close emulator? (y/N): " -r
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo "Closing emulator..."
        adb emu kill
    fi
fi
