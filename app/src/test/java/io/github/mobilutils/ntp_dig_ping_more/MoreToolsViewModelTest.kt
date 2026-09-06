package io.github.mobilutils.ntp_dig_ping_more

import io.github.mobilutils.ntp_dig_ping_more.settings.ManagedConfigRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MoreToolsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initialState_noMdmConfig_isMdmConfiguredIsFalse`() = runTest {
        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true) {
            every { hasMdmConfig } returns false
            every { isAppManagedFlow } returns MutableStateFlow(false)
        }

        val viewModel = MoreToolsViewModel(managedConfigRepository = mockRepo)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isMdmConfigured)
    }

    @Test
    fun `initialState_withMdmConfig_isMdmConfiguredIsTrue`() = runTest {
        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true) {
            every { hasMdmConfig } returns true
            every { isAppManagedFlow } returns MutableStateFlow(true)
        }

        val viewModel = MoreToolsViewModel(managedConfigRepository = mockRepo)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isMdmConfigured)
    }

    @Test
    fun `liveUpdate_mdmConfigArrives_updatesToTrue`() = runTest {
        val flow = MutableStateFlow(false)
        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true) {
            every { hasMdmConfig } returns false
            every { isAppManagedFlow } returns flow
        }

        val viewModel = MoreToolsViewModel(managedConfigRepository = mockRepo)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isMdmConfigured)

        flow.value = true
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isMdmConfigured)
    }

    @Test
    fun `liveUpdate_mdmConfigCleared_updatesToFalse`() = runTest {
        val flow = MutableStateFlow(true)
        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true) {
            every { hasMdmConfig } returns true
            every { isAppManagedFlow } returns flow
        }

        val viewModel = MoreToolsViewModel(managedConfigRepository = mockRepo)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isMdmConfigured)

        flow.value = false
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isMdmConfigured)
    }

    @Test
    fun `nullRepository_defaultsToFalse`() = runTest {
        val viewModel = MoreToolsViewModel(managedConfigRepository = null)
        assertFalse(viewModel.uiState.value.isMdmConfigured)
    }
}
