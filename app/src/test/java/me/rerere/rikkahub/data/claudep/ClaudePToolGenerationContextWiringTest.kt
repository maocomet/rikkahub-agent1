package me.rerere.rikkahub.data.claudep

import me.rerere.rikkahub.data.ai.ToolCallOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

/**
 * The generation identity, from the authority that owns each field to the provider that reads it.
 *
 * ## What is proven here, and what cannot be
 *
 * Two halves of one claim, and they are proven differently because they *are* different:
 *
 * 1. **The mapping is testable, so it is tested.** [ClaudePToolGenerationContextFactory] is a pure
 *    function, so "which authority does each field come from" is asserted field by field against
 *    distinct sentinels rather than accepted on inspection.
 *
 * 2. **The threading is not instantiable on the JVM, so it is asserted structurally.**
 *    `GenerationHandler` needs an Android `Context` and the full Koin graph; no JVM unit test can
 *    build one, and a test that pretended otherwise would be testing a mock of the thing. What is
 *    checked instead is the source: that the value is *threaded* — handed down and passed through
 *    — rather than rebuilt at any point on the way. A rebuild is the actual hazard, because a
 *    second construction of a six-field identity is a second chance to disagree about which
 *    generation is running, and it is exactly the change a later maintenance pass would make
 *    innocently. This is the same technique `LearningArchitectureBoundaryTest` uses for the
 *    architecture boundaries that are likewise not reachable from a unit test.
 *
 * Read together with the `:ai` side — `ClaudePToolGenerationContextTest` proves the value rides
 * [me.rerere.ai.provider.TextGenerationParams] as a `@Transient` property and survives `copy` as
 * the *same object*; `ClaudePToolFrameTest` proves carrying it changes no byte of the encoded
 * request — the chain from the app's authorities to the provider's read is closed at both ends.
 *
 * ## What this deliberately does not claim
 *
 * It does not prove the runtime behaviour of `generateText`, because it cannot. It proves that the
 * only construction is the factory, that the factory's inputs are the six authorities and nothing
 * else, and that every hop in between passes the value along unchanged.
 */
class ClaudePToolGenerationContextWiringTest {

    // ---------------------------------------------------------------------------------------
    // 1. The mapping: every authority to its own field
    // ---------------------------------------------------------------------------------------

    /**
     * Each field is read from the authority that owns it, and from no other.
     *
     * Six distinct sentinels, because the failure this catches is a *swap*: `commandId` filled
     * from the run id, or `branchId` from the conversation. A test with one shared value would
     * pass for every permutation.
     */
    @Test
    fun `each authority lands in its own field`() {
        val runId = Uuid.random()
        val commandId = Uuid.random()
        val conversationId = Uuid.random()
        val assistantId = Uuid.random()
        val branchId = Uuid.random()

        val context = ClaudePToolGenerationContextFactory.build(
            runId = runId,
            authoritativeCommandId = commandId,
            conversationId = conversationId,
            assistantId = assistantId,
            branchId = branchId,
            callOrigin = ToolCallOrigin.LocalChat,
        )

        assertEquals(runId.toString(), context.runId)
        assertEquals(commandId.toString(), context.commandId)
        assertEquals(conversationId.toString(), context.conversationId)
        assertEquals(assistantId.toString(), context.assistantId)
        assertEquals(branchId.toString(), context.branchId)
        assertEquals(ToolCallOrigin.LocalChat.name, context.callOrigin)

        // The five identities are pairwise distinct, so no two of the assertions above can be
        // satisfied by the same value.
        val identities = listOf(context.runId, context.commandId, context.conversationId, context.assistantId, context.branchId)
        assertEquals("the sentinels must be distinct for this test to mean anything", 5, identities.toSet().size)
    }

    /**
     * The run id and the durable command id are different identities, and neither stands in for the
     * other.
     *
     * This is the one substitution the surrounding code calls out by name: a run correlation is not
     * an admitted command, and several paths (cron, workflow, recovery) have the first without the
     * second. Filling `commandId` from `runId` would bind a tool call to a command that never
     * admitted it.
     */
    @Test
    fun `a missing command id is blank rather than filled from the run id`() {
        val runId = Uuid.random()

        val context = ClaudePToolGenerationContextFactory.build(
            runId = runId,
            authoritativeCommandId = null,
            conversationId = Uuid.random(),
            assistantId = Uuid.random(),
            branchId = Uuid.random(),
            callOrigin = ToolCallOrigin.TrustedWorkflow,
        )

        assertEquals(runId.toString(), context.runId)
        assertEquals("the run id must not stand in for the command id", "", context.commandId)
        assertNotEquals(context.runId, context.commandId)
    }

