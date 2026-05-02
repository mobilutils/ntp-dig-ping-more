# BulkActionsViewModelTest — 6 NPE Failures: Root Cause & Fix

**Date:** 2026-05-01
**Status:** ✅ Resolved

## The 6 Failures

All 6 failures occurred at the **same line** — `BulkActionsViewModelTest.kt:31`, which is the `BulkActionsViewModel(...)` constructor call inside the `@Before setup()` method. None of the individual test bodies were even reached.

| # | Test Name | What It Verifies |
|---|---|---|
| 1 | `initialState_allDefaults` | All `BulkUiState` fields have correct default values |
| 2 | `initialState_configTimeoutMsDefaultsToNull` | `configTimeoutMs` defaults to `null` |
| 3 | `onRunClicked_setsExecutingTrueBeforeCoroutineRuns` | `isExecuting` becomes `true` synchronously before the coroutine starts |
| 4 | `onStopClicked_stopsExecution` | `onStopClicked()` sets `isExecuting` to `false` |
| 5 | `onClearResults_clearsResultsAndProgress` | `onClearResults()` resets results, progress, and current command |
| 6 | `uiState_isImmutableCopy` | Original state object is unchanged after `onClearResults()` |

All failed with: `java.lang.NullPointerException at BulkActionsViewModelTest.kt:31`

## Root Cause

`BulkActionsViewModel` has an **eager** property initializer:

```kotlin
class BulkActionsViewModel(
    private val context: Context,
    private val repository: BulkActionsRepository,
) : ViewModel() {

    // ⚠ EAGER — executes at construction time
    private val runningFile = File(context.filesDir, ".running-tasks")
    // …
}
```

In the test's `setup()`, `context` is a **relaxed MockK**:

```kotlin
viewModel = BulkActionsViewModel(
    context = mockk(relaxed = true),
    repository = mockk(relaxed = true),
)
```

A relaxed MockK returns default values for intercepted method calls, but **property getters** like `context.filesDir` are **not intercepted** — they return `null`. Passing `null` to `File(null, ".running-tasks")` throws `NullPointerException` **during construction**, before any test body executes.

This is why all 6 tests fail at exactly the same location — the NPE happens in `@Before.setup()`, not inside any individual test.

## Why This Didn't Affect Production

In production, `context` is a real Android `Context` (specifically `context.applicationContext` from the ViewModel factory). `context.filesDir` always returns a valid `File` path (e.g., `/data/user/0/io.github.mobilutils.ntp_dig_ping_more/files`). The NPE is **test-only**.

## ADB Automation Impact

**No impact.** The ADB automation script (`BULKACTIONS-ADB-SCRIPT.sh`) polls for the `.running-tasks` marker file via:

```bash
adb shell "run-as $APP_ID test -f $PRIVATE_DIR/.running-tasks"
```

The file is created by `createRunningFile()` and deleted by `deleteRunningFile()`, both of which are called from `onRunClicked()`, `onLoadAndRun()`, and `onStopClicked()` — **never at ViewModel construction time**. Lazy evaluation doesn't change this timing.

## Fix Applied

**Option retained: Lazy initialization** — change the eager property to a `lazy` delegate so the `File` object is only created when first accessed (i.e., when `createRunningFile()` or `deleteRunningFile()` is actually called).

### Diff

```kotlin
// Before (eager — NPE at construction with mocked Context):
private val runningFile = File(context.filesDir, ".running-tasks")

// After (lazy — evaluated only on first access):
private val runningFile: File by lazy {
    File(context.filesDir, ".running-tasks")
}
```

### Why This Option

- **Minimal change:** One property, no test code changes needed.
- **Semantically correct:** The `File` object is only needed when `createRunningFile()` is called, not at construction.
- **Production behavior unchanged:** `lazy` evaluates on first access, which happens at the same moment as before.
- **Test behavior fixed:** In tests, `runningFile` is never accessed (no test calls `createRunningFile()`), so it stays uninitialized — no NPE.

## Verification

```bash
# All 6 BulkActionsViewModelTest tests pass
./gradlew testDebugUnitTest --tests "io.github.mobilutils.ntp_dig_ping_more.BulkActionsViewModelTest"

# Full suite: 210 passed, 0 failed
./gradlew testDebugUnitTest
```
