package io.github.mobilutils.ntp_dig_ping_more.deviceinfo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mobilutils.ntp_dig_ping_more.R
import io.github.mobilutils.ntp_dig_ping_more.ui.theme.NtpDigPingMoreTheme

/**
 * Required runtime permissions for the Device Info screen.
 *
 * ACCESS_NETWORK_STATE: Normal permission, auto-granted at install.
 * ACCESS_WIFI_STATE: Normal permission, auto-granted at install.
 * ACCESS_COARSE_LOCATION: Dangerous permission, requires runtime request (Android 10+ for Wi-Fi SSID).
 * ACCESS_FINE_LOCATION: Dangerous permission, required for Wi-Fi SSID on Android 10+.
 * READ_PHONE_STATE: Dangerous permission, required for carrier name on Android 10+.
 *
 * Note: ACCESS_COARSE_LOCATION and ACCESS_FINE_LOCATION are grouped together for the runtime request.
 * READ_PHONE_STATE is requested separately.
 */
private val locationPermissions = listOf(
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.ACCESS_FINE_LOCATION,
).toTypedArray()

private val phonePermission = Manifest.permission.READ_PHONE_STATE

/**
 * Composable entry point for the Device Info screen.
 *
 * Uses [rememberLauncherForActivityResult] with [ActivityResultContracts] to request
 * the necessary runtime permissions. The ViewModel handles data loading and periodic updates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceInfoScreen(
    viewModel: DeviceInfoViewModel = viewModel(
        factory = DeviceInfoViewModel.factory(LocalContext.current)
    ),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Track whether we have shown the rationale already
    var shouldShowRationale by remember { mutableStateOf(false) }

    // ── Permission launcher for location permissions (Wi-Fi SSID) ─────
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { grants ->
            val allGranted = grants.values.all { it }
            viewModel.onPermissionsResult(allGranted)
            if (!allGranted) {
                shouldShowRationale = true
            }
        }
    )

    // ── Permission launcher for phone permission (carrier name) ───────
    val phonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (!granted) {
                shouldShowRationale = true
            }
        }
    )

    // ── Request permissions on first launch ───────────────────────────
    LaunchedEffect(Unit) {
        val locationGranted = locationPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        val phoneGranted = ContextCompat.checkSelfPermission(
            context, phonePermission
        ) == PackageManager.PERMISSION_GRANTED

        viewModel.onPermissionsResult(locationGranted && phoneGranted)

        if (!locationGranted) {
            locationPermissionLauncher.launch(locationPermissions)
        }
        if (!phoneGranted) {
            phonePermissionLauncher.launch(phonePermission)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.device_info_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                actions = {
                    IconButton(onClick = { viewModel.onPermissionsResult(true) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_cd_refresh))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (val state = uiState) {
            is DeviceInfoState.Loading -> {
                LoadingContent(modifier = Modifier.padding(innerPadding))
            }
            is DeviceInfoState.Success -> {
                DeviceInfoContent(
                    deviceInfo = state.deviceInfo,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            is DeviceInfoState.PermissionDenied -> {
                PermissionDeniedContent(
                    message = state.message,
                    modifier = Modifier.padding(innerPadding),
                    onGrantPermissions = {
                        locationPermissionLauncher.launch(locationPermissions)
                        phonePermissionLauncher.launch(phonePermission)
                    }
                )
            }
            is DeviceInfoState.Error -> {
                ErrorContent(
                    message = state.message,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }

        // Show rationale snackbar if permissions were denied
        if (shouldShowRationale) {
            val message = stringResource(R.string.device_info_snackbar_permissions_denied)
            val actionLabel = stringResource(R.string.device_info_snackbar_dismiss)
            LaunchedEffect(shouldShowRationale) {
                snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = actionLabel
                )
                shouldShowRationale = false
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Loading state
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.device_info_loading),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Permission denied state
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PermissionDeniedContent(
    message: String,
    modifier: Modifier = Modifier,
    onGrantPermissions: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.device_info_permissions_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onGrantPermissions,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(stringResource(R.string.device_info_btn_grant_permissions))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Error state
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorContent(
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.common_label_error),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main device info content
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DeviceInfoContent(
    deviceInfo: DeviceInfo,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Device Identity ────────────────────────────────────────────
        item {
            InfoCard(
                title = stringResource(R.string.device_info_section_device),
                icon = Icons.Filled.Smartphone,
                items = listOfNotNull(
                    InfoItem(stringResource(R.string.device_info_field_device), deviceInfo.deviceName),
                    InfoItem(stringResource(R.string.device_info_field_imei), deviceInfo.imei),
                    InfoItem(stringResource(R.string.device_info_field_serial), deviceInfo.serialNumber),
                    InfoItem(stringResource(R.string.device_info_field_iccid), deviceInfo.iccid),
                    InfoItem(stringResource(R.string.device_info_field_android_version), deviceInfo.androidVersion),
                    InfoItem(stringResource(R.string.device_info_field_api_level), deviceInfo.apiLevel?.toString()),
                    InfoItem(stringResource(R.string.device_info_field_cpu_arch), deviceInfo.cpuAbi.joinToString(", ")),
                )
            )
        }

        // ── Time & Uptime ──────────────────────────────────────────────
        item {
            InfoCard(
                title = stringResource(R.string.device_info_section_time_uptime),
                icon = Icons.Filled.Timer,
                items = listOfNotNull(
                    InfoItem(stringResource(R.string.device_info_field_current_time), deviceInfo.deviceTime),
                    InfoItem(stringResource(R.string.device_info_field_time_since_boot), deviceInfo.timeSinceReboot),
                    InfoItem(stringResource(R.string.device_info_field_since_screen_off), deviceInfo.timeSinceScreenOff),
                )
            )
        }

        // ── Network Information ────────────────────────────────────────
        item {
            InfoCard(
                title = stringResource(R.string.device_info_section_network),
                icon = Icons.Filled.Language,
                items = listOfNotNull(
                    InfoItem(stringResource(R.string.device_info_field_active_network), deviceInfo.activeNetworkType),
                    InfoItem(stringResource(R.string.device_info_field_ipv4), deviceInfo.ipv4Address),
                    InfoItem(stringResource(R.string.device_info_field_ipv6), deviceInfo.ipv6Address),
                    InfoItem(stringResource(R.string.device_info_field_subnet_mask), deviceInfo.subnetMask),
                    InfoItem(stringResource(R.string.device_info_field_gateway), deviceInfo.defaultGateway),
                    InfoItem(stringResource(R.string.device_info_field_dns_servers), deviceInfo.dnsServers.joinToString(", ").takeIf { it.isNotEmpty() }),
                    InfoItem(stringResource(R.string.device_info_field_ntp_server), deviceInfo.ntpServer),
                )
            )
        }

        // ── Wi-Fi ──────────────────────────────────────────────────────
        item {
            InfoCard(
                title = stringResource(R.string.device_info_section_wifi),
                icon = Icons.Filled.Wifi,
                items = listOfNotNull(
                    InfoItem(stringResource(R.string.device_info_field_wifi_ssid), deviceInfo.wifiSSID),
                )
            )
        }

        // ── Mobile Carrier ─────────────────────────────────────────────
        item {
            InfoCard(
                title = stringResource(R.string.device_info_section_mobile_carrier),
                icon = Icons.Filled.CellTower,
                items = listOfNotNull(
                    InfoItem(stringResource(R.string.device_info_field_carrier), deviceInfo.carrierName),
                )
            )
        }

        // ── Battery ────────────────────────────────────────────────────
        item {
            InfoCard(
                title = stringResource(R.string.device_info_section_battery),
                icon = Icons.Filled.BatteryChargingFull,
                items = listOfNotNull(
                    InfoItem(stringResource(R.string.device_info_field_battery_level), deviceInfo.batteryLevel?.let { "$it%" }),
                    InfoItem(stringResource(R.string.device_info_field_charging), deviceInfo.isCharging?.let { if (it) stringResource(R.string.device_info_field_battery_yes) else stringResource(R.string.device_info_field_battery_no) }),
                    InfoItem(stringResource(R.string.device_info_field_health), deviceInfo.batteryHealth),
                )
            )
        }

        // ── Memory & Storage ───────────────────────────────────────────
        item {
            InfoCard(
                title = stringResource(R.string.device_info_section_memory_storage),
                icon = Icons.Filled.Memory,
                items = listOfNotNull(
                    InfoItem(stringResource(R.string.device_info_field_total_ram), deviceInfo.totalRam),
                    InfoItem(stringResource(R.string.device_info_field_available_ram), deviceInfo.availableRam),
                    InfoItem(stringResource(R.string.device_info_field_total_storage), deviceInfo.totalStorage),
                    InfoItem(stringResource(R.string.device_info_field_available_storage), deviceInfo.availableStorage),
                )
            )
        }

        // ── Security & MDM ─────────────────────────────────────────────
        item {
            MdmStatusCard(deviceInfo = deviceInfo)
        }

    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MDM Status card
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Security & MDM card that prominently displays a shield badge when the app
 * is operating under active MDM restrictions, or shows a plain "Not managed"
 * row otherwise.
 */
