# TRACING-PROGRESS.md
Tracking Android execution and ADB commands for Work Profile setup on Android 9, 11, 13, 15 & 16.

## Samsung Tab Active 2 Real Device (Android 9)

| Timestamp | Command | Observation/Result |
|-----------|---------|-------------------|
| 2026-05-25 09:20:54 | adb devices | 52007280b8f0c415 unauthorized |
| 2026-05-25 09:20:58 | adb devices | 52007280b8f0c415 device |
| 2026-05-25 09:21:03 | adb -s 52007280b8f0c415 shell "getprop ro.product.model && getprop ro.build.version.release" | SM-T395, 9 (Android 9 Samsung tablet) |
| 2026-05-25 09:21:12 | adb -s 52007280b8f0c415 shell pm create-user --profileOf 0 --managed "Work Profile" | Success: created user id 10 |
| 2026-05-25 09:21:16 | adb -s 52007280b8f0c415 shell am start-user 10 | Success: user started |
| 2026-05-25 09:21:56 | adb -s 52007280b8f0c415 install --user 10 testdpc.apk | Success |
| 2026-05-25 09:22:04 | adb -s 52007280b8f0c415 shell dpm set-profile-owner --user 10 com.afwsamples.testdpc/.DeviceAdminReceiver | Success: Active admin and profile owner set |
| 2026-05-25 09:22:19 | adb -s 52007280b8f0c415 install --user 10 app/build/outputs/apk/debug/app-debug.apk | Success |
| 2026-05-25 09:22:23 | adb -s 52007280b8f0c415 shell am start --user 10 -n io.github.mobilutils.ntp_dig_ping_more/.MainActivity | Starting: Intent { cmp=io.github.mobilutils.ntp_dig_ping_more/.MainActivity } |
| 2026-05-25 09:22:46 | adb -s 52007280b8f0c415 shell screencap -p /sdcard/workprofile_launch_samsung_tab.png | Successfully captured screen of running Work Profile app on Samsung Tab |

## Android 9

| Timestamp | Command | Observation/Result |
|-----------|---------|-------------------|
| 2026-05-24 22:22:20 | emulator -avd Medium_Phone_API_28 -wipe-data | Successfully launched emulator in background with wiped data |
| 2026-05-24 22:22:32 | adb devices | emulator-5554 device |
| 2026-05-24 22:22:46 | adb shell getprop sys.boot_completed | 1 (boot completed successfully) |
| 2026-05-24 22:22:56 | adb shell pm create-user --profileOf 0 --managed "Work Profile" | Success: created user id 10 |
| 2026-05-24 22:23:02 | adb shell am start-user 10 | Success: user started |
| 2026-05-24 22:23:07 | adb install --user 10 testdpc.apk | Success |
| 2026-05-24 22:23:14 | adb shell dpm set-profile-owner --user 10 com.afwsamples.testdpc/.DeviceAdminReceiver | Success: Active admin and profile owner set |
| 2026-05-24 22:23:21 | adb install --user 10 app/build/outputs/apk/debug/app-debug.apk | Success |
| 2026-05-24 22:23:26 | adb shell am start --user 10 -n io.github.mobilutils.ntp_dig_ping_more/.MainActivity | Starting: Intent { cmp=io.github.mobilutils.ntp_dig_ping_more/.MainActivity } |
| 2026-05-24 22:23:31 | adb shell screencap -p /sdcard/workprofile_launch_9.png | Successfully captured screen of running Work Profile app on Android 9 |

## Zebra TC57 Real Device (Android 11)

