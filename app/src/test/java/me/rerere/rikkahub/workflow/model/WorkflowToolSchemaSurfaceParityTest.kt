package me.rerere.rikkahub.workflow.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.appendTopToolExample
import me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Regression for the systematic `tool_schema_stale` on manual workflow_run.
 *
 * Authoring (workflow_create/update) must stamp schema fingerprints from the SAME
 * model-visible tool surface the engine re-derives at fire time (the decorated surface that
 * getTools returns — approval copy + quick-win usage example + error envelopes). Stamping the
 * raw internal list instead fingerprints a description WITHOUT the appended "Example: …"
 * suffix, so every action tool in the top-example set (show_toast, post_notification, …)
 * mismatches at run time and the workflow is permanently stale.
 *
 * The test mirrors that surface decoration with [appendTopToolExample]; the approval copy and
 * error-envelope wrappers do not touch name / description / parameters and therefore never
 * enter the fingerprint, so this is fingerprint-equivalent to the real seam.
 */
class WorkflowToolSchemaSurfaceParityTest {

    private val showToastDescription =
        "Show a brief toast popup over whatever is currently on screen. " +
            "Use sparingly — toasts are intrusive and only useful for short, momentary feedback."
    private val postNotificationDescription =
        "Post an Android notification on behalf of the user. Use sparingly — notifications are intrusive."
    private val controlDescription =
        "Read the contents of a text file from the device."

    private fun syntheticTool(name: String, description: String): Tool = Tool(
        name = name,
        description = description,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("arg", buildJsonObject {
                        put("type", "string")
                        put("description", "Argument")
                    })
                },
            )
        },
        execute = { emptyList() },
    )

    private fun schemaFingerprint(tool: Tool): String =
        ToolCatalogSnapshot.fromDefinitions(listOf(tool)).entry(tool.name)!!.schemaFingerprint

    /** The set an authoring workflow whose action is exactly these tools would act on. */
    private fun actionFor(tool: Tool): WorkflowAction =
        WorkflowAction(tool = tool.name, args = buildJsonObject { put("arg", JsonPrimitive("x")) })

    // ---- premise: decoration changes the fingerprint of top-example tools ----

    @Test
    fun `appendTopToolExample changes show_toast schema fingerprint`() {
        val raw = syntheticTool("show_toast", showToastDescription)
        val decorated = appendTopToolExample(raw)
        assertNotEquals("decorated description must differ", raw.description, decorated.description)
        assertNotEquals(
            "fingerprint includes description, so decoration must change it",
            schemaFingerprint(raw),
            schemaFingerprint(decorated),
        )
    }

    @Test
    fun `appendTopToolExample changes post_notification schema fingerprint`() {
        val raw = syntheticTool("post_notification", postNotificationDescription)
        val decorated = appendTopToolExample(raw)
        assertNotEquals(
            schemaFingerprint(raw),
            schemaFingerprint(decorated),
        )
    }

    @Test
    fun `control tool outside top examples is unaffected by decoration`() {
        val control = syntheticTool("read_file", controlDescription)
        val decorated = appendTopToolExample(control)
        assertEquals("control tool is not in TOP_TOOL_EXAMPLES", control.description, decorated.description)
        assertEquals(
            schemaFingerprint(control),
            schemaFingerprint(decorated),
        )
    }

    @Test
    fun `top example decoration is idempotent once the description already carries an Example`() {
        val once = appendTopToolExample(syntheticTool("show_toast", showToastDescription))
        val twice = appendTopToolExample(once)
        assertEquals(once.description, twice.description)
        assertEquals(schemaFingerprint(once), schemaFingerprint(twice))
    }

    // ---- the fix invariant: authoring on the decorated surface matches the engine ----

    @Test
    fun `authoring on the decorated surface stamps the fingerprint the engine recomputes`() {
        val raw = listOf(
            syntheticTool("show_toast", showToastDescription),
            syntheticTool("post_notification", postNotificationDescription),
            syntheticTool("read_file", controlDescription),
        )
        val decoratedSurface = raw.map(::appendTopToolExample) // fingerprint-equivalent to renderToolForSurface
        val actions = decoratedSurface.map(::actionFor)

        val stamped = WorkflowToolSchemaSnapshot.capture(actions, decoratedSurface)
        assertNotNull("every action has a canonical fingerprint on the decorated surface", stamped)

        val runSide = ToolCatalogSnapshot.fromDefinitions(decoratedSurface)
        for (action in stamped!!) {
            assertEquals(
                "stored fingerprint must equal the engine-side recompute for ${action.tool}",
                runSide.entry(action.tool)?.schemaFingerprint,
                action.toolSchemaFingerprint,
            )
        }
    }

    // ---- surface membership must never stale a tool ----

    @Test
    fun `surface membership does not change a tool schema fingerprint`() {
        val conversationTools = listOf(
            syntheticTool("recent_chats", "List the user's recent conversations."),
            syntheticTool("conversation_search", "Search across past conversations."),
        )
        val otherTools = listOf(
            syntheticTool("get_time_info", "Read the device clock."),
            syntheticTool("take_screenshot", "Capture the screen."),
        )

        val inIsolation = ToolCatalogSnapshot.fromDefinitions(conversationTools)
        val inFullSurface = ToolCatalogSnapshot.fromDefinitions(conversationTools + otherTools)

        conversationTools.forEach { tool ->
            assertEquals(
                "gating ${tool.name} out of a surface must not make it stale",
                inIsolation.entry(tool.name)?.schemaFingerprint,
                inFullSurface.entry(tool.name)?.schemaFingerprint,
            )
        }
    }

    @Test
    fun `conversation history tools are not decoration sensitive`() {
        val recentChats = syntheticTool("recent_chats", "List the user's recent conversations.")
        val conversationSearch =
            syntheticTool("conversation_search", "Search across past conversations.")

        listOf(recentChats, conversationSearch).forEach { tool ->
            assertEquals(
                "${tool.name} must not be in TOP_TOOL_EXAMPLES",
                tool.description,
                appendTopToolExample(tool).description,
            )
            assertEquals(schemaFingerprint(tool), schemaFingerprint(appendTopToolExample(tool)))
        }
    }

    // ---- pre-fix reproduction: raw authoring surface would mismatch at run time ----

    @Test
    fun `raw authoring surface would stamp fingerprints the engine rejects for decorated tools`() {
        val raw = listOf(
            syntheticTool("show_toast", showToastDescription),
            syntheticTool("post_notification", postNotificationDescription),
            syntheticTool("read_file", controlDescription),
        )
        val decoratedSurface = raw.map(::appendTopToolExample)
        val actions = decoratedSurface.map(::actionFor)

        val rawStamped = WorkflowToolSchemaSnapshot.capture(actions, raw)!!.associate { it.tool to it.toolSchemaFingerprint!! }
        val decoratedStamped = WorkflowToolSchemaSnapshot.capture(actions, decoratedSurface)!!.associate { it.tool to it.toolSchemaFingerprint!! }

        // Top-example action tools drift when the authoring surface is the raw list…
        assertNotEquals("show_toast", rawStamped["show_toast"], decoratedStamped["show_toast"])
        assertNotEquals("post_notification", rawStamped["post_notification"], decoratedStamped["post_notification"])
        // …while a non-top-example action tool is stable on both surfaces (never stale).
        assertEquals("read_file", rawStamped["read_file"], decoratedStamped["read_file"])
    }
}
