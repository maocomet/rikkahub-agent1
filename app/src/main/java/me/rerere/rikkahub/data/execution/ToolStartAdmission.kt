package me.rerere.rikkahub.data.execution

import me.rerere.rikkahub.learning.model.LearningScopeKind

/**
 * What the tool runtime may do about a call's execution record when it is about to start it.
 *
 * ## The defect this type exists to close
 *
 * An approval-gated call is opened **twice** by design and the two opens are not the same draft.
 * [SecondUserApprovalLifecycle] opens the record when it raises the barrier, under the approval's
 * own admission identity: an approval-scoped idempotency key and an authority learning scope.
 * [ExecutionRecordCriticalToolLifecycleSink] then opens the *same* record id when the runtime is
 * about to run the call, under the ordinary runtime identity: a tool idempotency key and, for an
 * ordinary assistant, an assistant learning scope.
 *
 * `ExecutionStateTransaction.open` refuses that second open — correctly, because it cannot tell the
 * difference between "the approval authority already opened this call" and "somebody is reusing
 * this id for a different call". So the identity mismatch surfaced as
 * `execution_tracking_unavailable` and the granted tool never ran.
 *
 * The fix is not to relax that refusal. It is to state, in one place and with an explicit decision,
 * the third case the two-way check could not express: *the approval authority already opened this
 * exact call, a human granted it, and it is already admitted to run — so adopt its record instead
 * of opening a second one.*
 *
 * ## Why adoption is not "the same id exists, so let it through"
 *
 * Adoption is granted only when the record, the approval that owns it and the runtime's own
 * invocation identity all agree, and only when the record is still `starting`. See
 * [decideToolStartAdmission] for the check and its ordering. Everything else — no approval
 * projection, a projection that does not belong to this record, a decision that is not an approval,
 * any identity disagreement, a record still waiting on a decision, a record already running, a
 * terminal or orphaned record — refuses, and a refusal is fail-closed at the runtime: durable
 * tracking is required before the side effect, so the tool body is never reached.
 *
 * ## Why this decision is not the whole guarantee
 *
 * Everything above is decided from a snapshot, and a snapshot can go stale between being read and
 * being acted on: an emergency stop, a cancellation or a recovery may commit in that window. So the
 * decision is never the last word — a `starting` record is *claimed* before the call proceeds
 * ([ToolExecutionLedger.claimApprovedToolStart]), and that compare-and-set is what refuses a call
 * whose snapshot stopped being true. This type answers "is this the approved call?"; the claim
 * answers "is that still the case?".
 */
internal sealed interface ToolStartAdmission {
    /**
     * Nothing has opened this call yet, or what has is already this very draft. Both keep the
     * established behaviour: open (idempotently) and let the ledger's own identity check answer.
     */
    data object OpensRecord : ToolStartAdmission

    /**
     * The approval authority opened this exact call and a human granted it. The runtime adopts that
     * record — no second open, no second row, no second identity — and continues the same record
     * through `running` to its terminal.
     *
     * [record] is the snapshot the decision was made from, version included: the caller must claim
     * it before proceeding, so that a state change after this reading is refused rather than run
     * through.
     */
    data class AdoptsApprovedCall(val record: ExecutionRecord) : ToolStartAdmission

    /** Nothing may run. [reasonCode] is stable, non-secret and never carries tool input. */
    data class Refuses(val reasonCode: String) : ToolStartAdmission
}

/** The pre-existing refusal, kept so a record with no approval authority reads the same as before. */
private const val IDENTITY_CONFLICT = "execution_open_identity_conflict"

/**
 * Decides whether the runtime may start on the record it found, on a record it opens itself, or not
 * at all.
 *
 * The order is the proof, and it runs from the strongest evidence outwards:
 *
 * 1. **No record** → [ToolStartAdmission.OpensRecord]. This is the ordinary tool path, unchanged.
 * 2. **A record already identical to this draft** → [ToolStartAdmission.OpensRecord], which is the
 *    idempotent re-open the ledger has always performed for a retried or resumed call. The ledger's
 *    own `hasSameAdmissionIdentityAs` is what decides this, so the global rule is not duplicated —
 *    and it can never match an approval's record, whose idempotency key is approval-scoped.
 * 3. **Anything else must be the approval authority's record for this exact call.** Without a
 *    projection there is nothing to prove provenance from, so it refuses with the code this path
 *    already produced.
 * 4. Only then, and only when every identity field agrees and the record is still `starting`, is it
 *    adopted.
 *
 * The result is a decision about one snapshot, and the caller must treat it as one: a record
 * reported as adoptable is claimed (compare-and-set on the version this decision read) before the
 * call proceeds, so a cancellation or a stop that commits afterwards is refused rather than run
 * through. Nothing here is a substitute for that claim.
 */
