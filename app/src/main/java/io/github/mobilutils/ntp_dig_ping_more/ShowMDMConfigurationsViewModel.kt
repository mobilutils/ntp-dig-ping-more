package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.mobilutils.ntp_dig_ping_more.settings.ManagedConfig
import io.github.mobilutils.ntp_dig_ping_more.settings.ManagedConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 7 logical categories mirroring the sections in app_restrictions.xml.
 */
enum class MdmFieldCategory(@get:StringRes val titleResId: Int) {
    NTP(R.string.mdm_section_ntp),
    DIG(R.string.mdm_section_dig),
    PING(R.string.mdm_section_ping),
    PORT_SCANNER(R.string.mdm_section_port_scanner),
    HTTPS_CERT(R.string.mdm_section_https_cert),
    PROXY(R.string.mdm_section_proxy),
    BULK_ACTIONS(R.string.mdm_section_bulk_actions),
}

/**
 * Filter mode for MDM configuration fields.
 */
enum class ShowMdmConfigFilter {
    ALL,
    CONFIGURED,
    NOT_SET,
}

/**
 * Represents one restriction item from app_restrictions.xml with its runtime value.
 */
data class MdmRestrictionField(
    val key: String,
    @get:StringRes val titleResId: Int,
    val type: String, // "string" or "bool"
    val category: MdmFieldCategory,
    val defaultValue: String,
    val value: String?,
    val isSet: Boolean,
)

/**
 * UI State for [ShowMDMConfigurationsScreen].
 */
data class ShowMdmConfigUiState(
    val isManaged: Boolean = false,
    val allFields: List<MdmRestrictionField> = emptyList(),
    val displayedFields: List<MdmRestrictionField> = emptyList(),
    val groupedFields: Map<MdmFieldCategory, List<MdmRestrictionField>> = emptyMap(),
    val configuredCount: Int = 0,
    val totalCount: Int = 14,
    val selectedFilter: ShowMdmConfigFilter = ShowMdmConfigFilter.ALL,
    val searchQuery: String = "",
)

/**
 * ViewModel for inspecting all MDM Configuration fields loaded via Android's RestrictionsManager.
 */
