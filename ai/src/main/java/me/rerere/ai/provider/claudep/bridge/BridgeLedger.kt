package me.rerere.ai.provider.claudep.bridge

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What Android answers with. `body` is opaque to the Server and is never inspected. */
data class ToolCallOutcome(
    val toolCallId: String,
    val state: ToolCallState,
    /** Present only for `completed`. Opaque; size-bounded; never parsed by the Server. */
    val body: String? = null,
)

/** One call, as this side's storage holds it. */
data class InvocationRecord(
    /** The ledger key: [BridgeBinding.invocationKey] of the tool call id. */
    val key: String,
    val toolCallId: String,
    /** The digest of the binding this call was admitted under. */
    val invocationDigest: String,
    val state: ToolCallState,
    /** Present only once the call completed with a body. */
    val body: String? = null,
    /**
     * The **canonical** invocation this call was admitted with, or `null` when none was stored.
     *
     * This is the object an executor is given, and the reason it is stored rather than rebuilt:
     * the arguments a tool is run with must be the ones the Server sent, validated once and kept,
     * not a value reassembled later from a frame, a UI part, a conversation message or a redacted
     * copy. A rebuild is a second opinion about what was asked for, and it can disagree.
     *
     * `null` is meaningful and is not a gap to fill: a record with no canonical invocation cannot
     * prove what a call was, so nothing may be executed from it. That is the state a record would
     * be in after any path that admitted a call without keeping the object, and the claim refuses
     * rather than guessing.
     */
    val invocation: BridgeInvocation? = null,
    /**
     * Whether an executor has taken this call's execution right.
     *
     * Written once, under the ledger's lock, by [BridgeLedger.claimForExecution]. It is what makes
     * "executed exactly once" a property of the ledger rather than a property of every caller's
     * self-restraint: a second caller finds the flag set and is refused.
     */
    val claimed: Boolean = false,
    /**
     * The Android run that claimed this call, when one did.
     *
     * Recorded for diagnostics only. It is never compared against anything: the pairing between a
     * Server generation and the run serving it is the app's to verify, and a second check here
     * would be a second answer to "which run is this?".
     */
    val claimedByRunId: String? = null,
    /**
     * When this call stops waiting, on a **monotonic** clock, fixed when it was admitted.
     *
     * Set once, at [BridgeRules.openRecord], from this side's own monotonic reading — never
     * from a timestamp a peer supplied, and never recomputed. That is the whole point of
     * storing it: the contract carries a *duration* (`timeoutMs`), the component that does the
     * waiting turns it into one local instant, and every repeat, query, replay and reconnect
     * reads the instant that was already decided rather than deriving a fresh one.
     *
     * A deadline that moved would make the timeout negotiable by whoever asked again, so a
     * device that retried in a loop could hold a call open indefinitely — and the call it held
     * open is one the tool never ran.
     */
    val expiresAtMs: Long,
)

/** What a repeat of a tool call means. A closed set. */
enum class InvocationDecision {
    /** Nothing recorded under this id. The call is created and must be executed. */
    FRESH,

    /** Recorded, same call, still in flight. Nothing runs; wait for the answer already coming. */
    ATTACH,

    /** Recorded, same call, already answered. The recorded answer is returned and nothing runs. */
    REPLAY,

    /** Recorded under this id as a *different* call. Refused; nothing runs. */
    CONFLICT,
}

/**
 * The pure rules of the ledger, and the Kotlin half of `src/bridge/ledger.ts`'s exported
 * functions.
 *
 * These take the record they act on rather than looking one up, which is what makes the
 * idempotency rules testable against the Server's vectors without standing up any state, and
 * what keeps [BridgeLedger] a thin holder rather than a second definition of "the same call".
 */
object BridgeRules {

