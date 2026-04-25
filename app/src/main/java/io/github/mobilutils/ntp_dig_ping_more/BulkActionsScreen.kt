package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

// ────────────────────────────────────────────────────────────────────
// Screen
// ────────────────────────────────────────────────────────────────────

@Composable
fun BulkActionsScreen() {
    val context = LocalContext.current
    val viewModel: BulkActionsViewModel = viewModel(factory = BulkActionsViewModel.factory(context))
    val uiState by viewModel.uiState.collectAsState()
    val currentUiState by rememberUpdatedState(uiState)

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val fileName = it.lastPathSegment ?: "config.json"
            viewModel.onFileSelected(it, fileName)
        }
    }

    // SAF launcher for output file
    val outputLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.writeFileViaSAF(uri, currentUiState.results)
        }
    }

    // Wire up launcher to ViewModel
    LaunchedEffect(outputLauncher) {
        viewModel.setOutputLauncher(outputLauncher)
    }

    // Validation message display
    val validationMsg = uiState.validationMessage
    LaunchedEffect(validationMsg) {
        // Auto-dismiss info messages after 3 seconds
        if (validationMsg is BulkActionsViewModel.ValidationMessage.Info) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearValidationMessage()
        }
    }

    // Helper to format validation message
    fun formatValidationMessage(msg: BulkActionsViewModel.ValidationMessage): String {
        return when (msg) {
            is BulkActionsViewModel.ValidationMessage.Info -> msg.text
            is BulkActionsViewModel.ValidationMessage.Success -> "✓ $msg.text"
            is BulkActionsViewModel.ValidationMessage.Error -> "✗ $msg.text"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Title ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = "Bulk Actions",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        // ── Load Config Button ──
        Button(
            onClick = { filePickerLauncher.launch("application/json") },
            enabled = !uiState.isExecuting,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.FileOpen,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Load JSON Config", fontWeight = FontWeight.Medium)
        }

        // ── Config Loaded Summary ──
        AnimatedVisibility(
            visible = uiState.configLoaded,
            enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 },
            exit = fadeOut(tween(200)),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Config loaded",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${uiState.configFileName} — ${uiState.commandCount} command(s)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = viewModel::validateConfig,
                        enabled = !uiState.isExecuting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Verified,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Validate Config", fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // ── Validation Message ──
        AnimatedVisibility(
            visible = uiState.validationMessage != null,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            uiState.validationMessage?.let { msg ->
                val (icon, bgColor, textColor) = when (msg) {
                    is BulkActionsViewModel.ValidationMessage.Info ->
                        Triple(Icons.Filled.Info, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    is BulkActionsViewModel.ValidationMessage.Success ->
                        Triple(Icons.Filled.CheckCircle, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                    is BulkActionsViewModel.ValidationMessage.Error ->
                        Triple(Icons.Filled.Warning, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = bgColor),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = formatValidationMessage(msg),
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor,
                        )
                    }
                }
            }
        }

        // ── Run / Stop Button ──
        Button(
            onClick = {
                if (uiState.isExecuting) {
                    viewModel.onStopClicked()
                } else {
                    viewModel.onRunClicked()
                }
            },
            enabled = uiState.configLoaded || uiState.isExecuting,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = if (uiState.isExecuting)
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            else
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
        ) {
            if (uiState.isExecuting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onError,
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = "Stop",
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Stop", fontWeight = FontWeight.Medium)
            } else {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Run",
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Run All Commands", fontWeight = FontWeight.Medium)
            }
        }

        // ── Progress Bar ──
        AnimatedVisibility(
            visible = uiState.isExecuting,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LinearProgressIndicator(
                    progress = { uiState.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer,
                )
                uiState.currentCommand?.let { cmd ->
                    Text(
                        text = cmd,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        }

        // ── Results ──
        AnimatedVisibility(
            visible = uiState.results.isNotEmpty(),
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(200)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Results (${uiState.results.count { it is BulkCommandSuccess }}/ ${uiState.results.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedButton(
                        onClick = viewModel::onClearResults,
                        enabled = !uiState.isFileWriting,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Clear")
                    }
                }

                // Output terminal card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .background(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                                shape = RoundedCornerShape(12.dp),
                            ),
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            itemsIndexed(uiState.results) { index, result ->
                                ResultItem(result)
                            }
                        }
                    }
                }

                // Write to file button
                AnimatedVisibility(
                    visible = uiState.results.any { it is BulkCommandSuccess },
                    enter = fadeIn(tween(200)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                val filename = "bulk-output-${System.currentTimeMillis()}.txt"
                                outputLauncher.launch(filename)
                            },
                            enabled = !uiState.isFileWriting,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                            ),
                        ) {
                            if (uiState.isFileWriting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onTertiary,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Writing…")
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.UploadFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Write to File", fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                // ── Green success card for auto-save ──
                AnimatedVisibility(
                    visible = uiState.autoSaved && uiState.autoSavedPath != null,
                    enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 },
                    exit = fadeOut(tween(200)),
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "File saved to: ${uiState.autoSavedPath}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }
        }

        // ── Error banner ──
        AnimatedVisibility(
            visible = uiState.outputFileWritten == false,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "Failed to write output file. Check permissions or try saving to a different location.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────
// Result item
// ────────────────────────────────────────────────────────────────────

@Composable
private fun ResultItem(result: BulkCommandResult) {
    val (icon, statusText, statusColor) = when (result) {
        is BulkCommandSuccess ->
            Triple(Icons.Filled.CheckCircle, "SUCCESS", MaterialTheme.colorScheme.secondary)
        is BulkCommandError ->
            Triple(Icons.Filled.Error, "ERROR", MaterialTheme.colorScheme.error)
        is BulkCommandTimeout ->
            Triple(Icons.Filled.Close, "TIMEOUT", MaterialTheme.colorScheme.tertiary)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Header row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = statusText,
                    tint = statusColor,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = result.commandName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
                Text(
                    text = "(${result.command})",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }

            // Output lines
            if (result is BulkCommandSuccess) {
                result.outputLines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            } else if (result is BulkCommandError) {
                Text(
                    text = result.errorMessage,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                )
            } else if (result is BulkCommandTimeout) {
                Text(
                    text = "Command timed out after 30 seconds",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────
// Preview
// ────────────────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun BulkActionsScreenPreview() {
    // This is a visual preview only — the ViewModel won't function in preview
}
