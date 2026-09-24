package me.rerere.ai.provider.claudep.bridge

import kotlinx.serialization.json.JsonElement

/**
 * One admitted invoke, in the shape the Android runtime needs to run it.
 *
 * [toolNameForRuntime] is the **raw** name the runtime knows — the same string the app's own
 * tool assembly produced — while [frozenName] is the identity that travelled. The two are
 * carried separately rather than collapsed, because the runtime is asked to run a tool it
 * already has, not a tool invented by this contract.
 */
data class BridgeInvocation(
    val binding: InvocationBinding,
    val frozenName: String,
    val toolNameForRuntime: String,
    val arguments: JsonElement,
    val canonicalArguments: String,
    val argsDigest: String,
    val readOnly: Boolean,
    val source: ToolSource,
)

/** What the adapter decided to do about one inbound `tool.invoke`. A closed set. */
sealed interface BridgeInvokeDecision {
    /** Execute exactly once, then report through [BridgeToolAdapter.complete]. */
    data class Execute(val invocation: BridgeInvocation) : BridgeInvokeDecision

    /** A repeat of a call still in flight. Nothing runs and nothing is sent: wait. */
    data object Await : BridgeInvokeDecision

    /** A repeat of a settled call. The recorded outcome stands; nothing runs. */
    data class Replay(val outcome: ToolCallOutcome) : BridgeInvokeDecision

    /**
     * Refused, and nothing ran.
     *
     * This is where every fail-closed path lands: an unknown tool, a tool not in this
     * generation's frozen catalog, arguments the contract will not take, or the same call id
     * carrying different content.
     */
    data class Refused(val reason: BridgeRejection) : BridgeInvokeDecision

    /**
     * Refused because this generation is ending.
     *
     * Deliberately **not** a [Refused]: [BridgeRejection] is the contract's closed vocabulary,
     * which the Server also holds, and there is no value in it for "the generation you are
     * asking about is closing". The Server never sends an invoke to a generation it has ended,
     * so this case is a local lifecycle fact rather than a protocol verdict — and giving it its
     * own variant is what makes an inbound handler that forgets it fail to compile.
     */
    data object GenerationClosing : BridgeInvokeDecision
}

/** What the adapter decided about one inbound `tool.cancel`. */
sealed interface BridgeCancelDecision {
    /** Propagate to the runtime: nothing has settled for this call yet. */
    data object Propagate : BridgeCancelDecision

    /**
     * Nothing to do, and that is the correct answer.
     *
     * Covers a call that is unknown here, one that already reached a terminal (whose recorded
     * outcome stands), and one whose cancel has already been propagated. All three are
     * legitimate repeats, which is what makes cancel idempotent.
     */
    data object NoOp : BridgeCancelDecision
}

/** What reporting an outcome did. */
sealed interface BridgeCompletion {
    /** A real transition: this is the answer to send. */
    data class Settled(val outcome: ToolCallOutcome) : BridgeCompletion

    /**
     * The call already reached the same terminal, byte for byte. Nothing changed, and the
     * recorded answer is not re-sent.
     */
    data class Repeat(val outcome: ToolCallOutcome) : BridgeCompletion

    /**
     * A *different* terminal than the one recorded. The first answer stands and this one is
     * dropped — the peer was already told something else, and replacing it would make this
     * side's record disagree with the reply that already left.
     */
    data class Superseded(val outcome: ToolCallOutcome) : BridgeCompletion
}

/**
 * The adapter between Claude P tool frames and the Android tool runtime, for **one generation**.
 *
 * ## One instance, one generation, and why that is the whole identity story
 *
 * This object is constructed with the identity of the generation it serves and refuses
 * anything that does not match it. That is a structural guarantee rather than a lookup rule:
 * because the ledger is this object's own and dies with it, there is no way to *express* "find
 * the first call matching this id across generations" — the search that the M2 gate forbids
 * cannot be written, so it cannot be written by accident later on a maintenance pass.
 *
 * A frame naming a different generation is refused rather than ignored. Ignoring it would be
 * safe here and *wrong* at the caller, which would have no way to tell a mismatched frame from
 * one this adapter simply had nothing to say about.
 *
 * ## What this does not do
 *
 * It does not decide whether a tool may run, whether a write is approved, or what a tool does.
 * Those are the existing `ToolExecutionGate`, the existing approval UI and the existing
 * runtime's. This object's entire job is to answer *which* call this is, whether it has been
 * seen before, and what Android concluded about it — and then to get out of the way.
 *
 * It never starts a model request, never re-dispatches after a disconnect, and never retries.
 */