    /**
     * Decides what to do with an invocation, given what is recorded for its tool call id.
     *
     * The record is found by [BridgeBinding.invocationKey] — by the tool call id alone — so an
     * invocation arriving under a *different* generation, device or schema is not a miss that
     * looks fresh: it finds the same record and is refused as a conflict, which is the
     * fail-closed behaviour the contract asks for.
     *
     * This does not touch the deadline and cannot: it returns a decision and writes nothing.
     * A repeat of a call that is still in flight is an [InvocationDecision.ATTACH], which means
     * *wait for the answer already coming* — not *start waiting again*.
     */
    fun decide(existing: InvocationRecord?, binding: InvocationBinding): InvocationDecision {
        if (existing == null) return InvocationDecision.FRESH

        // Defensive: the caller looked the record up by this binding's tool call id, so a
        // mismatch here means the lookup and the binding disagree about which call this is.
        // That is a conflict, never a fresh call.
        if (existing.toolCallId != binding.toolCallId) return InvocationDecision.CONFLICT

        if (existing.invocationDigest != BridgeBinding.invocationDigest(binding)) {
            return InvocationDecision.CONFLICT
        }

        return if (existing.state.isTerminal()) InvocationDecision.REPLAY else InvocationDecision.ATTACH
    }

    /**
     * Opens a record for a call that is about to be executed.
     *
     * Called **before** the invocation is handed to the runtime, not after it returns. A record
     * written on the way back would leave a window in which a retry arrives, finds nothing, and
     * is admitted as fresh — which is the double execution the ledger exists to prevent.
     */
    fun openRecord(
        binding: InvocationBinding,
        admittedAtMonotonicMs: Long,
        /** The canonical invocation, when the caller assembled one. See [InvocationRecord]. */
        invocation: BridgeInvocation? = null,
    ): InvocationRecord =
        InvocationRecord(
            key = BridgeBinding.invocationKey(binding.toolCallId),
            toolCallId = binding.toolCallId,
            invocationDigest = BridgeBinding.invocationDigest(binding),
            state = ToolCallState.PENDING,
            body = null,
            // The one place a deadline is ever computed.
            expiresAtMs = admittedAtMonotonicMs + binding.timeoutMs,
            invocation = invocation,
        )

    /** True when the recorded deadline has been reached on the caller's monotonic clock. */
    fun isExpired(record: InvocationRecord, nowMonotonicMs: Long): Boolean =
        nowMonotonicMs >= record.expiresAtMs

    /** How long this call may still wait, never negative. */
    fun remainingMs(record: InvocationRecord, nowMonotonicMs: Long): Long =
        maxOf(0L, record.expiresAtMs - nowMonotonicMs)

    /**
     * Writes an answer onto a record.
     *
     * Idempotent for the answer that is already recorded: a `completed` arriving twice, byte for
     * byte, is the same fact stated twice and returns **the same instance**, so a caller can use
     * identity to mean "nothing changed" and skip announcing a terminal it already announced. A
     * *different* answer for the same call is refused rather than overwriting — the first
     * terminal answer is what the caller was told, and silently replacing it would make the
     * record disagree with the reply that already left.
     *
     * @throws BridgeRejected with a reason from the closed set.
     */
    fun applyOutcome(record: InvocationRecord, outcome: ToolCallOutcome): InvocationRecord {
        if (outcome.toolCallId != record.toolCallId) {
            throw BridgeRejected(BridgeRejection.INVOCATION_ID_UNKNOWN)
        }

        if (record.state.isTerminal()) {
            if (record.state == outcome.state && record.body == outcome.body) return record
            throw BridgeRejected(BridgeRejection.OUTCOME_ALREADY_ANSWERED)
        }

        return record.copy(state = outcome.state, body = outcome.body)
    }

    /** The outcome form of a record, which is what a query or a replay answers with. */
    fun recordToOutcome(record: InvocationRecord): ToolCallOutcome =
        ToolCallOutcome(record.toolCallId, record.state, record.body)

