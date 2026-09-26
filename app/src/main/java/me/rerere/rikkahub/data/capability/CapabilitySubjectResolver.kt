package me.rerere.rikkahub.data.capability

import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import kotlin.uuid.Uuid

/**
 * The principal a tool call is made on behalf of, from the facts that decide it.
 *
 * ## Why this is its own object
 *
 * This rule used to live inside `ChatService` as a private method. That made it unreachable from
 * anything else — including the Claude P tool bridge host, which needs exactly the same answer for
 * exactly the same call — and the only way to reuse it would have been to copy it. A copy is a
 * second policy: the two drift, and the drift is invisible because both look correct in isolation.
 *
 * So the rule lives here, and both callers ask it. It is **extracted, not redesigned**: every
 * branch below was read off the original and preserves its result item for item, including the
 * identifier spellings and the one failure it can raise.
 *
 * ## What decides a subject, and what must not
 *
 * Three things, and only these three: the assistant, the conversation, and the call origin. In
 * particular **the provider is not an input**. Which provider an assistant is configured with and
 * whether it is a second user are independent settings — a Claude P assistant does not become a
 * second user by using Claude P, and nothing here has a parameter that could express such a rule.
 *
 * ## Why a remote origin does not become a local profile
 *
 * Origins and principals are separate. A remote request can carry the same assistant id as a local
 * conversation, and it never becomes that assistant's local identity: only a *live, privileged*
 * session produces [SubjectType.LOCAL_SECOND_USER], and it must carry its own authority snapshot to
 * do it. Everything else is mapped from the origin, and a remote origin maps to a remote type with
 * an origin-qualified id — so the same assistant used over Telegram and in the app yields two
 * different subjects rather than one shared profile.
 *
 * ## Fail-closed, and where it lives
 *
 * This function cannot return "no subject": the original never could, and inventing a null here
 * would change the answer for every existing caller. The fail-closed duty sits with the *caller*
 * that has an optional identity — the bridge host treats an unresolvable or incomplete generation
 * as "no subject" and refuses to execute, rather than substituting a permissive one. The single
 * failure this function does raise is preserved from the original: a privileged session without an
 * authority snapshot is a wiring error, not a session to be downgraded silently.
 */
object CapabilitySubjectResolver {

    /**
     * The subject for one invocation.
     *
     * @param assistantId the resolved assistant's id.
     * @param conversationId the conversation this turn belongs to.
     * @param origin the resolved call origin. This — never the provider — is what separates a local
     *   invocation from a remote one.
     * @param privilege the live privileged session, when there is one. A session that is not
     *   privileged, or that does not expand local tools, takes no effect and the origin decides.
     * @throws IllegalArgumentException when a privileged session expands local tools but carries no
     *   authority snapshot. That is a missing authority, and it must not be filled in from
     *   anything else.
     */
    fun resolve(
        assistantId: Uuid,
        conversationId: Uuid,
        origin: ToolCallOrigin,
        privilege: PrivilegedSessionContext? = null,
    ): CapabilitySubject {
        if (privilege?.isPrivileged == true && privilege.expandLocalTools) {
            return CapabilitySubject(
                id = requireNotNull(privilege.authoritySubjectId) {
                    "second_user_authority_snapshot_missing"
                },
                type = SubjectType.LOCAL_SECOND_USER,
                privilegedConversationId = privilege.conversationId.toString(),
            )
        }

        val type = when (origin) {
            ToolCallOrigin.Telegram -> SubjectType.TELEGRAM
            ToolCallOrigin.WebServer -> SubjectType.WEB
            ToolCallOrigin.MCP -> SubjectType.MCP
            ToolCallOrigin.ExternalIntent -> SubjectType.EXTERNAL_AUTOMATION

            // Workflow snapshots are introduced independently; do not claim a grant exists until
            // the authoring path freezes it. Existing local workflows retain their current gate
            // while this migration is rolled out.
            ToolCallOrigin.TrustedWorkflow,
            ToolCallOrigin.LocalChat,
            ToolCallOrigin.SystemAssistant,
            ToolCallOrigin.SystemAssistantKeyguard,
            ToolCallOrigin.QuickCapture,
            ToolCallOrigin.PetInteraction,
            ToolCallOrigin.PetHandoffConfirmed,
            ToolCallOrigin.PetHandoffAuto,
            -> SubjectType.LOCAL_ASSISTANT
        }

        // A local assistant is identified by the assistant alone — the same assistant across its
        // conversations is one principal. Anything else is origin-qualified, because the same
        // assistant reached over two remote surfaces is two principals and must not share grants.
        val id = if (type == SubjectType.LOCAL_ASSISTANT) {
            assistantId.toString()
        } else {
            "${type.name.lowercase()}:$assistantId:$conversationId"
        }
        return CapabilitySubject(id = id, type = type)
    }
}
