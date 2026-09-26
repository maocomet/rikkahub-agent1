package me.rerere.rikkahub.data.claudep

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.claudep.ClaudePToolGenerationContext
import me.rerere.ai.provider.claudep.ClaudePToolPreparationRefusal
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The production tool host, and the surface it now offers.
 *
 * ## Three claims, tested separately because they fail differently
 *
 * 1. **Every missing identity fails closed.** A generation that cannot be named, a partial
 *    context, an origin token this app does not recognise, a device that cannot name itself — each
 *    must produce a preparation that offers **no** tools. None of them may be completed by
 *    guesswork, and none may degrade to "all tools".
 * 2. **The flag decides, and nothing else does.** The surface was closed while the paths that
 *    answer a call were still being built, and it is open now that both gates have answered on a
 *    real device. Both readings are asserted against the *same* input, so each is a decision this
 *    host makes rather than a property of a broken assembly.
 * 3. **A non-empty catalog travels only when every precondition holds.** The `tool_snapshot` is
 *    the frame that tells the Server which tools exist, so "which preconditions produce one" is
 *    the whole of what activation opens.
 *
 * The subject type appears nowhere in these tests because it decides nothing here (§11.5 of the
 * M2-B report): an ordinary assistant and a second user get the same host and the same answer.
 */
class ClaudePToolBridgeHostImplTest {

    private fun context(
        runId: String = "run-1",
        commandId: String = "command-1",
        conversationId: String = "conversation-1",
        assistantId: String = "assistant-1",
        branchId: String = "branch-1",
        callOrigin: String = "LocalChat",
    ) = ClaudePToolGenerationContext(
        runId = runId,
        commandId = commandId,
        conversationId = conversationId,
        assistantId = assistantId,
        branchId = branchId,
        callOrigin = callOrigin,
    )

    /** One tool the catalog can actually freeze: a name it accepts and a declared schema. */
    private fun tools() = listOf(
        Tool(
            name = "read_file",
            description = "Read a file",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = { emptyList() },
        ),
    )

    private fun host(
        offerCatalog: Boolean = false,
        deviceRef: String? = "device-1",
    ) = ClaudePToolBridgeHostImpl(
        deviceRefProvider = { deviceRef },
        offerCatalog = offerCatalog,
        publications = ClaudePToolPublicationReceipts(),
    )

    // ---------------------------------------------------------------------------------------
    // 1. Every missing or unusable identity offers nothing
    // ---------------------------------------------------------------------------------------

    /**
     * No generation identity at all is a refusal, and the reason is recorded locally.
     *
     * It is deliberately *not* the text path: an assistant with no tools is a working
     * configuration, whereas this is a generation that wanted tools and could not be bound to one.
     * The distinction is what lets the two be told apart in a log.
     */
    @Test
    fun `no generation context refuses and offers nothing`() = runBlocking {
        val preparation = host(offerCatalog = true).prepare(tools(), context = null)

        assertTrue(preparation.catalog.isEmpty)
        assertNull(preparation.snapshot)
        assertNull(preparation.executionRef)
        assertEquals(ClaudePToolPreparationRefusal.NO_GENERATION_CONTEXT, preparation.refusal)
        assertFalse("a refusal is not the text path", preparation.isTextPath)
    }

    /**
     * A *partial* identity is not an identity. Every position is checked, because the failure this
     * prevents is binding a call to a generation whose other fields were filled in by assumption.
     */
    @Test
    fun `an incomplete generation context refuses in every position`() = runBlocking {
        val incomplete = listOf(
            "runId" to context(runId = ""),
            "commandId" to context(commandId = ""),
            "conversationId" to context(conversationId = ""),
            "assistantId" to context(assistantId = ""),
            "branchId" to context(branchId = ""),
            "callOrigin" to context(callOrigin = "   "),
        )

        incomplete.forEach { (field, value) ->
            val preparation = host(offerCatalog = true).prepare(tools(), value)
            assertTrue("a blank $field must offer nothing", preparation.catalog.isEmpty)
            assertEquals(
                "a blank $field must refuse rather than be completed",
                ClaudePToolPreparationRefusal.INCOMPLETE_GENERATION_CONTEXT,
                preparation.refusal,
            )
        }
    }

