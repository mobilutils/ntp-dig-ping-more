# LanScannerViewModelTest Fixes — 2026-04-28

## Overview

Two failing tests in `LanScannerViewModelTest` were caused by missing `ipToLong` and `longToIp` mock stubs. Both tests used `mockk<LanScannerRepository>(relaxed = true)` without explicitly setting up these methods, causing `startScan` to operate on a zero-length IP range instead of the intended scan range.

## Root Cause

When a relaxed MockK is used without stubbing `ipToLong()`, the method returns `0L` by default. In `LanScannerViewModel.startScan()`:

```kotlin
val startLong = try { repository.ipToLong(startStr) } catch (e: Exception) { null }
val endLong = try { repository.ipToLong(endStr) } catch (e: Exception) { null }
```

With `startLong = 0L` and `endLong = 0L`, the range becomes `0L..0L` (a single IP at `"0.0.0.0"`), rather than the intended `192.168.1.x` range. This cascaded into two distinct assertion failures:

---

## Fix 1: `full scan uses all IPs in range`

**File:** `app/src/test/java/io/github/mobilutils/ntp_dig_ping_more/LanScannerViewModelTest.kt` (line ~345)

**Failure:** `expected:<10> but was:<1>`

**Why:** The test sets `startIp = "192.168.1.1"` and `endIp = "192.168.1.10"`, expecting 10 IPs in the scan range. But `ipToLong` was unstubbed (returned `0L`), so the range was `0L..0L` → 1 IP.

**Fix:** Added `ipToLong` and `longToIp` mock answers before creating the ViewModel:

```kotlin
every { repository.ipToLong(any()) } answers {
    val ip = firstArg<String>()
    val parts = ip.split(".")
    (parts[0].toLong() shl 24) or (parts[1].toLong() shl 16) or (parts[2].toLong() shl 8) or parts[3].toLong()
}
every { repository.longToIp(any()) } answers {
    val ipLong = firstArg<Long>()
    "${(ipLong shr 24) and 0xff}.${(ipLong shr 16) and 0xff}.${(ipLong shr 8) and 0xff}.${ipLong and 0xff}"
}
```

---

## Fix 2: `activeDevices is populated when ping succeeds`

**File:** `app/src/test/java/io/github/mobilutils/ntp_dig_ping_more/LanScannerViewModelTest.kt` (line ~535)

**Failure:** `expected:<[192.168.1.1]> but was:<[]>`

**Why:** Same `ipToLong` issue — the scanned IP was `"0.0.0.0"` instead of `"192.168.1.1"`, so the stubbed `coEvery { repository.ping("192.168.1.1") } returns 5` never matched. No devices were added to `activeDevices`.

**Fix:** Same `ipToLong`/`longToIp` mock answers added before ViewModel creation (same code block as Fix 1).

---

## Key Takeaway

Tests that create their own `mockk<LanScannerRepository>(relaxed = true)` must explicitly stub `ipToLong()` and `longToIp()` — these are called synchronously during `startScan()` setup (before any coroutines run). The existing `createViewModel()` helper already had these stubs; these two tests bypassed it by constructing mocks inline.

## Verification

```bash
./gradlew testDebugUnitTest
# Result: BUILD SUCCESSFUL — 0 failures in LanScannerViewModelTest
```
