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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

// ─────────────────────────────────────────────────────────────────────────────
// Scrollbar helper (same as PingScreen)
// ─────────────────────────────────────────────────────────────────────────────

private fun Modifier.verticalScrollbar(
    scrollState: androidx.compose.foundation.ScrollState,
    width: Dp = 4.dp,
    color: Color = Color.Gray.copy(alpha = 0.5f),
): Modifier = this.drawWithContent {
    drawContent()
    val contentHeight = scrollState.maxValue.toFloat() + size.height
    if (contentHeight <= size.height) return@drawWithContent

    val thumbHeightPx = (size.height / contentHeight) * size.height
    val thumbTopPx    = (scrollState.value.toFloat() / contentHeight) * size.height
    val barWidth      = width.toPx()

    drawRoundRect(
        color        = color,
        topLeft      = Offset(size.width - barWidth, thumbTopPx),
        size         = Size(barWidth, thumbHeightPx),
        cornerRadius = CornerRadius(barWidth / 2),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Traceroute screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun TracerouteScreen() {
    val context = LocalContext.current
    val vm: TracerouteViewModel = viewModel(factory = TracerouteViewModel.factory(context))
    val uiState by vm.uiState.collectAsState()
    val focusManager = LocalFocusManager.current

    // Scroll state for the output area – auto-scroll to the bottom on new lines
    val outputScrollState = rememberScrollState()
    LaunchedEffect(uiState.outputLines.size) {
        outputScrollState.animateScrollTo(outputScrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Hostname field ────────────────────────────────────────────
        OutlinedTextField(
            value = uiState.host,
            onValueChange = vm::onHostChange,
            label = { Text("Hostname / IP") },
            placeholder = { Text("e.g. google.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(
                onGo = {
                    focusManager.clearFocus()
                    if (uiState.isRunning) vm.stopTraceroute() else vm.startTraceroute()
                },
            ),
            enabled = !uiState.isRunning,
        )

        // ── Traceroute / Stop button ──────────────────────────────────
        Button(
            onClick = {
                focusManager.clearFocus()
                if (uiState.isRunning) vm.stopTraceroute() else vm.startTraceroute()
            },
            enabled = uiState.isRunning || uiState.host.isNotBlank(),
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
                    imageVector = Icons.Filled.Route,
                    contentDescription = "Traceroute",
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Traceroute", fontWeight = FontWeight.Medium)
            }
        }

        // ── Output terminal card – fills remaining space ───────────────
        AnimatedVisibility(
            visible = uiState.outputLines.isNotEmpty(),
            modifier = Modifier.weight(1f),
            enter = fadeIn(tween(300)),
            exit  = fadeOut(tween(200)),
        ) {
            Card(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(12.dp),
                        ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = 10.dp)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .verticalScroll(outputScrollState)
                            .verticalScrollbar(outputScrollState),
                    ) {
                        uiState.outputLines.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // ── Traceroute History ────────────────────────────────────────
        AnimatedVisibility(
            visible = uiState.history.isNotEmpty(),
            enter = fadeIn(tween(400)),
            exit  = fadeOut(tween(200)),
        ) {
            TracerouteHistorySection(
                entries = uiState.history,
                onEntryClick = { entry ->
                    focusManager.clearFocus()
                    vm.selectHistoryEntry(entry)
                },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// History section composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TracerouteHistorySection(
    entries: List<TracerouteHistoryEntry>,
    onEntryClick: (TracerouteHistoryEntry) -> Unit,
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
                    text = "Recent Traceroutes",
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
                TracerouteHistoryRow(entry = entry, onClick = { onEntryClick(entry) })
            }
        }
    }
}

@Composable
private fun TracerouteHistoryRow(entry: TracerouteHistoryEntry, onClick: () -> Unit) {
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
                text = "\t${entry.host}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = when (entry.status) {
                TracerouteStatus.ALL_SUCCESS -> "✅"
                TracerouteStatus.PARTIAL     -> "\uD83E\uDD37\u200D\u2642\uFE0F"
                TracerouteStatus.ALL_FAILED  -> "❌"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
