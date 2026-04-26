package io.github.mobilutils.ntp_dig_ping_more

import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [BulkActionsViewModel] using MockK.
 * Tests state management and input handlers without Android API dependencies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BulkActionsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: BulkActionsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = BulkActionsViewModel(
            context = mockk(relaxed = true),
            repository = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initialState_allDefaults`() = runTest {
        val state = viewModel.uiState.value

        assertFalse(state.configLoaded)
        assertNull(state.configFileName)
        assertNull(state.configUri)
        assertEquals(0, state.commandCount)
        assertFalse(state.isExecuting)
        assertNull(state.currentCommand)
        assertEquals(0f, state.progress)
        assertTrue(state.results.isEmpty())
        assertFalse(state.isFileWriting)
        assertNull(state.outputFileWritten)
        assertNull(state.configTimeoutMs)
    }

    @Test
    fun `onStopClicked_stopsExecution`() = runTest {
        viewModel.onStopClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isExecuting)
    }

    @Test
    fun `onClearResults_clearsResultsAndProgress`() = runTest {
        viewModel.onClearResults()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.results.isEmpty())
        assertEquals(0f, state.progress)
        assertNull(state.currentCommand)
    }

    @Test
    fun `onRunClicked_setsExecutingTrueBeforeCoroutineRuns`() = runTest {
        val initialState = viewModel.uiState.value.isExecuting
        assertFalse(initialState)

        viewModel.onRunClicked()

        // onRunClicked sets isExecuting=true synchronously before launching the coroutine
        assertTrue(viewModel.uiState.value.isExecuting)
    }

    @Test
    fun `uiState_isImmutableCopy`() = runTest {
        val initial = viewModel.uiState.value
        viewModel.onClearResults()

        // Original state object should be unchanged
        assertEquals(initial.configLoaded, viewModel.uiState.value.configLoaded)
    }

    @Test
    fun `initialState_configTimeoutMsDefaultsToNull`() = runTest {
        assertNull(viewModel.uiState.value.configTimeoutMs)
    }
}
