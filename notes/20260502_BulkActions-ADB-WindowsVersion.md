# BULKACTIONS-ADB-WINDOWS-SCRIPT.bat. 

## Key adaptations from bash to Windows CMD:

### Ported features:
 - All 5 fixes (boolean --ez, private dir path, run-as cat, wait logic, ~ expansion)
 - Flag parsing (--no-interact, --show-emulator)
 - JSON field extraction via PowerShell (replaces grep/sed)
 - All 8 automation steps (emulator check, push, verify, launch, wait, pull, cleanup, report)
 - File existence/size checks for results reporting


### Windows-specific changes:
 - findstr replaces grep
 - type replaces cat for host-side file reading
 - PowerShell one-liners for JSON parsing (no Python dependency)
 - timeout /t N /nobreak replaces sleep
 - set /a for arithmetic
 - delims= in for /f to preserve whitespace in output capture
 - Backslash path separators throughout
 - start /b for background emulator launch
 - find /c /v "" for line counting