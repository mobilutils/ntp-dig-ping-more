package io.github.mobilutils.ntp_dig_ping_more

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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

// ─────────────────────────────────────────────────────────────────────────────
// Root screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun HttpsCertScreen(
    vm: HttpsCertViewModel = viewModel(
        factory = HttpsCertViewModel.factory(LocalContext.current)
    ),
) {
    val uiState      by vm.uiState.collectAsState()
    val host         by vm.host.collectAsState()
    val port         by vm.port.collectAsState()
    val history      by vm.history.collectAsState()
    val focusManager = LocalFocusManager.current

    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Description ────────────────────────────────────────────────
        item {
            Text(
                text  = "Enter a hostname and port to inspect its TLS certificate",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── Input row ──────────────────────────────────────────────────
        item {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment     = Alignment.Top,
            ) {
                OutlinedTextField(
                    value         = host,
                    onValueChange = vm::onHostChange,
                    label         = { Text("HTTPS Host") },
                    placeholder   = { Text("e.g. google.com") },
                    singleLine    = true,
                    modifier      = Modifier.weight(1f),
                    leadingIcon   = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    trailingIcon  = {
                        if (host.isNotEmpty()) {
                            IconButton(onClick = { vm.onHostChange("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear host")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction    = ImeAction.Next,
                    ),
                    isError = uiState is HttpsCertUiState.Error,
                )
                OutlinedTextField(
                    value         = port,
                    onValueChange = vm::onPortChange,
                    label         = { Text("Port") },
                    placeholder   = { Text("443") },
                    singleLine    = true,
                    modifier      = Modifier.width(90.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction    = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            focusManager.clearFocus()
                            vm.fetchCert()
                        }
                    ),
                )
            }
        }

        // ── Fetch button ───────────────────────────────────────────────
        item {
            Button(
                onClick  = {
                    focusManager.clearFocus()
                    vm.fetchCert()
                },
                enabled  = uiState !is HttpsCertUiState.Loading && host.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape  = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                if (uiState is HttpsCertUiState.Loading) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color       = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Connecting…")
                } else {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        modifier           = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Fetch Certificate", fontWeight = FontWeight.Medium)
                }
            }
        }

        // ── Results ────────────────────────────────────────────────────
        item {
            AnimatedVisibility(
                visible = uiState is HttpsCertUiState.Success ||
                          uiState is HttpsCertUiState.PartialSuccess ||
                          uiState is HttpsCertUiState.Error,
                enter   = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 },
                exit    = fadeOut(tween(200)),
            ) {
                when (val state = uiState) {
                    is HttpsCertUiState.Success        -> CertResultContent(info = state.info, warning = null, onRetry = { vm.fetchCert() })
                    is HttpsCertUiState.PartialSuccess -> CertResultContent(info = state.info, warning = state.warningMessage, onRetry = { vm.fetchCert() })
                    is HttpsCertUiState.Error          -> CertErrorCard(message = state.message, onRetry = { vm.fetchCert() })
                    else                               -> {}
                }
            }
        }

        // ── History ────────────────────────────────────────────────────
        item {
            AnimatedVisibility(
                visible = history.isNotEmpty(),
                enter   = fadeIn(tween(400)),
                exit    = fadeOut(tween(200)),
            ) {
                HttpsCertHistorySection(
                    entries      = history,
                    onEntryClick = { entry ->
                        focusManager.clearFocus()
                        vm.selectHistoryEntry(entry)
                    },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Full certificate content (used for both Success and PartialSuccess)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CertResultContent(
    info:    CertificateInfo,
    warning: String?,
    onRetry: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    // Track copy state per key string (fingerprints, SANs) with 2-second auto-reset
    val copiedKeys = remember { mutableStateMapOf<String, Boolean>() }

    fun copyToClipboard(key: String, value: String) {
        clipboardManager.setText(AnnotatedString(value))
        copiedKeys[key] = true
    }

    // Auto-reset each copied state after 2 seconds
    val currentlyCopied = copiedKeys.filter { it.value }.keys.toList()
    currentlyCopied.forEach { key ->
        LaunchedEffect(key) {
            delay(2_000)
            copiedKeys[key] = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // ── Warning banner (expired / untrusted) ───────────────────────
        if (warning != null) {
            WarningBanner(message = warning)
        }

        // ── Validity status chip ───────────────────────────────────────
        ValidityChip(info = info, trustWarning = warning)

        // ── Subject ───────────────────────────────────────────────────
        CertSection(title = "Subject", icon = Icons.Filled.Badge) {
            DnRows(info.subject)
        }

        // ── Issuer ────────────────────────────────────────────────────
        CertSection(title = "Issuer", icon = Icons.Filled.AccountBalance) {
            DnRows(info.issuer)
        }

        // ── Validity period ───────────────────────────────────────────
        CertSection(title = "Validity Period", icon = Icons.Filled.DateRange) {
            CertRow(label = "Not Before", value = info.notBefore)
            Spacer(Modifier.height(6.dp))
            CertRow(label = "Not After",  value = info.notAfter)
        }

        // ── Public key ────────────────────────────────────────────────
        CertSection(title = "Public Key", icon = Icons.Filled.Key) {
            CertRow(label = "Algorithm", value = info.keyAlgorithm)
            if (info.keySize > 0) {
                Spacer(Modifier.height(6.dp))
                CertRow(label = "Key Size", value = "${info.keySize} bits")
            }
        }

        // ── Certificate metadata ───────────────────────────────────────
        CertSection(title = "Certificate Info", icon = Icons.Filled.Info) {
            CertRow(label = "Version",             value = "X.509 v${info.version}")
            Spacer(Modifier.height(6.dp))
            CertRow(label = "Serial Number",       value = info.serialNumber)
            Spacer(Modifier.height(6.dp))
            CertRow(label = "Signature Algorithm", value = info.signatureAlgorithm)
            Spacer(Modifier.height(6.dp))
            CertRow(label = "Chain Depth",         value = "${info.chainDepth} cert(s)")
        }

        // ── Subject Alternative Names ──────────────────────────────────
        if (info.subjectAltNames.isNotEmpty()) {
            CertSection(title = "Subject Alternative Names (${info.subjectAltNames.size})", icon = Icons.Filled.Public) {
                info.subjectAltNames.forEachIndexed { index, san ->
                    if (index > 0) Spacer(Modifier.height(4.dp))
                    CopyableRow(
                        label    = san.type,
                        value    = san.value,
                        copied   = copiedKeys[san.value] == true,
                        onCopy   = { copyToClipboard(san.value, san.value) },
                    )
                }
            }
        }

        // ── Fingerprints ───────────────────────────────────────────────
        CertSection(title = "Fingerprints", icon = Icons.Filled.Fingerprint) {
            CopyableRow(
                label  = "SHA-256",
                value  = info.sha256Fingerprint,
                copied = copiedKeys["sha256"] == true,
                onCopy = { copyToClipboard("sha256", info.sha256Fingerprint) },
            )
            Spacer(Modifier.height(8.dp))
            CopyableRow(
                label  = "SHA-1",
                value  = info.sha1Fingerprint,
                copied = copiedKeys["sha1"] == true,
                onCopy = { copyToClipboard("sha1", info.sha1Fingerprint) },
            )
        }

        // ── Re-check button ────────────────────────────────────────────
        OutlinedButton(
            onClick  = onRetry,
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(10.dp),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Re-check")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Validity chip
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ValidityChip(info: CertificateInfo, trustWarning: String?) {
    val (bgColor, contentColor, icon, label) = when {
          // Trust issues always override to "Invalid" in red
        trustWarning != null -> Quad(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Filled.Error,
             "Invalid",
          )
        info.validityStatus == CertValidityStatus.VALID -> Quad(
            Color(0xFF1B5E20), Color(0xFFA5D6A7),
            Icons.Filled.CheckCircle,
            if (info.daysUntilExpiry > 365) "Valid" else "Valid · expires in ${info.daysUntilExpiry}d",
          )
        info.validityStatus == CertValidityStatus.EXPIRING_SOON -> Quad(
            Color(0xFFE65100), Color(0xFFFFCC80),
            Icons.Filled.Warning,
              "Expiring Soon · ${info.daysUntilExpiry}d left",
          )
        else -> Quad(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Filled.Error,
              "Expired · ${-info.daysUntilExpiry}d ago",
          )
      }

    Surface(
        shape = RoundedCornerShape(50.dp),
        color = bgColor,
     ) {
        Row(
            modifier           = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = contentColor,
                modifier           = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text       = label,
                style      = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color      = contentColor,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Warning banner
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun WarningBanner(message: String) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(12.dp),
        colors    = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier          = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector        = Icons.Filled.Warning,
                contentDescription = "Warning",
                tint               = Color(0xFFE65100),
                modifier           = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text  = message,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF4E2600),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Section card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CertSection(
    title:   String,
    icon:    ImageVector,
    content: @Composable () -> Unit,
) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Section header
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier          = Modifier
                        .size(32.dp)
                        .background(
                            color  = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            shape  = CircleShape,
                        ),
                    contentAlignment  = Alignment.Center,
                ) {
                    Icon(
                        imageVector        = icon,
                        contentDescription = null,
                        tint               = MaterialTheme.colorScheme.primary,
                        modifier           = Modifier.size(16.dp),
                    )
                }
                Text(
                    text       = title,
                    style      = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
            Spacer(Modifier.height(10.dp))

            content()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Row helpers
// ─────────────────────────────────────────────────────────────────────────────

/** A label + monospace value row. */
@Composable
private fun CertRow(label: String, value: String) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.Top,
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.9f),
        )
        Text(
            text       = value,
            style      = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.Medium,
            modifier   = Modifier.weight(1.6f),
        )
    }
}

/** Distinguished Name rows — skips null fields. */
@Composable
private fun DnRows(dn: DistinguishedName) {
    val fields = listOfNotNull(
        dn.cn?.let { "CN" to it },
        dn.o?.let  { "O"  to it },
        dn.ou?.let { "OU" to it },
        dn.c?.let  { "C"  to it },
    )
    if (fields.isEmpty()) {
        Text(
            text  = "(none)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    } else {
        fields.forEachIndexed { index, (label, value) ->
            if (index > 0) Spacer(Modifier.height(6.dp))
            CertRow(label = label, value = value)
        }
    }
}

/** A row with a value that can be copied to the clipboard. */
@Composable
private fun CopyableRow(
    label:  String,
    value:  String,
    copied: Boolean,
    onCopy: () -> Unit,
) {
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text       = value,
                style      = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Medium,
            )
        }
        IconButton(
            onClick  = onCopy,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector        = if (copied) Icons.Filled.CheckCircle else Icons.Filled.ContentCopy,
                contentDescription = if (copied) "Copied" else "Copy",
                tint               = if (copied) MaterialTheme.colorScheme.secondary
                                     else        MaterialTheme.colorScheme.onSurfaceVariant,
                modifier           = Modifier.size(18.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Error card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CertErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
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
                        text       = "Certificate Lookup Failed",
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
private fun HttpsCertHistorySection(
    entries:      List<HttpsCertHistoryEntry>,
    onEntryClick: (HttpsCertHistoryEntry) -> Unit,
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
                    text       = "Recent Lookups",
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
                HttpsCertHistoryRow(entry = entry, onClick = { onEntryClick(entry) })
            }
        }
    }
}

@Composable
private fun HttpsCertHistoryRow(
    entry:   HttpsCertHistoryEntry,
    onClick: () -> Unit,
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = entry.timestamp,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text       = "\thttps://${entry.host}:${entry.port}",
                style      = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color      = MaterialTheme.colorScheme.primary,
            )
            if (entry.summary.isNotBlank()) {
                Text(
                    text  = "\t${entry.summary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text  = when (entry.status) {
                CertHistoryStatus.VALID         -> "🟢"
                CertHistoryStatus.EXPIRING_SOON -> "🟡"
                CertHistoryStatus.EXPIRED       -> "🔴"
                CertHistoryStatus.UNTRUSTED     -> "⚠️"
                CertHistoryStatus.ERROR         -> "❌"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Internal utility
// ─────────────────────────────────────────────────────────────────────────────

/** Destructuring helper for 4-element tuples used in [ValidityChip]. */
private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
