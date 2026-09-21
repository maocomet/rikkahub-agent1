package me.rerere.rikkahub.ui.components.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.claudep.ClaudePDeviceDescriptor
import me.rerere.ai.provider.claudep.ClaudePPairingState
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Share03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.io.encoding.Base64

@Composable
fun ShareSheet(
    state: ShareSheetState,
) {
    val context = LocalContext.current
    if (state.isShow) {
        ModalBottomSheet(
            onDismissRequest = {
                state.dismiss()
            },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.share_sheet_title), style = MaterialTheme.typography.titleLarge)

                    IconButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND)
                            intent.type = "text/plain"
                            intent.putExtra(
                                Intent.EXTRA_TEXT,
                                state.currentProvider?.encodeForShare() ?: ""
                            )
                            try {
                                context.startActivity(Intent.createChooser(intent, null))
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    ) {
                        Icon(HugeIcons.Share03, null)
                    }
                }

                QRCode(
                    value = state.currentProvider?.encodeForShare() ?: "",
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )
            }
        }
    }
}

fun ProviderSetting.encodeForShare(): String {
    return buildString {
        append("ai-provider:")
        append("v1:")

        val value = JsonInstant.encodeToString(this@encodeForShare.copyProvider(models = emptyList()))
        append(Base64.encode(value.encodeToByteArray()))
    }
}

fun decodeProviderSetting(value: String): ProviderSetting {
    require(value.startsWith("ai-provider:v1:")) { "Invalid provider setting string" }

    // 去掉前缀
    val base64Str = value.removePrefix("ai-provider:v1:")

    // Base64解码
    val jsonBytes = Base64.decode(base64Str)
    val jsonStr = jsonBytes.decodeToString()

    return JsonInstant.decodeFromString<ProviderSetting>(jsonStr).sanitizedAfterImport()
}

/**
 * Strips everything from an imported provider that must never be trusted from the wire.
 *
 * A provider string is untrusted input: it arrives from a QR code or a pasted blob, and it is
 * decoded polymorphically into any `ProviderSetting` subtype. Without this step a crafted payload
 * could produce an *enabled* Claude P provider claiming to be paired with an origin and fingerprint
 * the user never chose — the CP1-A report recorded exactly this gap
 * (`claudep/reports/CP1A-local-provider-skeleton-report.md` §9 #8).
 *
 * Claude P is the one type where "imported settings" and "paired device" are genuinely different
 * things: its authority is a Keystore key and an encrypted credential that a QR code cannot carry.
 * So an imported Claude P is always reset to *unpaired and disabled*, and the user has to scan a
 * real pairing code. This mirrors `claudep/00-scope-and-product-contract.md` §4 — the phone must
 * never accept a gateway endpoint it did not pair with.
 *
 * Other provider types are returned unchanged: they carry their own credentials in their own fields,
 * and silently rewriting them here would break ordinary provider sharing.
 */
private fun ProviderSetting.sanitizedAfterImport(): ProviderSetting = when (this) {
    is ProviderSetting.ClaudeP -> copy(
        enabled = false,
        pairingState = ClaudePPairingState.NOT_PAIRED,
        pairedOrigin = null,
        gatewayFingerprint = null,
        gatewayInstallationId = null,
        device = ClaudePDeviceDescriptor(),
        cachedModels = emptyList(),
        catalogCachedAt = null,
        claudeCodeVersion = null,
    )

    else -> this
}

class ShareSheetState {
    private var show by mutableStateOf(false)
    val isShow get() = show

    private var provider by mutableStateOf<ProviderSetting?>(null)
    val currentProvider get() = provider

    fun show(provider: ProviderSetting) {
        this.show = true
        this.provider = provider
    }

    fun dismiss() {
        this.show = false
    }
}

@Composable
fun rememberShareSheetState(): ShareSheetState {
    return ShareSheetState()
}
