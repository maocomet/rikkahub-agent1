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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.claudep.ClaudePPairingFailure
import me.rerere.ai.provider.claudep.ClaudePUnpairFailure
import me.rerere.ai.provider.claudep.ClaudePUiStatus
import me.rerere.ai.provider.claudep.allowsDispatch
import me.rerere.ai.provider.claudep.offersPairing
import me.rerere.ai.provider.claudep.offersUnpair

/**
 * Settings surface for the Claude P provider.
 *
 * ### What this screen must never contain
 *
 * No API-key field, no Claude OAuth entry point, and no editable base URL. Claude P holds a
 * revocable *device* identity rather than an API key, its origin is learned from a pairing QR rather
 * than typed (`claudep/01-architecture-and-trust-boundaries.md` §4 forbids pasting an arbitrary
 * endpoint), and Claude credentials live only on the user's own VPS. There is deliberately no
 * control here that could put any of those on the device.
 *
 * ### What it must never claim
 *
 * A device that cannot authenticate is never shown as usable. [status] is derived by
 * `ClaudePUiStatusMapper` from the pairing record, the encrypted credential store and the live
 * connection — never from this screen's own state — and only `PAIRED`/`ONLINE` enable the provider,
 * so an offline or unpaired provider cannot be turned on and left to fail at request time.
 */