    /**
     * Decides whether an execution claim may be granted, against the record this side holds.
     *
     * Pure: it reads a record and writes nothing, which is what makes every refusal reachable from
     * a table-driven test without standing up a ledger, a runtime or a clock. The caller owns the
     * mutation ([BridgeLedger.claimForExecution] writes the claim flag under its own lock).
     *
     * ## The order of the checks, and why it is fixed
     *
     * 1. **A record must exist.** No record is [BridgeClaimRefusal.NOT_FOUND] — the process-restart
     *    answer, and the one that must never be turned into a re-run.
     * 2. **The canonical invocation must exist and must agree, field for field.** A disagreement is
     *    [BridgeClaimRefusal.CONFLICT] and nothing runs. This is checked before anything about the
     *    call's *state*, because a claim that disagrees about content is not the same call and no
     *    later answer about the same call applies to it.
     * 3. **The claim must be well formed** — a named run, and an approval whose ids are actually
     *    present when one was asserted.
     * 4. **Somebody must not already hold it.** [BridgeClaimRefusal.ALREADY_CLAIMED] is checked
     *    before the state, because a duplicate caller deserves the precise answer: the call may
     *    well have completed by now, and "already claimed" is the fact that explains this caller.
     * 5. **The call must still be open**: not terminal, and not past its deadline.
     *
     * The deadline is read from the record's own stored instant and compared on the caller's
     * monotonic clock — never recomputed from `timeoutMs`, which would make a deadline negotiable
     * by whoever asked for a claim.
     */
    fun claim(
        existing: InvocationRecord?,
        request: BridgeExecutionClaim,
        nowMonotonicMs: Long,
    ): BridgeExecutionClaimResult {
        val record = existing ?: return refused(BridgeClaimRefusal.NOT_FOUND)

        // Defensive: the caller looked the record up by this claim's tool call id, so a mismatch
        // here means the lookup and the claim disagree about which call this is.
        if (record.toolCallId != request.toolCallId) return refused(BridgeClaimRefusal.CONFLICT)

        val canonical = record.invocation ?: return refused(BridgeClaimRefusal.INTERRUPTED)

        // Every bound field, by equality. A partial comparison is how a changed conversation,
        // branch, device, catalog, schema or argument set would be waved through under a repeated
        // call id — and the first four of those are exactly what the binding exists to catch.
        if (canonical.binding != request.binding) return refused(BridgeClaimRefusal.CONFLICT)
        if (canonical.binding.generationId != request.serverGenerationId) {
            return refused(BridgeClaimRefusal.CONFLICT)
        }
        if (canonical.toolNameForRuntime != request.toolName) {
            return refused(BridgeClaimRefusal.CONFLICT)
        }
        if (canonical.argsDigest != request.argsDigest) return refused(BridgeClaimRefusal.CONFLICT)
        if (record.invocationDigest != BridgeBinding.invocationDigest(request.binding)) {
            return refused(BridgeClaimRefusal.CONFLICT)
        }

        if (request.runId.isBlank()) return refused(BridgeClaimRefusal.REFUSED)
        if (request.approval is BridgeExecutionApproval.Granted &&
            (request.approval.approvalId.isBlank() || request.approval.executionId.isBlank())
        ) {
            return refused(BridgeClaimRefusal.APPROVAL_REQUIRED)
        }

        if (record.claimed) return refused(BridgeClaimRefusal.ALREADY_CLAIMED)
        if (record.state.isTerminal()) return refused(BridgeClaimRefusal.INTERRUPTED)
        if (isExpired(record, nowMonotonicMs)) return refused(BridgeClaimRefusal.TIMED_OUT)

        return BridgeExecutionClaimResult.Claimed(canonical)
    }

    /** The one place a refusal is built, so no call site can invent a value the set does not hold. */
    private fun refused(reason: BridgeClaimRefusal): BridgeExecutionClaimResult =
        BridgeExecutionClaimResult.Refused(reason)

