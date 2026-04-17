package io.github.mobilutils.ntp_dig_ping_more

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.mobilutils.ntp_dig_ping_more.deviceinfo.DeviceInfoScreen
import io.github.mobilutils.ntp_dig_ping_more.ui.theme.NtpDigPingMoreTheme

// ─────────────────────────────────────────────────────────────────────────────
// Navigation destinations
// ─────────────────────────────────────────────────────────────────────────────

sealed class AppScreen(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    object NtpCheck    : AppScreen("ntp_check",   "NTP Check",  Icons.Filled.NetworkCheck)
    object DigTest     : AppScreen("dig_test",    "DIG Test",   Icons.Filled.Dns)
    object Ping        : AppScreen("ping",        "Ping",       Icons.Filled.Terminal)
    object Traceroute  : AppScreen("traceroute",  "Traceroute", Icons.Filled.Route)
    object PortScanner : AppScreen("port_scanner", "Port Scan", Icons.Filled.Search)
    object LanScanner     : AppScreen("lan_scanner",      "LAN Scan",         Icons.Filled.WifiFind)
    object GoogleTimeSync : AppScreen("google_time_sync", "Google Time Sync", Icons.Filled.AccessTime)
    object HttpsCert      : AppScreen("https_cert",       "HTTPS Cert",       Icons.Filled.Lock)
    object DeviceInfo     : AppScreen("device_info",      "Device Info",      Icons.Filled.Info)
    object MoreTools      : AppScreen("more_tools",       "More",             Icons.Filled.MoreHoriz)
}

private val allAppScreens = listOf(
    AppScreen.NtpCheck,
    AppScreen.DigTest,
    AppScreen.Ping,
    AppScreen.Traceroute,
    AppScreen.PortScanner,
    AppScreen.LanScanner,
    AppScreen.GoogleTimeSync,
    AppScreen.HttpsCert,
    AppScreen.DeviceInfo,
    AppScreen.MoreTools,
)

private val bottomNavItems = listOf(
    AppScreen.NtpCheck,
    AppScreen.DigTest,
    AppScreen.Ping,
    AppScreen.MoreTools,
)

// ─────────────────────────────────────────────────────────────────────────────
// Activity
// ─────────────────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NtpDigPingMoreTheme {
                AppRoot()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Root composable with NavHost + bottom bar
// ─────────────────────────────────────────────────────────────────────────────

