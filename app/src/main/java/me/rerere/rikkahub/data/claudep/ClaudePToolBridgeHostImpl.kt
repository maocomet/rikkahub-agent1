package me.rerere.rikkahub.data.claudep

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.claudep.BridgeToolExecution
import me.rerere.ai.provider.claudep.ClaudePToolBridgeHost
import me.rerere.ai.provider.claudep.ClaudePToolCallStatus
import me.rerere.ai.provider.claudep.ClaudePToolGenerationContext
import me.rerere.ai.provider.claudep.ClaudePToolPreparation
import me.rerere.ai.provider.claudep.ClaudePToolPreparationRefusal
import me.rerere.ai.provider.claudep.ClaudePToolStatusPublication
import me.rerere.ai.provider.claudep.ClaudePToolStatusSink
import me.rerere.ai.provider.claudep.ClaudePToolStatusUpdate
import me.rerere.ai.provider.claudep.bridge.BridgeExecutionBindings
import me.rerere.ai.provider.claudep.bridge.BridgeExecutionHost
import me.rerere.ai.provider.claudep.bridge.BridgeInvocation
import me.rerere.ai.provider.claudep.bridge.BridgeLimits
import me.rerere.ai.provider.claudep.bridge.ToolCallOutcome
import me.rerere.ai.provider.claudep.bridge.ToolCallState
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPlanRequest
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPlanResult
import me.rerere.rikkahub.data.ai.execution.ToolPreExecutionDecision
import me.rerere.rikkahub.data.ai.execution.ToolRuntime
import me.rerere.rikkahub.data.ai.execution.ToolStartableResolver
import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.capability.CapabilitySubject
import me.rerere.rikkahub.data.capability.SubjectType
import me.rerere.rikkahub.data.execution.ApprovalContinuationMode
import me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot
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
    /**
     * The app's runtime. `null` means this host has none wired, and it then runs nothing — the
     * runtime is asked to execute a tool, so a host without one must refuse rather than fall back
     * to calling the tool's own `execute` directly and skipping the gate, the policy and the
     * ledger that `DefaultToolRuntime` exists to apply.
     */
    private val toolRuntime: ToolRuntime? = null,
    /**
     * The live run controls, so a call can be registered against the run actually executing it.
     *
     * Found by the exact run id; an empty registry simply finds nothing, which fails the call
     * closed. Defaulting to a fresh instance is safe for that reason and keeps a host that is not
     * wired from silently sharing state with one that is.
     */
    private val runControls: ClaudePToolRunControls = ClaudePToolRunControls(),
    /** The app's cancellable-tool adapter. `NONE` means every tool is non-cancellable, honestly. */
    private val toolStartableResolver: ToolStartableResolver = ToolStartableResolver.NONE,
    /**
     * The app's pre-execution gate. Defaults to **deny**, not allow: a host wired without a gate
     * must not run anything, and a permissive default is how a wiring omission becomes a silent
     * policy bypass.
     */
    private val gate: ClaudePToolGate = ClaudePToolGate.DENY,
    /**
     * The one acknowledgement registry, shared with the service that commits the barrier.
     *
     * ## Why it must be the same instance
     *
     * A publication request is created here and completed there. Two instances would mean a host
     * waiting on a registry nothing ever answers — which is not a loud failure, it is a call that
     * sits until its deadline and then runs nothing. Required rather than defaulted for exactly
     * that reason: a defaulted fresh instance is the silent version of the same bug, and the
     * wiring site is where a reader can see it is not one.
     *
     * It holds no runtime, no MCP manager, no gate and no conversation. Injected, it grants this
     * host nothing but the ability to ask whether a card it published is durable.
     */
    private val publications: ClaudePToolPublicationReceipts,
    /**
     * The real capability subject for a generation, or `null` when it cannot be resolved.
     *
     * `null` fails the call closed. It must never be replaced by an empty or guessed subject — see
     * the note in [execute] about what an absent subject does to the gate.
     */
    private val subjectFor: suspend (
        assistantId: String,
        conversationId: String,
        origin: ToolCallOrigin,
    ) -> CapabilitySubject? = { _, _, _ -> null },
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
            tools = tools,
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
    ): Boolean {
        // The text path and every refusal carry no execution plan, so there is nothing to pair and
        // nothing this host could serve. That is a success, exactly as it is for the binding.
        val executionRef = preparation.executionRef
            ?: return bindings.open(generationId, null)

        if (!bindings.open(generationId, executionRef)) return false

        // The Server's generation id only exists here — it came back in the answer to
        // `generation.start` — and this is the one instant at which it can be paired with the run
        // that serves it. Every later question about the pairing is answered from this record,
        // never re-derived: a conversation id, an assistant id or "the run that is executing now"
        // can each *find* a run, and none of them can *prove* which one a call belongs to.
        //
        // A refused pairing fails the generation rather than running it. `bindGeneration` refuses
        // only a contradiction of something already recorded, so reaching it means two different
        // generations have claimed one run — a state in which no call can be answered correctly.
        val runId = bindings.lookup(generationId)?.runId ?: return false
        if (!publications.bindGeneration(serverGenerationId = generationId, runId = runId)) {
            bindings.close(generationId)
            return false
        }
        return true
    }

    /**
     * Releases a generation's binding, from the same `finally` that closes the tool registry.
     *
     * Not suspending on purpose: it runs on a path that may already be cancelled, and a suspension
     * point there is how a release gets skipped and a dead generation's context is kept alive. A
     * no-op for a generation that was never bound.
     *
     * The publication association is dropped here too, and for the same reason the binding is: a
     * terminal, a cancellation, a disconnect and a provider failure are all "this generation is
     * over", and an association that outlived one would let a later call pair that run with a
     * generation nothing is serving any more. Its outstanding publications are ended with it, so a
     * host suspended on one fails closed rather than waiting out a deadline for a generation that
     * no longer exists.
     */
    override fun closeGeneration(generationId: String) {
        publications.unbindGeneration(
            serverGenerationId = generationId,
            reason = ClaudePToolPublicationAbandonReason.GENERATION_CLOSED,
        )
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
    ): BridgeToolExecution {
        // Every refusal below is the same answer, and it is `FAILED` rather than a guess: the call
        // was not run, and this host cannot say it stopped anything either. A `cancelled` would
        // claim a stop nobody observed and a `denied` would claim a decision the user never made.
        val plan = bindings.lookup(invocation.binding.generationId) ?: return unexecuted(invocation)

        // The tool must be one *this generation* froze. A name the catalog did not carry cannot be
        // run by asking for it, which is what makes the catalog a boundary rather than a suggestion.
        val tool = plan.tools.firstOrNull { it.name == invocation.toolNameForRuntime }
            ?: return unexecuted(invocation)

        val arguments = invocation.arguments as? JsonObject ?: return unexecuted(invocation)

        // The control for the run that is *actually* executing, found by its exact id. The runtime
        // registers its `ToolExecutionHandle` against it, which is what makes this call reachable
        // by the closing generation's stop and reachable by a later cancel.
        val runControl = runControls.find(plan.runId) ?: return unexecuted(invocation)

        val runId = plan.runId.toUuidOrNull() ?: return unexecuted(invocation)
        val conversationId = plan.conversationId.toUuidOrNull() ?: return unexecuted(invocation)

        // The real subject, never a null stand-in. `ToolExecutionGate` skips its entire capability
        // branch when the subject is absent, so a null here would *widen* what a second-user
        // conversation is allowed to do rather than narrow it.
        val subject = subjectFor(plan.assistantId, plan.conversationId, plan.callOrigin)
            ?: return unexecuted(invocation)

        val executionContext = ToolExecutionContext(
            runId = runId,
            conversationId = conversationId,
            assistantId = plan.assistantId,
            callOrigin = plan.callOrigin,
            commandId = plan.commandId.toUuidOrNull(),
            toolCallId = invocation.binding.toolCallId,
            capabilitySubject = subject,
            selectedPrivilegedConversation = subject.type == SubjectType.LOCAL_SECOND_USER,
        )

        // Whether this call needs a human decision is the existing policy's answer — the tool's own
        // `needsApproval` against the real arguments, exactly as every other provider asks it. It
        // is read, not re-derived: a second opinion here would be a second approval policy.
        if (tool.needsApproval(invocation.arguments)) {
            return publishPendingApproval(invocation, plan, status)
        }

        // Nothing needed a decision, so the runtime may run it now. The status is published before
        // the call so the conversation shows it as running while it is, and the result comes back
        // through `BridgeToolExecution` below.
        status.publish(
            ClaudePToolStatusUpdate(
                toolCallId = invocation.binding.toolCallId,
                toolName = invocation.toolNameForRuntime,
                arguments = invocation.arguments,
                status = ClaudePToolCallStatus.RUNNING,
            ),
        )

        val runtime = toolRuntime ?: return unexecuted(invocation)
        val result = runtime.execute(
            ToolExecutionPlanRequest(
                toolCallId = invocation.binding.toolCallId,
                toolName = invocation.toolNameForRuntime,
                toolSchemaFingerprint = ToolCatalogSnapshot
                    .fromDefinitions(listOf(tool))
                    .entry(tool.name)
                    ?.schemaFingerprint,
                args = invocation.arguments,
                executionContext = executionContext,
                // The cancellable adapter when the app has one for this tool, and `null` otherwise —
                // the same resolution the normal loop performs, so a tool that can really be stopped
                // is stoppable here too and one that cannot says so honestly.
                startableTool = toolStartableResolver.resolve(tool, executionContext),
                // Always supplied. For an MCP tool this closure *is* the dispatch to `McpManager`,
                // and for a local tool it is the app's own implementation — either way this host
                // runs the tool it was handed rather than looking one up.
                legacyExecute = { element -> tool.execute(element) },
                runControl = runControl,
                wallClockBudgetMs = plan.wallClockBudgetMs,
                preExecutionGate = {
                    gate.decide(invocation.toolNameForRuntime, arguments, executionContext)
                },
            ),
        )

        return BridgeToolExecution(
            outcome = result.toOutcome(invocation.binding.toolCallId),
            part = UIMessagePart.Tool(
                toolCallId = invocation.binding.toolCallId,
                toolName = invocation.toolNameForRuntime,
                input = invocation.arguments.toString(),
                output = result.output,
                approvalState = ToolApprovalState.Auto,
            ),
        )
    }

    /**
     * Publishes a pending card and waits for the commit behind it to be acknowledged.
     *
     * ## What this does, and what it deliberately stops short of
     *
     * It performs the publication half of the in-flight approval handshake: create the request,
     * publish the pending status, wait for the receipt, release. The receipt is the *only* evidence
     * that the card is applied and its barrier durably committed — the provider hands the chunk to
     * a stream that does not wait for it (`ProviderTurnRunner` forwards through an unbounded
     * channel), so a `publish` that returned says nothing at all, and neither does a `Refused`
     * that did not.
     *
     * On success the returned receipt carries the approval's own `approvalId` and `executionId`,
     * derived by the single authoritative site inside the authority's transaction. This host never
     * derives them itself, and must not start: a second derivation is a second answer to "which
     * approval is this?", and the one that reaches `InFlightApprovalWaiters` has to be the one the
     * tap will match.
     *
     * ## Why nothing runs, even on a successful receipt
     *
     * Arming the waiter and running the tool are the next batch's, and this method ends where they
     * begin rather than pretending otherwise. The answer is therefore [unexecuted]: this host
     * published a card and did not run the call, which is precisely what `FAILED` claims.
     *
     * It is unreachable in production while [offerCatalog] is false — no snapshot means no bridge
     * tool, which means no invoke.
     */
    private suspend fun publishPendingApproval(
        invocation: BridgeInvocation,
        plan: ClaudePToolExecutionPlan,
        status: ClaudePToolStatusSink,
    ): BridgeToolExecution {
        // Both identities, kept apart. `invocation.binding.generationId` is the **Server's**
        // generation — the one the frame arrived under — and `plan.runId` is the Android run
        // serving it. Binding the two together is what `openGeneration` recorded; this asserts
        // that the record still says what this call assumes, so a call whose generation and run
        // disagree is refused here rather than executed under another generation's plan.
        //
        // The plan was found by `bindings.lookup(invocation.binding.generationId)` — an exact
        // lookup on the Server's id, with no search by conversation, assistant or recency — so
        // this is a confirmation of that lookup rather than a second, weaker one.
        val serverGenerationId = invocation.binding.generationId
        if (publications.serverGenerationIdFor(plan.runId) != serverGenerationId) {
            return unexecuted(invocation)
        }

        // The invocation's own digest must agree with the binding the ledger admitted it under.
        // The adapter derives both from the same validated arguments, so a disagreement is not a
        // protocol event — it is a caller that assembled an invocation by hand, or one whose
        // arguments were swapped after admission while the binding was carried over. Either way
        // the digest recorded here would describe a call the ledger never admitted, and an
        // approval earned by the real call could be spent on a different one.
        if (invocation.argsDigest != invocation.binding.argsDigest) return unexecuted(invocation)

        val publicationInvocation = ClaudePToolPublicationInvocation(
            serverGenerationId = serverGenerationId,
            runId = plan.runId,
            toolCallId = invocation.binding.toolCallId,
            toolName = invocation.toolNameForRuntime,
            // The contract's digest of the arguments the Server actually sent, taken from the
            // admitted invocation. Never recomputed from the conversation part: that copy has been
            // through `RuntimeSecretRedactor`, so a digest taken from it would disagree with the
            // ledger for exactly the calls that carry a secret — the ones most likely to need a
            // decision. The values themselves stay on this side; only their identity travels.
            argsDigest = invocation.argsDigest,
        )

        // `null` means this invocation cannot be published: its run and generation were never
        // bound together, or this exact key is already live or settled-but-unreleased. Each is a
        // refusal rather than a replacement — a second entry under one key would give two waiters
        // one answer.
        val request = publications.begin(publicationInvocation) ?: return unexecuted(invocation)

        try {
            val published = status.publish(
                ClaudePToolStatusUpdate(
                    toolCallId = invocation.binding.toolCallId,
                    toolName = invocation.toolNameForRuntime,
                    arguments = invocation.arguments,
                    status = ClaudePToolCallStatus.PENDING_APPROVAL,
                    // Declared here because this is the side that knows it: the peer is blocked
                    // inside the Worker on this very call, so approving it must release a waiter
                    // and must never create a resume command that starts a second generation.
                    pendingContinuation = ApprovalContinuationMode.IN_FLIGHT.name,
                ),
            )
            // A refusal means the card is not on screen, so nothing can be tapped, so nothing may
            // wait. Fail closed without waiting out the deadline.
            if (published !is ClaudePToolStatusPublication.Accepted) return unexecuted(invocation)

            return when (val receipt = request.await(plan.timeoutMs)) {
                // Durable. The next batch arms the waiter with these identities and runs the call;
                // this one reports the call unrun, because that is what happened.
                is ClaudePToolPublicationOutcome.Committed -> {
                    // The acknowledged record and the invocation about to be run must still be the
                    // same call. Nothing is executed in this batch, so this cannot yet cost anyone
                    // a tool run — which is exactly why it is asserted now, while it is still
                    // checkable rather than load-bearing: the batch that does run the call will
                    // find the guard already in place rather than a comment promising one.
                    if (!request.invocation.sameCallAs(publicationInvocation)) {
                        return unexecuted(invocation)
                    }
                    unexecuted(invocation)
                }

                // Applied but not committed, or never answered, or answered with a wrong identity.
                // All three mean the same thing to the caller: no decision may be waited on.
                is ClaudePToolPublicationOutcome.Refused,
                is ClaudePToolPublicationOutcome.Abandoned,
                -> unexecuted(invocation)
            }
        } finally {
            // Whatever happened — including a cancellation of this coroutine — the key stops being
            // remembered here. A live wait is ended by the release, and a settled one is forgotten
            // so the map is not the thing that grows.
            publications.release(publicationInvocation.key)
        }
    }

    /**
     * The answer for a call this host did not run.
     *
     * `FAILED` and no part: it claims no execution, no stop and no decision, and it shows the user
     * nothing about a call that never had a card. The Server concludes the call from it rather than
     * waiting for a deadline, which is the point of answering at all.
     */
    private fun unexecuted(invocation: BridgeInvocation): BridgeToolExecution = BridgeToolExecution(
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
    /**
     * The exact tools this generation froze.
     *
     * The same objects the app assembled for every other provider — an MCP tool among them carries
     * its own `execute` closure, which is what dispatches back to `McpManager`. Holding them here
     * is why the host needs no MCP reference of its own: it runs the tool it was handed rather than
     * looking one up, so a token or an OAuth state has no path into this layer at all.
     */
    val tools: List<Tool> = emptyList(),
    /**
     * How much wall clock one call may take, from the app's own turn budget.
     *
     * A Claude P generation has no turn to measure — the provider's stream *is* the turn — so this
     * is the app's global budget rather than a per-turn remainder, and the runtime enforces it the
     * same way it does everywhere else.
     */
    val wallClockBudgetMs: Long = ToolRuntimeLimits.turnBudgetMs,
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

/**
 * The app's pre-execution gate, as the one question this host may ask it.
 *
 * A seam rather than the `ToolExecutionGate` itself, because the gate needs an Android `Context`
 * and this host must be constructible — and testable — without one. The production binding wires
 * it to `ToolExecutionGate.evaluate`, and that call site is where the `GateResult` to
 * `ToolPreExecutionDecision` mapping lives, unchanged from the normal agent loop.
 *
 * [DENY] is the default on purpose. A host wired without a gate must run nothing, and a permissive
 * default is how a forgotten binding becomes a silent policy bypass.
 */
fun interface ClaudePToolGate {
    suspend fun decide(
        toolName: String,
        args: JsonObject,
        context: ToolExecutionContext,
    ): ToolPreExecutionDecision

    companion object {
        /** Denies everything, claiming nothing ran. The default for an unwired host. */
        val DENY: ClaudePToolGate = ClaudePToolGate { _, _, _ ->
            ToolPreExecutionDecision.Deny(
                errorCode = "claudep_gate_absent",
                reason = "No pre-execution gate is wired into this host.",
            )
        }
    }
}

/**
 * What Android concluded, in the contract's vocabulary.
 *
 * `Rejected` maps to `DENIED` because that is precisely what it is: the gate refused before
 * anything ran. `completed` carries a body and the other two do not — a body on a non-completion
 * would be a result claim the state contradicts.
 */
private fun ToolExecutionPlanResult.toOutcome(toolCallId: String): ToolCallOutcome = when (this) {
    is ToolExecutionPlanResult.Completed -> ToolCallOutcome(
        toolCallId = toolCallId,
        state = ToolCallState.COMPLETED,
        body = output.asToolResultBody(),
    )

    is ToolExecutionPlanResult.Rejected -> ToolCallOutcome(toolCallId, ToolCallState.DENIED)
    is ToolExecutionPlanResult.TimedOut -> ToolCallOutcome(toolCallId, ToolCallState.TIMED_OUT)
}

/**
 * A result's text, bounded to what the contract will carry.
 *
 * `null` when there is no text at all: an absent body and an empty one are the same thing to the
 * peer, and the contract says a body is present only for a completion that has one. The byte bound
 * is the contract's own `MAX_TOOL_RESULT_BYTES`, and it is applied on a character boundary so a
 * multi-byte character is never cut in half into invalid UTF-8.
 */
private fun List<UIMessagePart>.asToolResultBody(): String? {
    val text = filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    if (text.isEmpty()) return null
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= BridgeLimits.MAX_TOOL_RESULT_BYTES) return text
    var end = BridgeLimits.MAX_TOOL_RESULT_BYTES
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
    return String(bytes, 0, end, Charsets.UTF_8)
}

/**
 * The app's deliberately un-typed identities, read back as their real type.
 *
 * The generation context carries strings so that this module never has to follow the app's identity
 * types. The app is therefore the side that maps them back, and a value that is not a UUID is not a
 * generation — it fails closed rather than being coerced.
 */
private fun String.toUuidOrNull(): Uuid? = try {
    Uuid.parse(this)
} catch (_: IllegalArgumentException) {
    null
}