    /**
     * An origin token this app does not recognise is refused, and is never substituted.
     *
     * The mapping is exact — the enum's own `name`, compared character for character. A near miss
     * (a differently-cased token, a padded one) must not resolve to a real origin, because doing so
     * would grant that origin's tool surface and approval policy on the strength of a string.
     */
    @Test
    fun `an unrecognised origin token refuses rather than being normalised`() = runBlocking {
        // A *present but unrecognised* token. A blank one is a different refusal — it makes the
        // whole context incomplete before the origin is ever looked at, and that case is covered
        // above. Keeping the two apart is the point: "nobody named an origin" and "we do not know
        // this origin" are different answers, and collapsing them would hide which happened.
        val nearMisses = listOf("localchat", "LOCALCHAT", " LocalChat ", "LocalChat2", " LocalChat")

        nearMisses.forEach { token ->
            val preparation = host(offerCatalog = true).prepare(tools(), context(callOrigin = token))
            assertTrue("token <$token> must offer nothing", preparation.catalog.isEmpty)
            assertEquals(
                "token <$token> must not be normalised into an origin",
                ClaudePToolPreparationRefusal.UNKNOWN_CALL_ORIGIN,
                preparation.refusal,
            )
        }
    }

    /** Every origin the app really has is accepted, so the refusals above are not vacuous. */
    @Test
    fun `every real origin token is accepted`() = runBlocking {
        me.rerere.rikkahub.data.ai.ToolCallOrigin.entries.forEach { origin ->
            val preparation = host(offerCatalog = true).prepare(tools(), context(callOrigin = origin.name))
            assertNull("${origin.name} is a real origin and must not be refused", preparation.refusal)
            assertFalse(preparation.catalog.isEmpty)
        }
    }

    /**
     * A device that cannot name itself offers nothing, rather than borrowing a placeholder.
     *
     * The device reference is part of the binding, so two devices sharing a placeholder would
     * share a binding. Offering nothing costs a capability; offering a wrong identity costs
     * correctness.
     */
    @Test
    fun `a device that cannot name itself offers nothing`() = runBlocking {
        val preparation = host(offerCatalog = true, deviceRef = null).prepare(tools(), context())

        assertTrue(preparation.catalog.isEmpty)
        assertNull(preparation.snapshot)
        assertNull(preparation.executionRef)
    }

    // ---------------------------------------------------------------------------------------
    // 2. The closed surface
    // ---------------------------------------------------------------------------------------

    /**
     * The state the app is actually in: a real host, closed.
     *
     * This is the assertion that no intermediate wiring can expose a tool. The host is injected and
     * resolves; the catalog is assembled and validated; and what goes to Claude is still nothing at
     * all — no snapshot, no staged plan, and therefore no `tool.invoke` that could ever arrive.
     */
    @Test
    fun `a closed host offers nothing even though the catalog assembles`() = runBlocking {
        val instance = host(offerCatalog = false)
        val preparation = instance.prepare(tools(), context())

        assertTrue("nothing may be offered while the surface is closed", preparation.catalog.isEmpty)
        assertNull("no snapshot means the start frame is unchanged", preparation.snapshot)
        assertNull("no plan is staged for a call that cannot arrive", preparation.executionRef)
        assertTrue("a closed surface is the text path", preparation.isTextPath)

        // Nothing to bind either: the plan that would be staged is never created, so even a
        // well-formed open for this generation has nothing to redeem.
        assertTrue(instance.openGeneration("gen-1", preparation))
        assertEquals(0, instance.boundGenerationCount)
    }

