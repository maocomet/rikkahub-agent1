package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.claudep.ClaudePConfigureUiState
import me.rerere.ai.provider.claudep.ClaudePQuotaBody
import me.rerere.ai.provider.claudep.ClaudePQuotaWindow
import me.rerere.ai.provider.claudep.ClaudePUiStatus

@Composable
fun ClaudePProviderConfigure(
    provider: ProviderSetting.ClaudeP,
    ui: ClaudePConfigureUiState,
    onEdit: (ProviderSetting.ClaudeP) -> Unit,
    onScanPairingQr: () -> Unit,
    onUnpair: () -> Unit,
    onRetryCleanup: () -> Unit,
    quota: ClaudePQuotaBody?,
    quotaLoading: Boolean,
    onRefreshQuota: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Claude P", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Claude Code runs on your paired Gateway. Android never receives the Claude OAuth token.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                Text(ui.status.label())
                provider.claudeCodeVersion?.takeIf(String::isNotBlank)?.let { Text("Claude Code: $it") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.canScanPairingQr) Button(onClick = onScanPairingQr) { Text("Scan pairing QR") }
            if (ui.canUnpair) OutlinedButton(onClick = onUnpair) { Text("Unpair") }
            if (ui.canRetryCleanup) Button(onClick = onRetryCleanup) { Text("Retry cleanup") }
        }
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Enabled", style = MaterialTheme.typography.titleMedium)
                    Text("Available after pairing.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = provider.enabled && ui.canEnableProvider,
                    enabled = ui.canEnableProvider,
                    onCheckedChange = { onEdit(provider.copy(enabled = it)) },
                )
            }
        }
        if (provider.models.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Gateway models", style = MaterialTheme.typography.titleMedium)
                    provider.models.forEach { Text(it.displayName.ifBlank { it.modelId }) }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Subscription usage", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = onRefreshQuota, enabled = !quotaLoading && ui.canEnableProvider) {
                        Text(if (quotaLoading) "Loading…" else "Refresh")
                    }
                }
                when {
                    quotaLoading && quota == null -> Text("Loading…")
                    quota?.available != true -> Text("Usage unavailable")
                    else -> {
                        quota.fiveHour?.let { QuotaRow("5 hour", it) }
                        quota.sevenDay?.let { QuotaRow("7 day", it) }
                        quota.opus?.let { QuotaRow("Opus", it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuotaRow(label: String, window: ClaudePQuotaWindow) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text("${window.utilization}%")
        }
        LinearProgressIndicator(
            progress = { (window.utilization / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Resets: ${window.resetsAt}", style = MaterialTheme.typography.bodySmall)
    }
}

private fun ClaudePUiStatus.label(): String = when (this) {
    ClaudePUiStatus.NOT_PAIRED -> "Not paired"
    ClaudePUiStatus.PAIRING -> "Pairing…"
    ClaudePUiStatus.PAIRED -> "Paired"
    ClaudePUiStatus.CONNECTING -> "Connecting…"
    ClaudePUiStatus.ONLINE -> "Online"
    ClaudePUiStatus.OFFLINE -> "Offline"
    ClaudePUiStatus.PROTOCOL_ERROR -> "Gateway protocol error"
    ClaudePUiStatus.CREDENTIAL_INVALID -> "Device credential invalid"
}
