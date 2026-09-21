package me.rerere.ai.provider.claudep

import me.rerere.ai.provider.ProviderSetting

/**
 * Strips everything from an imported provider that must never be trusted from the wire.
 *
 * A provider string arrives from a QR code or a pasted blob and is decoded polymorphically into any
 * `ProviderSetting` subtype. Without this step a crafted payload could produce an *enabled* Claude P
 * claiming to be paired with an origin and a fingerprint the user never scanned — the gap recorded in
 * the CP1-A report (`claudep/reports/CP1A-local-provider-skeleton-report.md` §9 #8).
 *
 * Claude P is the one type where "imported settings" and "paired device" are genuinely different
 * things: its authority is a Keystore-held key and an encrypted credential, and a QR code can carry
 * neither. So an imported Claude P is always reset to **unpaired and disabled**, and the user must
 * scan a real pairing code. That follows `claudep/00-scope-and-product-contract.md` §4 — the phone
 * never accepts a gateway endpoint it did not pair with.
 *
 * Other provider types are returned unchanged. They carry their own credentials in their own fields,
 * and silently rewriting them here would break ordinary provider sharing.
 *
 * ### Why this lives here
 *
 * The rule is deliberately **not** wired to the import UI. The UI path decodes with
 * `android.util.Base64` and lives in a Compose file, neither of which a plain JVM unit test can
 * execute — so a rule kept next to it could only ever be verified by reading it. Keeping the rule
 * free of Android and Compose makes it ordinary, testable logic; the Base64 decode stays where it
 * belongs, in the app layer.
 */
fun ProviderSetting.sanitizedAfterImport(): ProviderSetting = when (this) {
    is ProviderSetting.ClaudeP -> copy(
        // Never enabled on import: an enabled provider with no credential fails at request time,
        // which is indistinguishable from a broken app.
        enabled = false,
        pairingState = ClaudePPairingState.NOT_PAIRED,
        // The endpoint is *derived from pairing*, never from imported text.
        pairedOrigin = null,
        gatewayFingerprint = null,
        gatewayInstallationId = null,
        device = ClaudePDeviceDescriptor(),
        // Catalog caches and the reported CLI version come from a gateway this device has never
        // talked to, so they are stale by construction.
        cachedModels = emptyList(),
        catalogCachedAt = null,
        claudeCodeVersion = null,
    )

    else -> this
}
