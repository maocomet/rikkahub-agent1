package me.rerere.rikkahub.data.capability

import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Characterization of the subject rule, and the product rule it must not lose.
 *
 * ## Why these are characterization tests
 *
 * The rule was moved out of `ChatService` unchanged. That move is only safe if every answer is
 * identical, so every branch is pinned **item for item** — including the exact identifier
 * spellings, which are load-bearing: a grant is matched on `subjectId` and `subjectType` together,
 * so an id that changes shape silently orphans every stored grant for that principal.
 *
 * The table-driven cases below are deliberately exhaustive over [ToolCallOrigin] rather than
 * spot-checked, because the failure mode is a *new* origin being added to the enum and inheriting
 * the wrong type by falling into a `when` branch nobody re-read.
 *
 * ## The product rules asserted here
 *
 * - Which provider an assistant uses is not an input to this function at all. A Claude P assistant
 *   that uses Claude P does not become a second user, and there is no parameter that could say so.
 * - A remote origin never becomes the local profile, even when it carries the same assistant id.
 * - Only a live, privileged session that expands local tools produces `LOCAL_SECOND_USER`, and it
 *   must bring its own authority snapshot.
 */
class CapabilitySubjectResolverTest {

    private val assistantId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val otherAssistantId = Uuid.parse("99999999-9999-9999-9999-999999999999")
    private val conversationId = Uuid.parse("22222222-2222-2222-2222-222222222222")

    private fun privilege(
        isPrivileged: Boolean = true,
        expandLocalTools: Boolean = true,
        authoritySubjectId: String? = "authority-subject-1",
        privilegedConversationId: Uuid? = conversationId,
    ) = PrivilegedSessionContext(
        assistantId = assistantId,
        conversationId = conversationId,
        origin = ToolCallOrigin.LocalChat,
        privilegedConversationId = privilegedConversationId,
        identityName = "Owner",
        isPrivileged = isPrivileged,
        expandLocalTools = expandLocalTools,
        autoApproveTools = false,
        unrestrictedOverride = false,
        authoritySubjectId = authoritySubjectId,
        authorityEpoch = 1L,
    )

    private fun resolve(
        origin: ToolCallOrigin = ToolCallOrigin.LocalChat,
        privilege: PrivilegedSessionContext? = null,
        assistant: Uuid = assistantId,
    ) = CapabilitySubjectResolver.resolve(assistant, conversationId, origin, privilege)

    // ---------------------------------------------------------------------------------------
    // 1. The origin decides, and every origin is covered
    // ---------------------------------------------------------------------------------------

    /**
     * Every origin maps to its type, exhaustively.
     *
     * The expectation is a literal table rather than a restatement of the implementation, so a
     * change to the mapping has to be made in two places — which is the point of a characterization
     * test.
     */
    @Test
    fun `every origin maps to its expected subject type`() {
        val expected = mapOf(
            ToolCallOrigin.LocalChat to SubjectType.LOCAL_ASSISTANT,
            ToolCallOrigin.SystemAssistant to SubjectType.LOCAL_ASSISTANT,
            ToolCallOrigin.SystemAssistantKeyguard to SubjectType.LOCAL_ASSISTANT,
            ToolCallOrigin.QuickCapture to SubjectType.LOCAL_ASSISTANT,
            ToolCallOrigin.TrustedWorkflow to SubjectType.LOCAL_ASSISTANT,
            ToolCallOrigin.PetInteraction to SubjectType.LOCAL_ASSISTANT,
            ToolCallOrigin.PetHandoffConfirmed to SubjectType.LOCAL_ASSISTANT,
            ToolCallOrigin.PetHandoffAuto to SubjectType.LOCAL_ASSISTANT,
            ToolCallOrigin.Telegram to SubjectType.TELEGRAM,
            ToolCallOrigin.WebServer to SubjectType.WEB,
            ToolCallOrigin.MCP to SubjectType.MCP,
            ToolCallOrigin.ExternalIntent to SubjectType.EXTERNAL_AUTOMATION,
        )

        // The table must cover the enum completely — a new origin is a compile-time omission here
        // only if the enum grows, so assert it rather than trusting the map's size.
        assertEquals(
            "every ToolCallOrigin must appear in this table",
            ToolCallOrigin.entries.toSet(),
            expected.keys,
        )

        expected.forEach { (origin, type) ->
            assertEquals("origin $origin", type, resolve(origin = origin).type)
        }
    }

