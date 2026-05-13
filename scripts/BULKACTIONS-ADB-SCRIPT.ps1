# ------------------------------------------------------------------
# BULKACTIONS-ADB-SCRIPT.ps1
#
# Fully automated Bulk Actions execution via ADB — no user interaction.
#
# Prerequisites:
#       - ADB installed and on PATH
#       - Emulator running OR device connected
#       - App already installed (or run ./gradlew installDebug first)
#
# Usage:
#        .\BULKACTIONS-ADB-SCRIPT.ps1 -f <config_filename> [options]
#
# Options:
#        -f  / -FilePath        <config>   Config filename (required)
#        -e  / -EmulatorName    <name>     AVD name to launch (default: Medium_Phone_API_35)
#        -d  / -RealDevice                 Skip emulator entirely; use connected physical device
#        -a  / -NoInteract                 Suppress all prompts (auto-exit after completion)
#        -s  / -ShowEmulator               Launch emulator in visible window mode
#        -h  / -Help                       Show this help message
#
# Examples:
#        .\BULKACTIONS-ADB-SCRIPT.ps1 -f blkacts_single_ping_success.json -NoInteract
#        .\BULKACTIONS-ADB-SCRIPT.ps1 -f blkacts_multi_all9_success.json -EmulatorName Medium_Phone_API_35
#        .\BULKACTIONS-ADB-SCRIPT.ps1 -f blkacts_multi_all9_success.json -RealDevice -NoInteract
#        .\BULKACTIONS-ADB-SCRIPT.ps1 -f blkacts_single_ping_success.json -ShowEmulator
#
# ── Key fixes applied (vs. original script) ──────────────────────────
#
#  FIX 1 (Critical): Use --ez for boolean intent extra, not --es.
#  FIX 2 (Critical): Private dir is .../files/files/, not .../files/.
#  FIX 3 (Critical): adb pull cannot read from app private dir — use run-as cat.
#  FIX 4 (Moderate): Wait time accounts for sequential command count.
#  FIX 5 (Minor):    adb pull ~ expansion was broken.
# ------------------------------------------------------------------

[CmdletBinding()]
param(
    [Alias("f")]
    [string]$FilePath,

    [Alias("e")]
    [string]$EmulatorName = "Medium_Phone_API_35",

    [Alias("d")]
    [switch]$RealDevice,

    [Alias("a")]
    [switch]$NoInteract,

    [Alias("s")]
    [switch]$ShowEmulator,

    [Alias("h")]
    [switch]$Help
)

# -- Help -------------------------------------------------------------
function Show-Help {
    Write-Host @"
Usage: .\BULKACTIONS-ADB-SCRIPT.ps1 -f <config_filename> [options]

Options:
   -f  / -FilePath       <config>   Config filename (required)
   -e  / -EmulatorName   <name>     AVD name to launch (default: Medium_Phone_API_35)
   -d  / -RealDevice                Skip emulator; use connected physical device
   -a  / -NoInteract                Suppress all prompts
   -s  / -ShowEmulator              Launch emulator in visible window mode
   -h  / -Help                      Show this help message

Examples:
   .\BULKACTIONS-ADB-SCRIPT.ps1 -f blkacts_single_ping_success.json -NoInteract
   .\BULKACTIONS-ADB-SCRIPT.ps1 -f blkacts_multi_all9_success.json -EmulatorName Medium_Phone_API_35
   .\BULKACTIONS-ADB-SCRIPT.ps1 -f blkacts_multi_all9_success.json -RealDevice -NoInteract
"@
}

if ($Help) {
    Show-Help
    exit 0
}

if (-not $FilePath) {
    Write-Error "ERROR: -FilePath (-f) is required."
    Show-Help
    exit 1
}

# -- Stop on first error (equivalent to set -e) ----------------------
$ErrorActionPreference = "Stop"

# -- Helpers ----------------------------------------------------------

