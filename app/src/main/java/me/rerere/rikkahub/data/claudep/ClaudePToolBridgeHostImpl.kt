package me.rerere.rikkahub.data.claudep

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.claudep.BridgeToolExecution
import me.rerere.ai.provider.claudep.ClaudePToolBridgeHost
import me.rerere.ai.provider.claudep.ClaudePToolGenerationContext
import me.rerere.ai.provider.claudep.ClaudePToolPreparation
import me.rerere.ai.provider.claudep.ClaudePToolPreparationRefusal
import me.rerere.ai.provider.claudep.ClaudePToolStatusSink
import me.rerere.ai.provider.claudep.bridge.BridgeExecutionBindings
import me.rerere.ai.provider.claudep.bridge.BridgeExecutionHost
import me.rerere.ai.provider.claudep.bridge.BridgeInvocation
import me.rerere.ai.provider.claudep.bridge.BridgeLimits
import me.rerere.ai.provider.claudep.bridge.ToolCallOutcome
import me.rerere.ai.provider.claudep.bridge.ToolCallState
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import kotlin.uuid.Uuid

/**
 * The app's half of the Claude P tool bridge: the one production host behind
 * [ClaudePToolBridgeHost].
 *
 * ## What it composes, and what it deliberately does not
 *
 * It composes what the batch before it built — the catalog assembly, [BridgeExecutionBindings],
 * and (in later stages) the run-control registry, the in-flight waiters and the execution host.
 * It implements none of them. Running a tool is still `DefaultToolRuntime`'s, deciding whether to
 * ask the user is still `ToolExecutionGate`'s and the approval lifecycle's, and reaching an MCP
 * server is still `McpManager`'s. A second implementation of any of those is what the M2 gate
 * forbids by name, and this class has no room to grow one.
 *
 * ## Provider and second user are independent settings
 *
 * Nothing here reads the assistant's subject type to decide whether a tool may run or whether the
 * user may be asked. Which provider an assistant uses and whether it is a second user are two
 * independent settings; an ordinary assistant that selects Claude P keeps its approval-gated
 * tools, and the `IN_FLIGHT` continuation is admitted for any subject type. The subject type is
 * still *carried* — it is part of the approval identity — but it decides nothing here.
 *
 * ## Why the catalog is closed until explicitly opened
 *
 * [offerCatalog] is the single point at which this host may tell Claude about a tool. It is
 * `false` while any path that could *answer* a tool call is still missing, because offering a tool
 * the app cannot conclude is worse than offering none: the Server freezes the catalog, the model
 * calls what it was shown, and a call nobody can answer leaves the peer blocked until its
 * deadline. A closed surface is therefore the safe default, and it is indistinguishable from the
 * text path — empty catalog, no snapshot, no execution plan staged, `execute` unreachable.
 *
 * The flag exists so that state is one greppable fact rather than an emergent property of several
 * partially-wired paths. It is not a feature toggle a user or a build variant may flip.
 */
