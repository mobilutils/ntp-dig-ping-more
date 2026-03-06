package io.github.mobilutils.ntp_dig_ping_more

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.WifiOff
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

// ─────────────────────────────────────────────────────────────────────────────
// DIG screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun DigScreen(
    viewModel: DigViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Usage hint ────────────────────────────────────────────────────────
        Text(
            text = "dig @DNS_SERVER  FQDN_TO_QUERY",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── DNS Server field ──────────────────────────────────────────────────
        OutlinedTextField(
            value = uiState.dnsServer,
            onValueChange = viewModel::onDnsServerChange,
            label = { Text("DNS Server (IP or FQDN)") },
            placeholder = { Text("e.g. 8.8.8.8  or  resolver.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Dns,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
        )

        // ── FQDN field ────────────────────────────────────────────────────────
        OutlinedTextField(
            value = uiState.fqdn,
            onValueChange = viewModel::onFqdnChange,
            label = { Text("FQDN to query") },
            placeholder = { Text("e.g. pool.ntp.org") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(
                onGo = {
                    focusManager.clearFocus()
                    viewModel.runDigQuery()
                },
            ),
        )

        // ── Run DIG button ────────────────────────────────────────────────────
        Button(
            onClick = {
                focusManager.clearFocus()
                viewModel.runDigQuery()
            },
            enabled = !uiState.isLoading && uiState.fqdn.isNotBlank(),
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
                Text("Resolving…")
            } else {
                Text("Run DIG", fontWeight = FontWeight.Medium)
            }
        }

        // ── Result card ───────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = uiState.result != null,
            enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 },
            exit  = fadeOut(tween(200)),
        ) {
            uiState.result?.let { DigResultCard(it) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Result card composable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DigResultCard(result: DigResult) {
    val isSuccess = result is DigResult.Success
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

            // ── Status header ─────────────────────────────────────────────────
            DigStatusHeader(result)

            when (result) {
                is DigResult.Success -> {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f)
                    )
                    Spacer(Modifier.height(14.dp))

                    // Resolver used
                    Text(
                        text = ";; SERVER: ${result.dnsServer}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))

                    // QUESTION SECTION
                    DnsSection(
                        header = ";; QUESTION SECTION:",
                        lines = listOf(result.questionSection),
                    )

                    Spacer(Modifier.height(12.dp))

                    // ANSWER SECTION
                    DnsSection(
                        header = ";; ANSWER SECTION:",
                        lines = result.records,
                    )
                }

                is DigResult.NxDomain -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "\"${result.fqdn}\" could not be resolved (NXDOMAIN). " +
                               "Check spelling or try a different resolver.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }

                is DigResult.DnsServerError -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = result.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }

                is DigResult.NoNetwork -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Please check your internet connection and try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }

                is DigResult.Error -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = result.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DNS section block  (header + monospace records on dark background)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DnsSection(header: String, lines: List<String>) {
    Column {
        Text(
            text = header,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Status header row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DigStatusHeader(result: DigResult) {
    val (icon, label) = when (result) {
        is DigResult.Success        -> Icons.Filled.CheckCircle to "Resolved"
        is DigResult.NxDomain       -> Icons.Filled.Error       to "Not Found (NXDOMAIN)"
        is DigResult.DnsServerError -> Icons.Filled.Error       to "Resolver Error"
        is DigResult.NoNetwork      -> Icons.Filled.WifiOff     to "No Network"
        is DigResult.Error          -> Icons.Filled.Error       to "Error"
    }
    val tint = if (result is DigResult.Success)
        MaterialTheme.colorScheme.secondary
    else
        MaterialTheme.colorScheme.error

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
