package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.SecondUserToolAllowlist
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

/**
 * Owner-facing switch board for the LOCAL second-user tool surface.
 *
 * Every row is a functional FAMILY (a LocalToolOption in section A, an OwnerToolFamily in
 * section B), not the ~257 concrete tools. Turned-off families are filtered BEFORE
 * LocalTools.getTools() / createOwnerManagementTools() so their Tool definitions are never
 * built and never reach provider `params.tools`. Ordinary assistants keep their own per-assistant
 * localTools list; this page only governs the selected local second-user surface.
 *
 * Three-state semantics (see [SecondUserToolAllowlist]):
 * - null (follow default) = all currently-implemented families, future additions auto-enable
 * - []  = all disabled (explicit)
 * - non-empty = explicit allowlist
 */
@Composable
fun SecondUserToolsPage(
    settingsStore: SettingsStore = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()

    fun setLocalTokens(tokens: Set<String>?) {
        scope.launch {
            settingsStore.update { current -> current.copy(secondUserEnabledLocalToolTokens = tokens) }
        }
    }

    fun setOwnerNames(names: Set<String>?) {
        scope.launch {
            settingsStore.update { current -> current.copy(secondUserEnabledOwnerFamilyNames = names) }
        }
    }

    fun toggleLocal(token: String, enabled: Boolean) {
        scope.launch {
            settingsStore.update { current ->
                val base = current.secondUserEnabledLocalToolTokens
                    ?: SecondUserToolAllowlist.allLocalOptionTokens()
                val next = if (enabled) base + token else base - token
                current.copy(secondUserEnabledLocalToolTokens = next)
            }
        }
    }

    fun toggleOwner(name: String, enabled: Boolean) {
        scope.launch {
            settingsStore.update { current ->
                val base = current.secondUserEnabledOwnerFamilyNames
                    ?: SecondUserToolAllowlist.allOwnerFamilyNames()
                val next = if (enabled) base + name else base - name
                current.copy(secondUserEnabledOwnerFamilyNames = next)
            }
        }
    }

    val storedLocal = settings.secondUserEnabledLocalToolTokens
    val storedOwner = settings.secondUserEnabledOwnerFamilyNames
    val localFollow = storedLocal == null
    val ownerFollow = storedOwner == null

    val localEntries = SecondUserToolsCatalog.localEntries
    val ownerEntries = SecondUserToolsCatalog.ownerEntries

    fun localEnabledCount(): Int = localEntries.count { e ->
        storedLocal == null || e.token in storedLocal
    }

    fun ownerEnabledCount(): Int = ownerEntries.count { e ->
        storedOwner == null || e.name in storedOwner
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.second_user_tools_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.second_user_tools_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )

            // Section A: device & execution (LocalToolOption)
            SectionHeader(
                title = stringResource(R.string.second_user_tools_section_local),
                summary = stringResource(
                    R.string.second_user_tools_enabled_of,
                    localEnabledCount(),
                    localEntries.size,
                ) + " · " + stringResource(
                    if (localFollow) R.string.second_user_tools_mode_follow else R.string.second_user_tools_mode_custom,
                ),
                onEnableAll = { setLocalTokens(null) },
                onDisableAll = { setLocalTokens(emptySet()) },
                onReset = { setLocalTokens(null) },
                enableAllLabel = stringResource(R.string.second_user_tools_all_on),
                disableAllLabel = stringResource(R.string.second_user_tools_all_off),
                resetLabel = stringResource(R.string.second_user_tools_restore_default),
            )
            CardGroup {
                localEntries.forEach { entry ->
                    item(
                        headlineContent = {
                            Text(SecondUserToolsCatalog.localTitle(context, entry))
                        },
                        supportingContent = {
                            Text(
                                if (entry.token == "browser") {
                                    stringResource(R.string.second_user_tools_browser_sub)
                                } else {
                                    SecondUserToolsCatalog.localDescription(context, entry)
                                },
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = storedLocal == null || entry.token in storedLocal,
                                onCheckedChange = { toggleLocal(entry.token, it) },
                            )
                        },
                    )
                }
            }

            // Section B: RikkaHub management (OwnerToolFamily)
            SectionHeader(
                title = stringResource(R.string.second_user_tools_section_owner),
                summary = stringResource(
                    R.string.second_user_tools_enabled_of,
                    ownerEnabledCount(),
                    ownerEntries.size,
                ) + " · " + stringResource(
                    if (ownerFollow) R.string.second_user_tools_mode_follow else R.string.second_user_tools_mode_custom,
                ),
                onEnableAll = { setOwnerNames(null) },
                onDisableAll = { setOwnerNames(emptySet()) },
                onReset = { setOwnerNames(null) },
                enableAllLabel = stringResource(R.string.second_user_tools_all_on),
                disableAllLabel = stringResource(R.string.second_user_tools_all_off),
                resetLabel = stringResource(R.string.second_user_tools_restore_default),
            )
            CardGroup {
                ownerEntries.forEach { entry ->
                    item(
                        headlineContent = {
                            Text(SecondUserToolsCatalog.ownerTitle(context, entry))
                        },
                        supportingContent = {
                            val desc = SecondUserToolsCatalog.ownerDescription(context, entry)
                            if (entry.recommended) {
                                Text(desc + " " + stringResource(R.string.second_user_tools_recommended_badge))
                            } else {
                                Text(desc)
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = storedOwner == null || entry.name in storedOwner,
                                onCheckedChange = { toggleOwner(entry.name, it) },
                            )
                        },
                    )
                }
            }

            Text(
                text = stringResource(R.string.second_user_tools_all_off_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    summary: String,
    onEnableAll: () -> Unit,
    onDisableAll: () -> Unit,
    onReset: () -> Unit,
    enableAllLabel: String,
    disableAllLabel: String,
    resetLabel: String,
) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            TextButton(onClick = onEnableAll) { Text(enableAllLabel) }
            TextButton(onClick = onReset) { Text(resetLabel) }
            TextButton(onClick = onDisableAll) { Text(disableAllLabel) }
        }
    }
}
