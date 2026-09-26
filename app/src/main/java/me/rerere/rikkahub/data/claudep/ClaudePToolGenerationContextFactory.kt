package me.rerere.rikkahub.data.claudep

import me.rerere.ai.provider.claudep.ClaudePToolGenerationContext
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import kotlin.uuid.Uuid

/**
 * Builds the generation identity a Claude P tool call is bound to, from the authorities that own
 * each field.
 *
 * ## Why this is a function and not six inline reads
 *
 * "Which authority does each field come from?" is the whole security question for this value, and
 * answering it inline at the dispatch site makes it untestable: a field wired to the wrong
 * authority is a silent cross-generation binding, and the dispatch site cannot be instantiated in
 * a JVM test. Naming the mapping here makes it a thing that can be asserted field by field.
 *
 * What this is **not** is a source of values. Every field is a parameter, so there is nothing here
 * to consult — no "current conversation", no "selected assistant", no page state, no global. A
 * caller that does not have an authority passes `null` for it, and the resulting identity fails
 * closed rather than being completed by guesswork.
 *
 * ## Why a missing authority is a blank field rather than a refusal here
 *
 * The reader of this value is what decides, and it already has: a blank field makes
 * [ClaudePToolGenerationContext.isComplete] false, and the bridge answers that by offering no
 * tools at all. Returning `null` instead would be a second, differently-spelled way to say the
 * same thing — and it would change which local refusal reason the bridge records for a case that
 * is already handled. The mapping stays a mapping.
 *
 * [callOrigin] is deliberately **not** nullable: it is not an identity that can be missing, it is
 * the caller's own vocabulary, and a caller that cannot name one has a different problem. It is
 * carried as the enum's own `name` and is never trimmed, folded or defaulted — the bridge's
 * mapping is an exact match, and normalising here would be substituting an origin the caller did
 * not supply.
 */
object ClaudePToolGenerationContextFactory {

    /**
     * The identity for one dispatch.
     *
     * @param runId the generation run control's own id; `null` when no control exists.
     * @param authoritativeCommandId the durable admitted command only — **never** the run id
     *   standing in for it, which is why the two are separate parameters.
     * @param conversationId the conversation this turn belongs to.
     * @param assistantId the resolved assistant's id.
     * @param branchId the generation lineage's branch anchor; `null` when the turn has no branch.
     * @param callOrigin the resolved call origin, as the app's own vocabulary names it.
     */
    fun build(
        runId: Uuid?,
        authoritativeCommandId: Uuid?,
        conversationId: Uuid?,
        assistantId: Uuid?,
        branchId: Uuid?,
        callOrigin: ToolCallOrigin,
    ): ClaudePToolGenerationContext = ClaudePToolGenerationContext(
        runId = runId?.toString().orEmpty(),
        commandId = authoritativeCommandId?.toString().orEmpty(),
        conversationId = conversationId?.toString().orEmpty(),
        assistantId = assistantId?.toString().orEmpty(),
        branchId = branchId?.toString().orEmpty(),
        callOrigin = callOrigin.name,
    )
}
