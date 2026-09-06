package io.github.mobilutils.ntp_dig_ping_more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun MoreToolsScreen(
    onNavigate: (String) -> Unit,
    viewModel: MoreToolsViewModel = viewModel(factory = MoreToolsViewModel.factory(LocalContext.current)),
) {
    val uiState by viewModel.uiState.collectAsState()

    MoreToolsContent(
        isMdmConfigured = uiState.isMdmConfigured,
        onNavigate = onNavigate,
    )
}

@Composable
fun MoreToolsContent(
    isMdmConfigured: Boolean,
    onNavigate: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1. Settings
        item(key = AppScreen.Settings.route) {
            ToolCard(
                title = stringResource(AppScreen.Settings.labelResId),
                icon = AppScreen.Settings.icon,
                onClick = { onNavigate(AppScreen.Settings.route) },
            )
        }

        // 2. Bulk Actions
        item(key = AppScreen.BulkActions.route) {
            ToolCard(
                title = stringResource(AppScreen.BulkActions.labelResId),
                icon = AppScreen.BulkActions.icon,
                onClick = { onNavigate(AppScreen.BulkActions.route) },
            )
        }

        // 3. Show MDMConfig(s) — placed directly after Bulk Actions
        item(key = AppScreen.ShowMdmConfigurations.route) {
            ToolCard(
                title = stringResource(R.string.more_tools_show_mdm_configs),
                icon = Icons.Filled.AdminPanelSettings,
                enabled = isMdmConfigured,
                subtitle = stringResource(
                    if (isMdmConfigured) R.string.more_tools_mdm_configured else R.string.more_tools_mdm_not_configured
                ),
                onClick = {
                    if (isMdmConfigured) {
                        onNavigate(AppScreen.ShowMdmConfigurations.route)
                    }
                },
            )
        }

        // 4. Remaining Tools
        val remainingTools = listOf(
            AppScreen.Traceroute,
            AppScreen.PortScanner,
            AppScreen.LanScanner,
            AppScreen.GoogleTimeSync,
            AppScreen.HttpsCert,
            AppScreen.DeviceInfo,
        )

        items(remainingTools, key = { it.route }) { tool ->
            ToolCard(
                title = stringResource(tool.labelResId),
                icon = tool.icon,
                onClick = { onNavigate(tool.route) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolCard(
    title: String,
    icon: ImageVector,
    enabled: Boolean = true,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (enabled) 2.dp else 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
                },
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
                    },
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
                        },
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = stringResource(R.string.common_cd_navigate),
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
                },
            )
        }
    }
}
