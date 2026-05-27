package io.github.mobilutils.ntp_dig_ping_more

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mobilutils.ntp_dig_ping_more.settings.SettingsKeys
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Settings screen
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Global settings screen.
 *
 * Displays:
 *  1. A numeric text field to configure the operation timeout applied to all
 *     network-based tools.
 *  2. A Proxy/PAC configuration section with toggle, PAC URL/File segmented toggle,
 *      PAC input field, logging toggle, log viewer, and a "Test Proxy/PAC" button.
 *
 * Changes are saved immediately on valid input. If the timeout field loses
 * focus while invalid, the value is reverted to the last known-good setting.
 *
 * The top app bar (title = "Settings") is rendered by [AppRoot]'s shared
 * [Scaffold] — this composable only fills the inner content area.
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context))
    val uiState by viewModel.uiState.collectAsState()

      // SAF launcher for PAC file selection
    val pacFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let { selectedUri ->
            viewModel.onPacFileSelected(selectedUri)
          }
     }

    Column(
        modifier = Modifier
             .fillMaxSize()
             .verticalScroll(rememberScrollState())
             .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
     ) {

          // ── Network Configuration section ─────────────────────────────────────
        SettingsSectionCard(title = stringResource(R.string.settings_section_network)) {

              // Timeout field
            OutlinedTextField(
                value = uiState.timeoutInput,
                onValueChange = viewModel::onTimeoutChange,
                modifier = Modifier
                     .fillMaxWidth()
                     .onFocusChanged { focusState ->
                          // Revert if focus leaves while input is still invalid
                        if (!focusState.isFocused && uiState.isError) {
                            viewModel.revert()
                         }
                     },
                label = { Text(stringResource(R.string.settings_label_timeout)) },
                placeholder = { Text(SettingsKeys.DEFAULT_TIMEOUT_SECONDS.toString()) },
                singleLine = true,
                isError = uiState.isError,
                supportingText = {
                    if (uiState.isError) {
                        Text(
                            text = stringResource(
                                R.string.settings_timeout_error,
                                SettingsKeys.MIN_TIMEOUT_SECONDS,
                                SettingsKeys.MAX_TIMEOUT_SECONDS,
                            ),
                            color = MaterialTheme.colorScheme.error,
                         )
                     } else {
                        Text(
                            text = stringResource(R.string.settings_timeout_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                         )
                     }
                 },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
             )
         }

          // ── Proxy Configuration section ───────────────────────────────────────
        SettingsSectionCard(title = stringResource(R.string.settings_section_proxy)) {

              // Enable/disable toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
             ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_proxy_enable_label),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                     )
                    Text(
                        text = stringResource(R.string.settings_proxy_enable_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                     )
                  }
                Switch(
                    checked = uiState.proxyEnabled,
                    onCheckedChange = viewModel::onProxyEnabledChange,
                 )
             }

            Spacer(Modifier.height(16.dp))

              // PAC source mode toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
             ) {
                FilterChip(
                    selected = uiState.pacSourceMode == PacSourceMode.URL,
                    onClick = { viewModel.onPacSourceModeChange(PacSourceMode.URL) },
                    label = { Text(stringResource(R.string.settings_proxy_pac_mode_url)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                      ),
                  )

                FilterChip(
                    selected = uiState.pacSourceMode == PacSourceMode.FILE,
                    onClick = { viewModel.onPacSourceModeChange(PacSourceMode.FILE) },
                    label = { Text(stringResource(R.string.settings_proxy_pac_mode_file)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                      ),
                  )
             }

            Spacer(Modifier.height(8.dp))

              // PAC URL/File input — mode-aware
            OutlinedTextField(
                value = uiState.proxyPacUrl,
                onValueChange = viewModel::onProxyPacUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        when (uiState.pacSourceMode) {
                            PacSourceMode.URL  -> stringResource(R.string.settings_proxy_pac_label_url)
                            PacSourceMode.FILE -> stringResource(R.string.settings_proxy_pac_label_file)
                          }
                      )
                  },
                placeholder = {
                    Text(
                        when (uiState.pacSourceMode) {
                            PacSourceMode.URL  -> stringResource(R.string.settings_proxy_pac_placeholder_url)
                            PacSourceMode.FILE -> stringResource(R.string.settings_proxy_pac_placeholder_file)
                          }
                      )
                  },
                singleLine = true,
                enabled = uiState.proxyEnabled,
                isError = uiState.proxyPacUrlError != null,
                supportingText = {
                    when {
                        uiState.proxyPacUrlError != null -> Text(
                            text = stringResource(uiState.proxyPacUrlError!!),
                            color = MaterialTheme.colorScheme.error,
                         )
                        uiState.proxyEnabled && uiState.pacSourceMode == PacSourceMode.FILE &&
                             uiState.proxyPacUrl.isNotBlank() && !uiState.proxyPacUrl.startsWith("content://") -> {
                            val fileName = File(uiState.proxyPacUrl).name
                            Text(
                                text = stringResource(R.string.settings_proxy_pac_hint_local_file, fileName),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                              )
                           }
                        uiState.proxyEnabled && uiState.pacSourceMode == PacSourceMode.FILE -> Text(
                            text = stringResource(R.string.settings_proxy_pac_hint_file),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                          )
                        uiState.proxyEnabled -> Text(
                            text = stringResource(R.string.settings_proxy_pac_hint_url),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                          )
                      }
                  },
                keyboardOptions = when (uiState.pacSourceMode) {
                    PacSourceMode.URL -> KeyboardOptions(keyboardType = KeyboardType.Uri)
                    PacSourceMode.FILE -> KeyboardOptions(keyboardType = KeyboardType.Text)
                  },
                trailingIcon = when (uiState.pacSourceMode) {
                    PacSourceMode.URL -> null         // No trailing icon for URL mode
                    PacSourceMode.FILE -> {
                        if (uiState.proxyEnabled) {
                            {
                                TextButton(
                                    onClick = { pacFilePickerLauncher.launch("*/*") },
                                  ) {
                                    Text(stringResource(R.string.settings_proxy_pac_btn_browse))
                                   }
                              }
                          } else null
                      }
                  },
             )

            Spacer(Modifier.height(8.dp))

              // ── Proxy logging toggle ──────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
             ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_proxy_logging_label),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                     )
                    Text(
                        text = stringResource(R.string.settings_proxy_logging_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                     )
                  }
                Switch(
                    checked = uiState.proxyLoggingEnabled,
                    onCheckedChange = viewModel::onProxyLoggingEnabledChange,
                    enabled = uiState.proxyEnabled,
                 )
             }

              // Log action buttons
            Row(
                modifier = Modifier
                     .fillMaxWidth()
                     .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
             ) {
                TextButton(
                    onClick = viewModel::onViewLogs,
                    enabled = uiState.proxyEnabled,
                  ) {
                    Text(stringResource(R.string.settings_proxy_btn_view_logs))
                  }
                TextButton(
                    onClick = viewModel::onClearLogs,
                    enabled = uiState.proxyEnabled,
                  ) {
                    Text(stringResource(R.string.settings_proxy_btn_clear_logs))
                  }
             }

            Spacer(Modifier.height(8.dp))

              // Test Proxy/PAC button
            Button(
                onClick = viewModel::testProxy,
                enabled = uiState.proxyEnabled &&
                        uiState.proxyPacUrl.isNotBlank() &&
                        uiState.proxyPacUrlError == null &&
                         !uiState.isTestingProxy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                  ),
             ) {
                if (uiState.isTestingProxy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondary,
                     )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_proxy_btn_testing))
                  } else {
                    Text(stringResource(R.string.settings_proxy_btn_test), fontWeight = FontWeight.Medium)
                  }
             }

              // Last test result
            if (uiState.proxyTestResult != null) {
                Spacer(Modifier.height(12.dp))

                val isSuccess = uiState.proxyTestResult!!.startsWith("✓")
                Text(
                    text = uiState.proxyTestResult!!,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = if (isSuccess) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.error,
                 )

                if (uiState.proxyLastTested > 0) {
                    val formatted = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
                         .format(Date(uiState.proxyLastTested))
                    Text(
                        text = stringResource(R.string.settings_proxy_last_tested, formatted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                  }
              }
         }
     }

      // ── Log viewer dialog ─────────────────────────────────────────────────────
    if (uiState.showLogDialog) {
        AlertDialog(
            onDismissRequest = viewModel::onDismissLogDialog,
            title = { Text(stringResource(R.string.settings_log_dialog_title)) },
            text = {
                if (uiState.proxyLogs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_log_dialog_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                     )
                  } else {
                    LazyColumn(
                        modifier = Modifier.height(400.dp),
                      ) {
                        items(uiState.proxyLogs) { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                  ),
                                modifier = Modifier.padding(vertical = 1.dp),
                              )
                          }
                      }
                  }
              },
            confirmButton = {
                TextButton(onClick = viewModel::onDismissLogDialog) {
                    Text(stringResource(R.string.common_btn_close))
                  }
              },
          )
      }
}

// ─────────────────────────────────────────────────────────────────────────────
// Private composables
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A card that groups related settings under a [title] header.
 *
 * @param title   Section header label.
 * @param content Slot for the settings controls inside the card.
 */
@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
          ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
     ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
              )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
              )
            Spacer(Modifier.height(16.dp))
            content()
          }
      }
}
