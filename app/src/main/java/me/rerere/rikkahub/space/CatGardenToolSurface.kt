package me.rerere.rikkahub.space

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext

/**
 * The single decision point for whether the Cat Garden tool surface exists, and what is in it.
 *
 * Both callers that can carry space tools — the interactive chat tool builder
 * (`ChatService`) and the trusted workflow runner (`WorkflowEngine`) — go through here. Neither
 * may re-derive the rule: a second copy of `catGardenEnabled + origin` would be free to drift
 * from this one, and the drift would show up as a workflow that still reaches Cat Garden after
 * the user switched it off.
 *
 * The origin is read from [ToolInvocationContext], which the runtime owns, rather than passed
 * alongside it. A caller therefore cannot evaluate the gate against one origin and build with
 * another.
 */
object CatGardenToolSurface {

    /**
     * Origins permitted to carry the Cat Garden surface.
     *
     * Local chat and the trusted workflow runner only. Remote origins (Telegram, the web server)
     * and external intents remain denied: space writes are persistent, other-visible state, and
     * an origin that is not the person sitting in front of the app must not publish as an
     * assistant. Widening this set is a deliberate act, not a default.
     */
    val ALLOWED_ORIGINS: Set<ToolCallOrigin> = setOf(
        ToolCallOrigin.LocalChat,
        ToolCallOrigin.TrustedWorkflow,
    )

    /**
     * Which space tools this turn's model may see.
     *
     * Fails closed in every direction: no assistant opt-in, or an absent/unknown origin, yields
     * nothing at all — so a disabled assistant is sent no space schema rather than a tool that
     * refuses at call time.
     */
    fun toolNamesFor(
        assistantEnabled: Boolean,
        callOrigin: ToolCallOrigin?,
    ): Set<String> {
        if (!assistantEnabled) return emptySet()
        val origin = callOrigin ?: return emptySet()
        return if (origin in ALLOWED_ORIGINS) SPACE_TOOL_NAMES else emptySet()
    }

    /**
     * Build the space tools for one run, or an empty list when the surface does not apply.
     *
     * The returned list is already narrowed to the names the gate allowed, so a caller that
     * concatenates it into a larger surface cannot widen it by mistake.
     */
    fun build(
        repository: SpaceRepository,
        invocationContext: ToolInvocationContext,
        assistantEnabled: Boolean,
    ): List<Tool> {
        val names = toolNamesFor(assistantEnabled, invocationContext.callOrigin)
        if (names.isEmpty()) return emptyList()
        return createSpaceTools(repository, invocationContext)
            .filter { tool -> tool.name in names }
    }
}
