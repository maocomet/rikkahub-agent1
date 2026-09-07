package me.rerere.rikkahub.owner

import me.rerere.rikkahub.assistant.SecondUserAdmissionSnapshot
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.ai.tools.createOwnerManagementTools
import me.rerere.rikkahub.data.ai.tools.ownerActionGuideCoverageGaps
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class OwnerManagementToolsTest {
    private val assistantId = Uuid.parse("30000000-0000-0000-0000-000000000003")
    private val conversationId = Uuid.parse("40000000-0000-0000-0000-000000000004")
    private val snapshot = SecondUserAdmissionSnapshot.create(
        assistantId, conversationId, 3, ToolCallOrigin.LocalChat,
    )

    @After
    fun clear() = SecondUserAuthorityRegistry.install(null)

    @Test
    fun `direct owner surface exposes every compact family without a discovery gate`() {
        SecondUserAuthorityRegistry.install(snapshot)
        val tools = createOwnerManagementTools(context()) { request, _ ->
            OwnerOperationResult(
                ok = true,
                requestId = request.requestId,
                state = OwnerOperationState.COMMITTED,
                code = "OK",
                message = "ok",
            )
        }

        assertEquals(OwnerToolFamily.entries.size, tools.size)
        assertEquals(OwnerToolFamily.entries.map { it.toolName }.toSet(), tools.map { it.name }.toSet())
        assertFalse(
            tools.any {
                it.name.startsWith("setup_") ||
                    it.name.startsWith("tool_catalog_") ||
                    it.name == "rikkahub_state_get"
            },
        )
    }

    @Test
    fun `remote target does not expose owner tools`() {
        SecondUserAuthorityRegistry.install(snapshot)
        val remote = context().copy(
            callOrigin = ToolCallOrigin.Telegram,
            privilege = context().privilege?.copy(origin = ToolCallOrigin.Telegram),
        )
        assertFalse(me.rerere.rikkahub.data.ai.tools.isOwnerToolSurfaceAvailable(remote))
        assertTrue(me.rerere.rikkahub.data.ai.tools.isOwnerToolSurfaceAvailable(context()))
    }

    @Test
    fun `every advertised owner action documents its direct arguments`() {
        assertTrue(ownerActionGuideCoverageGaps().isEmpty())
    }

    @Test
    fun `filtering a family removes only that family's model-facing schema`() {
        SecondUserAuthorityRegistry.install(snapshot)
        val enabled = OwnerToolFamily.entries - OwnerToolFamily.DOCTOR
        val tools = createOwnerManagementTools(
            invocationContext = context(),
            gateway = { request, _ ->
                OwnerOperationResult(
                    ok = true,
                    requestId = request.requestId,
                    state = OwnerOperationState.COMMITTED,
                    code = "OK",
                    message = "ok",
                )
            },
            enabledFamilies = enabled.toSet(),
        )

        assertEquals(enabled.size, tools.size)
        val names = tools.map { it.name }.toSet()
        assertFalse(OwnerToolFamily.DOCTOR.toolName in names)
        assertEquals(
            enabled.map { it.toolName }.toSet(),
            names,
        )
    }

    @Test
    fun `owner factory with null families keeps every family and empty disables all`() {
        SecondUserAuthorityRegistry.install(snapshot)
        val all = createOwnerManagementTools(
            invocationContext = context(),
            gateway = { request, _ ->
                OwnerOperationResult(
                    ok = true,
                    requestId = request.requestId,
                    state = OwnerOperationState.COMMITTED,
                    code = "OK",
                    message = "ok",
                )
            },
            enabledFamilies = null,
        )
        assertEquals(OwnerToolFamily.entries.size, all.size)

        val none = createOwnerManagementTools(
            invocationContext = context(),
            gateway = { request, _ ->
                OwnerOperationResult(
                    ok = true,
                    requestId = request.requestId,
                    state = OwnerOperationState.COMMITTED,
                    code = "OK",
                    message = "ok",
                )
            },
            enabledFamilies = emptySet(),
        )
        assertTrue(none.isEmpty())
    }

    private fun context() = ToolInvocationContext(
        callerAssistantId = assistantId.toString(),
        callerConversationId = conversationId.toString(),
        callerModelId = "model",
        callerProviderId = "provider",
        callOrigin = ToolCallOrigin.LocalChat,
        privilege = PrivilegedSessionContext(
            assistantId = assistantId,
            conversationId = conversationId,
            origin = ToolCallOrigin.LocalChat,
            privilegedConversationId = conversationId,
            identityName = "owner",
            isPrivileged = true,
            expandLocalTools = true,
            autoApproveTools = true,
            unrestrictedOverride = false,
            authoritySubjectId = snapshot.subjectId,
            authorityEpoch = snapshot.authorityEpoch,
        ),
    )
}
