# Pseudo-Commands

## Overview

A **pseudo-command** is a built-in command keyword recognized by the Bulk Actions feature. Unlike shell executables (e.g., `ping` or `dig` which invoke system binaries via `Runtime.exec`), pseudo-commands trigger custom Kotlin code directly inside `BulkActionsRepository`. There are 9 pseudo-commands:

| Keyword | Purpose |
|---|---|
| `ping` | ICMP ping |
| `dig` | DNS resolution (via dnsjava) |
| `ntp` | NTP time query |
| `port-scan` | TCP port scan |
| `checkcert` | HTTPS certificate inspection |
| `device-info` | Device identity info |
| `tracert` | Traceroute (TTL probing) |
| `google-timesync` | Google Time Sync |
| `lan-scan` | LAN subnet ping sweep |

Unrecognized keywords fall through to a raw `Runtime.exec()` fallback.

## How It Works

```
JSON config file
  "commands": {
    "ping_google":      "ping -c 5 google.com",
    "dig_example":      "dig example.com",
    "checkcert_google": "checkcert https://google.com"
  }
       │
       ▼
BulkConfigParser.parse(json)         ← parses JSON into BulkConfig(commands=Map<String, String>)
       │
       ▼
BulkActionsViewModel.onLoadAndRun()  ← reads config, stores in UI state
       │
       ▼
BulkActionsRepository.executeAllCommands(commands)
       │
       ├── For each (name, cmd) pair:
       │    executeSingleCommand(name, cmd, timeoutMs)
       │      │
       │      └── Parse first word → dispatch via `when`:
       │           "ping"        → executePing()         → Runtime.exec("ping -c 5 google.com")
       │           "dig"         → executeDig()          → DigRepository.resolve()
       │           "ntp"         → executeNtp()          → NtpRepository.query()
       │           "port-scan"   → executePortScan()     → Socket.connect(port, timeout) × N
       │           "checkcert"   → executeCheckcert()    → HttpsCertRepository.fetchCertificate()
       │           "device-info" → executeDeviceInfo()   → SystemInfoRepository.getDeviceInfo()
       │           "tracert"     → executeTracert()      → Runtime.exec("ping -c 1 -t TTL host")
       │           "google-timesync" → executeGoogleTimeSync() → GoogleTimeSyncRepository.fetchGoogleTime()
       │           "lan-scan"    → executeLanScan()      → LanScannerRepository.pingSweep()
       │           else          → executeRaw()          → Runtime.exec(full string)
       │
       ▼
BulkCommandResult  (sealed hierarchy)
  ├── BulkCommandSuccess   — succeeded with output lines
  ├── BulkCommandError     — failed with error message
  ├── BulkCommandTimeout   — timed out
  ├── BulkCommandClosed    — manually stopped
  └── BulkCommandWarning   — succeeded but with a warning (e.g. expired cert)
       │
       ├──→ BulkActionsViewModel.generateOutputContent()  → text report with summary table
       ├──→ BulkActionsViewModel.generateCsvContent()      → CSV export
       └──→ BulkActionsScreen.ResultItem()                 → Compose UI display (icon + lines)
```

## Key Classes

| Class | Role |
|---|---|
| `BulkConfigParser` | Parses JSON config into `BulkConfig` data class |
| `BulkActionsRepository.executeSingleCommand()` | Dispatches each command keyword to the correct executor |
| `executePing()` … `executeLanScan()` | 9 private executors — invoke repositories or system binaries |
| `BulkCommandResult` (+ subclasses) | Sealed hierarchy representing execution outcomes |
| `BulkActionsViewModel.generateOutputContent()` | Formats results into a text report with summary table |
| `BulkActionsViewModel.generateCsvContent()` | Formats results as CSV |
| `BulkActionsScreen.ResultItem()` | Renders each result in the Compose UI (icon + status + output lines) |
