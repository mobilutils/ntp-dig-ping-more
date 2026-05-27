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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Scrollbar helper
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Draws a thin vertical scrollbar on the right edge of the composable,
 * derived from [scrollState].
 */
private fun Modifier.verticalScrollbar(
    scrollState: androidx.compose.foundation.ScrollState,
    width: Dp = 4.dp,
    color: Color = Color.Gray.copy(alpha = 0.5f),
): Modifier = this.drawWithContent {
    drawContent()
    val contentHeight = scrollState.maxValue.toFloat() + size.height
    if (contentHeight <= size.height) return@drawWithContent   // nothing to scroll

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
// Ping screen
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// Stats bar
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A compact card showing real-time packet-loss and RTT stats during (and after) a ping run.
 *
 * Visible whenever there is at least one processed output line.
 * Loss percentage is colour-coded:
 *   - 0 %          → primary (healthy green-ish)
 *   - 1 – 99 %     → tertiary (amber warning)
 *   - 100 %        → error   (red)
 */
@Composable
private fun PingStatsBar(stats: PingStats, modifier: Modifier = Modifier) {
    val lossColor = when {
        stats.sent == 0          -> MaterialTheme.colorScheme.onSurfaceVariant
        stats.lossPercent == 0f  -> MaterialTheme.colorScheme.primary
        stats.lossPercent >= 100f -> MaterialTheme.colorScheme.error
        else                     -> MaterialTheme.colorScheme.tertiary
    }

    fun Float?.fmtMs(): String = if (this == null) "—" else
        String.format(Locale.US, "%.1f ms", this)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left half – packet counts + loss
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.ping_stats_label_packets),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Sent: ${stats.sent}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Text(
                        text = "Lost: ${stats.sent - stats.received}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (stats.sent == 0) "Loss: —"
                           else String.format(Locale.US, "Loss: %.1f %%", stats.lossPercent),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    fontFamily = FontFamily.Monospace,
                    color = lossColor,
                )
            }

            // Thin vertical divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(52.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)),
            )

            // Right half – RTT
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.ping_stats_label_latency),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Text(
                    text = "Min  ${stats.minMs.fmtMs()}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Avg  ${stats.avgMs.fmtMs()}",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Max  ${stats.maxMs.fmtMs()}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Ping screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PingScreen() {
    val context = LocalContext.current
    val vm: PingViewModel = viewModel(factory = PingViewModel.factory(context))
    val uiState by vm.uiState.collectAsState()
    val focusManager = LocalFocusManager.current

    // Separate scroll state just for the output area
    val outputScrollState = rememberScrollState()
    LaunchedEffect(uiState.outputLines.size) {
        outputScrollState.animateScrollTo(outputScrollState.maxValue)
    }

    // ── Outer column: no scroll — input+button pinned, output gets remaining space
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Hostname field (always visible) ───────────────────────────
        OutlinedTextField(
            value = uiState.host,
            onValueChange = vm::onHostChange,
            label = { Text(stringResource(R.string.common_label_hostname_ip)) },
            placeholder = { Text(stringResource(R.string.common_placeholder_eg_google)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(
                onGo = {
                    focusManager.clearFocus()
                    if (uiState.isRunning) vm.stopPing() else vm.startPing()
                },
            ),
            enabled = !uiState.isRunning,
        )

        // ── Ping / Stop button (always visible) ───────────────────────
        Button(
            onClick = {
                focusManager.clearFocus()
                if (uiState.isRunning) vm.stopPing() else vm.startPing()
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
                    contentDescription = stringResource(R.string.common_cd_stop),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.common_btn_stop), fontWeight = FontWeight.Medium)
            } else {
                Icon(
                    imageVector = Icons.Filled.Terminal,
                    contentDescription = stringResource(R.string.ping_cd_ping),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ping_btn_start), fontWeight = FontWeight.Medium)
            }
        }

        // ── Stats bar (between button and output) ─────────────────────
        AnimatedVisibility(
            visible = uiState.outputLines.isNotEmpty() || uiState.isRunning,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(200)),
        ) {
            PingStatsBar(stats = uiState.stats)
        }

        // ── Output terminal card (weight(1f) = fills all remaining space)
        AnimatedVisibility(
            visible = uiState.outputLines.isNotEmpty(),
            modifier = Modifier.weight(1f),
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(200)),
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
                    // Scrollable output content
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = 10.dp)          // room for scrollbar
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
                        // Render the localised timeout marker (if a timeout occurred)
                        uiState.timeoutMessage?.let { uiText ->
                            Text(
                                text = uiText.resolve(),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                ),
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }
        }

        // ── Ping History (below the output, always at the bottom) ─────
        AnimatedVisibility(
            visible = uiState.history.isNotEmpty(),
            enter = fadeIn(tween(400)),
            exit = fadeOut(tween(200)),
        ) {
            PingHistorySection(
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
private fun PingHistorySection(
    entries: List<PingHistoryEntry>,
    onEntryClick: (PingHistoryEntry) -> Unit,
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
                    text = stringResource(R.string.ping_history_title),
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
                PingHistoryRow(entry = entry, onClick = { onEntryClick(entry) })
            }
        }
    }
}

@Composable
private fun PingHistoryRow(entry: PingHistoryEntry, onClick: () -> Unit) {
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
                PingStatus.ALL_SUCCESS -> "✅"
                PingStatus.PARTIAL     -> "\uD83E\uDD37\u200D\u2642\uFE0F"
                PingStatus.ALL_FAILED  -> "❌"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
