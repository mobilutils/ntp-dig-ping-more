package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import io.github.mobilutils.ntp_dig_ping_more.settings.ManagedConfig
import io.github.mobilutils.ntp_dig_ping_more.settings.ManagedConfigRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
        mockkObject(BulkConfigParser)
        val mockContext = mockk<Context>(relaxed = true)
        every { mockContext.contentResolver.openInputStream(any()) } returns null
        viewModel = BulkActionsViewModel(
            context = mockContext,
            repository = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        unmockkObject(BulkConfigParser)
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
        assertFalse(state.loadMdmChecked)
        assertFalse(state.hasMdmBulkActions)
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

    // ────────────────────────────────────────────────────────────────
    // MDM Bulk Actions tests
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `setLoadMdmChecked_whenNoManagedConfigRepository_leavesConfigUnloaded`() = runTest {
        viewModel.setLoadMdmChecked(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.loadMdmChecked)
        assertFalse(viewModel.uiState.value.configLoaded)
        assertEquals(0, viewModel.uiState.value.commandCount)
    }

    @Test
    fun `setLoadMdmChecked_whenMdmJsonNull_leavesConfigUnloaded`() = runTest {
        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true)
        every { mockRepo.configFlow } returns MutableStateFlow(ManagedConfig(bulkActionsJson = null))
        val vm = BulkActionsViewModel(
            context = mockk(relaxed = true),
            repository = mockk(relaxed = true),
            managedConfigRepository = mockRepo,
        )

        vm.setLoadMdmChecked(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.loadMdmChecked)
        assertFalse(vm.uiState.value.configLoaded)
        assertEquals(0, vm.uiState.value.commandCount)
    }

    @Test
    fun `setLoadMdmChecked_whenMdmJsonBlank_leavesConfigUnloaded`() = runTest {
        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true)
        every { mockRepo.configFlow } returns MutableStateFlow(ManagedConfig(bulkActionsJson = "   "))
        val vm = BulkActionsViewModel(
            context = mockk(relaxed = true),
            repository = mockk(relaxed = true),
            managedConfigRepository = mockRepo,
        )

        vm.setLoadMdmChecked(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.loadMdmChecked)
        assertFalse(vm.uiState.value.configLoaded)
        assertEquals(0, vm.uiState.value.commandCount)
    }

    @Test
    fun `setLoadMdmChecked_whenMdmJsonValid_loadsConfigAndSetsCommandCount`() = runTest {
        val validJson = """{"run":{"cmd1":"ping -c 2 google.com","cmd2":"dig @1.1.1.1 example.com"}}"""
        every { BulkConfigParser.parse(validJson) } returns BulkConfig(
            outputFile = null,
            commands = mapOf("cmd1" to "ping -c 2 google.com", "cmd2" to "dig @1.1.1.1 example.com"),
        )

        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true)
        every { mockRepo.configFlow } returns MutableStateFlow(ManagedConfig(bulkActionsJson = validJson))
        val vm = BulkActionsViewModel(
            context = mockk(relaxed = true),
            repository = mockk(relaxed = true),
            managedConfigRepository = mockRepo,
        )

        vm.setLoadMdmChecked(true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.loadMdmChecked)
        assertTrue(state.configLoaded)
        assertEquals(2, state.commandCount)
        assertEquals("Bulk Actions JSON Config", state.configFileName)
    }

    @Test
    fun `setLoadMdmChecked_whenMdmJsonMalformed_setsErrorValidationMessage`() = runTest {
        val malformedJson = "{ not valid json }"
        every { BulkConfigParser.parse(malformedJson) } throws IllegalArgumentException("Malformed JSON")

        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true)
        every { mockRepo.configFlow } returns MutableStateFlow(ManagedConfig(bulkActionsJson = malformedJson))
        val vm = BulkActionsViewModel(
            context = mockk(relaxed = true),
            repository = mockk(relaxed = true),
            managedConfigRepository = mockRepo,
        )

        vm.setLoadMdmChecked(true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.loadMdmChecked)
        assertFalse(state.configLoaded)
        assertEquals(0, state.commandCount)
        assertTrue(state.validationMessage is BulkActionsViewModel.ValidationMessage.Error)
    }

    @Test
    fun `setLoadMdmChecked_whenMdmJsonEmptyCommands_leavesConfigUnloaded`() = runTest {
        val emptyCommandsJson = """{"run":{}}"""
        every { BulkConfigParser.parse(emptyCommandsJson) } returns BulkConfig(
            outputFile = null,
            commands = emptyMap(),
        )

        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true)
        every { mockRepo.configFlow } returns MutableStateFlow(ManagedConfig(bulkActionsJson = emptyCommandsJson))
        val vm = BulkActionsViewModel(
            context = mockk(relaxed = true),
            repository = mockk(relaxed = true),
            managedConfigRepository = mockRepo,
        )

        vm.setLoadMdmChecked(true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.loadMdmChecked)
        assertFalse(state.configLoaded)
        assertEquals(0, state.commandCount)
    }

    @Test
    fun `setLoadMdmChecked_uncheck_clearsMdmConfig`() = runTest {
        val validJson = """{"run":{"cmd1":"ping -c 2 google.com"}}"""
        every { BulkConfigParser.parse(validJson) } returns BulkConfig(
            outputFile = null,
            commands = mapOf("cmd1" to "ping -c 2 google.com"),
        )

        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true)
        every { mockRepo.configFlow } returns MutableStateFlow(ManagedConfig(bulkActionsJson = validJson))
        val vm = BulkActionsViewModel(
            context = mockk(relaxed = true),
            repository = mockk(relaxed = true),
            managedConfigRepository = mockRepo,
        )

        vm.setLoadMdmChecked(true)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.configLoaded)

        vm.setLoadMdmChecked(false)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.loadMdmChecked)
        assertFalse(state.configLoaded)
        assertEquals(0, state.commandCount)
        assertNull(state.configFileName)
    }

    @Test
    fun `onRunClicked_whenMdmConfigLoaded_runsMdmBulkActionsPayload`() = runTest {
        val validJson = """{"run":{"ping_cmd":"ping -c 2 google.com"}}"""
        every { BulkConfigParser.parse(validJson) } returns BulkConfig(
            outputFile = null,
            commands = mapOf("ping_cmd" to "ping -c 2 google.com"),
        )

        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true)
        every { mockRepo.configFlow } returns MutableStateFlow(ManagedConfig(bulkActionsJson = validJson))
        val mockRepository = mockk<BulkActionsRepository>(relaxed = true)
        coEvery {
            mockRepository.executeSingleCommand("ping_cmd", "ping -c 2 google.com", any())
        } returns BulkCommandSuccess("ping_cmd", "ping -c 2 google.com", listOf("64 bytes from ..."), 15L)

        val vm = BulkActionsViewModel(
            context = mockk(relaxed = true),
            repository = mockRepository,
            managedConfigRepository = mockRepo,
        )

        vm.setLoadMdmChecked(true)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.configLoaded)

        vm.onRunClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isExecuting)
        assertEquals(1, state.results.size)
        assertEquals("ping_cmd", state.results[0].commandName)
        assertTrue(state.results[0] is BulkCommandSuccess)
        coVerify { mockRepository.executeSingleCommand("ping_cmd", "ping -c 2 google.com", any()) }
    }

    @Test
    fun `onFileSelected_resetsLoadMdmCheckedToFalse`() = runTest {
        viewModel.setLoadMdmChecked(true)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loadMdmChecked)

        viewModel.onFileSelected(mockk(relaxed = true), "custom_config.json")
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.loadMdmChecked)
    }

    @Test
    fun `mdmAutoRun_automaticallyChecksMdmAndRunsConfig`() = runTest {
        val validJson = """{"run":{"cmd_auto":"ping -c 1 127.0.0.1"}}"""
        every { BulkConfigParser.parse(validJson) } returns BulkConfig(
            outputFile = null,
            commands = mapOf("cmd_auto" to "ping -c 1 127.0.0.1"),
        )

        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true)
        every { mockRepo.configFlow } returns MutableStateFlow(
            ManagedConfig(bulkActionsJson = validJson, bulkActionsAutoRun = true)
        )
        val mockRepository = mockk<BulkActionsRepository>(relaxed = true)
        coEvery {
            mockRepository.executeSingleCommand("cmd_auto", any(), any())
        } returns BulkCommandSuccess("cmd_auto", "ping -c 1 127.0.0.1", listOf("1 packets received"), 5L)

        val vm = BulkActionsViewModel(
            context = mockk(relaxed = true),
            repository = mockRepository,
            managedConfigRepository = mockRepo,
        )

        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.loadMdmChecked)
        assertTrue(state.configLoaded)
        assertEquals(1, state.results.size)
        assertEquals("cmd_auto", state.results[0].commandName)
    }

    @Test
    fun `setLoadMdmChecked_true_clearsPreviousRunResults`() = runTest {
        val validJson = """{"run":{"cmd1":"ping -c 2 google.com"}}"""
        every { BulkConfigParser.parse(validJson) } returns BulkConfig(
            outputFile = null,
            commands = mapOf("cmd1" to "ping -c 2 google.com"),
        )

        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true)
        every { mockRepo.configFlow } returns MutableStateFlow(ManagedConfig(bulkActionsJson = validJson))
        val vm = BulkActionsViewModel(
            context = mockk(relaxed = true),
            repository = mockk(relaxed = true),
            managedConfigRepository = mockRepo,
        )

        // Seed previous results and progress into UI state
        vm._uiState.value = vm._uiState.value.copy(
            results = listOf(BulkCommandSuccess("prev_cmd", "ping 1.1.1.1", listOf("ok"), 10L)),
            progress = 1.0f,
            currentCommand = "prev_cmd",
            autoSaved = true,
            autoSavedPath = "/path/to/prev.txt",
        )
        assertEquals(1, vm.uiState.value.results.size)

        // Checking loadMdm must clear previous results as if Clear was clicked
        vm.setLoadMdmChecked(true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.loadMdmChecked)
        assertTrue(state.results.isEmpty())
        assertEquals(0f, state.progress)
        assertNull(state.currentCommand)
        assertFalse(state.autoSaved)
        assertNull(state.autoSavedPath)
    }

    @Test
    fun `setLoadMdmChecked_true_whenMdmMalformed_stillClearsPreviousRunResults`() = runTest {
        val malformedJson = "{ invalid"
        every { BulkConfigParser.parse(malformedJson) } throws IllegalArgumentException("Malformed JSON")

        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true)
        every { mockRepo.configFlow } returns MutableStateFlow(ManagedConfig(bulkActionsJson = malformedJson))
        val vm = BulkActionsViewModel(
            context = mockk(relaxed = true),
            repository = mockk(relaxed = true),
            managedConfigRepository = mockRepo,
        )

        vm._uiState.value = vm._uiState.value.copy(
            results = listOf(BulkCommandSuccess("old_cmd", "ping 1.1.1.1", listOf("ok"), 10L)),
            progress = 1.0f,
        )
        assertEquals(1, vm.uiState.value.results.size)

        vm.setLoadMdmChecked(true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.loadMdmChecked)
        assertTrue(state.results.isEmpty())
        assertEquals(0f, state.progress)
        assertTrue(state.validationMessage is BulkActionsViewModel.ValidationMessage.Error)
    }

    @Test
    fun `hasMdmBulkActions_whenNoRepo_isFalse`() = runTest {
        val vm = BulkActionsViewModel(
            context = mockk(relaxed = true),
            repository = mockk(relaxed = true),
            managedConfigRepository = null,
        )
        assertFalse(vm.uiState.value.hasMdmBulkActions)
    }

    @Test
    fun `hasMdmBulkActions_whenBulkActionsJsonNullOrBlank_isFalse`() = runTest {
        val mockRepoNull = mockk<ManagedConfigRepository>(relaxed = true)
        every { mockRepoNull.configFlow } returns MutableStateFlow(ManagedConfig(bulkActionsJson = null))
        val vmNull = BulkActionsViewModel(
            context = mockk(relaxed = true),
            repository = mockk(relaxed = true),
            managedConfigRepository = mockRepoNull,
        )
        assertFalse(vmNull.uiState.value.hasMdmBulkActions)

        val mockRepoBlank = mockk<ManagedConfigRepository>(relaxed = true)
        every { mockRepoBlank.configFlow } returns MutableStateFlow(ManagedConfig(bulkActionsJson = "   "))
        val vmBlank = BulkActionsViewModel(
            context = mockk(relaxed = true),
            repository = mockk(relaxed = true),
            managedConfigRepository = mockRepoBlank,
        )
        assertFalse(vmBlank.uiState.value.hasMdmBulkActions)
    }

    @Test
    fun `hasMdmBulkActions_whenBulkActionsJsonPresent_isTrue`() = runTest {
        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true)
        every { mockRepo.configFlow } returns MutableStateFlow(ManagedConfig(bulkActionsJson = """{"run":{"cmd":"ping"}}"""))
        val vm = BulkActionsViewModel(
            context = mockk(relaxed = true),
            repository = mockk(relaxed = true),
            managedConfigRepository = mockRepo,
        )
        assertTrue(vm.uiState.value.hasMdmBulkActions)
    }

    @Test
    fun `hasMdmBulkActions_liveUpdate_updatesState`() = runTest {
        val flow = MutableStateFlow(ManagedConfig(bulkActionsJson = """{"run":{"cmd":"ping"}}"""))
        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true)
        every { mockRepo.configFlow } returns flow
        val vm = BulkActionsViewModel(
            context = mockk(relaxed = true),
            repository = mockk(relaxed = true),
            managedConfigRepository = mockRepo,
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.hasMdmBulkActions)

        // Clear MDM config dynamically
        flow.value = ManagedConfig(bulkActionsJson = null)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.hasMdmBulkActions)
    }
}
