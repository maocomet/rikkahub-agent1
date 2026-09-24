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
    fun openRecord(binding: InvocationBinding, admittedAtMonotonicMs: Long): InvocationRecord =
        InvocationRecord(
            key = BridgeBinding.invocationKey(binding.toolCallId),
            toolCallId = binding.toolCallId,
            invocationDigest = BridgeBinding.invocationDigest(binding),
            state = ToolCallState.PENDING,
            body = null,
            // The one place a deadline is ever computed.
            expiresAtMs = admittedAtMonotonicMs + binding.timeoutMs,
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
 */
class BridgeLedger {

    /** Keyed by [BridgeBinding.invocationKey] of the tool call id, and by nothing else. */
    private val calls = LinkedHashMap<String, InvocationRecord>()

    /** Calls still awaiting a terminal. */
    val pendingCount: Int get() = calls.values.count { !it.state.isTerminal() }

    /** Every call this generation holds, pending or terminal. */
    val size: Int get() = calls.size

    /** The record for this exact tool call id, or `null`. One id, one answer. */
    fun find(toolCallId: String): InvocationRecord? = calls[BridgeBinding.invocationKey(toolCallId)]

    /** [BridgeRules.decide], against what this ledger holds. */
    fun decide(binding: InvocationBinding): InvocationDecision =
        BridgeRules.decide(find(binding.toolCallId), binding)

    /** Opens and stores a record for a call that is about to be executed. */
    fun admit(binding: InvocationBinding, admittedAtMonotonicMs: Long): InvocationRecord =
        BridgeRules.openRecord(binding, admittedAtMonotonicMs).also { calls[it.key] = it }

    /**
     * Applies an answer to the stored record for this call.
     *
     * Returns the record after the attempt. Identity tells the caller whether anything
     * transitioned: [BridgeRules.applyOutcome] returns the *same instance* when the outcome
     * merely repeats a settled call, so announcing a terminal only when the instance changed is
     * what keeps one call from being reported twice.
     */
    fun apply(outcome: ToolCallOutcome): InvocationRecord {
        val record = find(outcome.toolCallId)
            ?: throw BridgeRejected(BridgeRejection.INVOCATION_ID_UNKNOWN)
        val updated = BridgeRules.applyOutcome(record, outcome)
        if (updated !== record) calls[updated.key] = updated
        return updated
    }

    /** [BridgeRules.resolveQuery], against what this ledger holds. */
    fun query(toolCallId: String?): ToolCallOutcome =
        BridgeRules.resolveQuery(
            if (BridgeBinding.isToolCallId(toolCallId)) find(toolCallId!!) else null,
            toolCallId,
        )

    /**
     * Settles every call whose recorded deadline has been reached on the caller's monotonic
     * clock, and returns how many reached one.
     */
    fun expire(nowMonotonicMs: Long): Int {
        var count = 0
        for (record in calls.values.toList()) {
            if (record.state.isTerminal()) continue
            if (!BridgeRules.isExpired(record, nowMonotonicMs)) continue
            calls[record.key] = record.copy(state = ToolCallState.TIMED_OUT)
            count += 1
        }
        return count
    }
}

/** `validateToolCallOutcome`, and the closed key set it checks against. */
object BridgeOutcomes {

    private val OUTCOME_KEYS = setOf("toolCallId", "state", "body")

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