    // ---------------------------------------------------------------------------------------
    // 2. Absence fails closed, and is never completed by guesswork
    // ---------------------------------------------------------------------------------------

    /**
     * Every authority can be absent, and an absent one is blank — never borrowed from a neighbour.
     *
     * The parameter list is the point: there is no conversation fallback, no "current assistant",
     * no page state and no global to read. A caller that has no authority for a field passes
     * `null`, and the result says so. If this factory had a source of values beyond its arguments,
     * this test could not be written in this shape.
     */
    @Test
    fun `an absent authority makes the identity incomplete rather than borrowing one`() {
        fun identity(
            runId: Uuid? = Uuid.random(),
            commandId: Uuid? = Uuid.random(),
            conversationId: Uuid? = Uuid.random(),
            assistantId: Uuid? = Uuid.random(),
            branchId: Uuid? = Uuid.random(),
        ) = ClaudePToolGenerationContextFactory.build(
            runId = runId,
            authoritativeCommandId = commandId,
            conversationId = conversationId,
            assistantId = assistantId,
            branchId = branchId,
            callOrigin = ToolCallOrigin.LocalChat,
        )

        assertTrue("a fully specified identity must be usable", identity().isComplete)

        // One authority absent at a time. Each case must leave its own field blank *and* make the
        // whole identity unusable — a context with a hole is not one to be completed by whoever
        // reads it next.
        val absent = listOf(
            "runId" to identity(runId = null),
            "commandId" to identity(commandId = null),
            "conversationId" to identity(conversationId = null),
            "assistantId" to identity(assistantId = null),
            "branchId" to identity(branchId = null),
        )

        absent.forEach { (field, context) ->
            assertFalse("an identity with no $field must not be usable", context.isComplete)
        }

        assertEquals("", identity(runId = null).runId)
        assertEquals("", identity(commandId = null).commandId)
        assertEquals("", identity(conversationId = null).conversationId)
        assertEquals("", identity(assistantId = null).assistantId)
        assertEquals("", identity(branchId = null).branchId)
    }

