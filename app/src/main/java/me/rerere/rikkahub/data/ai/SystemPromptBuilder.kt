package me.rerere.rikkahub.data.ai

/**
 * One "a cheap text alternative exists for this expensive call" routing hint.
 *
 * A rule is meaningful only when the final model-visible tool surface contains at least one of
 * [textReaders] *and* at least one of [costlyAlternatives]. Rules are declarative so a new
 * costly/cheap pair is one list entry rather than another hardcoded sentence, and so the rendered
 * guidance can never name a tool that is absent from the surface it describes.
 */
internal data class ToolCostRoutingRule(
    /** Cheap text-first readers, offered in declared order. */
    val textReaders: List<String>,
    /** Expensive tools the readers should normally precede. */
    val costlyAlternatives: Set<String>,
    /** Rendered only for the readers actually present in the surface. */
    val sentence: (availableTextReaders: List<String>) -> String,
) {
    /** The rendered hint, or null when this rule says nothing useful about [surface]. */
    fun hintFor(surface: Set<String>): String? {
        val available = textReaders.filter { it in surface }
        if (available.isEmpty()) return null
        if (costlyAlternatives.none { it in surface }) return null
        return sentence(available)
    }
}

/** Screenshot-class tools: they spend vision tokens where a text read would have sufficed. */
private val SCREENSHOT_TOOL_NAMES = setOf("take_screenshot", "browser_screenshot")

/** Declarative cost-routing rules. Add a pair here instead of growing a prompt string. */
internal val TOOL_COST_ROUTING_RULES: List<ToolCostRoutingRule> = listOf(
    ToolCostRoutingRule(
        textReaders = listOf("read_window_tree", "browser_get_text"),
        costlyAlternatives = SCREENSHOT_TOOL_NAMES,
        sentence = { readers ->
            "Use ${readers.joinToString(" or ")} before screenshots when text is enough."
        },
    ),
)

/**
 * Tool-cost guidance derived from the model-visible tool surface.
 *
 * Returns "" unless at least one [TOOL_COST_ROUTING_RULES] rule is meaningful for
 * [modelVisibleToolNames]. A surface with no cheap/expensive routing choice -- for example an
 * ordinary assistant exposing only `get_time_info` and `get_screen_time` -- gets no guidance at
 * all, and no rule can name a tool the surface does not contain.
 */
internal fun buildToolCostGuidance(modelVisibleToolNames: Set<String>): String {
    if (modelVisibleToolNames.isEmpty()) return ""
    val hints = TOOL_COST_ROUTING_RULES.mapNotNull { rule -> rule.hintFor(modelVisibleToolNames) }
    if (hints.isEmpty()) return ""
    return buildString {
        append("Tool cost guidance: prefer low-cost text tools before expensive ones.")
        hints.forEach { hint ->
            append(' ')
            append(hint)
        }
        append(" Avoid repeating high-cost tools unless the state likely changed.")
    }
}

/**
 * Single place for assembling the system prompt that is sent to every provider.
 *
 * Callers provide pre-rendered sections so the ordering and formatting live in one spot
 * rather than being reimplemented in GenerationHandler and the provider adapters.
 *
 * Ordering is **stable-first**: the assistant prompt and tool prompts (byte-identical turn
 * to turn) come first, then the volatile sections (user identity, memory, recent chats,
 * per-call addendum) that change between turns. [GenerationHandler] can place the volatile
 * section after persisted history for Chat Completions providers, preserving the long reusable
 * prefix; providers that only read one system instruction still receive the combined prompt.
 */
class SystemPromptBuilder {

    /**
     * Returns the system prompt split into `(stable, volatile)`.
     * - stable: assistant prompt + tool cost guidance + tool prompts.
     * - volatile: user identity + memory + recent chats + per-call addendum.
     * Either may be blank.
     *
     * [modelVisibleToolNames] must be the exact tool set this provider call exposes, so the cost
     * guidance describes the real surface rather than a guessed one.
     */
    fun buildSections(
        assistantPrompt: String,
        memoryPrompt: String = "",
        recentChatsPrompt: String = "",
        toolPrompts: List<String> = emptyList(),
        systemAddendum: String? = null,
        userIdentityPrompt: String = "",
        modelVisibleToolNames: Set<String> = emptySet(),
    ): Pair<String, String> {
        val stable = buildString {
            if (assistantPrompt.isNotBlank()) append(assistantPrompt)
            val toolCostGuidance = buildToolCostGuidance(modelVisibleToolNames)
            if (toolCostGuidance.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                appendLine(toolCostGuidance)
            }
            if (toolPrompts.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                toolPrompts.forEachIndexed { index, toolPrompt ->
                    if (index > 0) appendLine()
                    append(toolPrompt)
                }
            }
        }.trim()

        val volatile = buildString {
            fun appendSection(section: String?) {
                if (section.isNullOrBlank()) return
                if (isNotEmpty()) appendLine()
                append(section)
            }
            appendSection(userIdentityPrompt)
            appendSection(memoryPrompt)
            appendSection(recentChatsPrompt)
            appendSection(systemAddendum)
        }.trim()

        return stable to volatile
    }

    /** Combined single-string prompt (stable then volatile), for callers/providers that do
     *  not support a separate provider-only runtime message. */
    fun build(
        assistantPrompt: String,
        memoryPrompt: String = "",
        recentChatsPrompt: String = "",
        toolPrompts: List<String> = emptyList(),
        systemAddendum: String? = null,
        userIdentityPrompt: String = "",
        modelVisibleToolNames: Set<String> = emptySet(),
    ): String {
        val (stable, volatile) = buildSections(
            assistantPrompt,
            memoryPrompt,
            recentChatsPrompt,
            toolPrompts,
            systemAddendum,
            userIdentityPrompt,
            modelVisibleToolNames,
        )
        return listOf(stable, volatile).filter { it.isNotBlank() }.joinToString("\n")
    }
}