    /**
     * Answers "what is recorded for this exact tool call id".
     *
     * A call that is still in flight answers `pending`, which is accurate and is what lets a
     * reconnecting peer wait for the answer that is coming instead of re-running the tool. A
     * call this side holds no record of answers `not_found`.
     *
     * A malformed id is refused rather than answered `not_found`: a caller that is not asking
     * about a tool call is a different thing from a caller asking about one that is not there.
     *
     * @throws BridgeRejected when the id is malformed.
     */
    fun resolveQuery(existing: InvocationRecord?, toolCallId: String?): ToolCallOutcome {
        if (!BridgeBinding.isToolCallId(toolCallId)) {
            throw BridgeRejected(BridgeRejection.INVOCATION_ID_INVALID)
        }
        if (existing == null) {
            return ToolCallOutcome(toolCallId!!, ToolCallState.NOT_FOUND)
        }
        if (existing.toolCallId != toolCallId) {
            throw BridgeRejected(BridgeRejection.INVOCATION_ID_UNKNOWN)
        }
        return recordToOutcome(existing)
    }
}

/**
 * One generation's worth of tool calls, and no more.
 *
 * ## Why this is a map and not a platform
 *
 * A tool call lives **inside the run that asked for it**. There is no tool job table, no task
 * API, no queue, no history: this object holds at most one generation's worth of calls, and its
 * owner drops it when the run ends. That is the whole of the persistence story, and it is
 * deliberate — the M2 gate names a general tool platform as the thing this stage must not grow
 * into, and the way to not grow one is to have nowhere to put it.
 *
 * ## The binding, and why every field is in it
 *
 * One `toolCallId` and one invocation digest. A repeat carrying the same id but **any**
 * different field — a different device, conversation, branch, generation, request, catalog,
 * ABI, tool name or arguments — produces a different digest, and a different digest under a
 * repeated id is a conflict that executes nothing. That is what makes it safe for the map to
 * key on the call id alone, and it is why [find] may look a call up by nothing else.
 *
 * ## Why every method is synchronized
 *
 * This was a plain map, driven from one coroutine: the generation's inbound frame path. It is not
 * that any more. An execution claim is made from the coroutine that is answering a call, a
 * conclusion is settled from whoever owns the generation's lifetime, and the two run concurrently
 * during a close. A map mutated from one of those while another reads it is how a record is lost —
 * and a lost record is a second execution, or a call that can never be concluded.
 */
class BridgeLedger {

    /** Guards [calls]. Held only for the duration of one operation and never across a callback. */
    private val lock = Any()

    /** Keyed by [BridgeBinding.invocationKey] of the tool call id, and by nothing else. */
    private val calls = LinkedHashMap<String, InvocationRecord>()

    /** Calls still awaiting a terminal. */
    val pendingCount: Int get() = synchronized(lock) { calls.values.count { !it.state.isTerminal() } }

    /**
     * The tool call ids still awaiting a terminal, in the order they were admitted.
     *
     * Returns the ids rather than the records because every caller of this is about to ask the
     * *runtime* about them — to stop them, or to find out what became of them — and the runtime
     * knows a call by its id. Handing out records would invite a caller to read a state that is
     * about to be superseded by the answer it is on its way to fetch.
     */
    fun pendingToolCallIds(): List<String> = synchronized(lock) {
        calls.values.filter { !it.state.isTerminal() }.map { it.toolCallId }
    }

    /** Every call this generation holds, pending or terminal. */
    val size: Int get() = synchronized(lock) { calls.size }

    /** The record for this exact tool call id, or `null`. One id, one answer. */
    fun find(toolCallId: String): InvocationRecord? =
        synchronized(lock) { calls[BridgeBinding.invocationKey(toolCallId)] }

    /** [BridgeRules.decide], against what this ledger holds. */
    fun decide(binding: InvocationBinding): InvocationDecision =
        BridgeRules.decide(find(binding.toolCallId), binding)

