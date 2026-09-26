package me.rerere.rikkahub.data.claudep

import me.rerere.ai.provider.claudep.ClaudePToolBridgeHost
import me.rerere.rikkahub.data.ai.ToolExecutionGate
import me.rerere.rikkahub.data.ai.execution.ToolRuntime
import me.rerere.rikkahub.data.execution.InFlightApprovalWaiters
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.definition.Kind
import org.koin.core.instance.InstanceFactory
import kotlin.reflect.KClass

/**
 * The production graph's wiring, asserted at the level of its **definitions**.
 *
 * ## Why this is worth a test rather than a reading
 *
 * Two registrations for one type in one or two Koin modules is not a compile error and, with
 * `allowOverride` left at its default, not a startup error either: Koin replaces the first with the
 * second and every consumer silently gets a *different* instance from the one the other side of a
 * handshake holds. For the Claude P bridge that failure is not cosmetic — a host waiting on a
 * receipt registry nobody completes, or on approval waiters the approval lifecycle does not
 * release, presents as a tool call that sits out its whole thirty-minute deadline.
 *
 * So the properties asserted here are all of the form "exactly one, and it is not overridable",
 * and they are asserted against the modules as the application registers them.
 *
 * ## What this cannot prove, stated rather than implied
 *
 * It reads definitions; it does not instantiate them. A dependency cycle, or a constructor that
 * throws, is invisible here — Koin resolves lazily and needs an Android `Context` for most of this
 * graph, which is why the production graph is loaded for real by `ClaudePKoinGraphTest` on a
 * managed device. This test is the cheap half, and it is the half that catches the mistake a
 * reviewer cannot see by reading two files in different directories.
 *
 * ## Why it opts into a Koin internal API
 *
 * Reading a module's definitions needs `Module.mappings`, which Koin marks `@KoinInternalApi`. It
 * is opted into here, in a test, and not in production code: nothing the application ships depends
 * on it, the worst outcome of a Koin change is this file failing to compile, and a failure there
 * means the wiring has to be re-verified — which is exactly the moment to do it.
 */
@OptIn(KoinInternalApi::class)
class ClaudePToolProductionWiringTest {

    private val definitions: List<InstanceFactory<*>> = buildList {
        addAll(dataSourceModule.mappings.values)
        addAll(appModule.mappings.values)
    }

    private fun definitionsOf(type: KClass<*>): List<InstanceFactory<*>> =
        definitions.filter { it.beanDefinition.primaryType == type }

    private fun singleOf(type: KClass<*>): InstanceFactory<*> {
        val found = definitionsOf(type)
        assertEquals("exactly one definition of ${type.simpleName}", 1, found.size)
        val definition = found.single()
        assertEquals(
            "${type.simpleName} must be a single, or each consumer gets a private copy",
            Kind.Singleton,
            definition.beanDefinition.kind,
        )
        assertNotOverridable(type, definition)
        return definition
    }

    /**
     * A registration must not have *asked* to be replaceable.
     *
     * `allowOverride` is nullable and `null` when the definition did not opt in — which is every
     * definition here, and the state that matters: it means the application-wide default applies,
     * and `RikkaHubApp` does not enable overriding. An explicit `true` is the one shape that would
     * let a second registration silently win.
     */
    private fun assertNotOverridable(type: KClass<*>, definition: InstanceFactory<*>) {
        assertNotEquals(
            "${type.simpleName} opted in to being replaced by a later registration",
            true,
            definition.beanDefinition.allowOverride,
        )
    }

    /**
     * The one Claude P tool host, and the shape that makes it the only one.
     *
     * Declared against the **interface**, so the provider's `get()` and any other consumer resolve
     * the same object rather than each constructing its own.
     */
    @Test
    fun `the production graph declares exactly one Claude P tool host`() {
        val host = singleOf(ClaudePToolBridgeHost::class)

        assertEquals(
            "the host is registered against the interface the provider resolves",
            ClaudePToolBridgeHost::class,
            host.beanDefinition.primaryType,
        )
    }

    /**
     * The instances two sides of a handshake have to share, each declared once.
     *
     * Every one of these is a type where "two instances" is not a duplication but a broken
     * protocol: the waiters the approval lifecycle releases, the run controls `ConversationRuntime`
     * publishes into, the receipt registry `ChatService` completes, and the runtime every tool in
     * the app already runs through.
     */
    @Test
    fun `the graph declares exactly one of every instance the bridge shares with the app`() {
        listOf(
            InFlightApprovalWaiters::class,
            ClaudePToolRunControls::class,
            ClaudePToolPublicationReceipts::class,
            ToolRuntime::class,
            ToolExecutionGate::class,
        ).forEach { type ->
            singleOf(type)
        }
    }

    /**
     * No Claude P type is registered twice under different qualifiers.
     *
     * A named registration is the other way to end up with two instances of one thing: unqualified
     * lookups then resolve one of them, and which one is a detail of module load order.
     */
    @Test
    fun `no Claude P type carries a qualifier`() {
        val claudePTypes = definitions.filter { factory ->
            factory.beanDefinition.primaryType.java.name.startsWith(
                "me.rerere.rikkahub.data.claudep.",
            ) || factory.beanDefinition.primaryType.java.name.startsWith(
                "me.rerere.ai.provider.claudep.",
            )
        }
        val qualified = claudePTypes.filter { it.beanDefinition.qualifier?.value != null }
        assertTrue(
            "a qualified Claude P registration is a second instance under another name: " +
                qualified.map { it.beanDefinition.primaryType.simpleName },
            qualified.isEmpty(),
        )
    }
}
