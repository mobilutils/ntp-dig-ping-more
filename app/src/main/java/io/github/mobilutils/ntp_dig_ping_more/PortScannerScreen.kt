package io.github.mobilutils.ntp_dig_ping_more

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun PortScannerScreen() {
    val context = LocalContext.current
    val vm: PortScannerViewModel = viewModel(factory = PortScannerViewModel.factory(context))
    val uiState by vm.uiState.collectAsState()
    val focusManager = LocalFocusManager.current

    val outputScrollState = rememberScrollState()

    // Auto-scroll to bottom only when new ports are discovered (not on every progress update)
    LaunchedEffect(uiState.discoveredPorts.size, uiState.isRunning) {
        if (uiState.discoveredPorts.isNotEmpty() && uiState.isRunning) {
            outputScrollState.animateScrollTo(outputScrollState.maxValue)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = uiState.host,
            onValueChange = vm::onHostChange,
            label = { Text("Hostname / IP") },
            placeholder = { Text("e.g. google.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
            enabled = !uiState.isRunning,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { if (!uiState.isRunning) vm.onProtocolChange(PortScannerProtocol.TCP) }) {
                RadioButton(
                    selected = uiState.protocol == PortScannerProtocol.TCP,
                    onClick = { vm.onProtocolChange(PortScannerProtocol.TCP) },
                    enabled = !uiState.isRunning
                )
                Text("TCP")
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { if (!uiState.isRunning) vm.onProtocolChange(PortScannerProtocol.UDP) }) {
                RadioButton(
                    selected = uiState.protocol == PortScannerProtocol.UDP,
                    onClick = { vm.onProtocolChange(PortScannerProtocol.UDP) },
                    enabled = !uiState.isRunning
                )
                Text("UDP")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = uiState.startPort,
                onValueChange = vm::onStartPortChange,
                label = { Text("Start Port") },
                placeholder = { Text("1") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                enabled = !uiState.isRunning,
            )
            OutlinedTextField(
                value = uiState.endPort,
                onValueChange = vm::onEndPortChange,
                label = { Text("End Port") },
                placeholder = { Text("1024") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(
                    onGo = {
                        focusManager.clearFocus()
                        if (uiState.isRunning) vm.stopScan() else vm.startScan()
                    },
                ),
                enabled = !uiState.isRunning,
            )
        }

        Button(
            onClick = {
                focusManager.clearFocus()
                if (uiState.isRunning) vm.stopScan() else vm.startScan()
            },
            enabled = uiState.isRunning || (uiState.host.isNotBlank() && uiState.startPort.isNotBlank() && uiState.endPort.isNotBlank()),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = if (uiState.isRunning)
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            else
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            if (uiState.isRunning) {
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
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Scan",
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Scan Ports", fontWeight = FontWeight.Medium)
            }
        }

        if (uiState.isRunning || uiState.discoveredPorts.isNotEmpty()) {
            if (uiState.isRunning) {
                LinearProgressIndicator(
                    progress = { uiState.progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                )
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Text(
                        text = "Open Ports Found: ${uiState.discoveredPorts.size}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                                shape = RoundedCornerShape(8.dp),
                            )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                        ) {
                            if (uiState.discoveredPorts.isEmpty() && !uiState.isRunning) {
                                Text(
                                    text = "No open ports found.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(4.dp)
                                )
                            }
                            uiState.discoveredPorts.forEach { port ->
                                Text(
                                    text = "Port $port/${uiState.protocol} open",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 14.sp,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = uiState.history.isNotEmpty(),
            enter = fadeIn(tween(400)),
            exit = fadeOut(tween(200)),
        ) {
            PortScannerHistorySection(
                entries = uiState.history,
                onEntryClick = { entry ->
                    focusManager.clearFocus()
                    vm.selectHistoryEntry(entry)
                },
            )
        }
    }
}

@Composable
private fun PortScannerHistorySection(
    entries: List<PortScannerHistoryEntry>,
    onEntryClick: (PortScannerHistoryEntry) -> Unit,
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
                    text = "Recent Scans",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    entries.forEachIndexed { index, entry ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f))
                        }
                        PortScannerHistoryRow(entry = entry, onClick = { onEntryClick(entry) })
                    }
                }
            }
        }
    }
}

@Composable
private fun PortScannerHistoryRow(entry: PortScannerHistoryEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${entry.timestamp}   [${entry.protocol}]",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${entry.host} : ${entry.startPort}-${entry.endPort}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