    /**
     * Opens and stores a record for a call that is about to be executed.
     *
     * [invocation] is the canonical object the executor will later be handed. It is stored **here**
     * and returned by [claimForExecution] rather than being rebuilt at execution time, because a
     * rebuild is a second opinion about what the call was and can disagree with the one the
     * arguments were validated under.
     */
    fun admit(
        binding: InvocationBinding,
        admittedAtMonotonicMs: Long,
        invocation: BridgeInvocation? = null,
    ): InvocationRecord = synchronized(lock) {
        val record = BridgeRules.openRecord(binding, admittedAtMonotonicMs, invocation)
        calls[record.key] = record
        record
    }

    /**
     * Grants this call's execution right, atomically and at most once.
     *
     * The read, the decision and the write happen inside one critical section, so two callers
     * racing for the same call cannot both be told they may run it: one wins and the other is
     * [BridgeClaimRefusal.ALREADY_CLAIMED]. That is the whole of the duplicate-execution defence
     * at this layer, and it is why the decision itself is a pure function that this method calls
     * rather than a set of checks inlined between two map reads.
     *
     * @return the canonical invocation on success. On refusal nothing is written, no record is
     *   created, and no invocation is assembled — a claim for a call this side never admitted
     *   leaves the ledger exactly as it was.
     */
    fun claimForExecution(
        request: BridgeExecutionClaim,
        nowMonotonicMs: Long,
    ): BridgeExecutionClaimResult = synchronized(lock) {
        val key = BridgeBinding.invocationKey(request.toolCallId)
        val existing = calls[key]
        val decision = BridgeRules.claim(existing, request, nowMonotonicMs)
        if (decision is BridgeExecutionClaimResult.Claimed && existing != null) {
            calls[key] = existing.copy(claimed = true, claimedByRunId = request.runId)
        }
        decision
    }

    /**
     * The same decision as [claimForExecution], without taking the right.
     *
     * Used before anything user-visible happens, so a call that is already cancelled or expired
     * never reaches a screen. It reads the same rules and writes nothing, which is what keeps the
     * two answers from being two policies.
     */
    fun admissibility(request: BridgeExecutionClaim, nowMonotonicMs: Long): BridgeClaimRefusal? =
        synchronized(lock) {
            val decision = BridgeRules.claim(
                calls[BridgeBinding.invocationKey(request.toolCallId)],
                request,
                nowMonotonicMs,
            )
            (decision as? BridgeExecutionClaimResult.Refused)?.reason
        }

    /**
     * Applies an answer to the stored record for this call.
     *
     * Returns the record after the attempt. Identity tells the caller whether anything
     * transitioned: [BridgeRules.applyOutcome] returns the *same instance* when the outcome
     * merely repeats a settled call, so announcing a terminal only when the instance changed is
     * what keeps one call from being reported twice.
     */
    fun apply(outcome: ToolCallOutcome): InvocationRecord = synchronized(lock) {
        val record = calls[BridgeBinding.invocationKey(outcome.toolCallId)]
            ?: throw BridgeRejected(BridgeRejection.INVOCATION_ID_UNKNOWN)
        val updated = BridgeRules.applyOutcome(record, outcome)
        if (updated !== record) calls[updated.key] = updated
        updated
    }

    /** [BridgeRules.resolveQuery], against what this ledger holds. */
    fun query(toolCallId: String?): ToolCallOutcome =
        BridgeRules.resolveQuery(
            if (BridgeBinding.isToolCallId(toolCallId)) find(toolCallId!!) else null,
            toolCallId,
        )

    /**
     * Settles every call whose recorded deadline has been reached on the caller's monotonic
     * clock, and returns the outcomes that reached one.
     *
     * Returning the outcomes rather than a count is what lets the caller send exactly the
     * terminals that *this* call produced. A count would leave the caller to work out which
     * calls moved, and the only way to do that is to look at the whole ledger again — which is
     * the second definition of "what expired" that this exists to avoid.
     */
    fun expire(nowMonotonicMs: Long): List<ToolCallOutcome> = synchronized(lock) {
        val settled = mutableListOf<ToolCallOutcome>()
        for (record in calls.values.toList()) {
            if (record.state.isTerminal()) continue
            if (!BridgeRules.isExpired(record, nowMonotonicMs)) continue
            val expired = record.copy(state = ToolCallState.TIMED_OUT)
            calls[expired.key] = expired
            settled += BridgeRules.recordToOutcome(expired)
        }
        settled
    }
}

