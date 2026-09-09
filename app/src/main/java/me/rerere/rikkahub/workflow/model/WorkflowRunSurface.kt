package me.rerere.rikkahub.workflow.model

import me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot

/**
 * Pure, JVM-testable seams behind the workflow run-time tool surface + schema-staleness gate.
 *
 * Authoring and execution must fingerprint the SAME effective tool surface:
 *  - a workflow authored in a confirmed Second-User session may run against that assistant's
 *    current Second-User allowlist surface, but ONLY while the assistant is still the ACTIVE
 *    Second User and the tool is still allowed by the CURRENT allowlist (never "was once
 *    Second User, forever expanded");
 *  - a workflow authored under an ordinary surface is pinned to `assistant.localTools`;
 *  - a legacy row (no marker) is healed by a bounded inference, never by identity alone.
 */

/** Which tool surface a workflow run should validate + execute against. */
enum class WorkflowRunSurface {
    /** `assistant.localTools` — the ordinary per-assistant surface. */
    ASSISTANT_LOCAL,
    /** Current Second-User allowlist surface (PRIVILEGED restricted by the current tokens). */
    SECOND_USER_ALLOWLIST,
}

object WorkflowRunSurfaceResolver {

    /**
     * Choose the run surface for one workflow fire.
     *
     * @param referencedToolNames  the set of `action.tool` names the definition references.
     * @param localSurfaceToolNames the tool names `assistant.localTools` actually yields at run.
     */
    fun choose(
        origin: WorkflowOrigin,
        authoringAuthority: WorkflowAuthoringAuthority?,
        referencedToolNames: Set<String>,
        localSurfaceToolNames: Set<String>,
        assistantIsCurrentActiveSecondUser: Boolean,
    ): WorkflowRunSurface = when (origin) {
        // Learned workflows keep their existing fail-closed surface derivation unchanged.
        WorkflowOrigin.LEARNED -> WorkflowRunSurface.ASSISTANT_LOCAL

        WorkflowOrigin.USER -> when (authoringAuthority) {
            // Explicitly authored from the Second-User expanded surface. Valid while the
            // authoring assistant is still the ACTIVE Second User; otherwise fall back to the
            // assistant's ordinary surface (referenced tools outside it become unavailable).
            WorkflowAuthoringAuthority.SECOND_USER_CONFIRMED ->
                if (assistantIsCurrentActiveSecondUser) {
                    WorkflowRunSurface.SECOND_USER_ALLOWLIST
                } else {
                    WorkflowRunSurface.ASSISTANT_LOCAL
                }

            // Explicitly authored under an ordinary surface — pinned, never expanded by a
            // later Second-User promotion.
            WorkflowAuthoringAuthority.LOCAL -> WorkflowRunSurface.ASSISTANT_LOCAL

            // Legacy pre-marker row. Bounded inference: only expand when the ordinary surface
            // cannot cover a referenced tool AND the authoring assistant is still the ACTIVE
            // Second User. Ordinary-authored legacy rows whose tools all sit inside localTools
            // stay ordinary (the Second-User allowlist is never imposed on them).
            null ->
                if (assistantIsCurrentActiveSecondUser &&
                    referencedToolNames.any { it !in localSurfaceToolNames }
                ) {
                    WorkflowRunSurface.SECOND_USER_ALLOWLIST
                } else {
                    WorkflowRunSurface.ASSISTANT_LOCAL
                }
        }
    }
}

/**
 * Classifies each action of a definition against the run-time catalog. Mirrors the historical
 * engine scan but splits "tool genuinely not present in the effective surface" (an availability
 * problem) from "tool present but its schema fingerprint changed" (a staleness problem). Learned
 * workflows keep the old fail-closed mapping: any anomaly is staleness.
 */
sealed interface WorkflowSchemaProblem {
    val action: WorkflowAction

    /** Tool present but fingerprint mismatch / corrupt / (learned) missing stamp — real staleness. */
    data class Stale(override val action: WorkflowAction) : WorkflowSchemaProblem

    /** USER action whose tool is absent from the effective run surface — availability, not staleness. */
    data class ToolUnavailable(override val action: WorkflowAction) : WorkflowSchemaProblem
}

object WorkflowSchemaGate {

    /**
     * First problem in action order, or null when every action is runnable against [schemas].
     * [isLearned] keeps the pre-existing fail-closed semantics for learned workflows: they never
     * receive the availability split — every anomaly disables them as schema-stale.
     */
    fun firstProblem(
        actions: List<WorkflowAction>,
        schemas: ToolCatalogSnapshot,
        isLearned: Boolean,
    ): WorkflowSchemaProblem? = actions.firstNotNullOfOrNull { action ->
        val stored = action.toolSchemaFingerprint
        val entry = schemas.entry(action.tool)
        if (isLearned) {
            val stale = stored == null ||
                !WorkflowToolSchemaSnapshot.isCanonical(stored) ||
                entry?.schemaFingerprint != stored
            if (stale) WorkflowSchemaProblem.Stale(action) else null
        } else when {
            // USER action whose tool is absent from the current effective surface. We cannot
            // compare a schema that is no longer exposed — this is an availability/policy
            // problem (the tool may return when the user re-enables it / re-adds the allowlist),
            // NOT a schema change.
            entry == null -> WorkflowSchemaProblem.ToolUnavailable(action)
            // Legacy pre-P4 USER rows carry no fingerprint and are allowed to run when present.
            stored == null -> null
            // Defensive: a non-canonical stamp can only come from a corrupt definition.
            !WorkflowToolSchemaSnapshot.isCanonical(stored) -> WorkflowSchemaProblem.Stale(action)
            // Tool is present but the model-visible schema actually changed since authoring.
            entry.schemaFingerprint != stored -> WorkflowSchemaProblem.Stale(action)
            else -> null
        }
    }
}
