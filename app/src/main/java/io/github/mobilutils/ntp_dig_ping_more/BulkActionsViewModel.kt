package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// ────────────────────────────────────────────────────────────────────
// UI state
// ────────────────────────────────────────────────────────────────────

data class BulkUiState(
    val configLoaded: Boolean = false,
    val configFileName: String? = null,
    val configUri: String? = null,
    val commandCount: Int = 0,
    val isExecuting: Boolean = false,
    val currentCommand: String? = null,
    val progress: Float = 0f,
    val results: List<BulkCommandResult> = emptyList(),
    val isFileWriting: Boolean = false,
    val outputFileWritten: Boolean? = null,
    val validationMessage: BulkActionsViewModel.ValidationMessage? = null,
)

// ────────────────────────────────────────────────────────────────────
// ViewModel
// ────────────────────────────────────────────────────────────────────

class BulkActionsViewModel(
    private val context: Context,
    private val repository: BulkActionsRepository = BulkActionsRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(BulkUiState())
    val uiState: StateFlow<BulkUiState> = _uiState.asStateFlow()

    private var executionJob: Job? = null
    private val cancellationToken = AtomicBoolean(false)

    /**
     * Called when the user selects a JSON config file via the file picker.
     * Parses the file and loads the config into UI state.
     */
    fun onFileSelected(uri: Uri, fileName: String) {
        viewModelScope.launch {
            val json = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.readBytes()?.decodeToString() ?: ""
            }
            if (json.isBlank()) return@launch

            try {
                val config = BulkConfigParser.parse(json)
                _uiState.value = _uiState.value.copy(
                    configLoaded = true,
                    configFileName = fileName,
                    configUri = uri.toString(),
                    commandCount = config.commands.size,
                    results = emptyList(),
                    progress = 0f,
                    outputFileWritten = null,
                )
            } catch (e: IllegalArgumentException) {
                _uiState.value = _uiState.value.copy(
                    configLoaded = false,
                    configFileName = null,
                    commandCount = 0,
                )
            }
        }
    }

    /** Starts executing all commands in the loaded config. */
    fun onRunClicked() {
        if (_uiState.value.isExecuting) return

        cancellationToken.set(false)
        _uiState.value = _uiState.value.copy(
            isExecuting = true,
            results = emptyList(),
            progress = 0f,
            currentCommand = null,
        )

        executionJob = viewModelScope.launch {
            val configUriStr = _uiState.value.configUri
            if (configUriStr == null) {
                _uiState.value = _uiState.value.copy(isExecuting = false)
                return@launch
            }

            val config = withContext(Dispatchers.IO) {
                val json = context.contentResolver.openInputStream(android.net.Uri.parse(configUriStr))?.readBytes()?.decodeToString() ?: ""
                BulkConfigParser.parse(json)
            }

            val commands = config.commands.toList()
            val total = commands.size
            val allResults = mutableListOf<BulkCommandResult>()

            commands.forEachIndexed { index, (name, cmd) ->
                if (cancellationToken.get()) {
                    allResults.add(BulkCommandError(name, cmd, "Cancelled"))
                    return@forEachIndexed
                }

                // Update progress
                _uiState.value = _uiState.value.copy(
                    currentCommand = "$name: $cmd",
                    progress = index.toFloat() / total,
                )

                val result = try {
                    withTimeout(30_000L) {
                        repository.executeSingleCommand(name, cmd)
                    }
                } catch (e: Exception) {
                    null
                }

                val finalResult = result ?: BulkCommandTimeout(name, cmd)
                allResults.add(finalResult)
            }

            _uiState.value = _uiState.value.copy(
                isExecuting = false,
                results = allResults,
                progress = 1f,
                currentCommand = null,
            )
        }
    }

    /** Stops the currently running execution. */
    fun onStopClicked() {
        cancellationToken.set(true)
        executionJob?.cancel()
        _uiState.value = _uiState.value.copy(isExecuting = false)
    }

    /** Clears all results and progress. */
    fun onClearResults() {
        _uiState.value = _uiState.value.copy(
            results = emptyList(),
            progress = 0f,
            currentCommand = null,
        )
    }

    /** Writes results to the output file specified in the config. */
    fun onWriteOutputFile() {
        val results = _uiState.value.results
        if (results.isEmpty() || _uiState.value.isFileWriting) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isFileWriting = true)

            val configUriStr = _uiState.value.configUri ?: return@launch
            val json = context.contentResolver.openInputStream(android.net.Uri.parse(configUriStr))?.readBytes()?.decodeToString() ?: ""
            val config = runCatching { BulkConfigParser.parse(json) }.getOrNull()
            val outputPath = config?.outputFile

            var success = false
            if (outputPath != null) {
                // Try SAF first (via content resolver openOutputStream)
                success = writeViaSAF(outputPath, results)
                // Fallback to direct write if SAF fails
                if (!success) {
                    success = writeDirect(outputPath, results)
                }
            }

            _uiState.value = _uiState.value.copy(
                isFileWriting = false,
                outputFileWritten = success,
            )
        }
    }

    /** Writes results via SAF picker, letting the user choose the output location. */
    fun onWriteOutputFileViaSAF(launcher: androidx.activity.result.ActivityResultLauncher<String>) {
        val results = _uiState.value.results
        if (results.isEmpty()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isFileWriting = true)

            val content = results.joinToString("\n\n") { result ->
                when (result) {
                    is BulkCommandSuccess ->
                        "=== ${result.commandName}: ${result.command} ===\n" +
                        "Status: SUCCESS (${result.durationMs}ms)\n" +
                        result.outputLines.joinToString("\n")
                    is BulkCommandError ->
                        "=== ${result.commandName}: ${result.command} ===\n" +
                        "Status: ERROR\n${result.errorMessage}"
                    is BulkCommandTimeout ->
                        "=== ${result.commandName}: ${result.command} ===\n" +
                        "Status: TIMEOUT"
                }
            }

            // Launch SAF picker with suggested filename
            launcher.launch("bulk-output-${System.currentTimeMillis()}.txt")

            _uiState.value = _uiState.value.copy(isFileWriting = false)
        }
    }

    /** Validates the currently loaded config. */
    fun validateConfig() {
        val configUri = _uiState.value.configUri ?: run {
            _uiState.value = _uiState.value.copy(validationMessage = ValidationMessage.Info("No config loaded"))
            return
        }

        viewModelScope.launch {
            val json = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(android.net.Uri.parse(configUri))?.readBytes()?.decodeToString() ?: ""
            }

            if (json.isBlank()) {
                _uiState.value = _uiState.value.copy(validationMessage = ValidationMessage.Info("Config file is empty"))
                return@launch
            }

            val result = runCatching { BulkConfigParser.parse(json) }

            _uiState.value = _uiState.value.copy(
                validationMessage = if (result.isSuccess) {
                    val config = result.getOrNull()!!
                    ValidationMessage.Success("${config.commands.size} command(s) validated successfully")
                } else {
                    ValidationMessage.Error("Validation failed: ${result.exceptionOrNull()?.message}")
                },
            )
        }
    }

    /** Result of config validation. */
    sealed class ValidationMessage {
        data class Info(val text: String) : ValidationMessage()
        data class Success(val text: String) : ValidationMessage()
        data class Error(val text: String) : ValidationMessage()
    }

    /** Clears the current validation message. */
    fun clearValidationMessage() {
        _uiState.value = _uiState.value.copy(validationMessage = null)
    }

    /** Writes results via SAF to the given URI. */
    fun writeFileViaSAF(uri: Uri, results: List<BulkCommandResult>) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isFileWriting = true)
            val success = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        val content = results.joinToString("\n\n") { result ->
                            when (result) {
                                is BulkCommandSuccess ->
                                    "=== ${result.commandName}: ${result.command} ===\n" +
                                    "Status: SUCCESS (${result.durationMs}ms)\n" +
                                    result.outputLines.joinToString("\n")
                                is BulkCommandError ->
                                    "=== ${result.commandName}: ${result.command} ===\n" +
                                    "Status: ERROR\n${result.errorMessage}"
                                is BulkCommandTimeout ->
                                    "=== ${result.commandName}: ${result.command} ===\n" +
                                    "Status: TIMEOUT"
                            }
                        }
                        outputStream.write(content.toByteArray())
                        true
                    } ?: false
                } catch (e: Exception) {
                    false
                }
            }
            _uiState.value = _uiState.value.copy(
                isFileWriting = false,
                outputFileWritten = success,
            )
        }
    }

    private suspend fun writeViaSAF(path: String, results: List<BulkCommandResult>): Boolean {
        return try {
            val uri = android.net.Uri.parse(path)
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                val content = results.joinToString("\n\n") { result ->
                    when (result) {
                        is BulkCommandSuccess ->
                            "=== ${result.commandName}: ${result.command} ===\n" +
                            "Status: SUCCESS (${result.durationMs}ms)\n" +
                            result.outputLines.joinToString("\n")
                        is BulkCommandError ->
                            "=== ${result.commandName}: ${result.command} ===\n" +
                            "Status: ERROR\n${result.errorMessage}"
                        is BulkCommandTimeout ->
                            "=== ${result.commandName}: ${result.command} ===\n" +
                            "Status: TIMEOUT"
                    }
                }
                outputStream.write(content.toByteArray())
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun writeDirect(path: String, results: List<BulkCommandResult>): Boolean {
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            val content = results.joinToString("\n\n") { result ->
                when (result) {
                    is BulkCommandSuccess ->
                        "=== ${result.commandName}: ${result.command} ===\n" +
                        "Status: SUCCESS (${result.durationMs}ms)\n" +
                        result.outputLines.joinToString("\n")
                    is BulkCommandError ->
                        "=== ${result.commandName}: ${result.command} ===\n" +
                        "Status: ERROR\n${result.errorMessage}"
                    is BulkCommandTimeout ->
                        "=== ${result.commandName}: ${result.command} ===\n" +
                        "Status: TIMEOUT"
                }
            }
            file.writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Stores the URI of the currently loaded config for re-reading during execution. */
    private var _currentConfigUri: Uri? = null

    override fun onCleared() {
        super.onCleared()
        cancellationToken.set(true)
        executionJob?.cancel()
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    BulkActionsViewModel(
                        context = context.applicationContext,
                        repository = BulkActionsRepository(),
                    ) as T
            }
    }
}