/** `validateToolCallOutcome`, and the closed key set it checks against. */
object BridgeOutcomes {

    private val OUTCOME_KEYS = setOf("toolCallId", "state", "body")

    /**
     * The honest answer for a call this side can no longer prove anything about.
     *
     * After a process restart there is no record of what a tool did, and the contract is
     * explicit about what that means: Android "may not send" `not_found` or `conflict`, because
     * those are verdicts about the *Server's* ledger and Android does not hold that ledger. The
     * honest word is `failed`, and this function exists so that answer is produced by name
     * rather than assembled from two states at a call site — where the tempting mistake is to
     * reach for `not_found` because that is what the local lookup actually returned.
     *
     * It is also **not** permission to re-run the tool. A write whose result was never
     * persisted must not be executed a second time to discover what it did; reporting failure
     * costs the user a tool call and cannot cost them a side effect.
     */
    fun lostCallOutcome(toolCallId: String): ToolCallOutcome =
        ToolCallOutcome(toolCallId, ToolCallState.FAILED)

    /**
     * Validates an outcome frame.
     *
     * The state is checked against [isAndroidReportable] and not merely against "a known
     * state": `pending`, `disconnected`, `not_found` and `conflict` are the Server's own
     * verdicts about its own records. An Android claiming one of those would be claiming
     * knowledge it does not have.
     *
     * @throws BridgeRejected with a reason from the closed set.
     */
    fun validate(raw: JsonObject): ToolCallOutcome {
        for (key in raw.keys) {
            if (key !in OUTCOME_KEYS) throw BridgeRejected(BridgeRejection.OUTCOME_FIELD_UNKNOWN)
        }

        val toolCallId = raw.string("toolCallId")
        if (!BridgeBinding.isToolCallId(toolCallId)) {
            throw BridgeRejected(BridgeRejection.INVOCATION_ID_INVALID)
        }

        val state = ToolCallState.fromWire(raw.string("state"))
            ?: throw BridgeRejected(BridgeRejection.STATE_NOT_REPORTABLE)
        if (!state.isAndroidReportable()) {
            throw BridgeRejected(BridgeRejection.STATE_NOT_REPORTABLE)
        }

        val bodyElement = raw["body"]
        // A missing body is a tool that returned nothing, which is legitimate for `completed`
        // and is the only state that can carry one at all. An explicit `null` is neither: it is
        // a caller that meant to send something and sent the absence of it.
        if (bodyElement is JsonNull) throw BridgeRejected(BridgeRejection.RESULT_STATE_MISMATCH)
        if (bodyElement == null) return ToolCallOutcome(toolCallId!!, state)

        val body = (bodyElement as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw BridgeRejected(BridgeRejection.RESULT_STATE_MISMATCH)
        if (state != ToolCallState.COMPLETED) {
            throw BridgeRejected(BridgeRejection.RESULT_STATE_MISMATCH)
        }
        if (body.toByteArray(Charsets.UTF_8).size > BridgeLimits.MAX_RESULT_BYTES) {
            throw BridgeRejected(BridgeRejection.RESULT_TOO_LARGE)
        }
        // **No content scan**, and the omission is deliberate rather than an oversight. A result
        // body is opaque: this side does not parse it, does not render it, and has no opinion
        // about what it contains. A tool that returns a token — a credential inspector, a secret
        // manager reading back what it stored — is an ordinary tool doing what it was asked.
        //
        // What still bounds this body is size, and what still keeps it out of the places it must
        // never reach is that nothing here logs it and nothing persists it.
        return ToolCallOutcome(toolCallId!!, state, body)
    }
}
