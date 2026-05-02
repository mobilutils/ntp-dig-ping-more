package io.github.mobilutils.ntp_dig_ping_more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mobilutils.ntp_dig_ping_more.settings.SettingsKeys

// ─────────────────────────────────────────────────────────────────────────────
// Settings screen
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Global settings screen.
 *
 * Displays a numeric text field to configure the operation timeout that is
 * applied to all network-based tools (NTP, DIG, Ping, Traceroute, Port Scanner,
 * LAN Scanner, Google Time Sync). Changes are saved immediately on valid input.
 * If the field loses focus while invalid the value is reverted to the last
 * known-good setting.
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
