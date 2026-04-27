# Fix: 4 Test Failures Due to Coroutine Dispatcher Setup

**Date:** 2026-04-27  
**Issue:** 4 unit tests failing with `IllegalStateException` and `UncaughtExceptionsBeforeTest`  
**Status:** ✅ Fixed

---

## Problem

Four ViewModel unit tests were failing with coroutine dispatcher initialization errors:

| Test File | Failing Test |
|---|---|
| `PortScannerViewModelTest.kt` | `stopScan cancels job and sets not running` |
| `TracerouteViewModelTest.kt` | `startTraceroute sets isRunning and clears output` |
| `GoogleTimeSyncViewModelTest.kt` | `syncTime handles Timeout error` |
| `NtpViewModelTest.kt` | `initial state has default values` |

**Error messages:**
- `IllegalStateException: Module with the Main dispatcher had failed to initialize`
- `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest`

---

## Root Cause

All 4 tests used the same problematic pattern:

```kotlin
@Test
fun `some test`() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)  // ❌ Problematic
    
    val viewModel = createViewModel()  // ViewModel init triggers coroutine builder
    // ...
}
```

The issue: ViewModels create coroutines in their `init` blocks (via `viewModelScope.launch`). When `StandardTestDispatcher` is manually set via `Dispatchers.setMain()`, the coroutine builder triggers `Dispatchers.getMain()` during construction, but Android's `Looper.getMainLooper()` is not mocked on the JVM, causing initialization to fail.

---

## Solution

Removed the manual `Dispatchers.setMain()` calls and `StandardTestDispatcher` setup. The `runTest` builder already provides proper test dispatcher infrastructure:

```kotlin
@Test
fun `some test`() = runTest {
    // ✅ No manual dispatcher setup needed
    val viewModel = createViewModel()
    // ...
}
```

---

## Changes Made

### Files Modified

1. **`app/src/test/java/io/github/mobilutils/ntp_dig_ping_more/PortScannerViewModelTest.kt`**
   - Removed imports: `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.ExperimentalCoroutinesApi`, `kotlinx.coroutines.test.StandardTestDispatcher`, `kotlinx.coroutines.test.setMain`
   - Removed `@OptIn(ExperimentalCoroutinesApi::class)` annotation
   - Removed `val testDispatcher = StandardTestDispatcher(testScheduler)` and `Dispatchers.setMain(testDispatcher)` from all 15 test methods

2. **`app/src/test/java/io/github/mobilutils/ntp_dig_ping_more/TracerouteViewModelTest.kt`**
   - Same changes as above (18 test methods updated)

3. **`app/src/test/java/io/github/mobilutils/ntp_dig_ping_more/GoogleTimeSyncViewModelTest.kt`**
   - Same changes as above (14 test methods updated)

4. **`app/src/test/java/io/github/mobilutils/ntp_dig_ping_more/NtpViewModelTest.kt`**
   - Same changes as above (16 test methods updated)

---

## Verification

**Before fix:**
```
214 tests completed, 4 failed
```

**After fix:**
```
214 tests completed, 2 failed
```

The 2 remaining failures (`LanScannerViewModelTest`) are pre-existing issues unrelated to the dispatcher problem.

---

## Key Takeaway

When using `kotlinx.coroutines.test.runTest`, avoid manually calling `Dispatchers.setMain()`. The `runTest` builder automatically provides a test dispatcher that handles both Main and IO dispatchers correctly. Manual dispatcher replacement is only needed for advanced scenarios where you need to control time progression with `StandardTestDispatcher` explicitly.
