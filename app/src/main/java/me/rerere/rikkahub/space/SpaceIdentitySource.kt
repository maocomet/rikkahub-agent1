package me.rerere.rikkahub.space

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar

/**
 * Where the Cat Garden UI reads the assistant directory and the person's own display identity.
 *
 * A port rather than a direct [SettingsStore] dependency so the screen's state can be exercised on
 * the JVM: `SettingsStore` is a final class that needs an Android Context, which puts the whole
 * read model out of reach of a unit test. The production adapter is a two-line passthrough, so the
 * indirection costs nothing at runtime.
 */
interface SpaceIdentitySource {
    suspend fun assistants(): List<Assistant>
    val nickname: Flow<String>
    val avatar: Flow<Avatar>
}

class SettingsSpaceIdentitySource(
    private val settingsStore: SettingsStore,
) : SpaceIdentitySource {
    override suspend fun assistants(): List<Assistant> =
        settingsStore.settingsFlow.first().assistants

    override val nickname: Flow<String> =
        settingsStore.settingsFlow.map { it.displaySetting.userNickname }

    override val avatar: Flow<Avatar> =
        settingsStore.settingsFlow.map { it.displaySetting.userAvatar }
}