    /**
     * The origin token is the enum's own name, carried exactly.
     *
     * The bridge's mapping is an exact match, so anything this layer did to the token — trimming,
     * case folding, mapping an unknown origin to a known one — would be substituting an origin the
     * caller did not supply, and granting that origin's tool surface and approval policy on the
     * strength of a string. There is therefore nothing to normalise here, and this asserts that
     * the tokens the vocabulary actually holds survive round-trip.
     */
    @Test
    fun `every origin token is carried as the enum spells it`() {
        ToolCallOrigin.entries.forEach { origin ->
            val context = ClaudePToolGenerationContextFactory.build(
                runId = Uuid.random(),
                authoritativeCommandId = Uuid.random(),
                conversationId = Uuid.random(),
                assistantId = Uuid.random(),
                branchId = Uuid.random(),
                callOrigin = origin,
            )
            assertEquals(origin.name, context.callOrigin)
        }

        // And the vocabulary is not empty, so the loop above is not vacuous.
        assertTrue("the origin vocabulary must have entries", ToolCallOrigin.entries.isNotEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // 3. The threading, asserted where it is observable
    // ---------------------------------------------------------------------------------------

    /**
     * The app builds the identity through the factory, and constructs one nowhere else.
     *
     * "Exactly one construction site" is the property that makes the mapping above the mapping the
     * app actually uses. A second inline construction elsewhere would not be covered by it.
     */
    @Test
    fun `the dispatch site builds the identity through the factory and nowhere else`() {
        val chatService = projectFile(
            "app/src/main/java/me/rerere/rikkahub/service/ChatService.kt",
            "src/main/java/me/rerere/rikkahub/service/ChatService.kt",
        ).readText(Charsets.UTF_8)

        assertTrue(
            "ChatService must build the generation identity through the factory",
            "ClaudePToolGenerationContextFactory.build(" in chatService,
        )
        assertFalse(
            "ChatService must not construct a generation identity inline; the mapping would " +
                "then be untested at the site that actually runs",
            "ClaudePToolGenerationContext(" in chatService,
        )
    }

    /**
     * `GenerationHandler` threads the identity and never rebuilds it.
     *
     * A rebuild here is the quiet failure: it would compile, it would look right, and it would
     * produce a second answer to "which generation is this" from whatever state the handler
     * happened to hold. The parameter and the pass-throughs are the whole of its involvement.
     */
    @Test
    fun `the generation handler threads the identity and never rebuilds it`() {
        val handler = projectFile(
            "app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt",
            "src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt",
        ).readText(Charsets.UTF_8)

        assertFalse(
            "GenerationHandler must not construct a generation identity; a rebuild is a second " +
                "chance to disagree about which generation is running",
            "ClaudePToolGenerationContext(" in handler,
        )
        assertTrue(
            "generateText must accept the identity from its caller",
            "claudePToolGenerationContext: me.rerere.ai.provider.claudep.ClaudePToolGenerationContext? = null" in handler,
        )
        assertTrue(
            "the identity must reach the provider dispatch rather than stopping at the entry point",
            "claudePToolGenerationContext = claudePToolGenerationContext" in handler,
        )
    }

    /**
     * The final-answer recovery dispatch passes an explicit `null` and does not inherit.
     *
     * Recovery offers Claude `tools = emptyList()`, so there is no tool call for it to bind — and
     * there is no generation identity that could belong to it either. Inheriting the caller's
     * identity by default is how a later change that gives recovery a tool surface would quietly
     * bind the wrong generation, so the `null` is an assertion about a future change as much as
     * about this one.
     */
    @Test
    fun `the final-answer recovery dispatch refuses to inherit an identity`() {
        val handler = projectFile(
            "app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt",
            "src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt",
        ).readText(Charsets.UTF_8)

        assertTrue(
            "the final-answer recovery dispatch must pass an explicit null identity",
            "claudePToolGenerationContext = null" in handler,
        )
    }

    /**
     * The identity is carried on the request parameters and read from there — never rebuilt by the
     * provider either.
     *
     * The provider is the last hop before the wire, and the one place a rebuild would be most
     * tempting: it has the params object in hand and could plausibly reconstruct what it thinks the
     * identity should be. It reads the field instead.
     */
    @Test
    fun `the provider reads the identity off the parameters rather than rebuilding it`() {
        val provider = aiModuleFile(
            "ai/src/main/java/me/rerere/ai/provider/providers/ClaudePProvider.kt",
        ).readText(Charsets.UTF_8)

        assertTrue(
            "the provider must hand the params' identity to the bridge host",
            "toolHost.prepare(params.tools, params.claudePToolGenerationContext)" in provider,
        )
        assertFalse(
            "the provider must not construct a generation identity of its own",
            "ClaudePToolGenerationContext(" in provider,
        )
    }

    /**
     * The identity does not reach the fingerprint.
     *
     * The fingerprint is computed from the request's own content, and an identity folded into it
     * would both leak an Android-internal id into a digest that travels and make two identical
     * requests fingerprint differently depending on which run sent them.
     */
    @Test
    fun `the request fingerprint does not mention the generation identity`() {
        val fingerprint = aiModuleFile(
            "ai/src/main/java/me/rerere/ai/provider/claudep/ClaudePProtocol.kt",
        )
        val sources = fingerprint.parentFile!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText(Charsets.UTF_8).contains("ClaudePRequestFingerprint") }
            .toList()

        assertTrue("the fingerprint implementation must be locatable", sources.isNotEmpty())
        sources.forEach { file ->
            val declarations = file.readText(Charsets.UTF_8)
                .lineSequence()
                .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                .joinToString("\n")
            assertFalse(
                "${file.name} must not fold the generation identity into the fingerprint",
                "claudePToolGenerationContext" in declarations,
            )
        }
    }

    private fun projectFile(vararg candidates: String): File =
        requireNotNull(candidates.asSequence().map(::File).firstOrNull(File::isFile)) {
            "Cannot locate ${candidates.joinToString()} from ${File(".").absolutePath}"
        }

    /**
     * A file in the `:ai` module.
     *
     * `:app`'s unit tests run with the working directory set to `app/`, so a path inside `:ai` has
     * to climb out of the module first — the same reason the repo's other boundary tests try more
     * than one candidate.
     */
    private fun aiModuleFile(relative: String): File =
        projectFile(relative, "../$relative")
}