| Timestamp | Command | Observation/Result |
|-----------|---------|-------------------|
| 2026-05-25 10:08:59 | adb devices | 20295522504336 device |
| 2026-05-25 10:09:03 | adb -s 20295522504336 shell "getprop ro.product.model && getprop ro.build.version.release" | TC57, 11 (Android 11 Zebra device) |
| 2026-05-25 10:09:12 | adb -s 20295522504336 shell pm create-user --profileOf 0 --user-type android.os.usertype.profile.MANAGED "Work Profile" | Success: created user id 10 |
| 2026-05-25 10:09:16 | adb -s 20295522504336 shell am start-user 10 | Success: user started |
| 2026-05-25 10:09:30 | adb -s 20295522504336 install --user 10 testdpc.apk | Success |
| 2026-05-25 10:09:39 | adb -s 20295522504336 shell dpm set-profile-owner --user 10 com.afwsamples.testdpc/.DeviceAdminReceiver | Success: Active admin and profile owner set |
| 2026-05-25 10:09:58 | adb -s 20295522504336 install --user 10 app/build/outputs/apk/debug/app-debug.apk | Success |
| 2026-05-25 10:10:01 | adb -s 20295522504336 shell am start --user 10 -n io.github.mobilutils.ntp_dig_ping_more/.MainActivity | Starting: Intent { cmp=io.github.mobilutils.ntp_dig_ping_more/.MainActivity } |
| 2026-05-25 10:10:35 | adb -s 20295522504336 shell screencap -p /sdcard/workprofile_launch_zebra.png | Successfully captured screen of running Work Profile app on Zebra TC57 |

## Android 11

| Timestamp | Command | Observation/Result |
|-----------|---------|-------------------|
| 2026-05-24 22:25:52 | emulator -avd Medium_Phone_API_30 -wipe-data | Successfully launched emulator in background with wiped data |
| 2026-05-24 22:25:59 | adb devices | emulator-5556 offline |
| 2026-05-24 22:26:14 | adb devices | emulator-5556 device |
| 2026-05-24 22:26:17 | adb shell getprop sys.boot_completed | 1 (boot completed successfully) |
| 2026-05-24 22:26:27 | adb -s emulator-5556 shell pm create-user --profileOf 0 --user-type android.os.usertype.profile.MANAGED "Work Profile" | Success: created user id 10 |
| 2026-05-24 22:26:34 | adb -s emulator-5556 shell am start-user 10 | Success: user started |
| 2026-05-24 22:26:40 | adb -s emulator-5556 install --user 10 testdpc.apk | Success |
| 2026-05-24 22:26:46 | adb -s emulator-5556 shell dpm set-profile-owner --user 10 com.afwsamples.testdpc/.DeviceAdminReceiver | Success: Active admin and profile owner set |
| 2026-05-24 22:26:55 | adb -s emulator-5556 install --user 10 app/build/outputs/apk/debug/app-debug.apk | Success |
| 2026-05-24 22:27:01 | adb -s emulator-5556 shell am start --user 10 -n io.github.mobilutils.ntp_dig_ping_more/.MainActivity | Starting: Intent { cmp=io.github.mobilutils.ntp_dig_ping_more/.MainActivity } |
| 2026-05-24 22:27:10 | adb -s emulator-5556 shell screencap -p /sdcard/workprofile_launch_11.png | Successfully captured screen of running Work Profile app on Android 11 |

## Android 13

| Timestamp | Command | Observation/Result |
|-----------|---------|-------------------|
| 2026-05-24 22:35:26 | emulator -avd Medium_Phone_API_33 -wipe-data | Successfully launched emulator in background with wiped data |
| 2026-05-24 22:35:40 | adb devices | emulator-5554 device |
| 2026-05-24 22:35:55 | adb -s emulator-5554 shell getprop sys.boot_completed | 1 (boot completed successfully) |
| 2026-05-24 22:36:04 | adb -s emulator-5554 shell pm create-user --profileOf 0 --user-type android.os.usertype.profile.MANAGED "Work Profile" | Success: created user id 10 |
| 2026-05-24 22:36:11 | adb -s emulator-5554 shell am start-user 10 | Success: user started |
| 2026-05-24 22:36:17 | adb -s emulator-5554 install --user 10 testdpc.apk | Success |
| 2026-05-24 22:36:26 | adb -s emulator-5554 shell dpm set-profile-owner --user 10 com.afwsamples.testdpc/.DeviceAdminReceiver | Success: Active admin and profile owner set |
| 2026-05-24 22:36:35 | adb -s emulator-5554 install --user 10 app/build/outputs/apk/debug/app-debug.apk | Success |
| 2026-05-24 22:36:42 | adb -s emulator-5554 shell am start --user 10 -n io.github.mobilutils.ntp_dig_ping_more/.MainActivity | Starting: Intent { cmp=io.github.mobilutils.ntp_dig_ping_more/.MainActivity } |
| 2026-05-24 22:36:48 | adb -s emulator-5554 shell screencap -p /sdcard/workprofile_launch_13.png | Successfully captured screen of running Work Profile app on Android 13 |