class ClaudePToolBridgeHostImpl(
    /**
     * The app's device correlator, or `null` when this device cannot name itself.
     *
     * `null` is not an error to paper over: the binding is keyed partly on it, and a placeholder
     * would give two devices the same binding. A host that cannot name its device offers no tools,
     * which is the same fail-closed answer every other missing identity gets.
     */
    private val deviceRefProvider: suspend () -> String?,
    /**
     * Whether this host may offer a catalog to Claude. See the class doc.
     *
     * Required rather than defaulted, so that the one construction site has to state it and a
     * reader of that line can see which state the app is in without following the value.
     */
    private val offerCatalog: Boolean,
) : ClaudePToolBridgeHost {

    /**
     * The per-generation execution plans, keyed by the exact generation id and by nothing else.
     *
     * Staged by [prepare] under a host-owned token and redeemed by [openGeneration] once the
     * Server has assigned an id — the two-step shape the provider's own ordering requires, since
     * the catalog has to travel *in* `generation.start` and the id only comes back afterwards.
     */
    private val bindings = BridgeExecutionBindings<ClaudePToolExecutionPlan>()

    /** For diagnostics and tests: how many generations currently resolve to a plan. */
    val boundGenerationCount: Int get() = bindings.boundCount

    override suspend fun prepare(
        tools: List<Tool>,
        context: ClaudePToolGenerationContext?,
    ): ClaudePToolPreparation {
        // Each refusal is a distinct, locally-recorded reason. None of them is a protocol value and
        // none of them guesses: the bridge has no vocabulary for "Android could not bind this
        // generation", and inventing one would claim a value the Server does not hold.
        val identity = context
            ?: return ClaudePToolPreparation.refused(ClaudePToolPreparationRefusal.NO_GENERATION_CONTEXT)
        if (!identity.isComplete) {
            return ClaudePToolPreparation.refused(
                ClaudePToolPreparationRefusal.INCOMPLETE_GENERATION_CONTEXT,
            )
        }

        // Exact match against the app's own vocabulary. Not trimmed, not case-folded, and with no
        // fallback: substituting an origin the caller did not supply would grant that origin's
        // tool surface and approval policy on the strength of a string this layer merely carried.
        val callOrigin = ToolCallOrigin.entries.singleOrNull { it.name == identity.callOrigin }
            ?: return ClaudePToolPreparation.refused(ClaudePToolPreparationRefusal.UNKNOWN_CALL_ORIGIN)

        // Assembled unconditionally, so that a tool the app cannot freeze is dropped here and not
        // discovered later at call time. The result is dropped rather than offered while the
        // surface is closed; it is never *widened* by the decision to open it.
        val build = ClaudePToolCatalogAssembly.build(tools)

        // An assistant with no offerable tools is a working configuration, not a refusal: it is
        // the text path, and its bytes are what they were before the bridge existed.
        if (build.catalog.isEmpty) return ClaudePToolPreparation.NONE

        // See the class doc. Closed here means "offer nothing", never "offer everything".
        if (!offerCatalog) return ClaudePToolPreparation.NONE

        val deviceRef = deviceRefProvider() ?: return ClaudePToolPreparation.NONE

        val plan = ClaudePToolExecutionPlan(
            deviceRef = deviceRef,
            runId = identity.runId,
            commandId = identity.commandId,
            conversationId = identity.conversationId,
            assistantId = identity.assistantId,
            branchId = identity.branchId,
            callOrigin = callOrigin,
            timeoutMs = DEFAULT_TOOL_DEADLINE_MS,
        )

        // A token of this host's own, so the plan can be readied before the generation has an id.
        // It is opaque to both modules: never parsed, never compared against a protocol value, and
        // never written to a frame, a prompt, a fingerprint or a log line.
        val executionRef = "claudep-plan:" + Uuid.random().toString()
        check(bindings.stage(executionRef, plan.witness, plan)) {
            "a host-owned execution token collided; the token is not peer input"
        }

        return ClaudePToolPreparation(
            deviceRef = plan.deviceRef,
            assistantId = plan.assistantId,
            conversationId = plan.conversationId,
            branchId = plan.branchId,
            timeoutMs = plan.timeoutMs,
            catalog = build.catalog,
            snapshot = build.snapshot,
            executionRef = executionRef,
        )
    }

    /**
     * Binds the staged plan to the generation id the Server just assigned.
     *
     * Keyed on the exact id and on nothing else. A lookup that searched by conversation, assistant
     * or tool call id would let one generation's call be answered with another's context, which is
     * the failure this binding exists to prevent — and there is deliberately no method here that
     * could express such a search.
     *
     * `false` means the plan could not be bound: an expired, evicted or already-redeemed token, or
     * a retry that would rebind this generation to a *different* plan. The provider fails the
     * generation rather than running it with tools nobody can answer.
     */
    override suspend fun openGeneration(
        generationId: String,
        preparation: ClaudePToolPreparation,
    ): Boolean = bindings.open(generationId, preparation.executionRef)

    /**
     * Releases a generation's binding, from the same `finally` that closes the tool registry.
     *
     * Not suspending on purpose: it runs on a path that may already be cancelled, and a suspension
     * point there is how a release gets skipped and a dead generation's context is kept alive. A
     * no-op for a generation that was never bound.
     */
    override fun closeGeneration(generationId: String) {
        bindings.close(generationId)
    }

    /**
     * Runs one admitted invocation through the app's existing runtime.
     *
     * **Not implemented in this stage, and unreachable while the surface is closed.** With
     * [offerCatalog] false the catalog is empty, so no `tool_snapshot` is sent, the Server never
     * registers a bridge tool, and no `tool.invoke` can arrive. The answer below is the honest one
     * for a call that reached a host with no executor behind it: `FAILED` claims no execution and
     * no stop, which is strictly better than claiming a result.
     */
    override suspend fun execute(
        invocation: BridgeInvocation,
        status: ClaudePToolStatusSink,
    ): BridgeToolExecution = BridgeToolExecution(
        outcome = ToolCallOutcome(
            toolCallId = invocation.binding.toolCallId,
            state = ToolCallState.FAILED,
        ),
        part = null,
    )

    /**
     * The execution host a closing generation uses to stop what is running and find out what
     * really happened.
     *
     * Still [BridgeExecutionHost.NONE] in this stage, for the same reason [execute] is: nothing
     * this host can offer is running, so there is nothing to stop and nothing to prove. It becomes
     * the real host — over `ClaudePToolRunControls` and the runtime's own `ToolExecutionHandle` —
     * in the same change that makes [execute] real.
     */
    override val executions: BridgeExecutionHost = BridgeExecutionHost.NONE

    private companion object {
        /**
         * How long one call may stay pending before Android stops waiting for it.
         *
         * The contract's own ceiling, and not a tuned number: the Server bounds a call's deadline
         * at `MAX_DEADLINE_MS` too, so a longer local value would only mean waiting past the point
         * the peer has already given up, and a shorter one would conclude calls the Server still
         * believes are live.
         */
        val DEFAULT_TOOL_DEADLINE_MS = BridgeLimits.MAX_DEADLINE_MS
    }
}

