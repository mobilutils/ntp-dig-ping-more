#!/bin/bash
# ==============================================================================
# NTP DIG PING MORE - Managed Configuration (MDM) Testing Helper
# ==============================================================================
#
# IMPORTANT DISCOVERY:
# The 'adb shell dpm set-application-restriction' command DOES NOT exist in the 
# standard Android OS 'dpm' binary on any standard release (including Android 9
# and Android 16 on physical Samsung devices). Running it will always fail with
# "Unknown command: set-application-restriction".
#
# THE SOLUTION:
# You must configure app restrictions interactively using the TestDPC app's UI
# inside the Work Profile partition.
#
# ==============================================================================

DEVICE_SERIAL="RZCX40VDM4W"
USER_ID="10" # Work profile user ID
PKG="io.github.mobilutils.ntp_dig_ping_more"

echo "======================================================================"
echo "📱 Managed Configuration Testing (Samsung A34 / Android 16)"
echo "======================================================================"
echo "Note: Direct CLI pushes via 'dpm set-application-restriction' are not"
echo "supported by the Android OS on physical devices."
echo ""
echo "Interactive Testing Instructions:"
echo "1. The TestDPC app will launch on your device in the Work Profile."
echo "2. In TestDPC, scroll to 'App Management' and tap 'Manage app restrictions'."
echo "3. Select '$PKG'."
echo "4. Configure the restrictions:"
echo "   - 'ping_default_host' (String) -> e.g., 8.8.8.8"
echo "   - 'proxy_enabled' (Boolean)     -> toggle ON (true)"
echo "5. Tap Save/OK, then launch NTP DIG PING MORE (Work Profile version)!"
echo "======================================================================"
echo ""

echo "🚀 Launching TestDPC inside Work Profile (User $USER_ID)..."
adb -s $DEVICE_SERIAL shell am start --user $USER_ID -n com.afwsamples.testdpc/.PolicyManagementActivity

echo ""
echo "Done!"
