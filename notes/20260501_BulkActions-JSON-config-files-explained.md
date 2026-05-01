# Bulk Actions JSON Config Files — Reference Guide

> Generated: 2026-05-01
> Directory: `notes/config-files_bulk-actions/`
> Archive: `notes/config-files_bulk-actions/_archive/` (16 legacy files)

---

## 1. Naming Convention

Every file follows the pattern:

```
blkacts_<scope>_<commands>_<outcome>[-<variant>].json
```

| Segment | Values |
|---|---|
| **`<scope>`** | `single` — one command; `multi` — two or more; `full` — all 9 commands |
| **`<commands>`** | `ping`, `dig`, `ntp`, `portscan`, `checkcert`, `devinfo`, `tracert`, `googletimesync`, `lanscan` (comma-separated for multi) |
| **`<outcome>`** | `success`, `error`, `timeout`, `nxdomain`, `closed`, `mixed`, `partial` |
| **`<variant>`** | `noshort`, `long`, `csv`, `tilde`, `saf`, `nooutput`, `invalid`, `syntax`, `range`, `fullrange` |

**Legacy** files (pre-renaming) are prefixed `blkacts_legacy_` and live in the root alongside the new set. They preserve the original `nmap`-prefix syntax for backward-compatibility testing.

---

## 2. File Inventory (65 files)

### 2.1 Single-command smoke tests (14 files)

| File | Command | Expected outcome |
|---|---|---|
| `blkacts_single_ping_success.json` | `ping -c 2 google.com` | SUCCESS |
| `blkacts_single_dig_success.json` | `dig @8.8.8.8 google.com` | SUCCESS |
| `blkacts_single_dig_nxdomain.json` | `dig @8.8.8.8 nonexistent.invalid` | ERROR (NXDOMAIN) |
| `blkacts_single_ntp_success.json` | `ntp pool.ntp.org` | SUCCESS |
| `blkacts_single_portscan_open.json` | `port-scan -p 80 google.com` | SUCCESS (port 80 open) |
| `blkacts_single_portscan_closed.json` | `port-scan -p 1-10 127.0.0.1` | CLOSED (no open ports) |
| `blkacts_single_portscan_nxdomain.json` | `port-scan 80 nonexistent.invalid` | ERROR (host not found) |
| `blkacts_single_checkcert_success.json` | `checkcert -p 443 google.com` | SUCCESS |
| `blkacts_single_checkcert_badhost.json` | `checkcert -p 443 nonexistent.invalid` | ERROR |
| `blkacts_single_devinfo_success.json` | `device-info` | SUCCESS |
| `blkacts_single_tracert_success.json` | `tracert -t 5 google.com` | SUCCESS |
| `blkacts_single_tracert_badhost.json` | `tracert -t 5 nonexistent.invalid` | ERROR |
| `blkacts_single_googletimesync_success.json` | `google-timesync` | SUCCESS |
| `blkacts_single_lanscan_success.json` | `lan-scan` | SUCCESS or ERROR (no WiFi) |

### 2.2 Multi-command combos (10 files)

| File | Commands | Expected outcome |
|---|---|---|
| `blkacts_multi_ping_dig_success.json` | ping + dig | Both SUCCESS |
| `blkacts_multi_ping_ntp_success.json` | ping + ntp | Both SUCCESS |
| `blkacts_multi_dig_ntp_success.json` | dig + ntp | Both SUCCESS |
| `blkacts_multi_ping_portscan_success.json` | ping + port-scan | Both SUCCESS |
| `blkacts_multi_ping_checkcert_success.json` | ping + checkcert | Both SUCCESS |
| `blkacts_multi_ping_tracert_success.json` | ping + tracert | Both SUCCESS |
| `blkacts_multi_dig_portscan_mixed.json` | dig (success) + portscan (bad host) | Mixed success/error |
| `blkacts_multi_devinfo_portscan_success.json` | device-info + port-scan | Both SUCCESS |
| `blkacts_multi_all9_success.json` | All 9 commands | Mostly SUCCESS |
| `blkacts_multi_all9_mixed.json` | All 9 with bad hosts | Mixed success/error |

### 2.3 Timeout scenarios (5 files)

| File | Timeout mechanism | Expected outcome |
|---|---|---|
| `blkacts_timeout_config_level.json` | Config-level `timeout: 1` | TIMEOUT on both commands |
| `blkacts_timeout_command_level.json` | Inline `-t 1` on one command | First SUCCESS, second TIMEOUT |
| `blkacts_timeout_mixed.json` | Inline `-t 1` on tracert | ping SUCCESS, tracert TIMEOUT |
| `blkacts_timeout_portscan_slow.json` | `-t 1` on slow port range | TIMEOUT (unreachable host) |
| `blkacts_timeout_dig_slow.json` | `-t 1` on unreachable DNS server | TIMEOUT |

