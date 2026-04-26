# Bulk Actions: `-t` Timeout Support — Walkthrough

> **Date:** 2026-04-26
> **Scope:** `BulkActionsRepository.kt` — per-command `-t N` timeout parsing and semantics across all pseudo-commands.

---

## Global Mechanism

Every bulk command supports an **optional per-command timeout** via the `-t N` flag in the command string.

### How it works (two layers)

1. **`extractCommandTimeout(cmd: String)`** — scans the command for `-t N`, returns `N * 1000L` milliseconds.
   - `N` must be a positive integer. `0`, negative, or non-numeric values are ignored.
   - `-t` can appear **anywhere** in the command string (before or after the hostname/target).

2. **`executeCommands()`** — for each command:
   ```kotlin
   val commandTimeoutMs = extractCommandTimeout(cmd) ?: defaultTimeoutMs
   // defaultTimeoutMs = config-level "timeout" field, or 30_000L (30s)
   ```
   The coroutine-level `withTimeoutOrNull(commandTimeoutMs)` wraps the entire command execution. If the command exceeds this timeout, it returns `BulkCommandTimeout`.

### Hostname parsing fix (2026-04-26)

Prior to this fix, the host was resolved with `parts.lastOrNull { !it.startsWith("-") }`, which broke when `-t` appeared **after** the hostname:

| Command | Before (broken) | After (fixed) |
|---|---|-:--|
| `ping -c 1 google.com -t 10` | host = `"10"` ❌ | host = `"google.com"` ✅ |
| `ping -c 1 -t 10 google.com` | host = `"google.com"` ✅ | host = `"google.com"` ✅ |

The fix iterates through arguments, skipping flag-value pairs, and stops at the first non-flag token.

---

## Per-Command Behavior

### `ping`

| Aspect | Detail |
|---|---|
| **`-t` meaning** | Per-packet wait time in **seconds**, passed to the underlying ping binary as `-W N`. |
| **Coroutine timeout** | Also set to `-t N` seconds (same value). |
| **Default count** | 4 packets (if `-c` omitted). |
| **Default timeout** | No per-packet `-W` (uses OS default). |
| **Example** | `ping -c 3 -t 5 google.com` → 3 packets, wait 5s per packet, coroutine timeout 5s. |

### `dig`

| Aspect | Detail |
|---|---|
| **`-t` meaning** | **Not parsed** as a special flag. `-t` is consumed only by the global `extractCommandTimeout()` for the coroutine timeout. |
| **Coroutine timeout** | Set to `-t N` milliseconds if present. |
| **DNS server** | Parsed from `@server` argument; defaults to `8.8.8.8`. |
| **Example** | `dig @1.1.1.1 example.com -t 15` → coroutine timeout 15s, DNS query to Cloudflare. |

### `ntp`

| Aspect | Detail |
|---|---|
| **`-t` meaning** | **Not parsed** as a special flag. Only the global coroutine timeout applies. |
| **Coroutine timeout** | Set to `-t N` milliseconds if present. |
| **Pool** | Parsed from `parts[1]`; defaults to `pool.ntp.org`. |
| **Example** | `ntp pool.ntp.org -t 10` → coroutine timeout 10s, NTP query to pool.ntp.org. |

### `port-scan`

| Aspect | Detail |
|---|---|
| **`-t` meaning** | **Connection timeout per port** in **seconds**, multiplied by 1000 → milliseconds for `Socket.connect()`. |
| **Coroutine timeout** | Also set to `-t N` milliseconds (same value). |
| **Default** | 2000ms per port if `-t` omitted. |
| **Host parsing** | Host is the first non-flag token after `-p ports` (or after `-t N` if present). |
| **Example** | `port-scan -p 80,443 -t 3 google.com` → 3s connect timeout per port, coroutine timeout 3s. |

### `checkcert`

| Aspect | Detail |
|---|---|
| **`-t` meaning** | **Not passed** to the HTTPS cert I/O. Only the global coroutine timeout applies. |
| **Coroutine timeout** | Set to `-t N` milliseconds if present. |
| **Port** | Parsed from `-p N`; defaults to `443`. |
| **Host parsing** | If `-t` is between `-p` and host, skips it: `checkcert -p 443 -t 5 google.com` → host = `"google.com"`. |
| **Example** | `checkcert -p 443 -t 5 google.com` → coroutine timeout 5s, cert check on google.com:443. |

### `device-info`

| Aspect | Detail |
|---|---|
| **`-t` meaning** | **Ignored.** No timeout parameter accepted. |
| **Coroutine timeout** | Falls back to config-level `timeout` or 30s default. |
| **Usage** | `device-info` (no arguments). |

### `tracert`

| Aspect | Detail |
|---|---|
| **`-t` meaning** | **Max hops** (TTL probe limit), **not** a timeout. Defaults to 30 if omitted. |
| **Coroutine timeout** | Set to `-t N` milliseconds if present (conflicts with hop semantics — see note below). |
| **Internal ping** | Each hop uses `ping -c 1 -t <TTL> -W 2 <host>` internally. |
| **⚠️ Semantic conflict** | If user writes `tracert google.com -t 10`, `-t` is interpreted as **10 hops** (for the traceroute logic) **and** 10s coroutine timeout. This is usually fine but can be confusing. |
| **Example** | `tracert google.com -t 20` → probe up to 20 hops, coroutine timeout 20s. |

### `google-timesync`

| Aspect | Detail |
|---|---|
| **`-t` meaning** | **Ignored.** No timeout parameter accepted. |
| **Coroutine timeout** | Falls back to config-level `timeout` or 30s default. |
| **Usage** | `google-timesync` (no arguments). |

### `lan-scan`

| Aspect | Detail |
|---|---|
| **`-t` meaning** | **Ignored.** No timeout parameter accepted. |
| **Coroutine timeout** | Fallsback to config-level `timeout` or 30s default. |
| **Usage** | `lan-scan` (no arguments). |

---

## Config-Level Timeout

The bulk config JSON supports a top-level `"timeout"` field (in **seconds**):

```json
{
  "timeout": 42,
  "run": {
    "ping_google": "ping -c 1 google.com -t 10"
  }
}
```

- `"timeout": 42` → default 42s for all commands **that don't have their own `-t`**.
- Per-command `-t` **overrides** the config-level timeout.
- If neither exists, defaults to **30 seconds**.

---

## Summary Table

| Pseudo-command | `-t` parsed? | `-t` unit | `-t` scope | Coroutine timeout affected? |
|---|---:|---|---|---:|
| `ping` | ✅ | seconds | Per-packet `-W` + coroutine | ✅ |
| `dig` | ✅ (coroutine only) | ms (×1000) | Coroutine only | ✅ |
| `ntp` | ✅ (coroutine only) | ms (×1000) | Coroutine only | ✅ |
| `port-scan` | ✅ | seconds | Per-port connect + coroutine | ✅ |
| `checkcert` | ✅ (coroutine only) | ms (×1000) | Coroutine only | ✅ |
| `device-info` | ❌ | — | — | ❌ |
| `tracert` | ✅ (as max hops) | hop count | Max hops + coroutine | ✅ (conflicts) |
| `google-timesync` | ❌ | — | — | ❌ |
| `lan-scan` | ❌ | — | — | ❌ |