@Composable
fun ClaudePProviderConfigure(
    provider: ProviderSetting.ClaudeP,
    onEdit: (ProviderSetting.ClaudeP) -> Unit,
    /** Derived status. Defaults to the honest "not paired" for previews and tests. */
    status: ClaudePUiStatus = ClaudePUiStatus.NOT_PAIRED,
    /** Most recent pairing failure, or `null` when the last attempt succeeded or none was made. */
    pairingFailure: ClaudePPairingFailure? = null,
    /**
     * What the last revocation could not remove.
     *
     * Non-empty means local material may still be on the device. The screen must say so rather than
     * showing a clean "not paired": telling a user their device is clean while a credential or
     * private key survives is the failure this exists to prevent.
     */
    unpairCleanupFailures: List<ClaudePUnpairFailure> = emptyList(),
    /** True while a cleanup is outstanding — a tombstone exists, or the device is REVOKED. */
    cleanupPending: Boolean = false,
    /** True while an unpair or retry is running, so the buttons cannot be double-tapped. */
    cleanupInFlight: Boolean = false,
    /** Opens the QR scanner. The caller owns the launcher; this screen owns only the copy. */
    onScanPairingQr: () -> Unit = {},
    /** Ends the pairing: credential, device key and socket. */
    onUnpair: () -> Unit = {},
    /** Retries an incomplete cleanup. Same code path as unpair, so it is always safe to offer. */
    onRetryCleanup: () -> Unit = {},
) {
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

        StatusCard(status = status, pairingFailure = pairingFailure)

        PairingCard(
            status = status,
            cleanupIncomplete = cleanupPending || unpairCleanupFailures.isNotEmpty(),
            cleanupInFlight = cleanupInFlight,
            onScanPairingQr = onScanPairingQr,
            onUnpair = onUnpair,
            onRetryCleanup = onRetryCleanup,
        )

        if (status.offersUnpair) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = "Gateway", style = MaterialTheme.typography.titleMedium)
                    LabeledValue("Origin", provider.pairedOrigin ?: "Unavailable")
                    LabeledValue("Fingerprint", provider.gatewayFingerprint ?: "Unavailable")
                    LabeledValue("Claude Code", provider.claudeCodeVersion ?: "Unavailable")
                    LabeledValue("Device", provider.device.displayName.ifBlank { "This device" })
                }
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
                        // that fails every request at the connection check is worse than one the
                        // user cannot turn on yet.
                        text = if (status.allowsDispatch) {
                            "Available to assistants."
                        } else {
                            "Available once this device is paired and reachable."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = provider.enabled && status.allowsDispatch,
                    onCheckedChange = { onEdit(provider.copy(enabled = it)) },
                    enabled = status.allowsDispatch,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "Capabilities", style = MaterialTheme.typography.titleMedium)
                // Stated so a missing attachment picker and a missing tool approval read as known
                // limits rather than as bugs.
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
private fun StatusCard(status: ClaudePUiStatus, pairingFailure: ClaudePPairingFailure?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = when (status) {
            ClaudePUiStatus.ONLINE -> CardDefaults.cardColors()
            ClaudePUiStatus.PROTOCOL_ERROR,
            ClaudePUiStatus.CREDENTIAL_INVALID,
            -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)

            else -> CardDefaults.cardColors()
        },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = when (status) {
                    ClaudePUiStatus.NOT_PAIRED -> "Not paired"
                    ClaudePUiStatus.PAIRING -> "Pairing…"
                    ClaudePUiStatus.PAIRED -> "Paired"
                    ClaudePUiStatus.CONNECTING -> "Connecting…"
                    ClaudePUiStatus.ONLINE -> "Online"
                    ClaudePUiStatus.OFFLINE -> "Offline"
                    ClaudePUiStatus.PROTOCOL_ERROR -> "Gateway protocol error"
                    ClaudePUiStatus.CREDENTIAL_INVALID -> "Device credential invalid"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = status.explanation(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Only a bounded enum is ever shown. A gateway's raw error text is never rendered,
            // because it is untrusted remote input that could quote a prompt or a path.
            pairingFailure?.let { failure ->
                Text(
                    text = "Pairing failed: ${failure.name.lowercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PairingCard(
    status: ClaudePUiStatus,
    cleanupIncomplete: Boolean,
    cleanupInFlight: Boolean,
    onScanPairingQr: () -> Unit,
    onUnpair: () -> Unit,
    onRetryCleanup: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "This device", style = MaterialTheme.typography.titleMedium)

            // Stated plainly, and only in safe terms. No alias, path, exception text, credential or
            // gateway detail is ever rendered — the categories are bounded enums rendered as prose.
            if (cleanupIncomplete) {
                Text(
                    text = "The connection has been disabled, but some local pairing material " +
                        "could not be removed. Retry the cleanup to finish unpairing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (status.offersPairing) {
                Text(
                    text = "Generate a pairing code on your Gateway server, then scan it here. " +
                        "The code is single-use and expires in minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onScanPairingQr,
                    // Blocked while a cleanup is outstanding: pairing over unfinished material would
                    // leave the old key undeletable.
                    enabled = status != ClaudePUiStatus.PAIRING && !cleanupInFlight && !cleanupIncomplete,
                ) {
                    Text("Scan pairing QR code")
                }
            }

            if (status.offersUnpair) {
                Text(
                    text = "Unpairing removes this device's credential and key from the phone. " +
                        "It does not sign you out of Claude on the server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onUnpair, enabled = !cleanupInFlight) {
                    Text(if (cleanupInFlight) "Unpairing…" else "Unpair this device")
                }
            }

            if (cleanupIncomplete) {
                Button(onClick = onRetryCleanup, enabled = !cleanupInFlight) {
                    Text(if (cleanupInFlight) "Retrying…" else "Retry cleanup")
                }
            }
        }
    }
}

/** The one-sentence explanation shown under each status. Bounded vocabulary, no remote text. */
private fun ClaudePUiStatus.explanation(): String = when (this) {
    ClaudePUiStatus.NOT_PAIRED ->
        "No device credential is stored, so no request can be sent."

    ClaudePUiStatus.PAIRING ->
        "Exchanging the pairing code with your gateway."

    ClaudePUiStatus.PAIRED ->
        "Paired. The connection opens when a request is made."

    ClaudePUiStatus.CONNECTING ->
        "Opening the secure connection and signing the handshake."

    ClaudePUiStatus.ONLINE ->
        "Connected to your gateway."

    ClaudePUiStatus.OFFLINE ->
        "Paired, but the gateway is not reachable right now."

    ClaudePUiStatus.PROTOCOL_ERROR ->
        "The gateway replied in a way this app cannot safely interpret. Update the app or the " +
            "gateway so both speak the same protocol version."

    ClaudePUiStatus.CREDENTIAL_INVALID ->
        "This device's credential is missing, expired or revoked. Pair again to continue."
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