@Preview
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = navBackStackEntry?.destination

    // Derive the current screen for the top bar title
    val currentScreen = allAppScreens.firstOrNull { screen ->
        currentDest?.hierarchy?.any { it.route == screen.route } == true
    } ?: AppScreen.NtpCheck

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = currentScreen.icon,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(currentScreen.label, fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                bottomNavItems.forEach { screen ->
                    val isMoreToolsSelected = screen == AppScreen.MoreTools && currentDest?.route in listOf(
                        AppScreen.Traceroute.route,
                        AppScreen.PortScanner.route,
                        AppScreen.LanScanner.route,
                        AppScreen.GoogleTimeSync.route,
                        AppScreen.HttpsCert.route,
                        AppScreen.DeviceInfo.route,
                        AppScreen.MoreTools.route
                    )
                    val selected = currentDest?.hierarchy?.any { it.route == screen.route } == true || isMoreToolsSelected
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = screen.label,
                            )
                        },
                        label = { Text(screen.label) },
                        selected = selected,
                        onClick = {
                            if (selected) {
                                // If clicking the already selected tab, act like a "pop to root" of that tab
                                navController.popBackStack(screen.route, inclusive = false)
                            } else {
                                navController.navigate(screen.route) {
                                    // Avoid building up a large back-stack
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppScreen.NtpCheck.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(AppScreen.NtpCheck.route) {
                NtpCheckScreen()
            }
            composable(AppScreen.DigTest.route) {
                DigScreen()
            }
            composable(AppScreen.Ping.route) {
                PingScreen()
            }
            composable(AppScreen.Traceroute.route) {
                TracerouteScreen()
            }
            composable(AppScreen.PortScanner.route) {
                PortScannerScreen()
            }
            composable(AppScreen.LanScanner.route) {
                LanScannerScreen()
            }
            composable(AppScreen.GoogleTimeSync.route) {
                GoogleTimeSyncScreen()
            }
            composable(AppScreen.HttpsCert.route) {
                HttpsCertScreen()
            }
            composable(AppScreen.DeviceInfo.route) {
                DeviceInfoScreen()
            }
            composable(AppScreen.MoreTools.route) {
                MoreToolsScreen(
                    onNavigate = { route ->
                        navController.navigate(route) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NTP Check screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NtpCheckScreen() {
    val context = LocalContext.current
    val viewModel: SimpleNtpViewModel = viewModel(factory = SimpleNtpViewModel.factory(context))
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Server Address + Port Row ─────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            OutlinedTextField(
                value = uiState.serverAddress,
                onValueChange = viewModel::onServerAddressChange,
                label = { Text("NTP Server Address") },
                placeholder = { Text("e.g. pool.ntp.org") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                isError = uiState.result is NtpResult.Error ||
                        uiState.result is NtpResult.DnsFailure,
                supportingText = {
                    when (val r = uiState.result) {
                        is NtpResult.DnsFailure ->
                            Text("Cannot resolve \"${r.host}\"", color = MaterialTheme.colorScheme.error)
                        is NtpResult.Error ->
                            Text(r.message, color = MaterialTheme.colorScheme.error)
                        else -> {}
                    }
                },
            )
            OutlinedTextField(
                value = uiState.port,
                onValueChange = viewModel::onPortChange,
                label = { Text("Port") },
                placeholder = { Text("123") },
                singleLine = true,
                modifier = Modifier.width(90.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(
                    onGo = {
                        focusManager.clearFocus()
                        viewModel.checkReachability()
                    },
                ),
            )
        }

        // ── Check Button ─────────────────────────────────────────────
        Button(
            onClick = {
                focusManager.clearFocus()
                viewModel.checkReachability()
            },
            enabled = !uiState.isLoading && uiState.serverAddress.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text("Querying…")
            } else {
                Text("Check Reachability", fontWeight = FontWeight.Medium)
            }
        }

        // ── Results Card ─────────────────────────────────────────────
        AnimatedVisibility(
            visible = uiState.result != null,
            enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 },
            exit  = fadeOut(tween(200)),
        ) {
            uiState.result?.let { result ->
                ResultCard(result)
            }
        }

        // ── Query History ─────────────────────────────────────────────
        AnimatedVisibility(
            visible = uiState.history.isNotEmpty(),
            enter = fadeIn(tween(400)),
            exit  = fadeOut(tween(200)),
        ) {
            HistorySection(
                entries = uiState.history,
                onEntryClick = { entry ->
                    focusManager.clearFocus()
                    viewModel.selectHistoryEntry(entry)
                },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Result card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ResultCard(result: NtpResult) {
    val isSuccess = result is NtpResult.Success
    val cardColor = if (isSuccess)
        MaterialTheme.colorScheme.secondaryContainer
    else
        MaterialTheme.colorScheme.errorContainer

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            StatusHeader(result)

            if (result is NtpResult.Success) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f))
                Spacer(Modifier.height(16.dp))

                MetricRow(label = "Server Time",       value = result.serverTime)
                Spacer(Modifier.height(10.dp))
                MetricRow(label = "Clock Offset",      value = "${result.offsetMs} ms")
                Spacer(Modifier.height(10.dp))
                MetricRow(label = "Round-Trip Delay",  value = "${result.delayMs} ms")
            }

            if (result is NtpResult.NoNetwork) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Please check your internet connection and try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            if (result is NtpResult.Timeout) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "The server did not respond within the timeout window (5 s). " +
                           "It may be offline, firewalled, or unreachable from this network.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun StatusHeader(result: NtpResult) {
    val (icon, label, tint) = when (result) {
        is NtpResult.Success    ->
            Triple(Icons.Filled.CheckCircle, "Reachable",   MaterialTheme.colorScheme.secondary)
        is NtpResult.NoNetwork  ->
            Triple(Icons.Filled.WifiOff,     "No Network",  MaterialTheme.colorScheme.error)
        is NtpResult.Timeout    ->
            Triple(Icons.Filled.Error,       "Unreachable – Timeout", MaterialTheme.colorScheme.error)
        is NtpResult.DnsFailure ->
            Triple(Icons.Filled.Error,       "Unreachable – DNS Failure", MaterialTheme.colorScheme.error)
        is NtpResult.Error      ->
            Triple(Icons.Filled.Error,       "Error",        MaterialTheme.colorScheme.error)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = "Status",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1.5f),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// History section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HistorySection(
    entries: List<NtpHistoryEntry>,
    onEntryClick: (NtpHistoryEntry) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Recent Queries",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))

            entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f))
                }
                HistoryRow(entry = entry, onClick = { onEntryClick(entry) })
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: NtpHistoryEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.timestamp,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "\tntp://${entry.server}:${entry.port}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = if (entry.success) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = if (entry.success) "Success" else "Failed",
            tint = if (entry.success)
                MaterialTheme.colorScheme.secondary
            else
                MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
    }
}
