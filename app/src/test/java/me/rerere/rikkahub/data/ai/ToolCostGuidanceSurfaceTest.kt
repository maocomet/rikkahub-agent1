package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tool cost guidance must be derived from the tool surface the provider call actually exposes.
 *
 * The previous implementation appended a fixed sentence naming `read_window_tree` /
 * `browser_get_text` whenever any tool existed, so an ordinary assistant exposing only
 * `get_time_info` + `get_screen_time` still paid for a hint about tools it did not have.
 */
class ToolCostGuidanceSurfaceTest {

    private val builder = SystemPromptBuilder()

    /** Every tool name any routing rule can ever mention, whatever the surface is. */
    private val ruleUniverse: Set<String> = TOOL_COST_ROUTING_RULES
        .flatMap { it.textReaders + it.costlyAlternatives }
        .toSet()

    private fun stableFor(
        assistantPrompt: String = "",
        surface: Set<String>,
    ): String = builder.buildSections(
        assistantPrompt = assistantPrompt,
        modelVisibleToolNames = surface,
    ).first

    private fun assertNoGuidance(surface: Set<String>, assistantPrompt: String = "You are helpful.") {
        val stable = stableFor(assistantPrompt, surface)
        assertFalse("guidance must be absent for surface=$surface", stable.contains("Tool cost"))
        assertEquals(
            "a guidance-free stable section must be exactly the assistant prompt",
            assistantPrompt,
            stable,
        )
    }

    @Test
    fun `ordinary device surface produces no guidance`() {
        assertNoGuidance(setOf("get_time_info", "get_screen_time"))
    }

    @Test
    fun `empty surface produces no guidance`() {
        assertNoGuidance(emptySet())
    }

    @Test
    fun `a text reader without a costly alternative produces no guidance`() {
        assertNoGuidance(setOf("get_time_info", "read_window_tree"))
    }

    @Test
    fun `a costly tool without a text reader produces no guidance`() {
        assertNoGuidance(setOf("get_time_info", "take_screenshot"))
    }

    @Test
    fun `a routing choice produces guidance naming only the present reader`() {
        val stable = stableFor(
            surface = setOf("read_window_tree", "take_screenshot"),
        )

        assertTrue(stable.contains("read_window_tree"))
        assertFalse("browser_get_text is not in the surface", stable.contains("browser_get_text"))
        assertFalse("browser_screenshot is not in the surface", stable.contains("browser_screenshot"))
    }

    @Test
    fun `every rule tool named in guidance is present in the surface`() {
        val surfaces = listOf(
            emptySet(),
            setOf("get_time_info", "get_screen_time"),
            setOf("read_window_tree", "take_screenshot"),
            setOf("browser_get_text", "browser_screenshot"),
            setOf("read_window_tree", "browser_get_text", "take_screenshot", "browser_screenshot"),
            setOf("read_window_tree", "browser_get_text", "get_time_info"),
            setOf("take_screenshot", "browser_screenshot"),
        )

        surfaces.forEach { surface ->
            val stable = stableFor(surface = surface)
            ruleUniverse.filter { it in stable }.forEach { mentioned ->
                assertTrue(
                    "guidance mentioned '$mentioned' which is absent from surface=$surface",
                    mentioned in surface,
                )
            }
        }
    }

    @Test
    fun `identical surfaces render byte identical stable text`() {
        val surface = setOf("read_window_tree", "take_screenshot", "get_time_info")

        assertEquals(
            "the stable prefix must not drift between calls with the same surface",
            stableFor(surface = surface),
            stableFor(surface = surface),
        )
    }
}
