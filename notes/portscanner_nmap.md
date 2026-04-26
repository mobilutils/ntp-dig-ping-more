# Port Scanner Implementation

## Overview

A custom socket-based port scanner that probes TCP and UDP ports on a target host within a user-specified range. **Not nmap** — this app implements its own scanning logic using Java's `Socket` and `DatagramSocket` APIs.

## Architecture

| File | Role |
|---|---|
| `PortScannerScreen.kt` | Compose UI: host/port inputs, protocol toggle (TCP/UDP), progress bar, results list, history section |
| `PortScannerViewModel.kt` | Scanning orchestration, state management via `StateFlow<PortScannerUiState>` |
| `PortScannerHistoryStore.kt` | DataStore persistence of the last 5 scan configurations |

## How Scanning Works

### TCP Scanning
For each port in `[startPort, endPort]`, a `java.net.Socket` is created and connected to the target host with a **1-second timeout**. If `connect()` succeeds, the port is reported as open. If any `Exception` is thrown (connection refused, timeout, etc.), the port is considered closed.

### UDP Scanning
For each port, an empty (`0-byte`) datagram is sent via `DatagramSocket`. The socket waits up to **1 second** for a response. If a packet is received, the port is reported as open. On `SocketTimeoutException` or any other exception, the port is considered closed.

### Concurrency
Ports are scanned in **chunks of 50** (sequential chunk iteration, concurrent within each chunk). This prevents "too many open files" and OOM errors. Results are collected thread-safely via a `Mutex` and sorted on discovery.

### State Flow
1. User enters host + port range, selects TCP or UDP
2. Presses "Scan Ports" → `isRunning = true`, progress resets
3. Ports are probed in chunks; progress bar and discovered ports update live
4. On completion → `isRunning = false`, progress = 1.0f, scan saved to history

## History
- Last 5 scans persisted via DataStore (serialized as newline-delimited pipe-separated fields)
- History deduplication: if a scan with the same host/port range/protocol is re-run, the entry replaces the oldest duplicate
- Tapping a history entry populates the form fields and auto-starts a new scan

## Limitations

### UDP scanning is unreliable
The UDP implementation sends a **zero-byte packet** and waits for any response. This means:
- **Open ports with no service** → no response → reported as closed (false negative)
- **Filtered ports** (firewall dropping packets) → timeout → reported as closed (false negative)
- **Only open ports with an active service** will respond reliably

In short, UDP scanning here is a **best-effort probe**, not a thorough scan. A real UDP scanner (like nmap's) uses service-specific probes with sophisticated timing and classification.

### TCP scanning is also limited
- **1-second timeout** per port means slow-responding ports may be missed
- **No port state differentiation** — only "open" vs "closed"; no "filtered", "reset", or "closed-filtered" states
- **No service detection** — no banner grabbing, no OS fingerprinting

### General limitations vs. nmap
- No nmap script engine (NSE)
- No stealth (SYN) scanning
- No IP protocol scanning
- No timing/template tuning
- No MAC address or vendor detection

---

## Possible Improvements in the Future

1. **Add nmap as a dependency or companion CLI tool** — Bundle `nmap-android` (or use the `nmap` CLI via Termux/NDK) for full-featured scanning. This is the most impactful improvement.
2. **Configurable timeout** — Let users set per-port timeout (currently hardcoded to 1s).
3. **Well-known port presets** — Add quick-select buttons for common ranges (e.g., "Web Ports 80-443", "Common Services 1-1024").
4. **Service name lookup** — Use `ServiceManager` or IANA registry to label ports (e.g., "80 → HTTP").
5. **UDP probe templates** — Add service-specific UDP probes (DNS query, SNMP get, etc.) to improve UDP scan accuracy.
6. **Parallel scanning with adaptive concurrency** — Dynamically adjust chunk size based on system resources.
7. **Export results** — Allow saving scan results as CSV or JSON.
8. **Nmap-compatible output** — If nmap is added, support parsing and displaying its XML output format.
