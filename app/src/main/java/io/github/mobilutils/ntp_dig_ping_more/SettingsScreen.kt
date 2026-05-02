package io.github.mobilutils.ntp_dig_ping_more

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mobilutils.ntp_dig_ping_more.settings.SettingsKeys
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
 *  2. A Proxy/PAC configuration section with toggle, PAC URL input, and a
 *     "Test Proxy/PAC" button.
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {

        // ── Network Configuration section ─────────────────────────────────────
        SettingsSectionCard(title = "Network Configuration") {

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
                label = { Text("Operation Timeout (seconds)") },
                placeholder = { Text(SettingsKeys.DEFAULT_TIMEOUT_SECONDS.toString()) },
                singleLine = true,
                isError = uiState.isError,
                supportingText = {
                    if (uiState.isError) {
                        Text(
                            text = "Must be between ${SettingsKeys.MIN_TIMEOUT_SECONDS} " +
                                   "and ${SettingsKeys.MAX_TIMEOUT_SECONDS}",
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            text = "Applies to total duration of scans/requests.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }

        // ── Proxy Configuration section ───────────────────────────────────────
        SettingsSectionCard(title = "Proxy Configuration") {

            // Enable/disable toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enable Proxy",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Route traffic through a PAC-resolved proxy",
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

            // PAC URL input
            OutlinedTextField(
                value = uiState.proxyPacUrl,
                onValueChange = viewModel::onProxyPacUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("PAC URL") },
                placeholder = { Text("http://proxy.corp.com/proxy.pac") },
                singleLine = true,
                enabled = uiState.proxyEnabled,
                isError = uiState.proxyPacUrlError != null,
                supportingText = {
                    when {
                        uiState.proxyPacUrlError != null -> Text(
                            text = uiState.proxyPacUrlError!!,
                            color = MaterialTheme.colorScheme.error,
                        )
                        uiState.proxyEnabled -> Text(
                            text = "URL to an auto-configuration (.pac) script",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )

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
                    Text("Testing…")
                } else {
                    Text("Test Proxy/PAC", fontWeight = FontWeight.Medium)
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
                        text = "Last tested: $formatted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
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