class BridgeToolAdapter(
    private val binding: GenerationBinding,
    val catalog: FrozenCatalog,
    /**
     * The app's real execution layer, as a question this side may ask it.
     *
     * Defaults to the host that stops nothing and proves nothing, which is the correct answer
     * for a bridge with no executor behind it: every call it is asked to conclude reports
     * `failed` rather than claiming a stop it never observed.
     */
    private val executions: BridgeExecutionHost = BridgeExecutionHost.NONE,
    private val ledger: BridgeLedger = BridgeLedger(),
) {

    /** Call ids a cancel has already been propagated for. Sent at most once each. */
    private val cancelPropagated = mutableSetOf<String>()

    /**
     * True once this generation has begun to end.
     *
     * Volatile because the two halves run on different paths: a close is driven by whoever owns
     * the generation's lifetime, and an invoke arrives on the inbound frame path. A frame that
     * slips in between the two must see the flag, and it must be the flag rather than the
     * adapter's absence — during closing the adapter is still in the registry and still the
     * answer to a lookup by its exact id.
     */
    @Volatile
    private var closing = false

    /** True when this generation has begun to end, whether or not it has been released yet. */
    val isClosing: Boolean get() = closing

    /** True when this adapter serves the generation the frame names. */
    fun serves(generationId: String?): Boolean = generationId == binding.generationId

    /** The identity this adapter is bound to. */
    val generationId: String get() = binding.generationId

    val catalogDigest: String get() = catalog.digest

    /** Calls still awaiting a terminal, whether or not they are executing right now. */
    val pendingCount: Int get() = ledger.pendingCount

    /**
     * Handles one `tool.invoke`.
     *
     * The order of the checks is the security-relevant part, so it is fixed:
     *
     * 0. **The generation must not be ending.** This is checked before anything else and before
     *    any record is consulted, because the ledger is about to be settled by a close and a
     *    call admitted into that window would be a call whose execution nobody is waiting to
     *    stop.
     * 1. The call id must be one this contract accepts. A malformed id is not a call.
     * 2. The tool must be in **this generation's frozen catalog**. A tool the assistant did not
     *    offer is not reachable by naming it, which is what makes the catalog a boundary rather
     *    than a suggestion.
     * 3. The arguments must be a shape the contract takes.
     * 4. Only then is the binding built and the ledger asked — so a repeat is judged against a
     *    binding that was derived from verified inputs rather than from whatever arrived.
     *
     * @throws BridgeRejected never — every refusal is returned as a decision value, because a
     *   caller that has to catch does not see which of these it hit.
     */
    fun onInvoke(toolCallId: String?, toolName: String?, arguments: JsonElement?): BridgeInvokeDecision {
        if (closing) return BridgeInvokeDecision.GenerationClosing

        if (!BridgeBinding.isToolCallId(toolCallId)) {
            return BridgeInvokeDecision.Refused(BridgeRejection.INVOCATION_ID_INVALID)
        }
        val id = toolCallId!!

        // `tool.invoke.tool_name` carries the **frozen** name — the part after the MCP prefix —
        // because that is what the Server sends, having looked it up in the catalog it froze.
        // A name that is not already the canonical frozen spelling is therefore not a name this
        // contract carries at all, which is a different answer from a well-formed name for a
        // tool this generation was not given. The closed set keeps the two apart.
        val frozenName = toolName?.let { BridgeToolCatalog.frozenNameOf(it) }
        if (frozenName == null || frozenName != toolName) {
            return BridgeInvokeDecision.Refused(BridgeRejection.TOOL_NAME_NOT_BRIDGED)
        }
        val entry = catalog.findEntry(frozenName)
            ?: return BridgeInvokeDecision.Refused(BridgeRejection.TOOL_NOT_IN_CATALOG)

        val validated = try {
            BridgeArguments.validate(arguments ?: return BridgeInvokeDecision.Refused(
                BridgeRejection.ARGS_NOT_AN_OBJECT,
            ))
        } catch (error: BridgeRejected) {
            return BridgeInvokeDecision.Refused(error.reason)
        }

        val invocationBinding = InvocationBinding(
            deviceRef = binding.deviceRef,
            assistantId = binding.assistantId,
            conversationId = binding.conversationId,
            branchId = binding.branchId,
            generationId = binding.generationId,
            requestId = binding.requestId,
            catalogDigest = binding.catalogDigest,
            bridgeAbi = binding.bridgeAbi,
            timeoutMs = binding.timeoutMs,
            toolCallId = id,
            toolName = entry.name,
            // From the frozen entry, never from anything that arrived. The lookup above has
            // already proved they agree; binding the catalog's value is what makes that a fact
            // rather than a coincidence that happened to hold once.
            schemaDigest = entry.schemaDigest,
            argsDigest = validated.digest,
        )

        return when (ledger.decide(invocationBinding)) {
            InvocationDecision.FRESH -> {
                // Opened **before** the caller is told to execute, so a retry arriving while the
                // tool is still running finds a record and attaches instead of running it twice.
                ledger.admit(invocationBinding, monotonicMs())
                BridgeInvokeDecision.Execute(
                    BridgeInvocation(
                        binding = invocationBinding,
                        frozenName = entry.name,
                        toolNameForRuntime = entry.displayName,
                        arguments = validated.value,
                        canonicalArguments = validated.canonical,
                        argsDigest = validated.digest,
                        readOnly = entry.readOnly,
                        source = entry.source,
                    ),
                )
            }

            InvocationDecision.ATTACH -> BridgeInvokeDecision.Await

            InvocationDecision.REPLAY -> {
                val record = ledger.find(id)
                if (record == null) {
                    BridgeInvokeDecision.Await
                } else {
                    BridgeInvokeDecision.Replay(BridgeRules.recordToOutcome(record))
                }
            }

            InvocationDecision.CONFLICT -> {
                // The same id carrying different content. Nothing ran and nothing will. Android's
                // reportable vocabulary has no `conflict` — that is a verdict about the Server's
                // ledger, not about a tool Android ran — so the honest answer is the one that
                // claims nothing: this call is not going to be answered by executing it.
                BridgeInvokeDecision.Refused(BridgeRejection.INVOCATION_BINDING_MISMATCH)
            }
        }
    }

    /**
     * Handles one `tool.cancel`. Idempotent in every direction.
     *
     * A cancel for a call that already settled returns [BridgeCancelDecision.NoOp] and the
     * recorded outcome stands; a second cancel for the same still-pending call also returns
     * `NoOp`, because the first one is already on its way and asking the runtime to stop twice
     * is how a stop becomes a second instruction.
     *
     * Nothing here concludes anything about the call. A cancel *request* is not a cancellation:
     * whether the call can actually be stopped is a property of the runtime's own cancellation
     * capability, and this adapter reports what happened rather than what was asked for.
     */
    fun onCancel(toolCallId: String?): BridgeCancelDecision {
        if (!BridgeBinding.isToolCallId(toolCallId)) return BridgeCancelDecision.NoOp
        val id = toolCallId!!

        val record = ledger.find(id) ?: return BridgeCancelDecision.NoOp
        if (record.state.isTerminal()) return BridgeCancelDecision.NoOp
        if (!cancelPropagated.add(id)) return BridgeCancelDecision.NoOp

        return BridgeCancelDecision.Propagate
    }

    /** True when a cancel has been propagated for this call but it has not settled. */
    fun isCancelRequested(toolCallId: String): Boolean = toolCallId in cancelPropagated

    /**
     * Records what Android concluded about a call.
     *
     * A settle is announced **once**. The first terminal is the answer the peer was told, and a
     * late or repeated one is dropped rather than re-sent — which matters most for the case the
     * Server cannot see: a tool that finishes a moment after its cancel arrived. That call is
     * reported `completed`, not `cancelled`, because it *completed*. Reporting the cancel would
     * be claiming a stop that did not happen, and the M2 gate is explicit that an adapter which
     * cannot guarantee a stop must say so rather than pretend.
     */
    fun complete(toolCallId: String, state: ToolCallState, body: String? = null): BridgeCompletion {
        if (!state.isAndroidReportable()) {
            // A state Android may not report. Treated as a failure to report at all, never as a
            // claim: `pending` would be a call that never terminates, and the other three are
            // verdicts about the Server's records that this side cannot make.
            return BridgeCompletion.Superseded(
                ToolCallOutcome(toolCallId, ToolCallState.FAILED),
            )
        }

        val existing = ledger.find(toolCallId)
            ?: return BridgeCompletion.Superseded(ToolCallOutcome(toolCallId, ToolCallState.FAILED))

        val outcome = ToolCallOutcome(toolCallId, state, body)
        val updated = try {
            ledger.apply(outcome)
        } catch (error: BridgeRejected) {
            // A different terminal than the recorded one. The record stands; this report is
            // dropped rather than announced.
            return BridgeCompletion.Superseded(BridgeRules.recordToOutcome(existing))
        }

        return if (updated === existing) {
            BridgeCompletion.Repeat(BridgeRules.recordToOutcome(existing))
        } else {
            BridgeCompletion.Settled(BridgeRules.recordToOutcome(updated))
        }
    }

    /**
     * The answer to a `tool.query` this side made, or to the Server's own terminal report.
     *
     * The Server's `tool.query.result` is authoritative about **its** ledger, so it is not
     * merged into this one. It is returned unchanged for the caller to act on, which is what
     * keeps "the Server holds no record" from being rewritten as a local success — the failure
     * mode the plan names when it says Android must answer `failed` rather than `not_found`.
     */
    fun onQueryResult(toolCallId: String?, state: ToolCallState?, body: String?): ToolCallOutcome? {
        if (!BridgeBinding.isToolCallId(toolCallId)) return null
        if (state == null) return null
        return ToolCallOutcome(toolCallId!!, state, body)
    }

    /**
     * The outcome to answer a `query` with, for a call this generation holds.
     *
     * [ToolCallState.NOT_FOUND] means this side holds no record — which after a process restart
     * is the honest answer, and deliberately **not** an invitation to re-run the tool. A write
     * whose result was never persisted must not be executed a second time to find out what it
     * did.
     */
    fun query(toolCallId: String?): ToolCallOutcome? = try {
        ledger.query(toolCallId)
    } catch (error: BridgeRejected) {
        null
    }

    /**
     * Settles every call whose deadline has passed, and returns the outcomes to send.
     *
     * A deadline is Android's own: the Server concludes the same thing on its side and the
     * first terminal to arrive wins on both. Nothing here re-executes anything, and a call
     * settled this way is terminal, so a late result from the runtime for it is dropped by
     * [complete] rather than announced.
     */
    fun expire(nowMonotonicMs: Long): List<ToolCallOutcome> = ledger.expire(nowMonotonicMs)

    /**
     * Concludes every outstanding call because **this generation ended**.
     *
     * ## The order, and why it is this order
     *
     * 1. **Stop admitting.** [closing] is set first, so no invoke can enter between the moment
     *    the pending set is read and the moment it is settled. A call admitted in that window
     *    would be one nobody stops.
     * 2. **Close the approvals.** A prompt still on screen for this generation is a prompt whose
     *    answer has nowhere to go; it is closed before anything is stopped, so an "approve" tap
     *    cannot race the stop and start a tool for a generation that is already gone.
     * 3. **Ask the runtime to stop.** Through the runtime's own cancellation capability, for
     *    every call still pending. Already-propagated cancels are not asked for twice.
     * 4. **Wait, bounded, for real conclusions.** [BridgeExecutionHost.awaitConclusions] returns
     *    only what the runtime can prove.
     * 5. **Settle by what was actually proven.** Each pending record takes the state the runtime
     *    reported; a call with no proof ends `failed`, never `cancelled`.
     *
     * The adapter is spent afterwards and its owner must drop it: it holds a terminal for every
     * call it ever saw, so a frame arriving late attaches to a concluded record rather than
     * creating a new one.
     *
     * ## Why nothing here says `cancelled` on its own
     *
     * The previous version of this method recorded `cancelled` for everything still pending, on
     * the reasoning that the turn had ended so the execution had stopped. That reasoning is not
     * sound: a tool the runtime could not interrupt — one already inside a write, one whose
     * handle was lost when the scope that owned it was cancelled — keeps running and keeps its
     * side effect, and calling it `cancelled` would tell the peer that a write did not happen
     * when it did. `cancelled` here means one thing only: **a cancel was requested and the
     * runtime proved the call is over.** Everything else that cannot be proven is `failed`,
     * which claims nothing and cannot mislead.
     *
     * Nothing here is sent to the Server — a generation that has ended has no waiter — and no
     * tool is re-dispatched.
     */
    fun concludeForClosedGeneration(
        waitMs: Long = BridgeClosing.DEFAULT_STOP_WAIT_MS,
    ): List<BridgeClosedCall> {
        // 1. Stop admitting, before reading what is pending.
        closing = true

        val pending = ledger.pendingToolCallIds()
        if (pending.isEmpty()) return emptyList()

        // 2. Close or reject every pending approval.
        for (toolCallId in pending) {
            executions.abandonApproval(binding.generationId, toolCallId)
        }

        // 3. Ask for a real stop, once, for the calls that have not been asked about already.
        val toStop = pending.filter { cancelPropagated.add(it) }
        if (toStop.isNotEmpty()) {
            executions.requestStop(binding.generationId, toStop)
        }

        // 4. Wait, bounded, for whatever the runtime can prove.
        val proven = try {
            executions.awaitConclusions(binding.generationId, pending, waitMs)
        } catch (error: Exception) {
            // A host that threw has proven nothing. The catch is here rather than at the call
            // site because a throw between the stop request and the settle would otherwise
            // leave records pending forever — which is the one shape the nine-state vocabulary
            // is written to exclude.
            emptyMap()
        }

        // 5. Settle by what was proven, and only by what was proven.
        val concluded = mutableListOf<BridgeClosedCall>()
        for (toolCallId in pending) {
            val proof = proven[toolCallId]?.takeIf { it.state.isAndroidReportable() }
            val outcome = if (proof == null) {
                BridgeOutcomes.lostCallOutcome(toolCallId)
            } else {
                proof
            }
            val settled = try {
                ledger.apply(outcome)
            } catch (error: BridgeRejected) {
                // Settled by someone else in the meantime — the runtime's own result won the
                // race. Its record stands; this close has nothing further to say about it.
                ledger.find(toolCallId) ?: continue
            }
            concluded += BridgeClosedCall(
                toolCallId = toolCallId,
                outcome = BridgeRules.recordToOutcome(settled),
                reason = if (proof == null) {
                    BridgeCloseReason.STOP_UNPROVEN
                } else {
                    BridgeCloseReason.RUNTIME_CONCLUDED
                },
            )
        }
        return concluded
    }

    /** The outcome recorded for this call, or `null` when this generation holds no record. */
    fun recordedOutcome(toolCallId: String): ToolCallOutcome? =
        ledger.find(toolCallId)?.let { BridgeRules.recordToOutcome(it) }

    /**
     * The monotonic reading a deadline is computed from.
     *
     * `nanoTime` is the JVM's monotonic clock: it is unaffected by wall-clock adjustments, which
     * is the property that matters, because a deadline derived from a clock the user can move is
     * a deadline the peer can extend by moving it.
     */
    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L
}