### 2.4 CSV output (4 files)

| File | Commands | CSV flag |
|---|---|---|
| `blkacts_csv_single_ping.json` | ping | `output-as-csv: "true"` |
| `blkacts_csv_multi_commands.json` | ping + dig + ntp | `output-as-csv: "true"` |
| `blkacts_csv_mixed_results.json` | ping (good) + dig (bad) | `output-as-csv: "true"` |
| `blkacts_csv_full_all9.json` | All 9 commands | `output-as-csv: "true"` |

### 2.5 Edge cases (11 files)

| File | Scenario | Purpose |
|---|---|---|
| `blkacts_edge_empty_run.json` | `"run": {}` | Empty command map — no commands to execute |
| `blkacts_edge_no_output_file.json` | No `output-file` key | In-memory results only, no file write |
| `blkacts_edge_tilde_path.json` | `output-file: "~/Downloads/…"` | Tilde expansion to external storage |
| `blkacts_edge_absolute_path.json` | `output-file: "/tmp/…"` | Absolute path handling |
| `blkacts_edge_saf_path.json` | `output-file: "/sdcard/Download/…"` | SAF (Storage Access Framework) path |
| `blkacts_edge_zero_timeout.json` | `"timeout": 0` | Treated as no timeout (defaults to 30s) |
| `blkacts_edge_negative_timeout.json` | `"timeout": -5` | Treated as no timeout (ignored) |
| `blkacts_edge_blank_commands.json` | Commands with `""` and `"  "` values | Blank commands are filtered out |
| `blkacts_edge_whitespace_trimmed.json` | Command with leading/trailing spaces | Values are trimmed |
| `blkacts_edge_single_command.json` | One command, no numbering | Minimal valid config |
| `blkacts_edge_many_commands.json` | 21 ping commands | Large command map parsing |

### 2.6 Invalid / malformed (6 files)

| File | Scenario | Expected error |
|---|---|---|
| `blkacts_invalid_missing_run.json` | No `run` key | `IllegalArgumentException: Missing required 'run' object` |
| `blkacts_invalid_not_json.json` | Plain text, not JSON | Parse failure |
| `blkacts_invalid_broken_json.json` | Missing closing `}` | JSON syntax error |
| `blkacts_invalid_empty_file.json` | Empty file | Parse failure |
| `blkacts_invalid_no_commands.json` | `"run": null` | Parse failure |
| `blkacts_invalid_toocomplexe.json` | Array-based `run` (old format) | Parse failure — only object-based `run` is supported |

### 2.7 Legacy renames (16 files)

These are the original files, renamed to `blkacts_` prefix and moved from their old names. The originals are preserved in `_archive/`.

| New name | Original name | Notes |
|---|---|---|
| `blkacts_single_portscan_fullrange.json` | `blkacts_port-scan_google1-65534.json` | Full port range scan |
| `blkacts_multi_portscan_range.json` | `blkacts_port-scan_range.json` | Multi port range |
| `blkacts_single_devinfo_outfile.json` | `blkacts-deviceinfo-outfile.json` | device-info + port-scan |
| `blkacts_multi_devinfo_portscan_csv.json` | `blkacts-devinfo+portscan-outputascsv.json` | CSV output |
| `blkacts_single_ping_only.json` | `blkacts-ping-only.json` | Ping-only suite |
| `blkacts_single_portscan_only.json` | `blkacts-portscan_only.json` | Port-scan variants |
| `blkacts_single_portscan_csv.json` | `blkacts-portscan-outputascsv.json` | Port-scan CSV |
| `blkacts_legacy_nmap_one.json` | `bulk-nmap_one.json` | Old `nmap` prefix |
| `blkacts_full_timeout.json` | `bulkactions-complete-with-timeout.json` | Full suite with timeout |
| `blkacts_full_example.json` | `bulkactions-full-example.json` | Full example |
| `blkacts_no_output_file.json` | `bulkactions-no_output-file_defined.json` | No output file |
| `blkacts_small_nooutput_saf.json` | `bulkactions-small-nooutput_SAF.json` | Small, no output |
| `blkacts_small_output_file.json` | `bulkactions-small-output-file_defined.json` | Small with output |
| `blkacts_invalid_toocomplexe.json` | `bulkactions-toocomplexe.json` | Invalid structure |
| `blkacts_multi_timeout.json` | `bulkactions-with-timeout.json` | Multi with timeout |
| `blkacts_legacy_bulkactions1.json` | `bulkactions1.json` | Legacy large config |

