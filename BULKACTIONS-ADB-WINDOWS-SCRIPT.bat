@echo off
setlocal enabledelayedexpansion
:: ------------------------------------------------------------------
:: BULKACTIONS-ADB-WINDOWS-SCRIPT.bat
::
:: Fully automated Bulk Actions execution via ADB — no user interaction.
:: Windows / CMD port of BULKACTIONS-ADB-SCRIPT.sh
::
:: Prerequisites:
::       - ADB installed and on PATH
::       - Emulator running OR device connected
::       - App already installed (or run gradlew installDebug first)
::       - PowerShell available (for JSON parsing helpers)
::
:: Usage:
::       BULKACTIONS-ADB-WINDOWS-SCRIPT.bat [config_filename] [emulator_name] [--no-interact] [--show-emulator]
::
:: Examples:
::       BULKACTIONS-ADB-WINDOWS-SCRIPT.bat blkacts_single_ping_success.json
::       BULKACTIONS-ADB-WINDOWS-SCRIPT.bat blkacts_multi_all9_success.json Medium_Phone_API_35
::       BULKACTIONS-ADB-WINDOWS-SCRIPT.bat blkacts_multi_all9_success.json "" --no-interact
::       BULKACTIONS-ADB-WINDOWS-SCRIPT.bat blkacts_single_ping_success.json "" --show-emulator
::
:: ── Key fixes applied (vs. original bash script) ─────────────────────
::
::  FIX 1 (Critical): Use --ez for boolean intent extra, not --es.
::       adb shell am start --es auto_run true passes a String, and
::       getBooleanExtra("auto_run", false) silently returns false.
::       -> Use --ez auto_run true to pass a real boolean.
::
::  FIX 2 (Critical): Private dir is .../files/files/, not .../files/.
::       Context.filesDir resolves to /data/user/0/<pkg>/files on disk,
::       but the shell path for the *contents* is /data/user/0/<pkg>/files/files/
::       because the directory listing shows `files -> files/files/`.
::       -> PRIVATE_DIR is now /data/user/0/$APP_ID/files/files
::
::  FIX 3 (Critical): adb pull cannot read from app private dir.
::       adb pull /data/user/0/<pkg>/files/... fails with "Permission denied"
::       because adb runs as the shell user, not the app UID.
::       -> Use adb shell run-as <pkg> cat <file> and redirect to host.
::
::  FIX 4 (Moderate): Wait time accounts for sequential command count.
::      Old: timeout * 2 + 30. New: timeout * command_count + 30.
::
::  FIX 5 (Minor): adb pull ~ expansion was broken.
::       ~ in output-file is expanded to PRIVATE_DIR (app's filesDir).
:: ------------------------------------------------------------------

set "APP_ID=io.github.mobilutils.ntp_dig_ping_more"
set "FILES_DIR=/data/user/0/%APP_ID%/files"
set "PRIVATE_DIR=%FILES_DIR%"
set "RESULTS_DIR=.\test-results"

:: -- Parse flags and positional args ---------------------------------
set "CONFIG=blkacts_single_ping_success.json"
set "EMULATOR=Medium_Phone_API_35"
set "NO_INTERACT=0"
set "SHOW_EMULATOR=0"

if "%~1" NEQ "" set "CONFIG=%~1"
if "%~2" NEQ "" set "EMULATOR=%~2"

:: Check for flags in remaining args
for %%f in (%*) do (
    if "%%f"=="--no-interact" set "NO_INTERACT=1"
    if "%%f"=="--show-emulator" set "SHOW_EMULATOR=1"
)

:: Remove .json extension for display
set "CONFIG_NAME=%CONFIG:.json=%"

:: Resolve script directory for finding config files
for %%I in (%~dp0.) do set "SCRIPT_DIR=%%~fI"
set "CONFIG_SOURCE=%SCRIPT_DIR%notes\config-files_bulk-actions\%CONFIG%"

:: -- Helper: extract JSON string field (PowerShell, no python) ------
:: Usage: call :json_str_field <file> <field_name> -> sets JSON_RESULT
:json_str_field
    set "JSON_RESULT="
    for /f "delims=" %%L in ('
        powershell -NoProfile -Command ^
        "$content = Get-Content -Path '%~1' -Raw; ^
         $match = [regex]::Match($content, '\"%~2\"[\s]*:[\s]*\"[^\"]*\"'); ^
         if ($match.Success) { $value = $match.Value -replace '.*:[\s]*\"', '' -replace '\"$', ''; Write-Output $value }" 2>nul
    do set "JSON_RESULT=%%L"
    goto :eof

:: -- Helper: extract JSON numeric field (PowerShell, no python) ------
:: Usage: call :json_num_field <file> <field_name> -> sets JSON_RESULT
:json_num_field
    set "JSON_RESULT="
    for /f "delims=" %%L in ('
        powershell -NoProfile -Command ^
        "$content = Get-Content -Path '%~1' -Raw; ^
         $match = [regex]::Match($content, '\"%~2\"[\s]*:[\s]*-?[0-9]+'); ^
         if ($match.Success) { $value = $match.Value -replace '.*:[\s]*', ''; Write-Output $value }" 2>nul
    do set "JSON_RESULT=%%L"
    goto :eof

:: -- Validate config file exists ------------------------------------
if not exist "%CONFIG_SOURCE%" (
    echo ERROR: Config file not found: %CONFIG_SOURCE%
    echo Available configs:
    for %%f in ("%SCRIPT_DIR%notes\config-files_bulk-actions\*.json") do (
        set "name=%%~nxf"
        echo      - !name:.json=!
    )
    exit /b 1
)

echo =================================================================
echo   Bulk Actions ADB Automation
echo =================================================================
echo   Config:         %CONFIG%
echo   Emulator:       %EMULATOR%
echo   Push path:      %PRIVATE_DIR%\%CONFIG%
echo   Results dir:    %RESULTS_DIR%
echo =================================================================

:: -- 1. Ensure emulator is running ----------------------------------
echo.
echo [1/8] Checking device/emulator...
adb devices | findstr /c:"device" >nul 2>&1
if errorlevel 1 (
    echo [1/8] Starting emulator: %EMULATOR%
    if "%SHOW_EMULATOR%"=="1" (
        start /b emulator -avd "%EMULATOR%" -no-audio -no-boot-anim
        echo   Emulator window will be visible.
    ) else (
        start /b emulator -avd "%EMULATOR%" -no-skin -no-audio -no-boot-anim
    )
    set "EMULATOR_LAUNCHED=1"

    echo   Waiting for device...
    adb wait-for-device

    echo   Booting... (waiting up to 120s)
    set "BOOTED=0"
    for /l %%i in (1,1,120) do (
        timeout /t 1 /nobreak >nul
        adb shell getprop sys.boot_completed 2>nul | findstr "1" >nul 2>&1
        if not errorlevel 1 (
            echo   Boot complete after %%is.
            set "BOOTED=1"
            goto :boot_done
        )
    )
    :boot_done
    if "%BOOTED%"=="0" (
        echo   WARNING: Emulator may not have booted within 120s.
    )
) else (
    set "EMULATOR_LAUNCHED=0"
    echo [1/8] Device/emulator already running
)

:: -- 2. Push config file to app's private directory -----------------
echo.
echo [2/8] Pushing config file...
:: FIX 2 + 3: ADB cannot write directly to app's private dir (shell user has no permission).
:: Host-side type pipes file content into a single adb shell that runs as the app's UID via run-as.
type "%CONFIG_SOURCE%" | adb shell "run-as %APP_ID% sh -c \"cat > %PRIVATE_DIR%\%CONFIG%\""
if errorlevel 1 (
    echo ERROR: Failed to push config file.
    exit /b 1
)

:: -- 3. Verify file was written -------------------------------------
echo.
echo [3/8] Verifying config file...
adb shell "run-as %APP_ID% test -f %PRIVATE_DIR%\%CONFIG%"
if errorlevel 1 (
    echo   ERROR: Config file not found in private dir after push.
    echo   Try: adb shell run-as %APP_ID% ls -la files\files\
    exit /b 1
)
echo   Config file verified in private directory.

:: -- 4. Launch app with intent extras -------------------------------
echo.
echo [4/8] Launching app (auto-load + auto-run)...
set "FILE_URI=file:///%PRIVATE_DIR%/%CONFIG%"

:: FIX 1: Use --ez (boolean extra) NOT --es (string extra).
:: getBooleanExtra("auto_run", false) silently returns false when the value
:: is a String (passed via --es). --ez passes a genuine boolean.
adb shell am force-stop "%APP_ID%" 2>nul || true
timeout /t 1 /nobreak >nul
adb shell am start ^
    -n "%APP_ID%/.MainActivity" ^
    -d "%FILE_URI%" ^
    --ez auto_run true

echo   intent.data   = %FILE_URI%
echo   auto_run      = true (boolean via --ez)

:: -- 5. Wait for execution to complete ---------------------------------
echo.
echo [5/8] Waiting for bulk execution...

set "RUNNING_FILE=.running-tasks"
set "POLL_INTERVAL=1"
set "MAX_WAIT=600"
set "WAITED=0"

echo   Polling for .running-tasks marker file (max %MAX_WAIT%s, %POLL_INTERVAL%s interval)...

:wait_loop_start
if %WAITED% geq %MAX_WAIT% (
    goto :wait_timeout
)

set "exists=no"
for /f "delims=" %%G in ('adb shell "run-as %APP_ID% test -f %PRIVATE_DIR%\%RUNNING_FILE% ^&^& echo yes || echo no" 2^>nul ^| findstr /c:"yes"') do set "exists=%%G"

if "%exists%"=="yes" (
    echo   Waiting for .running-tasks marker file removal...
    goto :wait_check_done
)

set "WAITED=!WAITED!+1"
:: Manual increment since delayed expansion doesn't do arithmetic directly
set /a WAITED+=1
timeout /t %POLL_INTERVAL% /nobreak >nul
goto :wait_loop_start

:wait_check_done
:: Now wait for the file to disappear (execution finished)
:wait_loop_stop
if %WAITED% geq %MAX_WAIT% (
    goto :wait_timeout
)

set "exists=yes"
for /f "delims=" %%G in ('adb shell "run-as %APP_ID% test -f %PRIVATE_DIR%\%RUNNING_FILE% ^&^& echo yes || echo no" 2^>nul ^| findstr /c:"yes"') do set "exists=%%G"

if "%exists%"=="no" (
    echo   Execution finished after %WAITED%s.
    goto :execution_done
)

set /a WAITED+=1
timeout /t %POLL_INTERVAL% /nobreak >nul
goto :wait_loop_stop

:wait_timeout
echo   WARNING: Timed out after %MAX_WAIT%s — .running-tasks still present.
echo   The app may have crashed. Results might be incomplete or missing.

:execution_done

:: -- 6. Pull results -------------------------------------------------
echo.
echo [6/8] Pulling results...
if not exist "%RESULTS_DIR%" mkdir "%RESULTS_DIR%"

:: FIX 5: Expand ~ in output-file to app's private dir (same as BulkConfigParser.expandTilde).
call :json_str_field "%CONFIG_SOURCE%" "output-file"
set "OUTPUT_FILE=%JSON_RESULT%"

if "%OUTPUT_FILE%"=="" (
    echo   ERROR: field "output-file" not found, running via adb relies on JSON "output-file", set it and run the script again
    exit /b 1
)

echo "%OUTPUT_FILE%" | findstr "^/sdcard" >nul 2>&1
if not errorlevel 1 (
    echo   ERROR: field "output-file" value "%OUTPUT_FILE%" starts with /sdcard app will probably fail to write elsewhere than appfolder. Correct by removing /sdcard from "output-file" value and run again.
    exit /b 1
)

if defined OUTPUT_FILE (
    :: FIX 5: Expand ~ in output-file to the app's private filesDir.
    :: BulkConfigParser.expandTilde() uses: filesDir.absolutePath + path.substring(1)
    :: where filesDir.absolutePath = FILES_DIR = /data/user/0/<pkg>/files
    :: Example: "~/files/foo.txt" -> FILES_DIR + "/files/foo.txt" = PRIVATE_DIR/foo.txt
    ::          "~/foo.txt"        -> FILES_DIR + "/foo.txt" = FILES_DIR/foo.txt
    ::          "/abs/path"        -> "/abs/path" (no change)
    if "%OUTPUT_FILE:~0,2%"=="~/" (
        set "path_after_tilde=%OUTPUT_FILE:~2%"
        set "DEVICE_OUTPUT_FILE=%FILES_DIR%%path_after_tilde%"
    ) else (
        set "DEVICE_OUTPUT_FILE=%OUTPUT_FILE%"
    )

    for /f "delims=" %%T in ('date -u +%Y%m%d-%%H:%%M:%%S 2^>nul') do set "TIMESTAMP=%%T"
    :: Fallback timestamp format if date command doesn't work
    if "%TIMESTAMP%"=="" set "TIMESTAMP=%DATE:~-4%%DATE:~-10,2%%DATE:~-7,2%-000000"

    set "BASENAME=%DEVICE_OUTPUT_FILE:~1%"
    set "LOCAL_OUTPUT=%RESULTS_DIR%\%TIMESTAMP%_%BASENAME%"

    :: FIX 3: adb pull cannot read from private dir (shell user denied).
    :: Use run-as to cat the file to stdout, capture on host with shell redirect.
    echo   Pulling: %DEVICE_OUTPUT_FILE%
    adb shell "run-as %APP_ID% cat %DEVICE_OUTPUT_FILE%" > "%LOCAL_OUTPUT%" 2>nul
    if errorlevel 1 (
        echo   WARNING: Could not pull %DEVICE_OUTPUT_FILE% (may not exist if auto-save failed)
        set "LOCAL_OUTPUT="
    )

    if defined LOCAL_OUTPUT (
        if exist "%LOCAL_OUTPUT%" (
            for %%F in ("%LOCAL_OUTPUT%") do (
                if %%~zF gtr 0 (
                    echo        -> %LOCAL_OUTPUT%
                ) else (
                    echo   WARNING: Output file is empty or missing. Did the commands complete in time?
                    echo   Hint: Increase wait time or check app logs with: adb logcat ^| grep ntp_dig
                    set "LOCAL_OUTPUT="
                )
            )
        )
    )
) else (
    echo   No output-file defined in config — results are in-memory only
    set "LOCAL_OUTPUT="
)


:: -- 7. Cleaning ---------------------------------------------------
echo.
echo [7/8] Cleaning remote android app folder...
mkdir "%RESULTS_DIR%" >nul 2>&1
echo Will Clean %PRIVATE_DIR%

if exist "%LOCAL_OUTPUT%" (
    for %%F in ("%LOCAL_OUTPUT%") do set "FILE_SIZE=%%~zF"
)

:: Check and remove config file on device
adb shell "run-as %APP_ID% test -f '%PRIVATE_DIR%\%CONFIG%'" >nul 2>&1
if not errorlevel 1 (
    echo   Removing %PRIVATE_DIR%\%CONFIG%
    adb shell "run-as %APP_ID% rm '%PRIVATE_DIR%\%CONFIG%'"
) else (
    echo File %PRIVATE_DIR%\%CONFIG% doesn't exist. FAILED.
)

:: Check and remove output file on device
if defined DEVICE_OUTPUT_FILE (
    adb shell "run-as %APP_ID% test -f '%DEVICE_OUTPUT_FILE%'" >nul 2>&1
    if not errorlevel 1 (
        echo   Removing %DEVICE_OUTPUT_FILE%
        adb shell "run-as %APP_ID% rm %DEVICE_OUTPUT_FILE%"
    ) else (
        echo File %DEVICE_OUTPUT_FILE% doesn't exist. FAILED.
    )
)

:: -- 8. Report -------------------------------------------------------
echo.
echo [8/8] Done.
echo.
echo =================================================================
echo   Automation complete.
if defined LOCAL_OUTPUT (
    if exist "%LOCAL_OUTPUT%" (
        for %%F in ("%LOCAL_OUTPUT%") do set "FILE_SIZE=%%~zF"
        if !FILE_SIZE! gtr 0 (
            echo   Results: %LOCAL_OUTPUT%
            for /f "delims=" %%L in ('type "%LOCAL_OUTPUT%" ^| find /c /v ""') do echo   Lines:    %%L
            echo   Size:     !FILE_SIZE! bytes
        ) else (
            echo   No results file found.
        )
    ) else (
        echo   No results file found.
    )
) else (
    echo   No results file found.
)
echo =================================================================

:: Optionally keep emulator running or close it
if "%NO_INTERACT%"=="0" if "%EMULATOR_LAUNCHED%"=="1" (
    echo.
    set /p "CLOSE_EMU=Close emulator? (y/N): "
    if /i "!CLOSE_EMU!"=="y" (
        echo Closing emulator...
        adb emu kill
    )
)

exit /b 0