# Extract a JSON string field value without needing python/jq.
# Usage: Get-JsonStringField <filePath> <fieldName>
function Get-JsonStringField {
    param([string]$File, [string]$Field)
    $content = Get-Content $File -Raw
    if ($content -match """$Field""\s*:\s*""([^""]*)""") {
        return $Matches[1]
    }
    return ""
}

# Extract a JSON numeric field value.
# Usage: Get-JsonNumField <filePath> <fieldName>
function Get-JsonNumField {
    param([string]$File, [string]$Field)
    $content = Get-Content $File -Raw
    if ($content -match """$Field""\s*:\s*(-?\d+)") {
        return $Matches[1]
    }
    return ""
}

# -- Resolve config path ---------------------------------------------
# Expand ~ to $HOME if present
$CONFIG = $FilePath -replace "^~", $HOME
$CONFIG = [System.IO.Path]::GetFullPath($CONFIG)

$CONFIG_FILENAME = [System.IO.Path]::GetFileName($CONFIG)

$APP_ID        = "io.github.mobilutils.ntp_dig_ping_more"

# FIX 2: PRIVATE_DIR is the shell-accessible path for file contents.
$FILES_DIR     = "/data/user/0/$APP_ID/files"
$PRIVATE_DIR   = $FILES_DIR

$PUSH_PATH     = "$PRIVATE_DIR/$CONFIG_FILENAME"
$RESULTS_DIR   = ".\test-results"
$CONFIG_SOURCE = $CONFIG

# -- Validate config file exists -------------------------------------
if (-not (Test-Path $CONFIG_SOURCE)) {
    Write-Error "ERROR: Config file not found: $CONFIG_SOURCE"
    exit 1
}

Write-Host "================================================================"
Write-Host "  Bulk Actions ADB Automation"
Write-Host "================================================================"
Write-Host "  Config:         $CONFIG"
if ($RealDevice) {
    Write-Host "  Mode:           Real Device"
} else {
    Write-Host "  Emulator:       $EmulatorName"
}
Write-Host "  Push path:      $PUSH_PATH"
Write-Host "  Results dir:    $RESULTS_DIR"
Write-Host "================================================================"

# -- 1. Ensure emulator is running -----------------------------------
$EMULATOR_LAUNCHED = $false

if ($RealDevice) {
    Write-Host ""
    Write-Host "[1/8] Real device mode — skipping emulator."
} else {
    $adbDevices = adb devices 2>&1
    $deviceReady = $adbDevices | Select-String "`tdevice"

    if (-not $deviceReady) {
        Write-Host ""
        Write-Host "[1/8] Starting emulator: $EmulatorName"

        if ($ShowEmulator) {
            Start-Process "emulator" -ArgumentList "-avd", $EmulatorName, "-no-audio", "-no-boot-anim" -NoNewWindow
            Write-Host "  Emulator window will be visible."
        } else {
            Start-Process "emulator" -ArgumentList "-avd", $EmulatorName, "-no-skin", "-no-audio", "-no-boot-anim" -NoNewWindow
        }
        $EMULATOR_LAUNCHED = $true

        Write-Host "  Waiting for device..."
        adb wait-for-device

        Write-Host "  Booting... (waiting up to 120s)"
        for ($i = 1; $i -le 120; $i++) {
            Start-Sleep -Seconds 1
            $bootProp = adb shell getprop sys.boot_completed 2>$null
            if ($bootProp -match "1") {
                Write-Host "  Boot complete after ${i}s."
                break
            }
        }
    } else {
        Write-Host ""
        Write-Host "[1/8] Device/emulator already running"
    }
}

# -- 2. Push config file to app's private directory ------------------
Write-Host ""
Write-Host "[2/8] Pushing config file..."

# FIX 2 + 3: ADB cannot write directly to app's private dir (shell user has no permission).
# Pipe config file content into adb shell run-as, which writes as the app's UID.
$configContent = Get-Content $CONFIG_SOURCE -Raw
$configContent | adb shell "run-as $APP_ID sh -c 'cat > $PUSH_PATH'"

# -- 3. Verify file was written --------------------------------------
Write-Host ""
Write-Host "[3/8] Verifying config file..."

$verifyResult = adb shell "run-as $APP_ID test -f $PUSH_PATH && echo exists || echo missing" 2>$null
if ($verifyResult -notmatch "exists") {
    Write-Error "  ERROR: Config file not found in private dir after push.`n  Try: adb shell run-as $APP_ID ls -la files/files/"
    exit 1
}
Write-Host "  Config file verified in private directory."

# -- 4. Launch app with intent extras --------------------------------
Write-Host ""
Write-Host "[4/8] Launching app (auto-load + auto-run)..."

$FILE_URI = "file://$PUSH_PATH"

# FIX 1: Use --ez (boolean extra) NOT --es (string extra).
adb shell am force-stop $APP_ID 2>$null
Start-Sleep -Milliseconds 500
adb shell am start `
    -n "$APP_ID/.MainActivity" `
    -d "$FILE_URI" `
    --ez auto_run true

Write-Host "  intent.data   = $FILE_URI"
Write-Host "  auto_run      = true (boolean via --ez)"

# -- 5. Wait for execution to complete -------------------------------
Write-Host ""
Write-Host "[5/8] Waiting for bulk execution..."

$RUNNING_FILE  = ".running-tasks"
$POLL_INTERVAL = 1
$MAX_WAIT      = 600
$WAITED        = 0

# Wait for .running-tasks marker to appear (app has started execution)
while ($WAITED -lt $MAX_WAIT) {
    $exists = adb shell "run-as $APP_ID test -f $PRIVATE_DIR/$RUNNING_FILE && echo yes || echo no" 2>$null |
              Select-Object -Last 1
    if ($exists -ne "no") {
        Write-Host "  Waiting for marker .running-tasks file creation ${WAITED}s."
        break
    }
    Start-Sleep -Seconds $POLL_INTERVAL
    $WAITED += $POLL_INTERVAL
}

# Now poll until .running-tasks disappears (execution finished)
$POLL_INTERVAL = 2
$MAX_WAIT      = 600
$WAITED        = 0

Write-Host "  Polling for .running-tasks marker file (max ${MAX_WAIT}s, ${POLL_INTERVAL}s interval)..."

while ($WAITED -lt $MAX_WAIT) {
    $exists = adb shell "run-as $APP_ID test -f $PRIVATE_DIR/$RUNNING_FILE && echo yes || echo no" 2>$null |
              Select-Object -Last 1
    if ($exists -ne "yes") {
        Write-Host "  Execution finished after ${WAITED}s."
        break
    }
    Start-Sleep -Seconds $POLL_INTERVAL
    $WAITED += $POLL_INTERVAL
}

if ($WAITED -ge $MAX_WAIT) {
    Write-Warning "Timed out after ${MAX_WAIT}s — .running-tasks still present."
    Write-Warning "The app may have crashed. Results might be incomplete or missing."
}

# -- 6. Pull results -------------------------------------------------
Write-Host ""
Write-Host "[6/8] Pulling results..."
New-Item -ItemType Directory -Path $RESULTS_DIR -Force | Out-Null

# FIX 5: Expand ~ in output-file to app's private filesDir.
$OUTPUT_FILE = Get-JsonStringField -File $CONFIG_SOURCE -Field "output-file"

if (-not $OUTPUT_FILE) {
    Write-Error 'ERROR: field "output-file" not found. Running via adb requires JSON "output-file". Set it and run the script again.'
    exit 1
}

if ($OUTPUT_FILE -match "^/sdcard") {
    Write-Error "ERROR: field ""output-file"" value ""$OUTPUT_FILE"" starts with /sdcard. App will probably fail to write there. Remove /sdcard from ""output-file"" and run again."
    exit 1
}

$DEVICE_OUTPUT_FILE = ""
$LOCAL_OUTPUT       = ""

if ($OUTPUT_FILE) {
    # FIX 5: Expand ~ to FILES_DIR (mirrors BulkConfigParser.expandTilde())
    if ($OUTPUT_FILE -match "^~/") {
        $pathAfterTilde     = $OUTPUT_FILE.Substring(1)   # e.g. /files/blkacts_single_ping_success.txt
        $DEVICE_OUTPUT_FILE = "${FILES_DIR}${pathAfterTilde}"
    } else {
        $DEVICE_OUTPUT_FILE = $OUTPUT_FILE
    }

    $TIMESTAMP    = Get-Date -Format "yyyyMMdd-HH:mm:ss"
    $baseName     = [System.IO.Path]::GetFileName($DEVICE_OUTPUT_FILE)
    $LOCAL_OUTPUT = Join-Path $RESULTS_DIR "${TIMESTAMP}_${baseName}"

    # FIX 3: adb pull cannot read private dir — use run-as cat redirect.
    Write-Host "  Pulling: $DEVICE_OUTPUT_FILE"
    try {
        $fileContent = adb shell "run-as $APP_ID cat $DEVICE_OUTPUT_FILE" 2>$null
        if ($fileContent) {
            $fileContent | Set-Content -Path $LOCAL_OUTPUT -Encoding UTF8
        }
    } catch {
        Write-Warning "Could not pull $DEVICE_OUTPUT_FILE (may not exist if auto-save failed)"
        $LOCAL_OUTPUT = ""
    }

    if ($LOCAL_OUTPUT -and (Test-Path $LOCAL_OUTPUT) -and (Get-Item $LOCAL_OUTPUT).Length -gt 0) {
        Write-Host "        -> $LOCAL_OUTPUT"
    } else {
        Write-Warning "Output file is empty or missing. Did the commands complete in time?"
        Write-Host   "  Hint: Increase wait time or check app logs with: adb logcat | Select-String ntp_dig"
        $LOCAL_OUTPUT = ""
    }
} else {
    Write-Host "  No output-file defined in config — results are in-memory only"
}

# -- 7. Clean up remote android app folder ---------------------------
Write-Host ""
Write-Host "[7/8] Cleaning remote android app folder..."
Write-Host "Will clean $PRIVATE_DIR"

$pushExists = adb shell "run-as $APP_ID test -f '$PUSH_PATH' && echo yes || echo no" 2>$null | Select-Object -Last 1
if ($pushExists -eq "yes") {
    Write-Host "  Removing $PUSH_PATH"
    adb shell "run-as $APP_ID rm '$PUSH_PATH'"
} else {
    Write-Host "File $PUSH_PATH doesn't exist. FAILED."
}

if ($DEVICE_OUTPUT_FILE) {
    $outExists = adb shell "run-as $APP_ID test -f '$DEVICE_OUTPUT_FILE' && echo yes || echo no" 2>$null | Select-Object -Last 1
    if ($outExists -eq "yes") {
        Write-Host "  Removing $DEVICE_OUTPUT_FILE"
        adb shell "run-as $APP_ID rm $DEVICE_OUTPUT_FILE"
    } else {
        Write-Host "File $DEVICE_OUTPUT_FILE doesn't exist. FAILED."
    }
}

# -- 8. Report -------------------------------------------------------
Write-Host ""
Write-Host "[8/8] Done."
Write-Host ""
Write-Host "================================================================"
Write-Host "  Automation complete."

if ($LOCAL_OUTPUT -and (Test-Path $LOCAL_OUTPUT) -and (Get-Item $LOCAL_OUTPUT).Length -gt 0) {
    $lineCount = (Get-Content $LOCAL_OUTPUT).Count
    $byteCount = (Get-Item $LOCAL_OUTPUT).Length
    Write-Host "  Results: $LOCAL_OUTPUT"
    Write-Host "  Lines:   $lineCount"
    Write-Host "  Size:    $byteCount bytes"
} else {
    Write-Host "  No results file found."
}
Write-Host "================================================================"

# Optionally keep emulator running or close it
if (-not $NoInteract -and $EMULATOR_LAUNCHED -and -not $RealDevice) {
    Write-Host ""
    $reply = Read-Host "Close emulator? (y/N)"
    if ($reply -match "^[Yy]$") {
        Write-Host "Closing emulator..."
        adb emu kill
    }
}
