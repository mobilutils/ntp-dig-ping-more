# Project Summary: NTP DIG PING MORE

A modern, material-design Android application for professional network diagnostics, featuring NTP, DNS (DIG), Ping, Traceroute, Port Scanning, LAN Scanning, and Google Time Sync.

## Tech Stack

| Layer | Technology |
|---|---|
| **Language** | Kotlin |
| **UI Framework** | Jetpack Compose (Material 3) |
| **Architecture** | MVVM (ViewModel + StateFlow) |
| **Navigation** | Jetpack Navigation Compose |
| **Concurrency** | Kotlin Coroutines (`Dispatchers.IO`) |
| **Networking** | Apache Commons Net (`NTPUDPClient`), dnsjava (`SimpleResolver`), `HttpURLConnection` (Google Time Sync) |
| **Persistence** | AndroidX Preferences DataStore |
| **Build System** | Gradle (Kotlin DSL) |
| **Min SDK** | 26 (Android 8.0) |
| **Target SDK** | 35+ |

## Key Features

- **🕐 NTP Check**: Test reachability of NTP servers, showing server time, clock offset, and round-trip delay.
- **🔍 DIG Test**: Perform advanced DNS resolution directly via specified DNS servers using `dnsjava`, revealing full CNAME chains.
- **📡 Ping**: Live ICMP ping stream with real-time MONOSPACE terminal output and success/failure history tracking.
- **🛤 Traceroute**: Hop-by-hop network path discovery implemented via TTL-probing ICMP packets.
- **🕵️ Port Scanner**: Concurrent TCP/UDP port range scanning with live progress and discovery logs.
- **🖥️ LAN Scanner**: Subnet device discovery with IP, hostname, MAC, and latency; custom range scanning.
- **🌐 Google Time Sync**: Queries `http://clients2.google.com/time/1/current`, strips the XSSI prefix, and computes RTT, clock offset, and corrected server time using T1/T4 timestamps.
- **📚 Persistence**: Persistent query history for all tools (last 5 entries) using DataStore.

## Core Views & Screens

All views follow a consistent Material 3 design with dark mode support and monospace terminal cards for network output.

- **NTP Screen**: Server/Port input, status icons, time details, and clickable history list.
- **DIG Screen**: Resolver/Name input, raw `dig`-style output section, and query history.
- **Ping Screen**: Target input, Live Monospace Terminal card, Start/Stop toggle, and history.
- **Traceroute Screen**: Target input, Hop-by-hop streaming terminal, and history.
- **Port Scanner Screen**: Target/Range input, Protocol toggle, Progress bar, and open port list.
- **LAN Scanner Screen**: Subnet sweep with live device list (IP, hostname, MAC, latency).
- **Google Time Sync Screen**: Host input, animated result card with color-coded offset (🟢/🟠/🔴), corrected server time, and one-tap copy-offset button.
- **Navigation**: Bottom Navigation Bar for quick switching between diagnostics tools. Traceroute, Port Scanner, LAN Scanner, and Google Time Sync are accessible via the **More** overflow screen.

## Project Structure

```text
app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/
├── MainActivity.kt          # App Entry, NavHost, Bottom Navigation
├── *Repository.kt           # Network I/O logic (NTP, DIG)
├── *ViewModel.kt            # UI State management & command execution
├── *Screen.kt               # Jetpack Compose UI definitions
├── *HistoryStore.kt        # DataStore persistence layers
└── ui/theme/                # Material 3 Design System (Colors, Type)
```

## Internal Dependencies (Key)
- `libs.commons.net`: Used for NTP UDP packet exchange.
- `libs.dnsjava`: Used for complex DNS resolution bypassing system resolver.
- `androidx.datastore.preferences`: Lightweight persistence for user query history.
- `java.net.HttpURLConnection`: Used for the Google Time Sync HTTP request (no extra dependency; XSSI prefix stripped before JSON parsing via `org.json`).
