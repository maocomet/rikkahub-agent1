package me.rerere.rikkahub.data.execution

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Redacted lifecycle projection for a tool approval.
 *
 * The executable payload remains in the conversation message graph; this row must never contain
 * tool arguments, command text, paths, credentials, output, or message text.
 */
@Entity(
    tableName = "pending_tool_approvals",
    indices = [
        Index(name = "idx_tool_approvals_execution", value = ["execution_id"]),
        Index(
            name = "idx_tool_approvals_conversation_status_requested",
            value = ["conversation_id", "status", "requested_at_ms"],
        ),
        Index(name = "idx_tool_approvals_resolved", value = ["resolved_at_ms"]),
    ],
)
data class PendingToolApprovalRecord(
    @PrimaryKey
    @ColumnInfo(name = "approval_id")
    val approvalId: String,
    @ColumnInfo(name = "execution_id")
    val executionId: String,
    @ColumnInfo(name = "trace_id")
    val traceId: String?,
    @ColumnInfo(name = "tool_call_id")
    val toolCallId: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "subject_id")
    val subjectId: String,
    @ColumnInfo(name = "subject_type")
    val subjectType: String,
    @ColumnInfo(name = "origin")
    val origin: String,
    @ColumnInfo(name = "capability_key")
    val capabilityKey: String,
    @ColumnInfo(name = "resource_category")
    val resourceCategory: String,
    @ColumnInfo(name = "requested_at_ms")
    val requestedAtMs: Long,
    @ColumnInfo(name = "status", defaultValue = "'PENDING'")
    val status: String = ApprovalStatus.PENDING.name,
    @ColumnInfo(name = "state_version", defaultValue = "0")
    val stateVersion: Long = 0,
    @ColumnInfo(name = "resolved_at_ms")
    val resolvedAtMs: Long? = null,
    @ColumnInfo(name = "resolution_reason")
    val resolutionReason: String? = null,
    @ColumnInfo(name = "resolution_request_id")
    val resolutionRequestId: String? = null,
    /**
     * How this approval may continue once granted — see [ApprovalContinuationMode].
     *
     * Declared last, and with a database default, so v52 is a plain `ADD COLUMN` appended to the
     * existing table rather than a rebuild: nothing about how a pre-v52 row is laid out changes,
     * and every pre-v52 row reads back as [ApprovalContinuationMode.RESUME_COMMAND], which is what
     * it means.
     *
     * A `String` rather than the enum, matching `status`, `subject_type`, `origin` and
     * `execution_kind` above. Room would accept the enum directly, but the ledger's convention is
     * that the *row* is a projection of wire-shaped text and the enum lives at the boundary that
     * reads and writes it. The vocabulary is therefore closed by [ApprovalContinuationMode] and by
     * the boundary, not by this declaration — the constructor is internal to the projection and
     * every writer goes through the enum.
     */
    @ColumnInfo(name = "continuation_mode", defaultValue = "'RESUME_COMMAND'")
    val continuationMode: String = ApprovalContinuationMode.RESUME_COMMAND.name,
)

enum class ApprovalStatus {
    PENDING,
    APPROVED,
    DENIED,
    INVALIDATED;

    val isResolved: Boolean
        get() = this != PENDING

    companion object {
        fun fromWire(value: String?): ApprovalStatus = entries.firstOrNull { it.name == value } ?: INVALIDATED
    }
}