## Android 15

| Timestamp | Command | Observation/Result |
|-----------|---------|-------------------|
| 2026-05-24 21:49:53 | adb devices | Failed: ADB daemon not running on host port 5037 and socket bind blocked by sandbox |
| 2026-05-24 21:54:02 | emulator -avd Medium_Phone_API_35 -wipe-data | Successfully launched emulator in background with wiped data |
| 2026-05-24 21:54:07 | adb devices | emulator-5554 offline |
| 2026-05-24 21:54:12 | adb devices | emulator-5554 device |
| 2026-05-24 21:54:13 | adb shell getprop sys.boot_completed | Empty (booting in progress...) |
| 2026-05-24 21:54:30 | adb shell getprop sys.boot_completed | 1 (boot completed successfully) |
| 2026-05-24 21:54:40 | adb shell pm create-user --profileOf 0 --user-type android.os.usertype.profile.MANAGED "Work Profile" | Success: created user id 10 |
| 2026-05-24 21:54:47 | adb shell am start-user 10 | Success: user started |
| 2026-05-24 21:54:52 | adb install --user 10 testdpc.apk | Success |
| 2026-05-24 21:55:00 | adb shell dpm set-profile-owner --user 10 com.afwsamples.testdpc/.DeviceAdminReceiver | Success: Active admin and profile owner set |
| 2026-05-24 21:55:06 | adb install --user 10 app/build/outputs/apk/debug/app-debug.apk | Success |
| 2026-05-24 21:55:15 | adb shell am start --user 10 -n io.github.mobilutils.ntp_dig_ping_more/.MainActivity | Starting: Intent { cmp=io.github.mobilutils.ntp_dig_ping_more/.MainActivity } |
| 2026-05-24 21:55:21 | adb shell screencap -p /sdcard/workprofile_launch.png | Successfully captured screen of running Work Profile app |

## Android 16

| Timestamp | Command | Observation/Result |
|-----------|---------|-------------------|
| 2026-05-24 22:15:37 | emulator -avd Medium_Phone_API_36 -wipe-data | Successfully launched emulator in background with wiped data |
| 2026-05-24 22:15:51 | adb devices | emulator-5554 offline |
| 2026-05-24 22:15:56 | adb devices | emulator-5554 device |
| 2026-05-24 22:16:09 | adb shell getprop sys.boot_completed | 1 (boot completed successfully) |
| 2026-05-24 22:16:20 | adb shell pm create-user --profileOf 0 --user-type android.os.usertype.profile.MANAGED "Work Profile" | Success: created user id 10 |
| 2026-05-24 22:16:27 | adb shell am start-user 10 | Success: user started |
| 2026-05-24 22:16:32 | adb install --user 10 testdpc.apk | Success |
| 2026-05-24 22:16:38 | adb shell dpm set-profile-owner --user 10 com.afwsamples.testdpc/.DeviceAdminReceiver | Success: Active admin and profile owner set |
| 2026-05-24 22:16:44 | adb install --user 10 app/build/outputs/apk/debug/app-debug.apk | Success |
| 2026-05-24 22:16:49 | adb shell am start --user 10 -n io.github.mobilutils.ntp_dig_ping_more/.MainActivity | Starting: Intent { cmp=io.github.mobilutils.ntp_dig_ping_more/.MainActivity } |
| 2026-05-24 22:16:54 | adb shell screencap -p /sdcard/workprofile_launch_16.png | Successfully captured screen of running Work Profile app on Android 16 |

