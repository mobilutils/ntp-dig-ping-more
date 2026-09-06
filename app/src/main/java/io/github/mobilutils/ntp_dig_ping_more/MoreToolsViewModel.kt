package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.mobilutils.ntp_dig_ping_more.settings.ManagedConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MoreToolsUiState(
    val isMdmConfigured: Boolean = false,
)

class MoreToolsViewModel(
    private val managedConfigRepository: ManagedConfigRepository? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MoreToolsUiState(isMdmConfigured = managedConfigRepository?.hasMdmConfig ?: false)
    )
    val uiState: StateFlow<MoreToolsUiState> = _uiState.asStateFlow()

    init {
        managedConfigRepository?.let { repo ->
            viewModelScope.launch {
                repo.isAppManagedFlow.collect { isConfigured ->
                    _uiState.update { it.copy(isMdmConfigured = isConfigured) }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        managedConfigRepository?.unregister()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MoreToolsViewModel(
                        managedConfigRepository = ManagedConfigRepository(context.applicationContext)
                    ) as T
                }
            }
    }
}