/**
 * Everything one generation needs in order to run a tool call it is asked about.
 *
 * App-owned and opaque to the bridge: it is stored under a token, redeemed by an exact generation
 * id, and never inspected, compared or logged by the module that carries it. Every field is read
 * from the authority that owns it — the run control, the durable command row, the resolved
 * assistant, the conversation and the resolved origin — so that a call is answered with *its own*
 * generation's context rather than a near match.
 *
 * @property callOrigin already mapped to the app's vocabulary by [ClaudePToolBridgeHostImpl.prepare].
 *   A token that does not match exactly never reaches here, which is what keeps an unrecognised
 *   origin from being substituted by a permissive one.
 */
data class ClaudePToolExecutionPlan(
    val deviceRef: String,
    val runId: String,
    val commandId: String,
    val conversationId: String,
    val assistantId: String,
    val branchId: String,
    val callOrigin: ToolCallOrigin,
    val timeoutMs: Long,
) {
    /**
     * What two plans for the same generation have to agree on.
     *
     * Used only as the equality witness when a generation is re-opened: the registry already
     * decided whether the retry is legitimate, and this exists so that a table which silently kept
     * whichever plan arrived first cannot be the reason a call runs under another generation's
     * context. It is never parsed and never travels.
     */
    val witness: String
        get() = listOf(
            deviceRef,
            runId,
            commandId,
            conversationId,
            assistantId,
            branchId,
            callOrigin.name,
        ).joinToString(" ")
}