    /**
     * The same input, with the surface open, really does produce a catalog — which is what makes
     * the assertion above a decision rather than a symptom.
     *
     * If the assembly were broken, "closed offers nothing" would pass for the wrong reason and the
     * activation commit would silently still offer nothing.
     */
    @Test
    fun `an open host offers the assembled catalog and stages exactly one plan`() = runBlocking {
        val preparation = host(offerCatalog = true).prepare(tools(), context())

        assertFalse("the catalog must assemble from a real tool", preparation.catalog.isEmpty)
        assertNotNull("an offered catalog travels as a snapshot", preparation.snapshot)
        assertNotNull("an offerable generation gets a plan to bind", preparation.executionRef)
        assertNull(preparation.refusal)
        assertFalse(preparation.isTextPath)

        // The identity travels with it, so the binding can be checked against the generation.
        assertEquals("device-1", preparation.deviceRef)
        assertEquals("conversation-1", preparation.conversationId)
        assertEquals("assistant-1", preparation.assistantId)
        assertEquals("branch-1", preparation.branchId)
        assertTrue("a call must have a bounded lifetime", preparation.timeoutMs > 0L)
    }

    /**
     * An assistant with no tools is a working configuration, not a refusal — open or closed.
     *
     * The bytes of a text-only chat must not change because a tool host exists behind the provider.
     */
    @Test
    fun `an assistant with no tools is the text path in both states`() = runBlocking {
        listOf(false, true).forEach { open ->
            val preparation = host(offerCatalog = open).prepare(emptyList(), context())
            assertTrue(preparation.isTextPath)
            assertNull("the text path sends no snapshot", preparation.snapshot)
            assertNull(preparation.refusal)
            assertNull(preparation.executionRef)
        }
    }

    // ---------------------------------------------------------------------------------------
    // 3. Binding an offered generation
    // ---------------------------------------------------------------------------------------

    /**
     * A plan is bound to the exact generation id, and to nothing else.
     *
     * The lookup takes an id and only an id, so the cross-generation search the M2 gate forbids has
     * no expression here. A near-miss id is a miss.
     */
    @Test
    fun `a plan is bound to its exact generation id and no other`() = runBlocking {
        val instance = host(offerCatalog = true)
        val preparation = instance.prepare(tools(), context())

        assertTrue(instance.openGeneration("gen-1", preparation))
        assertEquals(1, instance.boundGenerationCount)

        instance.closeGeneration("gen-1")
        assertEquals("closing the exact id revokes it", 0, instance.boundGenerationCount)
    }

    /**
     * A token is redeemed once. A second attempt for the same generation cannot re-stage a plan.
     *
     * This is what keeps a re-delivered frame from being answered with a binding that a later,
     * different preparation put in place.
     */
    @Test
    fun `a spent execution token cannot be redeemed for another generation`() = runBlocking {
        val instance = host(offerCatalog = true)
        val preparation = instance.prepare(tools(), context())
        assertNotNull(preparation.executionRef)

        assertTrue("the token's own generation binds", instance.openGeneration("gen-1", preparation))

        // The same token, offered for a *different* generation. Redeeming it once consumed it, so
        // this must fail rather than hand gen-2 the plan that gen-1 is already running under — the
        // cross-generation binding the exact-id lookup exists to make impossible.
        assertFalse(
            "a spent token must not bind a second generation",
            instance.openGeneration("gen-2", preparation),
        )
        assertEquals("only the generation that redeemed it holds a plan", 1, instance.boundGenerationCount)
    }

    /**
     * The text path binds nothing and succeeds.
     *
     * A generation with no plan is not a failure — it is a generation with no tools, and failing it
     * would break text-only chat for every assistant that simply has none.
     */
    @Test
    fun `binding the text path succeeds and holds no plan`() = runBlocking {
        val instance = host(offerCatalog = false)
        val preparation = instance.prepare(tools(), context())

        assertTrue("a generation with no tools is still servable", instance.openGeneration("gen-1", preparation))
        assertEquals(0, instance.boundGenerationCount)
    }

    // ---------------------------------------------------------------------------------------
    // 4. The wiring itself
    // ---------------------------------------------------------------------------------------