---

## 3. JSON Schema Reference

```json
{
  "output-file": "<optional string, path to write results>",
  "output-as-csv": "<optional boolean string, \"true\" or \"false\">",
  "timeout": "<optional integer, seconds for per-command timeout>",
  "run": {
    "<command-name>": "<command-string>",
    ...
  }
}
```

### Top-level fields

| Field | Type | Required | Description |
|---|---|---|---|
| `output-file` | string | No | File path for results. `~` is expanded to external storage dir. |
| `output-as-csv` | string (`"true"` / `"false"`) | No | If `"true"`, output is CSV format instead of plain text. |
| `timeout` | integer (seconds) | No | Default per-command timeout. Overridden by inline `-t N`. Zero or negative → defaults to 30 s. |
| `run` | object | **Yes** | Map of command-name → command-string. Empty object is valid (zero commands). |

### Supported command strings

| Prefix | Example | Description |
|---|---|---|
| `ping` | `ping -c 4 -t 10 google.com` | ICMP ping |
| `dig` | `dig @8.8.8.8 google.com` | DNS lookup |
| `ntp` | `ntp pool.ntp.org` | NTP time query |
| `port-scan` | `port-scan -p 80,443 google.com` | TCP port scan |
| `checkcert` | `checkcert -p 443 google.com` | HTTPS certificate check |
| `device-info` | `device-info` | Device identity/network/battery/storage |
| `tracert` | `tracert -t 20 google.com` | TTL-probing traceroute |
| `google-timesync` | `google-timesync` | Google time sync |
| `lan-scan` | `lan-scan` | LAN device discovery |

---

## 4. Directory Structure

```
notes/config-files_bulk-actions/
├── _archive/                          ← 16 legacy files (pre-renaming)
│   ├── blkacts_port-scan_google1-65534.json
│   ├── blkacts_port-scan_range.json
│   ├── blkacts-deviceinfo-outfile.json
│   ├── blkacts-devinfo+portscan-outputascsv.json
│   ├── blkacts-ping-only.json
│   ├── blkacts-portscan_only.json
│   ├── blkacts-portscan-outputascsv.json
│   ├── bulk-nmap_one.json
│   ├── bulkactions-complete-with-timeout.json
│   ├── bulkactions-full-example.json
│   ├── bulkactions-no_output-file_defined.json
│   ├── bulkactions-small-nooutput_SAF.json
│   ├── bulkactions-small-output-file_defined.json
│   ├── bulkactions-toocomplexe.json
│   ├── bulkactions-with-timeout.json
│   └── bulkactions1.json
├── blkacts_single_*.*.json           ← 14 single-command smoke tests
├── blkacts_multi_*.*.json            ← 10 multi-command combos
├── blkacts_timeout_*.*.json          ← 5 timeout scenarios
├── blkacts_csv_*.*.json              ← 4 CSV output tests
├── blkacts_edge_*.*.json             ← 11 edge-case tests
├── blkacts_invalid_*.*.json          ← 6 invalid/malformed tests
├── blkacts_legacy_*.*.json           ← 16 renamed legacy files
├── blkacts_full_*.*.json             ← 2 full-suite examples
└── blkacts_no_output_file.json       ← No output file variant
└── blkacts_small_*.*.json            ← 2 small config variants
```

---

## 5. Test Coverage Summary

| Category | Files | What it validates |
|---|---|---|
| **Single-command smoke** | 14 | Each of the 9 commands, plus success/error/closed states |
| **Multi-command combos** | 10 | Sequential execution, mixed outcomes |
| **Timeout scenarios** | 5 | Config-level vs. command-level timeout, mixed success/timeout |
| **CSV output** | 4 | CSV format generation for single, multi, mixed, and full suites |
| **Edge cases** | 11 | Empty run, no output file, tilde/absolute/SAF paths, zero/negative timeout, blank commands, whitespace trimming, single command, large maps |
| **Invalid/malformed** | 6 | Missing run key, non-JSON, broken JSON, empty file, null run, array-based run |
| **Legacy renames** | 16 | Backward compatibility, `nmap` → `port-scan` migration |
| **Full suite** | 2 | All 9 commands, success and mixed outcomes |
| **Total** | **65** | |