internal fun decideToolStartAdmission(
    existing: ExecutionRecord?,
    approvalAuthority: PendingToolApprovalRecord?,
    draft: ExecutionRecordDraft,
): ToolStartAdmission {
    if (existing == null) return ToolStartAdmission.OpensRecord
    if (existing.hasSameAdmissionIdentityAs(draft)) return ToolStartAdmission.OpensRecord

    val approval = approvalAuthority ?: return ToolStartAdmission.Refuses(IDENTITY_CONFLICT)

    // ── The projection must be this record's approval, and it must have been granted ───────
    //
    // `getByExecutionId` selects the row; these two comparisons are what make the row the *authority*
    // for this execution rather than merely a row that mentions it. The id is derived from the
    // execution id by the single site that writes approvals, so a row whose id does not match that
    // derivation was not written by that site.
    if (approval.executionId != existing.id) return ToolStartAdmission.Refuses("approval_execution_mismatch")
    if (approval.approvalId != toolApprovalId(existing.id)) {
        return ToolStartAdmission.Refuses("approval_identity_mismatch")
    }
    if (ApprovalStatus.fromWire(approval.status) != ApprovalStatus.APPROVED) {
        return ToolStartAdmission.Refuses("approval_not_granted")
    }
    // The record itself must be linked to that approval. An approval-scoped idempotency key is
    // written by the approval authority's open and by nothing else, so this is the record's own
    // statement that the approval above is its admission.
    if (existing.idempotencyKey != toolApprovalIdempotencyKey(approval.approvalId)) {
        return ToolStartAdmission.Refuses("approval_record_link_mismatch")
    }

    // ── The record, the approval and this invocation must describe one call ────────────────
    //
    // Compared against the draft, which is built from the runtime's own canonical invocation and is
    // therefore the thing being started. `toolSchemaFingerprint` and `toolName` are the identity of
    // the *tool*; run, command, conversation and tool-call id are the identity of the *call*; the
    // subject and origin are who is acting and from where.
    if (existing.traceId != draft.traceId ||
        existing.commandId != draft.commandId ||
        existing.conversationId != draft.conversationId ||
        existing.toolCallId != draft.toolCallId ||
        existing.toolName != draft.toolName ||
        existing.toolSchemaFingerprint != draft.toolSchemaFingerprint ||
        existing.subjectId != draft.subjectId ||
        existing.subjectType != draft.subjectType ||
        existing.origin != draft.origin ||
        existing.parentExecutionId != draft.parentExecutionId ||
        existing.executionKind != draft.executionKind.name ||
        existing.completionPolicy != draft.completionPolicy.name
    ) {
        return ToolStartAdmission.Refuses("approval_execution_identity_mismatch")
    }
    if (approval.traceId != draft.traceId ||
        approval.conversationId != draft.conversationId ||
        approval.toolCallId != draft.toolCallId ||
        approval.subjectId != draft.subjectId ||
        approval.subjectType != draft.subjectType ||
        approval.origin != draft.origin
    ) {
        return ToolStartAdmission.Refuses("approval_projection_identity_mismatch")
    }

    // Capability and resource are anchored on the approval rather than on the draft on purpose. The
    // approval resolved them *with* the call's arguments and the sink resolves them without them, so
    // the two need not agree — but the record must still be exactly what the approval wrote.
    if (existing.capabilityKeys != approval.capabilityKey ||
        existing.resourceSummary != approval.resourceCategory
    ) {
        return ToolStartAdmission.Refuses("approval_capability_mismatch")
    }

    // The learning scope is the field that made the ordinary draft conflict, and it is authority
    // scoped by construction for every approval. Compared as the stored columns, the same way
    // `hasSameAdmissionIdentityAs` compares them, so a legacy null scope cannot slip through as an
    // equality against a fresh scope object.
    if (existing.learningScopeKind != LearningScopeKind.AUTHORITY_SUBJECT.name ||
        existing.learningScopeId != approval.subjectId
    ) {
        return ToolStartAdmission.Refuses("approval_learning_scope_mismatch")
    }

    // ── Only a record waiting to begin may be adopted ───────────────────────────────────────
    //
    // `waiting_approval` means the decision is not committed yet, whatever the projection says;
    // every terminal state means the call is over; and `running` means it has already begun.
    //
    // That last one is not a formality. Adoption is the runtime taking over a call that is admitted
    // but not yet started, and the only thing that reaches `running` is the runtime's own RUNNING
    // statement for this very call — so a STARTING that finds `running` is a second start, and a
    // second start is a second body execution. Refusing it here is one more layer that has to fail
    // before that happens; the layer that is actually supposed to prevent it is the bridge's own
    // exclusive claim, taken before the runtime is called at all.
    //
    // Every refusal is fail-closed at the runtime: durable tracking is required before the side
    // effect, so the tool body is never reached.
    val status = ExecutionStatus.fromWire(existing.status)
    if (status != ExecutionStatus.starting) {
        return ToolStartAdmission.Refuses("approval_execution_not_started")
    }
    return ToolStartAdmission.AdoptsApprovedCall(existing)
}
