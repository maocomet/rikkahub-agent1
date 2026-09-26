package me.rerere.rikkahub.data.claudep

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import me.rerere.ai.provider.claudep.bridge.BridgeClaimRefusal
import me.rerere.ai.provider.claudep.bridge.BridgeExecutionApproval
import me.rerere.ai.provider.claudep.bridge.BridgeExecutionBindings
import me.rerere.ai.provider.claudep.bridge.BridgeExecutionClaim
import me.rerere.ai.provider.claudep.bridge.BridgeExecutionClaimResult
import me.rerere.ai.provider.claudep.bridge.BridgeExecutionClaimant
import me.rerere.ai.provider.claudep.bridge.BridgeExecutionHost
import me.rerere.ai.provider.claudep.bridge.BridgeInvocation
import me.rerere.ai.provider.claudep.bridge.BridgeLimits
import me.rerere.ai.provider.claudep.bridge.ToolCallOutcome
import me.rerere.ai.provider.claudep.bridge.ToolCallState
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.execution.ToolAssessmentRequest
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
import me.rerere.rikkahub.data.execution.InFlightApprovalDecision
import me.rerere.rikkahub.data.execution.InFlightApprovalIdentity
import me.rerere.rikkahub.data.execution.InFlightApprovalOutcome
import me.rerere.rikkahub.data.execution.InFlightApprovalWaiters
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
    /**
     * The waiters the app's approval lifecycle releases, and the *same* instance the production
     * graph gives `SecondUserApprovalLifecycle`.
     *
     * Two instances would mean a tap released into one map while the waiter sat on the other —
     * which presents as a tool that hangs until its deadline despite the user having approved it.
     * Defaulted to a fresh instance so a host constructed without the graph behaves exactly as it
     * did before the in-flight path existed: nothing waits, so nothing is released.
     */
    private val inFlightWaiters: InFlightApprovalWaiters = InFlightApprovalWaiters(),
    /**
     * The app's records of what is running, and what the runtime proved about it.
     *
     * `null` means this host has no execution layer wired. Every question the bridge asks about a
     * running call then answers "nothing can be proven", which is what makes a host without one
     * report `failed` rather than claiming a stop — and is why the approval path refuses instead
     * of publishing a card it could never conclude.
     */
    private val executionHost: ClaudePToolExecutionHost? = null,
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
        // The generation's records are dropped here, and *after* the unbind above rather than
        // before it: the unbind is what releases a host suspended on a receipt, and a record
        // dropped first would leave that host waking into a state that no longer describes the
        // call it was answering. The execution host's own entries are keyed by the Server's
        // generation id, so this is the exact id and not a prefix search over runs.
        executionHost?.forgetGeneration(generationId)
    }

    /**
     * Runs one admitted invocation through the app's existing runtime.
     *
     * ## The three paths, and what they share
     *
     * A local read, a local write behind the existing approval, and an MCP tool are not three
     * implementations here. They are one path with one branch in it: whether the app's own policy
     * says this call needs a human decision, asked of the tool's own `needsApproval` against the
     * **real** arguments exactly as every other provider asks it. Everything after that branch —
     * the re-assessment, the claim, the runtime call, the gate, the outcome — is identical, which
     * is what keeps an MCP tool from quietly acquiring a different policy from a local one.
     *
     * ## Why nothing runs without a claim
     *
     * A caller hands over an invocation, not a licence. Between that hand-over and this method
     * reaching the runtime the call can be cancelled, time out, settle, or lose its generation —
     * and none of that is visible in the object handed over. So immediately before the runtime is
     * asked to run anything, the canonical invocation is claimed from the ledger, exactly once,
     * and **that** object is what runs. If the claim is refused, nothing runs and this method
     * reports `failed`: a call that did not happen must not be described as one that did.
     *
     * The claimant is supplied by the caller and is bound to the generation whose frame this is.
     * There is no path here through which another generation's ledger could be reached.
     */
    override suspend fun execute(
        invocation: BridgeInvocation,
        status: ClaudePToolStatusSink,
        claims: BridgeExecutionClaimant,
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

        val runtime = toolRuntime ?: return unexecuted(invocation)

        // Re-assessed here, against the arguments that actually arrived, and not merely once when
        // the catalog was frozen. An assessment is a statement about *this* call — the policy
        // effects the arguments imply and the security descriptor behind the name — and the frozen
        // catalog could only ever have been a statement about the tool. A call the runtime will not
        // accept must not be published, claimed or run.
        val assessment = runtime.assess(
            ToolAssessmentRequest(
                toolName = invocation.toolNameForRuntime,
                args = arguments,
                context = executionContext,
            ),
        )
        if (!assessment.accepted) return unexecuted(invocation)

        // Whether this call needs a human decision is the existing policy's answer, read rather
        // than re-derived: a second opinion here would be a second approval policy.
        val approval = if (tool.needsApproval(invocation.arguments)) {
            when (val gate = awaitApproval(invocation, plan, status, claims)) {
                is ApprovalGate.Approved -> BridgeExecutionApproval.Granted(
                    approvalId = gate.approvalId,
                    executionId = gate.executionId,
                )
                // The user refused, and the refusal is a *conclusion about this call* — not a
                // failure to answer it. It stops here: nothing is claimed and nothing runs, which
                // is exactly what a denial means.
                ApprovalGate.Denied -> return denied(invocation)
                // No decision was observed, so nothing may run. The shape includes a cancel, a
                // close, a peer that went away, a deadline, and a card that never reached the
                // screen — each of which the ledger will describe more precisely if it ever asks.
                ApprovalGate.NoDecision -> return unexecuted(invocation)
            }
        } else {
            BridgeExecutionApproval.NotRequired
        }

        // The pairing, re-checked at the last moment. It is the same question `awaitApproval` asks
        // before it publishes, and it is asked again here because the answer can change while a
        // card is on screen: a generation that ended during the wait has retired this association,
        // and a call whose generation is gone must not be executed under a run that is no longer
        // serving it. Everything after this line is a call the pairing still covers.
        if (publications.serverGenerationIdFor(plan.runId) != invocation.binding.generationId) {
            return unexecuted(invocation)
        }

        // The execution right, and the call it is a right to. Taken here deliberately: after the
        // decision, after the pairing, and immediately before the runtime — so a call that changed
        // in the meantime is refused rather than executed under an approval it no longer matches.
        // The claimant re-checks `closing` under the ledger's own lock at this instant, so a
        // generation whose close has *begun* refuses here even if the pairing line above raced it.
        val canonical = when (
            val claim = claims.claim(claimRequest(invocation, plan, approval))
        ) {
            is BridgeExecutionClaimResult.Claimed -> claim.invocation
            is BridgeExecutionClaimResult.Refused -> return unexecuted(invocation)
        }

        // Published before the call so the conversation shows it as running while it is. A denial
        // of this publication is ignored on purpose: the user has already decided, and a status
        // nobody could render must not turn a granted call into a refused one.
        status.publish(
            ClaudePToolStatusUpdate(
                toolCallId = canonical.binding.toolCallId,
                toolName = canonical.toolNameForRuntime,
                arguments = canonical.arguments,
                status = ClaudePToolCallStatus.RUNNING,
            ),
        )

        val serverGenerationId = canonical.binding.generationId
        val toolCallId = canonical.binding.toolCallId

        val result = try {
            runtime.execute(
                ToolExecutionPlanRequest(
                    toolCallId = toolCallId,
                    toolName = canonical.toolNameForRuntime,
                    toolSchemaFingerprint = ToolCatalogSnapshot
                        .fromDefinitions(listOf(tool))
                        .entry(tool.name)
                        ?.schemaFingerprint,
                    // The canonical arguments, from the claimed invocation — never the frame's
                    // copy and never a redacted one. What the Server sent and the ledger validated
                    // is what the tool is given.
                    args = canonical.arguments,
                    executionContext = executionContext,
                    // The cancellable adapter when the app has one for this tool, and `null`
                    // otherwise — the same resolution the normal loop performs, so a tool that can
                    // really be stopped is stoppable here too and one that cannot says so honestly.
                    startableTool = toolStartableResolver.resolve(tool, executionContext),
                    // Always supplied. For an MCP tool this closure *is* the dispatch to
                    // `McpManager`, and for a local tool it is the app's own implementation —
                    // either way this host runs the tool it was handed rather than looking one up,
                    // so it never holds a server id, an OAuth state or a token.
                    legacyExecute = { element -> tool.execute(element) },
                    runControl = runControl,
                    wallClockBudgetMs = plan.wallClockBudgetMs,
                    preExecutionGate = {
                        gate.decide(canonical.toolNameForRuntime, arguments, executionContext)
                    },
                ),
            )
        } catch (cancelled: CancellationException) {
            // Two different cancellations arrive here and they must not be confused. If *this*
            // coroutine is being cancelled the generation is ending and the cancellation belongs
            // to it — rethrowing is the only correct move, and `ensureActive` is what tells the
            // two apart. Otherwise the runtime cancelled the tool's own handle, because a
            // `tool.cancel` or a run stop reached it.
            currentCoroutineContext().ensureActive()
            // Whether that stopped the work is the runtime's answer and not this method's. A stop
            // it can prove is `cancelled`; anything else is `failed`, which claims nothing about a
            // tool that may still be running and may still have a side effect.
            val proven = executionHost?.proveStopForCancelledCall(serverGenerationId, toolCallId)
            if (proven != null) executionHost.recordConclusion(serverGenerationId, toolCallId, proven)
            return BridgeToolExecution(
                outcome = proven ?: failedOutcome(toolCallId),
                part = resultPart(canonical, approval, output = emptyList()),
            )
        }

        val outcome = result.toOutcome(toolCallId)
        executionHost?.recordConclusion(serverGenerationId, toolCallId, outcome)
        return BridgeToolExecution(
            outcome = outcome,
            part = resultPart(canonical, approval, output = result.output),
        )
    }

    /**
     * Publishes the pending card and waits for the decision the user makes on it.
     *
     * ## The order, which is the whole of this method
     *
     * 1. Ask the ledger whether the call can still happen at all. Raising a card for a call that
     *    has already been cancelled is asking the user to decide something that cannot occur.
     * 2. Create the publication request and publish the pending status with the `IN_FLIGHT`
     *    continuation — the token that tells the conversation authority an approval of this card
     *    must release a waiter rather than create a resume command.
     * 3. Wait for the **receipt**. `Accepted` says the status was handed over, not that anything
     *    was shown or committed: the provider forwards through a channel that does not wait, so
     *    only the authority's own acknowledgement means the card is durable.
     * 4. Take the approval's identity from the receipt — produced by the single authoritative
     *    derivation site inside the transaction, never derived here — register it with the
     *    execution host, and wait.
     * 5. Run only on an approval. A denial, an abandonment and a refused publication all mean the
     *    same thing to the caller: nothing may run.
     */
    private suspend fun awaitApproval(
        invocation: BridgeInvocation,
        plan: ClaudePToolExecutionPlan,
        status: ClaudePToolStatusSink,
        claims: BridgeExecutionClaimant,
    ): ApprovalGate {
        val host = executionHost ?: return ApprovalGate.NoDecision
        val serverGenerationId = invocation.binding.generationId

        // Both identities, kept apart. `invocation.binding.generationId` is the **Server's**
        // generation — the one the frame arrived under — and `plan.runId` is the Android run
        // serving it. Binding the two together is what `openGeneration` recorded; this asserts
        // that the record still says what this call assumes, so a call whose generation and run
        // disagree is refused here rather than executed under another generation's plan.
        //
        // The plan was found by `bindings.lookup(invocation.binding.generationId)` — an exact
        // lookup on the Server's id, with no search by conversation, assistant or recency — so
        // this is a confirmation of that lookup rather than a second, weaker one.
        if (publications.serverGenerationIdFor(plan.runId) != serverGenerationId) {
            return ApprovalGate.NoDecision
        }

        // The invocation's own digest must agree with the binding the ledger admitted it under.
        // The adapter derives both from the same validated arguments, so a disagreement is not a
        // protocol event — it is a caller that assembled an invocation by hand, or one whose
        // arguments were swapped after admission while the binding was carried over. Either way
        // the digest recorded here would describe a call the ledger never admitted, and an
        // approval earned by the real call could be spent on a different one.
        if (invocation.argsDigest != invocation.binding.argsDigest) return ApprovalGate.NoDecision

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

        // Asked before anything is shown. The approval proof is deliberately absent here: no
        // decision exists yet, and this question is about the call rather than about a decision.
        if (claims.admissible(claimRequest(invocation, plan, BridgeExecutionApproval.NotRequired)) != null) {
            return ApprovalGate.NoDecision
        }

        // `null` means this invocation cannot be published: its run and generation were never
        // bound together, or this exact key is already live or settled-but-unreleased. Each is a
        // refusal rather than a replacement — a second entry under one key would give two waiters
        // one answer.
        val request = publications.begin(publicationInvocation) ?: return ApprovalGate.NoDecision

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
            if (published !is ClaudePToolStatusPublication.Accepted) return ApprovalGate.NoDecision

            return when (val receipt = request.await(plan.timeoutMs)) {
                is ClaudePToolPublicationOutcome.Committed -> {
                    // The acknowledged record and the invocation about to be waited on must still
                    // be the same call. The authority derived the ids from the card it committed,
                    // so a disagreement here means the completion described a different call.
                    if (!request.invocation.sameCallAs(publicationInvocation)) {
                        return ApprovalGate.NoDecision
                    }
                    awaitDecision(host, plan, publicationInvocation, receipt, status)
                }

                // Applied but not committed, or never answered, or answered with a wrong identity.
                // All three mean the same thing to the caller: no decision may be waited on.
                is ClaudePToolPublicationOutcome.Refused,
                is ClaudePToolPublicationOutcome.Abandoned,
                -> ApprovalGate.NoDecision
            }
        } finally {
            // Whatever happened — including a cancellation of this coroutine — the key stops being
            // remembered here. A live wait is ended by the release, and a settled one is forgotten
            // so the map is not the thing that grows.
            publications.release(publicationInvocation.key)
        }
    }

    /**
     * Registers the waiter for a committed approval, waits, and reads the decision.
     *
     * The identity is the authority's four fields and is never derived here: a second derivation
     * is a second answer to "which approval is this?", and the one that reaches the waiter has to
     * be the one the tap will match.
     */
    private suspend fun awaitDecision(
        host: ClaudePToolExecutionHost,
        plan: ClaudePToolExecutionPlan,
        publicationInvocation: ClaudePToolPublicationInvocation,
        receipt: ClaudePToolPublicationOutcome.Committed,
        status: ClaudePToolStatusSink,
    ): ApprovalGate {
        val serverGenerationId = publicationInvocation.serverGenerationId
        val toolCallId = publicationInvocation.toolCallId
        val identity = InFlightApprovalIdentity(
            approvalId = receipt.approvalId,
            executionId = receipt.executionId,
            conversationId = plan.conversationId,
            toolCallId = toolCallId,
        )

        // Registration is where the close can still beat this call. A generation that began
        // ending between the commit and this line has already abandoned the slot, and the answer
        // is `false` — the caller must not wait, because nothing is coming.
        if (!host.registerApproval(serverGenerationId, toolCallId, identity)) {
            return ApprovalGate.NoDecision
        }

        try {
            return when (val decision = inFlightWaiters.await(identity, plan.timeoutMs)) {
                is InFlightApprovalOutcome.Decided -> when (decision.decision) {
                    InFlightApprovalDecision.APPROVED -> {
                        // Shown as approved while it runs, so the card does not claim a decision is
                        // still pending for a call that is already on its way. A refusal of this
                        // publication is ignored: the decision was already made, and failing to
                        // render it must not turn a granted call into a refused one.
                        status.publish(
                            ClaudePToolStatusUpdate(
                                toolCallId = toolCallId,
                                toolName = publicationInvocation.toolName,
                                arguments = kotlinx.serialization.json.JsonNull,
                                status = ClaudePToolCallStatus.APPROVED,
                            ),
                        )
                        ApprovalGate.Approved(receipt.approvalId, receipt.executionId)
                    }
                    InFlightApprovalDecision.DENIED -> ApprovalGate.Denied
                }

                // A timeout, a close, a cancel, a disconnect or a registry close. All of them are
                // "no decision was observed", and none of them may be read as consent.
                is InFlightApprovalOutcome.Abandoned -> ApprovalGate.NoDecision
            }
        } finally {
            host.forgetApproval(serverGenerationId, toolCallId)
            // Drops an undelivered decision or abandonment for this identity. It deliberately does
            // not clear the waiters' settled record: forgetting that a decision happened is
            // exactly the state in which a duplicate tap becomes a second execution.
            inFlightWaiters.forget(identity)
        }
    }

    /**
     * The claim this host will ask for, built from the invocation it was handed and the plan it
     * resolved. One construction site, so the request `admissible` is asked and the request
     * `claim` is asked cannot describe different calls.
     */
    private fun claimRequest(
        invocation: BridgeInvocation,
        plan: ClaudePToolExecutionPlan,
        approval: BridgeExecutionApproval,
    ) = BridgeExecutionClaim(
        serverGenerationId = invocation.binding.generationId,
        runId = plan.runId,
        toolCallId = invocation.binding.toolCallId,
        toolName = invocation.toolNameForRuntime,
        argsDigest = invocation.argsDigest,
        binding = invocation.binding,
        approval = approval,
    )

    /** The result part for a call that reached a terminal, shaped by what actually happened. */
    private fun resultPart(
        canonical: BridgeInvocation,
        approval: BridgeExecutionApproval,
        output: List<UIMessagePart>,
    ) = UIMessagePart.Tool(
        toolCallId = canonical.binding.toolCallId,
        toolName = canonical.toolNameForRuntime,
        input = canonical.arguments.toString(),
        output = output,
        // `Approved` only where a human really approved it. A call that needed no decision says
        // `Auto`, which is what the ordinary agent loop writes for the same case.
        approvalState = when (approval) {
            BridgeExecutionApproval.NotRequired -> ToolApprovalState.Auto
            is BridgeExecutionApproval.Granted -> ToolApprovalState.Approved
        },
    )

    private fun failedOutcome(toolCallId: String) =
        ToolCallOutcome(toolCallId, ToolCallState.FAILED)

    /** What waiting for the user produced. A closed set, so an unhandled outcome cannot compile. */
    private sealed interface ApprovalGate {
        /** A human approved this exact call, with the authority's own identity for it. */
        data class Approved(val approvalId: String, val executionId: String) : ApprovalGate

        /** A human refused it. Nothing runs, and the call is reported `denied`. */
        data object Denied : ApprovalGate

        /** No decision was observed. Nothing runs, and the call is reported `failed`. */
        data object NoDecision : ApprovalGate
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
     * The app's records, for a closing generation to stop what is running and find out what really
     * happened.
     *
     * [BridgeExecutionHost.NONE] when no execution host is wired, which proves nothing and stops
     * nothing — so every call a close asks about concludes `failed`. That is the conservative
     * answer and the correct one for a host with no executor behind it, and it is what keeps a
     * wiring omission from being reported as a proven stop.
     */
    override val executions: BridgeExecutionHost = executionHost ?: BridgeExecutionHost.NONE

    /**
     * The answer for a call the user refused.
     *
     * `DENIED` is a conclusion about the call rather than a failure to answer it, and it is
     * returned with the part that carries the refusal, so the conversation shows the decision the
     * user actually made instead of a card left pending. Nothing was claimed and nothing ran.
     */
    private fun denied(invocation: BridgeInvocation): BridgeToolExecution = BridgeToolExecution(
        outcome = ToolCallOutcome(
            toolCallId = invocation.binding.toolCallId,
            state = ToolCallState.DENIED,
        ),
        part = UIMessagePart.Tool(
            toolCallId = invocation.binding.toolCallId,
            toolName = invocation.toolNameForRuntime,
            input = invocation.arguments.toString(),
            output = emptyList(),
            // No reason text: the denial is the user's, and this side was not told why. Inventing
            // one would put words in the mouth of the person who tapped "deny".
            approvalState = ToolApprovalState.Denied(),
        ),
    )

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