@Composable
private fun MdmStatusCard(
    deviceInfo: DeviceInfo,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Card title row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = stringResource(R.string.device_info_section_security_mdm),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))

            if (deviceInfo.isAppManaged) {
                // ── Managed badge ─────────────────────────────────────
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "🛡️",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.device_info_mdm_managed_badge),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Status detail row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = stringResource(R.string.device_info_mdm_status_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = deviceInfo.mdmStatus ?: "Managed (restrictions active)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1.5f),
                    )
                }
            } else {
                // ── Not managed row ───────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = stringResource(R.string.device_info_mdm_status_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.device_info_mdm_not_managed),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.weight(1.5f),
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reusable info card composable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A single key-value pair for display in an info card.
 */
private data class InfoItem(
    val label: String,
    val value: String?,
)

/**
 * A card containing a list of [InfoItem]s with a title and icon.
 * Used to group related device information together.
 */
@Composable
private fun InfoCard(
    title: String,
    icon: ImageVector,
    items: List<InfoItem>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Title row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Info rows
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    Spacer(Modifier.height(8.dp))
                }
                InfoRow(item = item)
            }
        }
    }
}

/**
 * A single label-value row within an [InfoCard].
 */
@Composable
private fun InfoRow(item: InfoItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        val displayValue = item.value ?: stringResource(R.string.common_label_unavailable)
        val valueColor = if (item.value == null)
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else
            MaterialTheme.colorScheme.onSurface

        Text(
            text = displayValue,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = if (item.label.contains("IP") || item.label.contains("DNS") || item.label.contains("NTP") || item.label.contains("Mask") || item.label.contains("Gateway"))
                    FontFamily.Monospace
                else
                    FontFamily.Default,
            ),
            fontWeight = FontWeight.Medium,
            color = valueColor,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.5f),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Certificate card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CertificateCard(
    certificates: List<CertificateInfo>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = stringResource(R.string.device_info_section_installed_certs, certificates.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Limit displayed certificates to avoid UI lag (first 20)
            val displayCerts = certificates.take(20)
            displayCerts.forEachIndexed { index, cert ->
                if (index > 0) {
                    Spacer(Modifier.height(12.dp))
                }
                CertificateRow(cert = cert)
            }

            if (certificates.size > 20) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.device_info_section_certs_more, certificates.size - 20),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun CertificateRow(cert: CertificateInfo) {
    val typeColor = when (cert.type) {
        "User" -> MaterialTheme.colorScheme.tertiary
        "System" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = cert.type,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = typeColor,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = cert.subject,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.device_info_cert_valid_range, cert.notBefore, cert.notAfter),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Preview
// ─────────────────────────────────────────────────────────────────────────────

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun DeviceInfoScreenPreview() {
    NtpDigPingMoreTheme {
        DeviceInfoContent(
            deviceInfo = DeviceInfo(
                deviceName = "Google Pixel 7",
                imei = "35 123456 789012 3",
                serialNumber = "Restricted by Android 10+",
                iccid = "8901 2601 2345 6789 0123",
                deviceTime = "2026-04-17 14:30:00",
                ipv4Address = "192.168.1.105",
                ipv6Address = "fe80::1234:5678:abcd:ef01",
                subnetMask = "255.255.255.0",
                defaultGateway = "192.168.1.1",
                ntpServer = "time.android.com",
                dnsServers = listOf("8.8.8.8", "8.8.4.4"),
                carrierName = "T-Mobile US",
                wifiSSID = "MyHomeNetwork",
                timeSinceReboot = "3d 5h 12m 30s",
                timeSinceScreenOff = "Not exposed by OS",
                mdmStatus = "None",
                androidVersion = "14",
                apiLevel = 34,
                batteryLevel = 78,
                isCharging = true,
                batteryHealth = "Good",
                totalRam = "8.0 GB",
                availableRam = "3.2 GB",
                totalStorage = "128.0 GB",
                availableStorage = "45.3 GB",
                cpuAbi = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
                activeNetworkType = "Wi-Fi",
            )
        )
    }
}