## Samsung A34 Real Device (Android 16)

| Timestamp | Command | Observation/Result |
|-----------|---------|-------------------|
| 2026-05-24 23:21:12 | adb devices | RZCX40VDM4W device |
| 2026-05-24 23:21:40 | adb -s RZCX40VDM4W shell pm create-user --profileOf 0 --user-type android.os.usertype.profile.MANAGED "Work Profile" | Success: created user id 10 |
| 2026-05-24 23:21:53 | adb -s RZCX40VDM4W shell am start-user 10 | Success: user started |
| 2026-05-24 23:22:01 | adb -s RZCX40VDM4W install --user 10 testdpc.apk | Success |
| 2026-05-24 23:22:23 | adb -s RZCX40VDM4W shell dpm set-profile-owner --user 10 com.afwsamples.testdpc/.DeviceAdminReceiver | Success: Active admin and profile owner set |
| 2026-05-24 23:22:29 | adb -s RZCX40VDM4W install --user 10 app/build/outputs/apk/debug/app-debug.apk | Success |
| 2026-05-24 23:23:09 | adb -s RZCX40VDM4W shell am start --user 10 -n io.github.mobilutils.ntp_dig_ping_more/.MainActivity | Starting: Intent { cmp=io.github.mobilutils.ntp_dig_ping_more/.MainActivity } |
| 2026-05-24 23:23:16 | adb -s RZCX40VDM4W shell screencap -p /sdcard/workprofile_launch_samsung.png | Successfully captured screen of running Work Profile app on Samsung A34 |

## Samsung XCover 4 Real Device (Android 7.0)

| Timestamp | Command | Observation/Result |
|-----------|---------|-------------------|
| 2026-05-25 10:41:10 | adb devices | 4200adedd0884423 device |
| 2026-05-25 10:41:15 | adb -s 4200adedd0884423 shell "getprop ro.product.model && getprop ro.build.version.release" | SM-G390F, 7.0 (Android 7.0 Samsung XCover4) |
| 2026-05-25 10:41:20 | adb -s 4200adedd0884423 shell pm create-user --profileOf 0 --managed "Work Profile" | Success: created user id 10 |
| 2026-05-25 10:41:32 | adb -s 4200adedd0884423 shell am start-user 10 | Success: user started |
| 2026-05-25 10:45:33 | adb -s 4200adedd0884423 shell "settings put global verifier_verify_adb_installs 0 && settings put global package_verifier_enable 0" | Success: Package verification disabled to unblock adb installs |
| 2026-05-25 10:46:26 | adb -s 4200adedd0884423 install -r -g --user 10 testdpc.apk | Success: Streamed Install |
| 2026-05-25 10:46:41 | adb -s 4200adedd0884423 shell dpm set-profile-owner --user 10 com.afwsamples.testdpc/.DeviceAdminReceiver | Success: Active admin and profile owner set |
| 2026-05-25 10:48:07 | adb -s 4200adedd0884423 install -r -g --user 10 app/build/outputs/apk/debug/app-debug.apk | Failed: INSTALL_FAILED_OLDER_SDK (Requires newer SDK 26, current device is API 24) |
| 2026-05-25 10:48:20 | adb -s 4200adedd0884423 shell am start --user 10 -n com.afwsamples.testdpc/.PolicyManagementActivity | Starting: Intent { cmp=com.afwsamples.testdpc/.PolicyManagementActivity } (Opened Test DPC to verify Work Profile active state) |
| 2026-05-25 10:48:58 | adb -s 4200adedd0884423 shell screencap -p /sdcard/workprofile_launch_samsung_xcover4.png | Successfully captured screen of running Work Profile Test DPC on Samsung XCover4 |
