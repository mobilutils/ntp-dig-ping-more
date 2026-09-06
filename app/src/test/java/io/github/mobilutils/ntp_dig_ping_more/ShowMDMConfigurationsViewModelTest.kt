package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import io.github.mobilutils.ntp_dig_ping_more.settings.ManagedConfig
import io.github.mobilutils.ntp_dig_ping_more.settings.ManagedConfigRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShowMDMConfigurationsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val expected14Keys = listOf(
        "ntp_default_server",
        "ntp_default_port",
        "dig_default_server",
        "dig_default_fqdn",
        "ping_default_host",
        "port_scanner_default_host",
        "https_cert_default_host",
        "https_cert_default_port",
        "proxy_enabled",
        "proxy_pac_url",
        "proxy_logging_enabled",
        "bulk_actions_json",
        "bulk_actions_url",
        "bulk_actions_auto_run",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initialState_containsExactly14FieldsFromAppRestrictions`() = runTest {
        val viewModel = ShowMDMConfigurationsViewModel(managedConfigRepository = null)
        val state = viewModel.uiState.value

        assertEquals(14, state.totalCount)
        assertEquals(14, state.allFields.size)

        val actualKeys = state.allFields.map { it.key }
        assertEquals(expected14Keys, actualKeys)
    }

    @Test
    fun `initialState_unmanaged_allFieldsNotSet`() = runTest {
        val configFlow = MutableStateFlow(ManagedConfig())
        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true) {
            every { this@mockk.configFlow } returns configFlow
            every { hasMdmConfig } returns false
            every { isAppManagedFlow } returns MutableStateFlow(false)
        }

        val viewModel = ShowMDMConfigurationsViewModel(managedConfigRepository = mockRepo)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isManaged)
        assertEquals(0, state.configuredCount)
        assertEquals(14, state.displayedFields.size)
        assertTrue(state.allFields.all { !it.isSet })
    }

    @Test
    fun `initialState_configuredFields_correctValuesAndStatus`() = runTest {
        val config = ManagedConfig(
            ntpServer = "time.apple.com",
            ntpPort = "123",
            digServer = "1.1.1.1",
            pingHost = "192.168.1.1",
            proxyEnabled = true,
            bulkActionsAutoRun = true,
            bulkActionsAutoRunSet = true,
        )
        val configFlow = MutableStateFlow(config)
        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true) {
            every { this@mockk.configFlow } returns configFlow
            every { hasMdmConfig } returns true
            every { isAppManagedFlow } returns MutableStateFlow(true)
        }

        val viewModel = ShowMDMConfigurationsViewModel(managedConfigRepository = mockRepo)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isManaged)
        assertEquals(6, state.configuredCount)

        val ntpServerField = state.allFields.first { it.key == "ntp_default_server" }
        assertTrue(ntpServerField.isSet)
        assertEquals("time.apple.com", ntpServerField.value)

        val pingHostField = state.allFields.first { it.key == "ping_default_host" }
        assertTrue(pingHostField.isSet)
        assertEquals("192.168.1.1", pingHostField.value)

        val proxyEnabledField = state.allFields.first { it.key == "proxy_enabled" }
        assertTrue(proxyEnabledField.isSet)
        assertEquals("true", proxyEnabledField.value)

        val autoRunField = state.allFields.first { it.key == "bulk_actions_auto_run" }
        assertTrue(autoRunField.isSet)
        assertEquals("true", autoRunField.value)

        val digFqdnField = state.allFields.first { it.key == "dig_default_fqdn" }
        assertFalse(digFqdnField.isSet)
        assertEquals(null, digFqdnField.value)
    }

    @Test
    fun `filter_all_configured_notSet`() = runTest {
        val config = ManagedConfig(
            ntpServer = "pool.ntp.org",
            digServer = "8.8.8.8",
        )
        val configFlow = MutableStateFlow(config)
        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true) {
            every { this@mockk.configFlow } returns configFlow
            every { hasMdmConfig } returns true
            every { isAppManagedFlow } returns MutableStateFlow(true)
        }

        val viewModel = ShowMDMConfigurationsViewModel(managedConfigRepository = mockRepo)
        testDispatcher.scheduler.advanceUntilIdle()

        // Filter ALL
        assertEquals(14, viewModel.uiState.value.displayedFields.size)

        // Filter CONFIGURED
        viewModel.setFilter(ShowMdmConfigFilter.CONFIGURED)
        val configuredFields = viewModel.uiState.value.displayedFields
        assertEquals(2, configuredFields.size)
        assertTrue(configuredFields.all { it.isSet })
        assertTrue(configuredFields.any { it.key == "ntp_default_server" })
        assertTrue(configuredFields.any { it.key == "dig_default_server" })

        // Filter NOT_SET
        viewModel.setFilter(ShowMdmConfigFilter.NOT_SET)
        val notSetFields = viewModel.uiState.value.displayedFields
        assertEquals(12, notSetFields.size)
        assertTrue(notSetFields.all { !it.isSet })
    }

    @Test
    fun `searchQuery_filtersFieldsByKeyAndValue`() = runTest {
        val config = ManagedConfig(
            ntpServer = "custom.ntp.server",
            digServer = "8.8.8.8",
        )
        val configFlow = MutableStateFlow(config)
        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true) {
            every { this@mockk.configFlow } returns configFlow
            every { hasMdmConfig } returns true
            every { isAppManagedFlow } returns MutableStateFlow(true)
        }

        val viewModel = ShowMDMConfigurationsViewModel(managedConfigRepository = mockRepo)
        testDispatcher.scheduler.advanceUntilIdle()

        // Search key
        viewModel.setSearchQuery("proxy")
        val proxyResults = viewModel.uiState.value.displayedFields
        assertEquals(3, proxyResults.size)
        assertTrue(proxyResults.all { it.key.contains("proxy") })

        // Search value
        viewModel.setSearchQuery("custom.ntp.server")
        val valueResults = viewModel.uiState.value.displayedFields
        assertEquals(1, valueResults.size)
        assertEquals("ntp_default_server", valueResults[0].key)

        // Search non-existent
        viewModel.setSearchQuery("non_existent_query_xyz")
        assertTrue(viewModel.uiState.value.displayedFields.isEmpty())
    }

    @Test
    fun `liveUpdate_configFlowUpdatesUiState`() = runTest {
        val flow = MutableStateFlow(ManagedConfig())
        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true) {
            every { this@mockk.configFlow } returns flow
            every { hasMdmConfig } returns false
            every { isAppManagedFlow } returns MutableStateFlow(false)
        }

        val viewModel = ShowMDMConfigurationsViewModel(managedConfigRepository = mockRepo)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.configuredCount)

        // Live MDM push
        every { mockRepo.hasMdmConfig } returns true
        flow.value = ManagedConfig(ntpServer = "time.cloudflare.com")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.configuredCount)
        val ntpField = viewModel.uiState.value.allFields.first { it.key == "ntp_default_server" }
        assertTrue(ntpField.isSet)
        assertEquals("time.cloudflare.com", ntpField.value)
    }

    @Test
    fun `generateExportText_formatsAllCategoriesAndFields`() {
        val config = ManagedConfig(ntpServer = "time.cloudflare.com", proxyEnabled = false)
        val configFlow = MutableStateFlow(config)
        val mockRepo = mockk<ManagedConfigRepository>(relaxed = true) {
            every { this@mockk.configFlow } returns configFlow
            every { hasMdmConfig } returns true
            every { isAppManagedFlow } returns MutableStateFlow(true)
        }

        val viewModel = ShowMDMConfigurationsViewModel(managedConfigRepository = mockRepo)
        val mockContext = mockk<Context>(relaxed = true) {
            every { getString(any()) } answers { "Localized_${firstArg<Int>()}" }
        }

        val exportText = viewModel.generateExportText(mockContext)
        assertNotNull(exportText)
        assertTrue(exportText.contains("NTP DIG PING MORE - MDM Configuration"))
        assertTrue(exportText.contains("ntp_default_server"))
        assertTrue(exportText.contains("time.cloudflare.com"))
        assertTrue(exportText.contains("proxy_enabled"))
        assertTrue(exportText.contains("bulk_actions_auto_run"))
    }
}
