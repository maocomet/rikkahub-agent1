package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.ai.tools.CONVERSATION_SEARCH_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.CONTENT_BEARING_CONVERSATION_TOOL_NAMES
import me.rerere.rikkahub.data.ai.tools.RECENT_CHATS_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.TRANSIENT_CONVERSATION_READER_TOOL_NAMES
import me.rerere.rikkahub.data.ai.tools.TRANSIENT_CONVERSATION_SEARCH_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.ordinaryConversationToolNames
import me.rerere.rikkahub.data.ai.tools.transientReaderToolNamesFor
import me.rerere.rikkahub.data.capability.CapabilityCatalog
import me.rerere.rikkahub.data.capability.CapabilityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the invariant that a persisted tool result's NAME identifies exactly one tool.
 *
 * A tool result reaching persistence carries no identity beyond `toolName` — providers rebuild
 * `UIMessagePart.Tool` from `{id, name, input}` alone and never attach origin or privilege
 * metadata. So anywhere the codebase keys behaviour off a tool name (the persistence sanitizer,
 * the tool-output spill exemption, the capability catalog) two different tools sharing one name
 * become indistinguishable.
 *
 * That is exactly what happened: the Second-User transient reader's search tool and the ordinary
 * assistant's global search tool were both named `conversation_search`, so an ordinary
 * assistant's real search results were redacted and persisted as an audit envelope. These are the
 * assertions that would have caught it.
 */
class TransientConversationToolIdentityTest {

    @Test
    fun `transient reader names never collide with the ordinary conversation surface`() {
        val origins = ToolCallOrigin.entries
        origins.forEach { origin ->
            val ordinary = ordinaryConversationToolNames(
                historyToolsEnabled = true,
                callOrigin = origin,
            )
            val collision = ordinary intersect TRANSIENT_CONVERSATION_READER_TOOL_NAMES
            assertTrue(
                "origin $origin exposes $collision, which the sanitizer would treat as transient",
                collision.isEmpty(),
            )
        }
    }

    @Test
    fun `transient reader names never collide with the content bearing ordinary names`() {
        assertTrue(
            (CONTENT_BEARING_CONVERSATION_TOOL_NAMES intersect TRANSIENT_CONVERSATION_READER_TOOL_NAMES)
                .isEmpty()
        )
    }

    @Test
    fun `the two conversation search tools have distinct names`() {
        assertFalse(CONVERSATION_SEARCH_TOOL_NAME == TRANSIENT_CONVERSATION_SEARCH_TOOL_NAME)
        assertFalse(CONVERSATION_SEARCH_TOOL_NAME in TRANSIENT_CONVERSATION_READER_TOOL_NAMES)
    }

    @Test
    fun `transient reader surface fails closed unless privileged and opted in`() {
        assertEquals(emptySet<String>(), transientReaderToolNamesFor(privileged = false, historyReadEnabled = false))
        // An ordinary assistant that opted into history read still gets no transient reader
        // surface: the reader is a privileged second-user surface only.
        assertEquals(emptySet<String>(), transientReaderToolNamesFor(privileged = false, historyReadEnabled = true))
        assertEquals(emptySet<String>(), transientReaderToolNamesFor(privileged = true, historyReadEnabled = false))
        assertEquals(
            TRANSIENT_CONVERSATION_READER_TOOL_NAMES,
            transientReaderToolNamesFor(privileged = true, historyReadEnabled = true),
        )
    }

    @Test
    fun `ordinary conversation search keeps its unlocked-device capability guard`() {
        // The ordinary tool used to inherit this guard only because it shared a name with the
        // reader's tool. Now that the collision is gone the entry must name it explicitly, or
        // ToolExecutionGate would stop enforcing `requiresUnlockedDevice` for it.
        val descriptor = CapabilityCatalog.byToolName(CONVERSATION_SEARCH_TOOL_NAME)
        assertNotNull("ordinary conversation_search lost its capability descriptor", descriptor)
        assertEquals(CapabilityId.ConversationHistoryRead, descriptor!!.id)
        assertTrue(descriptor.requiresUnlockedDevice)
        assertTrue(ToolCallOrigin.LocalChat in descriptor.allowedOrigins)
    }

    @Test
    fun `every transient reader tool keeps its capability identity`() {
        TRANSIENT_CONVERSATION_READER_TOOL_NAMES.forEach { name ->
            val descriptor = CapabilityCatalog.byToolName(name)
            assertNotNull("transient reader tool $name is no longer catalogued", descriptor)
            assertEquals(CapabilityId.ConversationHistoryRead, descriptor!!.id)
            assertTrue(descriptor.requiresUnlockedDevice)
        }
    }

    @Test
    fun `recent chats stays outside the unlocked-device restriction`() {
        // Titles and dates only, deliberately reachable from other origins — recorded here so a
        // future edit to the catalog entry is a conscious decision rather than a silent change.
        val descriptor = CapabilityCatalog.byToolName(RECENT_CHATS_TOOL_NAME)
        assertTrue(descriptor == null || !descriptor.requiresUnlockedDevice)
    }
}
