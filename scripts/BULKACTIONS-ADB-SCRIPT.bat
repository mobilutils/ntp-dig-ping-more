@echo off
setlocal EnableDelayedExpansion
REM ------------------------------------------------------------------
REM BULKACTIONS-ADB-SCRIPT-NEW.bat
REM
REM Fully automated Bulk Actions execution via ADB — no user interaction.
REM Windows (CMD) port of BULKACTIONS-ADB-SCRIPT.sh
REM
REM Prerequisites:
REM    - ADB installed and on PATH
REM    - Emulator running OR device connected
REM    - App already installed (or run gradlew.bat installDebug first)
REM
REM Usage:
REM    BULKACTIONS-ADB-SCRIPT-NEW.bat -f <config_filepath> [options]
REM
REM Options:
REM    -f, --filepath <config>          Config filepath (required, supports ~ for %USERPROFILE%)
REM    -e, --emulator-name <name>       AVD name to launch (default: Medium_Phone_API_35)
REM    -d, --real-device                Skip emulator; use connected physical device
REM    -a, --no-interact                Suppress all prompts (auto-exit after completion)
REM    -s, --show-emulator              Launch emulator in visible window mode
REM    -h, --help                         Show this help message
REM
REM Examples:
REM    BULKACTIONS-ADB-SCRIPT-NEW.bat -f blkacts_single_ping_success.json --no-interact
REM    BULKACTIONS-ADB-SCRIPT-NEW.bat -f blkacts_multi_all9_success.json -e Medium_Phone_API_35
REM    BULKACTIONS-ADB-SCRIPT-NEW.bat -f blkacts_multi_all9_success.json --real-device --no-interact
REM    BULKACTIONS-ADB-SCRIPT-NEW.bat -f blkacts_single_ping_success.json --show-emulator
REM
REM ── Key fixes applied (vs. original script) ──────────────────────────
REM
REM  FIX 1 (Critical): Use --ez for boolean intent extra, not --es.
REM    `adb shell am start --es auto_run true` passes a String, and
REM    `getBooleanExtra("auto_run", false)` silently returns false.
REM    → Use `--ez auto_run true` to pass a real boolean.
REM
REM  FIX 2 (Critical): Private dir is .../files/files/, not .../files/.
REM    Context.filesDir resolves to /data/user/0&lt;pkg&gt;/files on disk,
REM    but the shell path for the *contents* is /data/user/0&lt;pkg&gt;/files/files/
REM    because the directory listing shows `files → files/files/`.
REM    → PRIVATE_DIR is now /data/user/0/%APP_ID%/files/files
REM
REM  FIX 3 (Critical): adb pull cannot read from app private dir.
REM    `adb pull /data/user/0&lt;pkg&gt;/files/...` fails with "Permission denied"
REM    because adb runs as the shell user, not the app UID.
REM    → Use `adb shell run-as &lt;pkg&gt; cat &lt;file&gt;` and redirect to host.
REM
REM  FIX 4 (Moderate): Wait time accounts for sequential command count.
REM    Old: timeout * 2 + 30. New: timeout * command_count + 30.
REM
REM  FIX 5 (Minor): adb pull ~ expansion was broken.
REM    ~ in output-file is expanded to PRIVATE_DIR (app's filesDir).
REM ------------------------------------------------------------------

REM -- Help -------------------------------------------------------------
:show_help
echo.
echo Usage: BULKACTIONS-ADB-SCRIPT-NEW.bat -f ^&lt;config_filepath^&gt; [options]
echo.
echo Options:
echo     -f, --filepath ^&lt;config^&gt;          Config filepath (required)
echo     -e, --emulator-name ^&lt;name^&gt;       AVD name to launch (default: Medium_Phone_API_35)
echo     -d, --real-device                Skip emulator; use connected physical device
echo     -a, --no-interact                Suppress all prompts
echo     -s, --show-emulator              Launch emulator in visible window mode
echo     -h, --help                         Show this help message
echo.
echo Examples:
echo     BULKACTIONS-ADB-SCRIPT-NEW.bat -f blkacts_single_ping_success.json --no-interact
echo     BULKACTIONS-ADB-SCRIPT-NEW.bat -f blkacts_multi_all9_success.json -e Medium_Phone_API_35
echo     BULKACTIONS-ADB-SCRIPT-NEW.bat -f blkacts_multi_all9_success.json --real-device --no-interact
goto :eof

REM -- Parse flags -------------------------------------------------------
set "CONFIG="
set "EMULATOR=Medium_Phone_API_35"
set "NO_INTERACT=false"
set "SHOW_EMULATOR=false"
set "REAL_DEVICE=false"

:parseargs
if "%~1"=="" goto :done_parsing

if "%~1"=="-f" if "%~2"=="" (
    echo ERROR: -f requires a value
    goto :show_help
    exit /b 1
)
if "%~1"=="--filepath" if "%~2"=="" (
    echo ERROR: --filepath requires a value
    goto :show_help
    exit /b 1
)

if "%~1"=="-e" if "%~2"=="" (
    echo ERROR: -e requires a value
    goto :show_help
    exit /b 1
)
if "%~1"=="--emulator-name" if "%~2"=="" (
    echo ERROR: --emulator-name requires a value
    goto :show_help
    exit /b 1
)

if "%~1"=="-f" (
    set "CONFIG=%~2"
    shift & shift
    goto :parseargs
)
if "%~1"=="--filepath" (
    set "CONFIG=%~2"
    shift & shift
    goto :parseargs
)
if "%~1"=="-e" (
    set "EMULATOR=%~2"
    shift & shift
    goto :parseargs
)
if "%~1"=="--emulator-name" (
    set "EMULATOR=%~2"
    shift & shift
    goto :parseargs
)
if "%~1"=="-d" (
    set "REAL_DEVICE=true"
    shift
    goto :parseargs
)
if "%~1"=="--real-device" (
    set "REAL_DEVICE=true"
    shift
    goto :parseargs
)
if "%~1"=="-a" (
    set "NO_INTERACT=true"
    shift
    goto :parseargs
)
if "%~1"=="--no-interact" (
    set "NO_INTERACT=true"
    shift
    goto :parseargs
)
if "%~1"=="-s" (
    set "SHOW_EMULATOR=true"
    shift
    goto :parseargs
)
if "%~1"=="--show-emulator" (
    set "SHOW_EMULATOR=true"
    shift
    goto :parseargs
)
if "%~1"=="-h" (
    goto :show_help
    exit /b 0
)
if "%~1"=="--help" (
    goto :show_help
    exit /b 0
)

echo ERROR: Unknown argument: %~1
goto :show_help
exit /b 1

:done_parsing

if "%CONFIG%"=="" (
    echo ERROR: --filepath (-f) is required.
    goto :show_help
    exit /b 1
)

REM Expand ~ in CONFIG to %USERPROFILE%
set "CONFIG=%CONFIG:~=%USERPROFILE%%"

set "APP_ID=io.github.mobilutils.ntp_dig_ping_more"

REM FIX 2: The actual writable filesDir on device is .../files/files/
REM Context.filesDir returns /data/user/0&lt;pkg&gt;/files on the Kotlin side,
REM but the shell-accessible directory for *contents* is /data/user/0&lt;pkg&gt;/files/files/
REM FILES_DIR = what Kotlin's filesDir.absolutePath returns (the tilde-expansion base)
REM PRIVATE_DIR = where files are actually stored on the shell (push/pull target)
set "FILES_DIR=/data/user/0/%APP_ID%/files"
set "PRIVATE_DIR=%FILES_DIR%"

REM Extract just the filename for device push (equivalent of basename)
for %%F in ("%CONFIG%") do set "CONFIG_FILENAME=%%~nxF"
set "PUSH_PATH=%PRIVATE_DIR%/%CONFIG_FILENAME%"

set "RESULTS_DIR=./test-results"

REM Use CONFIG as-is (already expanded). No implicit prefix resolution.
set "CONFIG_SOURCE=%CONFIG%"

REM -- Helper: extract JSON string field (via PowerShell) ---
REM Usage: call :json_str_field ^&lt;file^&gt; ^&lt;field_name^&gt; ^&lt;output_var^&gt;
:json_str_field
setlocal
for /f "delims=" %%I in ('powershell -Command ^
    "$content = Get-Content -Raw %~1; ^
     $match = [regex]::Match($content, '\"%~2\"\s*:\s*\"([^\"]*)\"'); ^
     if ($match.Success) { $match.Groups[1].Value } else { '' }"') do (
    endlocal
    set "%~3=%%I"
    goto :eof
)
endlocal
set "%~3="
goto :eof

REM -- Helper: extract JSON numeric field (via PowerShell) ---
REM Usage: call :json_num_field ^&lt;file^&gt; ^&lt;field_name^&gt; ^&lt;output_var^&gt;
:json_num_field
setlocal
for /f "delims=" %%I in ('powershell -Command ^
    "$content = Get-Content -Raw %~1; ^
     $match = [regex]::Match($content, '\"%~2\"\s*:\s*(-?[0-9]+)'); ^
     if ($match.Success) { $match.Groups[1].Value } else { '' }"') do (
    endlocal
    set "%~3=%%I"
    goto :eof
)
endlocal
set "%~3="
goto :eof

REM -- Validate config file exists ------------------------------------
if not exist "%CONFIG_SOURCE%" (
    echo ERROR: Config file not found: %CONFIG_SOURCE%
    exit /b 1
)

echo ==================================================================
echo   Bulk Actions ADB Automation
echo ==================================================================
echo   Config:          %CONFIG%
if "%REAL_DEVICE%"=="true" (
    echo   Mode:           Real Device
) else (
    echo   Emulator:        %EMULATOR%
)
echo   Push path:       %PUSH_PATH%
echo   Results dir:     %RESULTS_DIR%
echo ==================================================================

REM -- 1. Ensure emulator is running ----------------------------------
set "EMULATOR_LAUNCHED=false"

if "%REAL_DEVICE%"=="true" (
    echo.
    echo [1/8] Real device mode — skipping emulator.
) else (
    REM Check if a device is connected
    adb devices | findstr /i "device" >nul 2>&1
    if errorlevel 1 (
        REM No device found — launch emulator
        echo.
        echo [1/8] Starting emulator: %EMULATOR%
        if "%SHOW_EMULATOR%"=="true" (
            start /b emulator -avd "%EMULATOR%" -no-audio -no-boot-anim
            echo   Emulator window will be visible.
        ) else (
            start /b emulator -avd "%EMULATOR%" -no-skin -no-audio -no-boot-anim
        )
        set "EMULATOR_LAUNCHED=true"

        echo   Waiting for device...
        adb wait-for-device

        REM Wait for boot to complete
        echo   Booting... (waiting up to 120s)
        set /a BOOT_COUNT=0
        :boot_loop
        set /a BOOT_COUNT+=1
        if %BOOT_COUNT% gtr 120 goto :boot_done
        for /f "delims=" %%B in ('adb shell getprop sys.boot_completed 2^>nul') do (
            if "%%B"=="1" (
                echo   Boot complete after %BOOT_COUNT%s.
                goto :boot_done
            )
        )
        timeout /t 1 /nobreak >nul
        goto :boot_loop
        :boot_done
    ) else (
        echo.
        echo [1/8] Device/emulator already running
    )
)

REM -- 2. Push config file to app's private directory -----------------
echo.
echo [2/8] Pushing config file...
REM FIX 2 + 3: ADB cannot write directly to app's private dir (shell user has no permission).
REM Host-side type pipes file content into a single adb shell that runs as the app's UID via run-as.
REM This avoids both the shell-user permission issue (push) and the /sdcard/ issue (run-as can't
REM read /sdcard because it runs in the app's mount namespace).
REM a substituted command would look like :
REM type notes\config-files_bulk-actions\blkacts_single_ping_only.json | adb shell "run-as io.github.mobilutils.ntp_dig_ping_more sh -c 'cat > /data/user/0/io.github.mobilutils.ntp_dig_ping_more/files/blkacts_single_ping_only.json'"
type "%CONFIG_SOURCE%" | adb shell "run-as %APP_ID% sh -c 'cat ^> %PUSH_PATH%'"

REM -- 3. Verify file was written -------------------------------------
echo.
echo [3/8] Verifying config file...
adb shell "run-as %APP_ID% test -f %PUSH_PATH%"
if errorlevel 1 (
    echo   ERROR: Config file not found in private dir after push.
    echo   Try: adb shell run-as %APP_ID% ls -la files/files/
    exit /b 1
)
echo   Config file verified in private directory.

REM -- 4. Launch app with intent extras -------------------------------
echo.
echo [4/8] Launching app (auto-load + auto-run)...
set "FILE_URI=file://%PUSH_PATH%"

REM FIX 1: Use --ez (boolean extra) NOT --es (string extra).
REM getBooleanExtra("auto_run", false) silently returns false when the value
REM is a String (passed via --es). --ez passes a genuine boolean.
adb shell am force-stop "%APP_ID%" 2>nul
timeout /t 1 /nobreak >nul
adb shell am start ^
      -n "%APP_ID%/.MainActivity" ^
      -d "%FILE_URI%" ^
      --ez auto_run true

echo   intent.data    = %FILE_URI%
echo   auto_run       = true (boolean via --ez)

REM -- 5. Wait for execution to complete ---------------------------------
echo.
echo [5/8] Waiting for bulk execution...

REM it might take 1 or 2 sec for the app to launch our bulk actions
REM thus if we check for file existence too soon it will fail
REM so we will wait for .running-tasks marker file to exist before proceeding
set "RUNNING_FILE=.running-tasks"
set /a POLL_INTERVAL=1
set /a MAX_WAIT=600
set /a WAITED=0

:wait_for_marker_creation
if %WAITED% geq %MAX_WAIT% goto :marker_creation_timeout
for /f "delims=" %%E in ('adb shell "run-as %APP_ID% test -f %PRIVATE_DIR%/%RUNNING_FILE% ^&^& echo yes ^|^| echo no" 2^>nul') do (
    set "EXISTS=%%E"
)
if not "!EXISTS!"=="no" (
    echo   waiting for marker .running-tasks file creation %WAITED%s.
    goto :marker_creation_done
)
timeout /t %POLL_INTERVAL% /nobreak >nul
set /a WAITED+=POLL_INTERVAL
goto :wait_for_marker_creation

:marker_creation_timeout
echo   WARNING: Timed out waiting for .running-tasks marker file to appear.
:marker_creation_done

REM Poll for the .running-tasks marker file instead of a blind sleep.
REM Interval: 2s.  Max timeout: 10min (600s) as a safety net in case the
REM file is never removed (e.g. app crash during execution).
set /a POLL_INTERVAL=2
set /a MAX_WAIT=600
set /a WAITED=0

echo   Polling for .running-tasks marker file (max %MAX_WAIT%s, %POLL_INTERVAL%s interval)...

:wait_for_marker_removal
if %WAITED% geq %MAX_WAIT% goto :marker_removal_timeout
for /f "delims=" %%E in ('adb shell "run-as %APP_ID% test -f %PRIVATE_DIR%/%RUNNING_FILE% ^&^& echo yes ^|^| echo no" 2^>nul') do (
    set "EXISTS=%%E")
if not "!EXISTS!"=="yes" (
    echo   Execution finished after %WAITED%s.
    goto :marker_removal_done
)
timeout /t %POLL_INTERVAL% /nobreak >nul
set /a WAITED+=POLL_INTERVAL
goto :wait_for_marker_removal

:marker_removal_timeout
echo   WARNING: Timed out after %MAX_WAIT%s — .running-tasks still present.
echo   The app may have crashed. Results might be incomplete or missing.
:marker_removal_done

REM -- 6. Pull results -------------------------------------------------
echo.
echo [6/8] Pulling results...
if not exist "%RESULTS_DIR%" mkdir "%RESULTS_DIR%"

REM FIX 5: Expand ~ in output-file to app's private dir (same as BulkConfigParser.expandTilde).
call :json_str_field "%CONFIG_SOURCE%" "output-file" "OUTPUT_FILE"

if "%OUTPUT_FILE%"=="" (
    echo   ERROR: field "output-file" not found, running via adb relies on JSON "output-file", set it and run the script again
    exit /b 1
)

REM Check if output-file starts with /sdcard
echo "%OUTPUT_FILE%" | findstr /i "^/sdcard" >nul 2>&1
if not errorlevel 1 (
    echo   ERROR: field "output-file" value "%OUTPUT_FILE%" starts with /sdcard app will probably fail to write elsewhere than appfolder. Correct by removing /sdcard from "output-file" value and run again.
    exit /b 1
)

set "DEVICE_OUTPUT_FILE="
if "%OUTPUT_FILE%"=="" (
    echo   No output-file defined in config — results are in-memory only
    set "LOCAL_OUTPUT="
) else (
    REM FIX 5: Expand ~ in output-file to the app's private filesDir.
    REM BulkConfigParser.expandTilde() uses: filesDir.absolutePath + path.substring(1)
    REM where filesDir.absolutePath = FILES_DIR = /data/user/0&lt;pkg&gt;/files
    REM Example: "~/files/foo.txt" → FILES_DIR + "/files/foo.txt" = PRIVATE_DIR/foo.txt
    REM          "~/foo.txt"         → FILES_DIR + "/foo.txt" = FILES_DIR/foo.txt
    REM          "/abs/path"         → "/abs/path" (no change)
    echo "%OUTPUT_FILE%" | findstr /i "^~/" >nul 2>&1
    if not errorlevel 1 (
        REM Starts with ~/ — expand tilde
        for /f "delims=" %%T in ('echo "%OUTPUT_FILE%" ^| powershell -Command "$args[0] -replace '^~','"') do (
            set "PATH_AFTER_TILDE=%%T"
        )
        set "DEVICE_OUTPUT_FILE=%FILES_DIR%%PATH_AFTER_TILDE%"
    ) else (
        set "DEVICE_OUTPUT_FILE=%OUTPUT_FILE%"
    )

    REM Generate timestamp (equivalent of date '+%Y%m%d-%H:%M:%S')
    for /f "delims=" %%T in ('powershell -Command "Get-Date -Format 'yyyyMMdd-HHmmss'"') do (
        set "TIMESTAMP=%%T"
    )

    for %%F in ("%DEVICE_OUTPUT_FILE%") do set "OUTPUT_BASENAME=%%~nxF"
    set "LOCAL_OUTPUT=%RESULTS_DIR%\%TIMESTAMP%_%OUTPUT_BASENAME%"

    REM FIX 3: adb pull cannot read from private dir (shell user denied).
    REM Use run-as to cat the file to stdout, capture on host with shell redirect.
    echo   Pulling: %DEVICE_OUTPUT_FILE%
    adb shell "run-as %APP_ID% cat %DEVICE_OUTPUT_FILE%" > "%LOCAL_OUTPUT%" 2>nul
    if errorlevel 1 (
        echo   WARNING: Could not pull %DEVICE_OUTPUT_FILE% (may not exist if auto-save failed)
        set "LOCAL_OUTPUT="
    )

    if not "%LOCAL_OUTPUT%"=="" (
        if exist "%LOCAL_OUTPUT%" (
            for %%Z in ("%LOCAL_OUTPUT%") do set "FILE_SIZE=%%~zZ"
            if "!FILE_SIZE!"=="0" (
                echo   WARNING: Output file is empty or missing. Did the commands complete in time?
                echo   Hint: Increase wait time or check app logs with: adb logcat | findstr ntp_dig
                set "LOCAL_OUTPUT="
            ) else (
                echo         -^> %LOCAL_OUTPUT%
            )
        ) else (
            echo   WARNING: Output file is empty or missing. Did the commands complete in time?
            echo   Hint: Increase wait time or check app logs with: adb logcat | findstr ntp_dig
            set "LOCAL_OUTPUT="
        )
    )
)

REM -- 7. Cleaning ---------------------------------------------------
REM We will remove file on device now

echo.
echo [7/8] Cleaning remote android app folder...
if not exist "%RESULTS_DIR%" mkdir "%RESULTS_DIR%"
echo Will Clean %PRIVATE_DIR%
adb shell "run-as %APP_ID% test -f '%PUSH_PATH%'"
if not errorlevel 1 (
    REM We will remove file on device now
    echo   Removing %PUSH_PATH%
    adb shell "run-as %APP_ID% rm '%PUSH_PATH%'"
) else (
    echo File %PUSH_PATH% doesn't exists. FAILED.
)

if defined DEVICE_OUTPUT_FILE (
    adb shell "run-as %APP_ID% test -f '%DEVICE_OUTPUT_FILE%'"
    if not errorlevel 1 (
        echo   Removing %DEVICE_OUTPUT_FILE%
        adb shell "run-as %APP_ID% rm %DEVICE_OUTPUT_FILE%"
    ) else (
        echo File %DEVICE_OUTPUT_FILE% doesn't exists. FAILED.
    )
)

REM -- 8. Report -------------------------------------------------------
echo.
echo [8/8] Done.
echo.
echo ==================================================================
echo   Automation complete.
if not "%LOCAL_OUTPUT%"=="" (
    if exist "%LOCAL_OUTPUT%" (
        for %%Z in ("%LOCAL_OUTPUT%") do set "FILE_SIZE=%%~zZ"
        if not "!FILE_SIZE!"=="0" (
            echo   Results: %LOCAL_OUTPUT%
            for /f "delims=" %%L in ('find /c /v "" ^< "%LOCAL_OUTPUT%"') do (
                echo   Lines:     %%L
            )
            for %%Z in ("%LOCAL_OUTPUT%") do (
                echo   Size:      %%~zZ bytes
            )
        ) else (
            echo   No results file found.
        )
    ) else (
        echo   No results file found.
    )
) else (
    echo   No results file found.
)
echo ==================================================================

REM Optionally keep emulator running or close it
if "%NO_INTERACT%"=="false" if "%EMULATOR_LAUNCHED%"=="true" if "%REAL_DEVICE%"=="false" (
    echo.
    set /p "REPLY=Close emulator? (y/N): "
    if /i "!REPLY!"=="Y" (
        echo Closing emulator...
        adb emu kill
    )
)

exit /b 0