class ShowMDMConfigurationsViewModel(
    private val managedConfigRepository: ManagedConfigRepository? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        buildInitialState(managedConfigRepository?.configFlow?.value ?: ManagedConfig())
    )
    val uiState: StateFlow<ShowMdmConfigUiState> = _uiState.asStateFlow()

    init {
        managedConfigRepository?.let { repo ->
            viewModelScope.launch {
                repo.configFlow.collect { config ->
                    updateConfig(config, repo.hasMdmConfig)
                }
            }
            viewModelScope.launch {
                repo.isAppManagedFlow.collect { isManaged ->
                    _uiState.update { it.copy(isManaged = isManaged) }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        managedConfigRepository?.unregister()
    }

    fun setFilter(filter: ShowMdmConfigFilter) {
        _uiState.update { state ->
            val displayed = filterFields(state.allFields, filter, state.searchQuery)
            state.copy(
                selectedFilter = filter,
                displayedFields = displayed,
                groupedFields = displayed.groupBy { it.category },
            )
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { state ->
            val displayed = filterFields(state.allFields, state.selectedFilter, query)
            state.copy(
                searchQuery = query,
                displayedFields = displayed,
                groupedFields = displayed.groupBy { it.category },
            )
        }
    }

    fun generateExportText(context: Context): String {
        val state = _uiState.value
        val sb = StringBuilder()
        sb.appendLine("=== NTP DIG PING MORE - MDM Configuration ===")
        sb.appendLine("Status: ${if (state.isManaged) "Managed (Active)" else "Unmanaged"}")
        sb.appendLine("Configured: ${state.configuredCount} / ${state.totalCount}")
        sb.appendLine()

        MdmFieldCategory.values().forEach { category ->
            val categoryFields = state.allFields.filter { it.category == category }
            if (categoryFields.isNotEmpty()) {
                sb.appendLine("[${context.getString(category.titleResId)}]")
                categoryFields.forEach { field ->
                    val title = context.getString(field.titleResId)
                    val valueStr = if (field.isSet) field.value ?: "" else "(not set)"
                    sb.appendLine("  ${field.key} ($title) [${field.type}]: $valueStr")
                }
                sb.appendLine()
            }
        }
        return sb.toString().trimEnd()
    }

    private fun updateConfig(config: ManagedConfig, isManaged: Boolean) {
        val allFields = mapConfigToFields(config)
        val configuredCount = allFields.count { it.isSet }
        _uiState.update { state ->
            val displayed = filterFields(allFields, state.selectedFilter, state.searchQuery)
            state.copy(
                isManaged = isManaged,
                allFields = allFields,
                displayedFields = displayed,
                groupedFields = displayed.groupBy { it.category },
                configuredCount = configuredCount,
                totalCount = allFields.size,
            )
        }
    }

    private fun buildInitialState(config: ManagedConfig): ShowMdmConfigUiState {
        val allFields = mapConfigToFields(config)
        val configuredCount = allFields.count { it.isSet }
        val isManaged = managedConfigRepository?.hasMdmConfig ?: (configuredCount > 0)
        return ShowMdmConfigUiState(
            isManaged = isManaged,
            allFields = allFields,
            displayedFields = allFields,
            groupedFields = allFields.groupBy { it.category },
            configuredCount = configuredCount,
            totalCount = allFields.size,
        )
    }

    private fun filterFields(
        fields: List<MdmRestrictionField>,
        filter: ShowMdmConfigFilter,
        query: String,
    ): List<MdmRestrictionField> {
        return fields.filter { field ->
            val matchesFilter = when (filter) {
                ShowMdmConfigFilter.ALL -> true
                ShowMdmConfigFilter.CONFIGURED -> field.isSet
                ShowMdmConfigFilter.NOT_SET -> !field.isSet
            }
            val matchesQuery = if (query.isBlank()) {
                true
            } else {
                field.key.contains(query, ignoreCase = true) ||
                    field.value?.contains(query, ignoreCase = true) == true
            }
            matchesFilter && matchesQuery
        }
    }

    companion object {
        /**
         * Builds the canonical list of all 14 restriction fields from app_restrictions.xml.
         */
        fun mapConfigToFields(config: ManagedConfig): List<MdmRestrictionField> {
            return listOf(
                // 1. NTP Client
                MdmRestrictionField(
                    key = "ntp_default_server",
                    titleResId = R.string.mdm_ntp_server_title,
                    type = "string",
                    category = MdmFieldCategory.NTP,
                    defaultValue = "",
                    value = config.ntpServer,
                    isSet = config.ntpServer != null,
                ),
                MdmRestrictionField(
                    key = "ntp_default_port",
                    titleResId = R.string.mdm_ntp_port_title,
                    type = "string",
                    category = MdmFieldCategory.NTP,
                    defaultValue = "",
                    value = config.ntpPort,
                    isSet = config.ntpPort != null,
                ),

                // 2. DIG / DNS Lookup
                MdmRestrictionField(
                    key = "dig_default_server",
                    titleResId = R.string.mdm_dig_server_title,
                    type = "string",
                    category = MdmFieldCategory.DIG,
                    defaultValue = "",
                    value = config.digServer,
                    isSet = config.digServer != null,
                ),
                MdmRestrictionField(
                    key = "dig_default_fqdn",
                    titleResId = R.string.mdm_dig_fqdn_title,
                    type = "string",
                    category = MdmFieldCategory.DIG,
                    defaultValue = "",
                    value = config.digFqdn,
                    isSet = config.digFqdn != null,
                ),

                // 3. Ping
                MdmRestrictionField(
                    key = "ping_default_host",
                    titleResId = R.string.mdm_ping_host_title,
                    type = "string",
                    category = MdmFieldCategory.PING,
                    defaultValue = "",
                    value = config.pingHost,
                    isSet = config.pingHost != null,
                ),

                // 4. Port Scanner
                MdmRestrictionField(
                    key = "port_scanner_default_host",
                    titleResId = R.string.mdm_port_scanner_host_title,
                    type = "string",
                    category = MdmFieldCategory.PORT_SCANNER,
                    defaultValue = "",
                    value = config.portScannerHost,
                    isSet = config.portScannerHost != null,
                ),

                // 5. HTTPS Certificate Inspector
                MdmRestrictionField(
                    key = "https_cert_default_host",
                    titleResId = R.string.mdm_https_cert_host_title,
                    type = "string",
                    category = MdmFieldCategory.HTTPS_CERT,
                    defaultValue = "",
                    value = config.httpsCertHost,
                    isSet = config.httpsCertHost != null,
                ),
                MdmRestrictionField(
                    key = "https_cert_default_port",
                    titleResId = R.string.mdm_https_cert_port_title,
                    type = "string",
                    category = MdmFieldCategory.HTTPS_CERT,
                    defaultValue = "",
                    value = config.httpsCertPort,
                    isSet = config.httpsCertPort != null,
                ),

                // 6. Settings / Proxy PAC
                MdmRestrictionField(
                    key = "proxy_enabled",
                    titleResId = R.string.mdm_proxy_enabled_title,
                    type = "bool",
                    category = MdmFieldCategory.PROXY,
                    defaultValue = "false",
                    value = config.proxyEnabled?.toString(),
                    isSet = config.proxyEnabled != null,
                ),
                MdmRestrictionField(
                    key = "proxy_pac_url",
                    titleResId = R.string.mdm_proxy_pac_url_title,
                    type = "string",
                    category = MdmFieldCategory.PROXY,
                    defaultValue = "",
                    value = config.proxyPacUrl,
                    isSet = config.proxyPacUrl != null,
                ),
                MdmRestrictionField(
                    key = "proxy_logging_enabled",
                    titleResId = R.string.mdm_proxy_logging_title,
                    type = "bool",
                    category = MdmFieldCategory.PROXY,
                    defaultValue = "false",
                    value = config.proxyLoggingEnabled?.toString(),
                    isSet = config.proxyLoggingEnabled != null,
                ),

                // 7. Bulk Actions (zero-touch provisioning)
                MdmRestrictionField(
                    key = "bulk_actions_json",
                    titleResId = R.string.mdm_bulk_json_title,
                    type = "string",
                    category = MdmFieldCategory.BULK_ACTIONS,
                    defaultValue = "",
                    value = config.bulkActionsJson,
                    isSet = config.bulkActionsJson != null,
                ),
                MdmRestrictionField(
                    key = "bulk_actions_url",
                    titleResId = R.string.mdm_bulk_url_title,
                    type = "string",
                    category = MdmFieldCategory.BULK_ACTIONS,
                    defaultValue = "",
                    value = config.bulkActionsUrl,
                    isSet = config.bulkActionsUrl != null,
                ),
                MdmRestrictionField(
                    key = "bulk_actions_auto_run",
                    titleResId = R.string.mdm_bulk_auto_run_title,
                    type = "bool",
                    category = MdmFieldCategory.BULK_ACTIONS,
                    defaultValue = "false",
                    value = if (config.bulkActionsAutoRunSet || config.bulkActionsAutoRun) {
                        config.bulkActionsAutoRun.toString()
                    } else {
                        null
                    },
                    isSet = config.bulkActionsAutoRunSet || config.bulkActionsAutoRun,
                ),
            )
        }

        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ShowMDMConfigurationsViewModel(
                        managedConfigRepository = ManagedConfigRepository(context.applicationContext)
                    ) as T
                }
            }
    }
}
