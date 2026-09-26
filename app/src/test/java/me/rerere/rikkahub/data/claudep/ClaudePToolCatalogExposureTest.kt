package me.rerere.rikkahub.data.claudep

import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.ToolExposurePlan
import me.rerere.rikkahub.data.ai.tools.TRANSIENT_CONVERSATION_SEARCH_TOOL_NAME
import me.rerere.rikkahub.data.capability.CapabilityCatalog
import me.rerere.rikkahub.data.capability.ToolInvocationSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Claude P catalog may contain, and why a read-only tool can still be kept out of it.
 *
 * ## The rule this pins
 *
 * "Read-only" is a claim about **side effects**. It is not a claim about privacy, and it is not
 * permission. `transient_conversation_search` reads the content of conversations and has no write,
 * no side effect and no remote call — and it is still withheld from every surface that has not made
 * an explicit decision to accept it.
 *
 * ## Why these tests are here rather than at the provider
 *
 * The exclusion is not a provider rule and must never become one. Nothing anywhere branches on
 * "the provider is Claude P"; the tool list a provider receives is filtered by the same
 * `ToolExposurePlan` every other consumer uses, and this test exercises exactly that filter and
 * then the assembly the Claude P host would freeze. A future provider inherits the same exclusion
 * without anyone remembering to add it.
 */
class ClaudePToolCatalogExposureTest {

    private fun conversationSearchTool() = Tool(
        name = TRANSIENT_CONVERSATION_SEARCH_TOOL_NAME,
        description = "Read-only: search visible messages inside one identified conversation.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = { listOf(UIMessagePart.Text("read")) },
    )

    private fun systemAssistantSurface(overlayAuthorized: Boolean) = ToolExposurePlan(
        origin = ToolCallOrigin.SystemAssistant,
        surfaceAvailable = true,
        activityOverlayAuthorized = overlayAuthorized,
    )

    // ---------------------------------------------------------------------------------------
    // 1. It is withheld, not unclassified
    // ---------------------------------------------------------------------------------------

    /**
     * The classification says "deliberately not offered", which is a decision rather than a gap.
     *
     * This is the distinction the failing test was really about. An `Unclassified` tool keeps the
     * same doors shut today, but for a reason nobody chose — and the next person to add a caller
     * has no way to tell an oversight from a policy.
     */
    @Test
    fun `the transient conversation reader is withheld by name`() {
        assertEquals(
            "a decision, not an omission",
            ToolInvocationSurface.Phase1Unavailable,
            CapabilityCatalog.toolInvocationSurface(TRANSIENT_CONVERSATION_SEARCH_TOOL_NAME),
        )
        assertFalse(
            "and it must not be widened to the background allowlist on the grounds that it only reads",
            CapabilityCatalog.isAvailableFromSystemAssistant(TRANSIENT_CONVERSATION_SEARCH_TOOL_NAME),
        )
    }

    // ---------------------------------------------------------------------------------------
    // 2. The Claude P catalog cannot contain it
    // ---------------------------------------------------------------------------------------

    /**
     * The system-assistant surface never offers it, with or without the Activity overlay.
     *
     * Both overlay states are checked because they take different branches in
     * `ToolExposurePlan.blockReason`, and a tool that is withheld should be withheld on the path
     * that was *not* exercised next time too.
     */
    @Test
    fun `the system assistant surface never offers it`() {
        for (overlayAuthorized in listOf(false, true)) {
            val plan = systemAssistantSurface(overlayAuthorized)
            assertFalse(
                "overlayAuthorized=$overlayAuthorized must not expose it",
                plan.canExpose(TRANSIENT_CONVERSATION_SEARCH_TOOL_NAME),
            )
        }
    }

    /**
     * And the catalog the Claude P host would freeze is empty, because the filter runs first.
     *
     * The order matters and is the point: the tool is not assembled and then withdrawn, it is
     * never a candidate. A catalog that contained it and relied on something downstream to drop it
     * would leave the tool named in a snapshot the Server freezes.
     */
    @Test
    fun `the assembled catalog carries no entry for it`() {
        val plan = systemAssistantSurface(overlayAuthorized = true)
        val offered = listOf(conversationSearchTool()).filter { plan.canExpose(it.name) }

        val build = ClaudePToolCatalogAssembly.build(offered)

        assertTrue("nothing may be offered from this surface", offered.isEmpty())
        assertTrue("so the frozen catalog is empty", build.catalog.isEmpty)
        assertNull(
            build.catalog.findEntry(TRANSIENT_CONVERSATION_SEARCH_TOOL_NAME),
        )
    }

    // ---------------------------------------------------------------------------------------
    // 3. Nothing else moved
    // ---------------------------------------------------------------------------------------

    /**
     * The ordinary local path is untouched in both directions.
     *
     * `ToolExposurePlan.blockReason` returns before consulting any classification for an origin
     * that is neither the system assistant nor quick capture, so a privileged `LocalChat` session
     * still sees this tool exactly as it did before the entry was added. Classifying it was not
     * allowed to narrow an existing surface either — a fix that quietly revoked something would be
     * as wrong as one that quietly granted it.
     */
    @Test
    fun `the ordinary local path is unchanged`() {
        val plan = ToolExposurePlan(
            origin = ToolCallOrigin.LocalChat,
            surfaceAvailable = true,
            activityOverlayAuthorized = false,
        )

        assertTrue(
            "the local path is decided before any classification is consulted",
            plan.canExpose(TRANSIENT_CONVERSATION_SEARCH_TOOL_NAME),
        )
    }
}
