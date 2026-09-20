package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.claudep.ClaudePPairingState

/**
 * Settings surface for the Claude P provider.
 *
 * This is deliberately a skeleton. It reports state and explains what the provider cannot do yet;
 * it does not offer a pairing button, a QR scanner, an OAuth flow or a connection test, because
 * none of those exist in this milestone. A fake "Connected" badge is the specific failure this
 * screen is written to avoid — the user must never be told a VPS is reachable when no pairing
 * mechanism has run.
 *
 * There is intentionally no API key field and no editable base URL. Claude P does not use an API
 * key at all (it holds a revocable device identity), and its origin is learned from a pairing
 * handshake rather than typed, so `claudep/01-architecture-and-trust-boundaries.md` §4 forbids
 * letting a user paste an arbitrary endpoint here.
 */
@Composable
fun ClaudePProviderConfigure(
    provider: ProviderSetting.ClaudeP,
    onEdit: (ProviderSetting.ClaudeP) -> Unit,
) {
    val paired = provider.pairingState == ClaudePPairingState.PAIRED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Claude P",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Runs Claude Code on a Gateway server you control. Your Claude subscription " +
                "stays on that server — this app never receives a Claude token.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = when (provider.pairingState) {
                        ClaudePPairingState.PAIRED -> "Paired"
                        ClaudePPairingState.REVOKED -> "Access revoked"
                        ClaudePPairingState.NOT_PAIRED -> "Not yet paired"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "This build contains the local provider skeleton only. Device pairing, " +
                        "the gateway connection and the model catalog arrive in the next milestone, " +
                        "so no request can be sent yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "Gateway", style = MaterialTheme.typography.titleMedium)
                LabeledValue("Origin", provider.pairedOrigin ?: "Not paired")
                LabeledValue("Fingerprint", provider.gatewayFingerprint ?: "Not paired")
                LabeledValue("Claude Code", provider.claudeCodeVersion ?: "Unavailable")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Enabled", style = MaterialTheme.typography.titleMedium)
                    Text(
                        // Enabling is blocked rather than merely discouraged: an enabled provider
                        // that fails every request at the pairing check is worse than one the user
                        // cannot turn on yet.
                        text = "Available after pairing is implemented.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = provider.enabled,
                    onCheckedChange = { onEdit(provider.copy(enabled = it)) },
                    enabled = paired,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "Capabilities", style = MaterialTheme.typography.titleMedium)
                // Stated so a missing attachment picker reads as a known limit rather than a bug.
                LabeledValue("Supported", "Text, reasoning summary")
                LabeledValue(
                    "Not supported yet",
                    "Tools, images, documents, audio, video, background tasks",
                )
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
