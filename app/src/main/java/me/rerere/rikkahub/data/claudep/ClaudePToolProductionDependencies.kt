package me.rerere.rikkahub.data.claudep

import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.ToolExecutionGate
import me.rerere.rikkahub.data.ai.execution.ToolPreExecutionDecision
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.capability.CapabilitySubject
import me.rerere.rikkahub.data.capability.CapabilitySubjectResolver
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.privilege.DefaultPrivilegedSessionResolver
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/**
 * The real capability subject for a bridged tool call, resolved from the authorities that own it.
 *
 * ## Why this is a class and not a lambda at the binding site
 *
 * The rule it implements — which principal a call is made on behalf of — is a policy, and a policy
 * that exists only as an inline lambda in a dependency-injection module is a policy nobody can
 * test. This one is the same rule `ChatService` applies, expressed as a lookup: the conversation
 * by its id, the assistant from the current settings, the live privileged session from the
 * registry that owns it, and then the shared [CapabilitySubjectResolver].
 *
 * ## Why every failure is `null`
 *
 * `null` means "this host cannot name the principal", and the bridge host treats it as a refusal
 * to execute. That direction matters: `ToolExecutionGate` skips its entire capability branch when
 * the subject is absent, so substituting an empty or a guessed subject would **widen** what a
 * second-user conversation may do rather than narrow it. A conversation that cannot be read, an
 * assistant that is no longer configured, and an id that is not a UUID therefore all fail closed.
 *
 * Nothing here decides whether a subject is a second user. That is
 * [DefaultPrivilegedSessionResolver]'s answer, read from [me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry]
 * rather than re-derived from a string — re-deriving it is precisely the "make the assistant a
 * second user" substitution the product rule forbids.
 */
class ClaudePToolSubjectSource(
    private val conversations: ConversationRepository,
    private val settingsStore: SettingsStore,
) {

    suspend fun subjectFor(
        assistantId: String,
        conversationId: String,
        origin: ToolCallOrigin,
    ): CapabilitySubject? {
        val conversationUuid = conversationId.toClaudePUuidOrNull() ?: return null
        val conversation = conversations.getConversationById(conversationUuid) ?: return null

        // Read from the live settings rather than from the plan's stored id alone: an assistant
        // that has since been deleted or edited must not keep a principal alive on the strength of
        // an id this host was handed some minutes ago.
        val assistant = settingsStore.settingsFlow.first().assistants
            .firstOrNull { it.id.toString() == assistantId }
            ?: return null

        // The conversation must actually belong to that assistant, or the two halves of this
        // subject describe different turns.
        if (conversation.assistantId != assistant.id) return null

        val privilege = DefaultPrivilegedSessionResolver.resolve(
            assistant = assistant,
            conversation = conversation,
            origin = origin,
        )
        return CapabilitySubjectResolver.resolve(
            assistantId = assistant.id,
            conversationId = conversation.id,
            origin = origin,
            privilege = privilege,
        )
    }
}

/**
 * The app's pre-execution gate, as the one question the bridge host may ask it.
 *
 * `Allowed` becomes [ToolPreExecutionDecision.Allow]; every denial becomes a `Deny` carrying the
 * gate's own reason and the stable `tool_blocked` code, which is the mapping the ordinary agent
 * loop already performs. `Assistant.unrestricted` is **not** passed as an override: a bridged call
 * has no assistant-side autonomy to inherit, and the field is deliberately left at its default.
 */
class ClaudePToolProductionGate(
    private val gate: ToolExecutionGate,
) : ClaudePToolGate {

    override suspend fun decide(
        toolName: String,
        args: JsonObject,
        context: ToolExecutionContext,
    ): ToolPreExecutionDecision = when (
        val result = gate.evaluate(
            toolName = toolName,
            origin = context.callOrigin,
            conversationId = context.conversationId,
            commandId = context.commandId,
            arguments = args,
            capabilitySubject = context.capabilitySubject,
            selectedPrivilegedConversation = context.selectedPrivilegedConversation,
            frozenCapabilities = context.frozenCapabilities,
        )
    ) {
        ToolExecutionGate.GateResult.Allowed -> ToolPreExecutionDecision.Allow
        is ToolExecutionGate.GateResult.Denied -> ToolPreExecutionDecision.Deny(
            errorCode = "tool_blocked",
            reason = result.reason,
        )
    }
}

/** The app's deliberately un-typed identities, read back as their real type. */
private fun String.toClaudePUuidOrNull(): Uuid? = try {
    Uuid.parse(this)
} catch (_: IllegalArgumentException) {
    null
}
