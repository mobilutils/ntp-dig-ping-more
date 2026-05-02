package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Top-level DataStore instance for CSV output preference. */
private val Context.bulkActionsCsvDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "bulk_actions_csv")

// ────────────────────────────────────────────────────────────────────
// UI state
// ────────────────────────────────────────────────────────────────────

data class BulkUiState(
    val configLoaded: Boolean = false,
    val configFileName: String? = null,
    val configUri: String? = null,
    val commandCount: Int = 0,
    val configTimeoutMs: Long? = null,
    val csvOutputEnabled: Boolean = false,
    val isExecuting: Boolean = false,
    val currentCommand: String? = null,
    val progress: Float = 0f,
    val results: List<BulkCommandResult> = emptyList(),
    val isFileWriting: Boolean = false,
    val outputFileWritten: Boolean? = null,
    val autoSaved: Boolean = false,
    val autoSavedPath: String? = null,
    val validatedOutputFile: String? = null,
    val validationMessage: BulkActionsViewModel.ValidationMessage? = null,
    val outputFilePath: String? = null,
)

// ────────────────────────────────────────────────────────────────────
// ViewModel
// ────────────────────────────────────────────────────────────────────

class BulkActionsViewModel(
    private val context: Context,
    private val repository: BulkActionsRepository,
) : ViewModel() {

    internal val _uiState = MutableStateFlow(BulkUiState())
    val uiState: StateFlow<BulkUiState> = _uiState.asStateFlow()

    private var executionJob: Job? = null
    private val cancellationToken = AtomicBoolean(false)

    /** Path to a marker file in the app's private directory. Exists while bulk actions are running. */
    private val runningFile: File by lazy {
        File(context.filesDir, ".running-tasks")
    }

    /** CSV output preference loaded from DataStore. */
    private val csvDataStore: DataStore<Preferences> by lazy {
        context.bulkActionsCsvDataStore
    }
    val csvOutputEnabled: StateFlow<Boolean> = csvDataStore.data.map { prefs ->
        prefs[booleanPreferencesKey("csv_output_enabled")] ?: false
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), false)

    /**
     * Called when the user selects a JSON config file via the file picker.
     * Parses the file and loads the config into UI state.
     */
    fun onFileSelected(uri: Uri, fileName: String) {
        viewModelScope.launch {
            var json: String? = null
            var readError: String? = null
            withContext(Dispatchers.IO) {
                try {
                    json = context.contentResolver.openInputStream(uri)?.readBytes()?.decodeToString()
                } catch (e: java.io.IOException) {
                    readError = e.message ?: "Permission denied or file not found"
                    json = ""
                }
            }
            if (json.isNullOrBlank()) {
                _uiState.value = _uiState.value.copy(
                    configLoaded = false,
                    configFileName = null,
                    commandCount = 0,
                    validationMessage = ValidationMessage.Error(
                        readError ?: "Failed to read config file: $fileName"
                    )
                )
                return@launch
            }

            try {
                val config = BulkConfigParser.parse(json)
                // Load DataStore CSV setting (JSON field overrides)
                val csvFromStore = csvOutputEnabled.value

                // Auto-validate output-file if present
                val outputFilePath = config.outputFile
                val (validatedOutputFile, validationMsg) = config.outputFile?.let { rawPath ->
                    val validation = BulkConfigParser.validateOutputFile(rawPath)
                    val msg = when (validation) {
                        is BulkConfigParser.OutputFileValidationResult.Valid ->
                            ValidationMessage.Success("output-file: ${validation.path} is writable")
                        is BulkConfigParser.OutputFileValidationResult.Invalid ->
                            ValidationMessage.Info(
                                "'$rawPath' is not writable. Use: ${validation.suggestedPath}"
                            )
                    }
                    val resolvedPath = when (validation) {
                        is BulkConfigParser.OutputFileValidationResult.Valid -> validation.path
                        is BulkConfigParser.OutputFileValidationResult.Invalid -> validation.suggestedPath
                    }
                    resolvedPath to msg
                } ?: (null to null)

                _uiState.value = _uiState.value.copy(
                    configLoaded = true,
                    configFileName = fileName,
                    configUri = uri.toString(),
                    commandCount = config.commands.size,
                    configTimeoutMs = config.timeoutMs,
                    csvOutputEnabled = config.outputAsCsv || csvFromStore,
                    validatedOutputFile = validatedOutputFile,
                    outputFilePath = outputFilePath,
                    validationMessage = validationMsg,
                    results = emptyList(),
                    progress = 0f,
                    outputFileWritten = null,
                )
            } catch (e: IllegalArgumentException) {
                _uiState.value = _uiState.value.copy(
                    configLoaded = false,
                    configFileName = null,
                    commandCount = 0,
                    validationMessage = ValidationMessage.Error("Failed to parse config: ${e.message}"),
                )
            }
        }
    }

    /**
     * Loads and runs a config from a URI — used for ADB automation.
     * Reads the file synchronously, then starts execution, returning a Job that
     * completes when all commands finish. This avoids the race condition where
     * [onFileSelected] (async) hasn't finished when [onRunClicked] is called.
     */
    fun onLoadAndRun(uri: Uri, fileName: String): Job {
        return viewModelScope.launch {
            var json: String? = null
            var readError: String? = null
            withContext(Dispatchers.IO) {
                try {
                    json = context.contentResolver.openInputStream(uri)?.readBytes()?.decodeToString()
                } catch (e: java.io.IOException) {
                    readError = e.message ?: "Permission denied or file not found"
                    json = ""
                }
            }
            if (json.isNullOrBlank()) {
                _uiState.value = _uiState.value.copy(
                    configLoaded = false,
                    configFileName = null,
                    commandCount = 0,
                    validationMessage = ValidationMessage.Error(
                        readError ?: "Config file is empty or unreadable: $fileName"
                    )
                )
                return@launch
            }

            val config = try {
                BulkConfigParser.parse(json)
            } catch (e: IllegalArgumentException) {
                _uiState.value = _uiState.value.copy(
                    configLoaded = false,
                    configFileName = null,
                    commandCount = 0,
                    validationMessage = ValidationMessage.Error("Failed to parse config: ${e.message}"),
                )
                return@launch
            }

            val csvFromStore = csvOutputEnabled.value
            val outputFilePath = config.outputFile
            val (validatedOutputFile, validationMsg) = config.outputFile?.let { rawPath ->
                val validation = BulkConfigParser.validateOutputFile(rawPath)
                val msg = when (validation) {
                    is BulkConfigParser.OutputFileValidationResult.Valid ->
                        ValidationMessage.Success("output-file: ${validation.path} is writable")
                    is BulkConfigParser.OutputFileValidationResult.Invalid ->
                        ValidationMessage.Info(
                            "'$rawPath' is not writable. Use: ${validation.suggestedPath}"
                        )
                }
                val resolvedPath = when (validation) {
                    is BulkConfigParser.OutputFileValidationResult.Valid -> validation.path
                    is BulkConfigParser.OutputFileValidationResult.Invalid -> validation.suggestedPath
                }
                resolvedPath to msg
            } ?: (null to null)

            _uiState.value = _uiState.value.copy(
                configLoaded = true,
                configFileName = fileName,
                configUri = uri.toString(),
                commandCount = config.commands.size,
                configTimeoutMs = config.timeoutMs,
                csvOutputEnabled = config.outputAsCsv || csvFromStore,
                validatedOutputFile = validatedOutputFile,
                outputFilePath = outputFilePath,
                validationMessage = validationMsg,
                results = emptyList(),
                progress = 0f,
                outputFileWritten = null,
            )

            // Now run the config (reuses the same execution logic as onRunClicked)
            cancellationToken.set(false)
            createRunningFile()
            _uiState.value = _uiState.value.copy(
                isExecuting = true,
                results = emptyList(),
                progress = 0f,
                currentCommand = null,
            )

            val commands = config.commands.toList()
            val total = commands.size
            val defaultTimeoutMs = config.timeoutMs ?: 30_000L
            val allResults = mutableListOf<BulkCommandResult>()

            try {
                commands.forEachIndexed { index, (name, cmd) ->
                    if (cancellationToken.get()) {
                        allResults.add(BulkCommandError(name, cmd, "Cancelled"))
                        return@forEachIndexed
                    }

                    _uiState.value = _uiState.value.copy(
                        currentCommand = "$name: $cmd",
                        progress = index.toFloat() / total,
                    )

                    val commandTimeoutMs = BulkConfigParser.extractCommandTimeout(cmd) ?: defaultTimeoutMs

                    val result = try {
                        withTimeout(commandTimeoutMs) {
                            repository.executeSingleCommand(name, cmd, commandTimeoutMs)
                        }
                    } catch (e: Exception) {
                        null
                    }

                    val finalResult = result ?: BulkCommandTimeout(name, cmd)
                    allResults.add(finalResult)
                }

                // Auto-save if output-file is defined
                var autoSavedPath: String? = null
                if (config.outputFile != null) {
                    val outputPath = _uiState.value.validatedOutputFile ?: config.outputFile
                    val saved = autoSaveResults(outputPath, allResults)
                    if (saved) {
                        autoSavedPath = outputPath
                    }
                }

                _uiState.value = _uiState.value.copy(
                    isExecuting = false,
                    results = allResults,
                    progress = 1f,
                    currentCommand = null,
                    autoSaved = autoSavedPath != null,
                    autoSavedPath = autoSavedPath,
                )
            } finally {
                deleteRunningFile()
            }
        }
    }

    /** Starts executing all commands in the loaded config. */
    fun onRunClicked() {
        if (_uiState.value.isExecuting) return

        cancellationToken.set(false)
        createRunningFile()
        _uiState.value = _uiState.value.copy(
            isExecuting = true,
            results = emptyList(),
            progress = 0f,
            currentCommand = null,
        )

        executionJob = viewModelScope.launch {
            try {
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
                val defaultTimeoutMs = config.timeoutMs ?: 30_000L
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

                    // Per-command `-t N` overrides config-level timeout
                    val commandTimeoutMs = BulkConfigParser.extractCommandTimeout(cmd) ?: defaultTimeoutMs

                    val result = try {
                        withTimeout(commandTimeoutMs) {
                            repository.executeSingleCommand(name, cmd, commandTimeoutMs)
                        }
                    } catch (e: Exception) {
                        null
                    }

                    val finalResult = result ?: BulkCommandTimeout(name, cmd)
                    allResults.add(finalResult)
                }

                // Auto-save if output-file is defined
                var autoSavedPath: String? = null
                if (config.outputFile != null) {
                    val outputPath = _uiState.value.validatedOutputFile ?: config.outputFile
                    val saved = autoSaveResults(outputPath, allResults)
                    if (saved) {
                        autoSavedPath = outputPath
                    }
                }

                _uiState.value = _uiState.value.copy(
                    isExecuting = false,
                    results = allResults,
                    progress = 1f,
                    currentCommand = null,
                    autoSaved = autoSavedPath != null,
                    autoSavedPath = autoSavedPath,
                )
            } finally {
                deleteRunningFile()
            }
        }
    }

    /** Stops the currently running execution. */
    fun onStopClicked() {
        cancellationToken.set(true)
        executionJob?.cancel()
        _uiState.value = _uiState.value.copy(isExecuting = false)
        deleteRunningFile()
    }

    /** Clears all results and progress. */
    fun onClearResults() {
        _uiState.value = _uiState.value.copy(
            results = emptyList(),
            progress = 0f,
            currentCommand = null,
            autoSaved = false,
            autoSavedPath = null,
        )
        deleteRunningFile()
    }

    /** Generates the full output file content including a summary table at the end. */
    private fun generateOutputContent(results: List<BulkCommandResult>): String {
        val total = results.size
        val successCount = results.count { it is BulkCommandSuccess }
        val errorCount = results.count { it is BulkCommandError }
        val timeoutCount = results.count { it is BulkCommandTimeout }
        val closedCount = results.count { it is BulkCommandClosed }
        val warningCount = results.count { it is BulkCommandWarning }
        val totalDurationMs = results
                .filterIsInstance<BulkCommandSuccess>().sumOf { it.durationMs }
                .plus(results.filterIsInstance<BulkCommandClosed>().sumOf { it.durationMs })

        val lines = mutableListOf<String>()

        // Header
        lines.add("══════════════════════════════════════════════════════")
        lines.add("  BULK ACTIONS — OUTPUT REPORT")
        lines.add("══════════════════════════════════════════════════════")
        lines.add("  Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        lines.add("══════════════════════════════════════════════════════")
        lines.add("")

        // Individual results
        lines.add("── COMMAND RESULTS ──────────────────────────────────")
        lines.add("")
        results.forEachIndexed { index, result ->
            when (result) {
                is BulkCommandSuccess -> {
                    lines.add("[${index + 1}] ${result.commandName}: ${result.command}")
                    lines.add("    Status: SUCCESS (${result.durationMs}ms)")
                    result.outputLines.forEach { line -> lines.add("     $line") }
                }
                is BulkCommandError -> {
                    lines.add("[${index + 1}] ${result.commandName}: ${result.command}")
                    lines.add("    Status: ERROR")
                    lines.add("     ${result.errorMessage}")
                }
                is BulkCommandTimeout -> {
                    lines.add("[${index + 1}] ${result.commandName}: ${result.command}")
                    lines.add("    Status: TIMEOUT")
                }
                is BulkCommandClosed -> {
                    lines.add("[${index + 1}] ${result.commandName}: ${result.command}")
                    lines.add("    Status: CLOSED (${result.durationMs}ms)")
                    result.outputLines.forEach { line -> lines.add("        $line") }
                }
                is BulkCommandWarning -> {
                    lines.add("[${index + 1}] ${result.commandName}: ${result.command}")
                    lines.add("    Status: WARNING (${result.durationMs}ms)")
                    result.outputLines.forEach { line -> lines.add("         $line") }
                }
            }

            lines.add("")
        }

        // Summary table
        val successPct = if (total > 0) String.format("%5.1f%%", successCount.toFloat() / total * 100) else "    0.0%"
        val errorPct = if (total > 0) String.format("%5.1f%%", errorCount.toFloat() / total * 100) else "    0.0%"
        val timeoutPct = if (total > 0) String.format("%5.1f%%", timeoutCount.toFloat() / total * 100) else "    0.0%"
        val closedPct = if (total > 0) String.format("%5.1f%%", closedCount.toFloat() / total * 100) else "     0.0%"
        val warningPct = if (total > 0) String.format("%5.1f%%", warningCount.toFloat() / total * 100) else "     0.0%"
        val successBar = "█".repeat(successCount) + "░".repeat(total - successCount)

        lines.add("── SUMMARY ──────────────────────────────────────────")
        lines.add("")
        lines.add("    ┌───────────────────────┬──────────┬─────────────┐")
        lines.add("    │ Metric                  │ Count      │ Percentage    │")
        lines.add("    ├───────────────────────┼──────────┼─────────────┤")
        lines.add("    │ Total commands          │ ${total.toString().padStart(6)} │ ${"100.0%".padStart(7)} │")
        lines.add("    ├───────────────────────┼──────────┼─────────────┤")
        lines.add("    │ ✓ SUCCESS               │ ${successCount.toString().padStart(6)} │ ${successPct.padStart(7)} │")
        lines.add("    │ ✗ ERROR                 │ ${errorCount.toString().padStart(6)} │ ${errorPct.padStart(7)} │")
        lines.add("    │ ⏱ TIMEOUT               │ ${timeoutCount.toString().padStart(6)} │ ${timeoutPct.padStart(7)} │")
        lines.add("     │ ✗ CLOSED                  │ ${closedCount.toString().padStart(6)} │ ${closedPct.padStart(7)} │")
        lines.add("    │ ⚠️ WARNING                │ ${warningCount.toString().padStart(6)} │ ${warningPct.padStart(7)} │")
        lines.add("    ├───────────────────────┼──────────┼─────────────┤")
        lines.add("    │ Total duration          │ ${String.format("%8d", totalDurationMs)} ms    │               │")
        lines.add("    └───────────────────────┴──────────┴─────────────┘")
        lines.add("")
        lines.add("  Progress: [$successBar] $successCount/$total")
        lines.add("")
        lines.add("══════════════════════════════════════════════════════")

        return lines.joinToString("\n")
    }

    /** Generates CSV content for export. */
    private fun generateCsvContent(results: List<BulkCommandResult>): String {
        val lines = mutableListOf<String>()
        lines.add("cmdname,command,time,result")
        results.forEach { result ->
            when (result) {
                is BulkCommandSuccess -> {
                    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                    val resultText = result.outputLines.joinToString("; ")
                    lines.add("${result.commandName},${result.command},${time},${resultText}")
                }
                is BulkCommandError -> {
                    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                    lines.add("${result.commandName},${result.command},${time},${result.errorMessage}")
                }
                is BulkCommandTimeout -> {
                    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                    lines.add("${result.commandName},${result.command},${time},TIMEOUT")
                }
                is BulkCommandClosed -> {
                    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                    val resultText = result.outputLines.joinToString("; ")
                    lines.add("${result.commandName},${result.command},${time},${resultText}")
                }
                is BulkCommandWarning -> {
                    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                    val resultText = result.outputLines.joinToString("; ")
                    lines.add("${result.commandName},${result.command},${time},WARNING")
                  }
            }
        }
        return lines.joinToString("\n")
    }

    /** Auto-saves results to the validated output path after execution. */
    private suspend fun autoSaveResults(outputPath: String, results: List<BulkCommandResult>): Boolean {
        return try {
            val success = writeViaSAF(outputPath, results)
            if (!success) {
                writeDirect(outputPath, results)
            } else {
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Writes results via SAF picker, letting the user choose the output location. */
    fun onWriteOutputFile() {
        val results = _uiState.value.results
        if (results.isEmpty() || _uiState.value.isFileWriting) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isFileWriting = true)

            val csvEnabled = csvOutputEnabled.value
            val content = if (csvEnabled) {
                generateCsvContent(results)
            } else {
                generateOutputContent(results)
            }

            // Launch SAF picker with suggested filename
            val launcher = _outputLauncher
            if (launcher != null) {
                launcher.launch("bulk-output-${System.currentTimeMillis()}.txt")
            }

            _uiState.value = _uiState.value.copy(isFileWriting = false)
        }
    }

    /** Sets the SAF launcher for output file writing. */
    fun setOutputLauncher(launcher: androidx.activity.result.ActivityResultLauncher<String>) {
        _outputLauncher = launcher
    }

    private var _outputLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null

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

            val parseResult = runCatching { BulkConfigParser.parse(json) }

            if (!parseResult.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    validationMessage = ValidationMessage.Error("Validation failed: ${parseResult.exceptionOrNull()?.message}"),
                )
                return@launch
            }

            val config = parseResult.getOrNull()!!

            // Validate output-file if present
            val validatedOutputFile = config.outputFile?.let { rawPath ->
                val validation = BulkConfigParser.validateOutputFile(rawPath)
                when (validation) {
                    is BulkConfigParser.OutputFileValidationResult.Valid -> {
                        _uiState.value = _uiState.value.copy(
                            validationMessage = ValidationMessage.Success("output-file: $rawPath is writable")
                        )
                        rawPath
                    }
                    is BulkConfigParser.OutputFileValidationResult.Invalid -> {
                        _uiState.value = _uiState.value.copy(
                            validationMessage = ValidationMessage.Info(
                                "'$rawPath' is not writable. Use: ${validation.suggestedPath}"
                            )
                        )
                        validation.suggestedPath
                    }
                }
            }

            val msg = when {
                validatedOutputFile != null ->
                    ValidationMessage.Success("output-file validated: $validatedOutputFile")
                config.outputFile == null ->
                    ValidationMessage.Success("${config.commands.size} command(s) validated successfully")
                else ->
                    ValidationMessage.Success("output-file validated successfully")
            }

            _uiState.value = _uiState.value.copy(
                validationMessage = msg,
                validatedOutputFile = validatedOutputFile,
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

    /** Toggles CSV output setting (persists to DataStore). */
    fun toggleCsvOutput() {
        viewModelScope.launch {
            val current = csvDataStore.data.first()[booleanPreferencesKey("csv_output_enabled")] ?: false
            csvDataStore.edit {
                it[booleanPreferencesKey("csv_output_enabled")] = !current
            }
        }
    }

    // ── .running-tasks marker file management ───────────────────────

    /** Creates the .running-tasks marker file in the app's private directory. */
    private fun createRunningFile() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (runningFile.parentFile?.exists() != true) {
                    runningFile.parentFile?.mkdirs()
                }
                runningFile.createNewFile()
            } catch (_: Exception) {
                // Silently ignore — the file is just a marker
            }
        }
    }

    /** Deletes the .running-tasks marker file in the app's private directory. */
    private fun deleteRunningFile() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runningFile.delete()
            } catch (_: Exception) {
                // Silently ignore
            }
        }
    }

    /** Writes results via SAF to the given URI. */
    fun writeFileViaSAF(uri: Uri, results: List<BulkCommandResult>) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isFileWriting = true)
            val success = withContext(Dispatchers.IO) {
                try {
                    val csvEnabled = csvOutputEnabled.value
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        val content = if (csvEnabled) generateCsvContent(results) else generateOutputContent(results)
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

    /**
     * Attempt to write via SAF. Plain file paths (e.g. /sdcard/…) are not valid URI syntax,
     * so [Uri.parse] returns null and [openOutputStream] would NPE. Return false to fall
     * through to [writeDirect], which handles plain paths correctly.
     */
    private suspend fun writeViaSAF(path: String, results: List<BulkCommandResult>): Boolean {
        val uri = android.net.Uri.parse(path)
        // Plain file paths (not file:// URIs) skip SAF and fall through to writeDirect
        if (uri == null) return false
        val csvEnabled = csvOutputEnabled.value
        return try {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                val content = if (csvEnabled) generateCsvContent(results) else generateOutputContent(results)
                outputStream.write(content.toByteArray())
                true
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun writeDirect(path: String, results: List<BulkCommandResult>): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val file = File(path)
                file.parentFile?.mkdirs()
                val csvEnabled = csvOutputEnabled.value
                val content = if (csvEnabled) generateCsvContent(results) else generateOutputContent(results)
                file.writeText(content)
            }
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

    // ── Factory ──────────────────────────────────────────────

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    // Set app context for BulkConfigParser to use private dir path expansion
                    BulkConfigParser.appContext = context.applicationContext
                    return BulkActionsViewModel(
                        context = context.applicationContext,
                        repository = BulkActionsRepository(context.applicationContext),
                    ) as T
                }
            }
    }
}
