package me.rerere.rikkahub.data.claudep

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.ai.provider.claudep.ClaudePCleanupTombstoneStore
import me.rerere.ai.provider.claudep.ClaudePPairingSettingsGateway
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * The production Koin graph, resolved on a device.
 *
 * ### Why this is an instrumentation test and not a JVM one
 *
 * The bug it exists for is a **missing definition in the production module**, and the only way to
 * test that honestly is to load the production module and resolve through it. Both definitions
 * construct Android-backed objects — `FileClaudePCleanupTombstoneStore` takes a `Context` and reads
 * `noBackupFilesDir`; `SettingsClaudePPairingGateway` takes the DataStore-backed `SettingsStore`.
 * A plain JVM test cannot produce either without Robolectric or a mocking framework, and adding one
 * is out of scope.
 *
 * So this runs on a device, against the graph the app itself builds, and **does not override the two
 * definitions under test** — overriding them is exactly how a missing production binding would be
 * hidden. It is compiled and executed only by the managed-device instrumentation workflow; the
 * `build-debug-apk` workflow neither compiles nor runs `androidTest`.
 *
 * ### What would have caught the crash
 *
 * `ClaudePDevicePairingRepository` was registered while its two collaborators were registered under
 * their concrete types. Koin does not bind an implementation to an interface automatically, so
 * `get<ClaudePCleanupTombstoneStore>()` had nothing to find. `ChatVM` depends on `ChatService`,
 * which depends on `ProviderManager`, whose `single` resolves the repository — so the failure
 * surfaced as "could not create ChatVM". The first assertion below throws `NoDefinitionFoundException`
 * under the old wiring.
 */
@RunWith(AndroidJUnit4::class)
class ClaudePKoinGraphTest {

    // Method names are plain camelCase, not backticked. A backticked name contains spaces, and D8
    // rejects spaces in a DEX method name below DEX version 040 — which is what this module's
    // `minSdk = 26` produces. A JVM unit test would have accepted them; an instrumentation test
    // cannot. Every other class in `androidTest` already follows this, so the naming is the
    // repository's existing convention rather than a workaround.

    /** The graph the running app built in `RikkaHubApp.onCreate`. */
    private val koin get() = GlobalContext.get()

    @Test
    fun tombstoneStoreResolvesThroughItsInterface() {
        val resolved = koin.get<ClaudePCleanupTombstoneStore>()

        assertTrue(
            "expected the production implementation, got ${resolved::class.java.name}",
            resolved is FileClaudePCleanupTombstoneStore,
        )
    }

    @Test
    fun pairingSettingsGatewayResolvesThroughItsInterface() {
        val resolved = koin.get<ClaudePPairingSettingsGateway>()

        assertTrue(
            "expected the production implementation, got ${resolved::class.java.name}",
            resolved is SettingsClaudePPairingGateway,
        )
    }

    @Test
    fun bothInterfacesResolveToTheSameInstance() {
        // A `single` must hand back one object. Two instances would mean two writers of pairing
        // state, which is the failure the coordinator's single-owner design exists to prevent.
        assertSame(
            koin.get<ClaudePCleanupTombstoneStore>(),
            koin.get<ClaudePCleanupTombstoneStore>(),
        )
        assertSame(
            koin.get<ClaudePPairingSettingsGateway>(),
            koin.get<ClaudePPairingSettingsGateway>(),
        )
    }

    @Test
    fun repositoryDefinitionCompletesConstruction() {
        // The exact call the `ProviderManager` single makes. Under the old wiring this threw
        // `NoDefinitionFoundException` for `tombstoneStore`, and the app never opened a chat page.
        val repository = koin.get<ClaudePDevicePairingRepository>()

        assertNotNull(repository)
    }

    @Test
    fun repositoryIsASingle() {
        assertSame(
            koin.get<ClaudePDevicePairingRepository>(),
            koin.get<ClaudePDevicePairingRepository>(),
        )
    }

    @Test
    fun graphResolvesUpToProviderManager() {
        // ChatVM -> ChatService -> ProviderManager is the chain that failed. This walks it in the
        // order it is actually constructed.
        //
        // The test stops at `ProviderManager`: `ChatVM` itself is a ViewModel created with runtime
        // parameters (a conversation id) and reached only through the chat page, so it is NOT
        // verified here and must not be claimed as such. What is verified is that every Koin
        // definition on the way to it — including the two that were missing — now resolves.
        val repository = koin.get<ClaudePDevicePairingRepository>()
        val providerManager = koin.get<me.rerere.ai.provider.ProviderManager>()

        assertNotNull(repository)
        assertNotNull(providerManager)
    }

    @Test
    fun graphResolvesRepositoryCollaborators() {
        // Named together because the failure mode was a *pair* of missing bindings: fixing one and
        // not the other would still fail here.
        val tombstone: ClaudePCleanupTombstoneStore = koin.get()
        val gateway: ClaudePPairingSettingsGateway = koin.get()

        assertNotNull(tombstone)
        assertNotNull(gateway)
    }
}
