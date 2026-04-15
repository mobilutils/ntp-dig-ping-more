package io.github.mobilutils.ntp_dig_ping_more

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
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

// ─────────────────────────────────────────────────────────────────────────────
// Root screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GoogleTimeSyncScreen() {
    val context      = LocalContext.current
    val vm: GoogleTimeSyncViewModel = viewModel(
        factory = GoogleTimeSyncViewModel.factory(context)
    )

    val screenState      by vm.uiState.collectAsState()
    val syncState         = screenState.syncState
    val focusManager      = LocalFocusManager.current
    val clipboardManager  = LocalClipboardManager.current

    var url    by remember { mutableStateOf(GoogleTimeSyncRepository.DEFAULT_URL) }
    var copied by remember { mutableStateOf(false) }

    // Auto-reset the "Copied!" label after 2 s.
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Description ───────────────────────────────────────────────
        Text(
            text  = "Query clients2.google.com for UTC time with offset calculation",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── URL field ─────────────────────────────────────────────────
        OutlinedTextField(
            value       = url,
            onValueChange = { newValue ->
                url = newValue
                if (syncState !is GoogleTimeSyncUiState.Idle) vm.reset()
            },
            label       = { Text("URL") },
            placeholder = { Text(GoogleTimeSyncRepository.DEFAULT_URL) },
            singleLine  = true,
            modifier    = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Filled.Language, contentDescription = null) },
            trailingIcon = {
                if (url.isNotEmpty()) {
                    IconButton(onClick = {
                        url = ""
                        vm.reset()
                    }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear URL")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction    = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(
                onGo = {
                    focusManager.clearFocus()
                    if (url.isBlank()) url = GoogleTimeSyncRepository.DEFAULT_URL
                    vm.syncTime(url)
                }
            ),
        )

        // ── Sync button ───────────────────────────────────────────────
        Button(
            onClick  = {
                focusManager.clearFocus()
                if (url.isBlank()) url = GoogleTimeSyncRepository.DEFAULT_URL
                vm.syncTime(url)
            },
            enabled  = syncState !is GoogleTimeSyncUiState.Loading,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape  = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            if (syncState is GoogleTimeSyncUiState.Loading) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color       = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text("Syncing…")
            } else {
                Icon(
                    Icons.Filled.Sync,
                    contentDescription = null,
                    modifier           = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Sync Now", fontWeight = FontWeight.Medium)
            }
        }

        // ── Result ────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = syncState is GoogleTimeSyncUiState.Success ||
                      syncState is GoogleTimeSyncUiState.Error,
            enter   = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 },
            exit    = fadeOut(tween(200)),
        ) {
            when (syncState) {
                is GoogleTimeSyncUiState.Success -> {
                    TimeSyncResultCard(
                        result = syncState.result,
                        copied = copied,
                        onCopy = {
                            val sign = if (syncState.result.offsetMillis >= 0) "+" else ""
                            clipboardManager.setText(
                                AnnotatedString("$sign${syncState.result.offsetMillis} ms")
                            )
                            copied = true
                        },
                    )
                }
                is GoogleTimeSyncUiState.Error -> {
                    ErrorCard(
                        message = syncState.message,
                        onRetry = {
                            if (url.isBlank()) url = GoogleTimeSyncRepository.DEFAULT_URL
                            vm.syncTime(url)
                        },
                    )
                }
                else -> { /* Idle / Loading — nothing to show */ }
            }
        }

        // ── History ───────────────────────────────────────────────────
        AnimatedVisibility(
            visible = screenState.history.isNotEmpty(),
            enter   = fadeIn(tween(400)),
            exit    = fadeOut(tween(200)),
        ) {
            HistorySection(
                entries      = screenState.history,
                onEntryClick = { entry ->
                    focusManager.clearFocus()
                    vm.selectHistoryEntry(entry) { url = it }
                },
            )
        }

        // ── Footer helper text ────────────────────────────────────────
        Text(
            text  = "Offset = corrected server time − your device time",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Success card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TimeSyncResultCard(
    result: TimeSyncResult,
    copied: Boolean,
    onCopy: () -> Unit,
) {
    val offsetAbs = abs(result.offsetMillis)
    val offsetColor = when {
        offsetAbs < 100L -> MaterialTheme.colorScheme.secondary
        offsetAbs < 500L -> Color(0xFFE65100)           // deep-orange
        else             -> MaterialTheme.colorScheme.error
    }
    val offsetSign  = if (result.offsetMillis >= 0) "+" else ""
    val offsetLabel = when {
        result.offsetMillis > 0L -> "Local clock is behind server"
        result.offsetMillis < 0L -> "Local clock is ahead of server"
        else                     -> "Clocks are in sync"
    }

    val utcFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val serverTimeStr    = utcFmt.format(Date(result.serverTimeMillis))             + " UTC"
    val correctedTimeStr = utcFmt.format(Date(result.correctedServerTimeMillis))    + " UTC"

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector        = Icons.Filled.CheckCircle,
                    contentDescription = "Success",
                    tint               = MaterialTheme.colorScheme.secondary,
                    modifier           = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text       = "Sync Successful",
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text  = "RTT ${result.rttMillis} ms  ·  offset $offsetSign${result.offsetMillis} ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f))
            Spacer(Modifier.height(16.dp))

            SyncMetricRow(icon = Icons.Filled.Schedule,  label = "Server Time",     value = serverTimeStr)
            Spacer(Modifier.height(10.dp))
            SyncMetricRow(icon = Icons.Filled.SwapHoriz, label = "Round-Trip Time", value = "${result.rttMillis} ms")
            Spacer(Modifier.height(10.dp))

            // Clock offset (colour-coded)
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.Top,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector        = Icons.Filled.Timer,
                        contentDescription = null,
                        tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier           = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text  = "Clock Offset",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1.5f)) {
                    Text(
                        text       = "$offsetSign${result.offsetMillis} ms",
                        style      = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.Bold,
                        color      = offsetColor,
                    )
                    Text(
                        text  = offsetLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = offsetColor.copy(alpha = 0.8f),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            SyncMetricRow(icon = Icons.Filled.Adjust, label = "Corrected Time", value = correctedTimeStr)
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f))
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick  = onCopy,
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(10.dp),
            ) {
                Icon(
                    imageVector        = if (copied) Icons.Filled.CheckCircle else Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier           = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (copied) "Copied!" else "Copy Offset ($offsetSign${result.offsetMillis} ms)")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Error card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector        = Icons.Filled.Error,
                    contentDescription = "Error",
                    tint               = MaterialTheme.colorScheme.error,
                    modifier           = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text       = "Sync Failed",
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color      = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text  = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick  = onRetry,
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(10.dp),
                colors   = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Retry")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// History section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HistorySection(
    entries: List<GoogleTimeSyncHistoryEntry>,
    onEntryClick: (GoogleTimeSyncHistoryEntry) -> Unit,
) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector        = Icons.Filled.History,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier           = Modifier.size(18.dp),
                )
                Text(
                    text       = "Recent Syncs",
                    style      = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun HistoryRow(
    entry: GoogleTimeSyncHistoryEntry,
    onClick: () -> Unit,
) {
    val offsetSign = if (entry.offsetMs >= 0) "+" else ""

    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text      = entry.timestamp,
                style     = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text       = "\t${entry.url}",
                style      = MaterialTheme.typography.bodySmall,
                fontFamily  = FontFamily.Monospace,
                fontWeight  = FontWeight.Medium,
                color       = MaterialTheme.colorScheme.primary,
            )
            if (entry.success) {
                Text(
                    text      = "\tRTT ${entry.rttMs} ms  ·  offset $offsetSign${entry.offsetMs} ms",
                    style     = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector        = if (entry.success) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = if (entry.success) "Success" else "Failed",
            tint               = if (entry.success) MaterialTheme.colorScheme.secondary
                                 else               MaterialTheme.colorScheme.error,
            modifier           = Modifier.size(20.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reusable metric row (icon + label + monospace value)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SyncMetricRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.Top,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier           = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text  = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text       = value,
            style      = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.Medium,
            modifier   = Modifier.weight(1.5f),
        )
    }
}