    /** The identifier spellings, which stored grants are matched against. */
    @Test
    fun `the subject id keeps its exact existing spelling`() {
        val local = resolve(origin = ToolCallOrigin.LocalChat)
        assertEquals("a local assistant is identified by the assistant alone", assistantId.toString(), local.id)

        val telegram = resolve(origin = ToolCallOrigin.Telegram)
        assertEquals("telegram:$assistantId:$conversationId", telegram.id)

        val web = resolve(origin = ToolCallOrigin.WebServer)
        assertEquals("web:$assistantId:$conversationId", web.id)

        val mcp = resolve(origin = ToolCallOrigin.MCP)
        assertEquals("mcp:$assistantId:$conversationId", mcp.id)

        val external = resolve(origin = ToolCallOrigin.ExternalIntent)
        assertEquals("external_automation:$assistantId:$conversationId", external.id)
    }

    // ---------------------------------------------------------------------------------------
    // 2. Only a live privileged session is a second user
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a live privileged session is the second user, carrying its own authority snapshot`() {
        val subject = resolve(privilege = privilege())

        assertEquals(SubjectType.LOCAL_SECOND_USER, subject.type)
        assertEquals("authority-subject-1", subject.id)
        assertEquals(conversationId.toString(), subject.privilegedConversationId)
    }

    /**
     * A session that is not privileged, or that does not expand local tools, decides nothing.
     *
     * The origin then decides, exactly as if no session were present at all. This is what keeps a
     * session *context* from being mistaken for a session *grant*.
     */
    @Test
    fun `a session that is not privileged decides nothing`() {
        assertEquals(
            SubjectType.LOCAL_ASSISTANT,
            resolve(privilege = privilege(isPrivileged = false)).type,
        )
        assertEquals(
            SubjectType.LOCAL_ASSISTANT,
            resolve(privilege = privilege(expandLocalTools = false)).type,
        )
    }

    /**
     * A privileged session without an authority snapshot fails rather than being downgraded.
     *
     * Downgrading would run the call as an ordinary assistant while the user believes it is their
     * privileged profile — the wrong direction. The original raised here, and so does this.
     */
    @Test
    fun `a privileged session with no authority snapshot fails rather than downgrading`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            resolve(privilege = privilege(authoritySubjectId = null))
        }
        assertEquals("second_user_authority_snapshot_missing", thrown.message)
    }

    /**
     * A privileged session takes precedence over the origin — but only the privileged one.
     *
     * Stated explicitly because "the origin decides" is true only in the absence of a session.
     */
    @Test
    fun `a privileged session outranks the origin`() {
        val subject = resolve(origin = ToolCallOrigin.Telegram, privilege = privilege())
        assertEquals(SubjectType.LOCAL_SECOND_USER, subject.type)
    }

    // ---------------------------------------------------------------------------------------
    // 3. The provider is not an input, and Claude P is not a second user
    // ---------------------------------------------------------------------------------------

    /**
     * There is no provider parameter, asserted structurally rather than by reading the signature.
     *
     * This is the product rule expressed as a shape: if the provider were an input, some parameter
     * would name it, and a later change could then make Claude P imply a second-user profile. The
     * function has no such parameter, and this fails if one is added.
     */
    @Test
    fun `the resolver takes no provider input`() {
        // Asserted on the parameter *types*, not their names: Kotlin does not emit parameter names
        // into the class file, so a name-based assertion would read `null` for every parameter and
        // pass vacuously. Types are what the shape is actually made of, and a provider input would
        // have to be a type — there is no way to add one that this would not catch.
        val parameterTypes = CapabilitySubjectResolver::class.java
            .declaredMethods
            .first { it.name == "resolve" && !it.isSynthetic }
            .parameterTypes
            .map { it.name }
            .map { it.substringAfterLast('.') }

        assertEquals(
            "the resolver's inputs are the identity and the session, and nothing else",
            listOf("Uuid", "Uuid", "ToolCallOrigin", "PrivilegedSessionContext"),
            parameterTypes,
        )
        parameterTypes.forEach { name ->
            assertFalse("a provider input must not exist: $name", name.contains("Provider"))
        }
    }

    /**
     * A Claude P assistant behaves exactly like any other local assistant.
     *
     * Its origin is an ordinary local one, so it resolves to `LOCAL_ASSISTANT` — not to a second
     * user, and not to a type of its own. The point of the assertion is that nothing about the
     * provider enters the answer.
     */
    @Test
    fun `a claude p assistant is an ordinary local assistant`() {
        ToolCallOrigin.entries
            .filter { it in setOf(ToolCallOrigin.LocalChat, ToolCallOrigin.SystemAssistant) }
            .forEach { origin ->
                val subject = resolve(origin = origin)
                assertEquals(SubjectType.LOCAL_ASSISTANT, subject.type)
                assertNotEquals(SubjectType.LOCAL_SECOND_USER, subject.type)
                // No privileged conversation is claimed, so no privileged-conversation check can
                // be satisfied by accident.
                assertEquals(null, subject.privilegedConversationId)
            }
    }

    // ---------------------------------------------------------------------------------------
    // 4. Origins and principals are separate
    // ---------------------------------------------------------------------------------------

    /**
     * The same assistant over two remote surfaces is two principals, and neither is the local one.
     *
     * A remote request can carry the same assistant id as a local conversation. If the id were the
     * whole identity, a remote call would inherit the local assistant's grants; if the type were
     * `LOCAL_ASSISTANT`, it would inherit the local tool surface.
     */
    @Test
    fun `the same assistant over a remote origin is a different principal`() {
        val local = resolve(origin = ToolCallOrigin.LocalChat)
        val telegram = resolve(origin = ToolCallOrigin.Telegram)

        assertNotEquals(local.id, telegram.id)
        assertNotEquals(local.type, telegram.type)
        assertFalse(
            "a remote origin must never resolve to the local profile",
            telegram.type == SubjectType.LOCAL_SECOND_USER,
        )
    }

    /** A different assistant is a different principal, at every origin. */
    @Test
    fun `a different assistant is a different principal`() {
        ToolCallOrigin.entries.forEach { origin ->
            val first = resolve(origin = origin, assistant = assistantId)
            val second = resolve(origin = origin, assistant = otherAssistantId)
            assertNotEquals("origin $origin must include the assistant in its identity", first.id, second.id)
        }
    }

    /** The conversation qualifies a remote identity, and only a remote one. */
    @Test
    fun `the conversation qualifies a remote identity and not the local one`() {
        val otherConversation = Uuid.parse("33333333-3333-3333-3333-333333333333")

        val localHere = CapabilitySubjectResolver.resolve(assistantId, conversationId, ToolCallOrigin.LocalChat)
        val localThere = CapabilitySubjectResolver.resolve(assistantId, otherConversation, ToolCallOrigin.LocalChat)
        assertEquals("a local assistant is one principal across its conversations", localHere.id, localThere.id)

        val webHere = CapabilitySubjectResolver.resolve(assistantId, conversationId, ToolCallOrigin.WebServer)
        val webThere = CapabilitySubjectResolver.resolve(assistantId, otherConversation, ToolCallOrigin.WebServer)
        assertNotEquals("a remote principal is per-conversation", webHere.id, webThere.id)
    }

    /** The privileged branch is reached for exactly one condition, so the table is not vacuous. */
    @Test
    fun `only the privileged session produces a second user`() {
        assertTrue(resolve(privilege = privilege()).type == SubjectType.LOCAL_SECOND_USER)

        val nonSecondUser = listOf(
            resolve(origin = ToolCallOrigin.LocalChat),
            resolve(origin = ToolCallOrigin.Telegram),
            resolve(privilege = privilege(isPrivileged = false)),
            resolve(privilege = privilege(expandLocalTools = false)),
        )
        nonSecondUser.forEach { subject ->
            assertFalse(
                "LOCAL_SECOND_USER must be reachable only through a live privileged session",
                subject.type == SubjectType.LOCAL_SECOND_USER,
            )
        }
    }
}