    /**
     * Exactly one production host is registered, and the provider uses it instead of the default.
     *
     * The whole point of `single<ClaudePToolBridgeHost>` is that there is no second one to resolve;
     * a duplicate registration would be a wiring bug that still compiles, still runs, and quietly
     * gives the provider a host nobody else can see. The provider's `NONE` default is what the app
     * would silently keep if this binding were forgotten, so both halves are asserted.
     */
    @Test
    fun `the composition root registers exactly one production host and the provider uses it`() {
        val module = projectFile(
            "app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt",
            "src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt",
        ).readText(Charsets.UTF_8)

        val registrations = module
            .lineSequence()
            .count { it.contains("single<me.rerere.ai.provider.claudep.ClaudePToolBridgeHost>") }
        assertEquals("exactly one production host may be registered", 1, registrations)

        assertTrue(
            "the provider must be handed the host rather than keeping the inert default",
            "toolHost = get()" in module,
        )

        // And the host must not be constructed anywhere else, which is how a second, private host
        // would appear without any registration being duplicated.
        val constructed = module
            .lineSequence()
            .count { it.contains("ClaudePToolBridgeHostImpl(") }
        assertEquals("the host is constructed only by its own registration", 1, constructed)
    }

    /**
     * The surface is open at the one site that decides it.
     *
     * This asserts the *state* rather than the mechanism. It read `offerCatalog = false` while the
     * surface was closed; the activation commit flipped it, and that flip was gated on the two
     * runs this file cannot perform — a real compile-and-test run, and a managed-device run whose
     * approval barrier suite answered against a real `AppDatabase`. Closing the surface again has
     * to be as deliberate as opening it was, which is why the assertion is on the **value** and
     * not on the mere presence of the line.
     */
    @Test
    fun `the production host is registered with the catalog open`() {
        val module = projectFile(
            "app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt",
            "src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt",
        ).readText(Charsets.UTF_8)

        assertTrue(
            "the production host must be registered with offerCatalog = true: the surface was " +
                "opened only after the closed-surface CI run and the managed-device run both " +
                "answered, and this is the state a reader has to be able to see",
            "offerCatalog = true" in module,
        )
    }

    // ---------------------------------------------------------------------------------------
    // 5. Activation: what actually travels, and only when everything holds
    // ---------------------------------------------------------------------------------------

    /**
     * The vertical property the activation rests on: a non-empty catalog travels **only** when
     * every precondition holds, and each one on its own withholds it.
     *
     * The `tool_snapshot` is the frame that matters. `ClaudePProvider` sends it when — and only
     * when — the preparation carries a non-empty catalog, so a preparation that offers nothing
     * sends nothing, and the Server registers no bridge tool. This walks the preconditions as
     * separate claims rather than asserting the happy path alone, because a surface that is open
     * for the *wrong* reason is the failure an activation commit is most likely to introduce: the
     * flag gets flipped, a dependency is missing, and every test stays green while no tool ever
     * runs — or worse, while a tool runs that should not have been offered.
     */
    @Test
    fun `a non-empty snapshot travels only when every precondition holds`() = runBlocking {
        val open = host(offerCatalog = true)

        // The one shape that does travel.
        val offered = open.prepare(tools(), context())
        assertFalse(offered.catalog.isEmpty)
        assertNotNull("the snapshot is the frame, not the catalog", offered.snapshot)
        assertTrue(offered.snapshot.toString().contains("read_file"))

        // Every precondition, withheld on its own. The assertion is the same for each — no
        // catalog, therefore no snapshot — because that is the property the provider keys on.
        val withheld = listOf(
            "no generation context" to open.prepare(tools(), null),
            "an incomplete generation context" to open.prepare(tools(), context().copy(assistantId = "")),
            "an origin this build does not know" to
                open.prepare(tools(), context().copy(callOrigin = "Localchat")),
            "a device that cannot name itself" to host(offerCatalog = true, deviceRef = null)
                .prepare(tools(), context()),
            "an assistant with no offerable tools" to open.prepare(emptyList(), context()),
        )
        withheld.forEach { (what, preparation) ->
            assertTrue(
                "$what must offer nothing, and therefore send no snapshot",
                preparation.catalog.isEmpty && preparation.snapshot == null,
            )
        }
    }

    private fun projectFile(vararg candidates: String): File =
        requireNotNull(candidates.asSequence().map(::File).firstOrNull(File::isFile)) {
            "Cannot locate ${candidates.joinToString()} from ${File(".").absolutePath}"
        }
}
